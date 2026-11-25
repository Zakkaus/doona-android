//! Lane c7-feeds: provider declarations, feed bodies and the refresh schedule.
mod common;

use std::fs;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::path::PathBuf;
use std::time::Duration;

use common::TempDir;
use doonacore::dto::ErrorCode;
use doonacore::honkconf::feeds::{self, Authored, Feed, IntervalSource, Provider};
use doonacore::honkconf::schedule::{Claims, DEFAULT_HOURS, Schedule};
use doonacore::honkconf::{HonkConfig, load};

const HOUR: i64 = 3_600_000;

fn fixture(name: &str) -> String {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/honk/feeds");
    fs::read_to_string(path.join(name)).unwrap()
}

fn config(name: &str) -> HonkConfig {
    let dir = TempDir::new("honk-feeds");
    let path = dir.path().join("main.dae");
    fs::write(&path, fixture(name)).unwrap();
    load(path).unwrap_or_else(|e| panic!("{name}: {e}"))
}

fn provider(id: &str, interval: Option<(u32, IntervalSource)>) -> Provider {
    Provider {
        id: id.into(),
        name: id.into(),
        url: format!("https://feeds.example.net/{id}"),
        user_agent: None,
        interval: interval.map(|(seconds, source)| Authored { seconds, source }),
    }
}

fn node_ids(feed: &Feed) -> Vec<(String, String)> {
    feed.nodes
        .iter()
        .map(|n| (n.id.clone(), n.node.name.clone()))
        .collect()
}

// Declarations.

#[test]
fn entry_interval_beats_assets_and_assets_beats_the_default() {
    let providers = feeds::providers(&config("assets.dae"));
    let seen: Vec<_> = providers
        .iter()
        .map(|p| (p.id.as_str(), p.interval.map(|a| (a.seconds, a.source))))
        .collect();
    assert_eq!(
        seen,
        [
            ("s-inherits", Some((21_600, IntervalSource::Assets))),
            ("s-own", Some((3_600, IntervalSource::Subscription))),
            ("s-agent", Some((21_600, IntervalSource::Assets))),
        ]
    );
}

#[test]
fn a_user_agent_comes_from_the_entry_then_assets() {
    let providers = feeds::providers(&config("assets.dae"));
    let agents: Vec<_> = providers.iter().map(|p| p.user_agent.as_deref()).collect();
    assert_eq!(
        agents,
        [Some("doona-test/1"), Some("doona-test/1"), Some("custom/2")]
    );
}

#[test]
fn an_interval_written_as_honks_default_is_still_the_entrys_own() {
    let providers = feeds::providers(&config("plain.dae"));
    let seen: Vec<_> = providers
        .iter()
        .map(|p| (p.id.as_str(), p.interval.map(|a| (a.seconds, a.source))))
        .collect();
    assert_eq!(
        seen,
        [
            ("s-bare", None),
            ("s-day", Some((86_400, IntervalSource::Subscription))),
            ("s-my feed", Some((3_600, IntervalSource::Subscription))),
            ("s-manual", Some((0, IntervalSource::Subscription))),
            ("s-old", Some((21_600, IntervalSource::Subscription))),
        ]
    );
}

#[test]
fn entries_with_one_tag_get_distinct_ids_and_their_own_interval() {
    let providers = feeds::providers(&config("duplicates.dae"));
    let seen: Vec<_> = providers
        .iter()
        .map(|p| {
            let url = p.url.rsplit('/').next().unwrap();
            (
                p.id.as_str(),
                url,
                p.interval.map(|a| (a.seconds, a.source)),
            )
        })
        .collect();
    assert_eq!(
        seen,
        [
            ("s-twin", "one", Some((10_800, IntervalSource::Assets))),
            ("s-other", "other", Some((10_800, IntervalSource::Assets))),
            (
                "s-twin#2",
                "two",
                Some((7_200, IntervalSource::Subscription))
            ),
            ("s-twin#3", "three", Some((10_800, IntervalSource::Assets))),
        ]
    );
}

