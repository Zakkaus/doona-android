//! The lock on a crash directory, which the core (`crash.rs`) and the app (`CrashFiles.kt`) both
//! take. Only its holder takes a number, trims the files to five, publishes one, removes what
//! ended writes left or clears; so the core's or the app's next write may remove every part it
//! finds, however recent.
//!
//! The app takes it with the JVM's `FileChannel.lock`, a traditional fcntl lock on the whole
//! file. Such a lock belongs to the process, so it would not keep out the core in the same
//! process, and `flock` does not see it at all. The core takes an open file description lock
//! (`F_OFD_SETLKW`), which conflicts with a traditional one even within one process (fcntl(2)),
//! and with that of another description, as of a core in another process. A panic does not
//! wait: it tries (`F_OFD_SETLK`) for [`PANIC_WAIT`], then gives up its file. A clear waits.
//!
//! Closing any descriptor of the lock's file drops every traditional lock the process holds on
//! it, the app's included, so the core never closes one: it opens one descriptor per directory
//! for the process's life ([`SLOTS`]) and takes and releases its lock on that one. Threads of the
//! process share the description, whose lock does not keep them apart, so a mutex does.
//!
//! Earlier versions claimed a name by creating `<n>.txt` empty, and could leave six files. Under
//! the lock an empty `<n>.txt` is such a placeholder, never a crash file: neither listed nor
//! counted, it goes once a mark or a file above it holds its number, and the oldest files past
//! five go too.

use std::fs;
use std::io::{self, ErrorKind, Read as _};
use std::os::fd::AsRawFd;
use std::os::unix::fs::MetadataExt as _;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard, PoisonError, TryLockError};
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

/// A directory's lock descriptor, opened on first use.
type Slot = Mutex<Option<fs::File>>;

/// One slot per directory a core of this process locked, never freed: its descriptor stays open
/// for the process's life, however often its lock fails.
pub(super) static SLOTS: Mutex<Vec<(PathBuf, &'static Slot)>> = Mutex::new(Vec::new());

/// The slot of `dir`, made on its first use. The list of slots is waited for as the slot is:
/// with `until`, only until then.
fn slot(dir: &Path, until: Option<Instant>) -> io::Result<&'static Slot> {
    let mut slots = wait(&SLOTS, until)?;
    if let Some((_, slot)) = slots.iter().find(|(known, _)| known == dir) {
        return Ok(slot);
    }
    let slot: &'static Slot = Box::leak(Box::new(Mutex::new(None)));
    slots.push((dir.to_path_buf(), slot));
    Ok(slot)
}

/// A crash directory this core holds, until dropped: the lock taken on its slot's descriptor,
/// and the slot, which another thread of the process waits for meanwhile.
pub(super) struct DirLock {
    held: MutexGuard<'static, Option<fs::File>>,
}

impl DirLock {
    /// Makes the directory, takes its lock, then removes what writes the system ended left: parts
    /// (`<n>.part-core`, `<n>.part-app`) and those of earlier versions (`.core.tmp`, `.app.tmp`,
    /// `.core-<pid>.tmp`, `.app-<random>.tmp`). Waits as long as another holds the lock, or with
    /// `until`, as a panic does, tries until then and fails with `TimedOut`. A lock that fails
    /// keeps the descriptor in its slot for the next try.
    pub(super) fn take(dir: &Path, until: Option<Instant>) -> io::Result<DirLock> {
        fs::create_dir_all(dir)?;
        let mut held = wait(slot(dir, until)?, until)?;
        let path = dir.join(LOCK);
        let file = match held.take() {
            Some(file) if names(&file, &path) => file,
            old => {
                // A directory made again has a lock file of its own; the old one's descriptor
                // stays open too, as closing it could drop the app's lock.
                if let Some(old) = old {
                    std::mem::forget(old);
                }
                let mut open = fs::OpenOptions::new();
                open.read(true).write(true).create(true).truncate(false);
                open.open(&path)?
            }
        };
        lock(held.insert(file), until)?;
        let taken = DirLock { held };
        for entry in fs::read_dir(dir)? {
            let entry = entry?;
            if entry.file_name().to_str().is_some_and(left_over) {
                let _ = fs::remove_file(entry.path());
            }
        }
        Ok(taken)
    }
}

impl Drop for DirLock {
    /// Releases the lock and keeps the descriptor. Only this description's lock goes: releasing
    /// a whole-file lock, which splits none, does not fail.
    fn drop(&mut self) {
        if let Some(file) = self.held.as_ref() {
            let _ = whole(file, libc::F_UNLCK, libc::F_OFD_SETLK);
        }
    }
}

/// `mutex`, a slot or the list of them, waited for as long as another thread holds it, or with
/// `until`, until then; past it, `TimedOut`.
fn wait<T>(mutex: &'static Mutex<T>, until: Option<Instant>) -> io::Result<MutexGuard<'static, T>> {
    let Some(until) = until else {
        return Ok(mutex.lock().unwrap_or_else(PoisonError::into_inner));
    };
    loop {
        match mutex.try_lock() {
            Ok(held) => return Ok(held),
            Err(TryLockError::Poisoned(held)) => return Ok(held.into_inner()),
            Err(TryLockError::WouldBlock) if Instant::now() < until => std::thread::sleep(RETRY),
            Err(TryLockError::WouldBlock) => return Err(ErrorKind::TimedOut.into()),
        }
    }
}

/// Whether `file` is the one `path` names now.
fn names(file: &fs::File, path: &Path) -> bool {
    match (file.metadata(), fs::metadata(path)) {
        (Ok(open), Ok(named)) => open.dev() == named.dev() && open.ino() == named.ino(),
        _ => false,
    }
}

/// Takes the write lock on the whole of `file`: waits as long as another holds it, or with `until`
/// tries until then.
fn lock(file: &fs::File, until: Option<Instant>) -> io::Result<()> {
    let wait = if until.is_some() {
        libc::F_OFD_SETLK
    } else {
        libc::F_OFD_SETLKW
    };
    while let Err(e) = whole(file, libc::F_WRLCK, wait) {
        if let Some(pause) = retry(e, until, Instant::now())? {
            std::thread::sleep(pause);
        }
    }
    Ok(())
}

/// Sets a lock of `kind` (`F_WRLCK`, `F_UNLCK`) on the whole of `file` with `command`.
fn whole(file: &fs::File, kind: libc::c_int, command: libc::c_int) -> io::Result<()> {
    // SAFETY: a zeroed flock is a valid one; l_len 0 covers the whole file, as the JVM's does.
    let mut lock: libc::flock = unsafe { std::mem::zeroed() };
    lock.l_type = kind as libc::c_short;
    lock.l_whence = libc::SEEK_SET as libc::c_short;
    // SAFETY: `lock` outlives the call, and the descriptor is open.
    match unsafe { libc::fcntl(file.as_raw_fd(), command, &lock) } {
        0 => Ok(()),
        _ => Err(io::Error::last_os_error()),
    }
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
