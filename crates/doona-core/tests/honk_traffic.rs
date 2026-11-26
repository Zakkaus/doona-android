//! Slice 8: the traffic decision of a captured flow, against Lane A's stock honk reference
//! (`tools/honk-reference`, `fixtures/honk/reference`), plus what Android does differently: a
//! skipped rule leaves the engine as a unit, a marked fallback is refused, and a matched rule
//! charges its own row's meter.

mod common;

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use common::TempDir;
use doonacore::honkconf::meters::RevisionMeters;
use doonacore::honkconf::runtime::{Flow, GlobalTarget, Mode, Network, Runtime, Target, Verdict};
use doonacore::honkconf::{Capabilities, HonkConfig, HonkConfigError, HonkStore, load};
use honk_config::config::{BLOCK_NODE_ID, DIRECT_NODE_ID};
use honk_outbound::alive::{AliveDialerSet, IpVersion, ProbeDomain};
use honk_outbound::group::{GroupManager, SelectionNetwork, SelectorNetworks};
use serde_json::{Value, json};

const REFERENCE: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/honk/reference");

fn flow(network: Network, destination: &str, domain: Option<&str>) -> Flow {
    Flow {
        network,
        source: "172.19.0.1:40000".parse().unwrap(),
        destination: destination.parse().unwrap(),
        domain: domain.map(str::to_owned),
        domain_verified: domain.is_some(),
        process_name: None,
        mac: None,
        dscp: None,
    }
}

fn runtime(doc: &HonkConfig, geo: &Path) -> Runtime {
    Runtime::new(doc, &Capabilities::linked(), &RevisionMeters::new(), geo).unwrap()
}

fn doc(source: &str) -> (TempDir, HonkConfig) {
    let dir = TempDir::new("honk-traffic");
    let entry = dir.path().join("config.dae");
    fs::write(&entry, source).unwrap();
    let doc = load(&entry).unwrap();
    (dir, doc)
}

/// The live group state Lane A sets up for a case: honk's GroupManager over the assembled
/// configuration, with forced health and Selector choices.
fn groups(doc: &HonkConfig, case: &Value) -> GroupManager {
    let mut config = doc.config().clone();
    config.ensure_builtin_nodes();
    let config = config
        .assemble(honk_config::parser::resolve_group_filters)
        .unwrap();
    let alive = Arc::new(AliveDialerSet::new());
    let groups = GroupManager::with_alive_set(&config.groups, &config.nodes, Some(alive.clone()));
    for node in &config.nodes {
        alive.register_node(node.id, node.name.clone(), None);
    }
    for health in case["health"].as_array().into_iter().flatten() {
        let node = groups
            .node_by_name(health["node"].as_str().unwrap())
            .unwrap();
        let udp = health["network"] == "udp";
        if health["alive"] == false {
            let dead: &[ProbeDomain] = if udp {
                &[ProbeDomain::DataUdp, ProbeDomain::DnsUdp]
            } else {
                &[ProbeDomain::Tcp]
            };
            for &domain in dead {
                alive.report_unavailable_forced(node.id, domain, IpVersion::V4);
            }
        } else if let Some(ms) = health["latency_ms"].as_u64() {
            let domain = if udp {
                ProbeDomain::DataUdp
            } else {
                ProbeDomain::Tcp
            };
            alive.record_probe_latency(node.id, domain, IpVersion::V4, Duration::from_millis(ms));
        }
    }
    for choice in case["select"].as_array().into_iter().flatten() {
        let (group, member) = (choice["group"].as_str(), choice["member"].as_str());
        groups
            .set_selector_choice(group.unwrap(), member.unwrap(), SelectorNetworks::Both)
            .unwrap();
    }
    groups
}

