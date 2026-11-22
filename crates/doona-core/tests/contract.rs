//! Every example payload in `contracts/doona-core/` parses into its DTO and serializes back to the
//! same JSON, so the Rust types and the Kotlin ones (tested against the same files) agree.

use doonacore::dto::{
    Empty, ErrorCode, Init, Mode, Nodes, NodesArgs, Reply, Rules, RulesArgs, SelectArgs, Snapshot,
    SourceKind, StartConfig, Subscription, TargetKind, TrafficSeries, Usage,
};
use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::Value;
use std::path::PathBuf;

fn fixtures() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../contracts/doona-core")
}

fn read(name: &str) -> Value {
    let text = std::fs::read_to_string(fixtures().join(name)).unwrap();
    serde_json::from_str(&text).unwrap()
}

/// Parses `name` as `T`, then checks that `T` writes the file's JSON back.
fn round_trip<T: DeserializeOwned + Serialize>(name: &str) -> T {
    let json = read(name);
    let value: T = serde_json::from_value(json.clone())
        .unwrap_or_else(|e| panic!("{name} does not parse: {e}"));
    assert_eq!(serde_json::to_value(&value).unwrap(), json, "{name}");
    value
}

const COVERED: [&str; 15] = [
    "call-error.json",
    "call-nodes-args.json",
    "call-ok-empty.json",
    "call-ok-nodes.json",
    "call-ok-rules.json",
    "call-ok-subscription.json",
    "call-ok-traffic-series.json",
    "call-ok-usage.json",
    "call-rules-args.json",
    "call-select-args.json",
    "init.json",
    "snapshot-died.json",
    "snapshot-running.json",
    "snapshot-stopped-catalog.json",
    "start-config.json",
];

#[test]
fn every_fixture_is_covered() {
    let mut files: Vec<String> = std::fs::read_dir(fixtures())
        .unwrap()
        .map(|e| e.unwrap().file_name().into_string().unwrap())
        .filter(|n| n.ends_with(".json"))
        .collect();
    files.sort();
    assert_eq!(files, COVERED);
}

#[test]
fn init_and_start_config() {
    assert_eq!(round_trip::<Init>("init.json").schema, 1);
    let config = round_trip::<StartConfig>("start-config.json");
    assert_eq!(config.mtu, 8500);
    assert_eq!(config.dns.to_string(), "172.19.0.2");
}

#[test]
fn call_replies_and_args() {
    let error = round_trip::<Reply<Empty>>("call-error.json");
    assert!(matches!(error, Reply::Error(e) if e.code.as_str() == "fetch_failed"));
    assert_eq!(
        round_trip::<Reply<Empty>>("call-ok-empty.json"),
        Reply::Ok(Empty {})
    );
    let sub = round_trip::<Reply<Subscription>>("call-ok-subscription.json");
    assert!(matches!(sub, Reply::Ok(s) if s.id == "s1" && s.nodes == 2));
    let select = round_trip::<SelectArgs>("call-select-args.json");
    assert_eq!(select.member_id.as_deref(), Some("n-0c41be77"));
}

#[test]
fn snapshots() {
    let running = round_trip::<Snapshot>("snapshot-running.json");
    assert!(running.running && running.error.is_none() && running.catalog.is_none());
    assert!(running.traffic.down_peak <= running.traffic.down_total);
    assert!(running.today.down_total >= running.traffic.down_total);
    // A warning leaves the data path up.
    assert_eq!(running.warning, Some(ErrorCode::LocalNetwork));
    let died = round_trip::<Snapshot>("snapshot-died.json");
    assert!(!died.running && died.error.is_some() && died.warning.is_none());
    assert_eq!(running.today.yesterday_by_now, Some(1_610_612_736));
    let stopped = round_trip::<Snapshot>("snapshot-stopped-catalog.json");
    // No yesterday was kept.
    assert_eq!(stopped.today.yesterday_by_now, None);
    let catalog = stopped.catalog.unwrap();
    assert_eq!(catalog.mode, Mode::Global);
    assert_eq!(catalog.groups.len(), 3);
    // Two members share a display name but not an id.
    let proxy = &catalog.groups[0];
    assert_eq!(proxy.members[2].name, proxy.members[3].name);
    assert_ne!(proxy.members[2].id, proxy.members[3].id);
    // A node on the local network whose probe failed says why; the others carry no code.
    let lan = &proxy.members[3];
    assert!(lan.failed && lan.latency_ms.is_none());
    assert_eq!(lan.error, Some(ErrorCode::LocalNetwork));
    assert!(proxy.members[..3].iter().all(|m| m.error.is_none()));
    // The template's rules, all compiled.
    assert_eq!(catalog.main_group_id, "g-Proxy");
    let source = catalog.rule_source;
    assert_eq!(source.kind, SourceKind::Template);
    assert_eq!(source.template.as_deref(), Some("cn-direct"));
    assert_eq!((source.rules, source.lines, source.skipped), (5, 23, 0));
    assert!(source.error.is_none() && source.subscription_id.is_none());
}

