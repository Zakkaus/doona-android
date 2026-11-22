//! `bootstrapDns`: subscription and proxy-server host names are resolved by the resolvers
//! `start` passes, over the direct path, or by the fallback when there are none; before the first
//! `start` and after `stop` the last list serves. A loopback UDP resolver answers every name with
//! 127.0.0.1, and none of the names exists anywhere else, so a lookup that went to the system
//! resolver would fail. honk's hooks are per process, so these tests run one at a time.

mod common;

use common::client::Client;
use common::servers::{self, Seen};
use common::{TempDir, seqpacket_pair, socks};
use doonacore::api::{Core, Options};
use doonacore::dto::ErrorCode;
use doonacore::sys;
use std::net::{Ipv4Addr, SocketAddr};
use std::os::fd::AsRawFd;
use std::sync::atomic::Ordering::Relaxed;
use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, Instant};

fn serial() -> MutexGuard<'static, ()> {
    static SERIAL: Mutex<()> = Mutex::new(());
    SERIAL.lock().unwrap_or_else(|e| e.into_inner())
}

/// A core whose resolvers listen on `port`, with `fallback` when `start` names none.
fn new_core(dir: &TempDir, port: u16, fallback: Vec<SocketAddr>) -> Core {
    let options = Options {
        bootstrap_port: port,
        bootstrap_fallback: fallback,
        ..Options::default()
    };
    Core::with_options(dir.path().to_str().unwrap(), options).unwrap()
}