/// The leaf in Lane A's report form: a group's leaf from the live state, the rest from the verdict.
fn leaf(verdict: &Verdict, groups: &GroupManager, doc: &HonkConfig, network: Network) -> Value {
    let outbound = &verdict.decision.outbound;
    let (kind, name, chain) = match &verdict.target {
        Target::Group(group) => {
            let domain = match network {
                Network::Tcp => ProbeDomain::Tcp,
                Network::Udp => ProbeDomain::DataUdp,
            };
            let node = groups.select_node_for_domain(group, domain, IpVersion::V4);
            let chain = groups
                .selection_chain_for_network(group, SelectionNetwork::from_probe_domain(domain));
            let kind = match node.map(|n| n.id) {
                None => "none",
                Some(DIRECT_NODE_ID) => "direct",
                Some(BLOCK_NODE_ID) => "block",
                Some(_) => "node",
            };
            (kind, node.map(|n| n.name.clone()), chain)
        }
        Target::Node(id) => {
            let node = doc.config().nodes.iter().find(|n| n.id == *id).unwrap();
            ("node", Some(node.name.clone()), vec![outbound.clone()])
        }
        Target::Direct => ("direct", None, vec!["direct".to_owned()]),
        Target::Refuse if outbound == "block" => ("block", None, vec!["block".to_owned()]),
        Target::Refuse => ("none", None, vec![outbound.clone()]),
    };
    json!({"kind": kind, "name": name, "chain": chain})
}

fn report(verdict: &Verdict, leaf: Value, name: &str) -> Value {
    let decision = &verdict.decision;
    json!({
        "leaf": leaf,
        "mark": decision.mark,
        "must": decision.must,
        "name": name,
        "outbound": decision.outbound,
        "rule": verdict.row.as_ref().and_then(|row| row.rule),
        "rule_payload": decision.rule.as_ref().map(|hit| hit.payload.clone()),
    })
}

fn probe_flow(probe: &Value) -> (Network, Flow) {
    let network = match probe["protocol"].as_str().unwrap() {
        "udp" => Network::Udp,
        _ => Network::Tcp,
    };
    let ip: std::net::IpAddr = probe["dst_ip"].as_str().unwrap().parse().unwrap();
    let to = std::net::SocketAddr::new(ip, probe["dst_port"].as_u64().unwrap() as u16);
    (
        network,
        flow(network, &to.to_string(), probe["domain"].as_str()),
    )
}

/// What Android does where stock honk dials a direct fwmark: the marked rule is skipped and the
/// marked fallback is refused, so both probes that reach either end refused at the fallback.
fn mark_must(verdict: &Verdict, probe: &str) {
    let decision = &verdict.decision;
    match probe {
        "ssh" => assert_eq!(verdict.target, Target::Group("proxy".into())),
        _ => {
            assert_eq!(verdict.target, Target::Refuse, "{probe}");
            assert_eq!((decision.rule.as_ref(), decision.mark), (None, Some(256)));
        }
    }
}

/// Cases the stock loader reads but honk's validation refuses, as the daemon would at start.
const REFUSED: &[&str] = &["node-in-no-group", "unknown-outbound", "zero-groups"];

/// Each probe in Rule mode against Lane A's report; then Direct and GLOBAL, where `must` and
/// `block` keep the Rule-mode target and everything else takes the mode's.
fn compare(case: &Value, expected: &Value, geo: &Path) {
    let name = case["name"].as_str().unwrap();
    let doc = match load(Path::new(REFERENCE).join(case["config"].as_str().unwrap())) {
        Ok(doc) => doc,
        Err(error) => {
            // Refused by honk's validation, not by the loader's budget or format checks.
            let validated = matches!(error, HonkConfigError::ParseFailed { .. });
            assert!(REFUSED.contains(&name) && validated, "{name}: {error:?}");
            return;
        }
    };
    assert!(!REFUSED.contains(&name), "{name} loads");
    let runtime = runtime(&doc, geo);
    let live = groups(&doc, case);
    let global = doc.config().groups.first().map_or_else(
        || GlobalTarget::Node(doc.config().nodes[0].id),
        |g| GlobalTarget::Group(g.name.clone()),
    );
    let (probes, wanted) = (
        case["probes"].as_array().unwrap(),
        expected["probes"].as_array().unwrap(),
    );
    assert_eq!(
        probes.len(),
        wanted.len(),
        "{name}: probes against the report"
    );
    for (probe, want) in probes.iter().zip(wanted) {
        let label = probe["name"].as_str().unwrap();
        let (network, flow) = probe_flow(probe);
        runtime.set_mode(Mode::Rule);
        let ruled = runtime.decide(&flow);
        if name == "mark-must" {
            mark_must(&ruled, label);
        } else {
            let got = report(&ruled, leaf(&ruled, &live, &doc, network), label);
            assert_eq!(&got, want, "{name}/{label}");
        }

        // A marked fallback keeps its mark in Direct, which the TUN refuses (see `mark_must`).
        let kept = ruled.decision.must || ruled.decision.outbound == "block";
        runtime.set_mode(Mode::Direct);
        let direct = runtime.decide(&flow);
        let want = if kept {
            ruled.target.clone()
        } else if ruled.decision.mark.is_some() {
            Target::Refuse
        } else {
            Target::Direct
        };
        assert_eq!(direct.target, want, "{name}/{label} direct");
        runtime.set_mode(Mode::Global);
        runtime.set_global_target(global.clone()).unwrap();
        let global_verdict = runtime.decide(&flow);
        let want = match (&global, kept) {
            (_, true) => ruled.target.clone(),
            (GlobalTarget::Group(group), false) => Target::Group(group.clone()),
            (GlobalTarget::Node(id), false) => Target::Node(*id),
        };
        assert_eq!(global_verdict.target, want, "{name}/{label} global");
        assert_eq!(
            global_verdict.row.is_none(),
            !kept,
            "{name}/{label} global row"
        );
    }
}

