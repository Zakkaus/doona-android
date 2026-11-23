//! Crash files (core-api.md, `diagnostics`). The panic hook (`panics.rs`) writes one per panic to
//! `dataDir/crash/<epochMs>.txt`: where the panic happened, a message, and the stack as offsets in
//! the loaded objects, at most 64 KiB. The directory keeps the newest five; a process writes one
//! file per panic location, so a panic that repeats in every flow costs one write. A panic on a
//! thread of a core's runtime goes to that core's directory, one on any other thread (a JNI
//! caller, a carrier) to the app's core, which `jni.rs` names; with neither, as on a unit test's
//! own thread, nothing is written.
//!
//! ARCH D21: a panic's payload and its thread's name come from wherever it happened, a
//! dependency's format string included, whose arguments the core cannot audit. A file holds
//! neither. Its message is one of the core's own literal messages ([`OWN`], [`FAULT`]), or else
//! `panic in <crate>::<module>` from the panic's source file; its location is a file and line of
//! this crate, or a dependency's name and version; its thread is a runtime's of the core, or
//! `other`.
//!
//! The hook minds its own safety. It writes into a buffer allocated with the core and copies
//! nothing of the payload; it never logs, since a panic inside a logger that holds its lock would
//! wait on that lock forever; and it takes the stack, as offsets and before the crash files, in
//! `stack.rs`. A name is never taken twice: a new file's number is above every number a crash
//! file had (the mark), which a clear never lowers and saves for the next start. The app writes
//! into the same directory by the same rules, and both take the directory's lock (`crash_dir.rs`).

use super::crash_dir::{self, DirLock, Listed, MARK, PANIC_WAIT, RETRY};
pub(crate) use super::crash_dir::{MAX_NUMBER, MIN_NUMBER, number};
use super::place::{Module, Place, place, version_of};
use super::report::{self, CrashFile};
use super::stack::Stack;
use super::{Diag, Event, now_ms};
use crate::api::lock;
use crate::dto::ErrorCode;
use std::cell::{Cell, OnceCell};
use std::collections::HashSet;
use std::fmt;
use std::fs;
use std::hash::{DefaultHasher, Hash, Hasher};
use std::io::{self, ErrorKind, Read as _, Write as _};
use std::panic::{Location, PanicHookInfo};
use std::path::PathBuf;
use std::sync::{Mutex, MutexGuard, OnceLock, TryLockError, Weak};
use std::time::Instant;

/// The directory under `dataDir`.
pub(crate) const DIR: &str = "crash";
pub(crate) const MAX_FILES: usize = 5;
pub(crate) const MAX_BYTES: usize = 64 * 1024;
/// The panic locations a process writes files for; past these it writes none.
const MAX_LOCATIONS: usize = 64;
/// A file being written is `<n>.part-core` (the app's `<n>.part-app`) and takes its name `<n>.txt`
/// once complete. Neither command sees it: no crash file's name.
const PART: &str = ".part-core";
/// The mark ([`MARK`]) is written here first.
const MARK_PART: &str = ".core.tmp";

/// The messages of the core's own panic sites outside the tests, which name no data and are kept
/// as they are: the `unreachable!()` of `structured/clash.rs` and `json/sing_box.rs`, that of
/// `json/sing_box/tls.rs`, and [`FAULT`].
const OWN: [&str; 2] = [
    "internal error: entered unreachable code",
    "internal error: entered unreachable code: stream_transport returned an unknown transport",
];
/// The fault hook's message (`tunnel/mod.rs`), only in the builds that have the hook: a release
/// library holds no `debugFault`, which the app's release check looks for.
#[cfg(any(test, feature = "fault-hook"))]
const FAULT: Option<&str> = Some("debugFault");
#[cfg(not(any(test, feature = "fault-hook")))]
const FAULT: Option<&str> = None;
/// The names the core gives its runtimes' threads (`api.rs`, `outbounds.rs`).
const THREADS: [&str; 2] = ["doona-core", "doona-carrier"];

thread_local! {
    /// The core whose runtime runs this thread.
    static HERE: OnceCell<Weak<Diag>> = const { OnceCell::new() };
    /// Whether this thread holds a core's crash files: a panic it raises meanwhile writes no file,
    /// as taking them again would wait forever.
    static HOLDING: Cell<bool> = const { Cell::new(false) };
}
/// The app's core, for panics on threads no runtime of a core runs.
static OTHERWISE: OnceLock<Weak<Diag>> = OnceLock::new();