fn subscribe(core: &Core, url: &str) -> Result<(), ErrorCode> {
    let reply = core.call(
        "addSubscription",
        &format!(r#"{{"url":"{url}","name":null}}"#),
    );
    let reply: serde_json::Value = serde_json::from_str(&reply).unwrap();
    match reply["error"]["code"].as_str() {
        None => Ok(()),
        Some(code) => Err(serde_json::from_value(code.into()).unwrap()),
    }
}

fn config(bootstrap: &str) -> String {
    format!(r#"{{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":{bootstrap}}}"#)
}

fn try_start(core: &Core, bootstrap: &str) -> Result<Client, ErrorCode> {
    let (tun, peer) = seqpacket_pair();
    let tun = sys::dup_cloexec(tun.as_raw_fd()).unwrap();
    core.start(tun, &config(bootstrap))?;
    Ok(Client::new(peer))
}

fn start(core: &Core, bootstrap: &str) -> Client {
    try_start(core, bootstrap).unwrap()
}

fn queries(seen: &Seen) -> usize {
    seen.datagrams.load(Relaxed)
}

/// A UDP port with nothing listening: a query there is refused at once.
fn dead_resolver() -> SocketAddr {
    let s = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
    s.local_addr().unwrap()
}

/// Proxy-server names resolve through the resolvers `start` names: a Global flow reaches its
/// destination through a SOCKS5 node known only by name.
#[test]
fn node_hosts_are_resolved_with_the_start_config() {
    let _s = serial();
    let (resolver, seen) = servers::udp_resolver();
    let (proxy, proxy_seen) = socks::socks5();
    let (echo, _) = servers::tcp_echo();
    let feed = servers::feed(format!("socks5://proxy.doona.test:{}#s5", proxy.port()));
    let dir = TempDir::new("boot-node");
    let core = new_core(&dir, resolver.port(), vec![dead_resolver()]);
    subscribe(&core, &format!("http://{feed}/sub")).unwrap();
    let mut client = start(&core, r#"["127.0.0.1"]"#);
    let id = client.connect(echo);
    assert_eq!(client.exchange(id, b"by name").unwrap(), b"by name");
    assert_eq!(proxy_seen.connections.load(Relaxed), 1);
    assert!(queries(&seen) >= 1);
}

/// A TLS node with ECH on and no ECH configuration looks up its server name's HTTPS record, and
/// asks the first resolver `start` names for it, never the system's.
#[test]
fn ech_discovery_asks_the_start_config_resolvers() {
    let _s = serial();
    let (resolver, seen) = servers::udp_resolver();
    // Not a TLS server: the handshake fails once the lookup is done.
    let (stub, _) = servers::tcp_echo();
    let link = format!("trojan://pw@{stub}?security=tls&sni=ech.doona.test&ech=1#tj");
    let feed = servers::feed(link);
    let dir = TempDir::new("boot-ech");
    let core = new_core(&dir, resolver.port(), vec![dead_resolver()]);
    subscribe(&core, &format!("http://{feed}/sub")).unwrap();
    let mut client = start(&core, r#"["127.0.0.1"]"#);
    let id = client.connect(servers::closed_port());
    assert!(client.until(Duration::from_secs(10), |c| c.read_now(id).is_err()));
    assert_eq!(seen.https.load(Relaxed), 1);
}

/// With no resolver in the config, the fallback list serves.
#[test]
fn the_fallback_serves_when_start_names_no_resolver() {
    let _s = serial();
    let (resolver, seen) = servers::udp_resolver();
    let (proxy, proxy_seen) = socks::socks5();
    let (echo, _) = servers::tcp_echo();
    let feed = servers::feed(format!("socks5://proxy.doona.test:{}#s5", proxy.port()));
    let dir = TempDir::new("boot-fallback");
    // The port of the configured resolvers is dead; the fallback is the live resolver.
    let core = new_core(&dir, dead_resolver().port(), vec![resolver]);
    subscribe(&core, &format!("http://{feed}/sub")).unwrap();
    let mut client = start(&core, "[]");
    let id = client.connect(echo);
    assert_eq!(client.exchange(id, b"fallback").unwrap(), b"fallback");
    assert_eq!(proxy_seen.connections.load(Relaxed), 1);
    assert!(queries(&seen) >= 1);
}

/// Before any `start` the fallback resolves a subscription's host; after `stop` the list the last
/// `start` named does.
#[test]
fn subscription_hosts_use_the_fallback_then_the_last_list() {
    let _s = serial();
    let (resolver, seen) = servers::udp_resolver();
    let feed = servers::feed("socks5://127.0.0.1:1#s5".to_owned());
    let by_name = format!("http://feed.doona.test:{}/sub", feed.port());
    let dir = TempDir::new("boot-feed");
    let core = new_core(&dir, resolver.port(), vec![resolver]);
    assert_eq!(subscribe(&core, &by_name), Ok(()));
    assert!(queries(&seen) >= 1);

    let dir = TempDir::new("boot-feed-last");
    let core = new_core(&dir, resolver.port(), vec![dead_resolver()]);
    assert_eq!(subscribe(&core, &by_name), Err(ErrorCode::FetchFailed));
    assert_eq!(core.call("setMode", r#"{"mode":"Direct"}"#), r#"{"ok":{}}"#);
    let _client = start(&core, r#"["127.0.0.1"]"#);
    core.stop();
    let before = queries(&seen);
    assert_eq!(subscribe(&core, &by_name), Ok(()));
    assert!(queries(&seen) > before);
}

/// Only an accepted `start` changes the resolvers: one refused with `no_nodes` leaves the running
/// generation's list in use, and a list with an entry that is not an address is
/// `invalid_argument`, never the fallback.
#[test]
fn a_refused_start_keeps_the_running_list() {
    let _s = serial();
    let (resolver, seen) = servers::udp_resolver();
    let feed = servers::feed("socks5://127.0.0.1:1#s5".to_owned());
    let by_name = format!("http://feed.doona.test:{}/sub", feed.port());
    let dir = TempDir::new("boot-refused");
    let core = new_core(&dir, resolver.port(), vec![resolver]);
    assert_eq!(core.call("setMode", r#"{"mode":"Direct"}"#), r#"{"ok":{}}"#);
    let _running = start(&core, r#"["127.0.0.1"]"#);
    // Global with no node: refused before the running generation is touched. Nothing answers
    // on 127.0.0.2.
    assert_eq!(core.call("setMode", r#"{"mode":"Global"}"#), r#"{"ok":{}}"#);
    assert_eq!(
        try_start(&core, r#"["127.0.0.2"]"#).err(),
        Some(ErrorCode::NoNodes)
    );
    let before = queries(&seen);
    assert_eq!(subscribe(&core, &by_name), Ok(()));
    assert!(queries(&seen) > before);
    for junk in [r#"["not an address"]"#, r#"["127.0.0.2", "dns.example"]"#] {
        assert_eq!(
            try_start(&core, junk).err(),
            Some(ErrorCode::InvalidArgument)
        );
    }
}

/// A subscription host is classified by what it resolves to, never by its name: resolved to an
/// address on the local network that never connects, the fetch is `local_network`; resolved to a
/// loopback port with nothing on it, `fetch_failed`. Linux refuses TCP to a multicast address,
/// which Android counts as local, at once, so no network outcome can change either.
#[test]
fn a_subscription_host_on_the_local_network_is_local_network() {
    let _s = serial();
    let closed = servers::closed_port();
    let cases = [
        (Ipv4Addr::new(224, 0, 0, 1), ErrorCode::LocalNetwork),
        (Ipv4Addr::LOCALHOST, ErrorCode::FetchFailed),
    ];
    for (answer, want) in cases {
        let (resolver, seen) = servers::udp_resolver_to(answer);
        let dir = TempDir::new("boot-lan");
        let options = Options {
            bootstrap_port: resolver.port(),
            bootstrap_fallback: vec![resolver],
            subscription_bound: Duration::from_secs(2),
            ..Options::default()
        };
        let core = Core::with_options(dir.path().to_str().unwrap(), options).unwrap();
        let url = format!("http://192-168-8-161.lan.doona.test:{}/sub", closed.port());
        assert_eq!(subscribe(&core, &url), Err(want), "{answer}");
        assert!(queries(&seen) >= 1);
    }
}

/// A node known only by name is classified by what the name resolved to: once it resolves to the
/// local network and its dial fails, the snapshot carries the warning. Linux refuses TCP to a
/// multicast address, which Android counts as local, at once.
#[test]
fn a_node_name_that_resolves_to_the_local_network_warns_when_its_dial_fails() {
    let _s = serial();
    let (resolver, _) = servers::udp_resolver_to(Ipv4Addr::new(224, 0, 0, 1));
    let (echo, _) = servers::tcp_echo();
    let feed = servers::feed("socks5://nas.lan.doona.test:1080#nas".to_owned());
    let dir = TempDir::new("boot-lan-node");
    let core = new_core(&dir, resolver.port(), vec![dead_resolver()]);
    subscribe(&core, &format!("http://{feed}/sub")).unwrap();
    let mut client = start(&core, r#"["127.0.0.1"]"#);
    let warning = |core: &Core| {
        let snapshot: serde_json::Value = serde_json::from_str(&core.snapshot(-1)).unwrap();
        snapshot["warning"].clone()
    };
    assert_eq!(warning(&core), serde_json::Value::Null);
    let id = client.connect(echo);
    assert!(client.until(Duration::from_secs(15), |c| c.read_now(id).is_err()));
    assert_eq!(warning(&core), "local_network");
}

/// Tests the latency of every group and gives each node's name and error code once it ended.
fn probed(core: &Core) -> Vec<(String, serde_json::Value)> {
    let reply = core.call("testLatency", r#"{"group":null}"#);
    assert_eq!(reply, r#"{"ok":{}}"#);
    let begun = Instant::now();
    loop {
        let snapshot: serde_json::Value = serde_json::from_str(&core.snapshot(-1)).unwrap();
        let groups = snapshot["catalog"]["groups"].as_array().unwrap();
        let nodes = groups[1]["members"].as_array().unwrap();
        let tested = nodes
            .iter()
            .all(|m| m["failed"] == true || m["latencyMs"].is_number());
        if groups.iter().all(|g| g["testing"] == false) && tested {
            let name = |m: &serde_json::Value| m["name"].as_str().unwrap().to_owned();
            return nodes
                .iter()
                .map(|m| (name(m), m["error"].clone()))
                .collect();
        }
        assert!(begun.elapsed() < Duration::from_secs(20), "{snapshot}");
        std::thread::sleep(Duration::from_millis(20));
    }
}

/// A probe is classified by the address its dial to the node chose, never by what the node's name
/// resolved to before (ARCH D26): refused at the local address its name resolves to, it carries
/// `local_network`; once the name no longer resolves, it fails before it dials the node and
/// carries no code.
#[test]
fn a_probe_counts_the_address_it_dialed_not_an_earlier_lookup() {
    let _s = serial();
    let (resolver, _) = servers::udp_resolver_to(Ipv4Addr::new(224, 0, 0, 1));
    let feed = servers::feed("socks5://nas.lan.doona.test:1080#nas".to_owned());
    let dir = TempDir::new("boot-lan-probe");
    let options = Options {
        bootstrap_port: resolver.port(),
        bootstrap_fallback: vec![resolver],
        probe_url: format!("http://{}/generate_204", servers::closed_port()),
        ..Options::default()
    };
    let core = Core::with_options(dir.path().to_str().unwrap(), options).unwrap();
    subscribe(&core, &format!("http://{feed}/sub")).unwrap();
    let nas = |code: serde_json::Value| vec![("nas".to_owned(), code)];
    assert_eq!(probed(&core), nas("local_network".into()));
    // The resolvers `start` names stay in use after `stop`; nothing answers on 127.0.0.2.
    drop(start(&core, r#"["127.0.0.2"]"#));
    core.stop();
    assert_eq!(probed(&core), nas(serde_json::Value::Null));
}

/// A probe whose own target does not resolve never dialed its node: it carries no code, although
/// the node is on the local network.
#[test]
fn a_probe_whose_target_does_not_resolve_carries_no_code() {
    let _s = serial();
    let dead = dead_resolver();
    let feed = servers::feed("socks5://224.0.0.1:1080#lan".to_owned());
    let dir = TempDir::new("boot-probe-target");
    let options = Options {
        bootstrap_port: dead.port(),
        bootstrap_fallback: vec![dead],
        probe_url: "http://probe.doona.test/generate_204".to_owned(),
        ..Options::default()
    };
    let core = Core::with_options(dir.path().to_str().unwrap(), options).unwrap();
    subscribe(&core, &format!("http://{feed}/sub")).unwrap();
    assert_eq!(
        probed(&core),
        vec![("lan".to_owned(), serde_json::Value::Null)]
    );
}

/// A `.local` name that only mDNS publishes is not resolved in v0.1 (ARCH D26): unicast DNS does
/// not know it, so the fetch fails before it dials anything and keeps `fetch_failed`.
#[test]
fn a_local_name_unicast_dns_cannot_resolve_keeps_fetch_failed() {
    let _s = serial();
    let dead = dead_resolver();
    let dir = TempDir::new("boot-mdns");
    let core = new_core(&dir, dead.port(), vec![dead]);
    assert_eq!(
        subscribe(&core, "http://nas.local:8080/sub"),
        Err(ErrorCode::FetchFailed)
    );
}
