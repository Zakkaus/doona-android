//! Slice 10, lanes D and D2: the starting configurations the APK ships, `templates/*.dae`,
//! through the core's list.
mod common;

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::path::Path;

use common::TempDir;
use doonacore::honkconf::assets::Release;
use doonacore::honkconf::edit::{Edit, List};
use doonacore::honkconf::templates::TEMPLATES;
use doonacore::honkconf::{
    Capabilities, Disposition, Geo, GeoCatalog, HonkConfig, HonkStore, Ingress, Label, Support,
    load,
};
use honk_config::dns::{DnsCond, DnsDomainMatcher, DnsRequestAction};

const ENTRY: &str = "config.dae";

/// Every geodata label the templates name, ad block included.
const GEOSITE: [&str; 19] = [
    "anthropic",
    "apple",
    "bahamut",
    "bilibili",
    "category-ads-all",
    "category-games",
    "cn",
    "disney",
    "gfw",
    "hbo",
    "microsoft",
    "netease",
    "netflix",
    "openai",
    "primevideo",
    "spotify",
    "telegram",
    "twitch",
    "youtube",
];
const GEOIP: [&str; 2] = ["cn", "telegram"];

/// What each template declares, in the order of [`TEMPLATES`].
struct Shape {
    id: &'static str,
    groups: &'static [&'static str],
    routing_rules: usize,
    /// The `dip(geoip:…)` rules, whose IPv6 networks the IPv4 TUN never sees.
    geoip_rules: &'static [&'static str],
}

const SHAPES: [Shape; 5] = [
    Shape {
        id: "cn-direct",
        groups: &[
            "proxy", "auto", "media", "ai", "hk", "tw", "jp", "sg", "us", "kr",
        ],
        routing_rules: 4,
        geoip_rules: &["rule[3]"],
    },
    Shape {
        id: "gfw",
        groups: &["proxy", "auto"],
        routing_rules: 3,
        geoip_rules: &["rule[1]"],
    },
    Shape {
        id: "services",
        groups: &["proxy", "auto", "telegram", "media", "apple"],
        routing_rules: 8,
        geoip_rules: &["rule[3]", "rule[7]"],
    },
    Shape {
        id: "regions",
        groups: &[
            "proxy", "auto", "hk", "tw", "jp", "sg", "us", "kr", "telegram", "ai", "youtube",
            "netflix", "bahamut", "media",
        ],
        routing_rules: 12,
        geoip_rules: &["rule[2]", "rule[11]"],
    },
    Shape {
        id: "homebound",
        groups: &["cn"],
        routing_rules: 2,
        geoip_rules: &["rule[1]"],
    },
];

/// Each template with its shape, checked to be the same list.
fn templates() -> impl Iterator<Item = (&'static str, &'static Shape)> {
    let ids: Vec<_> = TEMPLATES.iter().map(|t| t.id).collect();
    let shapes: Vec<_> = SHAPES.iter().map(|s| s.id).collect();
    assert_eq!(ids, shapes);
    TEMPLATES.iter().map(|t| t.source).zip(SHAPES.iter())
}

/// A template with its paused statements restored, as the 封鎖廣告 switch turns them on.
fn active(text: &str) -> String {
    text.split_inclusive('\n')
        .map(|line| {
            line.strip_prefix("# paused ")
                .and_then(|rest| rest.split_once(": "))
                .map_or(line, |(_, statement)| statement)
        })
        .collect()
}

fn load_text(text: &str) -> HonkConfig {
    let dir = TempDir::new("honk-template");
    let path = dir.path().join(ENTRY);
    fs::write(&path, text).unwrap();
    load(path).unwrap()
}

/// ARCH D25 as `TunRoutes.IPV4` builds it: public IPv4 plus the TUN's own /30.
fn d25() -> Ingress {
    const EXCLUDED: [(u32, u8); 7] = [
        (0x0000_0000, 8),
        (0x0a00_0000, 8),
        (0x7f00_0000, 8),
        (0xa9fe_0000, 16),
        (0xac10_0000, 12),
        (0xc0a8_0000, 16),
        (0xe000_0000, 3),
    ];
    fn inside(outer: (u32, u8), inner: (u32, u8)) -> bool {
        let mask = u32::MAX.checked_shl(32 - u32::from(outer.1)).unwrap_or(0);
        inner.1 >= outer.1 && inner.0 & mask == outer.0
    }
    fn complement(prefix: (u32, u8), out: &mut Vec<(u32, u8)>) {
        if EXCLUDED.iter().any(|&e| inside(e, prefix)) {
            return;
        }
        if !EXCLUDED.iter().any(|&e| inside(prefix, e)) {
            out.push(prefix);
            return;
        }
        let bit = 1u32 << (31 - u32::from(prefix.1));
        complement((prefix.0, prefix.1 + 1), out);
        complement((prefix.0 | bit, prefix.1 + 1), out);
    }
    let mut routes = Vec::new();
    complement((0, 0), &mut routes);
    routes.push((u32::from(Ipv4Addr::new(172, 19, 0, 0)), 30));
    Ingress::ipv4(routes.into_iter().map(|(a, l)| (Ipv4Addr::from(a), l)))
}

