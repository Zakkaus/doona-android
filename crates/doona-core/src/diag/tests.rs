//! The ring, its level, and the crash files: their cap, their order, a repeated location, and
//! what a formatted message keeps.

use super::crash::{self, MAX_BYTES, MAX_FILES, Module, Place, place, version_of};
use super::report::Level;
use super::*;
use crate::testutil::{TempDir, alloc};
use std::fs;
use std::sync::Arc;
use std::time::Duration;

fn names(diag: &Diag, at_least: Option<Level>) -> Vec<&'static str> {
    diag.events(at_least).0.iter().map(|e| e.name).collect()
}

#[test]
fn the_ring_keeps_the_newest_events_and_counts_the_others() {
    let diag = Diag::default();
    for i in 0..CAPACITY + 5 {
        diag.record(Event::info("probeRound").count(Count::Probes, i));
    }
    let (events, overwritten) = diag.events(None);
    assert_eq!((events.len(), overwritten), (CAPACITY, 5));
    assert_eq!(events[0].counts[0], Some((Count::Probes, 5)));
    let last = u32::try_from(CAPACITY + 4).unwrap();
    assert_eq!(events[CAPACITY - 1].counts[0], Some((Count::Probes, last)));
    assert!(events.iter().all(|e| e.at_ms > 0));
}

#[test]
fn the_level_decides_what_is_kept_and_a_filter_what_is_returned() {
    let diag = Diag::default();
    assert_eq!(diag.level(), Level::Info);
    diag.record(Event::debug("usage"));
    diag.record(Event::info("select"));
    diag.record(Event::warn("start"));
    diag.record(Event::error("died"));
    diag.set_level(Level::Debug);
    diag.record(Event::debug("nodes"));
    assert_eq!(names(&diag, None), ["select", "start", "died", "nodes"]);
    assert_eq!(names(&diag, Some(Level::Warn)), ["start", "died"]);
    assert_eq!(names(&diag, Some(Level::Error)), ["died"]);
    diag.set_level(Level::Error);
    diag.record(Event::warn("start"));
    diag.try_record(Event::info("select"));
    assert_eq!(
        names(&diag, None).len(),
        4,
        "kept events stay, new ones below error go"
    );
    for level in [Level::Error, Level::Warn, Level::Info, Level::Debug] {
        assert_eq!(Level::of_rank(level.rank()), level);
    }
}

#[test]
fn an_event_holds_two_counts_a_code_and_its_duration() {
    let begun = Instant::now()
        .checked_sub(Duration::from_millis(30))
        .unwrap();
    let event = Event::warn("stopped")
        .count(Count::Tasks, 3_usize)
        .count(Count::Generation, 7_i64)
        .count(Count::Nodes, 1_u32)
        .io(ErrorKind::StorageFull)
        .detail("detached")
        .since(begun);
    assert_eq!(
        event.counts,
        [Some((Count::Tasks, 3)), Some((Count::Generation, 7))]
    );
    assert_eq!(event.code, Some("storage_full"));
    let ms = event.ms.unwrap();
    assert!(ms >= 30, "{ms}");
    let shown = format!("stopped: storage_full detached tasks=3 generation=7 {ms} ms");
    assert_eq!(event.to_string(), shown);
    let huge = Event::info("init").count(Count::Nodes, u64::MAX);
    assert_eq!(huge.counts[0], Some((Count::Nodes, u32::MAX)));
    assert_eq!(Event::died().code, Some("internal"));
    assert_eq!(Event::warning(true).code, Some("local_network"));
    assert_eq!(Event::warning(false).detail, Some("cleared"));
    assert_eq!(Event::stopped(None, true).level, Level::Info);
    let detached = Event::stopped(Some(2), true);
    assert_eq!(
        (detached.level, detached.detail),
        (Level::Warn, Some("detached"))
    );
    assert_eq!(detached.counts[0], Some((Count::Tasks, 2)));
}

