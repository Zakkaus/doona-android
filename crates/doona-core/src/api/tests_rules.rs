//! Mode switches through the real driver, with a SOCKS5 node on loopback that counts its dials:
//! every switch between Global, Direct and Rule applies to new flows only and keeps the
//! generation. The rules themselves are honk's (see `tunnel/route/tests.rs`).

use super::tests::echo;
use super::tests_catalog::{Feeds, add, path};
use super::{Core, Life, Options, lock};
use crate::dns::tests::{answer_to, query};
use crate::probe;
use crate::state::Store;
use crate::testutil::client::Client;
use crate::testutil::{TempDir, socks5, tun_and_peer};
use honk_netstack::{FlowId, StreamError};
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, UdpSocket};
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering::SeqCst};
use std::time::Duration;

const CONFIG: &str =
    r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":["192.168.8.1"]}"#;
const RESOLVER: SocketAddr = SocketAddr::new(
    std::net::IpAddr::V4(std::net::Ipv4Addr::new(172, 19, 0, 2)),
    53,
);

/// A running core over one SOCKS5 node, and the client that plays the apps on its tun.
struct Rig {
    core: Core,
    client: Client,
    /// Connections the node was asked to make.
    dials: Arc<AtomicU32>,
    _dir: TempDir,
    _feeds: Feeds,
}

impl Rig {
    fn new(tag: &str, options: Options) -> Rig {
        Rig::with(tag, options, crate::testutil::HONK)
    }

    /// As `new`, with `honk` committed as the core's honk configuration.
    fn with(tag: &str, options: Options, honk: &str) -> Rig {
        let dir = TempDir::new(tag);
        crate::testutil::honk_store(dir.path(), honk);
        let (proxy, dials) = socks5();
        let feeds = Feeds::new();
        feeds.set("/sub", format!("socks5://{proxy}#s5\n").as_bytes());
        let store = Store::new(Path::new(path(&dir)));
        let launch = (
            crate::tunnel::drive as crate::tunnel::Launch,
            Duration::from_secs(10),
        );
        let core = Core::build(store, options, launch, probe::urltest()).unwrap();
        add(&core, &feeds, "/sub").unwrap();
        let (tun, peer) = tun_and_peer();
        core.start(tun, CONFIG).unwrap();
        Rig {
            core,
            client: Client::new(peer),
            dials,
            _dir: dir,
            _feeds: feeds,
        }
    }

