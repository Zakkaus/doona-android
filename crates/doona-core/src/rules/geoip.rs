//! The GeoIP codes rule mode knows without geodata (rule-mode.md, decision 4): `CN`, from a table
//! compiled into the core; `private`, MetaCubeX's list; and Mihomo's `LAN`, which is no list but
//! a test of the address. Any other code is skipped as `geodata_missing` until geodata is
//! downloaded (`e-geo`).

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

/// China's IPv4 ranges: APNIC's `delegated-apnic-20261122`, 8,798 records merged into 4,119
/// ranges, written by `tools/gen_geoip.py`. Each range is two big-endian u32, its first and its
/// last address, in ascending order and apart. A 32,952-byte data file, read in place; nothing is
/// built from it. No IPv6 address is `CN`.
static CN_V4: &[u8] = include_bytes!("cn_ipv4.bin");

/// `private`: the IPv4 lines of MetaCubeX's list (meta-rules-dat `meta` at `ca7d9d64`,
/// `geo/geoip/private.list`), which `tools/gen_geoip.py` copies to `private.list`; a test holds
/// the two equal. Address and prefix length.
pub(super) const PRIVATE_V4: [(Ipv4Addr, u8); 16] = [
    (Ipv4Addr::new(0, 0, 0, 0), 8),
    (Ipv4Addr::new(10, 0, 0, 0), 8),
    (Ipv4Addr::new(100, 64, 0, 0), 10),
    (Ipv4Addr::new(127, 0, 0, 0), 8),
    (Ipv4Addr::new(169, 254, 0, 0), 16),
    (Ipv4Addr::new(172, 16, 0, 0), 12),
    (Ipv4Addr::new(192, 0, 0, 0), 24),
    (Ipv4Addr::new(192, 0, 2, 0), 24),
    (Ipv4Addr::new(192, 31, 196, 0), 24),
    (Ipv4Addr::new(192, 52, 193, 0), 24),
    (Ipv4Addr::new(192, 88, 99, 0), 24),
    (Ipv4Addr::new(192, 168, 0, 0), 16),
    (Ipv4Addr::new(198, 18, 0, 0), 15),
    (Ipv4Addr::new(198, 51, 100, 0), 24),
    (Ipv4Addr::new(203, 0, 113, 0), 24),
    (Ipv4Addr::new(224, 0, 0, 0), 3),
];

/// `private`: the IPv6 lines of the same list.
pub(super) const PRIVATE_V6: [(Ipv6Addr, u8); 12] = [
    (Ipv6Addr::new(0, 0, 0, 0, 0, 0, 0, 0), 127),
    (Ipv6Addr::new(0x64, 0xff9b, 0, 0, 0, 0, 0, 0), 96),
    (Ipv6Addr::new(0x64, 0xff9b, 1, 0, 0, 0, 0, 0), 48),
    (Ipv6Addr::new(0x100, 0, 0, 0, 0, 0, 0, 0), 64),
    (Ipv6Addr::new(0x2001, 0, 0, 0, 0, 0, 0, 0), 23),
    (Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 0), 32),
    (Ipv6Addr::new(0x2620, 0x4f, 0x8000, 0, 0, 0, 0, 0), 48),
    (Ipv6Addr::new(0x3fff, 0, 0, 0, 0, 0, 0, 0), 20),
    (Ipv6Addr::new(0x5f00, 0, 0, 0, 0, 0, 0, 0), 16),
    (Ipv6Addr::new(0xfc00, 0, 0, 0, 0, 0, 0, 0), 7),
    (Ipv6Addr::new(0xfe80, 0, 0, 0, 0, 0, 0, 0), 10),
    (Ipv6Addr::new(0xff00, 0, 0, 0, 0, 0, 0, 0), 8),
];