/// Codes with at least one entry, and the networks of each GeoIP code. Codes are lowercase.
struct Catalog {
    sites: BTreeSet<String>,
    ips: BTreeMap<String, Vec<(IpAddr, u8)>>,
}

impl GeoCatalog for Catalog {
    fn geoip(&self, code: &str) -> Label {
        match self.ips.contains_key(code) {
            true => Label::Present,
            false => Label::MissingCategory,
        }
    }
    fn geoip_nets(&self, code: &str) -> Option<Vec<(IpAddr, u8)>> {
        self.ips.get(code).cloned()
    }
    fn geosite(&self, label: &str) -> Label {
        assert!(
            !label.contains('@'),
            "the templates name no attribute: {label}"
        );
        match self.sites.contains(label) {
            true => Label::Present,
            false => Label::MissingCategory,
        }
    }
}

fn report(text: &str, catalog: &Catalog) -> Support {
    let ingress = d25();
    load_text(text).support(&Capabilities {
        ingress: Some(&ingress),
        geo: Geo::Installed {
            generation: 1,
            catalog,
        },
    })
}

#[test]
fn templates_load_complete_with_no_diagnostics() {
    for (source, shape) in templates() {
        for text in [source, &active(source)] {
            let doc = load_text(text);
            assert!(doc.complete(), "{}", shape.id);
            assert_eq!(doc.diagnostics(), [], "{}", shape.id);
            let names: Vec<_> = doc
                .config()
                .groups
                .iter()
                .map(|g| g.name.as_str())
                .collect();
            assert_eq!(names, shape.groups, "{}", shape.id);
            let summary = doc.summary();
            assert_eq!((summary.nodes, summary.subscriptions), (0, 0));
            assert_eq!(
                (summary.routing_rules, summary.dns_upstreams),
                (shape.routing_rules, 2),
                "{}",
                shape.id
            );
        }
        assert_eq!(load_text(source).summary().dns_request_rules, 1);
        assert_eq!(load_text(&active(source)).summary().dns_request_rules, 2);
    }
}

/// The only records: each `dip(geoip:…)` rule also lists IPv6 networks, which the IPv4 TUN
/// never sees.
fn assert_runs_on_android(report: &Support, shape: &Shape) {
    let rows: Vec<_> = report
        .records
        .iter()
        .map(|r| {
            let reasons: Vec<_> = r.reasons.iter().map(|r| r.code).collect();
            (r.section, r.name.as_ref(), reasons, r.disposition)
        })
        .collect();
    let limited: Vec<_> = shape
        .geoip_rules
        .iter()
        .map(|&rule| ("routing", rule, vec!["ipv4_scope"], Disposition::Limited))
        .collect();
    assert_eq!(rows, limited, "{}", shape.id);
    assert!(report.runnable(), "{}", shape.id);
}

/// GeoIP networks as a real generation lists them: public IPv4 and IPv6.
fn catalog_of(sites: &[&str]) -> Catalog {
    let nets = vec![
        (IpAddr::V4(Ipv4Addr::new(1, 0, 1, 0)), 24),
        (
            IpAddr::V6(Ipv6Addr::new(0x2400, 0xda00, 0, 0, 0, 0, 0, 0)),
            32,
        ),
    ];
    Catalog {
        sites: sites.iter().map(|s| (*s).to_owned()).collect(),
        ips: GEOIP
            .iter()
            .map(|c| ((*c).to_owned(), nets.clone()))
            .collect(),
    }
}

#[test]
fn templates_need_nothing_android_lacks() {
    let catalog = catalog_of(&GEOSITE);
    for (source, shape) in templates() {
        for text in [source, &active(source)] {
            assert_runs_on_android(&report(text, &catalog), shape);
        }
    }
}

/// Without the gfw list the gfw template does not run, and the report names the list.
#[test]
fn gfw_reports_its_geosite_need() {
    let sites: Vec<_> = GEOSITE.into_iter().filter(|s| *s != "gfw").collect();
    let gfw = TEMPLATES.iter().find(|t| t.id == "gfw").unwrap().source;
    let report = report(gfw, &catalog_of(&sites));
    let blocked: Vec<_> = report
        .records
        .iter()
        .filter(|r| r.disposition == Disposition::Blocked)
        .map(|r| {
            let labels: Vec<_> = r.reasons.iter().map(|r| r.category.as_deref()).collect();
            (r.section, r.name.as_ref(), labels)
        })
        .collect();
    assert_eq!(
        blocked,
        [
            ("routing", "rule[2]", vec![Some("gfw")]),
            ("dns.routing.request", "rule[0]", vec![Some("gfw")]),
        ]
    );
    assert!(!report.runnable());
}

