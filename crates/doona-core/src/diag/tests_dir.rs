//! The crash directory's lock: a listing never holds six, and a lock that fails keeps no
//! descriptor of its own.

use super::crash;
use super::crash_dir::DirLock;
use super::tests::crashes;
use super::tests_crash::{app_lock, numbers};
use crate::testutil::TempDir;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Instant;

/// Six files, as an earlier version could leave them, in the crash directory of a new core.
fn six(tag: &str) -> (TempDir, Arc<super::Diag>, PathBuf) {
    let (dir, diag) = crashes(tag);
    let at = dir.path().join(crash::DIR);
    fs::create_dir_all(&at).unwrap();
    for n in 1..=6 {
        fs::write(at.join(format!("179530000000{n}.txt")), "x").unwrap();
    }
    (dir, diag, at)
}

/// A listing that cannot take the directory's lock is an error: it never lists six, and the
/// export takes the app's own listing instead.
#[test]
fn a_listing_that_cannot_take_the_lock_fails() {
    let (_dir, diag, at) = six("diag-dir-unlocked");
    // The lock's name is a directory: it cannot be opened.
    fs::create_dir(at.join(".lock")).unwrap();
    let crashes = diag.crashes().unwrap();
    assert!(crashes.listing().is_err());
    fs::remove_dir(at.join(".lock")).unwrap();
    assert_eq!(crashes.list().len(), 5);
}

/// A listing that cannot remove the oldest file past five is an error, as a write is.
#[test]
fn a_listing_that_cannot_trim_to_five_fails() {
    let (_dir, diag, at) = six("diag-dir-untrimmed");
    fs::write(at.join(".lock"), "").unwrap();
    let crashes = diag.crashes().unwrap();
    // No file goes from a directory that cannot be written.
    fs::set_permissions(&at, fs::Permissions::from_mode(0o500)).unwrap();
    let untrimmed = crashes.listing();
    fs::set_permissions(&at, fs::Permissions::from_mode(0o700)).unwrap();
    assert!(untrimmed.is_err());
    assert_eq!(numbers(&at).len(), 6);
    assert_eq!(crashes.list().len(), 5);
    assert_eq!(numbers(&at).len(), 5);
}

/// A lock that fails keeps no descriptor of its own: the directory's one serves every try, so a
/// panic refused again and again does not use up the process's descriptors. None is closed, as
/// that would drop the app's lock.
#[test]
fn refused_tries_of_the_lock_open_no_descriptor_each() {
    let (dir, _diag) = crashes("diag-dir-fds");
    let at = dir.path().join(crash::DIR);
    fs::create_dir_all(&at).unwrap();
    let held = app_lock(&at);
    let lock = fs::canonicalize(at.join(".lock")).unwrap();
    // The descriptors of this process open on the lock file; other tests open none of them.
    let open = || {
        let fds = fs::read_dir("/proc/self/fd").unwrap();
        let targets = fds.filter_map(|fd| fs::read_link(fd.ok()?.path()).ok());
        targets.filter(|target| *target == lock).count()
    };
    let refused = || DirLock::take(&at, Some(Instant::now())).is_err();
    assert!(refused());
    assert_eq!(open(), 2, "the app's and the core's");
    for _ in 0..200 {
        assert!(refused());
    }
    assert_eq!(open(), 2);
    drop(held);
    assert!(DirLock::take(&at, None).is_ok());
    assert_eq!(open(), 1);
}
