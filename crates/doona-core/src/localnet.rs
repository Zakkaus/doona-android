//! Which addresses Android counts as the local network (ARCH D26). From Android 17 an app that
//! targets SDK 37 reaches them only with `ACCESS_LOCAL_NETWORK`; without it a TCP connect times
//! out and a UDP send fails with `EPERM`, so a refused dial looks like any unreachable server. A
//! dial that failed on its way to one of these addresses is reported as `local_network`, so the app
//! asks for the permission only then.
//!
//! The platform decides per interface (AOSP Connectivity, `ConnectivityService`
//! `updateLocalNetworkAddresses` with `NetworkStackConstants.IPV4_LOCAL_PREFIXES` and
//! `MULTICAST_AND_BROADCAST_PREFIXES`): an IPv4 range below that holds one of the interface's own
//! addresses, each on-link IPv6 prefix of the interface, and multicast and broadcast. Loopback has
//! no network of its own and is never blocked. The core does not follow interfaces, so it counts
//! every IPv4 range on that list, CGNAT `100.64.0.0/10` included (a carrier that numbers the phone
//! from it makes the whole range local), and the IPv6 ranges that are on-link wherever they appear:
//! link-local and unique local. A global IPv6 address in the phone's own /64 is not counted. The
//! code is reported only after a dial failed, so counting too much costs at most one question.
//!
//! Host names are classified by the addresses they resolved to, never by how the name looks:
//! [`note`] keeps the names whose last lookup gave a local address.

use std::collections::HashSet;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::sync::{Mutex, MutexGuard};

/// Whether Android may count `ip` as an address on the local network.
pub(crate) fn is_local(ip: IpAddr) -> bool {
    match ip.to_canonical() {
        IpAddr::V4(a) => v4(a),
        IpAddr::V6(a) => v6(a),
    }
}

fn v4(a: Ipv4Addr) -> bool {
    let [first, second, ..] = a.octets();
    let cgnat = first == 100 && second & 0xc0 == 64;
    a.is_private() || a.is_link_local() || cgnat || a.is_multicast() || a.is_broadcast()
}

fn v6(a: Ipv6Addr) -> bool {
    a.is_unique_local() || a.is_unicast_link_local() || a.is_multicast()
}

/// Names whose last lookup gave at least one local address. It holds names only, never logged, and
/// only the names the core looks up itself: proxy servers, subscription hosts and the probe host.
static LOCAL_HOSTS: Mutex<Option<HashSet<String>>> = Mutex::new(None);

fn hosts() -> MutexGuard<'static, Option<HashSet<String>>> {
    LOCAL_HOSTS.lock().unwrap_or_else(|e| e.into_inner())
}

fn key(host: &str) -> String {
    host.trim_end_matches('.').to_ascii_lowercase()
}

/// Records what a lookup of `host` gave, so a failed dial to it can be classified later.
pub(crate) fn note(host: &str, ips: &[IpAddr]) {
    let mut hosts = hosts();
    let hosts = hosts.get_or_insert_with(HashSet::new);
    if ips.iter().any(|&ip| is_local(ip)) {
        hosts.insert(key(host));
    } else {
        hosts.remove(&key(host));
    }
}

/// Whether a dial to `host` (an address literal, bracketed or not, or a name) went to the local
/// network: a literal by its address, a name by its last lookup.
pub(crate) fn host_is_local(host: &str) -> bool {
    let bare = host.trim_start_matches('[').trim_end_matches(']');
    if let Ok(ip) = bare.parse::<IpAddr>() {
        return is_local(ip);
    }
    hosts().as_ref().is_some_and(|h| h.contains(&key(bare)))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn local(text: &str) -> bool {
        is_local(text.parse().unwrap())
    }

    #[test]
    fn private_link_local_cgnat_and_multicast_ranges_are_local() {
        let v4 = [
            "10.0.0.1",
            "10.255.255.254",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.8.161",
            "169.254.1.1",
            "100.64.0.1",
            "100.127.255.254",
            "224.0.0.251",
            "239.255.255.250",
            "255.255.255.255",
        ];
        let v6 = [
            "fc00::1",
            "fd12:3456:789a::1",
            "fe80::1",
            "febf::1",
            "ff02::fb",
            // An IPv4 local address mapped into IPv6 is still that address.
            "::ffff:192.168.1.1",
        ];
        for text in v4.iter().chain(&v6) {
            assert!(local(text), "{text}");
        }
    }

    #[test]
    fn loopback_unspecified_and_public_addresses_are_not_local() {
        let not = [
            "127.0.0.1",
            "127.1.2.3",
            "::1",
            "0.0.0.0",
            "::",
            // Just outside the private ranges.
            "9.255.255.255",
            "11.0.0.1",
            "172.15.255.255",
            "172.32.0.1",
            "192.167.255.255",
            "192.169.0.1",
            "100.63.255.255",
            "100.128.0.1",
            "169.253.255.255",
            "1.1.1.1",
            "192.0.2.9",
            "203.0.113.7",
            "2001:db8::1",
            "2606:4700:4700::1111",
            "fec0::1",
            "::ffff:8.8.8.8",
        ];
        for text in not {
            assert!(!local(text), "{text}");
        }
    }

    #[test]
    fn literals_are_classified_by_address_and_names_by_their_last_lookup() {
        assert!(host_is_local("192.168.8.161"));
        assert!(host_is_local("[fd00::2]"));
        assert!(!host_is_local("[::1]"));
        assert!(!host_is_local("203.0.113.7"));
        // The name says nothing: only what it resolved to counts.
        let name = "192-168-8-161.sslip.localnet.test";
        assert!(!host_is_local(name));
        note(name, &["192.168.8.161".parse().unwrap()]);
        assert!(host_is_local(name));
        assert!(host_is_local("192-168-8-161.SSLIP.localnet.test."));
        note(name, &["203.0.113.7".parse().unwrap()]);
        assert!(!host_is_local(name));
        let mixed = "nas.localnet.test";
        note(
            mixed,
            &["2001:db8::1".parse().unwrap(), "10.0.0.2".parse().unwrap()],
        );
        assert!(host_is_local(mixed));
    }
}
