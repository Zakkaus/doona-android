use super::*;
use crate::dto::GroupKind;
use crate::groups::GroupDef;
use crate::ids::group_id;
use std::net::Ipv4Addr;

/// splitmix64: the tests' and benches' random numbers, the same on every run.
pub(crate) struct Rng(pub u64);

impl Rng {
    pub(crate) fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9e37_79b9_7f4a_7c15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
        z ^ (z >> 31)
    }

    /// A number below `n`, `n` > 0.
    pub(crate) fn below(&mut self, n: u64) -> u64 {
        self.next() % n
    }

    pub(crate) fn u128(&mut self) -> u128 {
        (u128::from(self.next()) << 64) | u128::from(self.next())
    }
}

pub(crate) fn group(name: &str, members: &[&str]) -> GroupDef {
    GroupDef {
        id: group_id(name),
        name: name.to_owned(),
        kind: GroupKind::Selector,
        members: members.iter().map(|m| (*m).to_owned()).collect(),
    }
}

pub(crate) fn rule(kind: &str, values: &[&str], target: &str) -> RuleSpec {
    RuleSpec {
        kind: Kind::clash(kind),
        values: values.iter().map(|v| (*v).to_owned()).collect(),
        target: match target {
            "DIRECT" => Target::Direct,
            "REJECT" => Target::Reject,
            name => Target::Group(group_id(name)),
        },
    }
}

fn groups() -> Vec<GroupDef> {
    vec![group("Proxy", &["n-1"]), group("Streaming", &["g-Proxy"])]
}

fn compile(rules: &[RuleSpec]) -> RuleSet {
    RuleSet::compile(&groups(), rules).unwrap()
}

fn refused(rules: &[RuleSpec]) -> bool {
    RuleSet::compile(&groups(), rules) == Err(ErrorCode::ParseFailed)
}

/// A TCP flow to port 443 of `ip`, named `domain` when given.
fn flow<'a>(domain: Option<&'a str>, ip: &str) -> Flow<'a> {
    Flow {
        domain,
        ip: ip.parse().unwrap(),
        port: 443,
        network: Network::Tcp,
    }
}

fn named(set: &RuleSet, name: &str) -> Option<usize> {
    set.matched(&flow(Some(name), "198.51.100.7"))
}

fn at(set: &RuleSet, ip: &str) -> Option<usize> {
    set.matched(&flow(None, ip))
}

impl PartialEq for RuleSet {
    fn eq(&self, other: &RuleSet) -> bool {
        self.rows == other.rows
    }
}

#[test]
fn domain_matches_the_name_only() {
    let set = compile(&[rule("DOMAIN", &["Netflix.com."], "Streaming")]);
    for hit in ["netflix.com", "NETFLIX.COM", "netflix.com."] {
        assert_eq!(named(&set, hit), Some(0), "{hit}");
    }
    for miss in ["www.netflix.com", "netflix.co", "notnetflix.com"] {
        assert_eq!(named(&set, miss), None, "{miss}");
    }
}

#[test]
fn a_suffix_matches_at_a_label_boundary() {
    let set = compile(&[rule("DOMAIN-SUFFIX", &["netflix.com"], "Streaming")]);
    for hit in [
        "netflix.com",
        "www.netflix.com",
        "a.b.NetFlix.com.",
        "x.netflix.com",
    ] {
        assert_eq!(named(&set, hit), Some(0), "{hit}");
    }
    for miss in ["notnetflix.com", "netflix.com.evil", "com", "netflix", ""] {
        assert_eq!(named(&set, miss), None, "{miss}");
    }
    // A leading dot leaves a suffix no parent starts with.
    let dotted = compile(&[rule("DOMAIN-SUFFIX", &[".cn"], "Streaming")]);
    assert_eq!(named(&dotted, "a.cn"), None);
}

#[test]
fn names_outside_ascii_match_in_their_idna_form() {
    let set = compile(&[rule("DOMAIN-SUFFIX", &["中国.cn"], "DIRECT")]);
    assert_eq!(named(&set, "www.xn--fiqs8s.cn"), Some(0));
}

