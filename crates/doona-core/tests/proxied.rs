//! Global through nodes, end to end as in `datapath.rs`: a subscription served from loopback
//! lists local SOCKS5 and Shadowsocks servers, and every Global flow must reach its destination
//! through the `Proxy` group's node, never directly. A VMess node on a plain listener stands for
//! an outbound without UDP: nothing but its TCP dials ever reach it.

mod common;

use common::client::Client;
use common::global::{Global, WAIT, pattern, ping, socks_link, vmess_link};
use common::servers::{self, Seen};
use common::{socks, ss};
use honk_netstack::StreamError;
use std::net::{Ipv4Addr, SocketAddr};
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
    assert_eq!(s.g.snapshot().traffic.active_flows, 1);
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
    let args = format!(r#"{{"id":"{}"}}"#, s.g.subscription);
    s.g.call_ok("removeSubscription", &args);
    let id = s.client.connect(echo);
    assert!(s.client.until(WAIT, |c| c.read_now(id).is_err()));
    s.client.send_udp(udp, b"hello");
    assert!(s.client.recv_udp(udp, Duration::from_millis(300)).is_none());
    let begun = Instant::now();
    let a = dns_over_udp(&mut s.client, &servers::query(9, "a.example", None));
    assert_eq!(servers::answers_and_rcode(&a), (0, 2), "SERVFAIL");
    assert!(begun.elapsed() < Duration::from_secs(1));
    // DNS over TCP is a new flow like any other: refused, where a datagram gets SERVFAIL.
    let begun = Instant::now();
    let tcp_dns = s.client.connect(RESOLVER);
    assert!(s.client.until(WAIT, |c| c.read_now(tcp_dns).is_err()));
    assert!(matches!(
        s.client.read_now(tcp_dns),
        Err(StreamError::Reset)
    ));
    assert!(begun.elapsed() < Duration::from_secs(1));
    assert_eq!(connections(&echo_seen), 0);
    assert_eq!(udp_seen.datagrams.load(Relaxed), 0);
    assert_eq!(resolver_seen.datagrams.load(Relaxed), 0);
    assert!(s.g.snapshot().running, "the tunnel stays up");
}
