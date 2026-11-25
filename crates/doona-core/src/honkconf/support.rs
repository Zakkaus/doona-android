//! The §9 capability projection: parsed rules against the platform's ingress and asset
//! facts. It reads the parsed `Config` and honk's rule spans; it never edits either and
//! never scans source text a second time.

mod capture;

use std::net::IpAddr;

use honk_config::Config;
use honk_config::dns::{DnsCond, DnsDomainMatcher, DnsRequestAction, decode_ip_or_cidr};
use honk_config::parser::source_edit::{RuleSourceIndex, RuleSourceLocation};
use honk_config::routing::RoutingCondition;

use super::{Disposition, Location, Reason, Unsupported};
pub use capture::Ingress;
use capture::{PRIVATE_V4, Scope};

/// Whether one geodata label resolves in the installed data. The catalog owns honk's
/// matching rules; this projection only asks. Labels arrive trimmed and lowercased, as honk
/// folds them. honk splits a GEOSITE label at its first `@` (so `cat@a@b` asks for the
/// attribute `a@b`), compares attributes by key presence, and reads `!attr` as a literal key.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Label {
    Present,
    /// The file, or the category in it, is absent.
    MissingCategory,
    /// The category exists without the requested attribute.
    MissingAttribute,
}

/// Installed geodata, answered per label.
pub trait GeoCatalog {
    /// A GeoIP country or list code, never `private` (honk's own ranges).
    fn geoip(&self, code: &str) -> Label;
    /// The networks a present GeoIP code expands to, or `None` when the catalog does not
    /// know them; the rule's capture is then not assessed.
    fn geoip_nets(&self, code: &str) -> Option<Vec<(IpAddr, u8)>>;
    /// A GEOSITE label as written, including any `@attribute`.
    fn geosite(&self, label: &str) -> Label;
}

/// The asset generation this report was computed against.
#[derive(Clone, Copy)]
pub enum Geo<'a> {
    /// Unknown: geo rules count as not assessed, never as supported.
    NotAssessed,
    /// This runtime has no geodata: every label except `geoip:private` is missing.
    Unavailable,
    /// One published generation; traffic and DNS are judged against the same one.
    Installed {
        generation: u64,
        catalog: &'a dyn GeoCatalog,
    },
}

/// Platform facts the report depends on. `None` ingress means the capture is not assessed.
#[derive(Clone, Copy)]
pub struct Capabilities<'a> {
    pub ingress: Option<&'a Ingress>,
    pub geo: Geo<'a>,
}

impl Capabilities<'_> {
    /// What the linked core knows on its own: IPv4-only capture with unknown routes, no geodata.
    pub fn linked() -> Self {
        Self {
            ingress: None,
            geo: Geo::Unavailable,
        }
    }
}

/// One activation report: records plus counts kept apart by disposition.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Support {
    /// Settings, nodes and includes first, then traffic, DNS request and DNS response rules.
    pub records: Vec<Unsupported>,
    /// The asset generation the geo dispositions refer to.
    pub asset_generation: Option<u64>,
    /// The captured routes the capture dispositions refer to; `None` when not assessed.
    pub ingress: Option<Ingress>,
    /// Declarations whose disposition depends on an input that was not supplied.
    pub unassessed: usize,
}

impl Support {
    fn count(&self, disposition: Disposition) -> usize {
        self.records
            .iter()
            .filter(|r| r.disposition == disposition)
            .count()
    }
    /// Rules and settings omitted as a unit.
    pub fn skipped(&self) -> usize {
        self.count(Disposition::Skip)
    }
    /// Rules that still match part of their scope.
    pub fn constrained(&self) -> usize {
        self.count(Disposition::Limited)
    }
    /// Rules naming a label the installed geodata lacks; the configuration cannot run.
    pub fn blocked(&self) -> usize {
        self.count(Disposition::Blocked)
    }
    /// Terminal fallbacks the runtime cannot honour.
    pub fn refused(&self) -> usize {
        self.count(Disposition::Refuse)
    }
    /// False while an include was refused; its declarations are unknown.
    pub fn complete(&self) -> bool {
        self.count(Disposition::Incomplete) == 0
    }
    /// True only when every count above is known: complete and nothing left unassessed.
    pub fn assessed(&self) -> bool {
        self.complete() && self.unassessed == 0
    }
    /// May this configuration start: every input assessed and no rule blocked. Skipped rules
    /// and refused fallbacks do not stop it; Apply checks this against the caller's facts.
    pub fn runnable(&self) -> bool {
        self.assessed() && self.blocked() == 0
    }
}