#[test]
fn ad_block_ships_paused_first_in_the_entry_dns_request() {
    for (source, shape) in templates() {
        let dir = TempDir::new("honk-template-ads");
        let mut store = HonkStore::open(dir.path()).unwrap();
        let draft = store.draft(None).unwrap();
        let entry = Path::new(ENTRY);
        store.edit(draft, entry, source).unwrap();
        let rows = store.rule_rows(draft, entry).unwrap();
        let first = rows.iter().find(|r| r.list == List::DnsRequest).unwrap();
        assert!(first.paused && !first.fallback, "{}: {first:?}", shape.id);
        let id = first.id.clone();

        store.form_edit(draft, entry, Edit::Resume(&id)).unwrap();
        assert_eq!(*store.text(draft, entry).unwrap(), active(source));
        let doc = store.check(draft).unwrap();
        let request = &doc.config().dns.routing.request.rules;
        let ads = vec![DnsDomainMatcher::Geosite("category-ads-all".into())];
        match (&request[0].conditions[..], &request[0].action) {
            ([DnsCond::Qname { matchers, .. }], DnsRequestAction::Reject) => {
                assert_eq!(*matchers, ads, "{}", shape.id);
            }
            other => panic!("{}: {other:?}", shape.id),
        }
        // The rule it must beat, the template's own qname rule, follows it.
        assert!(
            matches!(request[1].action, DnsRequestAction::Upstream(_)),
            "{}",
            shape.id
        );

        // The shipped bytes are exactly what the app's own pause writes.
        store.form_edit(draft, entry, Edit::Pause(&id)).unwrap();
        assert_eq!(*store.text(draft, entry).unwrap(), *source, "{}", shape.id);
    }
}

fn varint(b: &[u8], i: &mut usize) -> u64 {
    let mut value = 0;
    for shift in (0..64).step_by(7) {
        let byte = b[*i];
        *i += 1;
        value |= u64::from(byte & 0x7f) << shift;
        if byte < 0x80 {
            break;
        }
    }
    value
}

/// Protobuf fields in order: a varint's own bytes, or a length-delimited payload.
fn fields(b: &[u8]) -> Vec<(u64, &[u8])> {
    let (mut i, mut out) = (0, Vec::new());
    while i < b.len() {
        let key = varint(b, &mut i);
        let mut start = i;
        match key & 7 {
            0 => drop(varint(b, &mut i)),
            2 => {
                let len = varint(b, &mut i) as usize;
                (start, i) = (i, i + len);
            }
            wire => panic!("wire type {wire}"),
        }
        out.push((key >> 3, &b[start..i]));
    }
    out
}

/// A v2ray GeoSiteList or GeoIPList: each entry's lowercase code and its repeated field 2.
fn entries(bytes: &[u8]) -> Vec<(String, Vec<&[u8]>)> {
    fields(bytes)
        .into_iter()
        .map(|(_, entry)| {
            let inner = fields(entry);
            let code = inner.iter().find(|(f, _)| *f == 1).unwrap().1;
            let items = inner.iter().filter(|(f, _)| *f == 2).map(|(_, v)| *v);
            let code = String::from_utf8(code.to_vec()).unwrap();
            (code.to_ascii_lowercase(), items.collect())
        })
        .collect()
}

/// `CIDR {ip: bytes = 1, prefix: uint32 = 2}`.
fn cidr(bytes: &[u8]) -> (IpAddr, u8) {
    let (mut ip, mut prefix) = (None, 0);
    for (field, value) in fields(bytes) {
        match field {
            1 if value.len() == 4 => ip = Some(IpAddr::from(<[u8; 4]>::try_from(value).unwrap())),
            1 => ip = Some(IpAddr::from(<[u8; 16]>::try_from(value).unwrap())),
            _ => prefix = varint(value, &mut 0) as u8,
        }
    }
    (ip.unwrap(), prefix)
}

#[test]
#[ignore = "requires DOONA_GEO_DIR containing the pinned DATs"]
fn templates_run_on_the_pinned_pair() {
    let source = std::env::var_os("DOONA_GEO_DIR").unwrap();
    let stage = TempDir::new("honk-template-geo");
    let dir = stage.path().join("bundle");
    Release::from_manifest(include_bytes!("../tools/geo-pins.json"))
        .unwrap()
        .stage_bundle(Path::new(&source), &dir)
        .unwrap();
    let sites = fs::read(dir.join("geosite.dat")).unwrap();
    let ips = fs::read(dir.join("geoip.dat")).unwrap();
    let catalog = Catalog {
        sites: entries(&sites)
            .into_iter()
            .filter(|(_, domains)| !domains.is_empty())
            .map(|(code, _)| code)
            .collect(),
        ips: entries(&ips)
            .into_iter()
            .filter(|(code, _)| GEOIP.contains(&code.as_str()))
            .map(|(code, nets)| (code, nets.into_iter().map(cidr).collect()))
            .collect(),
    };
    for label in GEOSITE {
        assert!(catalog.sites.contains(label), "{label}");
    }
    for (source, shape) in templates() {
        for text in [source, &active(source)] {
            assert_runs_on_android(&report(text, &catalog), shape);
        }
    }
}