/// The cost of the ring: one allocation when the core starts, none per event; and its size full.
#[test]
fn recording_allocates_nothing_and_a_full_ring_is_its_one_buffer() {
    let (diag, _, held) = alloc::peak(Diag::default);
    let ((), allocated) = alloc::allocated(|| {
        for i in 0..CAPACITY * 2 {
            let event = Event::warn("statsWrite").io(ErrorKind::StorageFull);
            diag.record(event.detail("stats.json").count(Count::Generation, i));
        }
    });
    assert_eq!(allocated, 0);
    assert_eq!(diag.events(None).0.len(), CAPACITY);
    let event = std::mem::size_of::<Event>();
    println!("a full ring: {held} bytes, {event} bytes an event, {CAPACITY} events");
    assert!(
        held >= CAPACITY * event && held <= CAPACITY * event + 1024,
        "{held}"
    );
    assert!(held <= 192 * 1024, "{held}");
}

/// Panics on a thread whose panics go to `diag`, and waits for it.
fn panic_on(diag: &Arc<Diag>, panic: fn()) {
    crate::panics::install();
    let target = Arc::downgrade(diag);
    let ended = std::thread::spawn(move || {
        crash::report_here(&target);
        panic();
    })
    .join();
    assert!(ended.is_err());
}

fn crashes(tag: &str) -> (TempDir, Arc<Diag>) {
    let dir = TempDir::new(tag);
    let diag = Arc::new(Diag::new(Some(dir.path().join(crash::DIR))));
    (dir, diag)
}

fn read(dir: &TempDir, name: &str) -> String {
    fs::read_to_string(dir.path().join(crash::DIR).join(name)).unwrap()
}

#[test]
fn a_panic_writes_its_message_location_and_build_once_per_location() {
    let (dir, diag) = crashes("diag-crash");
    let literal: fn() = || panic!("a literal message");
    panic_on(&diag, literal);
    panic_on(&diag, literal);
    let files = diag.crashes().unwrap().list();
    assert_eq!(files.len(), 1, "{files:?}");
    let text = read(&dir, &files[0].name);
    assert_eq!(files[0].bytes, u64::try_from(text.len()).unwrap());
    assert!(text.starts_with("source: core\natEpochMs: "), "{text}");
    let version = format!("\nversion: {} ", env!("CARGO_PKG_VERSION"));
    assert!(text.contains(&version), "{text}");
    assert!(text.contains("\nthread: other\n"), "{text}");
    assert!(text.contains("\nlocation: src/diag/tests.rs:"), "{text}");
    let message = "\nmessage: panic in doonacore::diag::tests\n\nbacktrace:\n0 ";
    assert!(text.contains(message), "{text}");
    let panics: Vec<_> = diag
        .events(None)
        .0
        .into_iter()
        .filter(|e| e.name == "panic")
        .collect();
    assert_eq!(panics.len(), 2);
    assert_eq!(panics[0].code, Some("internal"));
    assert_eq!(
        (panics[0].detail, panics[1].detail),
        (None, Some("repeated"))
    );
}

#[test]
fn the_directory_keeps_the_newest_five_files() {
    let (dir, diag) = crashes("diag-crash-cap");
    let panics: [fn(); 7] = [
        || panic!("first"),
        || panic!("second"),
        || panic!("third"),
        || panic!("fourth"),
        || panic!("fifth"),
        || panic!("sixth"),
        || panic!("seventh"),
    ];
    let location = |name: &str| {
        let text = read(&dir, name);
        let at = text.lines().find_map(|l| l.strip_prefix("location: "));
        at.unwrap().to_owned()
    };
    let mut written = Vec::new();
    for panic in panics {
        panic_on(&diag, panic);
        written.push(location(
            &diag.crashes().unwrap().list().last().unwrap().name,
        ));
    }
    let files = diag.crashes().unwrap().list();
    assert_eq!(files.len(), MAX_FILES);
    let kept: Vec<String> = files.iter().map(|f| location(&f.name)).collect();
    assert_eq!(kept, written[2..]);
}

