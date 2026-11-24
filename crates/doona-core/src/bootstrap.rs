//! The host names the core resolves itself: proxy servers (through honk's node-host hook),
//! latency-probe hosts and subscription hosts (core-api.md, "start config"). Each is asked of the
//! underlying network's resolvers in order, over the direct path (doona is outside its own VPN).
//! `start` uses 1.1.1.1 and 8.8.8.8 when `bootstrapDns` is empty. `setBootstrapDns` replaces
//! that list on a network change. Each lookup keeps its list until it finishes. `stop` leaves
//! the list in place; until the first start or update the fallback serves. honk keeps its hooks
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
/// The two stores have separate locks: a concurrent lookup can see either valid configuration.
pub(crate) fn use_servers(servers: Vec<SocketAddr>) {
    let first = servers.first().unwrap_or(&FALLBACK[0]).to_string();
    honk_outbound::bootstrap::set_global(BootstrapResolver::parse(&first));
    *SERVERS.write().unwrap_or_else(|e| e.into_inner()) = servers;
}

/// Resolver addresses on `port`. Only `start` supplies a fallback for an empty list.
/// IPv6 entries may carry a scope (`fe80::1%wlan0`), by number or interface name.
/// A scope whose interface disappeared is skipped; malformed addresses still reject the list.
pub(crate) fn servers(
    config: &[String],
    port: u16,
    fallback: Option<&[SocketAddr]>,
) -> Option<Vec<SocketAddr>> {
    if config.is_empty() {
        return fallback.map(<[SocketAddr]>::to_vec);
    }
    let mut resolvers = Vec::new();
    for text in config {
        let text = text.trim();
        let (ip, scope) = text
            .split_once('%')
            .map_or((text, None), |(ip, scope)| (ip, Some(scope)));
        let resolver = match (ip.parse::<IpAddr>().ok()?, scope) {
            (ip, None) => SocketAddr::new(ip, port),
            (IpAddr::V6(ip), Some(scope)) => {
                let Some(index) = scope
                    .parse()
                    .ok()
                    .or_else(|| crate::sys::interface_index(scope))
                else {
                    continue;
                };
                SocketAddrV6::new(ip, port, 0, index).into()
            }
            (IpAddr::V4(_), Some(_)) => return None,
        };
        resolvers.push(resolver);
    }
    (!resolvers.is_empty()).then_some(resolvers)
}

/// The addresses of `host`, from the first resolver that has any; an address literal is its own.
/// Inside a traced dial honk reports each query, so the dial can name the address it took
/// (`localnet::Trace`).
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
        Some(Box::pin(tracked_lookup(host, false)))
    }
}

async fn tracked_lookup(host: String, target: bool) -> io::Result<Vec<IpAddr>> {
    crate::localnet::lookup_started();
    let result = lookup(&host).await;
    crate::localnet::looked_up(target, result.as_deref().unwrap_or_default());
    result
}

type Resolved = Pin<Box<dyn Future<Output = anyhow::Result<Vec<SocketAddr>>> + Send>>;

fn probe_host(host: String, port: u16) -> Resolved {
    Box::pin(async move {
        let ips = tracked_lookup(host, true).await?;
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
        let got = servers(&config, 53, None);
        let want: Vec<SocketAddr> = vec![
            "192.168.8.1:53".parse().unwrap(),
            "10.0.0.1:53".parse().unwrap(),
            SocketAddrV6::new("fe80::1".parse().unwrap(), 53, 0, 2).into(),
        ];
        assert_eq!(got, Some(want));
        assert_eq!(servers(&[], 53, Some(&FALLBACK)), Some(FALLBACK.to_vec()));
        let refused = ["x", "", "127.0.0.1%junk", "10.0.0.1%2"];
        for junk in refused {
            let config = ["10.0.0.1".to_owned(), junk.to_owned()];
            assert_eq!(servers(&config, 53, None), None, "{junk:?}");
        }
        let one = servers(&["10.0.0.1".to_owned()], 5353, None).unwrap();
        assert_eq!(one[0].port(), 5353);
    }

    #[test]
    fn start_and_handoff_skip_a_lost_scope_but_reject_an_empty_result() {
        use crate::api::Core;
        use crate::dto::ErrorCode;
        use crate::testutil::{TempDir, tun};

        // Other unit tests replace the process-wide resolvers.
        const CHILD: &str = "DOONA_ISOLATED_SCOPED_DNS_TEST";
        if std::env::var_os(CHILD).is_none() {
            let status = std::process::Command::new(std::env::current_exe().unwrap())
                .args(["--exact", std::thread::current().name().unwrap()])
                .env(CHILD, "1")
                .status()
                .unwrap();
            assert!(status.success());
            return;
        }

        let dir = TempDir::new("lost-scope");
        let core = Core::new(dir.path().to_str().unwrap()).unwrap();
        assert_eq!(core.call("setMode", r#"{"mode":"Direct"}"#), r#"{"ok":{}}"#);
        let start = |list: &str| {
            let (fd, _peer) = tun();
            core.start(
                fd,
                &format!(
                    r#"{{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":{list}}}"#
                ),
            )
        };
        let assert_servers = |server: &str| {
            assert_eq!(
                *SERVERS.read().unwrap(),
                [server.parse::<SocketAddr>().unwrap()]
            );
        };
        assert_eq!(
            start(r#"["fe80::1%nosuch0"]"#),
            Err(ErrorCode::InvalidArgument)
        );
        start(r#"["fe80::1%nosuch0","1.1.1.1"]"#).unwrap();
        assert_servers("1.1.1.1:53");

        assert_eq!(
            core.call(
                "setBootstrapDns",
                r#"{"servers":["fe80::1%nosuch0","8.8.8.8"]}"#
            ),
            r#"{"ok":{}}"#,
        );
        assert_eq!(
            core.call("setBootstrapDns", r#"{"servers":["fe80::1%nosuch0"]}"#),
            r#"{"error":{"code":"invalid_argument"}}"#,
        );
        assert_servers("8.8.8.8:53");
    }
}