/// Sends the calling thread's panics to `diag`; for a runtime's `on_thread_start`.
pub(crate) fn report_here(diag: &Weak<Diag>) {
    HERE.with(|h| {
        let _ = h.set(Weak::clone(diag));
    });
}

/// Sends the panics of threads no core's runtime runs to `diag`; once per process, by `jni.rs`.
pub(crate) fn report_otherwise(diag: &Weak<Diag>) {
    let _ = OTHERWISE.set(Weak::clone(diag));
}

/// For the panic hook: writes the crash file and records the panic in the ring, without logging.
pub(crate) fn on_panic(info: &PanicHookInfo<'_>) {
    #[cfg(test)]
    {
        let ((), n) = crate::testutil::alloc::allocated(|| on_panic_here(info));
        HOOK_ALLOCATED.with(|a| a.set(n));
    }
    #[cfg(not(test))]
    on_panic_here(info);
}

#[cfg(test)]
thread_local! {
    static HOOK_ALLOCATED: std::cell::Cell<usize> = const { std::cell::Cell::new(0) };
}

#[cfg(test)]
thread_local! {
    static UNDER_LOCK: std::cell::Cell<usize> = const { std::cell::Cell::new(0) };
}

/// How often this thread walked or resolved its stack while it held crash files.
#[cfg(test)]
pub(super) fn stack_under_lock() -> usize {
    UNDER_LOCK.with(std::cell::Cell::get)
}

#[cfg(test)]
pub(super) fn note_stack_work() {
    if HOLDING.try_with(Cell::get).unwrap_or(false) {
        let _ = UNDER_LOCK.try_with(|n| n.set(n.get() + 1));
    }
}

/// What the hook allocated for the calling thread's last panic.
#[cfg(test)]
pub(super) fn hook_allocated() -> usize {
    HOOK_ALLOCATED.with(std::cell::Cell::get)
}

fn on_panic_here(info: &PanicHookInfo<'_>) {
    // A thread being torn down may have lost its slot; then the app's core takes the panic.
    let here = HERE.try_with(|h| h.get().cloned()).ok().flatten();
    let Some(diag) = here
        .or_else(|| OTHERWISE.get().cloned())
        .and_then(|d| d.upgrade())
    else {
        return;
    };
    let event = Event::error("panic").code(ErrorCode::Internal);
    let event = match diag.crashes().map(|c| c.write(info)) {
        Some(Written::Repeated) => event.detail("repeated"),
        Some(Written::Failed(kind)) => event.detail(super::io_code(kind)),
        _ => event,
    };
    diag.try_record(event);
}

pub(crate) enum Written {
    File,
    /// This process wrote a file for the location already, or for as many locations as it may.
    Repeated,
    Failed(ErrorKind),
}

/// A core's crash files.
pub(crate) struct Crashes {
    dir: PathBuf,
    /// Held while a file is written, published and the oldest removed, and while the files are
    /// listed or removed: two panics at once leave five files, and a listing never sees six.
    files: Mutex<Files>,
    /// A full disk for the tests: a write past this many bytes fails; 0 for none.
    #[cfg(test)]
    pub(super) full_after: std::sync::atomic::AtomicUsize,
    /// The time names are taken from, for the tests; 0 for the clock.
    #[cfg(test)]
    pub(super) clock: std::sync::atomic::AtomicU64,
    /// A file that will not go, for the tests: trimming fails.
    #[cfg(test)]
    pub(super) stuck: std::sync::atomic::AtomicBool,
}

struct Files {
    /// Hashes of the locations this process wrote a file for.
    written: HashSet<u64>,
    /// The text of the file being written, allocated with the core.
    text: String,
    /// The highest number a crash file had here, published or listed, or in [`MARK`], read again
    /// each time. A new file's number is above it, and a clear never lowers it, so a name is
    /// never taken twice and a clear sent again removes no newer file.
    mark: Option<u64>,
}

impl Crashes {
    pub fn new(dir: PathBuf) -> Crashes {
        Crashes {
            dir,
            files: Mutex::new(Files {
                written: HashSet::with_capacity(MAX_LOCATIONS),
                text: String::with_capacity(MAX_BYTES),
                mark: None,
            }),
            #[cfg(test)]
            full_after: std::sync::atomic::AtomicUsize::new(0),
            #[cfg(test)]
            clock: std::sync::atomic::AtomicU64::new(0),
            #[cfg(test)]
            stuck: std::sync::atomic::AtomicBool::new(false),
        }
    }

