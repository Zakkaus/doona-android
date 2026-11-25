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
        .enable_time()
        .max_blocking_threads(1)
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
        let ruled = session.meters().get_ruled("g-Proxy", "r-00000000000000aa");
        meter::Tally::new(Some(ruled)).down(60);
        stats.finish(&session, &at);
        // Dropping the runtime waits for the write on its blocking thread.
    }
    assert!(dir.path().join("stats.json").exists());
    let rt = runtime();
    let again = stats(&dir, &rt);
    assert_eq!(again.today(&at).down_total, 400);
    let usage = again.usage(&at);
    assert_eq!(usage.today.flows.iter().sum::<u64>(), 1);
    assert_eq!(usage.today.outbounds[0].down, 160);
    let rule = dto::RuleBytes {
        id: "r-00000000000000aa".into(),
        up: 0,
        down: 60,
        flows: 1,
    };
    assert_eq!(usage.today.by_rule, [rule]);
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
fn keeping_the_same_nodes_defers_latency_writes_to_round_end() {
    let (dir, rt) = (TempDir::new("stats-keep-nodes"), runtime());
    let stats = stats(&dir, &rt);
    stats.record_latency("n-a", 1_000, Some(38));
    stats.keep_nodes(|_| true);
    rt.block_on(rt.spawn_blocking(|| {})).unwrap();
    assert!(!dir.path().join(file::LATENCY).exists());

    stats.end_round();
    // Dropping the runtime cancels a blocking write that has not started; wait for it.
    rt.block_on(rt.spawn_blocking(|| {})).unwrap();
    let saved = StatsFile::new(dir.path(), file::LATENCY, Arc::default()).load::<LatencyFile>();
    assert_eq!(saved.latency["n-a"][0], latency::Test(1_000, Some(38)));
}

