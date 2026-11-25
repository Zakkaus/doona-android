//! Which destination prefixes the platform routes into the TUN.

use std::net::{IpAddr, Ipv4Addr};

/// The IPv4 prefixes the VPN routes into the TUN, as the platform installed them
/// (Android: `TunRoutes.IPV4`, public IPv4 plus the TUN's own /30).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Ingress {
    routes: Vec<(u32, u8)>,
}

impl Ingress {
    /// Captured IPv4 routes; host bits are cleared, prefix lengths above 32 are clamped.
    pub fn ipv4(routes: impl IntoIterator<Item = (Ipv4Addr, u8)>) -> Self {
        let routes = routes
            .into_iter()
            .map(|(addr, len)| {
                let len = len.min(32);
                (u32::from(addr) & mask(len), len)
            })
            .collect();
        Self { routes }
    }

    /// The captured routes as installed, for echoing the facts a verdict used.
    pub fn routes(&self) -> impl ExactSizeIterator<Item = (Ipv4Addr, u8)> + '_ {
        self.routes
            .iter()
            .map(|&(addr, len)| (Ipv4Addr::from(addr), len))
    }

    /// The largest sub-prefixes of `base/len` that no route captures, in address order.
    fn gaps(&self, base: u32, len: u8, out: &mut Vec<(u32, u8)>) {
        if self.covers(base, len) {
            return;
        }
        let touched = self
            .routes
            .iter()
            .any(|&(route, rlen)| overlaps((route, rlen), (base, len)));
        if !touched || len == 32 {
            out.push((base, len));
            return;
        }
        let bit = 1u32 << (31 - u32::from(len));
        self.gaps(base, len + 1, out);
        self.gaps(base | bit, len + 1, out);
    }

    /// True when the union of routes holds the whole prefix; splits only where a route lies inside.
    fn covers(&self, base: u32, len: u8) -> bool {
        if self.routes.iter().any(|&route| inside(route, (base, len))) {
            return true;
        }
        let split = self
            .routes
            .iter()
            .any(|&route| route.1 > len && inside((base, len), route));
        if !split || len == 32 {
            return false;
        }
        let bit = 1u32 << (31 - u32::from(len));
        self.covers(base, len + 1) && self.covers(base | bit, len + 1)
    }
}

fn mask(len: u8) -> u32 {
    u32::MAX.checked_shl(32 - u32::from(len)).unwrap_or(0)
}

fn inside(outer: (u32, u8), inner: (u32, u8)) -> bool {
    inner.1 >= outer.1 && inner.0 & mask(outer.1) == outer.0
}

fn overlaps(a: (u32, u8), b: (u32, u8)) -> bool {
    let len = a.1.min(b.1);
    a.0 & mask(len) == b.0 & mask(len)
}

/// honk's own `geoip:private` IPv4 ranges (honk-core `routing/geo.rs:584-603` at the pin); honk never
/// reads the dat for this code. Its IPv6 ranges cannot reach the IPv4-only TUN.
pub(super) const PRIVATE_V4: [([u8; 4], u8); 15] = [
    ([10, 0, 0, 0], 8),
    ([100, 64, 0, 0], 10),
    ([127, 0, 0, 0], 8),
    ([169, 254, 0, 0], 16),
    ([172, 16, 0, 0], 12),
    ([192, 0, 0, 0], 24),
    ([192, 0, 2, 0], 24),
    ([192, 88, 99, 0], 24),
    ([192, 168, 0, 0], 16),
    ([198, 18, 0, 0], 15),
    ([198, 51, 100, 0], 24),
    ([203, 0, 113, 0], 24),
    ([224, 0, 0, 0], 4),
    ([240, 0, 0, 0], 4),
    ([255, 255, 255, 255], 32),
];

/// The local network: RFC 1918 and link-local. Other uncaptured space (`0/8`, loopback,
/// multicast, reserved, or a public prefix the platform left out) is `outside_capture`.
const LAN_V4: [(u32, u8); 4] = [
    (0x0a00_0000, 8),
    (0xa9fe_0000, 16),
    (0xac10_0000, 12),
    (0xc0a8_0000, 16),
];

/// Reachability of one rule's positive destination alternatives.
#[derive(Default)]
pub(super) struct Scope {
    /// Some alternative reaches the TUN.
    pub reachable: bool,
    /// Some alternative depends on a capture or network list nobody supplied.
    pub unknown: bool,
    pub v6: bool,
    /// Uncaptured space inside the local network.
    pub lan: bool,
    /// Uncaptured space outside it.
    pub other: bool,
}

impl Scope {
    /// One network, judged as `dip(<cidr>)` is.
    pub fn net(&mut self, ingress: Option<&Ingress>, addr: IpAddr, len: u8) {
        match addr {
            IpAddr::V6(_) => self.v6 = true,
            IpAddr::V4(addr) => self.v4(ingress, addr, len.min(32)),
        }
    }

    fn v4(&mut self, ingress: Option<&Ingress>, addr: Ipv4Addr, len: u8) {
        let Some(ingress) = ingress else {
            self.unknown = true;
            return;
        };
        let base = u32::from(addr) & mask(len);
        let mut gaps = Vec::new();
        ingress.gaps(base, len, &mut gaps);
        self.reachable |= gaps != [(base, len)];
        for gap in gaps {
            self.lan |= LAN_V4.iter().any(|&lan| overlaps(lan, gap));
            self.other |= !LAN_V4.iter().any(|&lan| inside(lan, gap));
        }
    }
}