    /// Writes the panic's file, unless this process wrote one for its location. A location
    /// counts as written once its file is published: a write that failed is tried again. Waits
    /// for the files and the directory until [`PANIC_WAIT`] has passed, then gives up; a location
    /// given up on counts as written too, as a panic repeated in every flow would wait each time
    /// and leave the lock's descriptor open each time (`crash_dir.rs`).
    pub(crate) fn write(&self, info: &PanicHookInfo<'_>) -> Written {
        let until = Some(Instant::now() + PANIC_WAIT);
        let key = info.location().map_or(0, |at| {
            let mut h = DefaultHasher::new();
            (at.file(), at.line(), at.column()).hash(&mut h);
            h.finish()
        });
        let repeated = |w: &HashSet<u64>| w.len() >= MAX_LOCATIONS || w.contains(&key);
        match self.hold(until) {
            Err(e) => return Written::Failed(e.kind()),
            Ok(files) if files.as_ref().is_none_or(|f| repeated(&f.0.written)) => {
                return Written::Repeated;
            }
            Ok(_) => {}
        }
        // Taken before the files are (`stack.rs`), and checked again after: another thread may
        // have written the location meanwhile.
        let (stack, thread) = (Stack::capture(), thread());
        let mut files = match self.hold(until) {
            Ok(Some(files)) => files,
            Ok(None) => return Written::Repeated,
            Err(e) => return Written::Failed(e.kind()),
        };
        let Files {
            written,
            text,
            mark,
        } = &mut *files.0;
        if repeated(written) {
            return Written::Repeated;
        }
        text.clear();
        let _ = describe(&mut Bounded(text), info, thread, &stack);
        match self.publish(text, mark, until) {
            Ok(_) => {
                written.insert(key);
                Written::File
            }
            Err(e) => {
                if e.kind() == ErrorKind::TimedOut {
                    written.insert(key);
                }
                Written::Failed(e.kind())
            }
        }
    }

    /// Publishes `text` as the newest file, as the hook does, but waits for the directory as long
    /// as another holds it.
    #[cfg(test)]
    pub(super) fn save(&self, text: &str) -> io::Result<String> {
        let mut files = self.hold(None)?.ok_or(ErrorKind::WouldBlock)?;
        self.publish(text, &mut files.0.mark, None)
    }

    /// Runs `f` holding the files, as a write does.
    #[cfg(test)]
    pub(super) fn holding(&self, f: impl FnOnce()) {
        let _files = self.hold(None);
        f();
    }

