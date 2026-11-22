//! What the screens chart, kept whether or not a screen is open: the rate of the last hour, each
//! session's fastest second, the day's usage and each node's last latency tests. A generation's
//! watch task folds its session's counters in once a second ([`Stats::sample`]), and `stop` folds
//! the last bytes in once the generation has ended. Every store is bounded and takes the time from
//! a [`Clock`], so tests set the time.

pub(crate) mod day;
mod file;
pub(crate) mod latency;
pub(crate) mod meter;
pub(crate) mod ring;

use crate::api::lock;
use crate::dto;
use crate::sys;
use crate::tunnel::Session;
use day::{Day, Moved, Usage};
use file::{LatencyFile, STATS_VERSION, StatsFile, UsageFile};
use latency::Latency;
use ring::{Ring, Series};
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::runtime::Handle;

/// Changed usage is written at most this often while a generation runs, besides when the local
/// day changes and when the generation ends; a failed write is asked again this often.
const WRITE_EVERY_S: u64 = 60;

/// One moment on the clocks the stores use.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Clock {
    /// `CLOCK_BOOTTIME`: keys the rate ring.
    pub boot_ms: u64,
    /// `CLOCK_MONOTONIC`: what the boot clock counted beyond it, the phone slept.
    pub mono_ms: u64,
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
            mono_ms: sys::mono_ms(),
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
    /// The monotonic time at the fold.
    pub mono_ms: u64,
    /// The seconds of `from_s..to_s` the phone was awake, rounded up: fewer when it slept.
    pub awake_s: u64,
    pub up: u64,
    pub down: u64,
    /// Flows the apps opened.
    pub flows: u64,
}

pub(crate) struct Stats {
    inner: Mutex<Inner>,
    usage_file: StatsFile,
    latency_file: StatsFile,
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
    /// The local day the last fold counted on, and the one before the last change of day: the
    /// outbound bytes that come late moved on it.
    day: Option<i64>,
    previous: Option<i64>,
    usage_writes: Pending,
    latency: Latency,
    latency_writes: Pending,
}

/// The writes a fold asks for.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct Writes {
    pub usage: bool,
    pub latency: bool,
}

/// What one file has not written yet. A write that fails leaves it so, to be asked again.
#[derive(Debug, Default)]
struct Pending {
    /// Changes so far; the last write asked for held up to `asked`, the last good one `saved`.
    changes: u64,
    asked: u64,
    saved: u64,
    /// The boot second the last write was asked for.
    asked_s: Option<u64>,
}

impl Pending {
    /// Asks for a write at boot second `now_s` when something is not written yet, and `at_once`
    /// or a minute passed since the last one was asked for.
    fn due(&mut self, now_s: u64, at_once: bool) -> bool {
        let stale = self.asked_s.is_none_or(|a| now_s >= a + WRITE_EVERY_S);
        let due = self.changes > self.saved && (at_once || stale);
        if due {
            self.asked = self.changes;
            self.asked_s = Some(now_s);
        }
        due
    }

    /// Asks again for a write that was asked for and did not succeed (or still runs) a minute on.
    fn retry(&mut self, now_s: u64) -> bool {
        self.saved < self.asked && self.due(now_s, false)
    }
}

impl Stats {
    /// The stats saved in `dir`, written on `runtime`'s blocking threads.
    pub fn new(dir: &Path, runtime: Handle) -> Stats {
        let usage_file = StatsFile::new(dir, file::USAGE);
        let latency_file = StatsFile::new(dir, file::LATENCY);
        let inner = Inner {
            ring: Ring::new(),
            usage: usage_file.load::<UsageFile>().usage.sane(),
            day: None,
            previous: None,
            usage_writes: Pending::default(),
            latency: Latency::from_saved(latency_file.load::<LatencyFile>().latency),
            latency_writes: Pending::default(),
        };
        Stats {
            inner: Mutex::new(inner),
            usage_file,
            latency_file,
            runtime,
        }
    }

    /// Folds what `session` moved since its last fold into the stores, and its fastest second
    /// into its peaks; writes the usage once a minute while it changes, and when the day changes.
    pub fn sample(self: &Arc<Self>, session: &Session, clock: &Clock) {
        self.save(self.fold(session, clock, false));
    }

    /// The generation of `session` ended: folds its last bytes, and writes what changed.
    pub fn finish(self: &Arc<Self>, session: &Session, clock: &Clock) {
        self.save(self.fold(session, clock, true));
    }