#[test]
fn a_keyword_matches_anywhere_in_lower_case() {
    let set = compile(&[
        rule("DOMAIN-KEYWORD", &["Google", "tube"], "Proxy"),
        rule("DOMAIN-KEYWORD", &["goo"], "DIRECT"),
    ]);
    for hit in ["www.google.com", "googleapis.cn", "YOUTUBE.com", "goo.gl"] {
        let want = if hit == "goo.gl" { 1 } else { 0 };
        assert_eq!(named(&set, hit), Some(want), "{hit}");
    }
    assert_eq!(named(&set, "gog.le"), None);
}

#[test]
fn cidrs_hold_their_edges_and_ignore_host_bits() {
    let set = compile(&[
        rule("IP-CIDR", &["10.1.2.3/8"], "DIRECT"),
        rule("IP-CIDR", &["192.0.2.1/32"], "REJECT"),
        rule("IP-CIDR6", &["2001:db8::/32"], "Proxy"),
        rule("IP-CIDR", &["0.0.0.0/0"], "Streaming"),
    ]);
    assert_eq!(at(&set, "10.0.0.0"), Some(0));
    assert_eq!(at(&set, "10.255.255.255"), Some(0));
    assert_eq!(at(&set, "11.0.0.0"), Some(3));
    assert_eq!(at(&set, "9.255.255.255"), Some(3));
    assert_eq!(at(&set, "192.0.2.1"), Some(1));
    assert_eq!(at(&set, "192.0.2.2"), Some(3));
    assert_eq!(at(&set, "2001:db8:ffff::1"), Some(2));
    // A /0 of one family holds none of the other; a mapped address is IPv4.
    assert_eq!(at(&set, "2001:db9::1"), None);
    assert_eq!(at(&set, "::ffff:10.0.0.1"), Some(0));
    // A mapped rule is the IPv4 prefix it maps.
    let mapped = compile(&[rule("IP-CIDR6", &["::ffff:198.51.100.0/120"], "DIRECT")]);
    assert_eq!(at(&mapped, "198.51.100.200"), Some(0));
    assert_eq!(at(&mapped, "198.51.101.1"), None);
}

#[test]
fn geoip_cn_holds_the_first_and_last_address_of_each_range() {
    let set = compile(&[rule("GEOIP", &["cn"], "DIRECT")]);
    let v4 = |a: u32| IpAddr::V4(Ipv4Addr::from(a));
    let mut ranges = 0;
    for (first, last) in geoip::cn_ranges() {
        ranges += 1;
        for inside in [first, last] {
            assert!(geoip::Code::Cn.contains(v4(inside)), "{}", v4(inside));
        }
        // Ranges are merged, so the addresses around one are outside the table.
        for outside in [first.checked_sub(1), last.checked_add(1)]
            .into_iter()
            .flatten()
        {
            assert!(!geoip::Code::Cn.contains(v4(outside)), "{}", v4(outside));
        }
    }
    assert_eq!(ranges, 4_119);
    for (ip, want) in [
        ("223.5.5.5", Some(0)),
        ("114.114.114.114", Some(0)),
        ("8.8.8.8", None),
        ("1.1.1.1", None),
        ("240e::1", None),
    ] {
        assert_eq!(at(&set, ip), want, "{ip}");
    }
}

#[test]
fn geoip_private_holds_the_special_ranges_of_both_families() {
    let set = compile(&[rule("GEOIP", &["PRIVATE"], "DIRECT")]);
    let lan = compile(&[rule("GEOIP", &["lan"], "DIRECT")]);
    for ip in [
        "10.1.2.3",
        "172.31.255.255",
        "192.168.0.1",
        "127.0.0.1",
        "169.254.1.1",
        "100.64.0.1",
        "224.0.0.251",
        "255.255.255.255",
        "0.0.0.0",
        "::1",
        "::",
        "fd00::1",
        "fe80::1",
        "ff02::fb",
    ] {
        assert_eq!(at(&set, ip), Some(0), "{ip}");
        assert_eq!(at(&lan, ip), Some(0), "{ip}");
    }
    for ip in [
        "8.8.8.8",
        "172.32.0.1",
        "192.169.0.1",
        "2001:4860::8888",
        "fe00::1",
    ] {
        assert_eq!(at(&set, ip), None, "{ip}");
    }
}