#[test]
fn every_form_honk_accepts_gives_the_entrys_own_interval() {
    let providers = feeds::providers(&config("wrapper.dae"));
    let seen: Vec<_> = providers
        .iter()
        .map(|p| (p.id.as_str(), p.interval.map(|a| (a.seconds, a.source))))
        .collect();
    assert_eq!(
        seen,
        [
            ("s-a", Some((21_600, IntervalSource::Subscription))),
            ("s-b", Some((43_200, IntervalSource::Assets))),
            // honk reads `30s {` as an unsupported duration and falls back to 0.
            ("s-quick", Some((0, IntervalSource::Subscription))),
            ("s-old", Some((7_200, IntervalSource::Subscription))),
        ]
    );
}

#[test]
fn a_scalar_value_is_the_entrys_own_and_a_nested_entry_is_not() {
    let providers = feeds::providers(&config("quoted.dae"));
    let seen: Vec<_> = providers
        .iter()
        .map(|p| (p.name.as_str(), p.interval.map(|a| (a.seconds, a.source))))
        .collect();
    assert_eq!(
        seen,
        [
            ("single", Some((21_600, IntervalSource::Subscription))),
            ("double", Some((3_600, IntervalSource::Subscription))),
            // Written, though it repeats the assets default.
            ("same", Some((10_800, IntervalSource::Subscription))),
            ("outer", Some((10_800, IntervalSource::Assets))),
            ("old", Some((86_400, IntervalSource::Subscription))),
        ]
    );
}

#[test]
fn an_interval_too_long_for_u32_saturates() {
    let providers = feeds::providers(&config("huge.dae"));
    let interval = providers[0].interval.unwrap();
    assert_eq!(interval.seconds, u32::MAX);
    let schedule = Schedule::default();
    let mut claims = Claims::default();
    let t0 = 1_000;
    schedule.refresh_due(&mut claims, &providers, t0);
    let mut schedule = schedule;
    schedule.finish(&mut claims, &providers, "s-huge", true, t0);
    let overview = schedule.overview(&claims, &providers, t0);
    assert_eq!(
        overview.next_refresh_at,
        Some(t0 + i64::from(u32::MAX) * 1000)
    );
}

#[test]
fn entries_across_included_files_keep_their_own_interval() {
    let dir = TempDir::new("honk-feeds-include");
    let entry = |tag: &str, options: &str| {
        format!("subscription {{ {tag}: 'https://feeds.example.net/{tag}' {{ {options} }} }}\n")
    };
    // The loader refuses the first include, so honk never read the bytes kept in the source.
    let main = dir.path().join("main.dae");
    fs::write(
        &main,
        format!(
            "include {{ '/etc/outside.dae' a.dae b.dae }}\n{}routing {{ fallback: direct }}",
            entry("main", "")
        ),
    )
    .unwrap();
    fs::write(dir.path().join("a.dae"), entry("a", "")).unwrap();
    fs::write(dir.path().join("b.dae"), entry("b", "interval: 1h")).unwrap();
    let loaded = load(main).unwrap();
    assert!(!loaded.complete());
    let seen: Vec<_> = feeds::providers(&loaded)
        .into_iter()
        .map(|p| (p.name, p.interval.map(|a| a.seconds)))
        .collect();
    let want = [("main", None), ("a", None), ("b", Some(3_600))];
    assert_eq!(seen, want.map(|(name, s)| (name.to_owned(), s)));
}

// Feeds.

#[test]
fn clash_policy_fields_change_no_node() {
    let clean = Feed::from_body("s-a", fixture("clean.yaml").into_bytes()).unwrap();
    let hostile = Feed::from_body("s-a", fixture("hostile.yaml").into_bytes()).unwrap();
    assert_eq!(node_ids(&hostile), node_ids(&clean));
    assert_eq!(hostile.nodes.len(), 2);
    assert_eq!(hostile.document, clean.document);
}

#[test]
fn sing_box_policy_outbounds_change_no_node() {
    let clean = Feed::from_body("s-a", fixture("clean.json").into_bytes()).unwrap();
    let hostile = Feed::from_body("s-a", fixture("hostile.json").into_bytes()).unwrap();
    assert_eq!(node_ids(&hostile), node_ids(&clean));
    assert_eq!(hostile.nodes.len(), 2);
}

