use super::*;
use crate::rules::{
    Origin, RuleSet,
    tests::{at, named, rule},
};
use crate::testutil::{TempDir, alloc::peak};
use std::fs;

fn field(tag: u8, bytes: &[u8]) -> Vec<u8> {
    let mut n = bytes.len();
    let mut out = vec![tag];
    while n >= 128 {
        out.push((n as u8) | 128);
        n >>= 7;
    }
    out.push(n as u8);
    out.extend(bytes);
    out
}
fn entry(code: &str, records: &[Vec<u8>]) -> Vec<u8> {
    let mut bytes = field(10, code.as_bytes());
    for record in records {
        bytes.extend(field(18, record));
    }
    field(10, &bytes)
}
fn domain(kind: u8, name: &str, attrs: &[&str]) -> Vec<u8> {
    let mut record = vec![8, kind];
    record.extend(field(18, name.as_bytes()));
    for attr in attrs {
        record.extend(field(26, &field(10, attr.as_bytes())));
    }
    record
}
fn cidr(addr: &[u8], len: u8) -> Vec<u8> {
    let mut b = field(10, addr);
    b.extend([16, len]);
    b
}
fn fixture(dir: &Path) {
    let ip = [
        entry("US", &[cidr(&[8, 8, 8, 0], 24)]),
        entry("JP", &[cidr(&[1, 2, 3, 4], 32)]),
        entry("TW", &[cidr(&[0x24; 16], 64)]),
    ]
    .concat();
    fs::write(dir.join("geoip.dat"), ip).unwrap();
    let site = entry(
        "media",
        &[
            domain(2, "video.test", &["cn", "ads"]),
            domain(3, "exact.test", &["CN"]),
            domain(0, "inside", &[]),
            domain(1, r"^r[0-9]+\.test$", &[]),
        ],
    );
    fs::write(dir.join("geosite.dat"), site).unwrap();
}
fn compiled(dir: &Path, rows: &[RuleSpec]) -> RuleSet {
    let data = Data::load(dir, rows).unwrap();
    RuleSet::compile_source(Origin::Imported, &[], &|_| false, rows, Some(&data)).unwrap()
}
#[test]
fn mihomo_geosite_types_and_attribute_conjunction() {
    let dir = TempDir::new("site-parity");
    fixture(dir.path());
    let rows = [
        rule("GEOSITE", &["media@CN@ads"], "DIRECT"),
        rule("GEOSITE", &["MEDIA@cn"], "DIRECT"),
        rule("GEOSITE", &["media"], "REJECT"),
    ];
    let set = compiled(dir.path(), &rows);
    for (name, row) in [
        ("a.video.test", 0),
        ("VIDEO.TEST", 0),
        ("exact.test", 1),
        ("xinside.test", 2),
        ("r101.test", 2),
    ] {
        assert_eq!(named(&set, name), Some(row), "{name}");
    }
    for name in ["badvideo.test", "x.exact.test", "xr1.test", "r1xtest"] {
        assert_eq!(named(&set, name), None, "{name}");
    }
    let inverted = compiled(dir.path(), &[rule("GEOSITE", &["!media@cn"], "REJECT")]);
    assert_eq!(named(&inverted, "r1.test"), Some(0));
    assert_eq!(at(&inverted, "8.8.8.8"), None);
}
#[test]
fn invalid_regexp_skips_one_entry_not_the_generation() {
    let dir = TempDir::new("geo-invalid-regexp");
    fixture(dir.path());
    fs::write(
        dir.path().join("geosite.dat"),
        entry(
            "media",
            &[domain(1, "(?=foo)", &[]), domain(3, "good.test", &[])],
        ),
    )
    .unwrap();
    let rows = [
        rule("GEOSITE", &["media"], "DIRECT"),
        rule("GEOIP", &["US"], "DIRECT"),
        rule("GEOSITE", &["media@!ads"], "REJECT"),
    ];
    let set = compiled(dir.path(), &rows);
    assert_eq!(named(&set, "good.test"), Some(0));
    assert_eq!(at(&set, "8.8.8.8"), Some(1));
    let listing = crate::rules::listing::Listing::new("fixture", &rows, Some(&set)).page(0, 3);
    assert_eq!(listing.rows[0].skipped_lines, 1);
    assert_eq!(listing.rows[0].skipped.as_deref(), Some("geodata_missing"));
    assert_eq!(listing.rows[2].skipped.as_deref(), Some("unsupported_kind"));
}
#[test]
fn geoip_uses_dat_only_except_private_and_lan() {
    let dir = TempDir::new("ip-parity");
    fixture(dir.path());
    let rows = [
        rule("GEOIP", &["cn", "US"], "DIRECT"),
        rule("GEOIP", &["jp"], "REJECT"),
        rule("GEOIP", &["tw"], "DIRECT"),
        rule("GEOIP", &["Private", "LAN"], "REJECT"),
        rule("GEOSITE", &["absent"], "DIRECT"),
    ];
    let set = compiled(dir.path(), &rows);
    for (ip, row) in [
        ("::ffff:8.8.8.199", 0),
        ("1.2.3.4", 1),
        ("2424:2424:2424:2424::1", 2),
        ("192.168.9.1", 3),
    ] {
        assert_eq!(at(&set, ip), Some(row));
    }
    assert_eq!(at(&set, "223.5.5.5"), None);
    assert_eq!(set.skipped(), 2);
    let listing = crate::rules::listing::Listing::new("fixture", &rows, Some(&set));
    assert_eq!(
        listing.page(0, 5).rows[0].skipped.as_deref(),
        Some("geodata_missing")
    );
    let mut out = crate::rules::export::Capped::new(4096);
    listing.export(&str::to_owned, &mut out);
    let text = out.finish().0;
    assert!(text.contains("# GEOIP,cn,DIRECT (skipped: geodata_missing)\nGEOIP,US,DIRECT\n"));
}
#[test]
fn unreferenced_country_does_not_enter_the_heap() {
    let dir = TempDir::new("geo-memory");
    fixture(dir.path());
    let path = dir.path().join("geoip.dat");
    let mut b = fs::read(&path).unwrap();
    b.extend(entry("unused", &vec![cidr(&[1, 2, 3, 4], 32); 100_000]));
    fs::write(path, b).unwrap();
    let (set, bytes, _) = peak(|| compiled(dir.path(), &[rule("GEOIP", &["US"], "DIRECT")]));
    assert_eq!(at(&set, "8.8.8.8"), Some(0));
    assert!(bytes < 64 * 1024, "{bytes}");
}
#[test]
#[ignore = "requires DOONA_GEO_DIR containing the pinned DATs"]
fn pinned_template_peak() {
    let dir = std::env::var_os("DOONA_GEO_DIR").unwrap();
    let dir = Path::new(&dir);
    let pins = crate::honkconf::assets::Release::from_manifest(include_bytes!(
        "../../../tools/geo-pins.json"
    ))
    .unwrap();
    pins.verify(dir).unwrap();
    let (policy, bytes, kept) = peak(|| {
        Data::verify(dir).unwrap();
        let data = Data::load(dir, &crate::rules::template::rules()).unwrap();
        crate::rules::policy::Policy::template_with_data(
            &[],
            &|_| false,
            Default::default(),
            Some(&data),
        )
    });
    assert_eq!(policy.source().skipped, 0);
    assert!(bytes < 4 << 20, "{bytes}");
    println!("cn-direct peak_heap_bytes={bytes} retained_heap_bytes={kept}");
}
