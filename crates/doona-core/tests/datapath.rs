//! The Direct data path end to end, no tun needed: the core runs a generation on one end of a
//! SOCK_SEQPACKET socketpair, an endpoint-mode honk Stack on the other end plays the apps (see
//! `common/client.rs`), and loopback servers are the internet. DNS goes to a loopback
//! DNS-over-TCP resolver instead of 1.1.1.1. Global through nodes is in `proxied.rs`.

mod common;

use common::client::Client;
use common::servers;
use common::stop_wait::STOP_WAIT;
use common::{TempDir, seqpacket_pair, wait_for};
use doonacore::api::{Core, Options};
use doonacore::dto::Snapshot;
use doonacore::sys;
use honk_netstack::{StreamError, TcpState};
use std::net::{Ipv4Addr, SocketAddr};
use std::os::fd::AsRawFd;
use std::sync::atomic::Ordering::Relaxed;
use std::time::{Duration, Instant};

const CONFIG: &str =
    r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":["192.168.8.1"]}"#;
const RESOLVER: SocketAddr =
    SocketAddr::new(std::net::IpAddr::V4(Ipv4Addr::new(172, 19, 0, 2)), 53);
const WAIT: Duration = Duration::from_secs(5);
/// How long a test's TCP flow may stall after a FIN, so that a cut comes quickly.
const DRAIN: Duration = Duration::from_millis(500);

struct Setup {
    core: Core,
    client: Client,
    _dir: TempDir,
}

/// A started core in `mode` whose DNS goes to `upstreams`.
fn setup(mode: &str, upstreams: Vec<SocketAddr>) -> Setup {
    let options = Options {
        dns_upstreams: upstreams,
        ..Options::default()
    };
    setup_with(mode, options)
}

