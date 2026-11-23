//! `diagnostics`, `setLogLevel` and `clearCrashes` through `call`: the events a session leaves, and
//! that neither the reply nor a crash file holds the feed's token URL, its server addresses, its
//! node names or its secrets (ARCH D21).

use super::Core;
use super::tests_catalog::{Feeds, call, catalog, path, wait_for};
use crate::diag::CAPACITY;
use crate::dto::{Diagnostics, ErrorCode, Level, Snapshot};
use crate::probe::{ProbeJob, Prober};
use crate::testutil::{TempDir, alloc, fixture, tun};
use futures_util::FutureExt;
use serde_json::{Value, json};
use std::sync::Arc;
use std::time::{Duration, Instant};

const CONFIG: &str =
    r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":["192.168.8.1"]}"#;
const TOKEN: &str = "tok-5f3a9c";

/// Every probe measures 5 ms but `Other`'s, which fails.
fn quick() -> Prober {
    Arc::new(|job: ProbeJob| {
        async move { (job.node.name != "Other").then_some(Duration::from_millis(5)) }.boxed()
    })
}

fn diagnostics(core: &Core, args: Value) -> Diagnostics {
    serde_json::from_value(call(core, "diagnostics", args).unwrap()).unwrap()
}

fn names(d: &Diagnostics) -> Vec<&str> {
    d.events.iter().map(|e| e.name.as_str()).collect()
}

/// A task of the core's runtime whose panic quotes `url`, a server and two node names.
async fn lost(url: String) {
    let name = "hk-01";
    panic!("lost {url} at 192.0.2.1:443 for {name:?}, 東京01")
}

async fn literal() {
    panic!("a task's literal panic")
}

fn snapshot(core: &Core) -> Snapshot {
    serde_json::from_str(&core.snapshot(-1)).unwrap()
}

/// Runs what a tester's session runs, with a feed whose URL holds a token and whose nodes hold
/// addresses, secrets and names, and a panic whose message quotes them.
fn session(core: &Core, feeds: &Feeds, sub: &str) {
    call(core, "setLogLevel", json!({"level": "debug"})).unwrap();
    let added = call(
        core,
        "addSubscription",
        json!({"url": feeds.url(sub), "name": null}),
    );
    assert_eq!(added.unwrap()["nodes"], 9);
    let gone = json!({"url": feeds.url(&format!("/gone?token={TOKEN}")), "name": "harbor"});
    assert_eq!(
        call(core, "addSubscription", gone),
        Err(ErrorCode::FetchFailed)
    );
    call(core, "updateSubscription", json!({"id": "s1"})).unwrap();
    let node = catalog(core).groups[0].members.last().unwrap().id.clone();
    call(
        core,
        "select",
        json!({"group": "g-Proxy", "memberId": node}),
    )
    .unwrap();
    call(core, "setMode", json!({"mode": "Rule"})).unwrap();
    call(core, "testLatency", json!({"group": null})).unwrap();
    wait_for(core, |c| c.groups.iter().all(|g| !g.testing));
    for (command, args) in [
        ("nodes", json!({"ids": null})),
        ("usage", json!({})),
        ("effectiveConfig", json!({})),
        ("rules", json!({"offset": 0, "limit": 50})),
        ("trafficSeries", json!({"seconds": 60})),
    ] {
        call(core, command, args).unwrap();
    }
    let (dup, _probe) = tun();
    core.start(dup, CONFIG).unwrap();
    core.runtime.spawn(lost(feeds.url(sub)));
    let begun = Instant::now();
    while snapshot(core).error.is_none() {
        assert!(
            begun.elapsed() < Duration::from_secs(10),
            "the generation did not die"
        );
        std::thread::sleep(Duration::from_millis(20));
    }
    core.stop();
}

#[test]
fn a_session_leaves_its_events_and_no_url_address_name_or_secret() {
    let dir = TempDir::new("api-diag-d21");
    let feeds = Feeds::new();
    let sub = format!("/sub?token={TOKEN}");
    feeds.set(&sub, &fixture("links.b64"));
    let core = Core::with_prober(path(&dir), quick());
    session(&core, &feeds, &sub);
    let report = diagnostics(&core, json!({}));
    let reply = serde_json::to_string(&report).unwrap();
    assert_eq!(report.crashes.len(), 1, "{reply}");
    let crash = dir.path().join("crash").join(&report.crashes[0].name);
    let crash = std::fs::read_to_string(crash).unwrap();
    let server = feeds.url("").replace("http://", "");
    let leaks = [
        TOKEN,
        "token",
        "192.0.2.",
        "2001:db8",
        &server,
        "127.0.0.1",
        "/sub",
        "/gone",
        "harbor",
        "hk-01",
        "Tokyo",
        "東京",
        "台湾",
        "secret",
        "other@",
        "YWVz",
        "http",
    ];
    for leak in leaks {
        assert!(!reply.contains(leak), "{leak} in the reply: {reply}");
        assert!(!crash.contains(leak), "{leak} in the crash file: {crash}");
    }
    let names = names(&report);
    for name in [
        "init",
        "setLogLevel",
        "addSubscription",
        "updateSubscription",
        "select",
        "setMode",
        "testLatency",
        "probeRound",
        "nodes",
        "trafficSeries",
        "start",
        "panic",
        "died",
        "stopped",
    ] {
        assert!(names.contains(&name), "{name}: {names:?}");
    }
    let event = |name: &str| report.events.iter().find(|e| e.name == name).unwrap();
    assert_eq!(event("addSubscription").counts["nodes"], 9);
    let failed = report
        .events
        .iter()
        .filter(|e| e.name == "addSubscription")
        .nth(1);
    let failed = failed.unwrap();
    assert_eq!(
        (failed.level, failed.code.as_deref()),
        (Level::Warn, Some("fetch_failed"))
    );
    assert_eq!(event("setMode").detail.as_deref(), Some("Rule"));
    assert_eq!(event("testLatency").counts["probes"], 9);
    let round = event("probeRound");
    assert_eq!((round.counts["probes"], round.counts["failed"]), (9, 1));
    assert_eq!(event("start").counts["generation"], 1);
    assert_eq!(event("died").code.as_deref(), Some("internal"));
    assert!(
        report
            .events
            .iter()
            .all(|e| e.at_epoch_ms > 0 && e.counts.len() <= 2)
    );
}

