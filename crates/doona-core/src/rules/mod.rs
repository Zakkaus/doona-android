//! Rule mode's rules (`plans/v1/rule-mode.md`, slice R1): the model of a rule source, its limits,
//! and the compiled set a flow is matched against. Nothing routes by it yet; R2 feeds
//! `Route::of` from it.
//!
//! A source is groups ([`GroupDef`]) and rows of rules ([`RuleSpec`]). [`RuleSet::compile`]
//! checks the whole source before it builds anything ([`limits`]) and refuses it as
//! `parse_failed` past a limit, when a group or a target is missing, when groups form a cycle or
//! when a value does not parse. A kind or a GeoIP code without data is skipped and counted, never
//! guessed. The set keeps one index per matcher, each giving the first row it matches; the
//! smallest of them is the first rule that matches the flow. A flow is matched once, when it
//! opens, never per packet, in time that does not grow with the number of rules.

// R2 wires the module into routing; until then only its tests reach it.
#![cfg_attr(not(test), allow(dead_code))]

mod compile;
mod domain;
mod geoip;
mod graph;
pub(crate) mod limits;
mod lpm;
mod ports;

use crate::dto::ErrorCode;
use crate::groups::GroupDef;
use std::net::IpAddr;

/// No row: the indexes' "nothing matched".
const NONE: u32 = u32::MAX;

/// What a row matches, by the names of Clash rules.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum Kind {
    /// `DOMAIN`: the name itself.
    Domain,
    /// `DOMAIN-SUFFIX`: the name and every name under it.
    DomainSuffix,
    /// `DOMAIN-KEYWORD`: every name holding the text.
    DomainKeyword,
    /// `IP-CIDR` and `IP-CIDR6`: the destination address, of either family.
    IpCidr,
    /// `GEOIP`: `CN` or `private`; another code is skipped as `geodata_missing`.
    GeoIp,
    /// `DST-PORT`: `443`, `8000-9000`, or several joined by `/`.
    DstPort,
    /// `NETWORK`: `tcp` or `udp`.
    Network,
    /// `MATCH`: every flow; takes no value.
    Match,
    /// A kind this core does not compile: its values are skipped and counted.
    Skipped { name: Box<str>, reason: Skip },
}

impl Kind {
    /// The kind a Clash rule `name` names, in any case. Kinds that need geodata, an app or a
    /// feature not built yet are skipped with the reason the contract shows.
    pub(crate) fn clash(name: &str) -> Kind {
        let upper = name.to_ascii_uppercase();
        let skipped = |reason| Kind::Skipped {
            name: name.into(),
            reason,
        };
        match upper.as_str() {
            "DOMAIN" => Kind::Domain,
            "DOMAIN-SUFFIX" => Kind::DomainSuffix,
            "DOMAIN-KEYWORD" => Kind::DomainKeyword,
            "IP-CIDR" | "IP-CIDR6" => Kind::IpCidr,
            "GEOIP" => Kind::GeoIp,
            "DST-PORT" => Kind::DstPort,
            "NETWORK" => Kind::Network,
            "MATCH" => Kind::Match,
            "GEOSITE" | "IP-ASN" | "SRC-GEOIP" | "SRC-IP-ASN" => skipped(Skip::GeodataMissing),
            // Rules never see apps (ARCH D24).
            "PROCESS-NAME"
            | "PROCESS-PATH"
            | "PROCESS-NAME-REGEX"
            | "PROCESS-PATH-REGEX"
            | "PROCESS-NAME-WILDCARD"
            | "PROCESS-PATH-WILDCARD"
            | "UID" => skipped(Skip::AppRule),
            _ => skipped(Skip::UnsupportedKind),
        }
    }
}

/// Why a rule was not compiled, as the contract names it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Skip {
    UnsupportedKind,
    GeodataMissing,
    AppRule,
}

impl Skip {
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Skip::UnsupportedKind => "unsupported_kind",
            Skip::GeodataMissing => "geodata_missing",
            Skip::AppRule => "app_rule",
        }
    }
}

/// Where a matching flow goes.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub(crate) enum Target {
    Direct,
    Reject,
    /// A group of the source, by id (`g-` and its name).
    Group(String),
}

/// One row of a source: values of one kind sent to one target. An importer folds consecutive
/// lines of one kind and target into one row.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct RuleSpec {
    pub kind: Kind,
    pub values: Vec<String>,
    pub target: Target,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Network {
    Tcp,
    Udp,
}

/// What a flow is matched on.
#[derive(Clone, Copy, Debug)]
pub(crate) struct Flow<'a> {
    /// The name the flow's address answered, when the core knows it.
    pub domain: Option<&'a str>,
    pub ip: IpAddr,
    pub port: u16,
    pub network: Network,
}

/// A compiled row: what it matched and where it sends, and how many of its lines were skipped.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Row {
    pub kind: Kind,
    pub target: Target,
    pub skipped: u32,
}

/// A compiled source.
#[derive(Clone, Debug)]
pub(crate) struct RuleSet {
    rows: Vec<Row>,
    domains: domain::Index,
    ips: lpm::Trie,
    /// The first row of each GeoIP code compiled, by row.
    geo: Vec<(u32, geoip::Code)>,
    ports: ports::Index,
    /// The first `NETWORK` row of TCP and of UDP.
    networks: [u32; 2],
    /// The first `MATCH` row.
    any: u32,
}

impl RuleSet {
    /// Compiles a source of `groups` and `rules`, or refuses all of it.
    pub(crate) fn compile(groups: &[GroupDef], rules: &[RuleSpec]) -> Result<RuleSet, ErrorCode> {
        compile::compile(groups, rules)
    }

    /// The first row that matches `flow`, `None` when no row does: the caller's main group then.
    /// Rows that need a name miss a flow without one; the others still apply.
    pub(crate) fn matched(&self, flow: &Flow<'_>) -> Option<usize> {
        let mut best = self.any.min(self.networks[flow.network as usize]);
        best = best.min(self.ports.first(flow.port));
        let ip = flow.ip.to_canonical();
        best = best.min(self.ips.first(ip));
        for &(row, code) in &self.geo {
            if row < best && code.contains(ip) {
                best = row;
            }
        }
        if let Some(name) = flow.domain {
            best = self.domains.first(name, best);
        }
        (best != NONE).then_some(best as usize)
    }

    pub(crate) fn rows(&self) -> &[Row] {
        &self.rows
    }

    /// The lines skipped in all rows.
    pub(crate) fn skipped(&self) -> usize {
        self.rows.iter().map(|r| r.skipped as usize).sum()
    }
}

#[cfg(test)]
mod bench;
#[cfg(test)]
pub(crate) mod tests;
#[cfg(test)]
mod tests_limits;
