//! Lane c7-feeds: provider declarations and feed bodies.
mod common;

use std::collections::BTreeSet;
use std::fs;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::path::PathBuf;
use std::time::Duration;

use common::TempDir;
use doonacore::dto::ErrorCode;
use doonacore::honkconf::feeds::{self, Feed, IntervalSource, Provider};
use doonacore::honkconf::schedule::{Claims, Schedule};
use doonacore::honkconf::{HonkConfig, load};

fn fixture(name: &str) -> String {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/honk/feeds");
    fs::read_to_string(path.join(name)).unwrap()
}

fn config(name: &str) -> HonkConfig {
    parse(&fixture(name))
}

fn parse(text: &str) -> HonkConfig {
    let dir = TempDir::new("honk-feeds");
    let path = dir.path().join("main.dae");
    fs::write(&path, text).unwrap();
    load(path).unwrap_or_else(|e| panic!("{e}: {text}"))
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
        .map(|p| (p.name.as_str(), p.interval.map(|a| (a.seconds, a.source))))
        .collect();
    assert_eq!(
        seen,
        [
            ("inherits", Some((21_600, IntervalSource::Assets))),
            ("own", Some((3_600, IntervalSource::Subscription))),
            ("agent", Some((21_600, IntervalSource::Assets))),
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
        .map(|p| (p.name.as_str(), p.interval.map(|a| (a.seconds, a.source))))
        .collect();
    assert_eq!(
        seen,
        [
            ("bare", None),
            ("day", Some((86_400, IntervalSource::Subscription))),
            ("my feed", Some((3_600, IntervalSource::Subscription))),
            ("manual", Some((0, IntervalSource::Subscription))),
            ("old", Some((21_600, IntervalSource::Subscription))),
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
                p.name.as_str(),
                url,
                p.interval.map(|a| (a.seconds, a.source)),
            )
        })
        .collect();
    assert_eq!(
        seen,
        [
            ("twin", "one", Some((10_800, IntervalSource::Assets))),
            ("other", "other", Some((10_800, IntervalSource::Assets))),
            ("twin", "two", Some((7_200, IntervalSource::Subscription))),
            // The same tag and URL as the first, with its own interval.
            ("twin", "one", Some((3_600, IntervalSource::Subscription))),
        ]
    );
    let ids: Vec<_> = providers.iter().map(|p| p.id.as_str()).collect();
    // Stored with pins and statistics, so the value itself must not move.
    assert_eq!(ids[0], "s-843aa99325295210");
    assert_eq!(ids[3], format!("{}#2", ids[0]));
    assert_eq!(BTreeSet::from_iter(&ids).len(), 4);
}

#[test]
fn every_form_honk_accepts_gives_the_entrys_own_interval() {
    let providers = feeds::providers(&config("wrapper.dae"));
    let seen: Vec<_> = providers
        .iter()
        .map(|p| (p.name.as_str(), p.interval.map(|a| (a.seconds, a.source))))
        .collect();
    assert_eq!(
        seen,
        [
            ("a", Some((21_600, IntervalSource::Subscription))),
            ("b", Some((43_200, IntervalSource::Assets))),
            // honk reads `30s {` as an unsupported duration and falls back to 0.
            ("quick", Some((0, IntervalSource::Subscription))),
            ("old", Some((7_200, IntervalSource::Subscription))),
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
fn inserting_an_entry_with_the_same_tag_keeps_the_other_ids() {
    let ids = |tags: &[&str]| -> Vec<(String, String)> {
        let entries: String = tags
            .iter()
            .map(|path| format!("    twin: 'https://feeds.example.net/{path}'\n"))
            .collect();
        let text = format!("subscription {{\n{entries}}}\nrouting {{ fallback: direct }}");
        feeds::providers(&parse(&text))
            .into_iter()
            .map(|p| (p.url, p.id))
            .collect()
    };
    let one = ids(&["one"]);
    let two = ids(&["one", "two"]);
    let inserted = ids(&["zero", "one", "two"]);
    assert_eq!(two[0], one[0]);
    assert_eq!(inserted[1..], two);
    assert_ne!(inserted[0].1, inserted[1].1);
}

#[test]
fn an_exact_twin_written_above_takes_the_bare_id() {
    let ids = |intervals: &[&str]| -> Vec<(u32, String)> {
        let entries: String = intervals
            .iter()
            .map(|i| format!("    twin: 'https://feeds.example.net/one' {{ interval: {i} }}\n"))
            .collect();
        let text = format!("subscription {{\n{entries}}}\nrouting {{ fallback: direct }}");
        feeds::providers(&parse(&text))
            .into_iter()
            .map(|p| (p.interval.unwrap().seconds, p.id))
            .collect()
    };
    let bare = ids(&["1h"]).remove(0).1;
    // The interval tells the entries apart here: the older 1 h one moves to `#2`.
    let want = [(21_600, bare.clone()), (3_600, format!("{bare}#2"))];
    assert_eq!(ids(&["6h", "1h"]), want);
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
    schedule.finish(&mut claims, &providers, &providers[0].id, true, t0);
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

fn local_provider(port: u16, user_agent: &str) -> Provider {
    Provider {
        id: "s-a".into(),
        name: "local".into(),
        url: format!("http://127.0.0.1:{port}/feed"),
        user_agent: Some(user_agent.into()),
        interval: None,
    }
}

#[tokio::test]
async fn fetch_sends_the_declared_agent_and_reads_nodes_and_quota() {
    let (port, server) = serve("HTTP/1.1 200 OK", fixture("hostile.yaml"));
    let p = local_provider(port, "custom/2");
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
    let port = listener.local_addr().unwrap().port();
    let p = local_provider(port, "x\r\nX-Injected: 1");
    let error = feeds::fetch(&p, Duration::from_secs(5)).await.unwrap_err();
    assert_eq!(error, ErrorCode::InvalidArgument);
    assert!(listener.accept().is_err(), "a connection was made");
}