#[test]
fn files_are_ordered_by_their_number_and_removed_by_name() {
    let (dir, diag) = crashes("diag-crash-order");
    let crashes = diag.crashes().unwrap();
    let names =
        |c: &crash::Crashes| -> Vec<String> { c.list().into_iter().map(|f| f.name).collect() };
    assert_eq!(
        crashes.remove(&["1.txt".to_owned()]).unwrap(),
        0,
        "no directory yet"
    );
    let at = dir.path().join(crash::DIR);
    fs::create_dir_all(&at).unwrap();
    for name in ["100.txt", "20.txt", "3.txt", "notes.txt"] {
        fs::write(at.join(name), name).unwrap();
    }
    assert_eq!(names(crashes), ["3.txt", "20.txt", "100.txt"]);
    let exported = ["20.txt", "7.txt", "notes.txt"].map(str::to_owned);
    assert_eq!(crashes.remove(&exported).unwrap(), 1);
    assert_eq!(names(crashes), ["3.txt", "100.txt"]);
    assert!(at.join("notes.txt").exists());
}

#[test]
fn a_place_names_its_crate_module_and_version() {
    let ours = |file, module| Place::Ours { file, module };
    let theirs = |dir, module| Place::Theirs { dir, module };
    for (file, at, shown) in [
        ("src/api.rs", ours("src/api.rs", "api"), "doonacore::api"),
        ("src/lib.rs", ours("src/lib.rs", ""), "doonacore"),
        (
            "src/api/mod.rs",
            ours("src/api/mod.rs", "api"),
            "doonacore::api",
        ),
        (
            "/home/u/code/doona/crates/doona-core/src/diag/crash.rs",
            ours("src/diag/crash.rs", "diag/crash"),
            "doonacore::diag::crash",
        ),
        (
            "/home/u/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/tokio-1.47.1/src/runtime/park.rs",
            theirs("tokio-1.47.1", "runtime/park"),
            "tokio::runtime::park",
        ),
        (
            "/home/u/.cargo/git/checkouts/honk-5f/8919784/crates/honk-outbound/src/lib.rs",
            theirs("honk-outbound", ""),
            "honk_outbound",
        ),
        (
            "/rustc/abc/library/core/src/option.rs",
            theirs("core", "option"),
            "core::option",
        ),
        (
            "/home/u/target/release/build/doona-core-1f/out/cn.rs",
            Place::Unknown,
            "unknown",
        ),
        ("/home/user name/src/a b.rs", Place::Unknown, "unknown"),
    ] {
        assert_eq!(place(file), at, "{file}");
        assert_eq!(Module(&at).to_string(), shown, "{file}");
    }
    for (dir, split) in [
        ("tokio-1.47.1", ("tokio", Some("1.47.1"))),
        ("sha-1-0.10.6", ("sha-1", Some("0.10.6"))),
        ("rustls-0.23.0-alpha.1", ("rustls", Some("0.23.0-alpha.1"))),
        ("honk-outbound", ("honk-outbound", None)),
        ("core", ("core", None)),
    ] {
        assert_eq!(version_of(dir), split, "{dir}");
    }
}

#[test]
fn a_file_holds_at_most_its_cap() {
    let mut text = String::with_capacity(MAX_BYTES);
    let mut bounded = crash::Bounded(&mut text);
    assert!(std::fmt::Write::write_str(&mut bounded, &"a".repeat(MAX_BYTES - 1)).is_ok());
    assert!(std::fmt::Write::write_str(&mut bounded, "é").is_err());
    assert_eq!(text.len(), MAX_BYTES - 1, "no half character");
}

/// Panics on a thread named `name` whose panics go to `diag`; answers what the hook allocated and
/// how many records the thread logged meanwhile.
fn panic_measured(
    diag: &Arc<Diag>,
    name: &str,
    panic: impl FnOnce() + Send + std::panic::UnwindSafe + 'static,
) -> (usize, usize) {
    crate::panics::install();
    let target = Arc::downgrade(diag);
    let thread = std::thread::Builder::new().name(name.to_owned());
    let run = move || {
        crash::report_here(&target);
        let (caught, logged) = crate::testutil::logs::logged(|| std::panic::catch_unwind(panic));
        assert!(caught.is_err());
        (crash::hook_allocated(), logged)
    };
    thread.spawn(run).unwrap().join().unwrap()
}

