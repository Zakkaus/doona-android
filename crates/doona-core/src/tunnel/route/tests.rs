//! Each mode's route for a new flow, against a published catalog of three nodes: Direct and
//! Global as before, Rule by the template's rules with names from the generation's DNS answers.

use super::*;
use crate::catalog::{Model, entries};
use crate::outbounds::Routing;
use crate::rules::policy::Policy;
use crate::rules::template::Options;
use crate::rules::tests::{group, rule};
use crate::rules::{Network, RuleSet};
use crate::subscription::parse::parse;
use std::collections::{BTreeMap, HashMap};
use std::sync::Weak;
use std::time::Duration;

/// Three SOCKS5 nodes, published with the template's groups and a policy of `options`.
fn published(options: Options) -> (Outbounds, Model) {
    let links = b"socks5://127.0.0.1:1#a\nsocks5://127.0.0.1:2#b\nsocks5://127.0.0.1:3#c\n";
    let nodes = entries("s1", parse(links).unwrap().nodes);
    let mut model = Model::new(nodes, HashMap::from([("s1".to_owned(), 1)]));
    let ids: Vec<String> = model.nodes.iter().map(|n| n.id.clone()).collect();
    let is_node = |id: &str| ids.iter().any(|n| n == id);
    model.policy = Arc::new(Policy::template(&model.groups, &is_node, options));
    let outbounds = Outbounds::new(Arc::default()).unwrap();
    let built = outbounds.build(&model.nodes);
    outbounds.replace(built, model.routing(&BTreeMap::new()));
    (outbounds, model)
}

fn session() -> Arc<Session> {
    Arc::new(Session::new(1, Weak::new()))
}

/// A destination for a route whose mode does not look at it.
pub(crate) const ANYWHERE: (SocketAddr, Network) = (
    SocketAddr::new(std::net::IpAddr::V4(Ipv4Addr::new(192, 0, 2, 1)), 443),
    Network::Tcp,
);

const ABROAD: [u8; 4] = [104, 16, 0, 1];
const CN: [u8; 4] = [223, 5, 5, 5];

fn tcp(ip: [u8; 4], port: u16) -> (SocketAddr, Network) {
    ((ip, port).into(), Network::Tcp)
}

fn udp(ip: [u8; 4], port: u16) -> (SocketAddr, Network) {
    ((ip, port).into(), Network::Udp)
}

/// The group a route goes through, `direct`, or `refused`.
fn target(route: &Route) -> &str {
    match route {
        Route::Direct => "direct",
        Route::Proxy(d, _) => &d.group,
        Route::Refuse => "refused",
        Route::Blocked => "blocked",
    }
}

/// Each set leaves its mode and its count in the one word a snapshot reads, so a snapshot pairs a
/// mode with the count of the set that left it, and a decision taken under it can tell the mode was
/// set since.
#[test]
fn mode_cell_round_trips() {
    let cell = ModeCell::new(Mode::Direct);
    let (_, first) = cell.snapshot();
    for (n, mode) in [
        Mode::Global,
        Mode::Rule,
        Mode::Direct,
        Mode::Rule,
        Mode::Global,
    ]
    .into_iter()
    .enumerate()
    {
        cell.set(mode);
        assert_eq!(cell.get(), mode);
        assert_eq!(cell.snapshot(), (mode, first + n as u64 + 1));
    }
}

#[test]
fn direct_and_global_route_as_before_and_rule_follows_the_rules() {
    let (outbounds, _model) = published(Options::default());
    let session = session();
    session.names().map(ABROAD.into(), "www.netflix.com");
    session.names().map([104, 18, 0, 1].into(), "chatgpt.com");
    session.names().map([104, 19, 0, 1].into(), "example.org");
    let route = |dst, mode| Route::of(Inbound::Tun, dst, mode, &outbounds, &session);
    for dst in [tcp(ABROAD, 443), tcp(CN, 443), tcp([10, 0, 0, 1], 80)] {
        assert_eq!(target(&route(dst, Mode::Direct)), "direct");
        assert_eq!(target(&route(dst, Mode::Global)), "g-Proxy");
    }
    for (dst, to) in [
        // Names from the generation's DNS answers.
        (tcp(ABROAD, 443), "g-Streaming"),
        (udp(ABROAD, 443), "g-Streaming"),
        (tcp([104, 18, 0, 1], 443), "g-AI"),
        (tcp([104, 19, 0, 1], 443), "g-Proxy"),
        // No name (strict Private DNS, an app's own DoH): the address decides, else the last rule.
        (tcp([104, 20, 0, 1], 443), "g-Proxy"),
        (tcp(CN, 443), "direct"),
        (tcp([192, 168, 1, 2], 443), "direct"),
    ] {
        assert_eq!(target(&route(dst, Mode::Rule)), to, "{dst:?}");
    }
    // A route is metered by the group it goes through.
    let streaming = route(tcp(ABROAD, 443), Mode::Rule);
    let meter = streaming.meter(&session).unwrap();
    assert!(Arc::ptr_eq(&meter, &session.meters().get("g-Streaming")));
}

