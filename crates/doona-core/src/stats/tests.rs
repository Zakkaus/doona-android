//! The stats hub: folds from a session, when it writes `stats.json` and `latency.json`, and what a
//! new process reads.

use super::*;
use crate::testutil::TempDir;
use std::sync::Weak;

/// 2026-11-22 10:24 UTC.
const NOW_MS: i64 = 1_795_343_040_000;

/// `s` seconds after `base` on the boot and the monotonic clock, as an awake phone sees them, at
/// `wall_ms`.
fn clock(base: &Clock, s: u64, wall_ms: i64) -> Clock {
    Clock {
        boot_ms: base.boot_ms + s * 1000,
        mono_ms: base.mono_ms + s * 1000,
        wall_ms,
        offset_s: 0,
    }
}

/// The time the stores read by the wall clock only.
fn wall(wall_ms: i64) -> Clock {
    Clock {
        boot_ms: 0,
        mono_ms: 0,
        wall_ms,
        offset_s: 0,
    }
}

/// A session whose counters are folded up to now, and that now.
fn session() -> (Session, Clock) {
    let session = Session::new(1, Weak::new());
    let base = Clock::now();
    session.fold(&base);
    (session, base)
}

fn runtime() -> tokio::runtime::Runtime {
    tokio::runtime::Builder::new_current_thread()
        .build()
        .unwrap()
}

fn stats(dir: &TempDir, rt: &tokio::runtime::Runtime) -> Arc<Stats> {
    Arc::new(Stats::new(dir.path(), rt.handle().clone()))
}

/// A session whose counters stand at `up`, `down` and `opened`, its meter `g-Proxy` holding
/// `outbound` bytes down.
fn moved(session: &Session, (up, down, opened): (u64, u64, u64), outbound: u64) {
    session.publish(up, down, 0, opened);
    let tally = meter::Tally::new(Some(session.meters().get("g-Proxy")));
    tally.down(usize::try_from(outbound).unwrap());
}

#[test]
fn a_fold_feeds_the_ring_the_day_and_the_peaks() {
    let (dir, rt) = (TempDir::new("stats-fold"), runtime());
    let stats = stats(&dir, &rt);
    let (session, base) = session();
    moved(&session, (300, 3000, 2), 2500);
    let at = clock(&base, 3, NOW_MS);
    assert!(
        stats.fold(&session, &at, false).usage,
        "the first change is written at once"
    );
    // Three seconds' bytes, spread: 100 and 1000 a second.
    assert_eq!(session.traffic().up_peak, 100);
    assert_eq!(session.traffic().down_peak, 1000);
    let (series, _) = stats.series(60, true, &at);
    assert_eq!(series.down.iter().sum::<u64>(), 3000);
    assert_eq!(
        stats.today(&at),
        dto::Today {
            up_total: 300,
            down_total: 3000,
            yesterday_by_now: None
        }
    );
    let usage = stats.usage(&at);
    assert_eq!(usage.today.date, "2026-11-22");
    assert_eq!(usage.today.down[10], 3000);
    assert_eq!(usage.today.flows[10], 2);
    assert_eq!(usage.today.outbounds[0].id, "g-Proxy");
    assert_eq!(usage.today.outbounds[0].down, 2500);
    assert!(usage.yesterday.is_none() && usage.days.is_empty());
}

#[test]
fn usage_is_written_once_a_minute_at_most_and_at_the_end() {
    let (dir, rt) = (TempDir::new("stats-throttle"), runtime());
    let stats = stats(&dir, &rt);
    let (session, base) = session();
    // Folds at `clock`, runs the usage's write if one is due, and answers whether it was.
    let fold = |clock: Clock, now| {
        let writes = stats.fold(&session, &clock, now);
        assert!(!writes.latency);
        if writes.usage {
            stats.write_usage();
        }
        writes.usage
    };
    let at = |s| clock(&base, s, NOW_MS);
    moved(&session, (1, 1, 0), 0);
    assert!(fold(at(1), false));
    moved(&session, (2, 2, 0), 0);
    assert!(!fold(at(30), false));
    // Still within the minute, with or without new bytes.
    assert!(!fold(at(40), false));
    // A minute after the last write, the change from second 30 is written.
    assert!(fold(at(61), false));
    // The generation's end writes what changed since, at once; with nothing new, nothing.
    moved(&session, (3, 3, 0), 0);
    assert!(fold(at(62), true));
    assert!(!fold(at(63), true));
    // The first fold of a new local day writes, even with no byte.
    let midnight = (NOW_MS / 86_400_000 + 1) * 86_400_000;
    assert!(fold(clock(&base, 64, midnight), false));
}