    /// The files, unless this thread holds them already, as when it panicked holding them (none).
    /// With `until`, waits for another thread to let go of them only until then (`TimedOut`).
    fn hold(&self, until: Option<Instant>) -> io::Result<Option<Held<'_>>> {
        if HOLDING.try_with(Cell::get).unwrap_or(false) {
            return Ok(None);
        }
        let files = match until {
            None => lock(&self.files),
            Some(until) => loop {
                match self.files.try_lock() {
                    Ok(files) => break files,
                    Err(TryLockError::Poisoned(files)) => break files.into_inner(),
                    Err(_) if Instant::now() < until => std::thread::sleep(RETRY),
                    Err(_) => return Err(ErrorKind::TimedOut.into()),
                }
            },
        };
        let _ = HOLDING.try_with(|h| h.set(true));
        Ok(Some(Held(files)))
    }

    /// Under the directory's lock, waited for until `until`: writes `text` to `<n>`[`PART`] until
    /// it is on disk, removes the oldest files so that five remain with it, and renames it
    /// `<n>.txt`; on a failure, removes the part. Then the placeholders go, which the new name is
    /// above. The number is one past `mark`, which [`Crashes::listed`] raises to every name
    /// present and the saved mark, or the time if later, and at least [`MIN_NUMBER`]: a clock set
    /// back cannot give an earlier name. Caller holds `files`.
    fn publish(
        &self,
        text: &str,
        mark: &mut Option<u64>,
        until: Option<Instant>,
    ) -> io::Result<String> {
        let _lock = DirLock::take(&self.dir, until)?;
        let (present, placeholders) = self.listed(mark)?;
        let at = mark.unwrap_or_default().saturating_add(1);
        let at = at.max(self.now()).max(MIN_NUMBER);
        if at > MAX_NUMBER {
            return Err(ErrorKind::InvalidData.into());
        }
        let (part, name) = (self.dir.join(format!("{at}{PART}")), format!("{at}.txt"));
        let published = self.put(&part, text.as_bytes()).and_then(|()| {
            self.trim(&present)?;
            fs::rename(&part, self.dir.join(&name))
        });
        published.inspect_err(|_| drop(fs::remove_file(&part)))?;
        *mark = Some(at);
        crash_dir::discard(&self.dir, &placeholders);
        Ok(name)
    }

    /// Removes the oldest of `present` until one more leaves [`MAX_FILES`]. A file that will not
    /// go stops the write: published, it would leave six.
    fn trim(&self, present: &[Listed]) -> io::Result<()> {
        let over = (present.len() + 1).saturating_sub(MAX_FILES);
        for (_, gone, _) in &present[..over] {
            #[cfg(test)]
            if self.stuck.load(std::sync::atomic::Ordering::Relaxed) {
                return Err(ErrorKind::PermissionDenied.into());
            }
            match fs::remove_file(self.dir.join(gone)) {
                Err(e) if e.kind() != ErrorKind::NotFound => return Err(e),
                _ => {}
            }
        }
        Ok(())
    }

    /// Under the directory's lock: the crash files, oldest first, and the empty placeholders of
    /// earlier versions, with `mark` raised to every number named, theirs included, and to the
    /// mark a clear saved, which another core may have saved meanwhile. A mark or directory that
    /// cannot be read is an error: the mark could come out too low.
    fn listed(&self, mark: &mut Option<u64>) -> io::Result<(Vec<Listed>, Vec<Listed>)> {
        let saved = crash_dir::saved_mark(&self.dir)?;
        let named = crash_dir::scan(&self.dir)?;
        let newest = named.last().map_or(0, |(at, ..)| *at);
        *mark = Some(mark.unwrap_or_default().max(saved).max(newest));
        Ok(named.into_iter().partition(|(.., bytes)| *bytes > 0))
    }

    fn now(&self) -> u64 {
        #[cfg(test)]
        match self.clock.load(std::sync::atomic::Ordering::Relaxed) {
            0 => {}
            set => return set,
        }
        u64::try_from(now_ms()).unwrap_or(0)
    }

    /// Writes `bytes` to the file at `path`, created or emptied, and waits until they are on disk.
    fn put(&self, path: &std::path::Path, bytes: &[u8]) -> io::Result<()> {
        let mut file = fs::File::create(path)?;
        #[cfg(test)]
        match self.full_after.load(std::sync::atomic::Ordering::Relaxed) {
            n if n > 0 && bytes.len() > n => {
                file.write_all(&bytes[..n])?;
                return Err(ErrorKind::StorageFull.into());
            }
            _ => {}
        }
        file.write_all(bytes)?;
        file.sync_data()
    }

    /// The crash files, oldest first, and the mark: no file listed has a higher number, and no
    /// file written from now on will have it or a lower one. `clearCrashes` takes it back. Read
    /// without the directory's lock; an empty file is no crash file, and what cannot be read
    /// leaves the mark lower, which a clear only narrows.
    pub fn listing(&self) -> (Vec<CrashFile>, u64) {
        let Ok(Some(mut files)) = self.hold(None) else {
            return (Vec::new(), 0);
        };
        let mut listed = crash_dir::scan(&self.dir).unwrap_or_default();
        listed.retain(|(.., bytes)| *bytes > 0);
        let newest = listed.last().map_or(0, |(at, ..)| *at);
        let saved = crash_dir::saved_mark(&self.dir).unwrap_or(0);
        let mark = files.0.mark.unwrap_or_default().max(saved).max(newest);
        files.0.mark = Some(mark);
        let listed = listed.into_iter();
        (
            listed
                .map(|(_, name, bytes)| CrashFile { name, bytes })
                .collect(),
            mark,
        )
    }

    #[cfg(test)]
    pub(super) fn list(&self) -> Vec<CrashFile> {
        self.listing().0
    }

    #[cfg(test)]
    pub(super) fn mark(&self) -> u64 {
        self.listing().1
    }

    /// Under the directory's lock, removes the files named whose numbers are `mark` or below, the
    /// mark of the listing they were exported from; a name already gone is passed over. Saves the
    /// core's own mark first, so no core takes a name at or below it, then removes the
    /// placeholders and the oldest files past five that earlier versions left. A `mark` above the
    /// core's, which no listing gave, is refused (none): saved, it would leave no name to take.
    /// Answers how many files it removed.
    pub fn remove(&self, names: &[String], mark: u64) -> io::Result<Option<usize>> {
        let Ok(Some(mut files)) = self.hold(None) else {
            return Ok(Some(0));
        };
        let _lock = DirLock::take(&self.dir, None)?;
        let kept = &mut files.0.mark;
        let (present, placeholders) = self.listed(kept)?;
        let kept = kept.unwrap_or_default();
        if mark > kept {
            return Ok(None);
        }
        let part = self.dir.join(MARK_PART);
        let saved = self.put(&part, kept.to_string().as_bytes());
        saved
            .and_then(|()| fs::rename(&part, self.dir.join(MARK)))
            .inspect_err(|_| {
                let _ = fs::remove_file(&part);
            })?;
        crash_dir::discard(&self.dir, &placeholders);
        crash_dir::discard(
            &self.dir,
            &present[..present.len().saturating_sub(MAX_FILES)],
        );
        let mut removed = 0;
        for name in names {
            if number(name).is_none_or(|at| at > mark) {
                continue;
            }
            match fs::remove_file(self.dir.join(name)) {
                Ok(()) => removed += 1,
                Err(e) if e.kind() == ErrorKind::NotFound => {}
                Err(e) => return Err(e),
            }
        }
        Ok(Some(removed))
    }
}