/// One cache per generation: a name a previous generation's answers gave is unknown to the next.
#[test]
fn a_generation_matches_names_from_its_own_answers_only() {
    let (outbounds, _model) = published(Options::default());
    let (old, new) = (session(), session());
    old.names().map(ABROAD.into(), "netflix.com");
    let route = |s| Route::of(Inbound::Tun, tcp(ABROAD, 443), Mode::Rule, &outbounds, s);
    assert_eq!(target(&route(&old)), "g-Streaming");
    assert_eq!(target(&route(&new)), "g-Proxy");
}

#[test]
fn reject_and_a_group_without_a_node_are_refused_never_direct() {
    let (outbounds, model) = published(Options::default());
    let groups = [group("Proxy", &[&model.nodes[0].id]), group("Empty", &[])];
    let rules = [
        rule("IP-CIDR", &["198.51.100.0/24"], "REJECT"),
        rule("IP-CIDR", &["203.0.113.0/24"], "Empty"),
    ];
    let set = RuleSet::compile(&groups, &|id| model.has(id), &rules).unwrap();
    let policy = Arc::new(Policy::new("g-Proxy", Some(set), Options::default()));
    let selected = model.leaves(&BTreeMap::new());
    outbounds.select(Routing { selected, policy });
    let session = session();
    let route = |dst| Route::of(Inbound::Tun, dst, Mode::Rule, &outbounds, &session);
    for dst in [
        tcp([198, 51, 100, 7], 443),
        udp([198, 51, 100, 7], 443),
        tcp([203, 0, 113, 7], 443),
        udp([203, 0, 113, 7], 53),
    ] {
        let r = route(dst);
        assert!(
            r.refused() && !r.carries_udp() && r.meter(&session).is_none(),
            "{dst:?}"
        );
    }
    assert_eq!(target(&route(tcp(ABROAD, 443))), "g-Proxy");
    // With no node at all, Rule refuses what the main group would carry, as Global does.
    let empty = Outbounds::new(Arc::default()).unwrap();
    let route = Route::of(Inbound::Tun, tcp(ABROAD, 443), Mode::Rule, &empty, &session);
    assert!(route.refused());
    let route = Route::of(
        Inbound::Tun,
        tcp(ABROAD, 443),
        Mode::Global,
        &empty,
        &session,
    );
    assert!(route.refused());
}

/// The last subscription removed: the catalog publishes no node and no group. Rule still sends
/// direct what its rules send direct (private and China's addresses), and refuses what a group
/// would carry, DNS included; Global refuses everything (rule-mode.md decision 9).
#[test]
fn without_a_node_rule_keeps_its_direct_rows_and_refuses_the_rest() {
    let model = Model::new(Vec::new(), HashMap::new());
    let outbounds = Outbounds::new(Arc::default()).unwrap();
    let built = outbounds.build(&model.nodes);
    outbounds.replace(built, model.routing(&BTreeMap::new()));
    let session = session();
    session.names().map(ABROAD.into(), "www.netflix.com");
    let route = |dst, mode| Route::of(Inbound::Tun, dst, mode, &outbounds, &session);
    for dst in [
        tcp([192, 168, 1, 1], 80),
        udp([192, 168, 1, 1], 5353),
        tcp(CN, 443),
        udp(CN, 443),
    ] {
        assert_eq!(target(&route(dst, Mode::Rule)), "direct", "{dst:?}");
        assert!(route(dst, Mode::Global).refused(), "{dst:?}");
    }
    for dst in [
        tcp(ABROAD, 443),
        tcp([104, 20, 0, 1], 443),
        udp(ABROAD, 8443),
    ] {
        assert!(route(dst, Mode::Rule).refused(), "{dst:?}");
    }
    assert!(Route::dns(Inbound::Tun, Mode::Rule, &outbounds, &session).refused());
}

