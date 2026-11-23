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
//! file had (the mark), which a clear never lowers and saves for the next start.

use super::report::{self, CrashFile};
use super::stack::Stack;
use super::{Diag, Event, now_ms};
use crate::api::lock;
use crate::dto::ErrorCode;
use std::cell::{Cell, OnceCell};
use std::collections::HashSet;
use std::fmt::{self, Write as _};
use std::fs;
use std::hash::{DefaultHasher, Hash, Hasher};
use std::io::{self, ErrorKind, Read as _, Write as _};
use std::panic::{Location, PanicHookInfo};
use std::path::PathBuf;
use std::sync::{Mutex, MutexGuard, OnceLock, Weak};

/// The directory under `dataDir`.
pub(crate) const DIR: &str = "crash";
pub(crate) const MAX_FILES: usize = 5;
pub(crate) const MAX_BYTES: usize = 64 * 1024;
/// The panic locations a process writes files for; past these it writes none.
const MAX_LOCATIONS: usize = 64;
/// The file being written; it takes its name once complete. Not a crash file's name, so neither
/// `diagnostics` nor `clearCrashes` sees it, and the next write starts it over.
const PART: &str = ".core.tmp";
/// The mark ([`Crashes::listing`]) as a clear leaves it, for a core that starts again.
const MARK: &str = ".core.mark";

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
}