    fn set_mode(&self, mode: &str) {
        let reply = self
            .core
            .call("setMode", &format!(r#"{{"mode":"{mode}"}}"#));
        assert_eq!(reply, r#"{"ok":{}}"#, "{mode}");
    }

    fn dials(&self) -> u32 {
        self.dials.load(SeqCst)
    }

    /// Flows the running generation's apps opened.
    fn opened(&self) -> u64 {
        match &*lock(&self.core.life) {
            Life::Running(t) => t.session().opened(),
            _ => panic!("not running"),
        }
    }

    /// The running generation's number and session.
    fn generation(&self) -> (i64, usize) {
        match &*lock(&self.core.life) {
            Life::Running(t) => (t.session().generation, Arc::as_ptr(t.session()) as usize),
            _ => panic!("not running"),
        }
    }
}

/// Sends `msg` on the open flow `id` and reads its echo, leaving the flow open.
fn round_trip(c: &mut Client, id: FlowId, msg: &[u8]) -> Vec<u8> {
    let (mut sent, mut got) = (0, Vec::new());
    let done = c.until(Duration::from_secs(10), |c| {
        sent += c.write_now(id, &msg[sent..]);
        got.extend(c.read_now(id).unwrap_or_default());
        got.len() >= msg.len()
    });
    assert!(done, "no echo of {msg:?}: sent {sent}, got {got:?}");
    got
}

/// A TCP echo server on `ip` that counts the connections it accepts.
fn counted_echo(ip: [u8; 4]) -> (SocketAddr, Arc<AtomicU32>) {
    let listener = TcpListener::bind(SocketAddr::from((ip, 0))).unwrap();
    let addr = listener.local_addr().unwrap();
    let accepted = Arc::new(AtomicU32::new(0));
    let count = Arc::clone(&accepted);
    std::thread::spawn(move || {
        for conn in listener.incoming().flatten() {
            count.fetch_add(1, SeqCst);
            std::thread::spawn(move || {
                let mut read = conn.try_clone().unwrap();
                let _ = std::io::copy(&mut read, &mut &conn);
            });
        }
    });
    (addr, accepted)
}

/// A UDP echo server on loopback.
fn udp_echo() -> SocketAddr {
    let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
    let addr = socket.local_addr().unwrap();
    std::thread::spawn(move || {
        let mut buf = [0; 2048];
        while let Ok((n, from)) = socket.recv_from(&mut buf) {
            let _ = socket.send_to(&buf[..n], from);
        }
    });
    addr
}

/// A DNS-over-TCP server on loopback that answers every query with one A record of `ip`.
fn resolver(ip: [u8; 4]) -> SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    std::thread::spawn(move || {
        for mut s in listener.incoming().flatten() {
            std::thread::spawn(move || {
                let mut len = [0; 2];
                while s.read_exact(&mut len).is_ok() {
                    let mut q = vec![0; usize::from(u16::from_be_bytes(len))];
                    if s.read_exact(&mut q).is_err() {
                        return;
                    }
                    let mut a = answer_to(&q, 1);
                    let end = a.len();
                    a[end - 4..].copy_from_slice(&ip);
                    let _ = s.write_all(&(a.len() as u16).to_be_bytes());
                    let _ = s.write_all(&a);
                }
            });
        }
    });
    addr
}

/// The honk configuration sends every flow through `Proxy` but in Direct, so a new flow to the
/// echo server reaches the node in Global and Rule. Every flow opened before a switch keeps its
/// route.
#[test]
fn every_mode_switch_applies_to_new_flows_and_keeps_the_tunnel() {
    let mut r = Rig::new("rules-switch", Options::default());
    let echo = echo();
    let before = r.generation();
    let (mut flows, mut through_node) = (Vec::new(), 0);
    // Global to Rule, Rule to Direct, Direct to Rule, Rule to Global, Global to Direct, Direct to
    // Global.
    let modes = [
        "Global", "Rule", "Direct", "Rule", "Global", "Direct", "Global",
    ];
    for mode in modes {
        r.set_mode(mode);
        let id = r.client.connect(echo);
        assert_eq!(
            round_trip(&mut r.client, id, mode.as_bytes()),
            mode.as_bytes()
        );
        // The honk configuration sends every flow but a Direct one through `Proxy`.
        through_node += u32::from(mode != "Direct");
        assert_eq!(r.dials(), through_node, "a new {mode} flow");
        flows.push((id, mode));
        for &(id, opened) in &flows {
            let msg = format!("{opened} still");
            assert_eq!(
                round_trip(&mut r.client, id, msg.as_bytes()),
                msg.as_bytes()
            );
        }
        assert_eq!(r.dials(), through_node, "the flows opened before {mode}");
    }
    assert_eq!(r.generation(), before, "no switch restarted the tunnel");
}

#[test]
fn changing_dns_redirects_queries_in_the_running_generation() {
    let initial = [192, 0, 2, 1];
    let changed = [192, 0, 2, 2];
    let options = Options {
        dns_upstreams: vec![resolver(initial)],
        ..Options::default()
    };
    let mut rig = Rig::new("dns-upstream-switch", options);
    let generation = rig.generation();
    for address in [initial, changed] {
        if address == changed {
            let args = serde_json::json!({"servers":[resolver(changed)]});
            super::tests_catalog::call(&rig.core, "setDnsUpstreams", args).unwrap();
        }
        rig.client.send_udp(RESOLVER, &query(1, "dns.test", None));
        let answer = rig
            .client
            .recv_udp(RESOLVER, Duration::from_secs(10))
            .unwrap();
        assert_eq!(answer[answer.len() - 4..], address);
    }
    assert_eq!(rig.generation(), generation);
}

#[test]
fn established_tcp_dns_keeps_its_list_but_new_connections_switch() {
    let first = [192, 0, 2, 1];
    let second = [192, 0, 2, 2];
    let options = Options {
        dns_upstreams: vec![resolver(first)],
        ..Options::default()
    };
    let mut rig = Rig::new("dns-tcp-switch", options);
    let old = rig.client.connect(RESOLVER);
    assert_eq!(tcp_dns_address(&mut rig.client, old), first);
    let args = serde_json::json!({"servers":[resolver(second)]});
    super::tests_catalog::call(&rig.core, "setDnsUpstreams", args).unwrap();
    assert_eq!(
        tcp_dns_address(&mut rig.client, old),
        first,
        "established connection"
    );
    let new = rig.client.connect(RESOLVER);
    assert_eq!(
        tcp_dns_address(&mut rig.client, new),
        second,
        "new connection"
    );
}

fn tcp_dns_address(client: &mut Client, id: FlowId) -> [u8; 4] {
    let query = query(13, "tcp.test", None);
    let mut frame = (query.len() as u16).to_be_bytes().to_vec();
    frame.extend(query);
    let (mut sent, mut reply) = (0, Vec::new());
    assert!(client.until(Duration::from_secs(10), |c| {
        sent += c.write_now(id, &frame[sent..]);
        reply.extend(c.read_now(id).unwrap());
        reply.len() >= 2 && reply.len() == usize::from(u16::from_be_bytes([reply[0], reply[1]])) + 2
    }));
    reply[reply.len() - 4..].try_into().unwrap()
}

/// Two placeholder nodes in `Proxy`, an empty group, and `rules` ahead of a fallback to `Proxy`.
fn honk(rules: &str) -> String {
    format!(
        "node {{\n    a: 'socks5://127.0.0.1:1'\n    b: 'socks5://127.0.0.1:2'\n}}\n\
         group {{\n    Proxy {{\n        filter: name(a, b)\n        policy: fixed(0)\n    }}\n\
         \x20   Empty {{\n        filter: name(zzz)\n        policy: fixed(0)\n    }}\n}}\n\
         routing {{\n{rules}    fallback: Proxy\n}}\n"
    )
}

/// A name from the generation's DNS answers routes a flow by honk's domain rule; unnamed, the
/// same address goes by its address rule, and a new generation has no names yet.
#[test]
fn a_name_from_the_generations_dns_routes_a_flow_by_a_domain_rule() {
    let (echo, accepted) = counted_echo([127, 0, 0, 3]);
    let options = Options {
        dns_upstreams: vec![resolver([127, 0, 0, 3])],
        ..Options::default()
    };
    let rules = format!(
        "    domain(suffix: echo.test) -> Proxy\n    dport({}) -> direct\n",
        echo.port()
    );
    let mut r = Rig::with("rules-names", options, &honk(&rules));
    r.set_mode("Rule");
    let id = r.client.connect(echo);
    assert_eq!(round_trip(&mut r.client, id, b"unnamed"), b"unnamed");
    assert_eq!((r.dials(), accepted.load(SeqCst)), (0, 1));
    // The resolver dial takes the fallback, `Proxy`; the answer names the address.
    r.client.send_udp(RESOLVER, &query(9, "Echo.Test", None));
    let answer = r
        .client
        .recv_udp(RESOLVER, Duration::from_secs(10))
        .unwrap();
    assert_eq!(answer[answer.len() - 4..], [127, 0, 0, 3]);
    assert_eq!(r.dials(), 1, "DNS by the fallback");
    let id = r.client.connect(echo);
    assert_eq!(round_trip(&mut r.client, id, b"named"), b"named");
    assert_eq!(
        (r.dials(), accepted.load(SeqCst)),
        (2, 2),
        "through Proxy's node"
    );
    let (tun, peer) = tun_and_peer();
    r.core.start(tun, CONFIG).unwrap();
    let mut client = Client::new(peer);
    let id = client.connect(echo);
    assert_eq!(round_trip(&mut client, id, b"next"), b"next");
    assert_eq!((r.dials(), accepted.load(SeqCst)), (2, 3), "direct again");
}

/// honk's `block`, and a group with no node, reset a TCP flow and drop datagrams through the real
/// driver: nothing reaches the node or the destination. `block` holds in Direct too; the group
/// without a node goes direct there.
#[test]
fn a_blocked_flow_and_a_group_without_a_node_are_refused() {
    let (echo, accepted) = counted_echo([127, 0, 0, 1]);
    let (empty_echo, empty_accepted) = counted_echo([127, 0, 0, 4]);
    let udp = udp_echo();
    let rules = format!(
        "    dport({}, {}) -> block\n    dport({}) -> Empty\n",
        echo.port(),
        udp.port(),
        empty_echo.port()
    );
    let mut r = Rig::with("rules-block", Options::default(), &honk(&rules));
    r.set_mode("Rule");
    for dst in [echo, empty_echo] {
        let id = r.client.connect(dst);
        let refused = r.client.exchange(id, b"refused");
        assert!(
            matches!(refused, Err(StreamError::Reset | StreamError::Unreachable)),
            "{dst}: {refused:?}"
        );
    }
    r.client.send_udp(udp, b"dropped");
    assert_eq!(r.client.recv_udp(udp, Duration::from_millis(500)), None);
    assert_eq!(r.dials(), 0);
    assert_eq!((accepted.load(SeqCst), empty_accepted.load(SeqCst)), (0, 0));
    r.set_mode("Direct");
    let id = r.client.connect(echo);
    let refused = r.client.exchange(id, b"still blocked");
    assert!(refused.is_err(), "block holds in Direct: {refused:?}");
    let id = r.client.connect(empty_echo);
    assert_eq!(r.client.exchange(id, b"direct").unwrap(), b"direct");
}

/// A refused UDP five-tuple is refused again without a decision for a while, until a name learned
/// since or a mode change may route it otherwise. A UDP echo honk sends direct shows the driver has
/// handled every datagram sent before it.
#[test]
fn a_refused_five_tuple_stays_refused_until_a_name_or_mode_changes() {
    let options = Options {
        dns_upstreams: vec![resolver([127, 0, 0, 5])],
        ..Options::default()
    };
    let echo = udp_echo();
    let rules = format!(
        "    dport({}) -> direct\n    domain(suffix: quic.test) -> direct\n    \
         dport(444) -> Empty\n    dport(443) -> block\n",
        echo.port()
    );
    let mut r = Rig::with("rules-refused", options, &honk(&rules));
    r.set_mode("Rule");
    let settle = |r: &mut Rig| {
        r.client.send_udp(echo, b"settle");
        let echoed = r.client.recv_udp(echo, Duration::from_secs(10));
        assert_eq!(echoed.as_deref(), Some(&b"settle"[..]));
    };
    let quic = SocketAddr::from(([127, 0, 0, 5], 443));
    r.client.send_udp(quic, b"initial");
    settle(&mut r);
    let base = r.opened();
    r.client.send_udp(quic, b"retransmit");
    settle(&mut r);
    assert_eq!(
        r.opened(),
        base,
        "the retransmit is refused as the first was"
    );
    // The answer names the address quic.test, which honk sends direct.
    r.client.send_udp(RESOLVER, &query(9, "quic.test", None));
    assert!(
        r.client
            .recv_udp(RESOLVER, Duration::from_secs(10))
            .is_some()
    );
    r.client.send_udp(quic, b"after the name");
    settle(&mut r);
    assert_eq!(r.opened(), base + 1, "direct by the name learned since");
    // An address honk sends to the group without a node, until a switch to Direct.
    let other = SocketAddr::from(([127, 0, 0, 6], 444));
    r.client.send_udp(other, b"initial");
    settle(&mut r);
    assert_eq!(r.opened(), base + 1);
    r.set_mode("Direct");
    r.client.send_udp(other, b"after a switch");
    settle(&mut r);
    assert_eq!(r.opened(), base + 2, "direct after the switch");
}
