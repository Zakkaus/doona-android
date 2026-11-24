//! The crash directory's lock: a listing never holds six nor hides a scan that failed, a lock
//! that fails keeps no descriptor of its own, and a panic waits for the list of slots only until
//! its deadline.

use super::crash;
use super::crash_dir::{DirLock, SLOTS};
use super::tests::crashes;
use super::tests_crash::{app_lock, numbers};
use crate::testutil::TempDir;
use std::fs;
use std::io::ErrorKind;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, PoisonError, mpsc};
use std::time::{Duration, Instant};

/// Taken by the tests that hold the list of slots or need it free: a try with no time to wait
/// fails while another holds it.
static SLOTS_TESTS: Mutex<()> = Mutex::new(());

fn slots_tests() -> std::sync::MutexGuard<'static, ()> {
    SLOTS_TESTS.lock().unwrap_or_else(PoisonError::into_inner)
}

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

/// A listing whose scan fails is an error, never an empty list: `diagnostics` then fails and the
/// app exports its own listing, instead of a report that holds no crash.
#[test]
fn a_listing_that_cannot_scan_the_directory_fails() {
    use std::sync::atomic::Ordering::Relaxed;
    let (_dir, diag, _at) = six("diag-dir-unscanned");
    let crashes = diag.crashes().unwrap();
    crashes.unscanned.store(true, Relaxed);
    assert!(crashes.listing().is_err());
    crashes.unscanned.store(false, Relaxed);
    assert_eq!(crashes.list().len(), 5);
}

/// A lock that fails keeps no descriptor of its own: the directory's one serves every try, so a
/// panic refused again and again does not use up the process's descriptors. None is closed, as
/// that would drop the app's lock.
#[test]
fn refused_tries_of_the_lock_open_no_descriptor_each() {
    let _serial = slots_tests();
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
    let refused =
        |ms| DirLock::take(&at, Some(Instant::now() + Duration::from_millis(ms))).is_err();
    // The first try waits a little, so a list of slots another test holds for a moment does not
    // stop it before it opens the descriptor.
    assert!(refused(50));
    assert_eq!(open(), 2, "the app's and the core's");
    for _ in 0..200 {
        assert!(refused(0));
    }
    assert_eq!(open(), 2);
    drop(held);
    assert!(DirLock::take(&at, None).is_ok());
    assert_eq!(open(), 1);
}

/// A panic's bounded wait covers the list of slots too: while another thread of the process
/// holds it, the panic gives up its file at its deadline, as for the slot and the lock.
#[test]
fn a_panic_gives_up_while_another_thread_holds_the_slots() {
    let _serial = slots_tests();
    let (dir, _diag) = crashes("diag-dir-slots");
    let at = dir.path().join(crash::DIR);
    let (holding, held) = mpsc::channel();
    let (done, release) = mpsc::channel::<()>();
    let holder = std::thread::spawn(move || {
        let slots = SLOTS.lock().unwrap_or_else(PoisonError::into_inner);
        holding.send(()).unwrap();
        // Release on timeout only to let the test fail if the lock waits indefinitely.
        let released = release.recv_timeout(Duration::from_secs(2)).is_ok();
        drop(slots);
        released
    });
    held.recv().unwrap();
    let begun = Instant::now();
    let taken = DirLock::take(&at, Some(begun + Duration::from_millis(20)));
    // A holder that let go on its own, as with a wait that outlasted it, no longer listens.
    let _ = done.send(());
    assert!(
        holder.join().unwrap(),
        "the lock waited for the slots to be released"
    );
    assert_eq!(taken.err().map(|e| e.kind()), Some(ErrorKind::TimedOut));
    assert!(DirLock::take(&at, None).is_ok());
}
