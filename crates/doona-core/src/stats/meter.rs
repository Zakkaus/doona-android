//! Bytes per outbound, counted where a flow relays its payload. Each flow adds its chunks to a
//! count of its own, one relaxed atomic add per chunk, and each fold of the session takes what
//! every flow moved since the fold before, open or just ended: a flow's bytes count on the local
//! day of the first fold after they moved, however long the flow stays open. A route's target is
//! its group (`g-Proxy` in Global), a node, or [`DIRECT`]. In Rule mode a flow's meter is also
//! that of the rule that routed it, so a fold gives each rule's bytes and the flows opened on it
//! besides each target's bytes, at no cost per chunk.

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

/// One target's flows, or those of one rule to it, open and ended since the last fold.
#[derive(Debug)]
pub(crate) struct Meter {
    id: Arc<str>,
    /// The rule that routed the flows (a row id, or `unmatched`); none outside Rule mode.
    rule: Option<Arc<str>>,
    /// Flows opened since the last fold.
    opened: AtomicU64,
    flows: Mutex<Vec<Arc<Count>>>,
}

impl Meter {
    fn new(id: &str, rule: Option<&str>) -> Meter {
        Meter {
            id: id.into(),
            rule: rule.map(Into::into),
            opened: AtomicU64::new(0),
            flows: Mutex::default(),
        }
    }

    /// What the meter's flows moved since the last fold, each way. A flow that ended is read a
    /// last time and let go.
    fn drain(&self) -> (u64, u64) {
        let (mut up, mut down) = (0, 0);
        lock(&self.flows).retain_mut(|count| {
            // The only owner left: the flow ended, and `get_mut`'s acquire orders its last
            // adds before the reads below.
            let ended = Arc::get_mut(count).is_some();
            up += drain(&count.up);
            down += drain(&count.down);
            !ended
        });
        (up, down)
    }
}

/// A flow's count; the folds read it while the flow is open, and once more after it ends.
#[derive(Debug)]
pub(crate) struct Tally(Option<Arc<Count>>);

