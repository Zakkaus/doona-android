//! Crash files (core-api.md, `diagnostics`). The panic hook (`panics.rs`) writes one per panic to
//! `dataDir/crash/<epochMs>.txt`: the message, the location and the backtrace, at most 64 KiB. The
//! directory keeps the newest five; a process writes one file per panic location, so a panic that
//! repeats in every flow costs one write. A panic on a thread of a core's runtime goes to that
//! core's directory, one on any other thread (a JNI caller, a carrier) to the app's core, which
//! `jni.rs` names; with neither, as on a unit test's own thread, nothing is written.
//!
//! ARCH D21: the message may come from a dependency's format string, whose arguments the core
//! cannot audit. A literal message is kept; a formatted one passes [`redact`]. The core's own
//! panics format no user data (`panic!`, `expect`, `unreachable!` and `assert!` outside tests
//! take literals only).

use super::report::{CoreBuild, CrashFile};
use super::{Diag, Event, now_ms};
use crate::api::lock;
use crate::dto::ErrorCode;
use std::cell::OnceCell;
use std::collections::HashSet;
use std::fmt::Write as _;
use std::fs;
use std::hash::{DefaultHasher, Hash, Hasher};
use std::io::{self, ErrorKind, Write as _};
use std::panic::PanicHookInfo;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock, Weak};

/// The directory under `dataDir`.
pub(crate) const DIR: &str = "crash";
pub(crate) const MAX_FILES: usize = 5;
pub(crate) const MAX_BYTES: usize = 64 * 1024;
/// How much of a message a file keeps.
const MAX_MESSAGE: usize = 4 * 1024;
/// The panic locations a process writes files for; past these it writes none.
const MAX_LOCATIONS: usize = 64;

thread_local! {
    /// The core whose runtime runs this thread.
    static HERE: OnceCell<Weak<Diag>> = const { OnceCell::new() };
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

/// For the panic hook: writes the crash file and records the panic in the ring.
pub(crate) fn on_panic(info: &PanicHookInfo<'_>) {
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
    /// This process wrote a file for the location already.
    Repeated,
    Failed(ErrorKind),
}

/// A core's crash files.
pub(crate) struct Crashes {
    pub(super) dir: PathBuf,
    /// Whether a file carries a backtrace. The unit tests leave it out: resolving one in a test
    /// binary with its debug info takes long on a thread whose timing a test may measure.
    pub(super) backtraces: bool,
    /// Hashes of the locations this process wrote a file for.
    written: Mutex<HashSet<u64>>,
}

impl Crashes {
    pub fn new(dir: PathBuf) -> Crashes {
        Crashes {
            dir,
            backtraces: !cfg!(test),
            written: Mutex::new(HashSet::new()),
        }
    }

    pub(crate) fn write(&self, info: &PanicHookInfo<'_>) -> Written {
        let key = info.location().map_or(0, |at| {
            let mut h = DefaultHasher::new();
            (at.file(), at.line(), at.column()).hash(&mut h);
            h.finish()
        });
        {
            let mut written = lock(&self.written);
            if written.len() >= MAX_LOCATIONS || !written.insert(key) {
                return Written::Repeated;
            }
        }
        match self.save(&report(info, self.backtraces)) {
            Ok(_) => Written::File,
            Err(e) => Written::Failed(e.kind()),
        }
    }

    /// Writes `text` as the newest file, after removing the oldest beyond the other four. Its
    /// name's number is the time, or one past the newest file's when that is later, as for a
    /// second panic in one millisecond: a name freed by the removal is never taken again.
    pub(super) fn save(&self, text: &str) -> io::Result<String> {
        fs::create_dir_all(&self.dir)?;
        let old = self.files();
        let newest = old.last().map_or(0, |(at, ..)| at.saturating_add(1));
        for (_, name, _) in old.iter().take((old.len() + 1).saturating_sub(MAX_FILES)) {
            let _ = fs::remove_file(self.dir.join(name));
        }
        let mut at = u64::try_from(now_ms()).unwrap_or(0).max(newest);
        for _ in 0..64 {
            let name = format!("{at}.txt");
            let path = self.dir.join(&name);
            match fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(path)
            {
                Ok(mut file) => return file.write_all(text.as_bytes()).map(|()| name),
                Err(e) if e.kind() == ErrorKind::AlreadyExists => at += 1,
                Err(e) => return Err(e),
            }
        }
        Err(ErrorKind::AlreadyExists.into())
    }

    /// The `.txt` files with the number their names start with and their sizes, oldest first: by
    /// that number (Kotlin names its crash files the same way), then by name.
    fn files(&self) -> Vec<(u64, String, u64)> {
        let Ok(dir) = fs::read_dir(&self.dir) else {
            return Vec::new();
        };
        let mut files: Vec<(u64, String, u64)> = dir
            .flatten()
            .filter_map(|e| {
                let meta = e.metadata().ok().filter(fs::Metadata::is_file)?;
                let name = e.file_name().into_string().ok()?;
                let digits = name
                    .find(|c: char| !c.is_ascii_digit())
                    .unwrap_or(name.len());
                let at = name[..digits].parse().unwrap_or(0);
                name.ends_with(".txt").then_some((at, name, meta.len()))
            })
            .collect();
        files.sort();
        files
    }

    pub fn list(&self) -> Vec<CrashFile> {
        let files = self.files().into_iter();
        files
            .map(|(_, name, bytes)| CrashFile { name, bytes })
            .collect()
    }

    /// Removes every file in the directory; answers how many.
    pub fn clear(&self) -> io::Result<usize> {
        let dir = match fs::read_dir(&self.dir) {
            Ok(dir) => dir,
            Err(e) if e.kind() == ErrorKind::NotFound => return Ok(0),
            Err(e) => return Err(e),
        };
        let mut removed = 0;
        for entry in dir.flatten() {
            if entry.file_type().is_ok_and(|t| t.is_file()) {
                fs::remove_file(entry.path())?;
                removed += 1;
            }
        }
        Ok(removed)
    }
}

/// A crash file's text: `key: value` lines, a blank line, then the backtrace (core-api.md).
fn report(info: &PanicHookInfo<'_>, backtraces: bool) -> String {
    let build = CoreBuild::current();
    let mut text = String::new();
    let _ = writeln!(text, "source: core");
    let _ = writeln!(text, "atEpochMs: {}", now_ms());
    let _ = writeln!(
        text,
        "version: {} {} {}",
        build.version, build.profile, build.target
    );
    let thread = fs::read_to_string("/proc/thread-self/comm").unwrap_or_default();
    let _ = writeln!(text, "thread: {}", redact(thread.trim_end()));
    if let Some(at) = info.location() {
        let file = source_path(at.file());
        let _ = writeln!(text, "location: {file}:{}:{}", at.line(), at.column());
    }
    let _ = writeln!(text, "message: {}", message(info));
    if let Some(base) = base() {
        let _ = writeln!(text, "base: {base:#x}");
    }
    let _ = writeln!(text, "\nbacktrace:");
    if backtraces {
        // `#` prints each frame's address, which `base` turns into an offset in the library.
        let _ = writeln!(text, "{:#}", std::backtrace::Backtrace::force_capture());
    } else {
        let _ = writeln!(text, "not captured");
    }
    cap(&mut text, MAX_BYTES);
    text
}

/// A literal message as it is, a formatted one through [`redact`].
fn message(info: &PanicHookInfo<'_>) -> String {
    let payload = info.payload();
    let mut message = if let Some(literal) = payload.downcast_ref::<&'static str>() {
        (*literal).to_owned()
    } else if let Some(formatted) = payload.downcast_ref::<String>() {
        redact(formatted)
    } else {
        "not text".to_owned()
    };
    cap(&mut message, MAX_MESSAGE);
    message.replace('\n', " ")
}

