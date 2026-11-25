//! Slice 5: the §9 capability projection against ingress and asset facts.
mod common;

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::net::Ipv4Addr;
use std::path::PathBuf;

use common::TempDir;
use doonacore::honkconf::{
    Capabilities, Disposition, Geo, GeoCatalog, HonkConfig, HonkStore, Ingress, Label, Support,
    load,
};

fn write(dir: &TempDir, name: &str, text: &str) -> PathBuf {
    let path = dir.path().join(name);
    fs::write(&path, text).unwrap();
    path
}

fn doc(text: &str) -> HonkConfig {
    let dir = TempDir::new("honk-support");
    load(write(&dir, "main.dae", text)).unwrap_or_else(|e| panic!("{text}: {e}"))
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

/// A fixture generation: GeoIP codes and GEOSITE categories with their attributes.
#[derive(Default)]
struct Catalog {
    ip: BTreeSet<&'static str>,
    site: BTreeMap<&'static str, BTreeSet<&'static str>>,
}

/// Follows honk's rules: lowercase codes, one split at the first `@`, attributes by key
/// without case. Fixture keys are lowercase.
impl GeoCatalog for Catalog {
    fn geoip(&self, code: &str) -> Label {
        if self.ip.contains(code.to_ascii_lowercase().as_str()) {
            Label::Present
        } else {
            Label::MissingCategory
        }
    }
    fn geosite(&self, label: &str) -> Label {
        let (code, attr) = label
            .split_once('@')
            .map_or((label, None), |(c, a)| (c, Some(a)));
        let Some(attrs) = self.site.get(code.to_ascii_lowercase().as_str()) else {
            return Label::MissingCategory;
        };
        match attr {
            Some(attr) if !attrs.iter().any(|a| a.eq_ignore_ascii_case(attr)) => {
                Label::MissingAttribute
            }
            _ => Label::Present,
        }
    }
}

fn caps<'a>(ingress: &'a Ingress, catalog: &'a Catalog, generation: u64) -> Capabilities<'a> {
    Capabilities {
        ingress: Some(ingress),
        geo: Geo::Installed {
            generation,
            catalog,
        },
    }
}

fn rows(report: &Support) -> Vec<(&str, &str, Vec<&str>, Disposition)> {
    report
        .records
        .iter()
        .map(|r| {
            (
                r.section,
                r.name.as_ref(),
                r.reasons.iter().map(|r| r.code).collect(),
                r.disposition,
            )
        })
        .collect()
}

#[test]
fn lan_destination_alone_is_outside_capture() {
    let (ingress, geo) = (d25(), Catalog::default());
    let doc = doc("routing {\n dip(192.168.1.0/24) -> direct\n fallback: direct\n}");
    let report = doc.support(&caps(&ingress, &geo, 1));
    assert_eq!(
        rows(&report),
        [(
            "routing",
            "rule[0]",
            vec!["lan_outside_capture"],
            Disposition::Skip
        )]
    );
    assert_eq!((report.skipped(), report.constrained()), (1, 0));
    assert_eq!(report.records[0].locations[0].line, 2);
    assert!(report.assessed());
}

#[test]
fn public_alternative_stays_usable() {
    let (ingress, geo) = (d25(), Catalog::default());
    let doc = doc("routing {\n dip(192.168.1.0/24, 8.8.8.0/24) -> direct\n fallback: direct\n}");
    let report = doc.support(&caps(&ingress, &geo, 1));
    assert_eq!(
        rows(&report),
        [(
            "routing",
            "rule[0]",
            vec!["lan_outside_capture"],
            Disposition::Limited
        )]
    );
    assert_eq!((report.skipped(), report.constrained()), (0, 1));
}

#[test]
fn tun_prefix_is_captured() {
    let (ingress, geo) = (d25(), Catalog::default());
    let doc = doc(
        "routing {\n dip(172.19.0.0/30) -> direct\n dip(172.19.0.2) -> direct\n dip(172.16.0.0/12) -> direct\n !dip(192.168.0.0/16) -> direct\n fallback: direct\n}",
    );
    let report = doc.support(&caps(&ingress, &geo, 1));
    // The /12 still reaches the TUN's own /30: constrained, not skipped.
    assert_eq!(
        rows(&report),
        [(
            "routing",
            "rule[2]",
            vec!["lan_outside_capture"],
            Disposition::Limited
        )]
    );
}

#[test]
fn private_list_is_partly_captured() {
    // honk's private list holds 100.64.0.0/10 and 198.18.0.0/15, which the TUN routes, and
    // the D25 LAN ranges, which it does not.
    let (ingress, geo) = (d25(), Catalog::default());
    let doc = doc(
        "routing {\n dip(geoip:private) -> direct\n !dip(geoip:private) -> direct\n fallback: direct\n}",
    );
    let report = doc.support(&caps(&ingress, &geo, 1));
    assert_eq!(
        rows(&report),
        [(
            "routing",
            "rule[0]",
            vec!["lan_outside_capture"],
            Disposition::Limited
        )]
    );
}

#[test]
fn unknown_capture_is_not_assessed() {
    let lan = doc(
        "routing {\n dip(192.168.1.0/24) -> direct\n dport(53) -> direct\n fallback: direct\n}",
    );
    let report = lan.support(&Capabilities {
        ingress: None,
        geo: Geo::NotAssessed,
    });
    assert!(report.records.is_empty());
    assert_eq!(report.unassessed, 1);
    assert!(!report.assessed());
    // A reachable GeoIP alternative does not settle whether the LAN prefix constrains the rule.
    let geo = Catalog {
        ip: BTreeSet::from(["cn"]),
        ..Catalog::default()
    };
    let mixed = doc("routing {\n dip(geoip:cn, 192.168.1.0/24) -> direct\n fallback: direct\n}");
    let report = mixed.support(&Capabilities {
        ingress: None,
        geo: Geo::Installed {
            generation: 1,
            catalog: &geo,
        },
    });
    assert_eq!((report.records.len(), report.unassessed), (0, 1));
}

#[test]
fn nfqueue_aliases_share_one_record() {
    let (ingress, geo) = (d25(), Catalog::default());
    let doc =
        doc("global {\n nfqueue_enable: true\n}\nexperimental { udp_nfqueue { enabled: true } }");
    let report = doc.support(&caps(&ingress, &geo, 1));
    assert_eq!(
        rows(&report),
        [(
            "global",
            "nfqueue_enable",
            vec!["linux_nfqueue"],
            Disposition::Skip
        )]
    );
    let lines: Vec<_> = report.records[0].locations.iter().map(|l| l.line).collect();
    assert_eq!(lines, [2, 4]);
}

#[test]
fn overridden_scalar_counts_only_the_effective_value() {
    let (ingress, geo) = (d25(), Catalog::default());
    for (entry, child, expected) in [("6060", "0", 0), ("0", "6060", 1)] {
        let dir = TempDir::new("honk-override");
        let path = write(
            &dir,
            "main.dae",
            &format!("include {{ child.dae }}\nglobal {{ pprof_port: {entry} }}"),
        );
        write(
            &dir,
            "child.dae",
            &format!("global {{\n pprof_port: {child}\n}}"),
        );
        let report = load(path).unwrap().support(&caps(&ingress, &geo, 1));
        assert_eq!(report.records.len(), expected, "{entry} then {child}");
        if let [r] = report.records.as_slice() {
            assert_eq!((r.name.as_ref(), r.locations.len()), ("pprof_port", 1));
            assert_eq!((r.locations[0].source, r.locations[0].line), (1, 2));
        }
    }
}

#[test]
fn mixed_family_keeps_reachable_alternatives() {
    let (ingress, geo) = (d25(), Catalog::default());
    let doc = doc(
        "routing {\n dip(2001:db8::/32, 8.8.8.8) -> direct\n dip(2001:db8::/32) -> direct\n dip(2001:db8::/32, 192.168.1.1) -> direct\n fallback: direct\n}",
    );
    let report = doc.support(&caps(&ingress, &geo, 1));
    assert_eq!(
        rows(&report),
        [
            (
                "routing",
                "rule[0]",
                vec!["ipv4_scope"],
                Disposition::Limited
            ),
            (
                "routing",
                "rule[1]",
                vec!["ipv6_ingress_only"],
                Disposition::Skip
            ),
            (
                "routing",
                "rule[2]",
                vec!["ipv6_ingress_only", "lan_outside_capture"],
                Disposition::Skip
            ),
        ]
    );
}

#[test]
fn missing_attribute_blocks_and_names_the_label() {
    let ingress = d25();
    let geo = Catalog {
        site: BTreeMap::from([("cn", BTreeSet::new())]),
        ..Catalog::default()
    };
    let doc = doc(
        "routing {\n domain(geosite:cn) -> direct\n domain(geosite:cn@ads) -> block\n fallback: direct\n}",
    );
    let report = doc.support(&caps(&ingress, &geo, 7));
    assert_eq!(
        rows(&report),
        [(
            "routing",
            "rule[1]",
            vec!["missing_geosite_attribute"],
            Disposition::Blocked
        )]
    );
    assert_eq!(
        report.records[0].reasons[0].category.as_deref(),
        Some("cn@ads")
    );
    assert_eq!((report.blocked(), report.skipped()), (1, 0));
    assert_eq!(report.asset_generation, Some(7));
}

#[test]
fn marked_fallback_refuses_instead_of_skipping() {
    let (ingress, geo) = (d25(), Catalog::default());
    let doc = doc("routing {\n dport(443) -> direct(mark: 1)\n fallback: direct(mark: 256)\n}");
    let report = doc.support(&caps(&ingress, &geo, 1));
    assert_eq!(
        rows(&report),
        [
            (
                "routing",
                "rule[0]",
                vec!["route_mark_unavailable"],
                Disposition::Skip
            ),
            (
                "routing",
                "fallback",
                vec!["route_mark_unavailable"],
                Disposition::Refuse
            ),
        ]
    );
    assert_eq!((report.skipped(), report.refused()), (1, 1));
}

#[test]
fn unknown_geo_is_not_assessed() {
    let doc = doc("routing {\n domain(geosite:cn) -> direct\n fallback: direct\n}");
    let ingress = d25();
    let report = doc.support(&Capabilities {
        ingress: Some(&ingress),
        geo: Geo::NotAssessed,
    });
    assert!(report.records.is_empty());
    assert_eq!(report.unassessed, 1);
    let linked = doc.support(&Capabilities::linked());
    assert_eq!(
        rows(&linked),
        [(
            "routing",
            "rule[0]",
            vec!["missing_geosite"],
            Disposition::Blocked
        )]
    );
}

const RESTORE: &str = "routing {\n domain(geosite:cn) -> direct\n dip(geoip:cn) -> direct\n fallback: direct\n}\ndns {\n upstream { alidns: 'udp://223.5.5.5:53' }\n routing {\n request {\n qname(geosite:cn) -> alidns\n fallback: alidns\n }\n }\n}";

#[test]
fn restored_asset_clears_the_block_at_the_same_revision() {
    let (data, src) = (
        TempDir::new("honk-restore-data"),
        TempDir::new("honk-restore-src"),
    );
    let mut store = HonkStore::open(data.path()).unwrap();
    let draft = store.import(&write(&src, "config.dae", RESTORE)).unwrap();
    let rev = store.apply(draft).unwrap();
    let ingress = d25();
    let stale = Catalog::default();
    let restored = Catalog {
        ip: BTreeSet::from(["cn"]),
        site: BTreeMap::from([("cn", BTreeSet::new())]),
    };
    let before = store
        .read_revision(rev)
        .unwrap()
        .support(&caps(&ingress, &stale, 1));
    assert_eq!(
        before
            .records
            .iter()
            .map(|r| (r.section, r.name.as_ref(), r.disposition))
            .collect::<Vec<_>>(),
        [
            ("routing", "rule[0]", Disposition::Blocked),
            ("routing", "rule[1]", Disposition::Blocked),
            ("dns.routing.request", "rule[0]", Disposition::Blocked),
        ]
    );
    let after = store
        .read_revision(rev)
        .unwrap()
        .support(&caps(&ingress, &restored, 2));
    assert_eq!(store.head(), Some(rev));
    assert!(after.records.is_empty(), "{:?}", after.records);
    assert_eq!((after.asset_generation, after.blocked()), (Some(2), 0));
}