#[test]
fn bytes_after_a_wake_count_in_the_second_they_were_sampled() {
    let (dir, rt) = (TempDir::new("stats-wake"), runtime());
    let stats = stats(&dir, &rt);
    let (session, base) = session();
    // Ten minutes asleep after the fold at `base`; on wake 6 KiB arrive before the watch's next
    // round, a second after `base` on the monotonic clock.
    session.publish(0, 6144, 0, 0);
    let wake = Clock {
        mono_ms: base.mono_ms + 1000,
        ..clock(&base, 601, NOW_MS + 601_000)
    };
    stats.fold(&session, &wake, false);
    let (last_minute, _) = stats.series(60, true, &wake);
    assert_eq!(last_minute.down[59], 6144, "{:?}", last_minute.down);
    assert!(last_minute.down[..59].iter().all(|&b| b == 0));
    // Nothing goes back into the sleep or the second before it.
    let (hour, _) = stats.series(601, true, &wake);
    assert_eq!(hour.down.iter().sum::<u64>(), 6144 / hour.step);
    assert_eq!(
        lock(&stats.inner).ring.series(base.boot_s() + 1, 1).down,
        [0]
    );
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
fn failed_final_writes_retry_without_another_fold() {
    use std::time::Duration;

    let (dir, rt) = (TempDir::new("stats-enospc"), runtime());
    let stats = stats(&dir, &rt);
    let (session, base) = session();
    let at = clock(&base, 1, NOW_MS);
    let temps = ["stats.json.tmp", "latency.json.tmp"].map(|t| dir.path().join(t));
    temps.iter().for_each(|t| std::fs::create_dir(t).unwrap());
    moved(&session, (1, 2, 0), 0);
    stats.finish(&session, &at);
    stats.record_latency("n-a", 1_000, Some(38));
    stats.end_round();
    rt.block_on(rt.spawn_blocking(|| {})).unwrap();
    let saved = [file::USAGE, file::LATENCY].map(|f| dir.path().join(f));
    assert!(saved.iter().all(|f| !f.exists()));

    temps.iter().for_each(|t| std::fs::remove_dir(t).unwrap());
    rt.block_on(async {
        tokio::time::timeout(Duration::from_secs(4), async {
            while saved.iter().any(|f| !f.exists()) {
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
        })
        .await
        .expect("the files were not written again after stop");
    });
    let usage = StatsFile::new(dir.path(), file::USAGE, Arc::default()).load::<UsageFile>();
    assert_eq!(usage.usage.today(&at).1, 2);
    let latency = StatsFile::new(dir.path(), file::LATENCY, Arc::default()).load::<LatencyFile>();
    assert_eq!(latency.latency["n-a"][0], latency::Test(1_000, Some(38)));
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

#[test]
fn a_clock_a_year_ahead_for_a_moment_keeps_today_and_yesterday() {
    let (dir, rt) = (TempDir::new("stats-jump"), runtime());
    let stats = stats(&dir, &rt);
    let (session, base) = session();
    let day_ms = 86_400_000;
    // A year ahead, a second before midnight there, then past it: two wrong dates.
    let ahead = (NOW_MS + 365 * day_ms) / day_ms * day_ms + day_ms - 1000;
    let walls = [
        NOW_MS - day_ms,
        NOW_MS,
        ahead,
        ahead + 1000,
        ahead + 2000,
        // The network corrects the clock.
        NOW_MS + 5000,
    ];
    for (s, wall) in (1..).zip(walls) {
        session.publish(0, 10 * s, 0, 0);
        stats.fold(&session, &clock(&base, s, wall), false);
    }
    let now = clock(&base, 6, NOW_MS + 5000);
    let usage = stats.usage(&now);
    let sum = |d: &dto::DayUsage| d.down.iter().sum::<u64>();
    assert_eq!(
        usage.yesterday.as_ref().map(sum),
        Some(10),
        "yesterday in full"
    );
    assert_eq!(sum(&usage.today), 50);
    assert!(stats.today(&now).yesterday_by_now.is_some());
}

#[test]
fn a_clock_that_stays_ahead_is_believed_and_a_long_sleep_at_once() {
    let (dir, rt) = (TempDir::new("stats-jump-stays"), runtime());
    let stats = stats(&dir, &rt);
    let (session, base) = session();
    let day_ms = 86_400_000;
    let counted = || lock(&stats.inner).day;
    stats.fold(&session, &clock(&base, 1, NOW_MS), false);
    // Set a week ahead and left there: the boot clock's time counts until the jump has held
    // for JUMP_FOLDS folds, then the new date.
    let week_on = |s: u64| clock(&base, s, NOW_MS + 7 * day_ms + 1000 * s as i64);
    let last = 1 + u64::from(JUMP_FOLDS);
    for s in 2..=last {
        stats.fold(&session, &week_on(s), false);
        assert_eq!(counted(), Some(NOW_MS / day_ms), "fold {s}");
    }
    stats.fold(&session, &week_on(last + 1), false);
    assert_eq!(counted(), Some(NOW_MS / day_ms + 7));
    // Asleep for three days, which the boot clock counts too: the new date at once.
    let asleep = 3 * 86_400;
    let wake = clock(
        &base,
        last + 1 + asleep,
        week_on(last + 1).wall_ms + 1000 * asleep as i64,
    );
    stats.fold(&session, &wake, false);
    assert_eq!(counted(), Some(NOW_MS / day_ms + 10));
    // A clock set back is believed at once.
    let back = clock(&base, last + 2 + asleep, NOW_MS);
    stats.fold(&session, &back, false);
    assert_eq!(counted(), Some(NOW_MS / day_ms));
}

/// In Rule mode a flow's meter is also its rule's: the day's `byRule` carries the bytes its
/// outbounds carry for the same flows, and the flows opened under each rule.
#[test]
fn rule_bytes_add_up_to_the_outbound_bytes() {
    let (dir, rt) = (TempDir::new("stats-rules"), runtime());
    let stats = stats(&dir, &rt);
    let (session, base) = session();
    let ruled = |target, rule| meter::Tally::new(Some(session.meters().get_ruled(target, rule)));
    let cn = ruled(meter::DIRECT, "r-cn");
    let streaming = ruled("g-Streaming", "r-streaming");
    let (first, second) = (ruled("g-Proxy", "unmatched"), ruled("g-Proxy", "unmatched"));
    cn.up(70);
    cn.down(700);
    streaming.down(5000);
    first.up(30);
    second.down(9);
    let at = clock(&base, 1, NOW_MS);
    stats.fold(&session, &at, false);
    let today = stats.usage(&at).today;
    let rules: Vec<_> = (today.by_rule.iter())
        .map(|r| (r.id.as_str(), r.up, r.down, r.flows))
        .collect();
    assert_eq!(
        rules,
        [
            ("r-cn", 70, 700, 1),
            ("r-streaming", 0, 5000, 1),
            ("unmatched", 30, 9, 2)
        ]
    );
    let sum = |bytes: Vec<(u64, u64)>| bytes.iter().fold((0, 0), |(u, d), b| (u + b.0, d + b.1));
    assert_eq!(
        sum(today.by_rule.iter().map(|r| (r.up, r.down)).collect()),
        sum(today.outbounds.iter().map(|o| (o.up, o.down)).collect())
    );
    assert_eq!(today.outbounds.len(), 3);
    drop((cn, streaming, first, second));
}

#[test]
fn a_file_written_before_rules_were_counted_keeps_its_outbounds() {
    let dir = TempDir::new("stats-no-rules");
    let day = NOW_MS / 86_400_000;
    let text = format!(
        r#"{{"statsVersion":2,"usage":{{"full":[{{"day":{day},"up":[5],"down":[7],"flows":[1],"outbounds":{{"g-Proxy":[5,7]}}}}]}}}}"#
    );
    std::fs::write(dir.path().join("stats.json"), text).unwrap();
    let rt = runtime();
    let stats = stats(&dir, &rt);
    let today = stats.usage(&wall(NOW_MS)).today;
    assert_eq!(today.outbounds[0].id, "g-Proxy");
    assert_eq!((today.outbounds[0].up, today.outbounds[0].down), (5, 7));
    assert!(today.by_rule.is_empty());
}

/// A hand-edited `stats.json` never hands back arbitrary text as a rule id: a rule that is not a
/// row id (`r-` and 16 lowercase hex digits), `unmatched` or `other` is read as `other`.
#[test]
fn a_rule_id_that_is_no_row_id_reads_as_other() {
    let dir = TempDir::new("stats-rule-ids");
    let day = NOW_MS / 86_400_000;
    let rules = r#"{"https://u:p@example.invalid/sub?token=demo":[1,2,1],"r-0123456789ABCDEF":[10,20,1],"r-0123":[100,200,1],"r-0123456789abcdef":[3,4,1],"unmatched":[5,6,2],"other":[7,8,1]}"#;
    let text = format!(
        r#"{{"statsVersion":2,"usage":{{"full":[{{"day":{day},"up":[5],"down":[7],"flows":[1],"outbounds":{{"g-Proxy":[5,7]}},"rules":{rules}}}]}}}}"#
    );
    std::fs::write(dir.path().join("stats.json"), text).unwrap();
    let rt = runtime();
    let stats = stats(&dir, &rt);
    let today = stats.usage(&wall(NOW_MS)).today;
    let rules: Vec<_> = (today.by_rule.iter())
        .map(|r| (r.id.as_str(), r.up, r.down, r.flows))
        .collect();
    assert_eq!(
        rules,
        [
            ("other", 118, 230, 4),
            ("r-0123456789abcdef", 3, 4, 1),
            ("unmatched", 5, 6, 2)
        ]
    );
}
