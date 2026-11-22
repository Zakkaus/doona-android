//! The stats hub: folds from a session, when it writes `stats.json`, and what a new process reads.

use super::*;
use crate::testutil::TempDir;
use std::sync::Weak;

/// 2026-11-22 10:24 UTC.
const NOW_MS: i64 = 1_795_343_040_000;

fn clock(boot_s: u64, wall_ms: i64) -> Clock {
    Clock {
        boot_ms: boot_s * 1000,
        wall_ms,
        offset_s: 0,
    }
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
    let mut tally = meter::Tally::new(Some(session.meters().get("g-Proxy")));
    tally.down(usize::try_from(outbound).unwrap());
}

#[test]
fn a_fold_feeds_the_ring_the_day_and_the_peaks() {
    let (dir, rt) = (TempDir::new("stats-fold"), runtime());
    let stats = stats(&dir, &rt);
    let session = Session::new(1, Weak::new());
    let start = session.fold(0).to_s;
    moved(&session, (300, 3000, 2), 2500);
    let at = clock(start + 3, NOW_MS);
    assert!(
        stats.fold(&session, &at, false),
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
            yesterday_by_now: 0
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
    let session = Session::new(1, Weak::new());
    let s0 = session.fold(0).to_s;
    moved(&session, (1, 1, 0), 0);
    assert!(stats.fold(&session, &clock(s0 + 1, NOW_MS), false));
    moved(&session, (2, 2, 0), 0);
    assert!(!stats.fold(&session, &clock(s0 + 30, NOW_MS), false));
    // Still within the minute, with or without new bytes.
    assert!(!stats.fold(&session, &clock(s0 + 40, NOW_MS), false));
    // A minute after the last write, the change from second 30 is written.
    assert!(stats.fold(&session, &clock(s0 + 61, NOW_MS), false));
    // The generation's end writes what changed since, at once; with nothing new, nothing.
    moved(&session, (3, 3, 0), 0);
    assert!(stats.fold(&session, &clock(s0 + 62, NOW_MS), true));
    assert!(!stats.fold(&session, &clock(s0 + 63, NOW_MS), true));
    // The first fold of a new local day writes, even with no byte.
    let midnight = (NOW_MS / 86_400_000 + 1) * 86_400_000;
    assert!(stats.fold(&session, &clock(s0 + 64, midnight), false));
}

#[test]
fn what_was_written_is_what_the_next_process_reads() {
    let dir = TempDir::new("stats-restart");
    let at = clock(10, NOW_MS);
    {
        let rt = runtime();
        let stats = stats(&dir, &rt);
        let session = Session::new(1, Weak::new());
        session.fold(0);
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
fn an_unreadable_or_newer_file_is_set_aside() {
    for text in [&b"{not json"[..], br#"{"statsVersion":2,"usage":{}}"#] {
        let dir = TempDir::new("stats-aside");
        std::fs::write(dir.path().join("stats.json"), text).unwrap();
        let rt = runtime();
        let stats = stats(&dir, &rt);
        assert_eq!(stats.today(&clock(1, NOW_MS)), dto::Today::default());
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
        r#"{{"statsVersion":1,"later":true,"usage":{{"today":{{"day":{day},"up":[5],"down":[],"flows":[]}}}}}}"#
    );
    std::fs::write(dir.path().join("stats.json"), text).unwrap();
    let rt = runtime();
    let stats = stats(&dir, &rt);
    assert_eq!(stats.today(&clock(1, NOW_MS)).up_total, 5);
    assert_eq!(stats.usage(&clock(1, NOW_MS)).today.down.len(), day::HOURS);
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
        stats.end_round(|id| id != "n-b");
        let tests = stats.latency(["n-a", "n-b", "n-c"].into_iter());
        let a: Vec<_> = tests[0].iter().map(|t| (t.at_epoch_ms, t.ms)).collect();
        assert_eq!(a, [(1_000, Some(38)), (2_000, Some(35))]);
        assert!(tests[1].is_empty() && tests[2].is_empty());
    }
    let rt = runtime();
    let again = stats(&dir, &rt);
    assert_eq!(again.latency(["n-a"].into_iter())[0].len(), 2);
    // A new process drops the nodes its catalog no longer has.
    again.keep_nodes(|_| false);
    assert!(again.latency(["n-a"].into_iter())[0].is_empty());
}
