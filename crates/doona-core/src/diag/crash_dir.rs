//! The lock on a crash directory, which the core (`crash.rs`) and the app (`CrashFiles.kt`) both
//! take. Only its holder takes a number, trims the files to five, publishes one, removes what
//! ended writes left or clears; so the core's or the app's next write may remove every part it
//! finds, however recent.
//!
//! The app takes it with the JVM's `FileChannel.lock`, a traditional fcntl lock on the whole
//! file. Such a lock belongs to the process, so it would not keep out the core in the same
//! process, and `flock` does not see it at all. The core takes an open file description lock
//! (`F_OFD_SETLKW`), which conflicts with a traditional one even within one process (fcntl(2)),
//! and with that of another description, as of a second core. A panic does not wait: it tries
//! (`F_OFD_SETLK`) for [`PANIC_WAIT`], then gives up its file. A clear waits.
//!
//! Earlier versions claimed a name by creating `<n>.txt` empty, and could leave six files. Under
//! the lock an empty `<n>.txt` is such a placeholder, never a crash file: neither listed nor
//! counted, it goes once a mark or a file above it holds its number, and the oldest files past
//! five go too.

use std::fs;
use std::io::{self, ErrorKind, Read as _};
use std::os::fd::AsRawFd;
use std::path::Path;
use std::time::{Duration, Instant};

/// The lock's file; no crash file's name.
const LOCK: &str = ".lock";
/// The mark (`crash.rs`) as a clear leaves it, for a core that starts again.
pub(super) const MARK: &str = ".core.mark";
/// The largest number a crash file's name holds: a millisecond time, 13 digits, until the year
/// 2286. Far below 2^63, so the mark fits Kotlin's `Long`.
pub(crate) const MAX_NUMBER: u64 = 9_999_999_999_999;
/// The smallest number a new crash file takes, 13 digits as well, for a clock set before
/// September 2001.
pub(crate) const MIN_NUMBER: u64 = 1_000_000_000_000;
/// How long a panic waits for the directory, or for its core's files, before it gives up its
/// file: a hook that waited for good would keep the process from ending.
pub(super) const PANIC_WAIT: Duration = Duration::from_millis(100);
/// Between two tries of a panic for what another holds.
pub(super) const RETRY: Duration = Duration::from_millis(1);

/// A file with a crash file's name: its number, name and size.
pub(super) type Listed = (u64, String, u64);

/// The number a crash file's name holds: `<epochMs>.txt`, at most [`MAX_NUMBER`], is the one
/// name the core and the app give their crash files.
pub(crate) fn number(name: &str) -> Option<u64> {
    let digits = name.strip_suffix(".txt")?;
    let digits =
        (!digits.is_empty() && digits.bytes().all(|b| b.is_ascii_digit())).then_some(digits);
    digits?.parse().ok().filter(|&at| at <= MAX_NUMBER)
}

/// A crash directory this core holds, until dropped.
pub(super) struct DirLock {
    _file: fs::File,
}

impl DirLock {
    /// Makes the directory, takes its lock, then removes what writes the system ended left: parts
    /// (`<n>.part-core`, `<n>.part-app`) and those of earlier versions (`.core.tmp`, `.app.tmp`,
    /// `.core-<pid>.tmp`, `.app-<random>.tmp`). Waits as long as another holds the lock, or with
    /// `until`, as a panic does, tries until then and fails with `TimedOut`.
    pub(super) fn take(dir: &Path, until: Option<Instant>) -> io::Result<DirLock> {
        fs::create_dir_all(dir)?;
        let mut open = fs::OpenOptions::new();
        let file = open.read(true).write(true).create(true).truncate(false);
        let file = file.open(dir.join(LOCK))?;
        if let Err(e) = lock(&file, until) {
            // Closing a descriptor of the file drops every traditional lock this process holds on
            // it, the app's included: this one stays open.
            std::mem::forget(file);
            return Err(e);
        }
        for entry in fs::read_dir(dir)? {
            let entry = entry?;
            if entry.file_name().to_str().is_some_and(left_over) {
                let _ = fs::remove_file(entry.path());
            }
        }
        Ok(DirLock { _file: file })
    }
}

