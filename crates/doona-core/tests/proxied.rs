//! Global through nodes, end to end as in `datapath.rs`: a subscription served from loopback
//! lists local SOCKS5 and Shadowsocks servers, and every Global flow must reach its destination
//! through the `Proxy` group's node, never directly. A VMess node on a plain listener stands for
//! an outbound without UDP: nothing but its TCP dials ever reach it.

mod common;

use common::client::Client;
use common::servers::{self, Seen};
use common::{TempDir, seqpacket_pair, socks, ss};
use doonacore::api::{Core, Options};
use doonacore::dto::Snapshot;
use doonacore::sys;
use honk_netstack::{FlowId, StreamError};
use std::net::{Ipv4Addr, SocketAddr};
use std::os::fd::AsRawFd;
use std::sync::atomic::Ordering::Relaxed;
use std::time::{Duration, Instant};

const CONFIG: &str =
    r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":["192.168.8.1"]}"#;
const RESOLVER: SocketAddr =
    SocketAddr::new(std::net::IpAddr::V4(Ipv4Addr::new(172, 19, 0, 2)), 53);
const WAIT: Duration = Duration::from_secs(5);

struct Setup {
    core: Core,
    client: Client,
    subscription: String,
    _dir: TempDir,
}

/// A core in Global with one subscription of `links`, started; its DNS goes to `upstreams`. The
/// `Proxy` group starts on `Auto`, which before any probe carries the first link.
fn setup(links: &[String], upstreams: Vec<SocketAddr>) -> Setup {
    let dir = TempDir::new("proxied");
    let options = Options {
        dns_upstreams: upstreams,
        ..Options::default()
    };
    let core = Core::with_options(dir.path().to_str().unwrap(), options).unwrap();
    let feed = servers::feed(links.join("\n"));
    let reply = core.call(
        "addSubscription",
        &format!(r#"{{"url":"http://{feed}/sub","name":null}}"#),
    );
    let reply: serde_json::Value = serde_json::from_str(&reply).unwrap();
    let subscription = reply["ok"]["id"].as_str().expect("added").to_owned();
    let (tun, peer) = seqpacket_pair();
    core.start(sys::dup_cloexec(tun.as_raw_fd()).unwrap(), CONFIG)
        .unwrap();
    drop(tun);
    Setup {
        core,
        client: Client::new(peer),
        subscription,
        _dir: dir,
    }
}

fn snapshot(core: &Core) -> Snapshot {
    serde_json::from_str(&core.snapshot(-1)).unwrap()
}

fn call_ok(core: &Core, command: &str, args: &str) {
    assert_eq!(core.call(command, args), r#"{"ok":{}}"#, "{command}");
}

/// Points the `Proxy` group at the node named `name`.
fn select(core: &Core, name: &str) {
    let catalog = snapshot(core).catalog.unwrap();
    let member = catalog.groups[0]
        .members
        .iter()
        .find(|m| m.name == name)
        .expect("a member of Proxy");
    let args = format!(r#"{{"group":"g-Proxy","memberId":"{}"}}"#, member.id);
    call_ok(core, "select", &args);
}

fn socks_link(addr: SocketAddr, name: &str) -> String {
    format!("socks5://{addr}#{name}")
}

/// A VMess node on `addr`, which may be a plain listener: VMess carries no UDP.
fn vmess_link(addr: SocketAddr, name: &str) -> String {
    use base64::Engine;
    let json = format!(
        r#"{{"v":"2","ps":"{name}","add":"{}","port":"{}","id":"b831381d-6324-4d53-ad4f-8cda48b30811","aid":"0","net":"tcp","type":"none","tls":""}}"#,
        addr.ip(),
        addr.port()
    );
    format!(
        "vmess://{}",
        base64::engine::general_purpose::STANDARD.encode(json)
    )
}

fn pattern(len: usize) -> Vec<u8> {
    (0..len).map(|i| (i * 31 % 251) as u8).collect()
}

/// Sends `msg` on an open flow and waits for the echo.
fn ping(c: &mut Client, id: FlowId, msg: &[u8]) -> bool {
    let mut sent = 0;
    let mut got = Vec::new();
    c.until(WAIT, |c| {
        sent += c.write_now(id, &msg[sent..]);
        got.extend(c.read_now(id).unwrap_or_default());
        got == msg
    })
}

fn connections(seen: &Seen) -> usize {
    seen.connections.load(Relaxed)
}

#[test]
fn tcp_goes_through_the_selected_socks5_node() {
    let (echo, echo_seen) = servers::tcp_echo();
    let (proxy, proxy_seen) = socks::socks5();
    let mut s = setup(&[socks_link(proxy, "s5")], vec![]);
    let data = pattern(1 << 20);
    let id = s.client.connect(echo);
    assert!(s.client.exchange(id, &data).unwrap() == data);
    assert_eq!(connections(&proxy_seen), 1);
    assert_eq!(connections(&echo_seen), 1);
}

#[test]
fn tcp_goes_through_a_shadowsocks_node() {
    let (echo, _) = servers::tcp_echo();
    let (proxy, proxy_seen) = socks::socks5();
    let (ss_addr, ss_seen) = ss::shadowsocks();
    let links = [ss::link(ss_addr, "ss"), socks_link(proxy, "s5")];
    let mut s = setup(&links, vec![]);
    let data = pattern(1 << 20);
    let id = s.client.connect(echo);
    assert!(s.client.exchange(id, &data).unwrap() == data);
    assert_eq!(connections(&ss_seen), 1);
    assert_eq!(connections(&proxy_seen), 0);
}

#[test]
fn direct_leaves_the_nodes_alone() {
    let (echo, echo_seen) = servers::tcp_echo();
    let (proxy, proxy_seen) = socks::socks5();
    let mut s = setup(&[socks_link(proxy, "s5")], vec![]);
    call_ok(&s.core, "setMode", r#"{"mode":"Direct"}"#);
    let id = s.client.connect(echo);
    assert_eq!(s.client.exchange(id, b"direct").unwrap(), b"direct");
    assert_eq!(connections(&echo_seen), 1);
    assert_eq!(connections(&proxy_seen), 0);
}

#[test]
fn a_mode_change_applies_to_new_flows_only() {
    let (echo, echo_seen) = servers::tcp_echo();
    let (proxy, proxy_seen) = socks::socks5();
    let mut s = setup(&[socks_link(proxy, "s5")], vec![]);
    let proxied = s.client.connect(echo);
    assert!(ping(&mut s.client, proxied, b"one"));
    call_ok(&s.core, "setMode", r#"{"mode":"Direct"}"#);
    let direct = s.client.connect(echo);
    assert!(ping(&mut s.client, direct, b"two"));
    assert_eq!(connections(&proxy_seen), 1, "the new flow went direct");
    assert_eq!(connections(&echo_seen), 2);
    // The open flow keeps its node.
    assert!(ping(&mut s.client, proxied, b"three"));
    call_ok(&s.core, "setMode", r#"{"mode":"Global"}"#);
    let again = s.client.connect(echo);
    assert!(ping(&mut s.client, again, b"four"));
    assert_eq!(connections(&proxy_seen), 2);
    assert!(ping(&mut s.client, direct, b"five"));
    assert_eq!(connections(&proxy_seen), 2);
}

#[test]
fn a_selection_change_applies_to_new_flows_only() {
    let (echo, _) = servers::tcp_echo();
    let (proxy, proxy_seen) = socks::socks5();
    let (ss_addr, ss_seen) = ss::shadowsocks();
    let links = [socks_link(proxy, "s5"), ss::link(ss_addr, "ss")];
    let mut s = setup(&links, vec![]);
    let first = s.client.connect(echo);
    assert!(ping(&mut s.client, first, b"one"));
    select(&s.core, "ss");
    let second = s.client.connect(echo);
    assert!(ping(&mut s.client, second, b"two"));
    assert_eq!(connections(&ss_seen), 1);
    assert!(ping(&mut s.client, first, b"three"));
    assert_eq!(
        connections(&proxy_seen),
        1,
        "the open flow stayed on its node"
    );
}

#[test]
fn a_failed_dial_resets_the_flow_at_once() {
    let (echo, echo_seen) = servers::tcp_echo();
    let mut s = setup(&[socks_link(servers::closed_port(), "gone")], vec![]);
    let begun = Instant::now();
    let id = s.client.connect(echo);
    assert!(s.client.until(WAIT, |c| c.read_now(id).is_err()));
    assert!(matches!(
        s.client.read_now(id),
        Err(StreamError::Reset | StreamError::Unreachable)
    ));
    assert!(
        begun.elapsed() < Duration::from_secs(1),
        "{:?}",
        begun.elapsed()
    );
    assert_eq!(connections(&echo_seen), 0, "never sent direct");
}

#[test]
fn udp_goes_through_a_node_that_carries_it() {
    let (udp, udp_seen) = servers::udp_echo();
    let (proxy, proxy_seen) = socks::socks5();
    let mut s = setup(&[socks_link(proxy, "s5")], vec![]);
    for i in 0..3u8 {
        s.client.send_udp(udp, &[i; 1200]);
        assert_eq!(s.client.recv_udp(udp, WAIT), Some(vec![i; 1200]));
    }
    assert_eq!(proxy_seen.datagrams.load(Relaxed), 3);
    assert_eq!(udp_seen.datagrams.load(Relaxed), 3);
    assert_eq!(snapshot(&s.core).traffic.active_flows, 1);
}

#[test]
fn udp_is_dropped_through_a_node_without_it() {
    let (udp, udp_seen) = servers::udp_echo();
    let (stub, stub_seen) = servers::tcp_echo();
    let mut s = setup(&[vmess_link(stub, "vm")], vec![]);
    s.client.send_udp(udp, b"quic");
    assert!(s.client.recv_udp(udp, Duration::from_millis(300)).is_none());
    assert_eq!(udp_seen.datagrams.load(Relaxed), 0);
    assert_eq!(connections(&stub_seen), 0);
    assert_eq!(snapshot(&s.core).traffic.active_flows, 0);
}

fn dns_over_udp(c: &mut Client, q: &[u8]) -> Vec<u8> {
    c.send_udp(RESOLVER, q);
    let a = c.recv_udp(RESOLVER, WAIT).expect("no DNS answer");
    assert_eq!(&a[..2], &q[..2], "answer id");
    a
}

#[test]
fn dns_goes_through_the_node_over_tcp() {
    let (resolver, resolver_seen) = servers::dns_server();
    let (proxy, proxy_seen) = socks::socks5();
    let mut s = setup(&[socks_link(proxy, "s5")], vec![resolver]);
    let a = dns_over_udp(&mut s.client, &servers::query(1, "a.example", None));
    assert_eq!(servers::answers_and_rcode(&a), (1, 0));
    assert_eq!(resolver_seen.datagrams.load(Relaxed), 1);
    assert_eq!(
        connections(&proxy_seen),
        1,
        "the query went through the node"
    );
    // Private DNS to the virtual resolver is still reset at once.
    let begun = Instant::now();
    let id = s.client.connect(SocketAddr::new(RESOLVER.ip(), 853));
    assert!(s.client.until(WAIT, |c| c.read_now(id).is_err()));
    assert!(begun.elapsed() < Duration::from_secs(1));
}

#[test]
fn a_global_flow_without_a_node_is_refused_never_sent_direct() {
    let (echo, echo_seen) = servers::tcp_echo();
    let (udp, udp_seen) = servers::udp_echo();
    let (resolver, resolver_seen) = servers::dns_server();
    let (proxy, _) = socks::socks5();
    let mut s = setup(&[socks_link(proxy, "s5")], vec![resolver]);
    let args = format!(r#"{{"id":"{}"}}"#, s.subscription);
    call_ok(&s.core, "removeSubscription", &args);
    let id = s.client.connect(echo);
    assert!(s.client.until(WAIT, |c| c.read_now(id).is_err()));
    s.client.send_udp(udp, b"hello");
    assert!(s.client.recv_udp(udp, Duration::from_millis(300)).is_none());
    let begun = Instant::now();
    let a = dns_over_udp(&mut s.client, &servers::query(9, "a.example", None));
    assert_eq!(servers::answers_and_rcode(&a), (0, 2), "SERVFAIL");
    assert!(begun.elapsed() < Duration::from_secs(1));
    assert_eq!(connections(&echo_seen), 0);
    assert_eq!(udp_seen.datagrams.load(Relaxed), 0);
    assert_eq!(resolver_seen.datagrams.load(Relaxed), 0);
    assert!(snapshot(&s.core).running, "the tunnel stays up");
}
