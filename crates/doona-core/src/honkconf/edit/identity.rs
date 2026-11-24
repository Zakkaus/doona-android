use std::collections::{HashMap, HashSet};

use honk_config::Config;
use honk_config::dns::{
    DnsCond, DnsDomainMatcher, DnsRequestAction, DnsResponseAction, DnsTagMatcher,
};
use honk_config::parser::lexer::Source;
use honk_config::parser::source_edit::{RuleSourceIndex, RuleSourceLocation};
use honk_config::parser::{SourceSnapshot, parse_dae_config_with_detailed_diagnostics};
use honk_config::routing::RoutingCondition;
use uuid::Uuid;

use super::{List, RuleRow};

use crate::rules::listing::{RuleKey as Key, occurrence_id};

impl Key {
    fn strings(&mut self, values: &[String]) {
        self.field((values.len() as u64).to_be_bytes());
        for s in values {
            self.field(s);
        }
    }

    fn traffic(&mut self, c: &RoutingCondition) {
        let n = &c.not;
        self.field("predicate");
        for (name, positive, negative) in [
            ("domain", &c.domain, &n.domain),
            ("suffix", &c.domain_suffix, &n.domain_suffix),
            ("keyword", &c.domain_keyword, &n.domain_keyword),
            ("regex", &c.domain_regex, &n.domain_regex),
            ("geosite", &c.geosite, &n.geosite),
            ("geoip", &c.geo_ip, &n.geo_ip),
            ("dip", &c.ip, &n.ip),
            ("sip", &c.source_ip, &n.source_ip),
            ("sport", &c.source_port, &n.source_port),
            ("dport", &c.port, &n.port),
            ("l4proto", &c.protocol, &n.protocol),
            ("pname", &c.process_name, &n.process_name),
            ("mac", &c.mac, &n.mac),
            ("ipversion", &c.ip_version, &n.ip_version),
            ("dscp", &c.dscp, &n.dscp),
        ] {
            for (not, values) in [(false, positive), (true, negative)] {
                if !values.is_empty() {
                    self.field(name);
                    self.field([u8::from(not)]);
                    self.strings(values);
                }
            }
        }
    }

    fn dns(&mut self, conditions: &[DnsCond]) {
        for cond in conditions {
            let (name, negated) = match cond {
                DnsCond::Qname { not, .. } => ("qname", not),
                DnsCond::Qtype { not, .. } => ("qtype", not),
                DnsCond::Sip { not, .. } => ("sip", not),
                DnsCond::Subtag { not, .. } => ("subtag", not),
                DnsCond::Upstream { not, .. } => ("upstream", not),
                DnsCond::Ip { not, .. } => ("ip", not),
            };
            self.field(name);
            self.field([u8::from(*negated)]);
            self.dns_values(cond);
        }
    }

    fn dns_values(&mut self, cond: &DnsCond) {
        match cond {
            DnsCond::Qname { matchers, .. } => {
                self.field((matchers.len() as u64).to_be_bytes());
                for m in matchers {
                    let (kind, value) = match m {
                        DnsDomainMatcher::Full(v) => ("full", v),
                        DnsDomainMatcher::Suffix(v) => ("suffix", v),
                        DnsDomainMatcher::Keyword(v) => ("keyword", v),
                        DnsDomainMatcher::Regex(v) => ("regex", v),
                        DnsDomainMatcher::Geosite(v) => ("geosite", v),
                    };
                    self.field(kind);
                    self.field(value);
                }
            }
            DnsCond::Qtype { types, .. } => {
                self.field((types.len() as u64).to_be_bytes());
                for t in types {
                    self.field(t.to_be_bytes());
                }
            }
            DnsCond::Sip { cidrs, .. } => self.strings(cidrs),
            DnsCond::Upstream { names, .. } => self.strings(names),
            DnsCond::Ip { cidrs, geoip, .. } => {
                self.strings(cidrs);
                self.strings(geoip);
            }
            DnsCond::Subtag { tags, .. } => {
                self.field((tags.len() as u64).to_be_bytes());
                for tag in tags {
                    let (kind, value) = match tag {
                        DnsTagMatcher::Exact(v) => ("exact", v),
                        DnsTagMatcher::Keyword(v) => ("keyword", v),
                        DnsTagMatcher::Regex(v) => ("regex", v),
                    };
                    self.field(kind);
                    self.field(value);
                }
            }
        }
    }
}

