//! Crash files: the names they take, the mark that keeps a cleared name from coming back, and
//! the stack, taken before the files and naming only known objects.

use super::crash;
use super::tests::{crashes, panic_on, read};
use crate::testutil::TempDir;
use std::fs;
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::Ordering::Relaxed;

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
    assert_eq!(left(&dir), std::slice::from_ref(&max));
    panic_on(&diag, || panic!("debugFault"));
    assert_eq!(left(&dir), [max]);
    let last = *diag.events(None).0.last().unwrap();
    assert_eq!((last.name, last.detail), ("panic", Some("invalid_data")));
}

/// A cleared name is never taken again, though the clock stands still or comes back, nor after
/// the core starts again: a clear sent twice cannot remove a newer file.
#[test]
fn a_cleared_name_is_never_taken_again() {
    const T: u64 = 1_795_300_000_000;
    let (dir, diag) = crashes("diag-crash-replay");
    let crashes = diag.crashes().unwrap();
    crashes.clock.store(T, Relaxed);
    let first = crashes.save("source: core\n").unwrap();
    assert_eq!(first, format!("{T}.txt"));
    let exported = [first.clone()];
    let mark = crashes.mark();
    assert_eq!(mark, T);
    assert_eq!(crashes.remove(&exported, mark).unwrap(), Some(1));
    let second = crashes.save("source: core\n").unwrap();
    assert_eq!(second, format!("{}.txt", T + 1));
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
        crashes.remove(&[], T + 2).unwrap(),
        None,
        "above the core's own"
    );
    let again = crash::Crashes::new(dir.path().join(crash::DIR));
    again.clock.store(T, Relaxed);
    assert_eq!(
        again.save("source: core\n").unwrap(),
        format!("{}.txt", T + 2),
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

/// The app writes into the same directory at any time (`CrashFiles.kt`, from its crash handler),
/// by the same rules, under the same lock. Writing at once, neither side takes a name the other
/// holds, and five remain.
#[test]
fn the_app_writing_at_once_takes_no_name_twice_and_five_remain() {
    const ROUNDS: usize = 200;
    const T: u64 = 1_795_300_000_000;
    let (dir, diag) = crashes("diag-crash-race");
    let at = dir.path().join(crash::DIR);
    fs::create_dir_all(&at).unwrap();
    let crashes = diag.crashes().unwrap();
    // A clock that stands still makes both sides want the same next number.
    crashes.clock.store(T, Relaxed);
    let app = std::thread::spawn({
        let at = at.clone();
        move || (0..ROUNDS).map(|_| app_write(&at, T)).collect::<Vec<_>>()
    });
    let mut names: Vec<String> = (0..ROUNDS)
        .filter_map(|_| crashes.save("source: core\n").ok())
        .collect();
    names.extend(app.join().unwrap());
    let taken = names.len();
    assert_eq!(taken, 2 * ROUNDS, "a write failed");
    names.sort();
    names.dedup();
    assert_eq!(names.len(), taken, "a name was taken twice");
    assert_eq!(numbers(&at).len(), crash::MAX_FILES, "{:?}", entries(&dir));
    assert_eq!(left(&dir).len(), crash::MAX_FILES, "a part left");
}

/// The crash files' numbers in `dir`, lowest first.
fn numbers(dir: &Path) -> Vec<u64> {
    let mut numbers: Vec<u64> = fs::read_dir(dir)
        .unwrap()
        .filter_map(|e| {
            e.ok()?
                .file_name()
                .into_string()
                .ok()?
                .strip_suffix(".txt")?
                .parse()
                .ok()
        })
        .collect();
    numbers.sort_unstable();
    numbers
}

/// One write of the app's, as `CrashFiles.write` makes it: under the lock, it removes parts, takes
/// a number above the mark, the files and the time, writes its part, makes room and renames it.
fn app_write(dir: &Path, now: u64) -> String {
    let _held = app_lock(dir);
    for entry in fs::read_dir(dir).unwrap().flatten() {
        if entry.file_name().to_str().unwrap().contains(".part-") {
            fs::remove_file(entry.path()).unwrap();
        }
    }
    let present = numbers(dir);
    let mark = fs::read_to_string(dir.join(".core.mark")).map_or(0, |m| m.trim().parse().unwrap());
    let at = (mark.max(present.last().copied().unwrap_or(0)) + 1).max(now);
    let part = dir.join(format!("{at}.part-app"));
    fs::write(&part, "source: app\n").unwrap();
    for n in &present[..(present.len() + 1).saturating_sub(crash::MAX_FILES)] {
        fs::remove_file(dir.join(format!("{n}.txt"))).unwrap();
    }
    let name = format!("{at}.txt");
    fs::rename(&part, dir.join(&name)).unwrap();
    name
}

/// The directory's lock as the app takes it (`CrashFiles.kt`): the JVM's `FileChannel.lock`, a
/// traditional fcntl lock on the whole of `.lock`, released when the file closes.
fn app_lock(dir: &Path) -> fs::File {
    use std::os::fd::AsRawFd;
    let file = fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .open(dir.join(".lock"))
        .unwrap();
    // SAFETY: a zeroed flock is a valid one; the fields set make it a write lock on the whole file.
    let mut lock: libc::flock = unsafe { std::mem::zeroed() };
    lock.l_type = libc::F_WRLCK as _;
    lock.l_whence = libc::SEEK_SET as _;
    // SAFETY: `lock` lives across the call, and the descriptor is open.
    assert_eq!(
        unsafe { libc::fcntl(file.as_raw_fd(), libc::F_SETLKW, &lock) },
        0
    );
    file
}

/// The crash files present and anything else but the lock, sorted.
fn left(dir: &TempDir) -> Vec<String> {
    entries(dir).into_iter().filter(|n| n != ".lock").collect()
}

/// While the app holds the directory, the core writes nothing; then it takes a number above the
/// mark it finds, as a clear may have saved one meanwhile.
#[test]
fn the_core_waits_for_the_apps_lock_and_reads_the_mark_then() {
    let (dir, diag) = crashes("diag-crash-lock");
    let at = dir.path().join(crash::DIR);
    fs::create_dir_all(&at).unwrap();
    let crashes = diag.crashes().unwrap();
    crashes.clock.store(1_795_300_000_000, Relaxed);
    let held = app_lock(&at);
    std::thread::scope(|s| {
        let core = s.spawn(|| crashes.save("source: core\n").unwrap());
        std::thread::sleep(std::time::Duration::from_millis(200));
        let early = numbers(&at);
        fs::write(at.join(".core.mark"), "1795300000500").unwrap();
        drop(held);
        let name = core.join().unwrap();
        assert!(early.is_empty(), "written while the app held the lock");
        assert_eq!(name, "1795300000501.txt");
    });
}

/// A core that read the mark before another one cleared does not take the cleared name: the mark
/// is read again under the lock.
#[test]
fn a_core_takes_no_name_another_cleared() {
    let (dir, diag) = crashes("diag-crash-two-cores");
    let first = diag.crashes().unwrap();
    let second = crash::Crashes::new(dir.path().join(crash::DIR));
    first.clock.store(1_795_300_000_000, Relaxed);
    second.clock.store(1_795_300_000_000, Relaxed);
    assert!(second.list().is_empty());
    let name = first.save("source: core\n").unwrap();
    let cleared = std::slice::from_ref(&name);
    assert_eq!(first.remove(cleared, first.mark()).unwrap(), Some(1));
    assert_ne!(second.save("source: core\n").unwrap(), name);
}

/// A clock set before September 2001 still gives a 13-digit name, as the app's do.
#[test]
fn a_name_has_thirteen_digits_however_early_the_clock() {
    let (_dir, diag) = crashes("diag-crash-floor");
    let crashes = diag.crashes().unwrap();
    crashes.clock.store(946_684_800_000, Relaxed);
    assert_eq!(crashes.save("source: core\n").unwrap(), "1000000000000.txt");
}

/// What a write the system ended left, the app's or the core's, of this version or an earlier
/// one, is removed by the next write, however recent: the lock proves no write is running.
#[test]
fn a_write_removes_what_ended_writes_left_at_once() {
    let (dir, diag) = crashes("diag-crash-sweep");
    let at = dir.path().join(crash::DIR);
    fs::create_dir_all(&at).unwrap();
    let left_over = [
        "1795300000100.part-app",
        "1795300000200.part-core",
        ".app.tmp",
        ".core.tmp",
        ".app-4242.tmp",
        ".core-77.tmp",
    ];
    for name in left_over.into_iter().chain(["1795300000000.txt"]) {
        fs::write(at.join(name), "x").unwrap();
    }
    let crashes = diag.crashes().unwrap();
    crashes.clock.store(1_795_300_000_300, Relaxed);
    let new = crashes.save("source: core\n").unwrap();
    assert_eq!(left(&dir), ["1795300000000.txt".to_owned(), new]);
}

/// The oldest file goes before the new one is published, so six never exist: a publish that
/// fails after the trim leaves four.
#[test]
fn the_oldest_file_goes_before_the_new_one_is_published() {
    let (dir, diag) = crashes("diag-crash-trim-first");
    let at = dir.path().join(crash::DIR);
    fs::create_dir_all(&at).unwrap();
    for n in 1..=5 {
        fs::write(at.join(format!("179530000000{n}.txt")), "x").unwrap();
    }
    let crashes = diag.crashes().unwrap();
    crashes.clock.store(1_795_300_000_100, Relaxed);
    // A directory holds the new name, so the rename fails.
    fs::create_dir(at.join("1795300000100.txt")).unwrap();
    assert!(crashes.save("source: core\n").is_err());
    let names: Vec<String> = crashes.list().into_iter().map(|f| f.name).collect();
    assert_eq!(
        names,
        (2..=5)
            .map(|n| format!("179530000000{n}.txt"))
            .collect::<Vec<_>>()
    );
    assert_eq!(left(&dir).len(), 5, "four files and the directory, no part");
}

/// A file that will not go stops the write: published, it would make six. The part goes too.
#[test]
fn a_file_that_will_not_go_stops_the_write() {
    let (dir, diag) = crashes("diag-crash-stuck");
    let at = dir.path().join(crash::DIR);
    fs::create_dir_all(&at).unwrap();
    let old: Vec<String> = (1..=5).map(|n| format!("179530000000{n}.txt")).collect();
    for name in &old {
        fs::write(at.join(name), "x").unwrap();
    }
    let crashes = diag.crashes().unwrap();
    crashes.stuck.store(true, Relaxed);
    let failed = crashes.save("source: core\n").unwrap_err();
    assert_eq!(failed.kind(), std::io::ErrorKind::PermissionDenied);
    assert_eq!(left(&dir), old);
    crashes.stuck.store(false, Relaxed);
    crashes.save("source: core\n").unwrap();
    assert_eq!(left(&dir)[..4], old[1..]);
}

/// Runs a panic on a core's thread while `hold` keeps the crash directory, and answers whether the
/// panic ended within two seconds. If not, `hold` is dropped, so a hook that waits ends.
fn panics_while<H>(diag: &Arc<super::Diag>, hold: &mut Option<H>) -> bool {
    let (done, ended) = std::sync::mpsc::channel();
    let target = Arc::clone(diag);
    let panicking = std::thread::spawn(move || {
        panic_on(&target, || panic!("debugFault"));
        let _ = done.send(());
    });
    let ended = ended
        .recv_timeout(std::time::Duration::from_secs(2))
        .is_ok();
    if !ended {
        *hold = None;
    }
    panicking.join().unwrap();
    ended
}

/// A panic while the app holds the directory waits about 100 ms for it, then writes no file: a
/// hook that waited longer could keep the process from ending. The same panic again does not
/// wait: in every flow, it would wait each time.
#[test]
fn a_panic_gives_up_on_a_directory_the_app_holds() {
    let (dir, diag) = crashes("diag-crash-held");
    let at = dir.path().join(crash::DIR);
    fs::create_dir_all(&at).unwrap();
    let mut held = Some(app_lock(&at));
    assert!(
        panics_while(&diag, &mut held),
        "the panic waited for the lock"
    );
    assert!(
        panics_while(&diag, &mut held),
        "the same panic waited again"
    );
    drop(held);
    assert!(numbers(&at).is_empty(), "{:?}", entries(&dir));
    let events = diag.events(None).0;
    let details: Vec<_> = events.iter().rev().take(2).map(|e| e.detail).collect();
    assert_eq!(details, [Some("repeated"), Some("timed_out")]);
}

/// A clear holds the core's files while it waits for the directory, which it may; a panic on
/// another thread meanwhile gives up within the same bound.
#[test]
fn a_panic_gives_up_while_a_clear_waits_for_the_directory() {
    let (dir, diag) = crashes("diag-crash-clearing");
    let at = dir.path().join(crash::DIR);
    fs::create_dir_all(&at).unwrap();
    let held = app_lock(&at);
    let clearing = std::thread::spawn({
        let diag = Arc::clone(&diag);
        move || diag.crashes().unwrap().remove(&[], 0).map(|_| ())
    });
    std::thread::sleep(std::time::Duration::from_millis(100));
    let mut held = Some(held);
    assert!(
        panics_while(&diag, &mut held),
        "the panic waited for the clear"
    );
    drop(held);
    clearing.join().unwrap().unwrap();
}

/// An empty `<n>.txt` is a placeholder an earlier version claimed, never a crash file: it is not
/// listed, so no export takes it.
#[test]
fn an_empty_file_is_no_crash_file() {
    let (dir, diag) = crashes("diag-crash-empty");
    let at = dir.path().join(crash::DIR);
    fs::create_dir_all(&at).unwrap();
    fs::write(at.join("1795300000000.txt"), "x").unwrap();
    fs::write(at.join("1795300000001.txt"), "").unwrap();
    let names: Vec<String> = diag
        .crashes()
        .unwrap()
        .list()
        .into_iter()
        .map(|f| f.name)
        .collect();
    assert_eq!(names, ["1795300000000.txt"]);
}

/// What earlier versions left is put right under the lock before anything is counted: the empty
/// placeholders go, and six files are trimmed to five. The new name is above every name seen,
/// the placeholders' included, and a clear saves a mark no lower.
#[test]
fn the_lock_removes_placeholders_and_trims_what_earlier_versions_left() {
    let (dir, diag) = crashes("diag-crash-earlier");
    let at = dir.path().join(crash::DIR);
    let earlier = || {
        fs::create_dir_all(&at).unwrap();
        for n in 1..=6 {
            fs::write(at.join(format!("179530000000{n}.txt")), "x").unwrap();
        }
        fs::write(at.join("1795300000009.txt"), "").unwrap();
    };
    earlier();
    let crashes = diag.crashes().unwrap();
    crashes.clock.store(1_795_300_000_000, Relaxed);
    assert_eq!(crashes.save("source: core\n").unwrap(), "1795300000010.txt");
    let kept = ["3", "4", "5", "6"].map(|n| format!("179530000000{n}.txt"));
    assert_eq!(left(&dir)[..4], kept, "{:?}", left(&dir));
    assert_eq!(left(&dir).len(), 5);

    fs::remove_dir_all(&at).unwrap();
    earlier();
    let again = crash::Crashes::new(at.clone());
    assert_eq!(again.remove(&[], 0).unwrap(), Some(0));
    assert_eq!(
        numbers(&at),
        (2..=6).map(|n| 1_795_300_000_000 + n).collect::<Vec<_>>()
    );
    let mark: u64 = fs::read_to_string(at.join(".core.mark"))
        .unwrap()
        .trim()
        .parse()
        .unwrap();
    assert!(mark >= 1_795_300_000_009, "{mark}");
}

/// Only a mark never saved reads as none. One that cannot be read, or that holds no number, stops
/// the write and the clear: read as none, a cleared name could be taken again. So does a
/// directory that cannot be listed.
#[test]
fn a_mark_or_directory_that_cannot_be_read_stops_writes_and_clears() {
    use std::os::unix::fs::PermissionsExt;
    let (dir, diag) = crashes("diag-crash-unread");
    let at = dir.path().join(crash::DIR);
    fs::create_dir_all(at.join(".core.mark")).unwrap();
    let crashes = diag.crashes().unwrap();
    assert!(
        crashes.save("source: core\n").is_err(),
        "a mark that is a directory"
    );
    assert!(crashes.remove(&[], 0).is_err());
    fs::remove_dir(at.join(".core.mark")).unwrap();
    fs::write(at.join(".core.mark"), "17953000x").unwrap();
    assert!(
        crashes.save("source: core\n").is_err(),
        "a mark that holds no number"
    );
    assert!(crashes.remove(&[], 0).is_err());
    assert!(numbers(&at).is_empty());

    fs::remove_file(at.join(".core.mark")).unwrap();
    fs::set_permissions(&at, fs::Permissions::from_mode(0o300)).unwrap();
    let unlisted = crashes.save("source: core\n");
    fs::set_permissions(&at, fs::Permissions::from_mode(0o700)).unwrap();
    assert!(unlisted.is_err(), "a directory that cannot be listed");
    assert!(numbers(&at).is_empty());
}
