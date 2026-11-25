//! Slice 10, lane D: the seed configuration the APK ships, `templates/cn-direct.dae`.
mod common;

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::path::Path;

use common::TempDir;
use doonacore::honkconf::assets::Release;
use doonacore::honkconf::edit::{Edit, List};
use doonacore::honkconf::{
    Capabilities, Disposition, Geo, GeoCatalog, HonkConfig, HonkStore, Ingress, Label, Support,
    load,
};
use honk_config::dns::{DnsCond, DnsDomainMatcher, DnsRequestAction};

const TEMPLATE: &str = include_str!("../templates/cn-direct.dae");
const ENTRY: &str = "config.dae";

/// Every geodata label the seed names, ad block included.
const GEOSITE: [&str; 11] = [
    "anthropic",
    "category-ads-all",
    "cn",
    "disney",
    "hbo",
    "netflix",
    "openai",
    "primevideo",
    "spotify",
    "twitch",
    "youtube",
];
const GEOIP: [&str; 1] = ["cn"];

/// The seed with its paused statements restored, as the 封鎖廣告 switch turns them on.
fn active() -> String {
    TEMPLATE
        .split_inclusive('\n')
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
        assert!(!label.contains('@'), "the seed names no attribute: {label}");
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
fn seed_loads_complete_with_no_diagnostics() {
    for text in [TEMPLATE, &active()] {
        let doc = load_text(text);
        assert!(doc.complete());
        assert_eq!(doc.diagnostics(), []);
        let names: Vec<_> = doc
            .config()
            .groups
            .iter()
            .map(|g| g.name.as_str())
            .collect();
        assert_eq!(
            names,
            [
                "proxy", "auto", "media", "ai", "hk", "tw", "jp", "sg", "us", "kr"
            ]
        );
        let summary = doc.summary();
        assert_eq!((summary.nodes, summary.subscriptions), (0, 0));
        assert_eq!((summary.routing_rules, summary.dns_upstreams), (4, 2));
    }
    assert_eq!(load_text(TEMPLATE).summary().dns_request_rules, 1);
    assert_eq!(load_text(&active()).summary().dns_request_rules, 2);
}

/// The only record: `dip(geoip:cn)` also lists IPv6 networks, which the IPv4 TUN never sees.
fn assert_runs_on_android(report: &Support) {
    let rows: Vec<_> = report
        .records
        .iter()
        .map(|r| {
            let reasons: Vec<_> = r.reasons.iter().map(|r| r.code).collect();
            (r.section, r.name.as_ref(), reasons, r.disposition)
        })
        .collect();
    assert_eq!(
        rows,
        [(
            "routing",
            "rule[3]",
            vec!["ipv4_scope"],
            Disposition::Limited
        )]
    );
    assert!(report.runnable());
}

#[test]
fn seed_needs_nothing_android_lacks() {
    // GeoIP networks as a real generation lists them: public IPv4 and IPv6.
    let nets = vec![
        (IpAddr::V4(Ipv4Addr::new(1, 0, 1, 0)), 24),
        (
            IpAddr::V6(Ipv6Addr::new(0x2400, 0xda00, 0, 0, 0, 0, 0, 0)),
            32,
        ),
    ];
    let catalog = Catalog {
        sites: GEOSITE.iter().map(|s| (*s).to_owned()).collect(),
        ips: GEOIP
            .iter()
            .map(|c| ((*c).to_owned(), nets.clone()))
            .collect(),
    };
    for text in [TEMPLATE, &active()] {
        assert_runs_on_android(&report(text, &catalog));
    }
}

#[test]
fn ad_block_ships_paused_first_in_the_entry_dns_request() {
    let dir = TempDir::new("honk-template-ads");
    let mut store = HonkStore::open(dir.path()).unwrap();
    let draft = store.draft(None).unwrap();
    let entry = Path::new(ENTRY);
    store.edit(draft, entry, TEMPLATE).unwrap();
    let rows = store.rule_rows(draft, entry).unwrap();
    let first = rows.iter().find(|r| r.list == List::DnsRequest).unwrap();
    assert!(first.paused && !first.fallback, "{first:?}");
    let id = first.id.clone();

    store.form_edit(draft, entry, Edit::Resume(&id)).unwrap();
    assert_eq!(*store.text(draft, entry).unwrap(), active());
    let doc = store.check(draft).unwrap();
    let request = &doc.config().dns.routing.request.rules;
    let qname = |i: usize| match &request[i].conditions[..] {
        [DnsCond::Qname { matchers, .. }] => matchers.clone(),
        other => panic!("{other:?}"),
    };
    let site = |code: &str| vec![DnsDomainMatcher::Geosite(code.into())];
    assert_eq!(
        (qname(0), &request[0].action),
        (site("category-ads-all"), &DnsRequestAction::Reject)
    );
    assert_eq!(
        (qname(1), &request[1].action),
        (site("cn"), &DnsRequestAction::Upstream("alidns".into()))
    );

    // The shipped bytes are exactly what the app's own pause writes.
    store.form_edit(draft, entry, Edit::Pause(&id)).unwrap();
    assert_eq!(*store.text(draft, entry).unwrap(), *TEMPLATE);
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
fn seed_runs_on_the_pinned_pair() {
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
    for text in [TEMPLATE, &active()] {
        assert_runs_on_android(&report(text, &catalog));
    }
}
