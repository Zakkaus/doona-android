//! Global through nodes, end to end as in `datapath.rs`: a subscription served from loopback
//! lists local SOCKS5 and Shadowsocks servers, and every Global flow must reach its destination
//! through the `Proxy` group's node, never directly. A VMess node on a plain listener stands for
//! an outbound without UDP: nothing but its TCP dials ever reach it.

#[cfg(any(target_os = "linux", target_os = "android"))]
#[path = "proxied/bench.rs"]
mod bench;
mod common;

use common::client::Client;
use common::global::{Global, WAIT, pattern, ping, socks_link, vmess_link};
use common::servers::{self, Seen};
use common::{socks, ss};
use doonacore::api::Options;
use doonacore::dto::ErrorCode;
use honk_netstack::StreamError;
use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::sync::atomic::Ordering::Relaxed;
use std::time::{Duration, Instant};

const RESOLVER: SocketAddr =
    SocketAddr::new(std::net::IpAddr::V4(Ipv4Addr::new(172, 19, 0, 2)), 53);

struct Setup {
    g: Global,
    client: Client,
}

/// A started core in Global with one subscription of `links`; its DNS goes to `upstreams`.
fn setup(links: &[String], upstreams: Vec<SocketAddr>) -> Setup {
    let g = Global::new(links, upstreams);
    let client = g.start();
    Setup { g, client }
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
    s.g.call_ok("setMode", r#"{"mode":"Direct"}"#);
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
    s.g.call_ok("setMode", r#"{"mode":"Direct"}"#);
    let direct = s.client.connect(echo);
    assert!(ping(&mut s.client, direct, b"two"));
    assert_eq!(connections(&proxy_seen), 1, "the new flow went direct");
    assert_eq!(connections(&echo_seen), 2);
    // The open flow keeps its node.
    assert!(ping(&mut s.client, proxied, b"three"));
    s.g.call_ok("setMode", r#"{"mode":"Global"}"#);
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
    s.g.select("ss");
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
    let (gone, _socket) = servers::closed_port();
    let mut s = setup(&[socks_link(gone, "gone")], vec![]);
    let begun = Instant::now();
    let id = s.client.connect(echo);
    assert!(s.client.until(WAIT, |c| c.read_now(id).is_err()));
    assert!(matches!(
        s.client.read_now(id),
        Err(StreamError::Reset | StreamError::Unreachable)
    ));
    assert!(
        begun.elapsed() < Duration::from_secs(3),
        "{:?}",
        begun.elapsed()
    );
    assert_eq!(connections(&echo_seen), 0, "never sent direct");
}

/// A failed dial through a node on the local network leaves the data path up and says so in the
/// snapshot (ARCH D26); one through any other node says nothing. Linux refuses TCP to a multicast
/// address, which Android counts as local, at once.
#[test]
fn a_failed_dial_through_a_node_on_the_local_network_is_a_warning() {
    let (echo, echo_seen) = servers::tcp_echo();
    let (gone, _socket) = servers::closed_port();
    let mut s = setup(&[socks_link(gone, "gone")], vec![]);
    let id = s.client.connect(echo);
    assert!(s.client.until(WAIT, |c| c.read_now(id).is_err()));
    assert_eq!(s.g.snapshot().warning, None);

    let local = SocketAddr::from((Ipv4Addr::new(224, 0, 0, 1), 1080));
    let mut s = setup(&[socks_link(local, "lan")], vec![]);
    assert_eq!(s.g.snapshot().warning, None);
    let id = s.client.connect(echo);
    assert!(
        s.client
            .until(Duration::from_secs(15), |c| c.read_now(id).is_err())
    );
    let snapshot = s.g.snapshot();
    assert!(snapshot.running && snapshot.error.is_none());
    assert_eq!(snapshot.warning, Some(ErrorCode::LocalNetwork));
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
    assert_eq!(s.g.snapshot().traffic.active_flows, 1);
}

/// A UDP server that answers each datagram from a second port first, then echoes it from its own.
fn udp_echo_with_a_stray_peer() -> SocketAddr {
    let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
    let stray = UdpSocket::bind("127.0.0.1:0").unwrap();
    let addr = socket.local_addr().unwrap();
    std::thread::spawn(move || {
        let mut buf = vec![0u8; 2048];
        while let Ok((n, from)) = socket.recv_from(&mut buf) {
            let _ = stray.send_to(b"stray", from);
            let _ = socket.send_to(&buf[..n], from);
        }
    });
    addr
}

/// An association is with its destination alone, as a connected socket is: the node relays a
/// reply from another port too, and that one is dropped.
#[test]
fn udp_replies_from_another_peer_are_dropped() {
    let udp = udp_echo_with_a_stray_peer();
    let (proxy, _) = socks::socks5();
    let mut s = setup(&[socks_link(proxy, "s5")], vec![]);
    for i in 0..3u8 {
        s.client.send_udp(udp, &[i; 100]);
        assert_eq!(s.client.recv_udp(udp, WAIT), Some(vec![i; 100]));
    }
    assert!(s.client.recv_udp(udp, Duration::from_millis(300)).is_none());
    assert!(s.client.datagrams.is_empty(), "{:?}", s.client.datagrams);
}

/// An association keeps the node it opened with when the selection changes, until it idles out;
/// the next datagram then opens one through the node selected now.
#[test]
fn a_udp_association_keeps_its_node_until_it_idles_out() {
    let (udp, _) = servers::udp_echo();
    let (one, one_seen) = socks::socks5();
    let (two, two_seen) = socks::socks5();
    let options = Options {
        udp_idle: Duration::from_millis(500),
        ..Options::default()
    };
    let g = Global::with_options(&[socks_link(one, "one"), socks_link(two, "two")], options);
    let mut c = g.start();
    c.send_udp(udp, b"first");
    assert_eq!(c.recv_udp(udp, WAIT).as_deref(), Some(&b"first"[..]));
    g.select("two");
    c.send_udp(udp, b"kept");
    assert_eq!(c.recv_udp(udp, WAIT).as_deref(), Some(&b"kept"[..]));
    assert_eq!(one_seen.datagrams.load(Relaxed), 2);
    assert_eq!(two_seen.datagrams.load(Relaxed), 0);
    let idled = c.until(WAIT, |_| g.snapshot().traffic.active_flows == 0);
    assert!(idled, "the association idled out");
    c.send_udp(udp, b"moved");
    assert_eq!(c.recv_udp(udp, WAIT).as_deref(), Some(&b"moved"[..]));
    assert_eq!(two_seen.datagrams.load(Relaxed), 1);
    assert_eq!(one_seen.datagrams.load(Relaxed), 2);
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
    assert_eq!(s.g.snapshot().traffic.active_flows, 0);
}

fn dns_over_udp(c: &mut Client, q: &[u8]) -> Vec<u8> {
    c.send_udp(RESOLVER, q);
    let a = c.recv_udp(RESOLVER, WAIT).expect("no DNS answer");
    assert_eq!(&a[..2], &q[..2], "answer id");
    a
}

/// DNS to the virtual resolver, over UDP or TCP, is forwarded over TCP through the node, one
/// connection per query; TCP/853 is reset at once and dials nothing.
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
    let id = s.client.connect(RESOLVER);
    let q = servers::query(2, "b.example", None);
    let mut framed = (q.len() as u16).to_be_bytes().to_vec();
    framed.extend_from_slice(&q);
    let reply = s.client.exchange(id, &framed).unwrap();
    assert_eq!(&reply[2..4], &q[..2], "answer id");
    assert_eq!(servers::answers_and_rcode(&reply[2..]), (1, 0));
    assert_eq!(resolver_seen.datagrams.load(Relaxed), 2);
    assert_eq!(connections(&proxy_seen), 2, "so did the one over TCP");
    let begun = Instant::now();
    let id = s.client.connect(SocketAddr::new(RESOLVER.ip(), 853));
    assert!(s.client.until(WAIT, |c| c.read_now(id).is_err()));
    assert!(matches!(s.client.read_now(id), Err(StreamError::Reset)));
    assert!(begun.elapsed() < Duration::from_secs(3));
    assert_eq!(connections(&proxy_seen), 2);
}

#[test]
fn a_global_flow_without_a_node_is_refused_never_sent_direct() {
    let (echo, echo_seen) = servers::tcp_echo();
    let (udp, udp_seen) = servers::udp_echo();
    let (resolver, resolver_seen) = servers::dns_server();
    let (proxy, _) = socks::socks5();
    let mut s = setup(&[socks_link(proxy, "s5")], vec![resolver]);
    let args = format!(r#"{{"id":"{}"}}"#, s.g.subscription);
    s.g.call_ok("removeSubscription", &args);
    let id = s.client.connect(echo);
    assert!(s.client.until(WAIT, |c| c.read_now(id).is_err()));
    s.client.send_udp(udp, b"hello");
    assert!(s.client.recv_udp(udp, Duration::from_millis(300)).is_none());
    let begun = Instant::now();
    let a = dns_over_udp(&mut s.client, &servers::query(9, "a.example", None));
    assert_eq!(servers::answers_and_rcode(&a), (0, 2), "SERVFAIL");
    assert!(begun.elapsed() < Duration::from_secs(3));
    // DNS over TCP is a new flow like any other: refused, where a datagram gets SERVFAIL.
    let begun = Instant::now();
    let tcp_dns = s.client.connect(RESOLVER);
    assert!(s.client.until(WAIT, |c| c.read_now(tcp_dns).is_err()));
    assert!(matches!(
        s.client.read_now(tcp_dns),
        Err(StreamError::Reset)
    ));
    assert!(begun.elapsed() < Duration::from_secs(3));
    assert_eq!(connections(&echo_seen), 0);
    assert_eq!(udp_seen.datagrams.load(Relaxed), 0);
    assert_eq!(resolver_seen.datagrams.load(Relaxed), 0);
    assert!(s.g.snapshot().running, "the tunnel stays up");
}