fn setup_with(mode: &str, options: Options) -> Setup {
    let dir = TempDir::new("datapath");
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
    let (dst, _socket) = servers::closed_port();
    let id = s.client.connect(dst);
    let reset = s.client.until(WAIT, |c| c.read_now(id).is_err());
    assert!(reset);
    assert!(matches!(
        s.client.read_now(id),
        Err(StreamError::Reset | StreamError::Unreachable)
    ));
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
    let (closed, _socket) = servers::closed_port();
    let mut s = setup("Direct", vec![closed, resolver]);
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
    assert_eq!(s.core.call("recentFlows", "{}"), r#"{"ok":{"flows":[]}}"#);
}

#[test]
fn recent_app_connections_keep_the_tun_tuple_and_never_enter_reports() {
    use std::io::{Read, Write};
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let dns = listener.local_addr().unwrap();
    let (udp, _) = servers::udp_echo();
    let (tcp, _) = servers::tcp_echo();
    let mut s = setup("Direct", vec![dns]);
    std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().unwrap();
        let mut len = [0; 2];
        socket.read_exact(&mut len).unwrap();
        let mut q = vec![0; usize::from(u16::from_be_bytes(len))];
        socket.read_exact(&mut q).unwrap();
        let mut answer = servers::answer_to(&q, 1);
        let end = answer.len();
        answer[end - 4..].copy_from_slice(&Ipv4Addr::LOCALHOST.octets());
        socket
            .write_all(&(answer.len() as u16).to_be_bytes())
            .unwrap();
        socket.write_all(&answer).unwrap();
    });
    dns_over_udp(
        &mut s.client,
        RESOLVER,
        &servers::query(1, "private-dns.test", None),
    );
    s.client.send_udp(udp, b"udp");
    assert_eq!(s.client.recv_udp(udp, WAIT).unwrap(), b"udp");
    let request = b"GET /secret HTTP/1.1\r\nHost: private-http.test:80\r\n\r\n";
    let id = s.client.connect(tcp);
    assert_eq!(s.client.exchange(id, request).unwrap(), request);
    assert!(wait_for(WAIT, || {
        s.client.turn();
        snapshot(&s.core).traffic.active_flows == 1
    }));
    let reply: serde_json::Value = serde_json::from_str(&s.core.call("recentFlows", "{}")).unwrap();
    let rows = reply["ok"]["flows"].as_array().unwrap();
    assert_eq!(rows.len(), 2);
    for (row, (protocol, remote, name, open)) in rows.iter().zip([
        ("tcp", tcp, "private-http.test", false),
        ("udp", udp, "private-dns.test", true),
    ]) {
        assert_eq!(row["protocol"], protocol);
        assert_eq!(row["remote"], remote.to_string());
        let local: SocketAddr = row["local"].as_str().unwrap().parse().unwrap();
        assert_eq!(local.ip(), common::client::TUN_ADDR);
        assert_ne!(local.port(), 0);
        assert_eq!(row["name"], name);
        assert_eq!(row["outbound"], "direct");
        assert_eq!(row["open"], open);
    }
    assert!(rows[0]["startedMs"].as_u64().unwrap() >= rows[1]["startedMs"].as_u64().unwrap());
    for text in [
        s.core.snapshot(-1),
        s.core.call("diagnostics", r#"{"level":"debug"}"#),
    ] {
        assert!(!text.contains("private-") && !text.contains("recentFlows\":"));
    }
    s.core.stop();
    assert_eq!(s.core.call("recentFlows", "{}"), r#"{"ok":{"flows":[]}}"#);
}

#[test]
fn private_dns_to_the_virtual_resolver_is_reset_at_once() {
    let mut s = setup("Direct", vec![]);
    let begun = Instant::now();
    let id = s.client.connect(SocketAddr::new(RESOLVER.ip(), 853));
    assert!(s.client.until(WAIT, |c| c.read_now(id).is_err()));
    assert!(begun.elapsed() < Duration::from_secs(3));
}

#[test]
fn stop_promptly_closes_open_flows() {
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
    let took = begun.elapsed();
    assert!(took < STOP_WAIT + STOP_WAIT / 2, "{took:?}");
    // The upstream connection was closed with its generation.
    assert!(wait_for(WAIT, || seen.closed.load(Relaxed) == 1));
}

#[test]
fn an_upstream_fin_before_the_handshake_ends_still_lets_the_client_send() {
    let (server, received) = servers::half_closer();
    let mut s = setup("Direct", vec![]);
    let id = s.client.connect(server);
    // The client leaves the SYN-ACK unanswered while the upstream connects and half-closes.
    std::thread::sleep(Duration::from_millis(300));
    assert!(s.client.until(WAIT, |c| c.read_now(id).is_err()));
    assert!(
        matches!(s.client.read_now(id), Err(StreamError::Closed)),
        "EOF, not a reset"
    );
    assert_eq!(s.client.exchange(id, b"late").unwrap(), b"");
    let mut got = None;
    assert!(s.client.until(WAIT, |_| {
        got = received.try_recv().ok();
        got.is_some()
    }));
    assert_eq!(got.unwrap(), b"late");
}

#[test]
fn dns_sessions_are_not_active_flows() {
    let (resolver, _) = servers::dns_server();
    let mut s = setup("Direct", vec![resolver]);
    let id = s.client.connect(RESOLVER);
    let q = servers::query(1, "a.example", None);
    let mut framed = (q.len() as u16).to_be_bytes().to_vec();
    framed.extend_from_slice(&q);
    assert!(
        s.client
            .until(WAIT, |c| c.write_now(id, &framed) == framed.len())
    );
    let mut reply = Vec::new();
    assert!(s.client.until(WAIT, |c| {
        reply.extend(c.read_now(id).unwrap_or_default());
        reply.len() > 2 && reply.len() == 2 + usize::from(u16::from_be_bytes([reply[0], reply[1]]))
    }));
    // The session stays open: the client has not closed it.
    assert_eq!(snapshot(&s.core).traffic.active_flows, 0);
}

/// A client that half-closes and then stops reading keeps the upstream's data queued in the core:
/// its window stays shut while it answers the core's probes. Once the flow has moved no byte for
/// the drain time it is reset, and the core no longer holds it or its buffers.
#[test]
fn a_client_that_stops_reading_after_its_fin_is_reset_after_the_drain_time() {
    let flood = servers::flood();
    let options = Options {
        tcp_drain: DRAIN,
        ..Options::default()
    };
    let mut s = setup_with("Direct", options);
    let id = s.client.connect(flood);
    assert!(s.client.until(WAIT, |c| c.write_now(id, b"GET") == 3));
    s.client.shutdown(id);
    let begun = Instant::now();
    assert_eq!(snapshot(&s.core).traffic.active_flows, 1);
    // The client turns without reading.
    let core = &s.core;
    let gone = s
        .client
        .until(DRAIN + WAIT, |_| snapshot(core).traffic.active_flows == 0);
    assert!(gone, "the core still holds the flow");
    assert!(begun.elapsed() >= DRAIN, "{:?}", begun.elapsed());
    assert!(
        s.client
            .until(WAIT, |c| c.state(id) == Some(TcpState::Closed))
    );
    assert!(matches!(s.client.read_now(id), Err(StreamError::Reset)));
}

/// A DNS client that sends its queries and never reads the answers is reset once they have not
/// moved for the drain time, as a TCP flow is.
#[test]
fn a_dns_client_that_never_reads_is_reset_after_the_drain_time() {
    let (resolver, _) = servers::dns_server();
    let options = Options {
        dns_upstreams: vec![resolver],
        tcp_drain: DRAIN,
        ..Options::default()
    };
    let mut s = setup_with("Direct", options);
    let id = s.client.connect(RESOLVER);
    // Answers of about 64 KB each: more than the client's window and the core's buffers hold.
    let mut queries = Vec::new();
    for i in 0..64 {
        let q = servers::query(i, "huge.example", None);
        queries.extend_from_slice(&(q.len() as u16).to_be_bytes());
        queries.extend_from_slice(&q);
    }
    let mut sent = 0;
    assert!(s.client.until(WAIT, |c| {
        sent += c.write_now(id, &queries[sent..]);
        sent == queries.len()
    }));
    // The client turns without reading.
    let reset = s
        .client
        .until(DRAIN + WAIT, |c| c.state(id) == Some(TcpState::Closed));
    assert!(reset, "the core still holds the session");
    assert!(matches!(s.client.read_now(id), Err(StreamError::Reset)));
}

/// A DNS client that connects and sends nothing is reset once the session has moved nothing for the
/// drain time: the driver bounds it from accept, though no event comes.
#[test]
fn a_dns_client_that_sends_nothing_is_reset_after_the_drain_time() {
    let options = Options {
        tcp_drain: DRAIN,
        ..Options::default()
    };
    let mut s = setup_with("Direct", options);
    let id = s.client.connect(RESOLVER);
    let reset = s
        .client
        .until(DRAIN + WAIT, |c| c.state(id) == Some(TcpState::Closed));
    assert!(reset, "the core still holds the session");
    assert!(matches!(s.client.read_now(id), Err(StreamError::Reset)));
}

/// A client that half-closes and never reads, while the upstream sends a reply the client and the
/// core's stack cannot hold, then closes: the relay's last chunk fits the queue to the driver, so
/// the relay ends cleanly with part of the reply still waiting for the client. The driver keeps the
/// drain time from there and resets the flow when it runs out.
#[test]
fn a_reply_the_client_never_takes_is_reset_after_the_drain_time() {
    // More than the client's window and the stack's send buffer hold, less than the queue adds:
    // 256 to 352 KiB stayed held forever before the fix.
    let upstream = servers::sender(304 << 10);
    let options = Options {
        tcp_drain: DRAIN,
        ..Options::default()
    };
    let mut s = setup_with("Direct", options);
    let id = s.client.connect(upstream);
    assert!(s.client.until(WAIT, |c| c.write_now(id, b"GET") == 3));
    s.client.shutdown(id);
    let begun = Instant::now();
    let core = &s.core;
    let gone = s
        .client
        .until(DRAIN + WAIT, |_| snapshot(core).traffic.active_flows == 0);
    assert!(gone, "the core still holds the flow");
    assert!(begun.elapsed() >= DRAIN, "{:?}", begun.elapsed());
    assert!(
        s.client
            .until(WAIT, |c| c.state(id) == Some(TcpState::Closed))
    );
    assert!(matches!(s.client.read_now(id), Err(StreamError::Reset)));
}

/// The same for DNS over TCP: the answers to queries sent with the client's FIN fit the queue, so
/// the server ends cleanly while the client takes none of them.
#[test]
fn dns_answers_the_client_never_takes_are_reset_after_the_drain_time() {
    let (resolver, _) = servers::dns_server();
    let options = Options {
        dns_upstreams: vec![resolver],
        tcp_drain: DRAIN,
        ..Options::default()
    };
    let mut s = setup_with("Direct", options);
    let id = s.client.connect(RESOLVER);
    // Five answers of about 64 KB, 313 KiB in all: the replies of the TCP test above.
    let mut queries = Vec::new();
    for i in 0..5 {
        let q = servers::query(i, "huge.example", None);
        queries.extend_from_slice(&(q.len() as u16).to_be_bytes());
        queries.extend_from_slice(&q);
    }
    let mut sent = 0;
    assert!(s.client.until(WAIT, |c| {
        sent += c.write_now(id, &queries[sent..]);
        sent == queries.len()
    }));
    s.client.shutdown(id);
    let reset = s
        .client
        .until(DRAIN + WAIT, |c| c.state(id) == Some(TcpState::Closed));
    assert!(reset, "the core still holds the session");
    assert!(matches!(s.client.read_now(id), Err(StreamError::Reset)));
}

/// After its FIN the client takes none of the answers, and the resolver is slow to answer the last
/// query. The answers before it fill everything between the core and the client, so nothing moves
/// after the FIN: the session is reset a drain time later, while that answer is still coming, not
/// a drain time after the core starts to write it.
#[test]
fn a_dns_session_whose_last_answer_is_slow_is_reset_a_drain_time_after_nothing_moved() {
    let (resolver, _) = servers::dns_server();
    let options = Options {
        dns_upstreams: vec![resolver],
        tcp_drain: DRAIN,
        ..Options::default()
    };
    let mut s = setup_with("Direct", options);
    let id = s.client.connect(RESOLVER);
    // Five answers of about 64 KB fill the client's window, the core's buffers and the queue (see
    // above); the sixth comes after servers::SLOW.
    let mut queries = Vec::new();
    for i in 0..6 {
        let name = if i == 5 {
            "slow.huge.example"
        } else {
            "huge.example"
        };
        let q = servers::query(i, name, None);
        queries.extend_from_slice(&(q.len() as u16).to_be_bytes());
        queries.extend_from_slice(&q);
    }
    let mut sent = 0;
    assert!(s.client.until(WAIT, |c| {
        sent += c.write_now(id, &queries[sent..]);
        sent == queries.len()
    }));
    s.client.shutdown(id);
    let fin = Instant::now();
    let reset = s
        .client
        .until(servers::SLOW, |c| c.state(id) == Some(TcpState::Closed));
    let took = fin.elapsed();
    assert!(
        reset,
        "the core still holds the session {took:?} after the FIN"
    );
    assert!(
        took < servers::SLOW - Duration::from_millis(500),
        "{took:?}"
    );
    assert!(matches!(s.client.read_now(id), Err(StreamError::Reset)));
}
