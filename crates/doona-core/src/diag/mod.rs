//! Diagnostics (core-api.md, `diagnostics`): a ring of the core's recent events, what the last
//! snapshot reported, and the crash files the panic hook writes (`crash.rs`).
//!
//! An event holds what ARCH D21 lets a log hold: a time, a level, a command or event name, an error
//! code, counts and a duration. Its texts are `&'static str`, written in this crate's source, so
//! no URL, node address, port, credential, DNS name or display name can enter the ring: the type
//! takes none. Events are recorded where the core already logged (commands, starts and stops,
//! health, subscription refreshes, probe rounds and errors), never per packet or per flow, so the
//! ring's lock is never taken on the data path.

pub(crate) mod crash;
pub mod report;

use crate::api::lock;
use crate::dto::{ErrorCode, Snapshot};
use report::Level;
use std::collections::VecDeque;
use std::fmt;
use std::io::ErrorKind;
use std::path::PathBuf;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU8, Ordering::Relaxed};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

/// How many events the ring keeps; past it the oldest goes first.
pub(crate) const CAPACITY: usize = 2_000;

/// What an event counts.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Count {
    Generation,
    Nodes,
    Subscriptions,
    Probes,
    Failed,
    Tasks,
    Files,
}

impl Count {
    pub fn as_str(self) -> &'static str {
        match self {
            Count::Generation => "generation",
            Count::Nodes => "nodes",
            Count::Subscriptions => "subscriptions",
            Count::Probes => "probes",
            Count::Failed => "failed",
            Count::Tasks => "tasks",
            Count::Files => "files",
        }
    }
}

/// One entry of the ring. Built with the level's constructor and the setters below, then given to
/// [`Diag::record`], which stamps the time.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Event {
    pub at_ms: i64,
    pub level: Level,
    /// A `call` command, or one of the events core-api.md lists.
    pub name: &'static str,
    /// An error code, or what an I/O error was.
    pub code: Option<&'static str>,
    /// A fixed word that qualifies the event: a mode, a file of the core, `cancelled`.
    pub detail: Option<&'static str>,
    pub counts: [Option<(Count, u32)>; 2],
    pub ms: Option<u32>,
}

impl Event {
    fn new(level: Level, name: &'static str) -> Event {
        Event {
            at_ms: 0,
            level,
            name,
            code: None,
            detail: None,
            counts: [None; 2],
            ms: None,
        }
    }

    pub fn error(name: &'static str) -> Event {
        Event::new(Level::Error, name)
    }

    pub fn warn(name: &'static str) -> Event {
        Event::new(Level::Warn, name)
    }

    pub fn info(name: &'static str) -> Event {
        Event::new(Level::Info, name)
    }

    pub fn debug(name: &'static str) -> Event {
        Event::new(Level::Debug, name)
    }

    pub fn code(mut self, code: ErrorCode) -> Event {
        self.code = Some(code.as_str());
        self
    }

    /// What an I/O error was, by its kind only: its message may hold a path.
    pub fn io(mut self, kind: ErrorKind) -> Event {
        self.code = Some(io_code(kind));
        self
    }

    pub fn detail(mut self, detail: &'static str) -> Event {
        self.detail = Some(detail);
        self
    }

    /// Adds a count; an event holds two, and a third is dropped.
    pub fn count<N: TryInto<u32>>(mut self, what: Count, n: N) -> Event {
        let n = n.try_into().unwrap_or(u32::MAX);
        if let Some(free) = self.counts.iter_mut().find(|c| c.is_none()) {
            *free = Some((what, n));
        }
        self
    }

    /// The data path of a generation died: `snapshot` reports `internal`.
    pub fn died() -> Event {
        Event::error("died").code(ErrorCode::Internal)
    }

    /// The `local_network` warning was set, or cleared.
    pub fn warning(on: bool) -> Event {
        match on {
            true => Event::warn("warning").code(ErrorCode::LocalNetwork),
            false => Event::info("warning").detail("cleared"),
        }
    }

    /// A generation ended; `detached` of its tasks still ran when the wait for them ended, and
    /// `closed` says whether its carriers were shut down.
    pub fn stopped(detached: Option<usize>, closed: bool) -> Event {
        let event = match (detached, closed) {
            (None, true) => return Event::info("stopped"),
            (_, true) => Event::warn("stopped").detail("detached"),
            (_, false) => Event::warn("stopped").detail("carriersOpen"),
        };
        match detached {
            Some(tasks) => event.count(Count::Tasks, tasks),
            None => event,
        }
    }

    /// The time since `begun`, in milliseconds.
    pub fn since(mut self, begun: Instant) -> Event {
        self.ms = Some(u32::try_from(begun.elapsed().as_millis()).unwrap_or(u32::MAX));
        self
    }
}

