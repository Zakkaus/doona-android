//! The ring, its level, and the crash files: their cap, their order, a repeated location, and
//! what a formatted message keeps.

use super::crash::{self, MAX_BYTES, MAX_FILES, cap, redact, source_path};
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
    assert!(text.contains("\nlocation: src/diag/tests.rs:"), "{text}");
    assert!(text.contains("\nmessage: a literal message\n"), "{text}");
    assert!(text.contains("\nbase: 0x"), "{text}");
    assert!(text.ends_with("\n\nbacktrace:\nnot captured\n"), "{text}");
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
    for panic in panics {
        panic_on(&diag, panic);
    }
    let files = diag.crashes().unwrap().list();
    assert_eq!(files.len(), MAX_FILES);
    let messages: Vec<String> = files
        .iter()
        .map(|f| read(&dir, &f.name))
        .map(|t| {
            t.lines()
                .find_map(|l| l.strip_prefix("message: "))
                .unwrap()
                .to_owned()
        })
        .collect();
    assert_eq!(messages, ["third", "fourth", "fifth", "sixth", "seventh"]);
}

#[test]
fn files_are_ordered_by_their_time_then_cleared_all() {
    let (dir, diag) = crashes("diag-crash-order");
    let crashes = diag.crashes().unwrap();
    assert_eq!(crashes.clear().unwrap(), 0, "no directory yet");
    let at = dir.path().join(crash::DIR);
    fs::create_dir_all(&at).unwrap();
    for name in [
        "100.txt",
        "20.txt",
        "notes.txt",
        "1795000000000-app.txt",
        "partial.tmp",
    ] {
        fs::write(at.join(name), name).unwrap();
    }
    let names: Vec<String> = crashes.list().into_iter().map(|f| f.name).collect();
    assert_eq!(
        names,
        ["notes.txt", "20.txt", "100.txt", "1795000000000-app.txt"]
    );
    assert_eq!(crashes.clear().unwrap(), 5);
    assert!(crashes.list().is_empty());
}

#[test]
fn a_backtrace_gives_frame_addresses() {
    let (dir, mut diag) = crashes("diag-crash-trace");
    Arc::get_mut(&mut diag)
        .unwrap()
        .crashes
        .as_mut()
        .unwrap()
        .backtraces = true;
    panic_on(&diag, || panic!("traced"));
    let files = diag.crashes().unwrap().list();
    let text = read(&dir, &files[0].name);
    let trace = text.split("\n\nbacktrace:\n").nth(1).unwrap();
    let first = trace.lines().find(|l| l.trim_start().starts_with("0:"));
    assert!(first.is_some_and(|l| l.contains("0x")), "{trace}");
    assert!(text.len() <= MAX_BYTES);
}

#[test]
fn a_formatted_message_keeps_no_place_name_or_quote() {
    let (dir, diag) = crashes("diag-crash-redact");
    panic_on(&diag, || {
        let url = "https://sub.example.com/api/v1?token=s3cr3t";
        let name = "hk-01";
        panic!("fetch {url} via 192.0.2.1:443 and [2001:db8::1]:8443 for {name:?}, 東京01; len 3")
    });
    let files = diag.crashes().unwrap().list();
    let text = read(&dir, &files[0].name);
    let message = text
        .lines()
        .find_map(|l| l.strip_prefix("message: "))
        .unwrap();
    assert_eq!(message, "fetch … via … and [… for \"…\", …; len 3");
}

#[test]
fn redact_keeps_words_numbers_and_rust_paths() {
    for (message, kept) in [
        (
            "the len is 3 but the index is 5",
            "the len is 3 but the index is 5",
        ),
        ("connect to 192.0.2.1:443 failed", "connect to … failed"),
        ("bad url https://a.example/x?t=1, retry", "bad url …, retry"),
        ("mail (user@example.com) now", "mail (…) now"),
        ("node 香港01 down", "node … down"),
        ("at [fe80::1%wlan0]:53.", "at […."),
        (
            "called `Result::unwrap()` on an `Err` value: Os { code: 2, message: \"No such\" }",
            "called `Result::unwrap()` on an `Err` value: Os { code: 2, message: \"…\" }",
        ),
        (
            "inside 'é' of `hk-01é` and `HK 01`",
            "inside … of `…` and `…`",
        ),
        ("unclosed \"abc def", "unclosed \"…"),
        ("tab\tand\nline", "tab\tand\nline"),
    ] {
        assert_eq!(redact(message), kept, "{message}");
    }
}

#[test]
fn a_source_path_starts_at_its_crate() {
    for (file, shown) in [
        ("src/api.rs", "src/api.rs"),
        (
            "/home/u/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/tokio-1.47.1/src/runtime/park.rs",
            "tokio-1.47.1/src/runtime/park.rs",
        ),
        (
            "/home/u/.cargo/git/checkouts/honk-5f/8919784/crates/honk-outbound/src/lib.rs",
            "honk-outbound/src/lib.rs",
        ),
        (
            "/rustc/abc/library/core/src/option.rs",
            "core/src/option.rs",
        ),
    ] {
        assert_eq!(source_path(file), shown);
    }
}

#[test]
fn cap_cuts_at_a_character_boundary() {
    let mut text = "aé".to_owned();
    cap(&mut text, 2);
    assert_eq!(text, "a");
    let mut long = "é".repeat(MAX_BYTES);
    cap(&mut long, MAX_BYTES);
    assert_eq!(long.len(), MAX_BYTES);
}
