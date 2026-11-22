//! Every example payload in `contracts/doona-core/` parses into its DTO and serializes back to the
//! same JSON, so the Rust types and the Kotlin ones (tested against the same files) agree.

use doonacore::dto::{
    Empty, ErrorCode, Init, Mode, Reply, SelectArgs, Snapshot, StartConfig, Subscription,
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

const COVERED: [&str; 9] = [
    "call-error.json",
    "call-ok-empty.json",
    "call-ok-subscription.json",
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
    // A warning leaves the data path up.
    assert_eq!(running.warning, Some(ErrorCode::LocalNetwork));
    let died = round_trip::<Snapshot>("snapshot-died.json");
    assert!(!died.running && died.error.is_some() && died.warning.is_none());
    let stopped = round_trip::<Snapshot>("snapshot-stopped-catalog.json");
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
