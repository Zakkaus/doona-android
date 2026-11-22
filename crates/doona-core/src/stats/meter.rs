//! Bytes per outbound, counted where a flow relays its payload. Each flow adds its chunks to a
//! count of its own, one relaxed atomic add per chunk, and each fold of the session takes what
//! every flow moved since the fold before, open or just ended: a flow's bytes count on the local
//! day of the first fold after they moved, however long the flow stays open. A route's target is
//! its group (`g-Proxy` in Global), a node, or [`DIRECT`]; a rule mode adds targets, not a new
//! shape.

use crate::api::lock;
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use std::sync::{Arc, Mutex};

/// The target of a flow that goes straight out.
pub(crate) const DIRECT: &str = "direct";

/// One flow's bytes since the fold before. On a cache line of its own, so flows relayed on other
/// threads never write to it.
#[derive(Debug, Default)]
#[repr(align(64))]
struct Count {
    up: AtomicU64,
    down: AtomicU64,
}

/// One target's flows, those open and those that ended since the last fold.
#[derive(Debug)]
pub(crate) struct Meter {
    id: Arc<str>,
    flows: Mutex<Vec<Arc<Count>>>,
}

/// A flow's count; the folds read it while the flow is open, and once more after it ends.
#[derive(Debug)]
pub(crate) struct Tally(Option<Arc<Count>>);

impl Tally {
    /// Counts for `meter`; with none, counts nothing (the core's own DNS).
    pub fn new(meter: Option<Arc<Meter>>) -> Tally {
        Tally(meter.map(|m| {
            let count = Arc::new(Count::default());
            lock(&m.flows).push(Arc::clone(&count));
            count
        }))
    }

    /// `n` bytes from the app toward the upstream.
    pub fn up(&self, n: usize) {
        if let Some(c) = &self.0 {
            c.up.fetch_add(n as u64, Relaxed);
        }
    }

    /// `n` bytes from the upstream toward the app.
    pub fn down(&self, n: usize) {
        if let Some(c) = &self.0 {
            c.down.fetch_add(n as u64, Relaxed);
        }
    }
}

/// Each target's bytes, `(id, up, down)`.
pub(crate) type Bytes = Vec<(String, u64, u64)>;

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
            flows: Mutex::default(),
        });
        meters.push(Arc::clone(&m));
        m
    }

    /// A fold: each target's bytes since the last one, leaving out targets with none. A flow
    /// that ended is read a last time and let go.
    pub fn take(&self) -> Bytes {
        let meters = lock(&self.0);
        let mut taken = Bytes::new();
        for m in meters.iter() {
            let (mut up, mut down) = (0, 0);
            lock(&m.flows).retain_mut(|count| {
                // The only owner left: the flow ended, and `get_mut`'s acquire orders its last
                // adds before the reads below.
                let ended = Arc::get_mut(count).is_some();
                up += drain(&count.up);
                down += drain(&count.down);
                !ended
            });
            if up + down > 0 {
                taken.push((m.id.to_string(), up, down));
            }
        }
        taken
    }
}

/// Takes what `bytes` holds. A load first leaves a quiet flow's cache line alone.
fn drain(bytes: &AtomicU64) -> u64 {
    if bytes.load(Relaxed) == 0 {
        0
    } else {
        bytes.swap(0, Relaxed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn proxy(up: u64, down: u64) -> Bytes {
        vec![("g-Proxy".to_owned(), up, down)]
    }

    #[test]
    fn each_fold_takes_what_a_flow_moved_while_open_and_after_it_ended() {
        let meters = Meters::default();
        let tally = Tally::new(Some(meters.get("g-Proxy")));
        tally.up(1);
        tally.up(32 * 1024);
        tally.down(7);
        assert_eq!(meters.take(), proxy(32 * 1024 + 1, 7));
        // Quiet: nothing to take, and the flow stays.
        assert_eq!(meters.take(), Bytes::new());
        tally.down(5);
        assert_eq!(meters.take(), proxy(0, 5));
        // The last chunk comes right before the end: the next fold takes it, then lets go.
        tally.up(3);
        drop(tally);
        assert_eq!(meters.take(), proxy(3, 0));
        assert!(lock(&meters.get("g-Proxy").flows).is_empty());
        assert_eq!(meters.take(), Bytes::new());
    }

    #[test]
    fn one_meter_per_target() {
        let meters = Meters::default();
        let (a, b) = (meters.get(DIRECT), meters.get(DIRECT));
        assert!(Arc::ptr_eq(&a, &b));
        let proxy = Tally::new(Some(meters.get("g-Proxy")));
        let direct = Tally::new(Some(a));
        proxy.up(1);
        direct.up(3);
        let mut taken = meters.take();
        taken.sort();
        assert_eq!(
            taken,
            vec![(DIRECT.to_owned(), 3, 0), ("g-Proxy".to_owned(), 1, 0)]
        );
        // No meter: the bytes go nowhere.
        let none = Tally::new(None);
        none.up(9);
        drop((none, proxy, direct));
        assert_eq!(meters.take(), Bytes::new());
    }
}