/// How the event shows in logcat: `start: generation=3 120 ms`.
impl fmt::Display for Event {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.name)?;
        f.write_str(":")?;
        if let Some(code) = self.code {
            write!(f, " {code}")?;
        }
        if let Some(detail) = self.detail {
            write!(f, " {detail}")?;
        }
        for (what, n) in self.counts.iter().flatten() {
            write!(f, " {}={n}", what.as_str())?;
        }
        if let Some(ms) = self.ms {
            write!(f, " {ms} ms")?;
        }
        Ok(())
    }
}

/// The kinds of I/O error the core's files meet, and `io` for the rest.
pub(super) fn io_code(kind: ErrorKind) -> &'static str {
    match kind {
        ErrorKind::NotFound => "not_found",
        ErrorKind::PermissionDenied => "permission_denied",
        ErrorKind::StorageFull | ErrorKind::QuotaExceeded => "storage_full",
        ErrorKind::ReadOnlyFilesystem => "read_only",
        ErrorKind::InvalidData => "invalid_data",
        _ => "io",
    }
}

/// What the last `snapshot` reported.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Seen {
    pub at_ms: i64,
    pub revision: i64,
    pub running: bool,
    pub generation: i64,
    pub error: Option<ErrorCode>,
    pub warning: Option<ErrorCode>,
}

struct Ring {
    events: VecDeque<Event>,
    /// Events the ring let go since the core began.
    overwritten: u64,
}

/// A core's diagnostics.
pub(crate) struct Diag {
    /// The least severe level the ring keeps, as [`Level::rank`].
    level: AtomicU8,
    ring: Mutex<Ring>,
    seen: Mutex<Option<Seen>>,
    crashes: Option<crash::Crashes>,
}

impl Default for Diag {
    /// A ring without crash files, for the unit tests' stand-ins.
    fn default() -> Diag {
        Diag::new(None)
    }
}

impl Diag {
    /// A ring at level `info`, allocated in full now so recording never allocates, and the crash
    /// files under `crash_dir`, when given.
    pub fn new(crash_dir: Option<PathBuf>) -> Diag {
        Diag {
            level: AtomicU8::new(Level::Info.rank()),
            ring: Mutex::new(Ring {
                events: VecDeque::with_capacity(CAPACITY),
                overwritten: 0,
            }),
            seen: Mutex::new(None),
            crashes: crash_dir.map(crash::Crashes::new),
        }
    }

    pub fn level(&self) -> Level {
        Level::of_rank(self.level.load(Relaxed))
    }

    /// Events less severe than `level` are not kept from now on; those kept stay.
    pub fn set_level(&self, level: Level) {
        self.level.store(level.rank(), Relaxed);
    }

    /// Logs `event` and keeps it, unless it is less severe than the ring's level.
    pub fn record(&self, event: Event) {
        log::log!(event.level.log(), "{event}");
        if let Some(event) = self.stamped(event) {
            push(&mut lock(&self.ring), event);
        }
    }

    /// As [`Diag::record`], but gives up when the ring is in use: for the panic hook, which may
    /// run on a thread that holds the ring.
    pub fn try_record(&self, event: Event) {
        log::log!(event.level.log(), "{event}");
        if let Some(event) = self.stamped(event)
            && let Ok(mut ring) = self.ring.try_lock()
        {
            push(&mut ring, event);
        }
    }

    fn stamped(&self, mut event: Event) -> Option<Event> {
        if event.level.rank() > self.level.load(Relaxed) {
            return None;
        }
        event.at_ms = now_ms();
        Some(event)
    }

    /// The events kept, oldest first, of `at_least` or more severe; and how many the ring let go.
    pub fn events(&self, at_least: Option<Level>) -> (Vec<Event>, u64) {
        let ring = lock(&self.ring);
        let most = at_least.map_or(u8::MAX, Level::rank);
        let events = ring.events.iter().filter(|e| e.level.rank() <= most);
        (events.copied().collect(), ring.overwritten)
    }

    /// Notes what `snapshot` just answered.
    pub fn saw(&self, s: &Snapshot) {
        *lock(&self.seen) = Some(Seen {
            at_ms: now_ms(),
            revision: s.revision,
            running: s.running,
            generation: s.generation,
            error: s.error,
            warning: s.warning,
        });
    }

    /// What the last `snapshot` answered; none before the first.
    pub fn seen(&self) -> Option<Seen> {
        *lock(&self.seen)
    }

    /// The crash files; none for a stand-in.
    pub fn crashes(&self) -> Option<&crash::Crashes> {
        self.crashes.as_ref()
    }
}

fn push(ring: &mut Ring, event: Event) {
    if ring.events.len() == CAPACITY {
        ring.events.pop_front();
        ring.overwritten += 1;
    }
    ring.events.push_back(event);
}

pub(crate) fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

#[cfg(test)]
mod tests;
