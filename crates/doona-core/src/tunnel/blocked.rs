//! The UDP five-tuples `blockQuic` refused lately. An app retransmits a refused QUIC Initial to the
//! same address and port, and each retransmit would look its name up and run the rules again to
//! be refused the same way, so for [`HOLD`] after a refusal the driver refuses that five-tuple at
//! once. A mode change or a new routing (a policy or a selection) drops every refusal held. At most
//! [`MAX`] are held; nothing runs on a timer: a refusal past its time is dropped when it is looked
//! up, or when the table is full.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::time::{Duration, Instant};

/// How long a refusal holds: QUIC's first retransmits come within it.
const HOLD: Duration = Duration::from_secs(5);
/// The most five-tuples held. When the table is full of live refusals, a new one is not held, and
/// its retransmits are routed again.
const MAX: usize = 256;

/// The routing a refusal was decided under: how many times the mode was set, and how many routings
/// were published.
pub(crate) type Stamp = (u64, u64);

/// A client address and the remote it sent to.
type Pair = (SocketAddr, SocketAddr);

pub(crate) struct Blocked {
    stamp: Stamp,
    until: HashMap<Pair, Instant>,
}

impl Blocked {
    pub fn new() -> Blocked {
        Blocked {
            stamp: (0, 0),
            until: HashMap::new(),
        }
    }

    /// Whether `pair` was refused under `stamp`'s routing, less than [`HOLD`] before `now`.
    pub fn holds(&mut self, pair: Pair, stamp: Stamp, now: Instant) -> bool {
        self.renew(stamp);
        match self.until.get(&pair) {
            Some(&until) if until > now => true,
            Some(_) => {
                self.until.remove(&pair);
                false
            }
            None => false,
        }
    }

    /// Holds the refusal of `pair` under `stamp`'s routing, from `now`.
    pub fn refuse(&mut self, pair: Pair, stamp: Stamp, now: Instant) {
        self.renew(stamp);
        if self.until.len() >= MAX && !self.until.contains_key(&pair) {
            self.until.retain(|_, until| *until > now);
            if self.until.len() >= MAX {
                return;
            }
        }
        self.until.insert(pair, now + HOLD);
    }

    /// Drops the refusals held under another routing than `stamp`'s.
    fn renew(&mut self, stamp: Stamp) {
        if self.stamp != stamp {
            self.until.clear();
            self.stamp = stamp;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pair(port: u16) -> Pair {
        (([10, 0, 0, 2], port).into(), ([104, 16, 0, 1], 443).into())
    }

    #[test]
    fn a_refusal_holds_for_its_time_under_its_routing() {
        let (mut b, now) = (Blocked::new(), Instant::now());
        b.refuse(pair(1), (1, 1), now);
        assert!(b.holds(pair(1), (1, 1), now + HOLD - Duration::from_millis(1)));
        assert!(!b.holds(pair(2), (1, 1), now));
        assert!(!b.holds(pair(1), (1, 1), now + HOLD));
        // Past its time, it is gone.
        assert!(b.until.is_empty());
        // A new mode or routing drops every refusal.
        for stamp in [(2, 1), (1, 2)] {
            b.refuse(pair(1), (1, 1), now);
            assert!(!b.holds(pair(1), stamp, now), "{stamp:?}");
            assert!(!b.holds(pair(1), (1, 1), now), "{stamp:?}");
        }
    }

    #[test]
    fn a_full_table_drops_what_ran_out_and_holds_no_more() {
        let (mut b, now) = (Blocked::new(), Instant::now());
        for port in 0..MAX as u16 {
            b.refuse(pair(port), (0, 0), now);
        }
        // Full of live refusals: a new one is not held, an old one is renewed.
        let later = now + Duration::from_secs(1);
        b.refuse(pair(MAX as u16), (0, 0), later);
        assert!(!b.holds(pair(MAX as u16), (0, 0), later));
        b.refuse(pair(0), (0, 0), later);
        assert!(b.holds(pair(0), (0, 0), now + HOLD));
        assert_eq!(b.until.len(), MAX);
        // Once they ran out, room is made.
        b.refuse(pair(MAX as u16), (0, 0), now + HOLD);
        assert_eq!(b.until.len(), 2);
        assert!(b.holds(pair(MAX as u16), (0, 0), now + HOLD));
    }
}
