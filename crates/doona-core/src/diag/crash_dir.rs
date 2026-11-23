//! The lock on a crash directory, which the core (`crash.rs`) and the app (`CrashFiles.kt`) both
//! take. Only its holder takes a number, trims the files to five, publishes one, removes what
//! ended writes left or clears; so the core's or the app's next write may remove every part it
//! finds, however recent.
//!
//! The app takes it with the JVM's `FileChannel.lock`, a traditional fcntl lock on the whole
//! file. Such a lock belongs to the process, so it would not keep out the core in the same
//! process, and `flock` does not see it at all. The core takes an open file description lock
//! (`F_OFD_SETLKW`), which conflicts with a traditional one even within one process (fcntl(2)),
//! and with that of another description, as of a second core.

use std::fs;
use std::io::{self, ErrorKind};
use std::os::fd::AsRawFd;
use std::path::Path;

/// The lock's file; no crash file's name.
const LOCK: &str = ".lock";
/// The largest number a crash file's name holds: a millisecond time, 13 digits, until the year
/// 2286. Far below 2^63, so the mark fits Kotlin's `Long`.
pub(crate) const MAX_NUMBER: u64 = 9_999_999_999_999;
/// The smallest number a new crash file takes, 13 digits as well, for a clock set before
/// September 2001.
pub(crate) const MIN_NUMBER: u64 = 1_000_000_000_000;

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
    /// Makes the directory, waits for its lock, then removes what writes the system ended left:
    /// parts (`<n>.part-core`, `<n>.part-app`) and those of earlier versions (`.core.tmp`,
    /// `.app.tmp`, `.core-<pid>.tmp`, `.app-<random>.tmp`).
    pub(super) fn take(dir: &Path) -> io::Result<DirLock> {
        fs::create_dir_all(dir)?;
        let mut open = fs::OpenOptions::new();
        let file = open.read(true).write(true).create(true).truncate(false);
        let file = file.open(dir.join(LOCK))?;
        // SAFETY: a zeroed flock is a valid one; l_len 0 covers the whole file, as the JVM's does.
        let mut lock: libc::flock = unsafe { std::mem::zeroed() };
        lock.l_type = libc::F_WRLCK as libc::c_short;
        lock.l_whence = libc::SEEK_SET as libc::c_short;
        // SAFETY: `lock` outlives each call, and the descriptor is open.
        while unsafe { libc::fcntl(file.as_raw_fd(), libc::F_OFD_SETLKW, &lock) } != 0 {
            let e = io::Error::last_os_error();
            if e.kind() != ErrorKind::Interrupted {
                // Closing a descriptor of the file drops every traditional lock this process
                // holds on it, the app's included: this one stays open.
                std::mem::forget(file);
                return Err(e);
            }
        }
        for entry in fs::read_dir(dir)?.flatten() {
            if entry.file_name().to_str().is_some_and(left_over) {
                let _ = fs::remove_file(entry.path());
            }
        }
        Ok(DirLock { _file: file })
    }
}

/// A part of a write that never ended, of this version or an earlier one.
fn left_over(name: &str) -> bool {
    let old = (name.starts_with(".core") || name.starts_with(".app")) && name.ends_with(".tmp");
    name.contains(".part-") || old
}
