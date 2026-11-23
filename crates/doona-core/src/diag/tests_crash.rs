//! Crash files: the names they take, the mark that keeps a cleared name from coming back, and
//! the stack, taken before the files and naming only known objects.

use super::crash;
use super::tests::{crashes, panic_on, read};
use crate::testutil::TempDir;
use std::fs;
use std::sync::Arc;

fn entries(dir: &TempDir) -> Vec<String> {
    let mut names: Vec<String> = fs::read_dir(dir.path().join(crash::DIR))
        .unwrap()
        .map(|e| e.unwrap().file_name().into_string().unwrap())
        .collect();
    names.sort();
    names
}

/// A file whose name holds the largest number a crash file may have leaves no name to take:
/// nothing is written, and the hook does not overflow.
#[test]
fn past_the_largest_number_nothing_is_written() {
    let (dir, diag) = crashes("diag-crash-max");
    let at = dir.path().join(crash::DIR);
    fs::create_dir_all(&at).unwrap();
    let max = format!("{}.txt", crash::MAX_NUMBER);
    fs::write(at.join(&max), "x").unwrap();
    let crashes = diag.crashes().unwrap();
    assert!(crashes.save("source: core\n").is_err());
    assert_eq!(entries(&dir), std::slice::from_ref(&max));
    panic_on(&diag, || panic!("debugFault"));
    assert_eq!(entries(&dir), [max]);
    let last = *diag.events(None).0.last().unwrap();
    assert_eq!((last.name, last.detail), ("panic", Some("invalid_data")));
}

/// A cleared name is never taken again, though the clock stands still or comes back, nor after
/// the core starts again: a clear sent twice cannot remove a newer file.
#[test]
fn a_cleared_name_is_never_taken_again() {
    use std::sync::atomic::Ordering::Relaxed;
    let (dir, diag) = crashes("diag-crash-replay");
    let crashes = diag.crashes().unwrap();
    crashes.clock.store(100, Relaxed);
    let first = crashes.save("source: core\n").unwrap();
    assert_eq!(first, "100.txt");
    let exported = [first.clone()];
    let mark = crashes.mark();
    assert_eq!(mark, 100);
    assert_eq!(crashes.remove(&exported, mark).unwrap(), Some(1));
    let second = crashes.save("source: core\n").unwrap();
    assert_eq!(second, "101.txt");
    assert_eq!(
        crashes.remove(&exported, mark).unwrap(),
        Some(0),
        "the clear sent again"
    );
    assert_eq!(crashes.list().len(), 1, "a crash file and the mark's");
    assert_eq!(
        crashes.remove(std::slice::from_ref(&second), mark).unwrap(),
        Some(0),
        "above the mark given"
    );
    assert_eq!(
        crashes
            .remove(std::slice::from_ref(&second), crashes.mark())
            .unwrap(),
        Some(1)
    );
    assert_eq!(
        crashes.remove(&[], 102).unwrap(),
        None,
        "above the core's own"
    );
    let again = crash::Crashes::new(dir.path().join(crash::DIR));
    again.clock.store(100, Relaxed);
    assert_eq!(
        again.save("source: core\n").unwrap(),
        "102.txt",
        "after a restart"
    );
}

/// The stack is walked and resolved before the crash files are taken: the unwinder and dladdr
/// take the loader's lock, which a thread that panics in a library's constructor holds.
#[test]
fn the_stack_is_taken_before_the_crash_files() {
    let (_dir, diag) = crashes("diag-crash-order-of-locks");
    crate::panics::install();
    let target = Arc::downgrade(&diag);
    let under_lock = std::thread::spawn(move || {
        crash::report_here(&target);
        assert!(std::panic::catch_unwind(|| panic!("debugFault")).is_err());
        crash::stack_under_lock()
    })
    .join()
    .unwrap();
    assert_eq!(under_lock, 0);
    assert_eq!(diag.crashes().unwrap().list().len(), 1);
}

/// A frame names only this library and the system's objects JNI calls pass through; any other
/// object's name, as a host program's taken from its path, is `?`.
#[test]
fn a_frame_names_only_known_objects() {
    let (dir, diag) = crashes("diag-crash-objects");
    panic_on(&diag, || panic!("debugFault"));
    let text = read(&dir, &diag.crashes().unwrap().list()[0].name);
    let trace = text.split("\n\nbacktrace:\n").nth(1).unwrap();
    let names: Vec<&str> = trace
        .lines()
        .map(|l| l.split(' ').nth(1).unwrap())
        .collect();
    let known = [
        "libdoonacore.so",
        "libc.so",
        "libc.so.6",
        "libart.so",
        "app_process64",
        "libc++_shared.so",
        "?",
    ];
    assert!(names.iter().all(|n| known.contains(n)), "{trace}");
    assert!(names.contains(&"libdoonacore.so"), "{trace}");
}
