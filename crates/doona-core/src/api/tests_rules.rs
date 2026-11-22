//! Rule mode through the real driver, with a SOCKS5 node on loopback that counts its dials: every
//! switch between Global, Direct and Rule applies to new flows only and keeps the generation; a
//! name the generation's DNS answer gave routes a flow by a domain rule, and the next generation
//! starts without it; a rule's reject, and a group without a node, reset the flow instead of
//! sending it anywhere.

use super::tests::echo;
use super::tests_catalog::{Feeds, add, path};
use super::{Core, Life, Options, lock};
use crate::dns::tests::{answer_to, query};
use crate::groups::GroupDef;
use crate::probe;
use crate::rules::policy::Policy;
use crate::rules::template;
use crate::rules::tests::{group, rule};
use crate::rules::{RuleSet, RuleSpec};
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
        let dir = TempDir::new(tag);
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

    /// Makes the catalog's policy the template's groups plus `extra`, under `rules`.
    fn rules(&self, extra: &[GroupDef], rules: &[crate::rules::RuleSpec]) {
        self.rules_with(extra, rules, template::Options::default());
    }

    /// As `rules`, with the template's `options`.
    fn rules_with(&self, extra: &[GroupDef], rules: &[RuleSpec], options: template::Options) {
        let mut c = lock(&self.core.shared.catalog);
        let mut groups = c.model.groups.clone();
        groups.extend_from_slice(extra);
        let set = RuleSet::compile(&groups, &|id| c.model.has(id), rules).unwrap();
        let policy = Policy::new("g-Proxy", Some(set), options);
        c.model.policy = Arc::new(policy);
        self.core.shared.publish(&c);
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

/// The echo server is on loopback, which the template's first rule sends direct, so a new flow to
/// it reaches the node only in Global. Every flow opened before a switch keeps its route.
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
        through_node += u32::from(mode == "Global");
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

/// rule-mode.md, R2: an answer of the core's DNS names its address, a flow to it takes the domain
/// rule, and the next generation starts without the name. In Rule, DNS goes through the main
/// group.
#[test]
fn a_name_from_the_generations_dns_routes_a_flow_by_a_domain_rule() {
    let (echo, accepted) = counted_echo([127, 0, 0, 3]);
    let options = Options {
        dns_upstreams: vec![resolver([127, 0, 0, 3])],
        ..Options::default()
    };
    let mut r = Rig::new("rules-names", options);
    let rules = [
        rule("DOMAIN", &["echo.test"], "Streaming"),
        rule("GEOIP", &["private"], "DIRECT"),
        rule("MATCH", &[], "Proxy"),
    ];
    r.rules(&[], &rules);
    r.set_mode("Rule");
    // Unnamed, the loopback address is private: direct.
    let id = r.client.connect(echo);
    assert_eq!(round_trip(&mut r.client, id, b"unnamed"), b"unnamed");
    assert_eq!((r.dials(), accepted.load(SeqCst)), (0, 1));
    // The query goes to the node, the answer names the address.
    r.client.send_udp(RESOLVER, &query(9, "Echo.Test", None));
    let answer = r
        .client
        .recv_udp(RESOLVER, Duration::from_secs(10))
        .unwrap();
    assert_eq!(answer[answer.len() - 4..], [127, 0, 0, 3]);
    assert_eq!(r.dials(), 1, "DNS through the main group");
    let id = r.client.connect(echo);
    assert_eq!(round_trip(&mut r.client, id, b"named"), b"named");
    assert_eq!(
        (r.dials(), accepted.load(SeqCst)),
        (2, 2),
        "through Streaming's node"
    );
    // A new generation has no names yet.
    let (tun, peer) = tun_and_peer();
    r.core.start(tun, CONFIG).unwrap();
    let mut client = Client::new(peer);
    let id = client.connect(echo);
    assert_eq!(round_trip(&mut client, id, b"next"), b"next");
    assert_eq!((r.dials(), accepted.load(SeqCst)), (2, 3), "direct again");
}

/// A rule's reject, and a rule's group with no node, reset a TCP flow and drop datagrams: nothing
/// reaches the node or the destination. A switch to Direct then shows the destinations answer.
#[test]
fn a_rejected_flow_and_a_group_without_a_node_are_refused() {
    let (echo, accepted) = counted_echo([127, 0, 0, 1]);
    let (empty_echo, empty_accepted) = counted_echo([127, 0, 0, 4]);
    let udp = udp_echo();
    let mut r = Rig::new("rules-reject", Options::default());
    let rules = [
        rule("IP-CIDR", &["127.0.0.1/32"], "REJECT"),
        rule("IP-CIDR", &["127.0.0.4/32"], "Empty"),
        rule("MATCH", &[], "DIRECT"),
    ];
    r.rules(&[group("Empty", &[])], &rules);
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
    for dst in [echo, empty_echo] {
        let id = r.client.connect(dst);
        assert_eq!(r.client.exchange(id, b"direct").unwrap(), b"direct");
    }
    r.client.send_udp(udp, b"direct");
    let reply = r.client.recv_udp(udp, Duration::from_secs(10));
    assert_eq!(reply.as_deref(), Some(&b"direct"[..]));
}

/// The last subscription removed in Rule: the template's direct rows still carry private and
/// China's addresses, and a flow a group would carry is reset without a dial. `start` then refuses
/// Global with `no_nodes`, not Rule.
#[test]
fn rule_without_a_node_keeps_the_template_direct_rows() {
    let (echo, accepted) = counted_echo([127, 0, 0, 1]);
    let mut r = Rig::new("rules-no-node", Options::default());
    r.set_mode("Rule");
    let removed = r.core.call("removeSubscription", r#"{"id":"s1"}"#);
    assert_eq!(removed, r#"{"ok":{}}"#);
    let id = r.client.connect(echo);
    assert_eq!(round_trip(&mut r.client, id, b"private"), b"private");
    let abroad = SocketAddr::from(([104, 16, 0, 1], 443));
    let id = r.client.connect(abroad);
    let refused = r.client.exchange(id, b"abroad");
    assert!(
        matches!(refused, Err(StreamError::Reset | StreamError::Unreachable)),
        "{refused:?}"
    );
    assert_eq!((r.dials(), accepted.load(SeqCst)), (0, 1));
    r.core.stop();
    let (tun, peer) = tun_and_peer();
    assert_eq!(r.core.start(tun, CONFIG), Ok(()));
    let mut client = Client::new(peer);
    let id = client.connect(echo);
    assert_eq!(round_trip(&mut client, id, b"again"), b"again");
    r.core.stop();
    r.set_mode("Global");
    let (tun, _peer) = tun_and_peer();
    assert_eq!(
        r.core.start(tun, CONFIG),
        Err(crate::dto::ErrorCode::NoNodes)
    );
}

/// blockQuic refuses a QUIC five-tuple, and refuses its retransmits for a few seconds without
/// another lookup, even when a name learned since would send it direct. A mode change, or a new
/// policy, ends that. A UDP echo the rules send direct shows the driver has handled every
/// datagram sent before it.
#[test]
fn a_five_tuple_block_quic_refused_stays_refused_until_the_mode_or_policy_changes() {
    let options = Options {
        dns_upstreams: vec![resolver([127, 0, 0, 5])],
        ..Options::default()
    };
    let mut r = Rig::new("rules-quic", options);
    let echo = udp_echo();
    let mut rules = vec![
        rule("IP-CIDR", &["127.0.0.1/32"], "DIRECT"),
        rule("DOMAIN", &["quic.test"], "DIRECT"),
        rule("MATCH", &[], "Proxy"),
    ];
    let block = template::Options { block_quic: true };
    r.rules_with(&[], &rules, block);
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
    // The answer names the address quic.test, which the rules send direct.
    r.client.send_udp(RESOLVER, &query(9, "quic.test", None));
    assert!(
        r.client
            .recv_udp(RESOLVER, Duration::from_secs(10))
            .is_some()
    );
    r.client.send_udp(quic, b"retransmit");
    settle(&mut r);
    assert_eq!(
        r.opened(),
        base,
        "the retransmit is refused as the first was"
    );
    r.set_mode("Global");
    r.set_mode("Rule");
    r.client.send_udp(quic, b"after a switch");
    settle(&mut r);
    assert_eq!(r.opened(), base + 1, "direct by its name");
    // An address the rules send to Proxy, until a new policy sends it direct.
    let other = SocketAddr::from(([127, 0, 0, 6], 443));
    r.client.send_udp(other, b"initial");
    settle(&mut r);
    assert_eq!(r.opened(), base + 1);
    rules.insert(0, rule("IP-CIDR", &["127.0.0.6/32"], "DIRECT"));
    r.rules_with(&[], &rules, block);
    r.client.send_udp(other, b"after the policy");
    settle(&mut r);
    assert_eq!(r.opened(), base + 2, "direct by the new policy");
}