/// A core's crash files, held by this thread.
struct Held<'a>(MutexGuard<'a, Files>);

impl Drop for Held<'_> {
    fn drop(&mut self) {
        let _ = HOLDING.try_with(|h| h.set(false));
    }
}

/// Text that fits in the string's room up to [`MAX_BYTES`]; the rest is dropped.
pub(super) struct Bounded<'a>(pub &'a mut String);

impl fmt::Write for Bounded<'_> {
    fn write_str(&mut self, s: &str) -> fmt::Result {
        let room = MAX_BYTES.saturating_sub(self.0.len());
        if s.len() <= room {
            self.0.push_str(s);
            return Ok(());
        }
        let end = (0..=room)
            .rev()
            .find(|&i| s.is_char_boundary(i))
            .unwrap_or(0);
        self.0.push_str(&s[..end]);
        Err(fmt::Error)
    }
}

/// A crash file's text: `key: value` lines, a blank line, then the frames (core-api.md).
fn describe(
    w: &mut impl fmt::Write,
    info: &PanicHookInfo<'_>,
    thread: &str,
    stack: &Stack,
) -> fmt::Result {
    writeln!(w, "source: core")?;
    writeln!(w, "atEpochMs: {}", now_ms())?;
    let (arch, os) = (std::env::consts::ARCH, std::env::consts::OS);
    let version = env!("CARGO_PKG_VERSION");
    writeln!(w, "version: {version} {} {arch}-{os}", report::profile())?;
    writeln!(w, "thread: {thread}")?;
    let place = info
        .location()
        .map_or(Place::Unknown, |at| place(at.file()));
    w.write_str("location: ")?;
    location(w, &place, info.location())?;
    w.write_str("\nmessage: ")?;
    match info
        .payload_as_str()
        .filter(|m| OWN.contains(m) || FAULT == Some(*m))
    {
        Some(own) => w.write_str(own)?,
        None => write!(w, "panic in {}", Module(&place))?,
    }
    write!(w, "\n\nbacktrace:\n{stack}")
}

/// `src/<file>.rs:<line>:<column>` in this crate, a dependency's name and version, or `unknown`.
fn location(w: &mut impl fmt::Write, place: &Place<'_>, at: Option<&Location<'_>>) -> fmt::Result {
    match (place, at) {
        (Place::Ours { file, .. }, Some(at)) => write!(w, "{file}:{}:{}", at.line(), at.column()),
        (Place::Theirs { dir, .. }, _) => match version_of(dir) {
            (name, Some(version)) => write!(w, "{name} {version}"),
            (name, None) => w.write_str(name),
        },
        _ => w.write_str("unknown"),
    }
}

/// The calling thread's name when the core named it, else `other`: another thread's name may be
/// anything.
fn thread() -> &'static str {
    let mut name = [0_u8; 16];
    let read = fs::File::open("/proc/thread-self/comm").and_then(|mut f| f.read(&mut name));
    let name = name.get(..read.unwrap_or(0)).unwrap_or_default();
    let name = name.strip_suffix(b"\n").unwrap_or(name);
    THREADS
        .into_iter()
        .find(|t| t.as_bytes() == name)
        .unwrap_or("other")
}
