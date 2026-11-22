//! Bytes per outbound, counted where a flow relays its payload: a flow adds to its own [`Tally`]
//! and hands the sum to the shared [`Meter`] of its route's target, so a chunk pays for no atomic
//! write. A tally hands over the first chunk after each fold of the session, which costs each
//! chunk one relaxed load to notice, then every [`FLUSH`] bytes and when it ends: a quiet flow
//! shows at the next fold, and a busy one holds at most a second's chunks. What it held from
//! before the fold that changed the local day is handed over as late, to count on that earlier
//! day. A route's target is its group (`g-Proxy` in Global), a node, or [`DIRECT`]; a rule mode
//! adds targets, not a new shape.

use crate::api::lock;
use std::mem::take;
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use std::sync::{Arc, Mutex};

/// The target of a flow that goes straight out.
pub(crate) const DIRECT: &str = "direct";
/// A tally hands its bytes over once it holds this many.
const FLUSH: u64 = 64 * 1024;

/// The session's folds so far (low half), and the first fold whose bytes count on the current
/// local day (high half), in one word so a tally reads both with one load.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
struct Tick(u64);

impl Tick {
    fn fold(self) -> u32 {
        self.0 as u32
    }

    fn day_from(self) -> u32 {
        (self.0 >> 32) as u32
    }

    /// The tick after a fold; with `turned`, the fold changed the local day, and what moved
    /// before the fold it ends counted on the day before.
    fn next(self, turned: bool) -> Tick {
        let from = if turned { self.fold() } else { self.day_from() };
        Tick((u64::from(from) << 32) | u64::from(self.fold().wrapping_add(1)))
    }
}

/// One target's bytes not yet folded into the day: those of the current day, and the late ones.
#[derive(Debug)]
pub(crate) struct Meter {
    id: Arc<str>,
    tick: AtomicU64,
    up: AtomicU64,
    down: AtomicU64,
    late_up: AtomicU64,
    late_down: AtomicU64,
}

impl Meter {
    fn hand(&self, late: bool, up: u64, down: u64) {
        let (to_up, to_down) = if late {
            (&self.late_up, &self.late_down)
        } else {
            (&self.up, &self.down)
        };
        if up > 0 {
            to_up.fetch_add(up, Relaxed);
        }
        if down > 0 {
            to_down.fetch_add(down, Relaxed);
        }
    }
}

/// A flow's bytes not yet handed to its meter.
#[derive(Debug)]
pub(crate) struct Tally {
    meter: Option<Arc<Meter>>,
    up: u64,
    down: u64,
    /// The fold the held bytes moved after; none yet before the first chunk.
    fold: u32,
}

impl Tally {
    /// Counts for `meter`; with none, counts nothing (the core's own DNS).
    pub fn new(meter: Option<Arc<Meter>>) -> Tally {
        Tally {
            meter,
            up: 0,
            down: 0,
            fold: u32::MAX,
        }
    }

    /// `n` bytes from the app toward the upstream.
    pub fn up(&mut self, n: usize) {
        self.add(n as u64, 0);
    }

    /// `n` bytes from the upstream toward the app.
    pub fn down(&mut self, n: usize) {
        self.add(0, n as u64);
    }

    fn add(&mut self, up: u64, down: u64) {
        let Some(meter) = self.meter.as_deref() else {
            return;
        };
        let tick = Tick(meter.tick.load(Relaxed));
        if tick.fold() != self.fold {
            // A fold came since the last chunk: what the tally holds moved before it.
            let late = self.fold < tick.day_from();
            meter.hand(late, take(&mut self.up), take(&mut self.down));
            self.fold = tick.fold();
            meter.hand(false, up, down);
            return;
        }
        self.up += up;
        self.down += down;
        if self.up >= FLUSH || self.down >= FLUSH {
            meter.hand(false, take(&mut self.up), take(&mut self.down));
        }
    }
}

impl Drop for Tally {
    fn drop(&mut self) {
        if let Some(meter) = &self.meter {
            let late = self.fold < Tick(meter.tick.load(Relaxed)).day_from();
            meter.hand(late, self.up, self.down);
        }
    }
}

/// Each target's bytes, `(id, up, down)`.
pub(crate) type Bytes = Vec<(String, u64, u64)>;

/// What the meters held at a fold, leaving out targets with none.
#[derive(Debug, Default, PartialEq, Eq)]
pub(crate) struct Taken {
    pub now: Bytes,
    /// Bytes that moved before the local day last changed.
    pub late: Bytes,
}

/// A generation's meters, one per target its flows used.
#[derive(Debug, Default)]
pub(crate) struct Meters(Mutex<Hub>);

