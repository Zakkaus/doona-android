//! The host names the core resolves itself: proxy servers (through honk's node-host hook),
//! latency-probe hosts and subscription hosts (core-api.md, "start config"). Each is asked of the
//! underlying network's resolvers that `start` passes as `bootstrapDns`, in order, over the direct
//! path (doona is outside its own VPN); with none, of 1.1.1.1 and 8.8.8.8. The list of the last
//! accepted `start` stays in use after `stop`; before the first `start` the fallback is used. A list
//! with an entry that is not an address is refused, never replaced. honk keeps its hooks
//! per process, so the list is per process too. `/etc/resolv.conf` is never read: the hook claims
//! every name, and the record lookups honk makes itself (a TLS node's ECH configuration, from its
//! server name's HTTPS record) go to honk's global resolver, which is always the first of the list.

use honk_outbound::bootstrap::{BootstrapResolver, NodeHostLookup, NodeHostResolver};
use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV6};
use std::pin::Pin;
use std::sync::{Arc, Once, RwLock};

/// The resolvers asked when `start` gave none.
pub const FALLBACK: [SocketAddr; 2] = [
    SocketAddr::new(IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), 53),
    SocketAddr::new(IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)), 53),
];

/// The resolvers in use, in order; none until a core sets them, and then the fallback serves.
static SERVERS: RwLock<Vec<SocketAddr>> = RwLock::new(Vec::new());

/// Makes `servers` the resolvers every lookup asks from now on, and the first of them honk's
/// global resolver: without one, honk would read `/etc/resolv.conf` for the records it looks up.
pub(crate) fn use_servers(servers: Vec<SocketAddr>) {
    let first = servers.first().unwrap_or(&FALLBACK[0]).to_string();
    honk_outbound::bootstrap::set_global(BootstrapResolver::parse(&first));
    *SERVERS.write().unwrap_or_else(|e| e.into_inner()) = servers;
}

/// `start`'s `bootstrapDns` on `port`, or `fallback` when it is empty; `None` when an entry is not
/// an address. An IPv6 entry may carry a scope (`fe80::1%wlan0`), by number or interface name; an
/// IPv4 one has none.
pub(crate) fn servers(
    config: &[String],
    port: u16,
    fallback: &[SocketAddr],
) -> Option<Vec<SocketAddr>> {
    if config.is_empty() {
        return Some(fallback.to_vec());
    }
    config.iter().map(|s| server(s, port)).collect()
}

fn server(text: &str, port: u16) -> Option<SocketAddr> {
    let (ip, scope) = match text.trim().split_once('%') {
        Some((ip, scope)) => (ip, Some(scope)),
        None => (text.trim(), None),
    };
    match ip.parse::<IpAddr>().ok()? {
        IpAddr::V4(a) => scope.is_none().then(|| SocketAddr::new(a.into(), port)),
        IpAddr::V6(a) => {
            let scope_id = match scope {
                None => 0,
                Some(s) => s.parse().ok().or_else(|| crate::sys::interface_index(s))?,
            };
            Some(SocketAddrV6::new(a, port, 0, scope_id).into())
        }
    }
}

/// The addresses of `host`, from the first resolver that has any; an address literal is its own.
/// Whether they are on the local network is noted, so a failed dial to `host` can say so.
pub(crate) async fn lookup(host: &str) -> io::Result<Vec<IpAddr>> {
    let bare = host.trim_start_matches('[').trim_end_matches(']');
    if let Ok(ip) = bare.parse::<IpAddr>() {
        return Ok(vec![ip]);
    }
    let mut servers = SERVERS.read().unwrap_or_else(|e| e.into_inner()).clone();
    if servers.is_empty() {
        servers = FALLBACK.to_vec();
    }
    for server in servers {
        let Some(resolver) = BootstrapResolver::parse(&server.to_string()) else {
            continue;
        };
        if let Ok(ips) = resolver.query(bare).await
            && !ips.is_empty()
        {
            crate::localnet::note(bare, &ips);
            return Ok(ips);
        }
    }
    Err(io::ErrorKind::NotFound.into())
}

/// Hands honk's proxy-server and probe-host lookups to [`lookup`]. honk keeps these hooks per
/// process, so they are installed once.
pub(crate) fn install() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        honk_outbound::bootstrap::set_node_host_resolver(Some(Arc::new(NodeHosts)));
        honk_outbound::urltest::set_urltest_resolver(Arc::new(probe_host));
    });
}

struct NodeHosts;

impl NodeHostResolver for NodeHosts {
    fn lookup(&self, host: &str) -> Option<NodeHostLookup> {
        let host = host.to_owned();
        Some(Box::pin(async move { lookup(&host).await }))
    }
}

type Resolved = Pin<Box<dyn Future<Output = anyhow::Result<Vec<SocketAddr>>> + Send>>;

fn probe_host(host: String, port: u16) -> Resolved {
    Box::pin(async move {
        let ips = lookup(&host).await?;
        Ok(ips
            .into_iter()
            .map(|ip| SocketAddr::new(ip, port))
            .collect())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn entries_are_addresses_on_the_port_or_refused() {
        let config = ["192.168.8.1", " 10.0.0.1 ", "fe80::1%2"].map(String::from);
        let got = servers(&config, 53, &FALLBACK);
        let want: Vec<SocketAddr> = vec![
            "192.168.8.1:53".parse().unwrap(),
            "10.0.0.1:53".parse().unwrap(),
            SocketAddrV6::new("fe80::1".parse().unwrap(), 53, 0, 2).into(),
        ];
        assert_eq!(got, Some(want));
        assert_eq!(servers(&[], 53, &FALLBACK), Some(FALLBACK.to_vec()));
        let refused = [
            "x",
            "fe80::1%no-such-interface",
            "",
            "127.0.0.1%junk",
            "10.0.0.1%2",
        ];
        for junk in refused {
            let config = ["10.0.0.1".to_owned(), junk.to_owned()];
            assert_eq!(servers(&config, 53, &FALLBACK), None, "{junk:?}");
        }
        let one = servers(&["10.0.0.1".to_owned()], 5353, &FALLBACK).unwrap();
        assert_eq!(one[0].port(), 5353);
    }
}