/// Takes the write lock on the whole of `file`: waits as long as another holds it, or with `until`
/// tries until then.
fn lock(file: &fs::File, until: Option<Instant>) -> io::Result<()> {
    // SAFETY: a zeroed flock is a valid one; l_len 0 covers the whole file, as the JVM's does.
    let mut lock: libc::flock = unsafe { std::mem::zeroed() };
    lock.l_type = libc::F_WRLCK as libc::c_short;
    lock.l_whence = libc::SEEK_SET as libc::c_short;
    let wait = if until.is_some() {
        libc::F_OFD_SETLK
    } else {
        libc::F_OFD_SETLKW
    };
    // SAFETY: `lock` outlives each call, and the descriptor is open.
    while unsafe { libc::fcntl(file.as_raw_fd(), wait, &lock) } != 0 {
        if let Some(pause) = retry(io::Error::last_os_error(), until, Instant::now())? {
            std::thread::sleep(pause);
        }
    }
    Ok(())
}

/// What a try of the lock that failed with `e` at `now` leads to: another try at once after an
/// interruption, one after [`RETRY`] while another holds the lock, or an error. Past `until`, an
/// interrupted try gives up too (`TimedOut`): signals that keep coming cannot hold a panic.
pub(super) fn retry(
    e: io::Error,
    until: Option<Instant>,
    now: Instant,
) -> io::Result<Option<Duration>> {
    let held = matches!(e.raw_os_error(), Some(libc::EAGAIN | libc::EACCES));
    let interrupted = e.kind() == ErrorKind::Interrupted;
    match until {
        Some(until) if (held || interrupted) && now >= until => Err(ErrorKind::TimedOut.into()),
        _ if interrupted => Ok(None),
        Some(_) if held => Ok(Some(RETRY)),
        _ => Err(e),
    }
}

/// The files with a crash file's name, empty ones included, oldest first: by number, then by
/// name. A directory that cannot be listed, or an entry that cannot be read, is an error; a file
/// gone meanwhile is passed over.
pub(super) fn scan(dir: &Path) -> io::Result<Vec<Listed>> {
    let mut files = Vec::new();
    for entry in fs::read_dir(dir)? {
        let entry = entry?;
        let Ok(name) = entry.file_name().into_string() else {
            continue;
        };
        let Some(at) = number(&name) else {
            continue;
        };
        match entry.metadata() {
            Ok(meta) if meta.is_file() => files.push((at, name, meta.len())),
            Err(e) if e.kind() != ErrorKind::NotFound => return Err(e),
            _ => {}
        }
    }
    files.sort();
    Ok(files)
}

/// The mark a clear saved in [`MARK`]: 0 only when none was ever saved. A mark that cannot be
/// read, or holds anything but a number up to [`MAX_NUMBER`] and white space, is an error: read as
/// 0, it could give a cleared name again.
pub(super) fn saved_mark(dir: &Path) -> io::Result<u64> {
    let mut file = match fs::File::open(dir.join(MARK)) {
        Err(e) if e.kind() == ErrorKind::NotFound => return Ok(0),
        opened => opened?,
    };
    // Read to the end, so nothing may follow the number: a mark is 13 digits, a buffer that
    // fills holds more than one.
    let mut digits = [0_u8; 32];
    let mut read = 0;
    while read < digits.len() {
        match file.read(&mut digits[read..]) {
            Ok(0) => break,
            Ok(n) => read += n,
            Err(e) if e.kind() == ErrorKind::Interrupted => {}
            Err(e) => return Err(e),
        }
    }
    let digits = (read < digits.len())
        .then(|| std::str::from_utf8(&digits[..read]).ok())
        .flatten();
    let mark = digits.and_then(|d| d.trim().parse().ok());
    mark.filter(|&m| m <= MAX_NUMBER)
        .ok_or_else(|| ErrorKind::InvalidData.into())
}

/// Removes `files`, as far as they go: what earlier versions left, which a later write or clear
/// tries again.
pub(super) fn discard(dir: &Path, files: &[Listed]) {
    for (_, name, _) in files {
        let _ = fs::remove_file(dir.join(name));
    }
}

/// A part of a write that never ended, of this version or an earlier one.
fn left_over(name: &str) -> bool {
    let old = (name.starts_with(".core") || name.starts_with(".app")) && name.ends_with(".tmp");
    name.contains(".part-") || old
}