fn message(text: &str) -> &str {
    text.lines()
        .find_map(|l| l.strip_prefix("message: "))
        .unwrap()
}

/// ARCH D21: a message the core did not write, literal or formatted, and a thread's name never
/// reach the file.
#[test]
fn a_dependency_s_message_and_a_thread_s_name_stay_out() {
    let (dir, diag) = crashes("diag-crash-d21");
    panic_measured(&diag, "Paris-abc123", || {
        panic!("https://sub.example/api?token=abc123")
    });
    panic_measured(&diag, "Paris-abc123", || {
        let (token, node, port) = ("abc123", "Paris", 443);
        panic!("token {token} node {node} port {port}")
    });
    let files = diag.crashes().unwrap().list();
    assert_eq!(files.len(), 2, "{files:?}");
    for file in files {
        let text = read(&dir, &file.name);
        for leak in [
            "abc123",
            "sub.example",
            "token",
            "Paris",
            "https",
            "port 443",
        ] {
            assert!(!text.contains(leak), "{leak}: {text}");
        }
        assert_eq!(message(&text), "panic in doonacore::diag::tests", "{text}");
        assert!(text.contains("\nthread: other\n"), "{text}");
    }
}

/// The core's own panic sites keep their messages: they hold no data.
#[test]
fn the_core_s_own_literal_messages_are_kept() {
    let (dir, diag) = crashes("diag-crash-own");
    let sites: [fn(); 3] = [
        || panic!("debugFault"),
        || unreachable!(),
        || unreachable!("stream_transport returned an unknown transport"),
    ];
    for site in sites {
        panic_on(&diag, site);
    }
    let files = diag.crashes().unwrap().list();
    let messages: Vec<String> = files
        .iter()
        .map(|f| message(&read(&dir, &f.name)).to_owned())
        .collect();
    assert_eq!(
        messages,
        [
            "debugFault",
            "internal error: entered unreachable code",
            "internal error: entered unreachable code: stream_transport returned an unknown transport",
        ]
    );
}

/// Only names the core or the app give crash files are listed: a DNS name, a note or a part
/// written are not.
#[test]
fn only_crash_file_names_are_listed() {
    let (dir, diag) = crashes("diag-crash-names");
    let at = dir.path().join(crash::DIR);
    fs::create_dir_all(&at).unwrap();
    for name in [
        "100.txt",
        "20.txt",
        "notes.txt",
        "node.example.txt",
        "1795000000000-app.txt",
        "partial.tmp",
        ".core.tmp",
        "99999999999999999999.txt",
    ] {
        fs::write(at.join(name), name).unwrap();
    }
    let names: Vec<String> = diag
        .crashes()
        .unwrap()
        .list()
        .into_iter()
        .map(|f| f.name)
        .collect();
    assert_eq!(names, ["20.txt", "100.txt"]);
}

/// A backtrace is a list of offsets in named objects: no path, no symbol.
#[test]
fn a_backtrace_holds_module_offsets_and_no_path() {
    let (dir, diag) = crashes("diag-crash-offsets");
    panic_on(&diag, || panic!("traced"));
    let text = read(&dir, &diag.crashes().unwrap().list()[0].name);
    println!("{text}");
    let trace = text.split("\n\nbacktrace:\n").nth(1).unwrap();
    let frames: Vec<&str> = trace.lines().collect();
    assert!(frames.len() >= 3, "{trace}");
    for (i, frame) in frames.iter().enumerate() {
        let parts: Vec<&str> = frame.split(' ').collect();
        let offset = parts.get(2).and_then(|o| o.strip_prefix("0x"));
        let good = parts.len() == 3
            && parts[0] == i.to_string()
            && !parts[1].is_empty()
            && !parts[1].contains('/')
            && offset.is_some_and(|o| u64::from_str_radix(o, 16).is_ok());
        assert!(good, "{frame}\n{trace}");
    }
}

