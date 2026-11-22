//! What the screens chart, kept whether or not a screen is open: the rate of the last hour, each
//! session's fastest second, the day's usage and each node's last latency tests. A generation's
//! watch task folds its session's counters in once a second ([`Stats::sample`]), and `stop` folds
//! the last bytes in once the generation has ended. Every store is bounded and takes the time from
//! a [`Clock`], so tests set the time.

pub(crate) mod day;
mod file;
pub(crate) mod meter;
pub(crate) mod ring;

use crate::api::lock;
use crate::dto;
use crate::sys;
use crate::tunnel::Session;
use day::{Day, Moved, Usage};
use file::{STATS_VERSION, SavedRef, StatsFile};
use ring::{Ring, Series};
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::runtime::Handle;

/// Changed usage is written at most this often while a generation runs, besides at a day's
/// rollover and when the generation ends.
const WRITE_EVERY_S: u64 = 60;

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
    /// Flows the apps opened.
    pub flows: u64,
}

pub(crate) struct Stats {
    inner: Mutex<Inner>,
    file: StatsFile,
    /// Runs the writes, off the caller's thread.
    runtime: Handle,
}

impl std::fmt::Debug for Stats {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Stats").finish_non_exhaustive()
    }
}

struct Inner {
    ring: Ring,
    usage: Usage,
    /// The usage changed since the last write was asked for.
    dirty: bool,
    /// The boot second of the last write asked for.
    written_s: Option<u64>,
}

impl Stats {
    /// The stats `dir/stats.json` saved, written on `runtime`'s blocking threads.
    pub fn new(dir: &Path, runtime: Handle) -> Stats {
        let file = StatsFile::new(dir);
        let saved = file.load();
        let inner = Inner {
            ring: Ring::new(),
            usage: saved.usage.sane(),
            dirty: false,
            written_s: None,
        };
        Stats {
            inner: Mutex::new(inner),
            file,
            runtime,
        }
    }

    /// Folds what `session` moved since its last fold into the stores, and its fastest second
    /// into its peaks; writes the usage once a minute while it changes, and at a new day.
    pub fn sample(self: &Arc<Self>, session: &Session, clock: &Clock) {
        if self.fold(session, clock, false) {
            self.save();
        }
    }

    /// The generation of `session` ended: folds its last bytes, and writes what changed.
    pub fn finish(self: &Arc<Self>, session: &Session, clock: &Clock) {
        if self.fold(session, clock, true) {
            self.save();
        }
    }

    /// Folds, and answers whether a write is due; with `now`, one is whenever the usage changed.
    fn fold(&self, session: &Session, clock: &Clock, now: bool) -> bool {
        let mut inner = lock(&self.inner);
        let fold = session.fold(clock.boot_s());
        let (up, down) = inner.ring.add(fold.from_s, fold.to_s, fold.up, fold.down);
        session.note_peak(up, down);
        let moved = Moved {
            up: fold.up,
            down: fold.down,
            flows: fold.flows,
            outbounds: session.meters().take(),
        };
        let rolled = inner.usage.add(clock, &moved);
        inner.dirty |= rolled || moved != Moved::default();
        let stale = (inner.written_s).is_none_or(|w| clock.boot_s() >= w + WRITE_EVERY_S);
        let due = inner.dirty && (now || rolled || stale);
        if due {
            inner.dirty = false;
            inner.written_s = Some(clock.boot_s());
        }
        due
    }

    /// Writes the stats on a blocking thread. Writes run one at a time, each with the data as it
    /// is when its turn comes, so the last one written is the newest.
    fn save(self: &Arc<Self>) {
        let stats = Arc::clone(self);
        self.runtime.spawn_blocking(move || stats.write());
    }

    fn write(&self) {
        self.file.save(|| {
            let inner = lock(&self.inner);
            let saved = SavedRef {
                stats_version: STATS_VERSION,
                usage: &inner.usage,
            };
            serde_json::to_vec(&saved).ok()
        });
    }

    /// Today's bytes each way and yesterday's by the same local time, for `snapshot`.
    pub fn today(&self, clock: &Clock) -> dto::Today {
        let (up_total, down_total, yesterday_by_now) = lock(&self.inner).usage.today(clock);
        dto::Today {
            up_total,
            down_total,
            yesterday_by_now,
        }
    }

    /// `usage`'s reply: today and yesterday by the hour, and the totals of the days before.
    pub fn usage(&self, clock: &Clock) -> dto::Usage {
        let now = day::local(clock).0;
        let view = lock(&self.inner).usage.view(now);
        let empty = Day::new(now);
        dto::Usage {
            today: day_usage(view.today.as_ref().unwrap_or(&empty)),
            yesterday: view.yesterday.as_ref().map(day_usage),
            days: (view.days.iter())
                .map(|t| dto::DayTotal {
                    date: day::date(t.day),
                    up: t.up,
                    down: t.down,
                })
                .collect(),
        }
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

fn day_usage(d: &Day) -> dto::DayUsage {
    dto::DayUsage {
        date: day::date(d.day),
        up: Day::hours(&d.up),
        down: Day::hours(&d.down),
        flows: d.flows.clone(),
        outbounds: (d.outbounds.iter())
            .map(|(id, [up, down])| dto::OutboundBytes {
                id: id.clone(),
                up: *up,
                down: *down,
            })
            .collect(),
    }
}

#[cfg(test)]
mod tests;
