//! Bytes per outbound, counted where a flow relays its payload: a flow adds to its own [`Tally`]
//! and hands the sum to the shared [`Meter`] of its route's target every [`FLUSH`] bytes and when
//! it ends, so no datagram or chunk pays for an atomic. A route's target is its group (`g-Proxy`
//! in Global), a node, or [`DIRECT`]; a rule mode adds targets, not a new shape.

use crate::api::lock;
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use std::sync::{Arc, Mutex};

/// The target of a flow that goes straight out.
pub(crate) const DIRECT: &str = "direct";
/// A flow's tally hands its bytes over once it holds this many.
const FLUSH: u64 = 64 * 1024;

/// One target's bytes not yet folded into the day.
#[derive(Debug)]
pub(crate) struct Meter {
    id: Arc<str>,
    up: AtomicU64,
    down: AtomicU64,
}

/// A flow's bytes not yet handed to its meter.
#[derive(Debug, Default)]
pub(crate) struct Tally {
    meter: Option<Arc<Meter>>,
    up: u64,
    down: u64,
}

impl Tally {
    /// Counts for `meter`; with none, counts nothing (the core's own DNS).
    pub fn new(meter: Option<Arc<Meter>>) -> Tally {
        Tally {
            meter,
            up: 0,
            down: 0,
        }
    }

    /// `n` bytes from the app toward the upstream.
    pub fn up(&mut self, n: usize) {
        self.up += n as u64;
        if self.up >= FLUSH {
            self.flush();
        }
    }

    /// `n` bytes from the upstream toward the app.
    pub fn down(&mut self, n: usize) {
        self.down += n as u64;
        if self.down >= FLUSH {
            self.flush();
        }
    }

    fn flush(&mut self) {
        if let Some(m) = &self.meter {
            m.up.fetch_add(std::mem::take(&mut self.up), Relaxed);
            m.down.fetch_add(std::mem::take(&mut self.down), Relaxed);
        }
    }
}

impl Drop for Tally {
    fn drop(&mut self) {
        self.flush();
    }
}

/// A generation's meters, one per target its flows used.
#[derive(Debug, Default)]
pub(crate) struct Meters(Mutex<Vec<Arc<Meter>>>);

impl Meters {
    /// The meter of target `id`, made on first use. Once per flow, never per chunk.
    pub fn get(&self, id: &str) -> Arc<Meter> {
        let mut meters = lock(&self.0);
        if let Some(m) = meters.iter().find(|m| &*m.id == id) {
            return Arc::clone(m);
        }
        let m = Arc::new(Meter {
            id: id.into(),
            up: AtomicU64::new(0),
            down: AtomicU64::new(0),
        });
        meters.push(Arc::clone(&m));
        m
    }

    /// Each target's bytes since the last call, `(id, up, down)`, leaving out targets with none.
    pub fn take(&self) -> Vec<(String, u64, u64)> {
        lock(&self.0)
            .iter()
            .map(|m| {
                (
                    m.id.to_string(),
                    m.up.swap(0, Relaxed),
                    m.down.swap(0, Relaxed),
                )
            })
            .filter(|&(_, up, down)| up + down > 0)
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_tally_hands_over_in_batches_and_when_dropped() {
        let meters = Meters::default();
        let mut tally = Tally::new(Some(meters.get("g-Proxy")));
        tally.up(100);
        tally.down(FLUSH as usize - 1);
        // Below the batch: nothing reached the meter yet.
        assert!(meters.take().is_empty());
        tally.down(1);
        assert_eq!(meters.take(), vec![("g-Proxy".to_owned(), 100, FLUSH)]);
        tally.up(5);
        drop(tally);
        assert_eq!(meters.take(), vec![("g-Proxy".to_owned(), 5, 0)]);
        assert!(meters.take().is_empty());
    }

    #[test]
    fn one_meter_per_target() {
        let meters = Meters::default();
        let (a, b) = (meters.get(DIRECT), meters.get(DIRECT));
        assert!(Arc::ptr_eq(&a, &b));
        drop(Tally {
            meter: Some(meters.get("g-Proxy")),
            up: 1,
            down: 2,
        });
        drop(Tally {
            meter: Some(a),
            up: 3,
            down: 0,
        });
        let mut taken = meters.take();
        taken.sort();
        assert_eq!(
            taken,
            vec![(DIRECT.to_owned(), 3, 0), ("g-Proxy".to_owned(), 1, 2)]
        );
        // No meter: the bytes go nowhere.
        drop(Tally {
            meter: None,
            up: 9,
            down: 9,
        });
    }
}
