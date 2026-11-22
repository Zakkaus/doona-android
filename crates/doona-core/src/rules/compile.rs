//! From a source to a [`RuleSet`]: the limits and the group graph first, then every target, then
//! each row's values into the index of its kind. Any failure refuses the whole source.

use super::{Kind, NONE, Network, Row, RuleSet, RuleSpec, Target, domain, geoip, graph, limits};
use super::{lpm, ports};
use crate::dto::ErrorCode;
use crate::groups::GroupDef;
use std::net::IpAddr;

pub(super) fn compile(groups: &[GroupDef], rules: &[RuleSpec]) -> Result<RuleSet, ErrorCode> {
    limits::check(groups, rules)?;
    let ids = graph::check(groups)?;
    let missing = |t: &Target| matches!(t, Target::Group(id) if !ids.contains_key(id.as_str()));
    if rules.iter().any(|r| missing(&r.target)) {
        return Err(ErrorCode::ParseFailed);
    }
    let mut set = RuleSet {
        rows: Vec::with_capacity(rules.len()),
        domains: domain::Index::default(),
        ips: lpm::Trie::default(),
        geo: Vec::new(),
        ports: ports::Index::default(),
        networks: [NONE; 2],
        any: NONE,
    };
    for (row, rule) in rules.iter().enumerate() {
        let skipped = set.add(row as u32, rule)?;
        set.rows.push(Row {
            kind: rule.kind.clone(),
            target: rule.target.clone(),
            skipped,
        });
    }
    set.domains.finish()?;
    set.ips.shrink();
    set.ports.finish();
    Ok(set)
}

impl RuleSet {
    /// Adds the values of `rule`, row `row`; returns how many lines it skipped.
    fn add(&mut self, row: u32, rule: &RuleSpec) -> Result<u32, ErrorCode> {
        let values = &rule.values;
        // A `MATCH` takes no value, any other kind at least one.
        if (rule.kind == Kind::Match) != values.is_empty() {
            return Err(ErrorCode::ParseFailed);
        }
        let mut skipped = 0;
        for value in values {
            skipped += u32::from(!self.add_value(&rule.kind, value, row)?);
        }
        match rule.kind {
            Kind::Match => self.any = self.any.min(row),
            Kind::Skipped { .. } if values.is_empty() => skipped = 1,
            _ => {}
        }
        Ok(skipped)
    }

    /// Adds one value of a `kind` row; `false` when it was skipped.
    fn add_value(&mut self, kind: &Kind, value: &str, row: u32) -> Result<bool, ErrorCode> {
        let added = match kind {
            Kind::Domain => self.domains.add_full(value, row),
            Kind::DomainSuffix => self.domains.add_suffix(value, row),
            Kind::DomainKeyword => self.domains.add_keyword(value, row),
            Kind::IpCidr => cidr(value).map(|(addr, len)| self.ips.insert(addr, len, row)),
            Kind::GeoIp => return Ok(self.add_geoip(value, row)),
            Kind::DstPort => self.add_ports(value, row),
            Kind::Network => network(value).map(|n| {
                let slot = &mut self.networks[n as usize];
                *slot = (*slot).min(row);
            }),
            Kind::Match => Ok(()),
            Kind::Skipped { .. } => return Ok(false),
        };
        added.map(|()| true)
    }

    /// `false` for a code without data.
    fn add_geoip(&mut self, value: &str, row: u32) -> bool {
        let Some(code) = geoip::Code::parse(value) else {
            return false;
        };
        // Rows come in order: the first of a code is the one that can match.
        if !self.geo.iter().any(|g| g.1 == code) {
            self.geo.push((row, code));
        }
        true
    }

    fn add_ports(&mut self, value: &str, row: u32) -> Result<(), ErrorCode> {
        for part in value.split('/') {
            let (first, last) = port_range(part)?;
            self.ports.add(first, last, row);
        }
        Ok(())
    }
}

/// `addr/len`, either family; bits past `len` are ignored, as Clash masks them. A prefix within
/// `::ffff:0:0/96` becomes the IPv4 prefix it maps, since flows are matched with mapped
/// addresses as IPv4.
fn cidr(value: &str) -> Result<(IpAddr, u8), ErrorCode> {
    let refused = ErrorCode::ParseFailed;
    let (addr, len) = value.split_once('/').ok_or(refused)?;
    let addr: IpAddr = addr.parse().map_err(|_| refused)?;
    let len: u8 = len.parse().map_err(|_| refused)?;
    let width = if addr.is_ipv4() { 32 } else { 128 };
    if len > width {
        return Err(refused);
    }
    if let IpAddr::V6(v6) = addr
        && let Some(v4) = v6.to_ipv4_mapped()
        && len >= 96
    {
        return Ok((IpAddr::V4(v4), len - 96));
    }
    Ok((addr, len))
}

/// `443` or `8000-9000`.
fn port_range(value: &str) -> Result<(u16, u16), ErrorCode> {
    let refused = ErrorCode::ParseFailed;
    let port = |s: &str| s.trim().parse::<u16>().map_err(|_| refused);
    let (first, last) = match value.split_once('-') {
        Some((a, b)) => (port(a)?, port(b)?),
        None => (port(value)?, port(value)?),
    };
    if first > last {
        return Err(refused);
    }
    Ok((first, last))
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
