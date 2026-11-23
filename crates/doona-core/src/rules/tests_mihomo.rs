//! Behaviour pinned to Mihomo's source, MetaCubeX/mihomo `Alpha` at `100ff73a` (2026-11-20),
//! after astra's review of R1. Each test names the lines it follows.

use super::tests::{at, compile, group, named, node, refused, rule};
use super::*;
use std::net::{Ipv4Addr, Ipv6Addr};

/// The first row that matches a TCP flow to `port` of 8.8.8.8.
fn port(set: &RuleSet, port: u16) -> Option<usize> {
    set.matched(&Flow {
        domain: None,
        ip: "8.8.8.8".parse().unwrap(),
        port,
        network: Network::Tcp,
    })
}

/// An `IP-CIDR6` prefix inside `::ffff:0:0/96` stays IPv6: `netip.ParsePrefix` keeps the mapped
/// form (`rules/common/ipcidr.go:59`), `Prefix.Contains` never matches across families (Go
/// `net/netip/netip.go:1423`), and Mihomo unmaps every destination before it matches
/// (`tunnel/tunnel.go:282-289`, `constant/metadata.go:335,364`). Such a rule matches no flow.
#[test]
fn a_mapped_prefix_stays_ipv6_and_matches_no_flow() {
    let set = compile(&[
        rule("IP-CIDR6", &["::ffff:192.0.2.0/120"], "DIRECT"),
        rule("MATCH", &[], "REJECT"),
    ]);
    assert_eq!(at(&set, "192.0.2.1"), Some(1));
    assert_eq!(at(&set, "::ffff:192.0.2.1"), Some(1));
    // An IPv4 rule holds a mapped destination, which Mihomo unmaps first.
    let v4 = compile(&[rule("IP-CIDR", &["192.0.2.0/24"], "DIRECT")]);
    assert_eq!(at(&v4, "::ffff:192.0.2.1"), Some(0));
}

/// `GEOIP,LAN` is Mihomo's `isLan` (`rules/common/geoip.go:48-49,154-160`): Go's `IsPrivate`,
/// `IsUnspecified`, `IsLoopback`, `IsMulticast` and `IsLinkLocalUnicast` (Go
/// `net/netip/netip.go:519-672`), not the `private` list.
#[test]
fn geoip_lan_is_mihomos_is_lan() {
    let lan = compile(&[rule("GEOIP", &["LAN"], "DIRECT")]);
    for ip in [
        "10.1.2.3",
        "172.31.255.255",
        "192.168.0.1",
        "127.0.0.1",
        "169.254.1.1",
        "224.0.0.251",
        "239.255.255.250",
        "0.0.0.0",
        "fd00::1",
        "fe80::1",
        "ff02::fb",
        "::1",
        "::",
    ] {
        assert_eq!(at(&lan, ip), Some(0), "{ip}");
    }
    for ip in [
        "100.64.0.1",
        "0.0.0.1",
        "192.0.2.1",
        "198.18.0.1",
        "240.0.0.1",
        "255.255.255.255",
        "8.8.8.8",
        "::2",
        "2001:db8::1",
        "64:ff9b::808:808",
        "fec0::1",
    ] {
        assert_eq!(at(&lan, ip), None, "{ip}");
    }
}

/// `GEOIP,private` is MetaCubeX's list (meta-rules-dat `meta`, `geo/geoip/private.list`), which
/// holds what `LAN` leaves out.
#[test]
fn geoip_private_is_the_metacubex_list() {
    let private = compile(&[rule("GEOIP", &["private"], "DIRECT")]);
    for ip in [
        "100.64.0.1",
        "192.31.196.1",
        "192.52.193.1",
        "255.255.255.255",
        "64:ff9b::808:808",
        "2001::1",
        "2001:1ff:ffff::1",
        "2620:4f:8000::1",
        "3fff::1",
        "5f00::1",
    ] {
        assert_eq!(at(&private, ip), Some(0), "{ip}");
    }
    for ip in [
        "192.31.197.1",
        "192.52.194.1",
        "2001:200::1",
        "2620:4f:8001::1",
        "4000::1",
        "64:ff9b::1:0:0:0",
    ] {
        assert_eq!(at(&private, ip), None, "{ip}");
    }
}

/// A group whose member is not a known node refuses the profile: `getProxies` fails on a name
/// it does not hold (`adapter/outboundgroup/parser.go:156,223-228`), and so does the whole
/// configuration (`config/config.go:956-958`).
#[test]
fn a_member_that_is_no_known_node_refuses_the_source() {
    let missing = [group("Proxy", &["n-1", "n-missing"])];
    assert_eq!(
        RuleSet::compile(&missing, &node, &[]),
        Err(ErrorCode::ParseFailed)
    );
    let known = [group("Proxy", &["n-1", "n-2"])];
    assert!(RuleSet::compile(&known, &node, &[]).is_ok());
}

