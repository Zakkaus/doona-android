use super::{Opened, Queues, Session, Tally, UdpUpstream, millis};
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
    fn send_timeout(&self, idle: Duration) -> Duration {
        match self {
            Self::Proxy(t, ..) => t.send_timeout(),
            Self::Socket(_) => idle,
        }
    }

    fn send_timeout_outcome(&self) -> ScoreOutcome {
        match self {
            Self::Proxy(t, ..) if t.send_timeout_is_congestion() => {
                self.terminal_outcome(ScoreOutcome::Cancelled)
            }
            _ => ScoreOutcome::Timeout,
        }
    }

    fn idle_outcome(
        &self,
        error: Option<ScoreOutcome>,
        replied: bool,
        queues: &Queues,
    ) -> ScoreOutcome {
        match error {
            Some(ScoreOutcome::Timeout) => self.send_timeout_outcome(),
            Some(e) => e,
            None if replied => ScoreOutcome::Success,
            None if queues.local_drop.load(Relaxed) => ScoreOutcome::Cancelled,
            None => ScoreOutcome::Timeout,
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
) -> ScoreOutcome {
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
            let timeout = upstream.send_timeout(idle);
            let Ok(sent) = tokio::time::timeout(timeout, upstream.send(&d.bytes)).await else {
                let outcome = upstream.send_timeout_outcome();
                if outcome != ScoreOutcome::Cancelled {
                    return outcome;
                }
                queues.local_drop.store(true, Relaxed);
                continue;
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
                queues.local_drop.store(true, Relaxed);
            }
            moved();
        }
    };
    let outcome = tokio::select! {
        // If both deadlines expire together, the send has exhausted its own deadline.
        biased;
        outcome = sending => outcome,
        outcome = receiving => outcome,
        () = reply_idle(begun, &last, idle) => upstream.idle_outcome(send_error, replied, &queues),
    };
    upstream.terminal_outcome(outcome)
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
