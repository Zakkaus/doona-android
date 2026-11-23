use honk_config::Config;
use honk_config::dns::{DnsCond, DnsDomainMatcher, DnsRequestAction, decode_ip_or_cidr};
use honk_config::parser::source_edit::{RuleSourceIndex, RuleSourceLocation};
use honk_config::routing::RoutingCondition;

use super::{Disposition, Location, Reason, Unsupported};

#[derive(Default)]
struct Findings {
    reasons: Vec<Reason>,
    skip: bool,
}

impl Findings {
    fn add(&mut self, code: &'static str, category: Option<&str>, skip: bool) {
        self.skip |= skip;
        if !self
            .reasons
            .iter()
            .any(|r| r.code == code && r.category.as_deref() == category)
        {
            self.reasons.push(Reason {
                code,
                category: category.map(str::to_owned),
            });
        }
    }

    // The linked Android runtime has no honk geodata snapshot. A file merely existing
    // (or the old built-in CN table) is not proof the category/attribute was loaded.
    fn geoip(&mut self, code: &str) {
        if !code.eq_ignore_ascii_case("private") {
            self.add("missing_geoip", Some(code), true);
        }
    }

    fn networks(&mut self, cidrs: &[String], negated: bool, has_geoip: bool) {
        let mut v6 = false;
        let mut v4 = has_geoip;
        for network in cidrs.iter().filter_map(|s| decode_ip_or_cidr(s)) {
            if network.network.addr().is_ipv6() {
                v6 = true;
            } else {
                v4 = true;
                if negated && network.network.prefix_len() == 0 {
                    self.add("ipv6_ingress_only", None, true);
                }
            }
        }
        if v6 && !negated {
            self.add(
                if v4 {
                    "ipv4_scope"
                } else {
                    "ipv6_ingress_only"
                },
                None,
                !v4,
            );
        }
    }

    fn traffic(&mut self, c: &RoutingCondition, mark: u32) {
        for (code, active) in [
            (
                "process_name_unavailable",
                !c.process_name.is_empty() || !c.not.process_name.is_empty(),
            ),
            (
                "l2_mac_unavailable",
                !c.mac.is_empty() || !c.not.mac.is_empty(),
            ),
            (
                "dscp_unavailable",
                !c.dscp.is_empty() || !c.not.dscp.is_empty(),
            ),
            ("route_mark_unavailable", mark != 0),
        ] {
            if active {
                self.add(code, None, true);
            }
        }
        for code in c.geosite.iter().chain(&c.not.geosite) {
            self.add("missing_geosite", Some(code), true);
        }
        for code in c.geo_ip.iter().chain(&c.not.geo_ip) {
            self.geoip(code);
        }
        self.networks(&c.ip, false, !c.geo_ip.is_empty());
        self.networks(&c.source_ip, false, false);
        self.networks(&c.not.ip, true, false);
        self.networks(&c.not.source_ip, true, false);
        let v4 = |s: &String| s == "4" || s.eq_ignore_ascii_case("ipv4");
        let v6 = |s: &String| s == "6" || s.eq_ignore_ascii_case("ipv6");
        if c.not.ip_version.iter().any(v4)
            || (c.ip_version.iter().any(v6) && !c.ip_version.iter().any(v4))
        {
            self.add("ipv6_ingress_only", None, true);
        } else if c.ip_version.iter().any(v6) && !c.not.ip_version.iter().any(v6) {
            self.add("ipv4_scope", None, false);
        }
    }

    fn dns(&mut self, conditions: &[DnsCond]) {
        for condition in conditions {
            match condition {
                DnsCond::Qname { matchers, .. } => {
                    for matcher in matchers {
                        if let DnsDomainMatcher::Geosite(code) = matcher {
                            self.add("missing_geosite", Some(code), true);
                        }
                    }
                }
                DnsCond::Ip { geoip, .. } => {
                    for code in geoip {
                        self.geoip(code);
                    }
                }
                DnsCond::Sip { not, cidrs } => self.networks(cidrs, *not, false),
                _ => {}
            }
        }
    }

    fn emit(
        self,
        records: &mut Vec<Unsupported>,
        section: &'static str,
        ordinal: Option<usize>,
        source: Option<&RuleSourceLocation>,
    ) {
        if self.reasons.is_empty() {
            return;
        }
        records.push(Unsupported {
            section,
            name: ordinal.map_or_else(|| "fallback".into(), |i| format!("rule[{i}]").into()),
            reasons: self.reasons,
            disposition: if self.skip {
                Disposition::Skip
            } else {
                Disposition::Limited
            },
            locations: source
                .into_iter()
                .map(|s| Location {
                    source: s.source_index,
                    bytes: s.bytes.clone(),
                    line: s.line,
                    byte_column: s.column,
                })
                .collect(),
        });
    }
}

pub(super) fn append(cfg: &Config, sources: &RuleSourceIndex, records: &mut Vec<Unsupported>) {
    for (i, rule) in cfg.routing.rules.iter().enumerate() {
        let mut reasons = Findings::default();
        reasons.traffic(&rule.condition, rule.mark);
        reasons.emit(records, "routing", Some(i), sources.rules.get(i));
    }
    if cfg.routing.default_mark != 0 {
        let mut reasons = Findings::default();
        reasons.add("route_mark_unavailable", None, true);
        reasons.emit(records, "routing", None, sources.fallback.as_ref());
    }
    let dns = &cfg.dns.routing;
    for (i, rule) in dns.request.rules.iter().enumerate() {
        let mut reasons = Findings::default();
        reasons.dns(&rule.conditions);
        if rule.action == DnsRequestAction::AsIs {
            reasons.add("virtual_dns_destination", None, false);
        }
        reasons.emit(
            records,
            "dns.routing.request",
            Some(i),
            sources.dns_request.rules.get(i),
        );
    }
    if dns.request.fallback == DnsRequestAction::AsIs && sources.dns_request.fallback.is_some() {
        let mut reasons = Findings::default();
        reasons.add("virtual_dns_destination", None, false);
        reasons.emit(
            records,
            "dns.routing.request",
            None,
            sources.dns_request.fallback.as_ref(),
        );
    }
    for (i, rule) in dns.response.rules.iter().enumerate() {
        let mut reasons = Findings::default();
        reasons.dns(&rule.conditions);
        reasons.emit(
            records,
            "dns.routing.response",
            Some(i),
            sources.dns_response.rules.get(i),
        );
    }
}
