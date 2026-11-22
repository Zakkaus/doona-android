//! Helpers for the unit tests.

use crate::sys;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering::Relaxed};

/// A fresh directory under the system temp dir, removed on drop.
pub struct TempDir(PathBuf);

impl TempDir {
    pub fn new(tag: &str) -> TempDir {
        static NEXT: AtomicU32 = AtomicU32::new(0);
        let n = NEXT.fetch_add(1, Relaxed);
        let dir = std::env::temp_dir().join(format!("doona-core-{tag}-{}-{n}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        TempDir(dir)
    }

    pub fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

/// A duplicate's number and what it refers to, and the far end of its socketpair.
pub struct Probe {
    raw: RawFd,
    identity: (u64, u64),
    _peer: OwnedFd,
}

/// A duplicate as jni.rs makes it, and a probe that tells whether it is closed.
pub fn tun() -> (OwnedFd, Probe) {
    let mut fds = [0; 2];
    // SAFETY: `fds` has room for the two descriptors socketpair writes.
    let r = unsafe {
        libc::socketpair(
            libc::AF_UNIX,
            libc::SOCK_SEQPACKET | libc::SOCK_CLOEXEC,
            0,
            fds.as_mut_ptr(),
        )
    };
    assert_eq!(r, 0);
    // SAFETY: socketpair returned two open descriptors that nothing else owns.
    let (a, b) = unsafe { (OwnedFd::from_raw_fd(fds[0]), OwnedFd::from_raw_fd(fds[1])) };
    let dup = sys::dup_cloexec(a.as_raw_fd()).unwrap();
    let raw = dup.as_raw_fd();
    let probe = Probe {
        identity: identity(raw).unwrap(),
        raw,
        _peer: b,
    };
    (dup, probe)
}

fn identity(fd: RawFd) -> Option<(u64, u64)> {
    // SAFETY: fstat only writes the struct it is given.
    let mut st: libc::stat = unsafe { std::mem::zeroed() };
    // SAFETY: `st` is valid for writes.
    (unsafe { libc::fstat(fd, &mut st) } == 0).then_some((st.st_dev, st.st_ino))
}

/// The duplicate is closed: its number is free, or another test reopened it for something else.
pub fn closed(p: &Probe) -> bool {
    // SAFETY: F_GETFD only reads the descriptor's flags.
    let open = unsafe { libc::fcntl(p.raw, libc::F_GETFD) } != -1;
    !open || identity(p.raw) != Some(p.identity)
}