#[test]
fn node_ids_are_scoped_by_provider_and_survive_a_reorder() {
    let body = fixture("clean.yaml");
    let a = Feed::from_body("s-a", body.clone().into_bytes()).unwrap();
    let b = Feed::from_body("s-b", body.clone().into_bytes()).unwrap();
    let ids = |f: &Feed| f.nodes.iter().map(|n| n.id.clone()).collect::<Vec<_>>();
    assert_ne!(ids(&a), ids(&b));
    let (head, tail) = body.split_once("  - name: trojan-01").unwrap();
    let swapped = format!(
        "proxies:\n  - name: trojan-01{tail}{}",
        head.trim_start_matches("proxies:\n")
    );
    let again = Feed::from_body("s-a", swapped.into_bytes()).unwrap();
    let mut left = ids(&a);
    let mut right = ids(&again);
    left.sort();
    right.sort();
    assert_eq!(left, right);
}

fn serve(status_line: &'static str, body: String) -> (u16, std::thread::JoinHandle<String>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let handle = std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        let mut request = Vec::new();
        let mut chunk = [0u8; 1024];
        while !request.windows(4).any(|w| w == b"\r\n\r\n") {
            let n = stream.read(&mut chunk).unwrap();
            request.extend_from_slice(&chunk[..n]);
        }
        let reply = format!(
            "{status_line}\r\nContent-Length: {}\r\nSubscription-Userinfo: upload=1; download=2; total=10; expire=1893456000\r\nConnection: close\r\n\r\n{body}",
            body.len()
        );
        stream.write_all(reply.as_bytes()).unwrap();
        String::from_utf8(request).unwrap()
    });
    (port, handle)
}

#[tokio::test]
async fn fetch_sends_the_declared_agent_and_reads_nodes_and_quota() {
    let (port, server) = serve("HTTP/1.1 200 OK", fixture("hostile.yaml"));
    let mut p = provider("s-a", None);
    p.url = format!("http://127.0.0.1:{port}/feed");
    p.user_agent = Some("custom/2".into());
    let feed = feeds::fetch(&p, Duration::from_secs(5)).await.unwrap();
    assert_eq!(feed.nodes.len(), 2);
    assert_eq!(
        (feed.used_bytes, feed.total_bytes, feed.expires_at_epoch_ms),
        (Some(3), Some(10), Some(1_893_456_000_000))
    );
    let request = server.join().unwrap();
    assert!(
        request
            .to_ascii_lowercase()
            .contains("user-agent: custom/2\r\n"),
        "{request}"
    );
}

#[tokio::test]
async fn a_user_agent_that_could_split_the_header_is_refused_before_any_connection() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let mut p = provider("s-a", None);
    p.url = format!(
        "http://127.0.0.1:{}/feed",
        listener.local_addr().unwrap().port()
    );
    p.user_agent = Some("x\r\nX-Injected: 1".into());
    let error = feeds::fetch(&p, Duration::from_secs(5)).await.unwrap_err();
    assert_eq!(error, ErrorCode::InvalidArgument);
    assert!(listener.accept().is_err(), "a connection was made");
}

// Schedule.

#[test]
fn the_default_is_on_every_twelve_hours_and_only_the_known_hours_are_taken() {
    let mut schedule = Schedule::default();
    assert_eq!(
        (schedule.enabled, schedule.default_hours),
        (true, DEFAULT_HOURS)
    );
    for hours in [1, 6, 12, 24] {
        schedule.set(false, hours).unwrap();
        assert_eq!((schedule.enabled, schedule.default_hours), (false, hours));
    }
    let before = schedule.clone();
    for hours in [0, 2, 48] {
        assert_eq!(schedule.set(true, hours), Err(ErrorCode::InvalidArgument));
        assert_eq!(schedule, before);
    }
}