#[test]
fn missing_required_fields_fail_and_unknown_ones_are_ignored() {
    assert!(serde_json::from_str::<StartConfig>(r#"{"mtu":8500,"ipv4":"172.19.0.1"}"#).is_err());
    let config: StartConfig = serde_json::from_str(
        r#"{"mtu":1500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":[],"later":1}"#,
    )
    .unwrap();
    assert_eq!(config.mtu, 1500);
    assert!(serde_json::from_str::<SelectArgs>(r#"{"memberId":null}"#).is_err());
}

fn ok<T>(reply: Reply<T>) -> T {
    match reply {
        Reply::Ok(value) => value,
        Reply::Error(e) => panic!("an error reply: {e:?}"),
    }
}

#[test]
fn stats_replies() {
    let series = ok(round_trip::<Reply<TrafficSeries>>(
        "call-ok-traffic-series.json",
    ));
    assert_eq!(
        (series.step_seconds, series.up.len()),
        (1, series.down.len())
    );
    assert_eq!(series.down_peak, *series.down.iter().max().unwrap());
    let usage = ok(round_trip::<Reply<Usage>>("call-ok-usage.json"));
    for day in std::iter::once(&usage.today).chain(&usage.yesterday) {
        assert_eq!(
            (day.up.len(), day.down.len(), day.flows.len()),
            (24, 24, 24)
        );
    }
    // Yesterday is the last of the finished days.
    let yesterday = usage.yesterday.unwrap();
    let last = usage.days.last().unwrap();
    assert_eq!(last.date, yesterday.date);
    assert_eq!(last.down, yesterday.down.iter().sum::<u64>());
    assert_eq!(usage.today.outbounds[0].id, "direct");
    let nodes = ok(round_trip::<Reply<Nodes>>("call-ok-nodes.json")).nodes;
    assert_eq!(nodes[0].tests[1].ms, None);
    assert_eq!(nodes[1].transport, "quic");
    let args = round_trip::<NodesArgs>("call-nodes-args.json");
    assert_eq!(args.ids.unwrap().len(), nodes.len());
    // `ids` is required, as every nullable argument key.
    assert!(serde_json::from_str::<NodesArgs>("{}").is_err());
}

#[test]
fn rule_replies_and_args() {
    let args = round_trip::<RulesArgs>("call-rules-args.json");
    assert_eq!((args.offset, args.limit), (0, 50));
    let rules = ok(round_trip::<Reply<Rules>>("call-ok-rules.json"));
    assert_eq!((rules.total, rules.rows.len()), (5, 5));
    let r#match = &rules.rows[4];
    assert_eq!(
        (r#match.kind.as_str(), r#match.sample.as_ref()),
        ("Match", None)
    );
    assert_eq!((r#match.target_kind, r#match.count), (TargetKind::Group, 0));
    assert!(
        rules
            .rows
            .iter()
            .all(|r| r.id.len() == 18 && r.skipped.is_none())
    );
    // `offset` and `limit` are both required.
    assert!(serde_json::from_str::<RulesArgs>(r#"{"limit":50}"#).is_err());
}
