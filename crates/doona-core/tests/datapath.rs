//! The Direct data path end to end, no tun needed: the core runs a generation on one end of a
//! SOCK_SEQPACKET socketpair, an endpoint-mode honk Stack on the other end plays the apps (see
//! `common/client.rs`), and loopback servers are the internet. DNS goes to a loopback
//! DNS-over-TCP resolver instead of 1.1.1.1.

mod common;

use common::client::Client;
use common::servers;
use common::{TempDir, seqpacket_pair, wait_for};
use doonacore::api::{Core, Options};
use doonacore::dto::Snapshot;
use doonacore::sys;
use honk_netstack::StreamError;
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
    _dir: TempDir,
}

/// A started core in `mode` whose DNS goes to `upstreams`.
fn setup(mode: &str, upstreams: Vec<SocketAddr>) -> Setup {
    let dir = TempDir::new("datapath");
    let options = Options {
        dns_upstreams: upstreams,
    };
    let core = Core::with_options(dir.path().to_str().unwrap(), options).unwrap();
    let reply = core.call("setMode", &format!(r#"{{"mode":"{mode}"}}"#));
    assert_eq!(reply, r#"{"ok":{}}"#);
    let (tun, peer) = seqpacket_pair();
    core.start(sys::dup_cloexec(tun.as_raw_fd()).unwrap(), CONFIG)
        .unwrap();
    drop(tun);
    Setup {
        core,
        client: Client::new(peer),
        _dir: dir,
    }
}

fn snapshot(core: &Core) -> Snapshot {
    serde_json::from_str(&core.snapshot(i64::MAX)).unwrap()
}

fn pattern(len: usize) -> Vec<u8> {
    (0..len).map(|i| (i * 31 % 251) as u8).collect()
}

#[test]
fn direct_tcp_reaches_the_original_destination_both_ways() {
    let (echo, seen) = servers::tcp_echo();
    let mut s = setup("Direct", vec![]);
    let data = pattern(3 << 20);
    let id = s.client.connect(echo);
    let got = s.client.exchange(id, &data).unwrap();
    assert!(got == data, "echoed {} of {} bytes", got.len(), data.len());
    assert_eq!(seen.connections.load(Relaxed), 1);
    let t = snapshot(&s.core).traffic;
    assert!(t.up_total > 3 << 20 && t.down_total > 3 << 20, "{t:?}");
    assert!(wait_for(WAIT, || {
        s.client.turn();
        snapshot(&s.core).traffic.active_flows == 0
    }));
}

#[test]
fn direct_tcp_to_a_closed_port_is_reset() {
    let mut s = setup("Direct", vec![]);
    let id = s.client.connect(servers::closed_port());
    let reset = s.client.until(WAIT, |c| c.read_now(id).is_err());
    assert!(reset);
    assert!(matches!(
        s.client.read_now(id),
        Err(StreamError::Reset | StreamError::Unreachable)
    ));
}

#[test]
fn global_without_nodes_refuses_flows_and_never_goes_direct() {
    let (echo, tcp_seen) = servers::tcp_echo();
    let (udp, udp_seen) = servers::udp_echo();
    let mut s = setup("Global", vec![]);
    let id = s.client.connect(echo);
    assert!(s.client.until(WAIT, |c| c.read_now(id).is_err()));
    s.client.send_udp(udp, b"hello");
    assert!(s.client.recv_udp(udp, Duration::from_millis(300)).is_none());
    assert_eq!(tcp_seen.connections.load(Relaxed), 0);
    assert_eq!(udp_seen.datagrams.load(Relaxed), 0);
}

#[test]
fn direct_udp_gets_its_replies_from_the_destination() {
    let (udp, seen) = servers::udp_echo();
    let mut s = setup("Direct", vec![]);
    for i in 0..3u8 {
        s.client.send_udp(udp, &[i; 1200]);
        assert_eq!(s.client.recv_udp(udp, WAIT), Some(vec![i; 1200]));
    }
    assert_eq!(seen.datagrams.load(Relaxed), 3);
    assert_eq!(snapshot(&s.core).traffic.active_flows, 1);
}

fn dns_over_udp(c: &mut Client, to: SocketAddr, q: &[u8]) -> Vec<u8> {
    c.send_udp(to, q);
    let a = c.recv_udp(to, WAIT).expect("no DNS answer");
    assert_eq!(&a[..2], &q[..2], "answer id");
    a
}

#[test]
fn dns_is_answered_over_tcp_upstream_and_truncated_for_udp() {
    let (resolver, seen) = servers::dns_server();
    // The first upstream refuses connections; the second answers.
    let mut s = setup("Direct", vec![servers::closed_port(), resolver]);
    let c = &mut s.client;

    let a = dns_over_udp(c, RESOLVER, &servers::query(1, "a.example", None));
    assert_eq!(servers::answers_and_rcode(&a), (1, 0));
    // An app's hard-coded resolver is answered the same way, from its own address.
    let hard_coded = SocketAddr::from(([9, 9, 9, 9], 53));
    let a = dns_over_udp(c, hard_coded, &servers::query(2, "b.example", None));
    assert_eq!(servers::answers_and_rcode(&a), (1, 0));

    let a = dns_over_udp(c, RESOLVER, &servers::query(3, "big.example", None));
    assert!(servers::truncated(&a));
    assert_eq!(servers::answers_and_rcode(&a), (0, 0));
    let a = dns_over_udp(c, RESOLVER, &servers::query(4, "big.example", Some(4096)));
    assert_eq!(servers::answers_and_rcode(&a), (60, 0));

    // The TCP retry after truncation gets the whole answer.
    let id = c.connect(RESOLVER);
    let q = servers::query(5, "big.example", None);
    let mut framed = (q.len() as u16).to_be_bytes().to_vec();
    framed.extend_from_slice(&q);
    let reply = c.exchange(id, &framed).unwrap();
    assert_eq!(
        usize::from(u16::from_be_bytes([reply[0], reply[1]])),
        reply.len() - 2
    );
    assert_eq!(servers::answers_and_rcode(&reply[2..]), (60, 0));
    assert_eq!(seen.datagrams.load(Relaxed), 5);
}

#[test]
fn dns_without_a_route_fails_fast_and_private_dns_is_reset() {
    let (resolver, seen) = servers::dns_server();
    let mut s = setup("Global", vec![resolver]);
    let begun = Instant::now();
    let a = dns_over_udp(
        &mut s.client,
        RESOLVER,
        &servers::query(9, "a.example", None),
    );
    assert_eq!(servers::answers_and_rcode(&a), (0, 2), "SERVFAIL");
    assert!(begun.elapsed() < Duration::from_secs(1));
    assert_eq!(seen.datagrams.load(Relaxed), 0);
    let id = s.client.connect(SocketAddr::new(RESOLVER.ip(), 853));
    assert!(s.client.until(WAIT, |c| c.read_now(id).is_err()));
}

#[test]
fn set_mode_applies_to_new_flows_at_once() {
    let (echo, seen) = servers::tcp_echo();
    let mut s = setup("Global", vec![]);
    let refused = s.client.connect(echo);
    assert!(s.client.until(WAIT, |c| c.read_now(refused).is_err()));
    assert_eq!(
        s.core.call("setMode", r#"{"mode":"Direct"}"#),
        r#"{"ok":{}}"#
    );
    let id = s.client.connect(echo);
    assert_eq!(s.client.exchange(id, b"ping").unwrap(), b"ping");
    assert_eq!(seen.connections.load(Relaxed), 1);
}

#[test]
fn stop_closes_open_flows_within_a_second() {
    let (echo, seen) = servers::tcp_echo();
    let (udp, _) = servers::udp_echo();
    let mut s = setup("Direct", vec![]);
    let id = s.client.connect(echo);
    assert!(s.client.until(WAIT, |c| c.write_now(id, b"x") == 1));
    assert!(
        s.client
            .until(WAIT, |c| c.read_now(id).is_ok_and(|d| d == b"x"))
    );
    s.client.send_udp(udp, b"x");
    assert!(s.client.recv_udp(udp, WAIT).is_some());
    assert_eq!(snapshot(&s.core).traffic.active_flows, 2);
    let begun = Instant::now();
    s.core.stop();
    assert!(
        begun.elapsed() < Duration::from_secs(1),
        "{:?}",
        begun.elapsed()
    );
    // The upstream connection was closed with its generation.
    assert!(wait_for(WAIT, || seen.closed.load(Relaxed) == 1));
}