#[test]
fn an_early_wake_fetches_nothing_and_two_wakes_fetch_a_due_provider_once() {
    let providers = [provider("s-a", None)];
    let mut schedule = Schedule::default();
    let mut claims = Claims::default();
    let t0 = 1_000_000_000;

    // Never fetched: due now. The second wake meets the fetch still running.
    let first = schedule.refresh_due(&mut claims, &providers, t0);
    assert_eq!(first.claimed, providers);
    let second = schedule.refresh_due(&mut claims, &providers, t0);
    assert!(second.claimed.is_empty());
    assert!(schedule.finish(&mut claims, &providers, "s-a", true, t0));

    // Finished: not due before twelve hours, due at twelve.
    let early = schedule.refresh_due(&mut claims, &providers, t0 + 12 * HOUR - 1);
    assert!(early.claimed.is_empty());
    assert_eq!(early.next_refresh_at, Some(t0 + 12 * HOUR));
    let due = schedule.refresh_due(&mut claims, &providers, t0 + 12 * HOUR);
    assert_eq!(due.claimed, providers);
    assert_eq!(due.next_refresh_at, None);
}

#[test]
fn the_feed_interval_decides_and_reports_its_source() {
    let mut schedule = Schedule::default();
    schedule.set(true, 24).unwrap();
    let own = provider("s-own", Some((3_600, IntervalSource::Subscription)));
    let assets = provider("s-assets", Some((21_600, IntervalSource::Assets)));
    let global = provider("s-global", None);
    let seen: Vec<_> = [&own, &assets, &global]
        .map(|p| {
            let f = schedule.feed(p);
            (f.effective_interval_seconds, f.interval_source)
        })
        .into();
    assert_eq!(
        seen,
        [
            (3_600, IntervalSource::Subscription),
            (21_600, IntervalSource::Assets),
            (86_400, IntervalSource::Global),
        ]
    );
}

#[test]
fn a_turned_on_default_applies_to_unconfigured_feeds_only() {
    let providers = [
        provider("s-own", Some((6 * 3_600, IntervalSource::Subscription))),
        provider("s-none", None),
    ];
    let mut schedule = Schedule::default();
    schedule.set(true, 24).unwrap();
    let mut claims = Claims::default();
    let t0 = 5_000_000_000;
    let first = schedule.refresh_due(&mut claims, &providers, t0);
    assert_eq!(first.claimed.len(), 2);
    for id in ["s-own", "s-none"] {
        schedule.finish(&mut claims, &providers, id, true, t0);
    }
    let at_six = schedule.refresh_due(&mut claims, &providers, t0 + 6 * HOUR);
    let ids: Vec<_> = at_six.claimed.iter().map(|p| p.id.as_str()).collect();
    assert_eq!(ids, ["s-own"]);
    assert_eq!(at_six.next_refresh_at, Some(t0 + 24 * HOUR));
}

#[test]
fn off_stops_scheduled_fetches_but_a_manual_update_still_runs() {
    let providers = [provider(
        "s-a",
        Some((6 * 3_600, IntervalSource::Subscription)),
    )];
    let mut schedule = Schedule::default();
    schedule.set(false, 12).unwrap();
    let mut claims = Claims::default();
    let t0 = 7_000_000_000;
    let due = schedule.refresh_due(&mut claims, &providers, t0 + 100 * HOUR);
    assert!(due.claimed.is_empty());
    assert_eq!(due.next_refresh_at, None);
    assert_eq!(
        schedule.overview(&claims, &providers, t0).next_refresh_at,
        None
    );

    assert!(claims.claim(&providers[0]));
    assert!(
        !claims.claim(&providers[0]),
        "a second manual update joins the first"
    );
    assert!(schedule.finish(&mut claims, &providers, "s-a", true, t0));

    schedule.set(true, 12).unwrap();
    let overview = schedule.overview(&claims, &providers, t0);
    assert_eq!(overview.next_refresh_at, Some(t0 + 6 * HOUR));
}

#[test]
fn a_manual_interval_is_never_scheduled() {
    let providers = [provider("s-m", Some((0, IntervalSource::Subscription)))];
    let schedule = Schedule::default();
    let mut claims = Claims::default();
    let due = schedule.refresh_due(&mut claims, &providers, 1);
    assert!(due.claimed.is_empty());
    assert_eq!(due.next_refresh_at, None);
    assert_eq!(schedule.feed(&providers[0]).effective_interval_seconds, 0);
}

