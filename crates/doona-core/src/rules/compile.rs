//! From a source to a [`RuleSet`]: the limits and the group graph first, then every target, then
//! each row's values, each read as its kind reads it. Any failure refuses the whole source.

use super::dat::Data;
use super::limits::{self, Origin};
use super::{Kind, Row, RuleSet, RuleSpec, Target, domain, geoip, graph, ports};
use crate::dto::{ErrorCode, Network};
use crate::groups::GroupDef;
use std::net::IpAddr;

pub(super) fn compile(
    origin: Origin,
    groups: &[GroupDef],
    is_node: &dyn Fn(&str) -> bool,
    rules: &[RuleSpec],
    data: Option<&Data>,
) -> Result<RuleSet, ErrorCode> {
    limits::check(origin, groups, rules)?;
    let ids = graph::check(groups, is_node)?;
    let missing = |t: &Target| matches!(t, Target::Group(id) if !ids.contains_key(id.as_str()));
    if rules.iter().any(|r| missing(&r.target)) {
        return Err(ErrorCode::ParseFailed);
    }
    let mut set = RuleSet {
        rows: Vec::with_capacity(rules.len()),
        missing: Default::default(),
    };
    for (row, rule) in rules.iter().enumerate() {
        let skipped = set.read(row as u32, rule, data)?;
        set.rows.push(Row {
            kind: rule.kind.clone(),
            target: rule.target.clone(),
            skipped,
        });
    }
    Ok(set)
}

impl RuleSet {
    /// Reads the values of `rule`, row `row`; returns how many lines it skipped.
    fn read(&mut self, row: u32, rule: &RuleSpec, data: Option<&Data>) -> Result<u32, ErrorCode> {
        let values = &rule.values;
        // A kind not compiled is skipped whole: a line a value, or one for a row without values,
        // as the limits count it. `limits::check` keeps the count under `MAX_LINES`.
        if let Kind::Skipped { .. } = rule.kind {
            return Ok(values.len().max(1) as u32);
        }
        // A `MATCH` takes no value, any other kind at least one.
        if (rule.kind == Kind::Match) != values.is_empty() {
            return Err(ErrorCode::ParseFailed);
        }
        let mut skipped = 0;
        for (column, value) in values.iter().enumerate() {
            let read = match rule.kind {
                Kind::GeoIp | Kind::GeoSite => geo(&rule.kind, value, data),
                _ => check(&rule.kind, value).map(|()| true)?,
            };
            if !read {
                self.missing.insert((row, column as u32));
                skipped += 1;
            }
        }
        Ok(skipped)
    }
}

/// Reads one value of a `kind` row, or refuses it.
fn check(kind: &Kind, value: &str) -> Result<(), ErrorCode> {
    match kind {
        Kind::Domain | Kind::DomainSuffix => domain::rule_name(value).map(drop),
        Kind::DomainKeyword => domain::keyword(value),
        Kind::IpCidr => cidr(value).map(drop),
        Kind::DstPort => ports::parse(value),
        Kind::Network => network(value).map(drop),
        // `skipped` takes these before any value.
        Kind::GeoIp | Kind::GeoSite | Kind::Match | Kind::Skipped { .. } => Ok(()),
    }
}

/// Whether a GeoIP or GeoSite value has data: the loaded generation's, or without one (and for
/// `private` and `LAN` always) the codes known without geodata.
fn geo(kind: &Kind, value: &str, data: Option<&Data>) -> bool {
    let builtin = geoip::Code::parse(value.strip_prefix('!').unwrap_or(value));
    let use_builtin = *kind == Kind::GeoIp
        && (data.is_none() || matches!(builtin, Some(geoip::Code::Private | geoip::Code::Lan)));
    if use_builtin {
        builtin.is_some()
    } else {
        data.is_some_and(|d| d.holds(kind, value))
    }
}

/// `addr/len`, either family, with `len` no wider than the family; bits past `len` are ignored,
/// as Clash masks them.
fn cidr(value: &str) -> Result<(IpAddr, u8), ErrorCode> {
    let refused = ErrorCode::ParseFailed;
    let (addr, len) = value.split_once('/').ok_or(refused)?;
    let addr: IpAddr = addr.parse().map_err(|_| refused)?;
    let len: u8 = len.parse().map_err(|_| refused)?;
    let width = if addr.is_ipv4() { 32 } else { 128 };
    if len > width {
        return Err(refused);
    }
    Ok((addr, len))
}

fn network(value: &str) -> Result<Network, ErrorCode> {
    if value.eq_ignore_ascii_case("tcp") {
        Ok(Network::Tcp)
    } else if value.eq_ignore_ascii_case("udp") {
        Ok(Network::Udp)
    } else {
        Err(ErrorCode::ParseFailed)
    }
}