struct Files {
    /// Hashes of the locations this process wrote a file for.
    written: HashSet<u64>,
    /// The text of the file being written, allocated with the core.
    text: String,
    /// The highest number a crash file had here: published, listed, or given to a clear, and
    /// read from [`MARK`] at the first use. A new file's number is above it, and a clear never
    /// lowers it, so a name is never taken twice and a clear sent again removes no newer file.
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
        }
    }

    /// Writes the panic's file, unless this process wrote one for its location. A location
    /// counts as written once its file is published: a write that failed is tried again.
    pub(crate) fn write(&self, info: &PanicHookInfo<'_>) -> Written {
        let key = info.location().map_or(0, |at| {
            let mut h = DefaultHasher::new();
            (at.file(), at.line(), at.column()).hash(&mut h);
            h.finish()
        });
        let repeated = |w: &HashSet<u64>| w.len() >= MAX_LOCATIONS || w.contains(&key);
        if self.hold().is_none_or(|files| repeated(&files.0.written)) {
            return Written::Repeated;
        }
        // Taken before the files are (`stack.rs`), and checked again after: another thread may
        // have written the location meanwhile.
        let (stack, thread) = (Stack::capture(), thread());
        let Some(mut files) = self.hold() else {
            return Written::Repeated;
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
        match self.publish(text, mark) {
            Ok(_) => {
                written.insert(key);
                Written::File
            }
            Err(e) => Written::Failed(e.kind()),
        }
    }

    /// Publishes `text` as the newest file, as the hook does.
    #[cfg(test)]
    pub(super) fn save(&self, text: &str) -> io::Result<String> {
        let mut files = self.hold().ok_or(ErrorKind::WouldBlock)?;
        self.publish(text, &mut files.0.mark)
    }

    /// Runs `f` holding the files, as a write does.
    #[cfg(test)]
    pub(super) fn holding(&self, f: impl FnOnce()) {
        let _files = self.hold();
        f();
    }

    /// The files, unless this thread holds them already, as when it panicked holding them.
    fn hold(&self) -> Option<Held<'_>> {
        if HOLDING.try_with(Cell::get).unwrap_or(false) {
            return None;
        }
        let files = lock(&self.files);
        let _ = HOLDING.try_with(|h| h.set(true));
        Some(Held(files))
    }

    /// Writes `text` whole to [`PART`], then renames it to its name, then removes the oldest
    /// files beyond the other four; on a failure, removes the part and leaves the rest. The name's
    /// number is the time, or one past `mark` when that is later, as for a second panic in one
    /// millisecond or a clock set back. The caller holds `files`.
    fn publish(&self, text: &str, mark: &mut Option<u64>) -> io::Result<String> {
        fs::create_dir_all(&self.dir)?;
        let part = self.dir.join(PART);
        let published = self.put(&part, text.as_bytes()).and_then(|()| {
            let old = self.listed(mark);
            let (at, name) = self.free_name(mark.unwrap_or_default())?;
            fs::rename(&part, self.dir.join(&name))?;
            Ok((at, name, old))
        });
        let (at, name, old) = published.inspect_err(|_| {
            let _ = fs::remove_file(&part);
        })?;
        *mark = Some(at);
        for (_, gone, _) in old.iter().take((old.len() + 1).saturating_sub(MAX_FILES)) {
            let _ = fs::remove_file(self.dir.join(gone));
        }
        Ok(name)
    }

    /// The first number past `mark`, and not before the time, whose name nothing holds (the app
    /// may have taken one meanwhile, which a rename would replace). None past the largest number:
    /// `invalid_data`.
    fn free_name(&self, mark: u64) -> io::Result<(u64, String)> {
        let exhausted = || io::Error::from(ErrorKind::InvalidData);
        let mut at = mark.checked_add(1).ok_or_else(exhausted)?.max(self.now());
        loop {
            let name = format!("{at}.txt");
            if fs::symlink_metadata(self.dir.join(&name)).is_err() {
                return Ok((at, name));
            }
            at = at.checked_add(1).ok_or_else(exhausted)?;
        }
    }

    /// The crash files, oldest first, with `mark` raised to the newest of them.
    fn listed(&self, mark: &mut Option<u64>) -> Vec<(u64, String, u64)> {
        let files = self.files();
        let kept = mark.get_or_insert_with(|| self.saved_mark());
        *kept = (*kept).max(files.last().map_or(0, |(at, ..)| *at));
        files
    }

    /// The mark a clear saved in [`MARK`], or 0.
    fn saved_mark(&self) -> u64 {
        let mut digits = [0_u8; 24];
        let read = fs::File::open(self.dir.join(MARK)).and_then(|mut f| f.read(&mut digits));
        let digits = digits.get(..read.unwrap_or(0)).unwrap_or_default();
        let digits = std::str::from_utf8(digits).ok();
        digits.and_then(|d| d.trim().parse().ok()).unwrap_or(0)
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

    /// The crash files with their names' numbers and their sizes, oldest first: by that number,
    /// then by name. Anything else in the directory is left out.
    fn files(&self) -> Vec<(u64, String, u64)> {
        let Ok(dir) = fs::read_dir(&self.dir) else {
            return Vec::new();
        };
        let mut files: Vec<(u64, String, u64)> = dir
            .flatten()
            .filter_map(|e| {
                let name = e.file_name().into_string().ok()?;
                let at = number(&name)?;
                let meta = e.metadata().ok().filter(fs::Metadata::is_file)?;
                Some((at, name, meta.len()))
            })
            .collect();
        files.sort();
        files
    }

    /// The crash files, oldest first, and the mark: no file listed has a higher number, and no
    /// file written from now on will have it or a lower one. `clearCrashes` takes it back.
    pub fn listing(&self) -> (Vec<CrashFile>, u64) {
        let Some(mut files) = self.hold() else {
            return (Vec::new(), 0);
        };
        let mark = &mut files.0.mark;
        let listed = self.listed(mark).into_iter();
        let listed = listed.map(|(_, name, bytes)| CrashFile { name, bytes });
        (listed.collect(), mark.unwrap_or_default())
    }

    #[cfg(test)]
    pub(super) fn list(&self) -> Vec<CrashFile> {
        self.listing().0
    }

    #[cfg(test)]
    pub(super) fn mark(&self) -> u64 {
        self.listing().1
    }

    /// Removes the files named whose numbers are `mark` or below, the mark of the listing they
    /// were exported from; a name already gone is passed over. Raises the core's mark to `mark`
    /// and saves it first, so a core that starts again takes no name at or below it. Answers how
    /// many files it removed.
    pub fn remove(&self, names: &[String], mark: u64) -> io::Result<usize> {
        let Some(mut files) = self.hold() else {
            return Ok(0);
        };
        let kept = &mut files.0.mark;
        let _ = self.listed(kept);
        let kept = kept.insert(kept.unwrap_or_default().max(mark));
        fs::create_dir_all(&self.dir)?;
        let part = self.dir.join(PART);
        let saved = self.put(&part, kept.to_string().as_bytes());
        saved
            .and_then(|()| fs::rename(&part, self.dir.join(MARK)))
            .inspect_err(|_| {
                let _ = fs::remove_file(&part);
            })?;
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
        Ok(removed)
    }
}