/// A Direct flow is routed while a catalog swap holds the routing table's write lock, here
/// rebuilding a registry built on a table published since: Direct reads no table.
#[test]
fn direct_routes_while_a_swap_holds_the_table() {
    let (outbounds, model) = published(Options::default());
    let stale = outbounds.build(&model.nodes);
    let newer = outbounds.build(&model.nodes);
    outbounds.replace(newer, model.routing(&BTreeMap::new()));
    let (inside, entered) = std::sync::mpsc::channel();
    let (release, released) = std::sync::mpsc::channel::<()>();
    outbounds.on_build(move || {
        inside.send(()).unwrap();
        let _ = released.recv();
    });
    let session = session();
    let (routed, got) = std::sync::mpsc::channel();
    std::thread::scope(|s| {
        s.spawn(|| outbounds.replace(stale, model.routing(&BTreeMap::new())));
        let wait = Duration::from_secs(10);
        entered
            .recv_timeout(wait)
            .expect("the swap rebuilds under the lock");
        s.spawn(|| {
            let route = Route::of(Inbound::Tun, ANYWHERE, Mode::Direct, &outbounds, &session);
            routed.send(target(&route).to_owned()).unwrap();
        });
        let direct = got.recv_timeout(Duration::from_secs(2));
        release.send(()).unwrap();
        assert_eq!(direct.as_deref(), Ok("direct"));
    });
}

#[test]
fn block_quic_refuses_quic_bound_for_a_group() {
    let (outbounds, _model) = published(Options { block_quic: true });
    let session = session();
    session.names().map(ABROAD.into(), "youtube.com");
    let route = |dst, mode| Route::of(Inbound::Tun, dst, mode, &outbounds, &session);
    for dst in [udp(ABROAD, 443), udp([104, 20, 0, 1], 443)] {
        let blocked = route(dst, Mode::Rule);
        assert!(blocked.refused() && !blocked.carries_udp(), "{dst:?}");
        assert_eq!(target(&blocked), "blocked", "{dst:?}");
    }
    assert_eq!(target(&route(udp(CN, 443), Mode::Rule)), "direct");
    assert_eq!(target(&route(tcp(ABROAD, 443), Mode::Rule)), "g-Streaming");
    assert_eq!(target(&route(udp(ABROAD, 8443), Mode::Rule)), "g-Streaming");
    // The option belongs to the rules: Global carries QUIC through its group as before.
    assert_eq!(target(&route(udp(ABROAD, 443), Mode::Global)), "g-Proxy");
}

/// DNS the core answers goes through the main group in Rule as in Global (rule-mode.md
/// decision 10), whatever server the query was sent to.
#[test]
fn dns_goes_through_the_main_group_unless_direct() {
    let (outbounds, _model) = published(Options::default());
    let session = session();
    let dns = |mode| Route::dns(Inbound::Tun, mode, &outbounds, &session);
    assert_eq!(target(&dns(Mode::Rule)), "g-Proxy");
    assert_eq!(target(&dns(Mode::Global)), "g-Proxy");
    assert_eq!(target(&dns(Mode::Direct)), "direct");
    let empty = Outbounds::new(Arc::default()).unwrap();
    assert!(Route::dns(Inbound::Tun, Mode::Rule, &empty, &session).refused());
}

#[tokio::test]
async fn global_without_a_node_is_refused_never_sent_direct() {
    let outbounds = Outbounds::new(Arc::default()).unwrap();
    let session = session();
    let global = Route::of(
        Inbound::Tun,
        tcp(ABROAD, 443),
        Mode::Global,
        &outbounds,
        &session,
    );
    assert!(global.refused() && !global.carries_udp());
    let direct = Route::of(
        Inbound::Tun,
        tcp(ABROAD, 443),
        Mode::Direct,
        &outbounds,
        &session,
    );
    assert!(matches!(direct, Route::Direct) && direct.carries_udp());
}