/// Cuts `text` to at most `max` bytes, at a character boundary.
pub(super) fn cap(text: &mut String, max: usize) {
    if text.len() > max {
        let end = (0..=max)
            .rev()
            .find(|&i| text.is_char_boundary(i))
            .unwrap_or(0);
        text.truncate(end);
    }
}

/// `message` with every part that may name a place, a credential or a node replaced by `…`:
/// double-quoted text, back-quoted text other than a Rust path (`Option::unwrap()`), and each word
/// holding `.`, `:`, `/`, `@`, `\`, `%`, `=`, `?`, `#` or a character outside ASCII, once the
/// punctuation around it is set apart. URLs, addresses with or without a port, host names, e-mail
/// addresses and names in other scripts go; an ASCII word without those characters stays.
pub(crate) fn redact(message: &str) -> String {
    let mut out = String::with_capacity(message.len());
    let mut rest = message;
    while let Some(i) = rest.find(['"', '`']) {
        words(&rest[..i], &mut out);
        let quote = &rest[i..=i];
        let after = &rest[i + 1..];
        out.push_str(quote);
        match after.find(quote) {
            Some(j) => {
                let inner = &after[..j];
                out.push_str(if quote == "`" && is_path(inner) {
                    inner
                } else {
                    "…"
                });
                out.push_str(quote);
                rest = &after[j + 1..];
            }
            None => {
                out.push('…');
                rest = "";
            }
        }
    }
    words(rest, &mut out);
    out
}

fn words(text: &str, out: &mut String) {
    let edge = |c: char| matches!(c, ',' | ';' | ':' | '.' | '!' | '?' | '(' | ')' | '[' | ']');
    for piece in text.split_inclusive(char::is_whitespace) {
        let word = piece.trim_end();
        let inner = word.trim_start_matches(edge);
        let lead = &word[..word.len() - inner.len()];
        let core = inner.trim_end_matches(edge);
        let trail = &inner[core.len()..];
        let place = |c: char| !c.is_ascii() || ".:/@\\%=?#".contains(c);
        if core.contains(place) {
            out.push_str(lead);
            out.push('…');
            out.push_str(trail);
        } else {
            out.push_str(word);
        }
        out.push_str(&piece[word.len()..]);
    }
}

/// A Rust path as std quotes it: `None`, `Option::unwrap()`, `Vec<u8>`.
fn is_path(text: &str) -> bool {
    let first = text.chars().next();
    first.is_some_and(|c| c.is_ascii_alphabetic() || c == '_' || c == '&')
        && text.len() <= 64
        && text
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "_:<>()&,!".contains(c))
}

/// A source path from its crate's directory on: `tokio-1.47.1/src/runtime/park.rs` for a
/// dependency, `src/api.rs` for this crate. The build machine's home stays out.
pub(super) fn source_path(file: &str) -> &str {
    match file.rfind("/src/") {
        Some(i) => &file[file[..i].rfind('/').map_or(0, |j| j + 1)..],
        None => file,
    }
}

/// Where the object holding this code is loaded, so a backtrace's addresses become offsets in it.
fn base() -> Option<usize> {
    static ANCHOR: u8 = 0;
    let mut info = std::mem::MaybeUninit::<libc::Dl_info>::zeroed();
    // SAFETY: `ANCHOR` is in this object, and `info` has room for what dladdr writes.
    let found = unsafe { libc::dladdr(std::ptr::from_ref(&ANCHOR).cast(), info.as_mut_ptr()) };
    // SAFETY: all-zero bytes are a valid `Dl_info`, which dladdr filled when it returned nonzero.
    (found != 0).then(|| unsafe { info.assume_init() }.dli_fbase as usize)
}