/// A core's crash files, held by this thread.
struct Held<'a>(MutexGuard<'a, Files>);

impl Drop for Held<'_> {
    fn drop(&mut self) {
        let _ = HOLDING.try_with(|h| h.set(false));
    }
}

/// The number a crash file's name holds: `<epochMs>.txt` is the one name the core and the app
/// give their crash files.
pub(crate) fn number(name: &str) -> Option<u64> {
    let digits = name.strip_suffix(".txt")?;
    let digits =
        (!digits.is_empty() && digits.bytes().all(|b| b.is_ascii_digit())).then_some(digits);
    digits?.parse().ok()
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

/// Where a panic happened, by its source file.
#[derive(Debug, PartialEq, Eq)]
pub(super) enum Place<'a> {
    /// This crate: its file from the crate's directory on (`src/api/rules.rs`) and the module
    /// (`api/rules`, empty for the crate's root).
    Ours {
        file: &'a str,
        module: &'a str,
    },
    /// A dependency or the standard library: its source directory (`tokio-1.47.1`,
    /// `honk-outbound`, `core`) and the module.
    Theirs {
        dir: &'a str,
        module: &'a str,
    },
    Unknown,
}

/// `src/…` for this crate built in its own directory, else the directory before the last
/// `/src/`: the crate's, whether under cargo's registry or git checkouts, rustc's `library`, or a
/// workspace. Anything else, as a file generated in a build directory, is unknown.
pub(super) fn place(file: &str) -> Place<'_> {
    let (dir, file, rest) = match file.strip_prefix("src/") {
        Some(rest) => (env!("CARGO_PKG_NAME"), file, rest),
        None => match file.rfind("/src/") {
            Some(i) => {
                let start = file[..i].rfind('/').map_or(0, |j| j + 1);
                (&file[start..i], &file[i + 1..], &file[i + 5..])
            }
            None => return Place::Unknown,
        },
    };
    let module = rest.strip_suffix(".rs").map(|m| {
        let m = m.strip_suffix("/mod").unwrap_or(m);
        if matches!(m, "lib" | "main" | "mod") {
            ""
        } else {
            m
        }
    });
    let plain = |text: &str, more: &str| {
        text.chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || more.contains(c))
    };
    match module {
        Some(module) if !dir.is_empty() && plain(dir, "-.+") && plain(module, "/") => {
            if dir == env!("CARGO_PKG_NAME") {
                Place::Ours { file, module }
            } else {
                Place::Theirs { dir, module }
            }
        }
        _ => Place::Unknown,
    }
}

/// A source directory's crate name and version: `tokio-1.47.1` is `tokio` and `1.47.1`,
/// `sha-1-0.10.6` is `sha-1` and `0.10.6`; a git checkout's or the standard library's has no
/// version.
pub(super) fn version_of(dir: &str) -> (&str, Option<&str>) {
    let numeric = |p: &str| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit());
    for (i, _) in dir.match_indices('-') {
        let version = &dir[i + 1..];
        let mut parts = version.splitn(3, '.');
        let (major, minor) = (parts.next().unwrap_or(""), parts.next().unwrap_or(""));
        let patch = parts.next().unwrap_or("");
        if numeric(major) && numeric(minor) && patch.starts_with(|c: char| c.is_ascii_digit()) {
            return (&dir[..i], Some(version));
        }
    }
    (dir, None)
}

/// A place's module as Rust names it: `doonacore::api::rules`, `tokio::runtime::park`.
pub(super) struct Module<'a, 'p>(pub &'a Place<'p>);

impl fmt::Display for Module<'_, '_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let (krate, module) = match self.0 {
            Place::Ours { module, .. } => (env!("CARGO_CRATE_NAME"), *module),
            Place::Theirs { dir, module } => (version_of(dir).0, *module),
            Place::Unknown => return f.write_str("unknown"),
        };
        for c in krate.chars() {
            f.write_char(if c == '-' { '_' } else { c })?;
        }
        for part in module.split('/').filter(|p| !p.is_empty()) {
            write!(f, "::{part}")?;
        }
        Ok(())
    }
}