#[derive(Debug, Default)]
struct Hub {
    tick: Tick,
    meters: Vec<Arc<Meter>>,
}

impl Meters {
    /// The meter of target `id`, made on first use. Once per flow, never per chunk.
    pub fn get(&self, id: &str) -> Arc<Meter> {
        let mut hub = lock(&self.0);
        if let Some(m) = hub.meters.iter().find(|m| &*m.id == id) {
            return Arc::clone(m);
        }
        let m = Arc::new(Meter {
            id: id.into(),
            tick: AtomicU64::new(hub.tick.0),
            up: AtomicU64::new(0),
            down: AtomicU64::new(0),
            late_up: AtomicU64::new(0),
            late_down: AtomicU64::new(0),
        });
        hub.meters.push(Arc::clone(&m));
        m
    }

    /// A fold: each target's bytes since the last one. Tallies hand over what they hold at their
    /// next chunk; with `turned`, the local day changed, and what they hold from before the fold
    /// this one ends comes as late.
    pub fn take(&self, turned: bool) -> Taken {
        let mut hub = lock(&self.0);
        let tick = hub.tick.next(turned);
        hub.tick = tick;
        let mut taken = Taken::default();
        for m in &hub.meters {
            m.tick.store(tick.0, Relaxed);
            swap(&mut taken.now, &m.id, &m.up, &m.down);
            swap(&mut taken.late, &m.id, &m.late_up, &m.late_down);
        }
        taken
    }
}

fn swap(into: &mut Bytes, id: &str, up: &AtomicU64, down: &AtomicU64) {
    let (up, down) = (up.swap(0, Relaxed), down.swap(0, Relaxed));
    if up + down > 0 {
        into.push((id.to_owned(), up, down));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn proxy(up: u64, down: u64) -> Bytes {
        vec![("g-Proxy".to_owned(), up, down)]
    }

    #[test]
    fn a_tally_hands_over_after_a_fold_in_batches_and_when_dropped() {
        let meters = Meters::default();
        let mut tally = Tally::new(Some(meters.get("g-Proxy")));
        // The first chunk goes at once, so a quiet flow shows at the next fold; the next ones of
        // the same fold are held.
        tally.up(100);
        tally.down(FLUSH as usize - 1);
        assert_eq!(meters.take(false).now, proxy(100, 0));
        // A fold came: the held bytes go with the next chunk.
        tally.down(1);
        assert_eq!(meters.take(false).now, proxy(0, FLUSH));
        // A batch goes once it is full.
        tally.up(5);
        tally.up(FLUSH as usize);
        assert_eq!(meters.take(false).now, proxy(5 + FLUSH, 0));
        tally.up(1);
        tally.up(2);
        drop(tally);
        assert_eq!(
            meters.take(false),
            Taken {
                now: proxy(3, 0),
                late: vec![]
            }
        );
        assert_eq!(meters.take(false), Taken::default());
    }

    #[test]
    fn bytes_held_across_a_change_of_day_come_as_late() {
        let meters = Meters::default();
        let mut tally = Tally::new(Some(meters.get("g-Proxy")));
        meters.take(false);
        tally.down(10);
        // Held after fold 1; fold 2 is the old day's last.
        tally.down(20);
        assert_eq!(meters.take(false).now, proxy(0, 10));
        // Fold 3 is the new day's first: what moved after fold 2 counts on the new day by a
        // fold's measure, what moved before it on the old day.
        meters.take(true);
        tally.down(5);
        assert_eq!(
            meters.take(false),
            Taken {
                now: proxy(0, 5),
                late: proxy(0, 20)
            }
        );
        // Held after fold 4, handed over after the day changed at fold 5: not late.
        tally.down(1);
        tally.down(7);
        assert_eq!(meters.take(true).now, proxy(0, 1));
        drop(tally);
        assert_eq!(meters.take(false).now, proxy(0, 7));
    }

    #[test]
    fn one_meter_per_target() {
        let meters = Meters::default();
        let (a, b) = (meters.get(DIRECT), meters.get(DIRECT));
        assert!(Arc::ptr_eq(&a, &b));
        let mut proxy = Tally::new(Some(meters.get("g-Proxy")));
        let mut direct = Tally::new(Some(a));
        proxy.up(1);
        direct.up(3);
        let mut taken = meters.take(false).now;
        taken.sort();
        assert_eq!(
            taken,
            vec![(DIRECT.to_owned(), 3, 0), ("g-Proxy".to_owned(), 1, 0)]
        );
        // No meter: the bytes go nowhere.
        let mut none = Tally::new(None);
        none.up(9);
        drop((none, proxy, direct));
        assert_eq!(meters.take(false), Taken::default());
    }
}