fn corpus(geo: Option<&Path>) {
    let read = |file: &str| -> Value {
        serde_json::from_slice(&fs::read(Path::new(REFERENCE).join(file)).unwrap()).unwrap()
    };
    let (corpus, expected) = (read("corpus.json"), read("expected.json"));
    let empty = TempDir::new("honk-traffic-nogeo");
    let mut ran = 0;
    let (cases, wanted) = (
        corpus["cases"].as_array().unwrap(),
        expected["cases"].as_array().unwrap(),
    );
    assert_eq!(cases.len(), wanted.len(), "cases against the report");
    for (case, want) in cases.iter().zip(wanted) {
        assert_eq!(case["name"], want["name"]);
        let config =
            fs::read_to_string(Path::new(REFERENCE).join(case["config"].as_str().unwrap()))
                .unwrap();
        let needs_geo = config.contains("geosite:") || config.contains("geoip:");
        if needs_geo != geo.is_some() {
            continue;
        }
        compare(case, want, geo.unwrap_or(empty.path()));
        ran += 1;
    }
    assert!(ran > 0);
}

#[test]
fn decisions_match_the_stock_reference_in_every_mode() {
    corpus(None);
}

#[test]
#[ignore = "requires DOONA_GEO_DIR containing the pinned DATs"]
fn geo_decisions_match_the_stock_reference_in_every_mode() {
    let geo = PathBuf::from(std::env::var_os("DOONA_GEO_DIR").unwrap());
    corpus(Some(&geo));
}

const SKIPS: &str = "\
node {
    n1: 'socks5://10.10.10.1:1080'
}
group {
    left {
        filter: name('n1')
        policy: select
    }
}
routing {
    !pname(updater) -> direct
    dport(80) -> direct(mark: 1)
    dport(443) && !domain(suffix: a.test) -> left
    dport(80) -> block
    fallback: direct
}
";

#[test]
fn a_skipped_rule_leaves_the_engine_as_a_unit() {
    let (dir, doc) = doc(SKIPS);
    let runtime = runtime(&doc, dir.path());
    let verdict = runtime.decide(&flow(Network::Tcp, "203.0.113.9:443", Some("www.b.test")));
    assert_eq!(verdict.target, Target::Group("left".into()));
    assert_eq!(verdict.row.as_ref().and_then(|r| r.rule), Some(2));
    let verdict = runtime.decide(&flow(Network::Tcp, "203.0.113.9:80", None));
    assert_eq!(verdict.target, Target::Refuse);
    assert_eq!(verdict.row.as_ref().and_then(|r| r.rule), Some(3));
    let verdict = runtime.decide(&flow(Network::Tcp, "203.0.113.9:443", Some("www.a.test")));
    assert_eq!(
        (verdict.target, verdict.row.and_then(|r| r.rule)),
        (Target::Direct, None)
    );
}

