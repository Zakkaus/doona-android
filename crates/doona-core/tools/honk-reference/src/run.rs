//! One case through stock honk: the loader, `Router::route_action` and `GroupManager`.

use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result};
use honk_config::config::{BLOCK_NODE_ID, DIRECT_NODE_ID};
use honk_config::diagnostic::DetailedDiagnostic;
use honk_config::parser::parse_dae_config_file_with_detailed_diagnostics;
use honk_core::group::{GroupManager, SelectionNetwork, SelectorNetworks};
use honk_core::outbound::{AliveDialerSet, IpVersion, ProbeDomain};
use honk_core::routing::{ConnectionInfo, Router};
use serde::Serialize;

use crate::corpus::{Case, Probe, Protocol};

#[derive(Serialize)]
pub struct CaseReport {
    pub name: String,
    pub diagnostics: Vec<Diagnostic>,
    /// The load failure, when the entry file does not load.
    pub error: Option<String>,
    pub probes: Vec<ProbeReport>,
}

#[derive(Serialize)]
pub struct Diagnostic {
    pub code: &'static str,
    pub severity: String,
    pub line: Option<usize>,
    pub setting: String,
    pub value: String,
    pub message: &'static str,
}

#[derive(Serialize)]
pub struct ProbeReport {
    pub name: String,
    /// Winning rule index in the compiled rule list; `None` is the fallback.
    pub rule: Option<u32>,
    pub rule_payload: Option<String>,
    pub outbound: String,
    pub must: bool,
    pub mark: Option<u32>,
    pub leaf: Leaf,
}

#[derive(Serialize)]
pub struct Leaf {
    /// `node`, `direct`, `block` or `none` (a group without a selectable member).
    pub kind: &'static str,
    pub name: Option<String>,
    /// Group and node names from the routed outbound down to the leaf, read after the selection.
    /// It stops at a group with no formed member (loadbalance, a `final:` edge), so `kind` and
    /// `name` are authoritative for the leaf.
    pub chain: Vec<String>,
}

fn diagnostic(d: &DetailedDiagnostic) -> Diagnostic {
    Diagnostic {
        code: d.code,
        severity: format!("{:?}", d.severity).to_lowercase(),
        line: d.line,
        setting: d.setting.to_string(),
        value: d.value.to_string(),
        message: d.message,
    }
}

pub fn run(case: &Case, corpus_dir: &Path) -> Result<CaseReport> {
    let path = corpus_dir.join(&case.config);
    let mut diagnostics = Vec::new();
    let loaded = parse_dae_config_file_with_detailed_diagnostics(&path, &mut diagnostics);
    let diagnostics = diagnostics.iter().map(diagnostic).collect();
    let config = match loaded {
        Ok(config) => config,
        Err(error) => {
            return Ok(CaseReport {
                name: case.name.clone(),
                diagnostics,
                error: Some(error.to_string()),
                probes: Vec::new(),
            });
        }
    };
    let router =
        Router::from_config(&config.routing).with_context(|| format!("{}: router", case.name))?;
    let alive = Arc::new(AliveDialerSet::new());
    let groups = GroupManager::with_alive_set(&config.groups, &config.nodes, Some(alive.clone()));
    for node in &config.nodes {
        alive.register_node(node.id, node.name.clone(), None);
    }
    for health in &case.health {
        let node = groups
            .node_by_name(&health.node)
            .with_context(|| format!("{}: health names unknown node {}", case.name, health.node))?;
        let domain = domain(health.network);
        if !health.alive {
            // A node's UDP is down only when both UDP domains are; either one keeps it selectable.
            let dead: &[ProbeDomain] = match health.network {
                Protocol::Tcp => &[ProbeDomain::Tcp],
                Protocol::Udp => &[ProbeDomain::DataUdp, ProbeDomain::DnsUdp],
            };
            for &domain in dead {
                // Forced, because a fresh registration is in its grace period and ignores plain deaths.
                alive.report_unavailable_forced(node.id, domain, IpVersion::V4);
            }
        } else if let Some(ms) = health.latency_ms {
            alive.record_probe_latency(node.id, domain, IpVersion::V4, Duration::from_millis(ms));
        }
    }
    for choice in &case.select {
        groups
            .set_selector_choice(&choice.group, &choice.member, SelectorNetworks::Both)
            .map_err(|error| {
                anyhow::anyhow!("{}: select {}: {error:?}", case.name, choice.group)
            })?;
    }
    let probes = case
        .probes
        .iter()
        .map(|probe| probe_report(probe, &router, &groups))
        .collect();
    Ok(CaseReport {
        name: case.name.clone(),
        diagnostics,
        error: None,
        probes,
    })
}

fn probe_report(probe: &Probe, router: &Router, groups: &GroupManager) -> ProbeReport {
    let conn = ConnectionInfo {
        domain: probe.domain.clone(),
        dst_ip: probe.dst_ip,
        dst_port: probe.dst_port,
        src_ip: "10.0.0.2".parse().expect("fixed source"),
        src_port: 50000,
        protocol: probe.protocol.as_str(),
        process_name: None,
        mac: None,
        dscp: None,
    };
    let (action, matched) = router.route_action(&conn);
    ProbeReport {
        name: probe.name.clone(),
        rule: matched.as_ref().map(|hit| hit.rule_id),
        rule_payload: matched.as_ref().map(|hit| hit.rule_payload.to_owned()),
        outbound: action.outbound.clone(),
        must: action.must,
        mark: action.mark.map(|mark| mark.get()),
        leaf: leaf(&action.outbound, domain(probe.protocol), groups),
    }
}

fn leaf(outbound: &str, domain: ProbeDomain, groups: &GroupManager) -> Leaf {
    if groups.get_group_policy(outbound).is_some() {
        // The chain reads caches that the selection fills, so it comes second.
        let selected = groups.select_node_for_domain(outbound, domain, IpVersion::V4);
        let chain = groups
            .selection_chain_for_network(outbound, SelectionNetwork::from_probe_domain(domain));
        return match selected {
            Some(node) => Leaf {
                kind: kind(node.id),
                name: Some(node.name.clone()),
                chain,
            },
            None => Leaf {
                kind: "none",
                name: None,
                chain,
            },
        };
    }
    match (outbound, groups.node_by_name(outbound)) {
        ("direct", _) => Leaf {
            kind: "direct",
            name: None,
            chain: vec![outbound.to_owned()],
        },
        ("block", _) => Leaf {
            kind: "block",
            name: None,
            chain: vec![outbound.to_owned()],
        },
        (_, Some(node)) => Leaf {
            kind: kind(node.id),
            name: Some(node.name.clone()),
            chain: vec![outbound.to_owned()],
        },
        (_, None) => Leaf {
            kind: "none",
            name: None,
            chain: vec![outbound.to_owned()],
        },
    }
}

fn domain(network: Protocol) -> ProbeDomain {
    match network {
        Protocol::Tcp => ProbeDomain::Tcp,
        Protocol::Udp => ProbeDomain::DataUdp,
    }
}

fn kind(id: uuid::Uuid) -> &'static str {
    match id {
        DIRECT_NODE_ID => "direct",
        BLOCK_NODE_ID => "block",
        _ => "node",
    }
}
