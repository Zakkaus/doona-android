mod common;

use std::fs;
use std::path::Path;

use common::TempDir;
use doonacore::honkconf::runtime::{
    Decision, Flow, GlobalTarget, Mode, ModeResult, Network, Runtime, RuntimeError,
};
use doonacore::honkconf::{Capabilities, HonkStore, load};

const FIXTURE: &str = include_str!("fixtures/honk/runtime.dae");

fn committed(data: &TempDir, source: &str) -> HonkStore {
    let mut store = HonkStore::open(data.path()).unwrap();
    let id = store.draft(None).unwrap();
    store.edit(id, Path::new("config.dae"), source).unwrap();
    store.apply(id, &Capabilities::linked()).unwrap();
    store
}

fn flow(destination: &str, domain: Option<&str>) -> Flow {
    Flow {
        network: Network::Tcp,
        source: "10.0.0.2:40000".parse().unwrap(),
        destination: destination.parse().unwrap(),
        domain: domain.map(str::to_owned),
        domain_verified: domain.is_some(),
        process_name: None,
        mac: None,
        dscp: None,
    }
}

/// Outbound after mode handling, the mode result and the chain to the first leaf; `direct` and
/// `block` are leaves of their own.
fn summary(decision: &Decision) -> (String, ModeResult, Vec<String>) {
    let chain = decision
        .candidates
        .first()
        .map(|leaf| leaf.chain.clone())
        .unwrap_or_default();
    (decision.outbound.clone(), decision.mode.clone(), chain)
}

fn expect(runtime: &Runtime, flow: &Flow, outbound: &str, mode: ModeResult, chain: &[&str]) {
    let got = summary(&runtime.decide(flow));
    let chain = chain.iter().map(|s| (*s).to_owned()).collect();
    assert_eq!(got, (outbound.to_owned(), mode, chain), "{flow:?}");
}

#[test]
fn committed_groups_decide_in_every_mode() {
    let data = TempDir::new("honk-runtime");
    let geo = TempDir::new("honk-runtime-geo");
    let store = committed(&data, FIXTURE);
    let mut runtime = Runtime::load(&store, geo.path()).unwrap();
    assert_eq!(runtime.revision(), store.head());
    assert_eq!(runtime.mode(), Mode::Rule);
    assert_eq!(runtime.global_target(), None);

    let fallback = flow("203.0.113.5:443", None);
    let ssh = flow("203.0.113.5:22", None);
    let blocked = flow("203.0.113.6:443", Some("ads.blocked.test"));
    let video = flow("203.0.113.7:443", Some("www.video.test"));
    let rescue = flow("203.0.113.9:8443", None);
    let unchanged = ModeResult::Unchanged;

    let decision = runtime.decide(&fallback);
    assert_eq!(decision.rule, None, "the routing fallback matched no rule");
    expect(
        &runtime,
        &fallback,
        "japan",
        unchanged.clone(),
        &["japan", "tokyo"],
    );
    let decision_ssh = runtime.decide(&ssh);
    assert!(decision_ssh.must);
    assert_eq!(decision_ssh.rule.as_ref().map(|r| r.index), Some(0));
    expect(&runtime, &ssh, "direct", unchanged.clone(), &["direct"]);
    expect(&runtime, &blocked, "block", unchanged.clone(), &["block"]);
    expect(
        &runtime,
        &video,
        "streaming",
        unchanged.clone(),
        &["streaming", "taiwan", "taipei"],
    );
    expect(
        &runtime,
        &rescue,
        "rescue",
        unchanged.clone(),
        &["rescue", "osaka"],
    );

    runtime.set_mode(Mode::Global);
    expect(&runtime, &fallback, "block", ModeResult::Block, &["block"]);
    runtime
        .set_global_target(GlobalTarget::Group("taiwan".into()))
        .unwrap();
    let taiwan = ModeResult::Group("taiwan".into());
    expect(
        &runtime,
        &fallback,
        "taiwan",
        taiwan.clone(),
        &["taiwan", "taipei"],
    );
    expect(&runtime, &video, "taiwan", taiwan, &["taiwan", "taipei"]);
    expect(&runtime, &ssh, "direct", unchanged.clone(), &["direct"]);
    expect(&runtime, &blocked, "block", unchanged.clone(), &["block"]);
    assert!(
        runtime
            .set_global_target(GlobalTarget::Group("Proxy".into()))
            .is_err()
    );
    assert_eq!(
        runtime.global_target(),
        Some(&GlobalTarget::Group("taiwan".into()))
    );
    let doc = store.read_revision(store.head().unwrap()).unwrap();
    let osaka = doc
        .config()
        .nodes
        .iter()
        .find(|n| n.name == "osaka")
        .unwrap()
        .id;
    runtime
        .set_global_target(GlobalTarget::Node(osaka))
        .unwrap();
    expect(
        &runtime,
        &rescue,
        "osaka",
        ModeResult::Node(osaka),
        &["osaka"],
    );

    runtime.set_mode(Mode::Direct);
    assert_eq!(runtime.mode(), Mode::Direct);
    expect(
        &runtime,
        &fallback,
        "direct",
        ModeResult::Direct,
        &["direct"],
    );
    expect(&runtime, &video, "direct", ModeResult::Direct, &["direct"]);
    expect(&runtime, &blocked, "block", unchanged, &["block"]);
}

#[test]
fn a_configuration_that_fails_to_load_is_a_typed_error() {
    let data = TempDir::new("honk-runtime-empty");
    let store = HonkStore::open(data.path()).unwrap();
    let error = Runtime::load(&store, data.path()).err().unwrap();
    assert!(matches!(error, RuntimeError::NoRevision), "{error}");

    let dir = TempDir::new("honk-runtime-geo-missing");
    let entry = dir.path().join("main.dae");
    fs::write(
        &entry,
        "routing { domain(geosite:cn) -> direct\n fallback: block }",
    )
    .unwrap();
    let doc = load(&entry).unwrap();
    let geo = TempDir::new("honk-runtime-geo-empty");
    let error = Runtime::new(&doc, geo.path()).err().unwrap();
    assert!(matches!(error, RuntimeError::Engine(_)), "{error}");
}

#[test]
fn runtime_is_shareable_across_threads() {
    fn shareable<T: Send + Sync>() {}
    shareable::<Runtime>();
}