#[test]
fn what_was_written_is_what_the_next_process_reads() {
    let dir = TempDir::new("stats-restart");
    let at = wall(NOW_MS);
    {
        let rt = runtime();
        let stats = stats(&dir, &rt);
        let (session, _) = session();
        moved(&session, (40, 400, 1), 100);
        stats.finish(&session, &at);
        // Dropping the runtime waits for the write on its blocking thread.
    }
    assert!(dir.path().join("stats.json").exists());
    let rt = runtime();
    let again = stats(&dir, &rt);
    assert_eq!(again.today(&at).down_total, 400);
    let usage = again.usage(&at);
    assert_eq!(usage.today.flows.iter().sum::<u64>(), 1);
    assert_eq!(usage.today.outbounds[0].down, 100);
    // The rate history is not kept: a new process starts with an empty hour.
    assert!(again.series(60, false, &at).0.down.iter().all(|&b| b == 0));
}

#[test]
fn an_unreadable_older_or_newer_file_is_set_aside() {
    let texts = [
        &b"{not json"[..],
        br#"{"statsVersion":1,"usage":{"today":null}}"#,
        br#"{"statsVersion":3,"usage":{}}"#,
    ];
    for text in texts {
        let dir = TempDir::new("stats-aside");
        std::fs::write(dir.path().join("stats.json"), text).unwrap();
        let rt = runtime();
        let stats = stats(&dir, &rt);
        assert_eq!(stats.today(&wall(NOW_MS)), dto::Today::default());
        assert_eq!(
            std::fs::read(dir.path().join("stats.json.unreadable")).unwrap(),
            text
        );
        assert!(!dir.path().join("stats.json").exists());
    }
}

#[test]
fn a_file_with_odd_days_is_fixed_on_load() {
    let dir = TempDir::new("stats-odd");
    let day = NOW_MS / 86_400_000;
    let text = format!(
        r#"{{"statsVersion":2,"later":true,"usage":{{"full":[{{"day":{day},"up":[5],"down":[],"flows":[]}}]}}}}"#
    );
    std::fs::write(dir.path().join("stats.json"), text).unwrap();
    let rt = runtime();
    let stats = stats(&dir, &rt);
    assert_eq!(stats.today(&wall(NOW_MS)).up_total, 5);
    assert_eq!(stats.usage(&wall(NOW_MS)).today.down.len(), day::HOURS);
}

#[test]
fn latency_tests_are_kept_written_at_a_round_end_and_pruned() {
    let dir = TempDir::new("stats-latency");
    {
        let rt = runtime();
        let stats = stats(&dir, &rt);
        stats.record_latency("n-a", 1_000, Some(38));
        stats.record_latency("n-b", 1_000, None);
        stats.record_latency("n-a", 2_000, Some(35));
        // A fold during the round writes the usage, not the tests.
        let (session, base) = session();
        moved(&session, (1, 1, 0), 0);
        stats.finish(&session, &clock(&base, 1, NOW_MS));
        assert!(!dir.path().join("latency.json").exists());
        stats.end_round();
        stats.keep_nodes(|id| id != "n-b");
        let tests = stats.latency(["n-a", "n-b", "n-c"].into_iter());
        let a: Vec<_> = tests[0].iter().map(|t| (t.at_epoch_ms, t.ms)).collect();
        assert_eq!(a, [(1_000, Some(38)), (2_000, Some(35))]);
        assert!(tests[1].is_empty() && tests[2].is_empty());
    }
    // The round's end wrote the tests to their own file, and the node that left is gone from it.
    let read = |name| {
        let text = std::fs::read(dir.path().join(name)).unwrap();
        serde_json::from_slice::<serde_json::Value>(&text).unwrap()
    };
    assert_eq!(
        read("latency.json")["latency"]["n-a"][1],
        serde_json::json!([2_000, 35])
    );
    assert!(read("latency.json")["latency"].get("n-b").is_none());
    assert!(read("stats.json").get("latency").is_none());
    let rt = runtime();
    let again = stats(&dir, &rt);
    assert_eq!(again.latency(["n-a"].into_iter())[0].len(), 2);
    // A new process drops the nodes its catalog no longer has.
    again.keep_nodes(|_| false);
    assert!(again.latency(["n-a"].into_iter())[0].is_empty());
}

#[test]
fn bytes_moved_before_a_suspend_stay_out_of_the_sleep() {
    let (dir, rt) = (TempDir::new("stats-suspend"), runtime());
    let stats = stats(&dir, &rt);
    let (session, base) = session();
    moved(&session, (600, 6000, 0), 0);
    // The phone suspends for ten minutes right after; the watch's next round comes on wake, a
    // second later on the monotonic clock, and the boot clock counted the sleep.
    let wake = Clock {
        mono_ms: base.mono_ms + 1000,
        ..clock(&base, 601, NOW_MS + 601_000)
    };
    stats.fold(&session, &wake, false);
    let (last_minute, _) = stats.series(60, true, &wake);
    assert!(
        last_minute.down.iter().all(|&b| b == 0),
        "the sleep shows no traffic: {:?}",
        last_minute.down
    );
    let first = base.boot_s() + 1;
    assert_eq!(lock(&stats.inner).ring.series(first, 1).down, [6000]);
    assert_eq!(session.traffic().down_peak, 6000);
}