#[test]
fn another_geoip_code_is_skipped_and_counted() {
    let set = compile(&[
        rule("GEOIP", &["US", "CN"], "Proxy"),
        rule("GEOIP", &["JP"], "DIRECT"),
    ]);
    assert_eq!((set.rows()[0].skipped, set.rows()[1].skipped), (1, 1));
    assert_eq!(set.skipped(), 2);
    assert_eq!(at(&set, "223.5.5.5"), Some(0));
    assert_eq!(at(&set, "8.8.8.8"), None);
}

#[test]
fn ports_take_single_ports_ranges_and_lists() {
    let set = compile(&[
        rule("DST-PORT", &["8000-9000"], "Proxy"),
        rule("DST-PORT", &["25/465/587"], "REJECT"),
        rule("DST-PORT", &["0-65535"], "DIRECT"),
    ]);
    let port = |p| {
        set.matched(&Flow {
            port: p,
            ..flow(None, "8.8.8.8")
        })
    };
    assert_eq!(
        [port(7999), port(8000), port(9000), port(9001)],
        [Some(2), Some(0), Some(0), Some(2)]
    );
    assert_eq!(
        [port(25), port(465), port(587), port(586)],
        [Some(1), Some(1), Some(1), Some(2)]
    );
    assert_eq!(port(65_535), Some(2));
}

#[test]
fn network_matches_tcp_or_udp() {
    let set = compile(&[rule("NETWORK", &["UDP"], "REJECT")]);
    let udp = Flow {
        network: Network::Udp,
        ..flow(None, "8.8.8.8")
    };
    assert_eq!(set.matched(&udp), Some(0));
    assert_eq!(at(&set, "8.8.8.8"), None);
}

#[test]
fn the_first_matching_row_wins_and_match_ends_the_list() {
    let set = compile(&[
        rule("DOMAIN-KEYWORD", &["netflix"], "Proxy"),
        rule("DOMAIN-SUFFIX", &["netflix.com"], "Streaming"),
        rule("IP-CIDR", &["198.51.100.0/24"], "REJECT"),
        rule("GEOIP", &["CN"], "DIRECT"),
        rule("MATCH", &[], "Proxy"),
        rule("DOMAIN", &["example.com"], "DIRECT"),
    ]);
    assert_eq!(named(&set, "www.netflix.com"), Some(0));
    assert_eq!(named(&set, "example.org"), Some(2));
    assert_eq!(at(&set, "223.5.5.5"), Some(3));
    assert_eq!(at(&set, "8.8.8.8"), Some(4));
    // Rows after MATCH never match.
    assert_eq!(set.matched(&flow(Some("example.com"), "8.8.8.8")), Some(4));
}

#[test]
fn without_a_name_domain_rows_miss_and_ip_rows_still_apply() {
    let set = compile(&[
        rule("DOMAIN-SUFFIX", &["netflix.com"], "Streaming"),
        rule("GEOIP", &["CN"], "DIRECT"),
    ]);
    assert_eq!(set.matched(&flow(None, "8.8.8.8")), None);
    assert_eq!(set.matched(&flow(None, "223.5.5.5")), Some(1));
    assert_eq!(
        set.matched(&flow(Some("netflix.com"), "223.5.5.5")),
        Some(0)
    );
}