    /// Folds, and answers which writes are due: the usage's once a minute while it changes, at
    /// once when the day changed or with `now`; a failed one again a minute on.
    fn fold(&self, session: &Session, clock: &Clock, now: bool) -> Writes {
        let mut inner = lock(&self.inner);
        let day = day::local(clock).0;
        let last = inner.day.replace(day);
        let turned = last.is_some_and(|d| d != day);
        if turned {
            inner.previous = last;
        }
        let fold = session.fold(clock);
        let (up, down) = (inner.ring).add(fold.from_s, fold.to_s, fold.awake_s, fold.up, fold.down);
        session.note_peak(up, down);
        let taken = session.meters().take(turned);
        let moved = Moved {
            up: fold.up,
            down: fold.down,
            flows: fold.flows,
            outbounds: taken.now,
        };
        inner.usage.add(clock, &moved);
        let moved_on = inner.previous.unwrap_or(day);
        inner.usage.add_late(moved_on, day, &taken.late);
        let changed = turned || moved != Moved::default() || !taken.late.is_empty();
        inner.usage_writes.changes += u64::from(changed);
        let now_s = clock.boot_s();
        Writes {
            usage: inner.usage_writes.due(now_s, now || turned),
            latency: inner.latency_writes.retry(now_s),
        }
    }

    /// Runs `writes` on blocking threads. A file's writes run one at a time, each with the data
    /// as it is when its turn comes, so the last one written is the newest.
    fn save(self: &Arc<Self>, writes: Writes) {
        if writes.usage {
            let stats = Arc::clone(self);
            self.runtime.spawn_blocking(move || stats.write_usage());
        }
        if writes.latency {
            let stats = Arc::clone(self);
            self.runtime.spawn_blocking(move || stats.write_latency());
        }
    }

    /// Copies the usage under the lock, and encodes and writes the copy without it.
    fn write_usage(&self) {
        let mut changes = 0;
        let written = self.usage_file.save(|| {
            let inner = lock(&self.inner);
            changes = inner.usage_writes.changes;
            UsageFile {
                stats_version: STATS_VERSION,
                usage: inner.usage.clone(),
            }
        });
        if written {
            let saved = &mut lock(&self.inner).usage_writes.saved;
            *saved = changes.max(*saved);
        }
    }

    /// As [`Stats::write_usage`], for the latency tests.
    fn write_latency(&self) {
        let mut changes = 0;
        let written = self.latency_file.save(|| {
            let inner = lock(&self.inner);
            changes = inner.latency_writes.changes;
            LatencyFile {
                stats_version: STATS_VERSION,
                latency: inner.latency.all().clone(),
            }
        });
        if written {
            let saved = &mut lock(&self.inner).latency_writes.saved;
            *saved = changes.max(*saved);
        }
    }

    /// Records a latency test of node `id` in the round begun at `at_ms`: `ms`, or none when the
    /// probe failed. The caller holds the catalog lock, as every caller that nests the two does.
    pub fn record_latency(&self, id: &str, at_ms: i64, ms: Option<u32>) {
        let mut inner = lock(&self.inner);
        inner.latency.record(id, at_ms, ms);
        inner.latency_writes.changes += 1;
    }

    /// A latency round ended: writes the tests if they changed.
    pub fn end_round(self: &Arc<Self>) {
        let latency = lock(&self.inner)
            .latency_writes
            .due(Clock::now().boot_s(), true);
        self.save(Writes {
            usage: false,
            latency,
        });
    }

    /// Forgets the tests of the nodes `keep` does not keep, as a catalog without them is
    /// committed or loaded, and writes that at once, so a node that comes back starts with none.
    /// The caller holds the catalog lock, as every caller that nests the two does.
    pub fn keep_nodes(self: &Arc<Self>, keep: impl Fn(&str) -> bool) {
        let latency = {
            let mut inner = lock(&self.inner);
            if inner.latency.retain(keep) {
                inner.latency_writes.changes += 1;
            }
            inner.latency_writes.due(Clock::now().boot_s(), true)
        };
        self.save(Writes {
            usage: false,
            latency,
        });
    }

    /// The tests of each node of `ids`, oldest first.
    pub fn latency<'a>(&self, ids: impl Iterator<Item = &'a str>) -> Vec<Vec<dto::LatencyTest>> {
        let inner = lock(&self.inner);
        ids.map(|id| {
            (inner.latency.get(id).into_iter().flatten())
                .map(|t| dto::LatencyTest {
                    at_epoch_ms: t.0,
                    ms: t.1,
                })
                .collect()
        })
        .collect()
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
        let inner = lock(&self.inner);
        let view = inner.usage.view(now);
        let empty = Day::new(now);
        dto::Usage {
            today: day_usage(view.today.unwrap_or(&empty)),
            yesterday: view.yesterday.map(day_usage),
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