#[test]
fn a_flow_across_midnight_counts_each_side_on_its_day() {
    let (dir, rt) = (TempDir::new("stats-midnight"), runtime());
    let stats = stats(&dir, &rt);
    let (session, base) = session();
    let midnight = (NOW_MS / 86_400_000 + 1) * 86_400_000;
    let fold = |s: u64| {
        stats.fold(
            &session,
            &clock(&base, s, midnight - 4000 + 1000 * s as i64),
            false,
        )
    };
    let proxy = |d: &dto::DayUsage| {
        d.outbounds
            .iter()
            .find(|o| o.id == "g-Proxy")
            .map(|o| (o.up, o.down))
    };
    let flow = meter::Tally::new(Some(session.meters().get("g-Proxy")));
    // 32 KiB in 4 KiB chunks over the last seconds before midnight, folded each second.
    fold(1);
    (0..4).for_each(|_| flow.up(4096));
    fold(2);
    (0..4).for_each(|_| flow.up(4096));
    fold(3);
    // Midnight's fold comes before the flow's next chunk, then 32 KiB more on the new day.
    fold(4);
    (0..8).for_each(|_| flow.up(4096));
    fold(5);
    drop(flow);
    fold(6);
    let usage = stats.usage(&clock(&base, 6, midnight + 2000));
    assert_eq!(proxy(&usage.today), Some((32_768, 0)));
    assert_eq!(proxy(&usage.yesterday.unwrap()), Some((32_768, 0)));
    // A flow that moves a little now and then shows at the next fold, while it is open.
    let push = meter::Tally::new(Some(session.meters().get("g-Proxy")));
    push.down(100);
    fold(7);
    assert_eq!(
        proxy(&stats.usage(&clock(&base, 7, midnight + 3000)).today),
        Some((32_768, 100))
    );
    drop(push);
}

#[test]
fn a_failed_write_is_tried_again() {
    let (dir, rt) = (TempDir::new("stats-enospc"), runtime());
    let stats = stats(&dir, &rt);
    let (session, base) = session();
    let at = |s| clock(&base, s, NOW_MS);
    // The temporary files cannot be made, as when the disk is full.
    let temps = ["stats.json.tmp", "latency.json.tmp"].map(|t| dir.path().join(t));
    temps.iter().for_each(|t| std::fs::create_dir(t).unwrap());
    moved(&session, (1, 1, 0), 0);
    assert!(stats.fold(&session, &at(1), true).usage);
    stats.write_usage();
    stats.record_latency("n-a", 1_000, Some(38));
    assert!(
        lock(&stats.inner)
            .latency_writes
            .due(base.boot_s() + 1, true)
    );
    stats.write_latency();
    let written = || ["stats.json", "latency.json"].map(|f| dir.path().join(f).exists());
    assert_eq!(written(), [false, false]);
    // Space comes free, and no byte moves and no test runs for the rest of the day: both
    // writes are asked again, a minute after the failed ones.
    temps.iter().for_each(|t| std::fs::remove_dir(t).unwrap());
    assert_eq!(stats.fold(&session, &at(30), false), Writes::default());
    let again = stats.fold(&session, &at(62), false);
    assert_eq!(
        again,
        Writes {
            usage: true,
            latency: true
        },
        "the failed writes are due again"
    );
    stats.write_usage();
    stats.write_latency();
    assert_eq!(written(), [true, true]);
    // Written: nothing more is due.
    assert_eq!(stats.fold(&session, &at(130), false), Writes::default());
}

#[test]
fn an_open_flow_counts_its_bytes_on_the_day_they_moved() {
    let (dir, rt) = (TempDir::new("stats-open-flow"), runtime());
    let stats = stats(&dir, &rt);
    let (session, base) = session();
    let today = NOW_MS / 86_400_000;
    let midnight = (today + 1) * 86_400_000;
    // `s` seconds after 23:59:58, on every clock.
    let at = |s: u64| clock(&base, s, midnight - 2000 + 1000 * s as i64);
    let proxy = |d: i64| {
        let inner = lock(&stats.inner);
        (inner.usage.get(d)).and_then(|d| d.outbounds.get("g-Proxy").copied())
    };
    let flow = meter::Tally::new(Some(session.meters().get("g-Proxy")));
    stats.fold(&session, &at(0), false);
    // 1 B and 32 KiB in the same second, then the flow stays open and quiet.
    flow.up(1);
    flow.up(32_768);
    stats.fold(&session, &at(1), false);
    assert_eq!(proxy(today), Some([32_769, 0]), "the next fold counts all");
    // Past midnight, and two days on, the flow ends.
    stats.fold(&session, &at(3), false);
    let later = 2 * 86_400;
    stats.fold(&session, &at(later), false);
    drop(flow);
    stats.fold(&session, &at(later + 1), false);
    assert_eq!(proxy(today), Some([32_769, 0]));
    assert_eq!((proxy(today + 1), proxy(today + 2)), (None, None));
}