impl Tally {
    /// Counts for `meter`; with none, counts nothing (the core's own DNS).
    pub fn new(meter: Option<Arc<Meter>>) -> Tally {
        Tally(meter.map(|m| {
            m.opened.fetch_add(1, Relaxed);
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

/// Each rule's bytes and flows opened, `(id, [up, down, flows])`.
pub(crate) type Rules = Vec<(String, [u64; 3])>;

/// What a fold takes.
#[derive(Debug, Default, PartialEq, Eq)]
pub(crate) struct Taken {
    /// Each target's bytes over all its rules and none.
    pub outbounds: Bytes,
    pub rules: Rules,
}

/// A generation's meters, one per target its flows used, and one per rule to a target.
#[derive(Debug, Default)]
pub(crate) struct Meters(Mutex<Vec<Arc<Meter>>>);

impl Meters {
    /// The meter of target `id` with no rule, made on first use. Once per flow, never per chunk.
    pub fn get(&self, id: &str) -> Arc<Meter> {
        self.find(id, None)
    }

    /// The meter of the flows `rule` sends to `target`, made on first use. Once per flow.
    #[cfg_attr(
        not(test),
        expect(dead_code, reason = "route.rs records the rule once r2-fix2 merges")
    )]
    pub fn get_ruled(&self, target: &str, rule: &str) -> Arc<Meter> {
        self.find(target, Some(rule))
    }

    fn find(&self, id: &str, rule: Option<&str>) -> Arc<Meter> {
        let mut meters = lock(&self.0);
        if let Some(m) = (meters.iter()).find(|m| &*m.id == id && m.rule.as_deref() == rule) {
            return Arc::clone(m);
        }
        let m = Arc::new(Meter::new(id, rule));
        meters.push(Arc::clone(&m));
        m
    }

    /// A fold: each target's bytes since the last one, and each rule's bytes and flows opened,
    /// leaving out those with none.
    pub fn take(&self) -> Taken {
        let meters = lock(&self.0);
        let mut taken = Taken::default();
        for m in meters.iter() {
            let (up, down) = m.drain();
            let opened = drain(&m.opened);
            if let Some(rule) = &m.rule
                && up + down + opened > 0
            {
                taken.rules.push((rule.to_string(), [up, down, opened]));
            }
            if up + down == 0 {
                continue;
            }
            match taken.outbounds.iter_mut().find(|(id, ..)| **id == *m.id) {
                Some((_, u, d)) => {
                    *u += up;
                    *d += down;
                }
                None => taken.outbounds.push((m.id.to_string(), up, down)),
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

    fn proxy(up: u64, down: u64) -> Taken {
        Taken {
            outbounds: vec![("g-Proxy".to_owned(), up, down)],
            rules: Rules::new(),
        }
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
        assert_eq!(meters.take(), Taken::default());
        tally.down(5);
        assert_eq!(meters.take(), proxy(0, 5));
        // The last chunk comes right before the end: the next fold takes it, then lets go.
        tally.up(3);
        drop(tally);
        assert_eq!(meters.take(), proxy(3, 0));
        assert!(lock(&meters.get("g-Proxy").flows).is_empty());
        assert_eq!(meters.take(), Taken::default());
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
        let mut taken = meters.take().outbounds;
        taken.sort();
        assert_eq!(
            taken,
            vec![(DIRECT.to_owned(), 3, 0), ("g-Proxy".to_owned(), 1, 0)]
        );
        // No meter: the bytes go nowhere.
        let none = Tally::new(None);
        none.up(9);
        drop((none, proxy, direct));
        assert_eq!(meters.take(), Taken::default());
    }

    #[test]
    fn rules_to_one_target_add_up_to_its_bytes() {
        let meters = Meters::default();
        let streaming = Tally::new(Some(meters.get_ruled("g-Proxy", "r-1")));
        let matched = Tally::new(Some(meters.get_ruled("g-Proxy", "r-2")));
        let again = Tally::new(Some(meters.get_ruled("g-Proxy", "r-2")));
        let unruled = Tally::new(Some(meters.get("g-Proxy")));
        assert!(Arc::ptr_eq(
            &meters.get_ruled("g-Proxy", "r-2"),
            &meters.get_ruled("g-Proxy", "r-2")
        ));
        streaming.down(100);
        matched.up(10);
        again.down(20);
        unruled.down(1000);
        let taken = meters.take();
        assert_eq!(taken.outbounds, [("g-Proxy".to_owned(), 10, 1120)]);
        assert_eq!(
            taken.rules,
            [
                ("r-1".to_owned(), [0, 100, 1]),
                ("r-2".to_owned(), [10, 20, 2]),
            ]
        );
        // Quiet: nothing to take.
        assert_eq!(meters.take(), Taken::default());
        // A flow opened since counts with its bytes; an ended flow's last bytes are taken once
        // more, and the flow let go.
        let late = Tally::new(Some(meters.get_ruled("g-Proxy", "r-1")));
        late.up(4);
        streaming.down(6);
        drop(streaming);
        let taken = meters.take();
        assert_eq!(taken.outbounds, [("g-Proxy".to_owned(), 4, 6)]);
        assert_eq!(taken.rules, [("r-1".to_owned(), [4, 6, 1])]);
        assert_eq!(lock(&meters.get_ruled("g-Proxy", "r-1").flows).len(), 1);
        assert_eq!(meters.take(), Taken::default());
        // A flow that opened and moved nothing still counts as opened.
        drop(Tally::new(Some(meters.get_ruled(DIRECT, "r-3"))));
        let taken = meters.take();
        assert_eq!(taken.outbounds, Bytes::new());
        assert_eq!(taken.rules, [("r-3".to_owned(), [0, 0, 1])]);
        assert!(lock(&meters.get_ruled(DIRECT, "r-3").flows).is_empty());
        drop((matched, again, unruled, late));
    }
}