// Honk folds traffic predicates into buckets. Ask the same reader to decode each conjunct
// separately to retain their authored order. Quoted && belongs to the value.
fn predicates(
    key: &mut Key,
    condition: &RoutingCondition,
    expr: &str,
    source: &SourceSnapshot,
) -> Option<()> {
    if !expr.contains("&&") {
        key.traffic(condition);
        return Some(());
    }
    let lexer = Source::new(expr, source.source.clone());
    let mut program = String::from("routing {\n");
    let mut conjunct = |part: &str| -> Option<()> {
        if !part.trim().is_empty() {
            program.truncate("routing {\n".len());
            program.push_str(part);
            program.push_str(" -> direct\n}");
            let parsed =
                parse_dae_config_with_detailed_diagnostics(&program, &mut Vec::new()).ok()?;
            key.traffic(&parsed.routing.rules.first()?.condition);
        }
        Some(())
    };
    let mut start = 0;
    for token in lexer.tokenize(&mut Vec::new()) {
        for pos in super::unquoted(expr, &token, "&&") {
            conjunct(&expr[start..pos])?;
            start = pos + 2;
        }
    }
    conjunct(&expr[start..])?;
    Some(())
}

fn request(a: &DnsRequestAction) -> &str {
    match a {
        DnsRequestAction::Reject => "reject",
        DnsRequestAction::AsIs => "asis",
        DnsRequestAction::Upstream(s) => s,
    }
}

fn response(a: &DnsResponseAction) -> &str {
    match a {
        DnsResponseAction::Reject => "reject",
        DnsResponseAction::Accept => "accept",
        DnsResponseAction::Upstream(s) => s,
    }
}

fn owned<T>(
    locations: Vec<RuleSourceLocation>,
    rules: &[T],
    owner: usize,
) -> impl Iterator<Item = (RuleSourceLocation, &T)> {
    locations
        .into_iter()
        .zip(rules)
        .filter(move |(s, _)| s.source_index == owner)
}

pub(super) fn rows(
    source: &SourceSnapshot,
    cfg: &Config,
    index: RuleSourceIndex,
    owner: usize,
    reserved: &HashSet<&str>,
) -> Option<Vec<RuleRow>> {
    let ns = Uuid::new_v5(&Uuid::NAMESPACE_URL, b"doona:honk:rules");
    let mut seen = HashMap::<Uuid, u32>::new();
    let mut rows = Vec::new();
    let mut key = Key::default();
    let mut row = |list: List, fallback: bool, loc: RuleSourceLocation, key: &mut Key| {
        key.field(source.path.as_os_str().as_encoded_bytes());
        key.field(list.name());
        key.field(if fallback { "fallback" } else { "rule" });
        let base = key.finish(&ns);
        let occurrence = seen.entry(base).or_default();
        let mut id = occurrence_id(base, occurrence);
        // A paused twin still owns its ID even though honk omits it from the parsed rules.
        while reserved.contains(id.as_str()) {
            id = occurrence_id(base, occurrence);
        }
        rows.push(RuleRow {
            id,
            list,
            fallback,
            paused: false,
            bytes: loc.bytes,
        });
    };
    let routing = &cfg.routing;
    for (loc, rule) in owned(index.rules, &routing.rules, owner) {
        predicates(&mut key, &rule.condition, &loc.expression, source)?;
        key.field(rule.outbound.as_str());
        key.field([rule.must.into()]);
        key.field(rule.mark.to_be_bytes());
        row(List::Traffic, false, loc, &mut key);
    }
    if let Some(loc) = index.fallback.filter(|s| s.source_index == owner) {
        key.field(&routing.default_outbound);
        key.field([routing.default_must.into()]);
        key.field(routing.default_mark.to_be_bytes());
        row(List::Traffic, true, loc, &mut key);
    }
    let dns = &cfg.dns.routing;
    for (loc, rule) in owned(index.dns_request.rules, &dns.request.rules, owner) {
        key.dns(&rule.conditions);
        key.field(request(&rule.action));
        row(List::DnsRequest, false, loc, &mut key);
    }
    if let Some(loc) = index
        .dns_request
        .fallback
        .filter(|s| s.source_index == owner)
    {
        key.field(request(&dns.request.fallback));
        row(List::DnsRequest, true, loc, &mut key);
    }
    for (loc, rule) in owned(index.dns_response.rules, &dns.response.rules, owner) {
        key.dns(&rule.conditions);
        key.field(response(&rule.action));
        row(List::DnsResponse, false, loc, &mut key);
    }
    if let Some(loc) = index
        .dns_response
        .fallback
        .filter(|s| s.source_index == owner)
    {
        key.field(response(&dns.response.fallback));
        row(List::DnsResponse, true, loc, &mut key);
    }
    Some(rows)
}
