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

impl UdpUpstream {
    fn send_outcome(&self, outcome: ScoreOutcome) -> ScoreOutcome {
        if outcome == ScoreOutcome::Timeout
            && matches!(self, Self::Proxy(t, ..) if t.send_timeout_is_congestion())
        {
            ScoreOutcome::Cancelled
        } else {
            outcome
        }
    }

    fn terminal_outcome(&self, outcome: ScoreOutcome) -> ScoreOutcome {
        if !matches!(outcome, ScoreOutcome::Shutdown | ScoreOutcome::Rejected)
            && matches!(self, Self::Proxy(t, ..) if t.quic_path_stalled())
        {
            ScoreOutcome::NodeFailure
        } else {
            outcome
        }
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
        waker,
        budget,
        idle,
        ..
    } = o;
    let begun = Instant::now();
    let last = AtomicU64::new(0);
    let moved = || last.store(millis(begun.elapsed()), Relaxed);
    let mut send_error = None;
    let mut reply_idle_outcome = ScoreOutcome::Timeout;
    let sending = async {
        let mut first = upstream.first_send_to();
        while let Some(d) = up.recv().await {
            let began = first.map(Session::begin_dial);
            let timed = reporter.map(|r| (r, std::time::Instant::now()));
            // The association idle deadline can expire before this send's own deadline.
            send_error = Some(ScoreOutcome::Timeout);
            let Ok(sent) = tokio::time::timeout(idle, upstream.send(&d.bytes)).await else {
                return upstream.send_outcome(ScoreOutcome::Timeout);
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
                    if reply_idle_outcome != ScoreOutcome::Success {
                        r.first_response();
                    }
                    r.rx(n as u64);
                }
                reply_idle_outcome = ScoreOutcome::Success;
                tally.down(n);
                waker.wake();
            } else if reply_idle_outcome != ScoreOutcome::Success {
                reply_idle_outcome = ScoreOutcome::Cancelled;
            }
            moved();
        }
    };
    let outcome = tokio::select! {
        outcome = sending => outcome,
        outcome = receiving => outcome,
        () = reply_idle(begun, &last, idle) => {
            send_error.map_or(reply_idle_outcome, |e| upstream.send_outcome(e))
        },
    };
    if let Some(r) = reporter {
        r.finish(upstream.terminal_outcome(outcome));
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