#[test]
fn unsupported_kinds_are_skipped_counted_and_never_match() {
    let set = compile(&[
        rule("GEOSITE", &["netflix"], "Streaming"),
        rule("RULE-SET", &["streaming"], "Streaming"),
        rule(
            "PROCESS-NAME",
            &["com.netflix.mediaclient", "chrome"],
            "Proxy",
        ),
        rule("DOMAIN-REGEX", &["^.*netflix.*$"], "Streaming"),
        rule("AND", &["((NETWORK,UDP),(DST-PORT,443))"], "REJECT"),
        rule("SRC-IP-CIDR", &["192.168.1.0/24"], "DIRECT"),
        rule("NOT-A-KIND", &["x"], "DIRECT"),
        rule("DOMAIN-SUFFIX", &["netflix.com"], "Streaming"),
    ]);
    let reasons: Vec<Option<Skip>> = set
        .rows()
        .iter()
        .map(|r| match &r.kind {
            Kind::Skipped { reason, .. } => Some(*reason),
            _ => None,
        })
        .collect();
    use Skip::*;
    assert_eq!(
        reasons,
        [
            Some(GeodataMissing),
            Some(UnsupportedKind),
            Some(AppRule),
            Some(UnsupportedKind),
            Some(UnsupportedKind),
            Some(UnsupportedKind),
            Some(UnsupportedKind),
            None
        ]
    );
    assert_eq!(set.skipped(), 8);
    assert_eq!(named(&set, "www.netflix.com"), Some(7));
    assert_eq!(GeodataMissing.as_str(), "geodata_missing");
}

#[test]
fn kinds_are_read_in_any_case() {
    assert_eq!(Kind::clash("domain-suffix"), Kind::DomainSuffix);
    assert_eq!(Kind::clash("IP-CIDR6"), Kind::IpCidr);
    assert_eq!(
        Kind::clash("uid"),
        Kind::Skipped {
            name: "uid".into(),
            reason: Skip::AppRule
        }
    );
}

#[test]
fn values_that_do_not_parse_refuse_the_source() {
    for bad in [
        rule("IP-CIDR", &["192.0.2.1"], "DIRECT"),
        rule("IP-CIDR", &["192.0.2.0/33"], "DIRECT"),
        rule("IP-CIDR6", &["::/129"], "DIRECT"),
        rule("IP-CIDR", &["example.com/8"], "DIRECT"),
        rule("DST-PORT", &["65536"], "DIRECT"),
        rule("DST-PORT", &["90-80"], "DIRECT"),
        rule("DST-PORT", &["80/"], "DIRECT"),
        rule("NETWORK", &["icmp"], "DIRECT"),
        rule("DOMAIN", &["."], "DIRECT"),
        rule("DOMAIN-KEYWORD", &[""], "DIRECT"),
        rule("DOMAIN", &[], "DIRECT"),
        rule("MATCH", &["x"], "DIRECT"),
    ] {
        assert!(refused(std::slice::from_ref(&bad)), "{bad:?}");
    }
}

#[test]
fn a_missing_target_or_member_refuses_the_source() {
    assert!(refused(&[rule("MATCH", &[], "Nowhere")]));
    // A skipped rule's target is checked too.
    assert!(refused(&[rule("GEOSITE", &["cn"], "Nowhere")]));
    let dangling = [group("Proxy", &["g-Gone"])];
    assert!(RuleSet::compile(&dangling, &[]).is_err());
    let twice = [group("Proxy", &["n-1"]), group("Proxy", &["n-2"])];
    assert!(RuleSet::compile(&twice, &[]).is_err());
}

#[test]
fn group_cycles_refuse_the_source() {
    let cycles: [&[GroupDef]; 3] = [
        &[group("A", &["g-A"])],
        &[group("A", &["g-B"]), group("B", &["n-1", "g-A"])],
        &[
            group("A", &["g-B"]),
            group("B", &["g-C"]),
            group("C", &["g-A"]),
        ],
    ];
    for groups in cycles {
        assert_eq!(RuleSet::compile(groups, &[]), Err(ErrorCode::ParseFailed));
    }
    // Two paths to one group are no cycle.
    let diamond = [
        group("A", &["g-B", "g-C"]),
        group("B", &["g-D"]),
        group("C", &["g-D"]),
        group("D", &["n-1"]),
    ];
    assert!(RuleSet::compile(&diamond, &[]).is_ok());
}
