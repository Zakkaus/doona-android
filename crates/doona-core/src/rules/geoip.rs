//! The GeoIP codes rule mode knows without geodata (rule-mode.md, decision 4): `CN`, from a table
//! compiled into the core, and `private`, the special-purpose ranges. Any other code is skipped as
//! `geodata_missing` until geodata is downloaded (`e-geo`).

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

/// China's IPv4 ranges: APNIC's `delegated-apnic-20261122`, 8,798 records merged into 4,119
/// ranges, written by `tools/gen_cn_ipv4.py`. Each range is two big-endian u32, its first and
/// its last address, in ascending order and apart. 32,952 bytes, read in place; nothing is
/// built from it. No IPv6 address is `CN`.
static CN_V4: &[u8] = include_bytes!("cn_ipv4.bin");

/// The IPv4 ranges IANA's special-purpose registries (RFC 6890) mark as not globally reachable,
/// with 192.88.99.0/24 (the retired 6to4 relays) and multicast: address and prefix length.
const PRIVATE_V4: [(Ipv4Addr, u8); 15] = [
    (Ipv4Addr::new(0, 0, 0, 0), 8),
    (Ipv4Addr::new(10, 0, 0, 0), 8),
    (Ipv4Addr::new(100, 64, 0, 0), 10),
    (Ipv4Addr::new(127, 0, 0, 0), 8),
    (Ipv4Addr::new(169, 254, 0, 0), 16),
    (Ipv4Addr::new(172, 16, 0, 0), 12),
    (Ipv4Addr::new(192, 0, 0, 0), 24),
    (Ipv4Addr::new(192, 0, 2, 0), 24),
    (Ipv4Addr::new(192, 88, 99, 0), 24),
    (Ipv4Addr::new(192, 168, 0, 0), 16),
    (Ipv4Addr::new(198, 18, 0, 0), 15),
    (Ipv4Addr::new(198, 51, 100, 0), 24),
    (Ipv4Addr::new(203, 0, 113, 0), 24),
    (Ipv4Addr::new(224, 0, 0, 0), 4),
    (Ipv4Addr::new(240, 0, 0, 0), 4),
];

/// The IPv6 ranges of the same registries, and multicast. IPv4-mapped addresses are matched as
/// IPv4 (`to_canonical`).
const PRIVATE_V6: [(Ipv6Addr, u8); 7] = [
    // Unspecified and loopback.
    (Ipv6Addr::new(0, 0, 0, 0, 0, 0, 0, 0), 127),
    (Ipv6Addr::new(0x64, 0xff9b, 1, 0, 0, 0, 0, 0), 48),
    (Ipv6Addr::new(0x100, 0, 0, 0, 0, 0, 0, 0), 64),
    (Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 0), 32),
    (Ipv6Addr::new(0xfc00, 0, 0, 0, 0, 0, 0, 0), 7),
    (Ipv6Addr::new(0xfe80, 0, 0, 0, 0, 0, 0, 0), 10),
    (Ipv6Addr::new(0xff00, 0, 0, 0, 0, 0, 0, 0), 8),
];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Code {
    Cn,
    Private,
}

impl Code {
    /// `CN`, or `private` (Mihomo's `LAN` too), in any case; `None` for a code with no data.
    pub(crate) fn parse(value: &str) -> Option<Code> {
        if value.eq_ignore_ascii_case("cn") {
            Some(Code::Cn)
        } else if value.eq_ignore_ascii_case("private") || value.eq_ignore_ascii_case("lan") {
            Some(Code::Private)
        } else {
            None
        }
    }

    pub(crate) fn contains(self, ip: IpAddr) -> bool {
        match (self, ip.to_canonical()) {
            (Code::Cn, IpAddr::V4(a)) => cn(a),
            (Code::Cn, IpAddr::V6(_)) => false,
            (Code::Private, IpAddr::V4(a)) => PRIVATE_V4
                .iter()
                .any(|&(net, len)| within(u32::from(a).into(), u32::from(net).into(), len, 32)),
            (Code::Private, IpAddr::V6(a)) => PRIVATE_V6
                .iter()
                .any(|&(net, len)| within(a.into(), net.into(), len, 128)),
        }
    }
}

/// Whether `a` is in `net`/`len`, both `width` bits wide.
fn within(a: u128, net: u128, len: u8, width: u32) -> bool {
    let rest = width - u32::from(len);
    rest >= width || (a ^ net) >> rest == 0
}

/// The CN table's ranges.
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