pub(super) fn project(
    cfg: &Config,
    index: &RuleSourceIndex,
    declared: &[Unsupported],
    caps: &Capabilities<'_>,
) -> Support {
    let mut out = Projection {
        caps,
        records: declared.to_vec(),
        unassessed: 0,
    };
    out.traffic(cfg, index);
    out.dns(cfg, index);
    Support {
        records: out.records,
        asset_generation: match caps.geo {
            Geo::Installed { generation, .. } => Some(generation),
            _ => None,
        },
        ingress: caps.ingress.cloned(),
        unassessed: out.unassessed,
    }
}

struct Projection<'c, 'a> {
    caps: &'c Capabilities<'a>,
    records: Vec<Unsupported>,
    unassessed: usize,
}

impl<'c> Projection<'c, '_> {
    fn findings(&self) -> Findings<'c> {
        Findings {
            caps: self.caps,
            reasons: Vec::new(),
            skip: false,
            block: false,
            unknown: false,
        }
    }

    fn traffic(&mut self, cfg: &Config, index: &RuleSourceIndex) {
        for (i, rule) in cfg.routing.rules.iter().enumerate() {
            let mut f = self.findings();
            f.traffic(&rule.condition, rule.mark);
            self.emit(f, "routing", Some(i), index.rules.get(i));
        }
        if cfg.routing.default_mark != 0 {
            let mut f = self.findings();
            f.add("route_mark_unavailable", None, true);
            self.emit(f, "routing", None, index.fallback.as_ref());
        }
    }

    fn dns(&mut self, cfg: &Config, index: &RuleSourceIndex) {
        let dns = &cfg.dns.routing;
        for (i, rule) in dns.request.rules.iter().enumerate() {
            let mut f = self.findings();
            f.dns(&rule.conditions);
            if rule.action == DnsRequestAction::AsIs {
                f.add("virtual_dns_destination", None, false);
            }
            let source = index.dns_request.rules.get(i);
            self.emit(f, "dns.routing.request", Some(i), source);
        }
        let fallback = index.dns_request.fallback.as_ref();
        if dns.request.fallback == DnsRequestAction::AsIs && fallback.is_some() {
            let mut f = self.findings();
            f.add("virtual_dns_destination", None, false);
            self.emit(f, "dns.routing.request", None, fallback);
        }
        for (i, rule) in dns.response.rules.iter().enumerate() {
            let mut f = self.findings();
            f.dns(&rule.conditions);
            let source = index.dns_response.rules.get(i);
            self.emit(f, "dns.routing.response", Some(i), source);
        }
    }

    /// One record per rule or fallback, whatever the number of reasons.
    fn emit(
        &mut self,
        f: Findings<'_>,
        section: &'static str,
        ordinal: Option<usize>,
        source: Option<&RuleSourceLocation>,
    ) {
        // Only Blocked is final: a skipped rule naming an unassessed label may still block the run.
        if f.unknown && !f.block {
            self.unassessed += 1;
        }
        if f.reasons.is_empty() {
            return;
        }
        let disposition = match (f.block, f.skip, ordinal) {
            (true, ..) => Disposition::Blocked,
            (false, true, None) => Disposition::Refuse,
            (false, true, Some(_)) => Disposition::Skip,
            (false, false, _) => Disposition::Limited,
        };
        self.records.push(Unsupported {
            section,
            name: ordinal.map_or_else(|| "fallback".into(), |i| format!("rule[{i}]").into()),
            reasons: f.reasons,
            disposition,
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

/// Reasons for one rule. `skip` drops the rule as a unit, `block` stops the configuration,
/// `unknown` marks a disposition that depends on an input nobody supplied.
struct Findings<'c> {
    caps: &'c Capabilities<'c>,
    reasons: Vec<Reason>,
    skip: bool,
    block: bool,
    unknown: bool,
}

impl Findings<'_> {
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

    fn missing(&mut self, code: &'static str, label: &str) {
        self.block = true;
        self.add(code, Some(label), false);
    }

    // honk's `geoip:private` is its own range list, not a lookup in the installed data.
    fn geoip(&mut self, code: &str) {
        let code = fold(code);
        if code == "private" {
            return;
        }
        match self.caps.geo {
            Geo::NotAssessed => self.unknown = true,
            Geo::Unavailable => self.missing("missing_geoip", &code),
            Geo::Installed { catalog, .. } => {
                if catalog.geoip(&code) != Label::Present {
                    self.missing("missing_geoip", &code);
                }
            }
        }
    }

    /// The networks a folded GeoIP code adds to a destination scope: none for a missing code,
    /// which honk expands to nothing, and `None` when nobody knows them.
    fn geoip_nets(&self, code: &str) -> Option<Vec<(IpAddr, u8)>> {
        match self.caps.geo {
            Geo::NotAssessed => None,
            Geo::Unavailable => Some(Vec::new()),
            Geo::Installed { catalog, .. } => match catalog.geoip(code) {
                Label::Present => catalog.geoip_nets(code),
                _ => Some(Vec::new()),
            },
        }
    }

    fn geosite(&mut self, label: &str) {
        let label = &fold(label);
        let found = match self.caps.geo {
            Geo::NotAssessed => {
                self.unknown = true;
                return;
            }
            Geo::Unavailable => Label::MissingCategory,
            Geo::Installed { catalog, .. } => catalog.geosite(label),
        };
        match found {
            Label::Present => {}
            Label::MissingCategory => self.missing("missing_geosite", label),
            Label::MissingAttribute => self.missing("missing_geosite_attribute", label),
        }
    }

    /// Positive destination alternatives: the rule survives while one can reach the TUN; a
    /// prefix only partly routed into it constrains the rule. A GeoIP code is judged by its
    /// networks exactly as `dip(<cidr>)` is.
    fn destinations(&mut self, cidrs: &[String], geo_ip: &[String]) {
        let ingress = self.caps.ingress;
        let mut scope = Scope::default();
        for code in geo_ip.iter().map(|c| fold(c)) {
            if code == "private" {
                for &(octets, len) in &PRIVATE_V4 {
                    scope.net(ingress, IpAddr::from(octets), len);
                }
                continue;
            }
            match self.geoip_nets(&code) {
                Some(nets) => nets
                    .into_iter()
                    .for_each(|(addr, len)| scope.net(ingress, addr, len)),
                None => scope.unknown = true,
            }
        }
        for network in cidrs.iter().filter_map(|s| decode_ip_or_cidr(s)) {
            scope.net(
                ingress,
                network.network.addr(),
                network.network.prefix_len(),
            );
        }
        // Unknown routes leave a constraint open even when another alternative reaches the TUN.
        let skip = !scope.reachable && !scope.unknown;
        self.unknown |= scope.unknown;
        if scope.v6 {
            let code = if skip {
                "ipv6_ingress_only"
            } else {
                "ipv4_scope"
            };
            self.add(code, None, skip);
        }
        if scope.lan {
            self.add("lan_outside_capture", None, skip);
        }
        if scope.other {
            self.add("outside_capture", None, skip);
        }
    }

    /// Family scope only: the capture admits IPv4 and nothing else.
    fn families(&mut self, cidrs: &[String], negated: bool) {
        let mut v6 = false;
        let mut v4 = false;
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
            let code = if v4 {
                "ipv4_scope"
            } else {
                "ipv6_ingress_only"
            };
            self.add(code, None, !v4);
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
        for label in c.geosite.iter().chain(&c.not.geosite) {
            self.geosite(label);
        }
        for code in c.geo_ip.iter().chain(&c.not.geo_ip) {
            self.geoip(code);
        }
        self.destinations(&c.ip, &c.geo_ip);
        // `sip` is judged by family only. honk matches it against the source address the
        // embedding passes (honk answer Q4): with the TUN's own address a LAN `sip` never
        // matches, with the app's original source it does. Slice 8 decides which one doona passes.
        self.families(&c.source_ip, false);
        self.families(&c.not.ip, true);
        self.families(&c.not.source_ip, true);
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
                        if let DnsDomainMatcher::Geosite(label) = matcher {
                            self.geosite(label);
                        }
                    }
                }
                DnsCond::Ip { geoip, .. } => {
                    for code in geoip {
                        self.geoip(code);
                    }
                }
                // Family only, for the same reason as traffic `sip`; DNS `sip` is request-only.
                DnsCond::Sip { not, cidrs } => self.families(cidrs, *not),
                _ => {}
            }
        }
    }
}

/// A geodata code as honk compares it: trimmed and lowercased (honk-core `routing/geo.rs`).
fn fold(code: &str) -> String {
    code.trim().to_lowercase()
}