#[test]
fn the_reply_filters_by_level_and_holds_the_last_snapshot_and_the_build() {
    let dir = TempDir::new("api-diag-reply");
    let core = Core::new(path(&dir)).unwrap();
    let first = diagnostics(&core, json!({}));
    assert_eq!(first.core.version, env!("CARGO_PKG_VERSION"));
    assert_eq!(first.core.schema, crate::dto::SCHEMA);
    assert_eq!((first.level, first.snapshot), (Level::Info, None));
    assert_eq!(names(&first), ["init"]);
    assert_eq!(first.events[0].counts["subscriptions"], 0);
    assert!(first.crashes.is_empty() && first.overwritten == 0);
    call(&core, "nodes", json!({"ids": null})).unwrap();
    let missing = json!({"group": "g-None", "memberId": null});
    assert_eq!(call(&core, "select", missing), Err(ErrorCode::NotFound));
    let verbose = json!({"level": "verbose"});
    assert_eq!(
        call(&core, "setLogLevel", verbose),
        Err(ErrorCode::InvalidArgument)
    );
    assert_eq!(
        call(&core, "bogus", json!({})),
        Err(ErrorCode::InvalidArgument)
    );
    let shown = snapshot(&core);
    let warned = diagnostics(&core, json!({"level": "warn"}));
    assert_eq!(names(&warned), ["select", "setLogLevel", "unknown"]);
    let codes: Vec<_> = warned.events.iter().map(|e| e.code.as_deref()).collect();
    assert_eq!(
        codes,
        [
            Some("not_found"),
            Some("invalid_argument"),
            Some("invalid_argument")
        ]
    );
    let all = diagnostics(&core, json!({}));
    assert_eq!(names(&all), ["init", "select", "setLogLevel", "unknown"]);
    let last = all.snapshot.unwrap();
    assert_eq!((last.revision, last.running), (shown.revision, false));
    assert_eq!((last.error, last.warning), (None, None));
    assert_eq!(all.stats.traffic, crate::dto::Traffic::default());
}

#[test]
fn a_panic_on_the_core_s_runtime_writes_a_file_that_clear_crashes_removes() {
    let dir = TempDir::new("api-diag-crash");
    let core = Core::new(path(&dir)).unwrap();
    let task = core.runtime.spawn(literal());
    assert!(core.runtime.block_on(task).is_err());
    let report = diagnostics(&core, json!({}));
    assert_eq!(report.crashes.len(), 1);
    let file = dir.path().join("crash").join(&report.crashes[0].name);
    let text = std::fs::read_to_string(&file).unwrap();
    assert_eq!(report.crashes[0].bytes, u64::try_from(text.len()).unwrap());
    let message = "\nmessage: panic in doonacore::api::tests_diagnostics\n";
    assert!(text.contains(message), "{text}");
    assert!(
        text.contains("\nlocation: src/api/tests_diagnostics.rs:"),
        "{text}"
    );
    assert!(names(&report).contains(&"panic"));
    let names = json!({"names": [report.crashes[0].name], "mark": report.crash_mark});
    call(&core, "clearCrashes", names).unwrap();
    let cleared = diagnostics(&core, json!({}));
    assert!(cleared.crashes.is_empty() && !file.exists());
    let event = cleared
        .events
        .iter()
        .find(|e| e.name == "clearCrashes")
        .unwrap();
    assert_eq!(event.counts["files"], 1);
}

