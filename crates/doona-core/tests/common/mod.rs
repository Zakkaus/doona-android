//! Helpers shared by the integration tests.
// Each test binary compiles this module and uses a different part of it.
#![allow(dead_code)]

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

/// A SOCK_SEQPACKET socketpair: it keeps packet boundaries like a tun, and a read returns 0
/// once every copy of the other end is closed.
pub fn seqpacket_pair() -> (std::os::fd::OwnedFd, std::os::fd::OwnedFd) {
    use std::os::fd::FromRawFd;
    let mut fds = [0; 2];
    // SAFETY: `fds` is valid for two ints.
    let r = unsafe {
        libc::socketpair(
            libc::AF_UNIX,
            libc::SOCK_SEQPACKET | libc::SOCK_CLOEXEC,
            0,
            fds.as_mut_ptr(),
        )
    };
    assert_eq!(r, 0);
    // SAFETY: both descriptors are fresh and owned by nothing else.
    unsafe {
        (
            std::os::fd::OwnedFd::from_raw_fd(fds[0]),
            std::os::fd::OwnedFd::from_raw_fd(fds[1]),
        )
    }
}

/// Polls `cond` every 10 ms until it holds or `limit` passes.
pub fn wait_for(limit: std::time::Duration, mut cond: impl FnMut() -> bool) -> bool {
    let end = std::time::Instant::now() + limit;
    loop {
        if cond() {
            return true;
        }
        if std::time::Instant::now() >= end {
            return false;
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
}

pub mod anytls;
pub mod cert;
pub mod client;
pub mod global;
pub mod servers;
pub mod socks;
pub mod ss;
pub mod tuic;