/// Two panics at once never leave more than five files.
#[test]
fn concurrent_publishes_keep_five_files() {
    let (dir, diag) = crashes("diag-crash-race");
    let crashes = diag.crashes().unwrap();
    for round in 0..20 {
        let barrier = std::sync::Barrier::new(8);
        std::thread::scope(|s| {
            for _ in 0..8 {
                s.spawn(|| {
                    barrier.wait();
                    crashes.save("source: core\n").unwrap();
                });
            }
        });
        let n = fs::read_dir(dir.path().join(crash::DIR)).unwrap().count();
        assert!(n <= MAX_FILES, "round {round}: {n} files");
    }
}

/// A full disk leaves the directory as it was, and the location is tried again.
#[test]
fn a_failed_write_leaves_no_file_and_is_tried_again() {
    use std::sync::atomic::Ordering::Relaxed;
    let (dir, diag) = crashes("diag-crash-full");
    let at = dir.path().join(crash::DIR);
    fs::create_dir_all(&at).unwrap();
    let old = ["1.txt", "2.txt", "3.txt", "4.txt", "5.txt"];
    for name in old {
        fs::write(at.join(name), name).unwrap();
    }
    let crashes = diag.crashes().unwrap();
    crashes.full_after.store(10, Relaxed);
    let site: fn() = || panic!("debugFault");
    panic_on(&diag, site);
    let mut present: Vec<String> = fs::read_dir(&at)
        .unwrap()
        .map(|e| e.unwrap().file_name().into_string().unwrap())
        .collect();
    present.sort();
    assert_eq!(present, old, "nothing evicted, no part left");
    let last = |diag: &Diag| *diag.events(None).0.last().unwrap();
    assert_eq!(last(&diag).detail, Some("storage_full"));
    crashes.full_after.store(0, Relaxed);
    panic_on(&diag, site);
    assert_eq!(last(&diag).detail, None, "not taken for a repeat");
    let names: Vec<String> = crashes.list().into_iter().map(|f| f.name).collect();
    assert_eq!(names[..4], old[1..]);
    assert_eq!(message(&read(&dir, &names[4])), "debugFault");
}

/// The hook copies nothing of a payload: a 4 MiB message costs it no more than a short one.
#[test]
fn a_huge_payload_costs_the_hook_no_copy() {
    let (_dir, diag) = crashes("diag-crash-huge");
    let huge: &'static str = Box::leak("x".repeat(4 << 20).into_boxed_str());
    let (allocated, _) = panic_measured(&diag, "doona-core", move || std::panic::panic_any(huge));
    assert!(allocated < 64 * 1024, "{allocated}");
}

/// The hook never logs: a panic inside a logger that holds its lock would deadlock on it.
#[test]
fn the_hook_logs_nothing() {
    let (_dir, diag) = crashes("diag-crash-quiet");
    let (_, logged) = panic_measured(&diag, "doona-core", || panic!("debugFault"));
    assert_eq!(logged, 0);
    assert!(diag.events(None).0.iter().any(|e| e.name == "panic"));
}

/// An event stamped first but kept second comes back first.
#[test]
fn the_events_come_back_in_time_order() {
    let diag = Diag::default();
    for (at, name) in [(5, "d"), (3, "a"), (4, "c"), (3, "b")] {
        let event = Event {
            at_ms: at,
            ..Event::info(name)
        };
        push(&mut lock(&diag.ring), event);
    }
    assert_eq!(names(&diag, None), ["a", "b", "c", "d"]);
}

/// A panic raised while its thread holds the crash files writes none, and does not wait on them.
#[test]
fn a_panic_holding_the_files_writes_none() {
    let (_dir, diag) = crashes("diag-crash-held");
    crate::panics::install();
    let target = Arc::downgrade(&diag);
    let inner = Arc::clone(&diag);
    let ended = std::thread::spawn(move || {
        crash::report_here(&target);
        inner.crashes().unwrap().holding(|| panic!("debugFault"));
    })
    .join();
    assert!(ended.is_err());
    assert!(diag.crashes().unwrap().list().is_empty());
    let panic = diag.events(None).0.into_iter().find(|e| e.name == "panic");
    assert_eq!(panic.unwrap().detail, Some("repeated"));
}