/// What a full ring costs as a reply, printed for the record.
#[test]
fn a_full_ring_is_one_bounded_reply() {
    let dir = TempDir::new("api-diag-full");
    let core = Core::new(path(&dir)).unwrap();
    call(&core, "setLogLevel", json!({"level": "debug"})).unwrap();
    for _ in 0..CAPACITY {
        call(&core, "usage", json!({})).unwrap();
    }
    let (reply, peak, _) = alloc::peak(|| core.call("diagnostics", "{}"));
    let report: Value = serde_json::from_str(&reply).unwrap();
    assert_eq!(report["ok"]["events"].as_array().unwrap().len(), CAPACITY);
    assert_eq!(report["ok"]["overwritten"], 2);
    println!(
        "diagnostics with a full ring: {} bytes, {peak} bytes at peak",
        reply.len()
    );
    assert!(reply.len() < 256 * 1024, "{}", reply.len());
}

/// `clearCrashes` removes the files the app exported and nothing else: a file written since stays,
/// and a name the core does not give a crash file is refused.
#[test]
fn clear_crashes_removes_only_the_names_given() {
    let dir = TempDir::new("api-diag-clear");
    let core = Core::new(path(&dir)).unwrap();
    let crash = dir.path().join("crash");
    std::fs::create_dir_all(&crash).unwrap();
    for name in ["100.txt", "200.txt"] {
        std::fs::write(crash.join(name), name).unwrap();
    }
    let mark = diagnostics(&core, json!({})).crash_mark;
    assert_eq!(mark, 200);
    let exported = json!({"names": ["100.txt", "300.txt"], "mark": 150});
    call(&core, "clearCrashes", exported).unwrap();
    assert!(!crash.join("100.txt").exists() && crash.join("200.txt").exists());
    let above = json!({"names": ["200.txt"], "mark": 150});
    call(&core, "clearCrashes", above).unwrap();
    assert!(crash.join("200.txt").exists(), "above the mark given");
    let report = diagnostics(&core, json!({}));
    let clears = report.events.iter().filter(|e| e.name == "clearCrashes");
    let removed: Vec<u32> = clears.map(|e| e.counts["files"]).collect();
    assert_eq!(removed, [1, 0]);
    for args in [
        json!({}),
        json!({"names": ["200.txt"]}),
        json!({"names": ["../state.json"], "mark": mark}),
        json!({"names": ["notes.txt"], "mark": mark}),
        json!([["200.txt"], mark]),
    ] {
        let refused = call(&core, "clearCrashes", args.clone());
        assert_eq!(refused, Err(ErrorCode::InvalidArgument), "{args}");
    }
    assert!(crash.join("200.txt").exists());
}

/// Arguments are a JSON object: an array is not read by position.
#[test]
fn arguments_must_be_an_object() {
    let dir = TempDir::new("api-diag-args");
    let core = Core::new(path(&dir)).unwrap();
    for (command, args) in [
        ("setLogLevel", json!(["debug"])),
        ("setLogLevel", json!("debug")),
        ("diagnostics", json!([null])),
    ] {
        let refused = call(&core, command, args.clone());
        assert_eq!(refused, Err(ErrorCode::InvalidArgument), "{command} {args}");
    }
    assert_eq!(diagnostics(&core, json!({})).level, Level::Info);
}

async fn elsewhere() {
    panic!("another literal panic")
}

/// A clear cannot raise the mark past what the core gave out: a larger mark is refused, and
/// crash files are still written, by this core and by the next on the same directory.
#[test]
fn a_clear_cannot_raise_the_mark() {
    let dir = TempDir::new("api-diag-mark");
    let core = Core::new(path(&dir)).unwrap();
    let huge = json!({"names": [], "mark": u64::MAX});
    assert_eq!(
        call(&core, "clearCrashes", huge),
        Err(ErrorCode::InvalidArgument)
    );
    let given = diagnostics(&core, json!({})).crash_mark;
    let past = json!({"names": [], "mark": given + 1});
    assert_eq!(
        call(&core, "clearCrashes", past),
        Err(ErrorCode::InvalidArgument)
    );
    let task = core.runtime.spawn(literal());
    assert!(core.runtime.block_on(task).is_err());
    assert_eq!(diagnostics(&core, json!({})).crashes.len(), 1);
    drop(core);
    let again = Core::new(path(&dir)).unwrap();
    let task = again.runtime.spawn(elsewhere());
    assert!(again.runtime.block_on(task).is_err());
    assert_eq!(diagnostics(&again, json!({})).crashes.len(), 2);
}

/// `crashMark` fits Kotlin's Long: a file numbered past the largest millisecond time the core
/// names a file with is not a crash file.
#[test]
fn the_mark_fits_a_long() {
    let dir = TempDir::new("api-diag-long");
    let core = Core::new(path(&dir)).unwrap();
    let crash = dir.path().join("crash");
    std::fs::create_dir_all(&crash).unwrap();
    for name in ["9223372036854775808.txt", "1795300150000.txt"] {
        std::fs::write(crash.join(name), name).unwrap();
    }
    let report = diagnostics(&core, json!({}));
    assert!(
        i64::try_from(report.crash_mark).is_ok(),
        "{}",
        report.crash_mark
    );
    assert_eq!(report.crash_mark, 1_795_300_150_000);
    let names: Vec<&str> = report.crashes.iter().map(|c| c.name.as_str()).collect();
    assert_eq!(names, ["1795300150000.txt"]);
}
