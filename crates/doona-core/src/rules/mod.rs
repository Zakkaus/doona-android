//! Rule mode's rules (`plans/v1/rule-mode.md`, slices R1 to R3) as the rules page shows them: the
//! model of a rule source, its limits, doona's template, and the rows as the contract lists them.
//! honk's engine routes every flow (`honkconf::runtime`); nothing here matches one.
//!
//! A source is groups ([`GroupDef`]) and rows of rules ([`RuleSpec`]). [`RuleSet::compile_source`]
//! checks the whole source ([`limits`]) and refuses it as `parse_failed` past a limit, when a
//! member, a group or a target is missing, when groups form a cycle or when a value does not
//! parse. A kind or a GeoIP code without data is skipped and counted, never guessed.

mod compile;
pub(crate) mod dat;
mod domain;
pub(crate) mod export;
mod geoip;
mod graph;
pub(crate) mod limits;
pub(crate) mod listing;
pub(crate) mod policy;
mod ports;
pub(crate) mod template;

use crate::dto::ErrorCode;
use crate::groups::GroupDef;
use limits::Origin;

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
    GeoSite,
    GeoIp,
    /// `DST-PORT`: `443`, `8000-9000`, or several joined by `/`, read as Mihomo reads them.
    DstPort,
    /// `NETWORK`: `tcp` or `udp`.
    Network,
    /// `MATCH`: every flow; takes no value.
    Match,
    /// A kind this core does not compile: its values are skipped and counted.
    Skipped {
        name: Box<str>,
        reason: Skip,
    },
}

impl Kind {
    /// The kind a Clash rule `name` names, in any case. Kinds that need geodata, an app or a
    /// feature not built yet are skipped with the reason the contract shows.
    #[cfg_attr(
        not(test),
        allow(dead_code, reason = "R4's Clash importer reads rules with it")
    )]
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
            "GEOSITE" => Kind::GeoSite,
            "DST-PORT" => Kind::DstPort,
            "NETWORK" => Kind::Network,
            "MATCH" => Kind::Match,
            "IP-ASN" | "SRC-GEOIP" | "SRC-IP-ASN" => skipped(Skip::GeodataMissing),
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
    #[cfg_attr(not(test), allow(dead_code, reason = "R4's importer reads REJECT"))]
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

/// A checked row: its kind, where it sends, and how many of its lines were skipped.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Row {
    pub kind: Kind,
    pub target: Target,
    pub skipped: u32,
}

/// A checked source: its rows, each with the lines skipped, and the values skipped by row and
/// column.
#[derive(Clone, Debug)]
pub(crate) struct RuleSet {
    rows: Vec<Row>,
    missing: std::collections::HashSet<(u32, u32)>,
}

impl RuleSet {
    /// Compiles an imported source of `groups` and `rules`, or refuses all of it. `is_node` tells
    /// whether a member that is not a group (`n-…`) is a node the catalog holds; one that is not
    /// refuses the source, as a missing group does.
    #[cfg(test)]
    pub(crate) fn compile(
        groups: &[GroupDef],
        is_node: &dyn Fn(&str) -> bool,
        rules: &[RuleSpec],
    ) -> Result<RuleSet, ErrorCode> {
        Self::compile_source(Origin::Imported, groups, is_node, rules, None)
    }

    pub(crate) fn compile_source(
        origin: Origin,
        groups: &[GroupDef],
        is_node: &dyn Fn(&str) -> bool,
        rules: &[RuleSpec],
        data: Option<&dat::Data>,
    ) -> Result<RuleSet, ErrorCode> {
        compile::compile(origin, groups, is_node, rules, data)
    }

    /// Compiles doona's template as [`RuleSet::compile`] does an imported source, but its groups
    /// follow the node cap instead of [`limits::MAX_MEMBERS`] ([`Origin::Template`]).
    #[cfg(test)]
    pub(crate) fn compile_template(
        groups: &[GroupDef],
        is_node: &dyn Fn(&str) -> bool,
        rules: &[RuleSpec],
    ) -> Result<RuleSet, ErrorCode> {
        compile::compile(Origin::Template, groups, is_node, rules, None)
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
pub(crate) mod tests;
#[cfg(test)]
mod tests_limits;
#[cfg(test)]
mod tests_mihomo;
#[cfg(test)]
mod tests_template;