#[test]
fn a_marked_fallback_refuses_in_rule_and_direct_mode() {
    let source = SKIPS.replace("fallback: direct", "fallback: direct(mark: 256)");
    let (dir, doc) = doc(&source);
    let runtime = runtime(&doc, dir.path());
    let unmatched = flow(Network::Udp, "203.0.113.9:443", Some("www.a.test"));
    for mode in [Mode::Rule, Mode::Direct] {
        runtime.set_mode(mode);
        let verdict = runtime.decide(&unmatched);
        assert_eq!(
            (verdict.target, verdict.decision.mark),
            (Target::Refuse, Some(256)),
            "{mode:?}"
        );
    }
    runtime.set_mode(Mode::Global);
    runtime
        .set_global_target(GlobalTarget::Group("left".into()))
        .unwrap();
    assert_eq!(
        runtime.decide(&unmatched).target,
        Target::Group("left".into())
    );
}

#[test]
fn a_matched_rule_charges_its_rows_meter() {
    let data = TempDir::new("honk-traffic-store");
    let mut store = HonkStore::open(data.path()).unwrap();
    let id = store.draft(None).unwrap();
    store.edit(id, Path::new("config.dae"), SKIPS).unwrap();
    store.apply(id, &Capabilities::linked()).unwrap();
    let rev = store.head().unwrap();
    let runtime = Runtime::load(&store, &Capabilities::linked(), data.path()).unwrap();

    let draft = store.draft(None).unwrap();
    let rows = store.rule_rows(draft, Path::new("config.dae")).unwrap();
    let meters = &store.revision_meters(rev).unwrap()[Path::new("config.dae")];
    let meter_of = |statement: &str| {
        let row = rows
            .iter()
            .find(|row| &SKIPS[row.bytes.clone()] == statement)
            .unwrap();
        Some(Arc::from(meters[&row.id].as_str()))
    };

    let verdict = runtime.decide(&flow(Network::Tcp, "203.0.113.9:443", Some("www.b.test")));
    let row = verdict.row.unwrap();
    assert_eq!(
        &SKIPS[row.bytes.clone()],
        "dport(443) && !domain(suffix: a.test) -> left"
    );
    assert_eq!(
        row.meter,
        meter_of("dport(443) && !domain(suffix: a.test) -> left")
    );
    let verdict = runtime.decide(&flow(Network::Tcp, "203.0.113.9:80", None));
    assert_eq!(verdict.row.unwrap().meter, meter_of("dport(80) -> block"));
    let verdict = runtime.decide(&flow(Network::Tcp, "203.0.113.9:443", Some("www.a.test")));
    assert_eq!(verdict.row.unwrap().meter, meter_of("fallback: direct"));
    assert_ne!(meter_of("dport(80) -> block"), meter_of("fallback: direct"));
}

/// The valid form of the corpus's `zero-groups`: nodes and no group, the fallback `direct`.
/// Rule sends every flow but `block` direct by the fallback's row; GLOBAL has no group to target
/// and blocks what is not `must`.
#[test]
fn zero_groups_with_a_direct_fallback_route_direct() {
    let (_dir, doc) = doc("\
node {
    lone: 'socks5://10.10.6.1:1080'
}
routing {
    dport(22) -> direct(must)
    dport(25) -> block
    fallback: direct
}
");
    assert!(doc.config().groups.is_empty());
    let runtime = runtime(&doc, Path::new("/nonexistent"));
    assert_eq!(runtime.fallback_group(), None);
    let flow = |port| Flow {
        network: Network::Tcp,
        source: "10.0.0.2:40000".parse().unwrap(),
        destination: ([203, 0, 113, 9], port).into(),
        domain: None,
        domain_verified: true,
        process_name: None,
        mac: None,
        dscp: None,
    };
    let fallback = runtime.decide(&flow(443));
    assert_eq!(fallback.target, Target::Direct);
    assert_eq!(fallback.row.unwrap().rule, None, "the fallback's row");
    assert_eq!(runtime.decide(&flow(25)).target, Target::Refuse);
    runtime.set_mode(Mode::Global);
    assert_eq!(runtime.decide(&flow(443)).target, Target::Refuse);
    assert_eq!(runtime.decide(&flow(22)).target, Target::Direct);
}