/// `DST-PORT` reads its value as `utils.NewUnsignedRanges` (`common/utils/ranges.go:16-48`,
/// `common/utils/range.go:16-28,65-95`): the value trimmed, `,` read as `/`, empty pieces
/// skipped, each piece trimmed, `[`, ` ` and `]` trimmed around each port, and a reversed range
/// turned around.
#[test]
fn dst_port_reads_a_value_as_mihomo_does() {
    let set = compile(&[
        rule("DST-PORT", &["80/"], "DIRECT"),
        rule("DST-PORT", &["/443"], "DIRECT"),
        rule("DST-PORT", &["25//465"], "DIRECT"),
        rule("DST-PORT", &[" 53 , 853 "], "DIRECT"),
        rule("DST-PORT", &["9000-8000"], "DIRECT"),
        rule("DST-PORT", &["[1080]"], "DIRECT"),
    ]);
    let rows = [
        (80, 0),
        (443, 1),
        (25, 2),
        (465, 2),
        (53, 3),
        (853, 3),
        (8000, 4),
        (9000, 4),
        (1080, 5),
    ];
    for (p, row) in rows {
        assert_eq!(port(&set, p), Some(row), "{p}");
    }
    assert_eq!([port(&set, 81), port(&set, 7999)], [None, None]);
    // 28 ranges are more than `MAX_VALUE` bytes, and still one value.
    let ranges: Vec<String> = (0..28)
        .map(|i| format!("{}-{}", 10_000 + 2 * i, 10_001 + 2 * i))
        .collect();
    let long = ranges.join("/");
    assert!(long.len() > limits::MAX_VALUE);
    let set = compile(&[rule("DST-PORT", &[&long], "DIRECT")]);
    assert_eq!([port(&set, 10_055), port(&set, 10_056)], [Some(0), None]);
    // A piece of spaces only is the range 0-0 (`range.go:66-68`).
    let zero = compile(&[rule("DST-PORT", &["80/ /443"], "DIRECT")]);
    assert_eq!([port(&zero, 0), port(&zero, 1)], [Some(0), None]);
}

/// What Mihomo refuses: a value with no range (`rules/common/port.go:42-49`, and `*` reads as
/// none at `ranges.go:19-20`), more than 28 pieces (`ranges.go:25-28`), a sign
/// (`strconv.ParseUint`, `range.go:90`), and three ends (`range.go:84-85`).
#[test]
fn dst_port_refuses_what_mihomo_refuses() {
    let pieces = |n: u16| (1..=n).map(|p| p.to_string()).collect::<Vec<_>>().join("/");
    assert!(!refused(&[rule("DST-PORT", &[&pieces(28)], "DIRECT")]));
    let many = pieces(29);
    for bad in [
        "",
        " ",
        "/",
        "//",
        "*",
        "+80",
        "80-90-100",
        "-80",
        "80-",
        "8o",
        &many,
    ] {
        assert!(refused(&[rule("DST-PORT", &[bad], "DIRECT")]), "{bad:?}");
    }
    // Mihomo keeps the low 16 bits of a larger number (`range.go:91`, `T(val)`); doona refuses
    // it rather than match a port the profile never named.
    assert!(refused(&[rule("DST-PORT", &["65616"], "DIRECT")]));
}

/// A kind without data and without values is one line skipped, as the limits count it; only a
/// compiled kind needs its values.
#[test]
fn a_skipped_kind_without_values_is_one_line_skipped() {
    let geosite = RuleSpec {
        kind: Kind::clash("GEOSITE"),
        values: Vec::new(),
        target: Target::Direct,
    };
    let set = compile(&[geosite]);
    assert_eq!((set.rows()[0].skipped, set.skipped()), (1, 1));
}

/// A name of 253 bytes with a final dot is within the bound: the dot is dropped first.
#[test]
fn a_final_dot_does_not_count_toward_a_names_length() {
    let label = "a".repeat(63);
    let name = format!("{label}.{label}.{label}.{}", "b".repeat(61));
    assert_eq!(name.len(), limits::MAX_VALUE);
    let dotted = format!("{name}.");
    for kind in ["DOMAIN", "DOMAIN-SUFFIX"] {
        let set = compile(&[rule(kind, &[&dotted], "DIRECT")]);
        assert_eq!(named(&set, &name), Some(0), "{kind}");
        let longer = format!("x{dotted}");
        assert!(refused(&[rule(kind, &[&longer], "DIRECT")]), "{kind}");
    }
}

/// geoip.rs's `private` tables are the lines of `private.list`, which `tools/gen_geoip.py` copies
/// from MetaCubeX's list, and each network holds its first and its last address.
#[test]
fn the_private_tables_are_the_generated_list() {
    let (mut v4, mut v6) = (Vec::new(), Vec::new());
    for line in include_str!("private.list").lines() {
        let (addr, len) = line.split_once('/').unwrap();
        let len: u8 = len.parse().unwrap();
        match addr.parse().unwrap() {
            IpAddr::V4(a) => v4.push((a, len)),
            IpAddr::V6(a) => v6.push((a, len)),
        }
    }
    assert_eq!(v4, geoip::PRIVATE_V4);
    assert_eq!(v6, geoip::PRIVATE_V6);
    // The first and the last address of `net`/`len`, `width` bits wide.
    let ends = |net: u128, len: u8, width: u32| {
        let host = u128::MAX
            .checked_shr(128 - (width - u32::from(len)))
            .unwrap_or(0);
        [net, net | host]
    };
    let v4 = v4
        .into_iter()
        .flat_map(|(a, len)| ends(u32::from(a).into(), len, 32));
    let v4 = v4.map(|x| IpAddr::V4(Ipv4Addr::from(x as u32)));
    let v6 = v6.into_iter().flat_map(|(a, len)| ends(a.into(), len, 128));
    for ip in v4.chain(v6.map(|x| IpAddr::V6(Ipv6Addr::from(x)))) {
        assert!(geoip::Code::Private.contains(ip), "{ip}");
    }
}
