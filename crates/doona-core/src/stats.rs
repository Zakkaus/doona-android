//! What the screens chart, kept whether or not a screen is open: the rate of the last hour, each
//! session's fastest second, the day's usage and each node's last latency tests. A generation's
//! watch task folds its session's counters in once a second ([`Stats::sample`]), and `stop` folds
//! the last bytes in once the generation has ended. Every store is bounded and takes the time from
//! a [`Clock`], so tests set the time.

pub(crate) mod ring;

use crate::api::lock;
use crate::sys;
use crate::tunnel::Session;
use ring::{Ring, Series};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

/// One moment on the clocks the stores use.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Clock {
    /// `CLOCK_BOOTTIME`: keys the rate ring.
    pub boot_ms: u64,
    pub wall_ms: i64,
    /// The local zone's offset from UTC at `wall_ms`, in seconds.
    pub offset_s: i32,
}

impl Clock {
    pub fn now() -> Clock {
        let wall_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX));
        Clock {
            boot_ms: sys::boot_ms(),
            wall_ms,
            offset_s: sys::utc_offset(wall_ms.div_euclid(1000)),
        }
    }

    pub fn boot_s(&self) -> u64 {
        self.boot_ms / 1000
    }
}

/// What a session moved since its last fold, and the boot seconds the fold covers.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct Fold {
    /// The second of the previous fold, or of the session's start.
    pub from_s: u64,
    pub to_s: u64,
    pub up: u64,
    pub down: u64,
}

pub(crate) struct Stats {
    inner: Mutex<Inner>,
}

impl std::fmt::Debug for Stats {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Stats").finish_non_exhaustive()
    }
}

struct Inner {
    ring: Ring,
}

impl Stats {
    pub fn new() -> Stats {
        Stats {
            inner: Mutex::new(Inner { ring: Ring::new() }),
        }
    }

    /// Folds what `session` moved since its last fold into the stores, and its fastest second
    /// into its peaks.
    pub fn sample(&self, session: &Session, clock: &Clock) {
        let mut inner = lock(&self.inner);
        let fold = session.fold(clock.boot_s());
        let (up, down) = inner.ring.add(fold.from_s, fold.to_s, fold.up, fold.down);
        session.note_peak(up, down);
    }

    /// The rate over the `seconds` that end at the latest folded second while `running` (it is at
    /// most a second old), or at `clock`'s second, and the wall time at the end of that second.
    pub fn series(&self, seconds: u64, running: bool, clock: &Clock) -> (Series, i64) {
        let inner = lock(&self.inner);
        let now = clock.boot_s();
        let end = match inner.ring.latest() {
            Some(latest) if running && latest <= now && latest + 2 >= now => latest,
            _ => now,
        };
        let behind_ms = (clock.boot_ms - end * 1000).saturating_sub(999);
        let end_ms = clock.wall_ms - i64::try_from(behind_ms).unwrap_or(0);
        (inner.ring.series(end, seconds), end_ms)
    }
}