#[test]
fn a_failed_fetch_waits_an_hour_not_a_whole_interval() {
    let providers = [provider("s-a", None)];
    let mut schedule = Schedule::default();
    let mut claims = Claims::default();
    let t0 = 9_000_000_000;
    schedule.refresh_due(&mut claims, &providers, t0);
    schedule.finish(&mut claims, &providers, "s-a", false, t0);
    assert!(
        schedule
            .refresh_due(&mut claims, &providers, t0 + HOUR - 1)
            .claimed
            .is_empty()
    );
    assert_eq!(
        schedule
            .refresh_due(&mut claims, &providers, t0 + HOUR)
            .claimed
            .len(),
        1
    );
}

#[test]
fn a_late_result_for_a_removed_or_changed_provider_is_dropped() {
    let original = provider("s-a", None);
    let mut schedule = Schedule::default();
    let t0 = 11_000_000_000;

    // Removed while the fetch ran.
    let mut claims = Claims::default();
    schedule.refresh_due(&mut claims, std::slice::from_ref(&original), t0);
    assert!(!schedule.finish(&mut claims, &[], "s-a", true, t0 + 1));

    // The URL changed while the fetch ran.
    let mut moved = original.clone();
    moved.url = "https://feeds.example.net/moved".into();
    schedule.refresh_due(&mut claims, std::slice::from_ref(&original), t0);
    assert!(!schedule.finish(
        &mut claims,
        std::slice::from_ref(&moved),
        "s-a",
        true,
        t0 + 1
    ));

    // Neither result was recorded: the provider is still due at once.
    let again = schedule.refresh_due(&mut claims, std::slice::from_ref(&moved), t0 + 2);
    assert_eq!(again.claimed, [moved]);
}

#[test]
fn a_refused_claim_does_not_replace_the_fetch_in_flight() {
    let original = provider("s-a", None);
    let mut moved = original.clone();
    moved.url = "https://feeds.example.net/moved".into();
    let mut schedule = Schedule::default();
    let mut claims = Claims::default();
    assert!(claims.claim(&original));
    assert!(
        !claims.claim(&moved),
        "a manual update of the edited URL joins the fetch"
    );
    // The body fetched from the old URL is not recorded under the new one.
    assert!(!schedule.finish(&mut claims, std::slice::from_ref(&moved), "s-a", true, 1));
}

#[test]
fn an_id_given_to_another_feed_does_not_inherit_the_old_progress() {
    let old = provider("s-twin#2", None);
    let mut replaced = old.clone();
    replaced.url = "https://feeds.example.net/new".into();
    let mut schedule = Schedule::default();
    let mut claims = Claims::default();
    let t0 = 20_000_000_000;
    schedule.refresh_due(&mut claims, std::slice::from_ref(&old), t0);
    assert!(schedule.finish(
        &mut claims,
        std::slice::from_ref(&old),
        "s-twin#2",
        true,
        t0
    ));

    let due = schedule.refresh_due(&mut claims, std::slice::from_ref(&replaced), t0 + 1);
    assert_eq!(due.claimed, [replaced.clone()]);
    assert!(schedule.finish(
        &mut claims,
        std::slice::from_ref(&replaced),
        "s-twin#2",
        false,
        t0 + 2
    ));
    let again = schedule.refresh_due(&mut claims, std::slice::from_ref(&replaced), t0 + 3);
    assert!(
        again.claimed.is_empty(),
        "the new feed's own failure is remembered"
    );
}

#[test]
fn the_record_round_trips_as_camel_case_json() {
    let mut schedule = Schedule::default();
    let providers = [provider("s-a", None)];
    let mut claims = Claims::default();
    schedule.refresh_due(&mut claims, &providers, 42);
    schedule.finish(&mut claims, &providers, "s-a", true, 42);
    let text = serde_json::to_string(&schedule).unwrap();
    assert_eq!(
        text,
        r#"{"enabled":true,"defaultHours":12,"progress":{"s-a":{"url":"https://feeds.example.net/s-a","userAgent":null,"lastSuccessMs":42,"lastFailureMs":null}}}"#
    );
    assert_eq!(serde_json::from_str::<Schedule>(&text).unwrap(), schedule);
}