/// The mean time `f` takes per flow of `flows`, over enough rounds to take about half a second.
fn per_flow<T>(f: impl Fn((SocketAddr, Network)) -> T, flows: &[(SocketAddr, Network)]) -> u128 {
    for &d in flows.iter().take(10_000) {
        std::hint::black_box(f(d));
    }
    let mut rounds = 0u32;
    let start = std::time::Instant::now();
    while start.elapsed() < Duration::from_millis(500) {
        for &d in flows {
            std::hint::black_box(f(std::hint::black_box(d)));
        }
        rounds += 1;
    }
    (start.elapsed() / (rounds * flows.len() as u32)).as_nanos()
}

/// What a new flow's route costs in each mode, for `rules-r2-STATE.md`: the template over 200
/// nodes, a full names cache (a quarter of it after the streaming list, a quarter after the AI
/// list), and 100,000 flows, 60 % to a named address, 20 % to China, 20 % to an unnamed one
/// abroad, one in ten over UDP. A route through a group includes its dialer, made and dropped.
/// Release: `bench` is in the name.
#[test]
#[ignore = "a release-mode measurement; see rules-r2-STATE.md"]
fn bench_route_per_flow() {
    use crate::dns::names::CAPACITY;
    use crate::rules::template::{AI, STREAMING};
    let links: String = (0..200)
        .map(|i| format!("socks5://127.0.0.1:{}#n{i}\n", 1000 + i))
        .collect();
    let nodes = entries("s1", parse(links.as_bytes()).unwrap().nodes);
    let model = Model::new(nodes, HashMap::from([("s1".to_owned(), 1)]));
    let outbounds = Outbounds::new(Arc::default()).unwrap();
    outbounds.replace(
        outbounds.build(&model.nodes),
        model.routing(&BTreeMap::new()),
    );
    let session = session();
    let named: Vec<Ipv4Addr> = (0..CAPACITY as u32)
        .map(|i| {
            let ip = Ipv4Addr::from(0x6800_0000 + i);
            let n = i as usize;
            let name = match i % 4 {
                0 => format!("v{i}.{}", STREAMING[n % STREAMING.len()]),
                1 => format!("a{i}.{}", AI[n % AI.len()]),
                _ => format!("h{i}.example{}.com", i % 97),
            };
            session.names().map(ip, &name);
            ip
        })
        .collect();
    let cn = [
        [223, 5, 5, 5],
        [114, 114, 114, 114],
        [119, 29, 29, 29],
        [180, 76, 76, 76],
    ];
    let mut rng = crate::rules::tests::Rng(0x0005_eed5);
    let flows: Vec<(SocketAddr, Network)> = (0..100_000)
        .map(|_| {
            let ip = match rng.below(10) {
                0..6 => named[rng.below(named.len() as u64) as usize],
                6 | 7 => Ipv4Addr::from(cn[rng.below(4) as usize]),
                _ => Ipv4Addr::from(0x6900_0000 + rng.below(1 << 20) as u32),
            };
            let network = if rng.below(10) == 0 {
                Network::Udp
            } else {
                Network::Tcp
            };
            ((ip, 443).into(), network)
        })
        .collect();
    for mode in [Mode::Direct, Mode::Global, Mode::Rule] {
        let route = |dst| Route::of(Inbound::Tun, dst, mode, &outbounds, &session);
        println!("route, {mode:?}: {} ns/flow", per_flow(route, &flows));
    }
    let lookup = |(dst, _): (SocketAddr, Network)| session.names().name(dst.ip());
    println!(
        "route, the name lookup alone: {} ns/flow",
        per_flow(lookup, &flows)
    );
    // What the rules' groups add to the catalog a snapshot carries (these node names are short).
    let catalog = model.catalog(&crate::state::State::default());
    let ruled: usize = (catalog.groups.iter())
        .filter(|g| g.id == "g-Streaming" || g.id == "g-AI")
        .map(|g| serde_json::to_vec(g).unwrap().len())
        .sum();
    let total = serde_json::to_vec(&catalog).unwrap().len();
    println!(
        "route, catalog over 200 nodes: {total} bytes of JSON, {ruled} of them Streaming and AI"
    );
}
