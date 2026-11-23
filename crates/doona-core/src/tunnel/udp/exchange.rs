use super::{Opened, Session, Tally, UdpUpstream, millis};
use honk_outbound::group::{ScoreOutcome, ScoreReporter};
use honk_outbound::proxy::{PacketErrorClass, packet_error_class};
use std::io;
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use std::time::Duration;
use tokio::time::Instant;

fn packet_outcome(error: &io::Error) -> ScoreOutcome {
    if packet_error_class(error) == PacketErrorClass::Congestion {
        ScoreOutcome::Cancelled
    } else {
        ScoreOutcome::from_io_error(error)
    }
}

/// Poll each direction continuously: cancelling a framed transport mid-datagram loses framing.
pub(super) async fn exchange(
    o: Opened,
    upstream: &UdpUpstream,
    tally: Tally,
    reporter: Option<&ScoreReporter>,
) {
    let Opened {
        mut up,
        down,
        queues,
        budget,
        idle,
        ..
    } = o;
    let begun = Instant::now();
    let last = AtomicU64::new(0);
    let moved = || last.store(millis(begun.elapsed()), Relaxed);
    let mut send_error = None;
    let mut replied = false;
    let sending = async {
        let mut first = upstream.first_send_to();
        while let Some(d) = up.recv().await {
            let began = first.map(Session::begin_dial);
            let timed = reporter.map(|r| (r, std::time::Instant::now()));
            let Ok(sent) = tokio::time::timeout(idle, upstream.send(&d.bytes)).await else {
                return ScoreOutcome::Timeout;
            };
            if let (Some(session), Some(began)) = (first.take(), began) {
                session.first_sent(began, &sent);
            }
            send_error = sent.err().map(|e| packet_outcome(&e));
            if send_error.is_none() {
                tally.up(d.bytes.len());
                if let Some((r, started)) = timed {
                    r.tx_completed(d.bytes.len() as u64, started);
                }
            }
            moved();
        }
        ScoreOutcome::Shutdown
    };
    let receiving = async {
        let mut own = Vec::new();
        loop {
            let d = match upstream.recv(&mut own, &budget).await {
                Ok(d) => d,
                Err(e) => return packet_outcome(&e),
            };
            let n = d.as_ref().map_or(0, |d| d.bytes.len());
            if d.is_some_and(|d| down.try_send(d).is_ok()) {
                if let Some(r) = reporter {
                    if !replied {
                        r.first_response();
                    }
                    r.rx(n as u64);
                }
                replied = true;
                tally.down(n);
                queues.waker.wake();
            } else {
                queues.dropped.store(true, Relaxed);
            }
            moved();
        }
    };
    let outcome = tokio::select! {
        outcome = sending => outcome,
        outcome = receiving => outcome,
        () = reply_idle(begun, &last, idle) => send_error.unwrap_or_else(|| {
            if replied { ScoreOutcome::Success }
            else if queues.dropped.load(Relaxed) { ScoreOutcome::Cancelled }
            else { ScoreOutcome::Timeout }
        }),
    };
    if let Some(r) = reporter {
        r.finish(outcome);
    }
}

async fn reply_idle(begun: Instant, last: &AtomicU64, idle: Duration) {
    loop {
        let until = begun + Duration::from_millis(last.load(Relaxed)) + idle;
        if Instant::now() >= until {
            return;
        }
        tokio::time::sleep_until(until).await;
    }
}