/// `LAN`: Mihomo's `isLan` (`rules/common/geoip.go:154-160`, Mihomo `100ff73a`), Go's
/// `IsUnspecified`, `IsPrivate`, `IsLoopback`, `IsLinkLocalUnicast` and `IsMulticast`
/// (`net/netip/netip.go:519-672`) as IPv4 prefixes.
const LAN_V4: [(Ipv4Addr, u8); 7] = [
    (Ipv4Addr::new(0, 0, 0, 0), 32),
    (Ipv4Addr::new(10, 0, 0, 0), 8),
    (Ipv4Addr::new(172, 16, 0, 0), 12),
    (Ipv4Addr::new(192, 168, 0, 0), 16),
    (Ipv4Addr::new(127, 0, 0, 0), 8),
    (Ipv4Addr::new(169, 254, 0, 0), 16),
    (Ipv4Addr::new(224, 0, 0, 0), 4),
];

/// `LAN`'s IPv6 prefixes, from the same tests.
const LAN_V6: [(Ipv6Addr, u8); 5] = [
    (Ipv6Addr::new(0, 0, 0, 0, 0, 0, 0, 0), 128),
    (Ipv6Addr::new(0xfc00, 0, 0, 0, 0, 0, 0, 0), 7),
    (Ipv6Addr::new(0, 0, 0, 0, 0, 0, 0, 1), 128),
    (Ipv6Addr::new(0xfe80, 0, 0, 0, 0, 0, 0, 0), 10),
    (Ipv6Addr::new(0xff00, 0, 0, 0, 0, 0, 0, 0), 8),
];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Code {
    Cn,
    Private,
    Lan,
}

impl Code {
    /// `CN`, `private` or `LAN`, in any case, as Mihomo lower-cases a code
    /// (`rules/common/geoip.go:203`); `None` for a code with no data.
    pub(crate) fn parse(value: &str) -> Option<Code> {
        [
            ("cn", Code::Cn),
            ("private", Code::Private),
            ("lan", Code::Lan),
        ]
        .into_iter()
        .find_map(|(name, code)| value.eq_ignore_ascii_case(name).then_some(code))
    }

    /// Whether `ip` has this code. A mapped IPv6 address is its IPv4 address, as Mihomo unmaps a
    /// destination before any rule sees it (`tunnel/tunnel.go:282-289`).
    pub(crate) fn contains(self, ip: IpAddr) -> bool {
        match (self, ip.to_canonical()) {
            (Code::Cn, IpAddr::V4(a)) => cn(a),
            (Code::Cn, IpAddr::V6(_)) => false,
            (Code::Private, IpAddr::V4(a)) => in_v4(&PRIVATE_V4, a),
            (Code::Private, IpAddr::V6(a)) => in_v6(&PRIVATE_V6, a),
            (Code::Lan, IpAddr::V4(a)) => in_v4(&LAN_V4, a),
            (Code::Lan, IpAddr::V6(a)) => in_v6(&LAN_V6, a),
        }
    }
}

fn in_v4(prefixes: &[(Ipv4Addr, u8)], a: Ipv4Addr) -> bool {
    let a = u32::from(a).into();
    prefixes
        .iter()
        .any(|&(net, len)| within(a, u32::from(net).into(), len, 32))
}

fn in_v6(prefixes: &[(Ipv6Addr, u8)], a: Ipv6Addr) -> bool {
    prefixes
        .iter()
        .any(|&(net, len)| within(a.into(), net.into(), len, 128))
}

/// Whether `a` is in `net`/`len`, both `width` bits wide.
fn within(a: u128, net: u128, len: u8, width: u32) -> bool {
    let rest = width - u32::from(len);
    rest >= width || (a ^ net) >> rest == 0
}

/// The CN table's ranges.
#[cfg(test)]
pub(super) fn cn_ranges() -> impl Iterator<Item = (u32, u32)> {
    let (ranges, rest) = CN_V4.as_chunks::<8>();
    debug_assert!(rest.is_empty());
    ranges.iter().map(range)
}

fn range(r: &[u8; 8]) -> (u32, u32) {
    (
        u32::from_be_bytes([r[0], r[1], r[2], r[3]]),
        u32::from_be_bytes([r[4], r[5], r[6], r[7]]),
    )
}

/// A binary search of the table: 13 steps over its 4,119 ranges.
fn cn(a: Ipv4Addr) -> bool {
    let (ranges, _) = CN_V4.as_chunks::<8>();
    let x = u32::from(a);
    let after = ranges.partition_point(|r| range(r).0 <= x);
    after > 0 && x <= range(&ranges[after - 1]).1
}
