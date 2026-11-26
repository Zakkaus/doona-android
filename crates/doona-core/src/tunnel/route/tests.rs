//! Each mode's route for a new flow, against a published catalog of three nodes: honk's engine
//! decides by its rules, with names from the generation's DNS answers, and a group it names dials
//! the node the catalog selects for that group.

use super::*;
use crate::catalog::{Model, entries};
use crate::dto::Network;
use crate::outbounds::Routing;
use crate::rules::policy::Policy;
use crate::rules::template::Options;
use crate::state::State;
use crate::stats::meter::Tally;
use crate::subscription::parse::parse;
use crate::testutil::engine;
use std::collections::HashMap;
use std::sync::Weak;
use std::time::Duration;

fn routing(model: &Model, mode: Mode) -> Routing {
    model.routing(&State {
        mode,
        ..State::default()
    })
}

/// Three SOCKS5 nodes, published with the template's groups and a policy of `options`.
pub(crate) fn published(options: Options) -> (Outbounds, Model) {
    let links = b"socks5://127.0.0.1:1#a\nsocks5://127.0.0.1:2#b\nsocks5://127.0.0.1:3#c\n";
    published_links(links, options, Mode::Rule)
}

pub(crate) fn published_links(links: &[u8], options: Options, mode: Mode) -> (Outbounds, Model) {
    let nodes = entries("s1", parse(links).unwrap().nodes);
    let mut model = Model::new(nodes, HashMap::from([("s1".to_owned(), 1)]));
    model.policy = template(&model, options);
    let outbounds = Outbounds::new(Arc::default(), 2).unwrap();
    let built = outbounds.build(&model.nodes);
    outbounds.replace(built, routing(&model, mode));
    (outbounds, model)
}

/// The template's policy of `options` over `model`'s groups.
fn template(model: &Model, options: Options) -> Arc<Policy> {
    let ids: Vec<String> = model.nodes.iter().map(|n| n.id.clone()).collect();
    let is_node = |id: &str| ids.iter().any(|n| n == id);
    Arc::new(Policy::template(&model.groups, &is_node, options))
}

/// Publishes `model`'s routing under `mode`.
fn publish(outbounds: &Outbounds, model: &Model, mode: Mode) {
    outbounds.select(routing(model, mode));
}

fn session() -> Arc<Session> {
    Arc::new(Session::new(1, Weak::new()))
}

/// A destination for a route whose mode does not look at it.
pub(crate) const ANYWHERE: (SocketAddr, SocketAddr, Network) = (
    APP,
    SocketAddr::new(std::net::IpAddr::V4(Ipv4Addr::new(192, 0, 2, 1)), 443),
    Network::Tcp,
);

const ABROAD: [u8; 4] = [104, 16, 0, 1];
const NAMED_A: [u8; 4] = [104, 16, 0, 2];
const NAMED_B: [u8; 4] = [104, 16, 0, 3];

/// Grouped negation, a must, a block, a direct row and the fallback, over the catalog template's
/// `Proxy` and `Auto` (the catalog's `g-Proxy` and `g-Auto`).
const ROUTES: &str = "\
node {
    a: 'socks5://127.0.0.1:1'
    b: 'socks5://127.0.0.1:2'
}
group {
    Proxy {
        filter: name(a, b)
        policy: fixed(0)
    }
    Auto {
        filter: name(a, b)
        policy: fixed(0)
    }
}
routing {
    dport(22) -> direct(must)
    dport(25) -> block
    dport(443) && !domain(suffix: a.test, suffix: b.test) -> Auto
    domain(suffix: a.test) -> direct
    fallback: Proxy
}
";

/// A session whose DNS answers named `NAMED_A` and `NAMED_B`.
fn named() -> Arc<Session> {
    let session = session();
    session.names().map(NAMED_A.into(), "www.a.test");
    session.names().map(NAMED_B.into(), "www.b.test");
    session
}

/// The app's end of every flow.
const APP: SocketAddr = SocketAddr::new(std::net::IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 40000);

fn tcp(ip: [u8; 4], port: u16) -> (SocketAddr, SocketAddr, Network) {
    (APP, (ip, port).into(), Network::Tcp)
}

fn udp(ip: [u8; 4], port: u16) -> (SocketAddr, SocketAddr, Network) {
    (APP, (ip, port).into(), Network::Udp)
}

/// The upstream DNS the core answers is sent to in these tests.
const UPSTREAM: SocketAddr = SocketAddr::new(std::net::IpAddr::V4(Ipv4Addr::new(9, 9, 9, 9)), 53);

/// The group or node a route goes through, `direct`, or `refused`; for DNS the core answers,
/// the route of its dial to [`UPSTREAM`].
fn target(route: &Route) -> String {
    match route {
        Route::Direct | Route::RuleDirect(_) => "direct".to_owned(),
        Route::Proxy(d, _) => d.group.to_string(),
        Route::Resolver(r) => target(&r.route(UPSTREAM, None)),
        Route::Refuse => "refused".to_owned(),
    }
}

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

/// Grouped negation, the fallback and a direct row in Rule; `must` and `block` hold in every mode,
/// and Global and Direct override the rest.
#[test]
fn honk_decides_every_flow_in_each_mode() {
    let (outbounds, _model) = published(Options::default());
    let (session, engine) = (named(), engine(ROUTES));
    let route = |flow, mode| {
        target(&Route::of(
            Inbound::Tun,
            flow,
            mode,
            Some(&*engine),
            &outbounds,
            &session,
        ))
        .to_owned()
    };
    let rule = [
        (tcp(ABROAD, 443), "g-Auto"),
        (udp(ABROAD, 443), "g-Auto"),
        (tcp(NAMED_A, 443), "direct"),
        (tcp(NAMED_B, 443), "g-Proxy"),
        (tcp(ABROAD, 80), "g-Proxy"),
        (tcp(ABROAD, 22), "direct"),
        (tcp(ABROAD, 25), "refused"),
    ];
    for (flow, want) in rule {
        assert_eq!(route(flow, Mode::Rule), want, "{flow:?}");
    }
    for (mode, rest) in [(Mode::Global, "g-Proxy"), (Mode::Direct, "direct")] {
        assert_eq!(route(tcp(ABROAD, 22), mode), "direct", "{mode:?}: must");
        assert_eq!(route(tcp(ABROAD, 25), mode), "refused", "{mode:?}: block");
        for flow in [tcp(ABROAD, 443), tcp(NAMED_A, 443), tcp(ABROAD, 80)] {
            assert_eq!(route(flow, mode), rest, "{mode:?} {flow:?}");
        }
    }
    // The engine runs whichever mode the flow read; going back to Rule decides by the rules again.
    assert_eq!(route(tcp(ABROAD, 443), Mode::Rule), "g-Auto");
}

/// A group honk names dials the node the catalog selects for it: a pin moves new flows.
#[test]
fn a_group_dials_the_leaf_the_catalog_selects() {
    let (outbounds, model) = published(Options::default());
    let (session, engine) = (session(), engine(ROUTES));
    let leaf = |pin: &str| {
        let state = State {
            mode: Mode::Rule,
            selections: [("g-Proxy".to_owned(), pin.to_owned())].into(),
            ..State::default()
        };
        outbounds.select(model.routing(&state));
        match Route::of(
            Inbound::Tun,
            tcp(ABROAD, 80),
            Mode::Rule,
            Some(&*engine),
            &outbounds,
            &session,
        ) {
            Route::Proxy(d, _) => (d.group.to_string(), d.node.name.clone()),
            _ => panic!("through a node"),
        }
    };
    for (i, node) in model.nodes.iter().enumerate().take(2) {
        let name = ["a", "b"][i];
        assert_eq!(leaf(&node.id), ("g-Proxy".to_owned(), name.to_owned()));
    }
}

/// Each flow honk sends direct or through a group by a row is metered under that row's meter as
/// well; the fallback has one too, and a mode that overrode the rules charges none.
#[test]
fn flows_are_metered_under_the_row_that_routed_them() {
    let (outbounds, _model) = published(Options::default());
    let (session, engine) = (named(), engine(ROUTES));
    let charge = |flow, mode, (up, down)| {
        let route = Route::of(
            Inbound::Tun,
            flow,
            mode,
            Some(&*engine),
            &outbounds,
            &session,
        );
        let tally = Tally::new(route.meter(&session));
        tally.up(up);
        tally.down(down);
    };
    let row = |flow: (SocketAddr, SocketAddr, Network)| {
        let (src, dst, _) = flow;
        let flow = runtime::Flow {
            network: runtime::Network::Tcp,
            source: src,
            destination: dst,
            domain: session.names().name(dst.ip()).map(|n| n.to_string()),
            domain_verified: true,
            process_name: None,
            mac: None,
            dscp: None,
        };
        engine
            .decide_in(runtime::Mode::Rule, &flow)
            .row
            .unwrap()
            .meter
            .unwrap()
            .to_string()
    };
    charge(tcp(NAMED_A, 443), Mode::Rule, (10, 20));
    charge(tcp(ABROAD, 443), Mode::Rule, (30, 40));
    charge(tcp(ABROAD, 80), Mode::Rule, (50, 60));
    let mut taken = session.meters().take();
    taken.rules.sort();
    taken.outbounds.sort();
    let mut rules = vec![
        (row(tcp(NAMED_A, 443)), [10, 20, 1]),
        (row(tcp(ABROAD, 443)), [30, 40, 1]),
        (row(tcp(ABROAD, 80)), [50, 60, 1]),
    ];
    rules.sort();
    assert_eq!(taken.rules, rules);
    let outbounds_taken = [
        ("direct".to_owned(), 10, 20),
        ("g-Auto".to_owned(), 30, 40),
        ("g-Proxy".to_owned(), 50, 60),
    ];
    assert_eq!(taken.outbounds, outbounds_taken);
    charge(tcp(ABROAD, 443), Mode::Global, (1, 2));
    charge(tcp(ABROAD, 443), Mode::Direct, (3, 4));
    let taken = session.meters().take();
    assert!(taken.rules.is_empty(), "{taken:?}");
    assert_eq!(taken.outbounds.len(), 2, "{taken:?}");
}

/// One cache per generation: a name a previous generation's answers gave is unknown to the next.
#[test]
fn a_generation_matches_names_from_its_own_answers_only() {
    let (outbounds, _model) = published(Options::default());
    let (old, new, engine) = (named(), session(), engine(ROUTES));
    let route = |s| {
        Route::of(
            Inbound::Tun,
            tcp(NAMED_A, 443),
            Mode::Rule,
            Some(&*engine),
            &outbounds,
            s,
        )
    };
    assert_eq!(target(&route(&old)), "direct");
    assert_eq!(target(&route(&new)), "g-Auto");
}

/// A group without a node is refused, never sent direct, in Rule and Global; Direct still sends
/// the flow straight out.
#[test]
fn a_group_without_a_node_is_refused_never_direct() {
    let empty = Outbounds::new(Arc::default(), 2).unwrap();
    let (session, engine) = (session(), engine(ROUTES));
    let route = |flow, mode| Route::of(Inbound::Tun, flow, mode, Some(&*engine), &empty, &session);
    for mode in [Mode::Rule, Mode::Global] {
        let r = route(tcp(ABROAD, 80), mode);
        assert!(r.refused() && !r.carries_udp(), "{mode:?}");
    }
    assert!(matches!(
        route(tcp(ABROAD, 80), Mode::Direct),
        Route::Direct
    ));
    assert!(matches!(
        route(tcp(ABROAD, 22), Mode::Rule),
        Route::RuleDirect(_)
    ));
}

/// GLOBAL with no target blocks every flow but `must`.
#[test]
fn global_without_a_target_refuses_all_but_must() {
    use crate::honkconf::Capabilities;
    let (outbounds, _model) = published(Options::default());
    let dir = crate::testutil::TempDir::new("untargeted");
    let store = crate::testutil::honk_store(dir.path(), ROUTES);
    let engine = Arc::new(Runtime::load(&store, &Capabilities::linked(), dir.path()).unwrap());
    let session = session();
    let route = |flow| {
        Route::of(
            Inbound::Tun,
            flow,
            Mode::Global,
            Some(&*engine),
            &outbounds,
            &session,
        )
    };
    assert!(route(tcp(ABROAD, 80)).refused());
    assert!(matches!(route(tcp(ABROAD, 22)), Route::RuleDirect(_)));
}

/// A fallback that keeps a direct fwmark cannot be honoured on the TUN: it is refused in Rule and
/// Direct, never dialed direct unmarked; GLOBAL overrides it.
#[test]
fn a_marked_fallback_is_refused() {
    let (outbounds, _model) = published(Options::default());
    let marked = ROUTES.replace("fallback: Proxy", "fallback: direct(mark: 256)");
    let (session, engine) = (session(), engine(&marked));
    let proxy = crate::honkconf::runtime::GlobalTarget::Group("Proxy".into());
    engine.set_global_target(proxy).unwrap();
    let route = |mode| {
        Route::of(
            Inbound::Tun,
            tcp(ABROAD, 80),
            mode,
            Some(&*engine),
            &outbounds,
            &session,
        )
    };
    assert!(route(Mode::Rule).refused());
    assert!(route(Mode::Direct).refused());
    assert_eq!(target(&route(Mode::Global)), "g-Proxy");
}

#[test]
fn direct_routes_while_a_swap_holds_the_table() {
    let (outbounds, model) = published(Options::default());
    let stale = outbounds.build(&model.nodes);
    let newer = outbounds.build(&model.nodes);
    outbounds.replace(newer, routing(&model, Mode::Rule));
    let (inside, entered) = std::sync::mpsc::channel();
    let (release, released) = std::sync::mpsc::channel::<()>();
    outbounds.on_build(move || {
        inside.send(()).unwrap();
        let _ = released.recv();
    });
    let (session, engine) = (session(), engine(ROUTES));
    let (routed, got) = std::sync::mpsc::channel();
    std::thread::scope(|s| {
        s.spawn(|| outbounds.replace(stale, routing(&model, Mode::Rule)));
        let wait = Duration::from_secs(10);
        entered
            .recv_timeout(wait)
            .expect("the swap rebuilds under the lock");
        s.spawn(|| {
            let route = Route::of(
                Inbound::Tun,
                ANYWHERE,
                Mode::Direct,
                Some(&*engine),
                &outbounds,
                &session,
            );
            routed.send(target(&route).to_owned()).unwrap();
        });
        let direct = got.recv_timeout(Duration::from_secs(2));
        release.send(()).unwrap();
        assert_eq!(direct.as_deref(), Ok("direct"));
    });
}

/// DNS the core answers is decided per upstream as a TCP flow to its port 53: the fallback's
/// group in Rule, the GLOBAL target in Global, straight out in Direct, and a `block` row for port
/// 53 holds in every mode. With no configuration only Direct resolves.
#[test]
fn dns_is_decided_like_a_flow_to_its_upstream() {
    let (outbounds, model) = published(Options::default());
    let session = session();
    let dns = |mode, engine: Option<Arc<Runtime>>| {
        publish(&outbounds, &model, mode);
        target(&Route::dns(
            Inbound::Tun,
            (mode, engine),
            &[UPSTREAM],
            &outbounds,
            &session,
        ))
    };
    let routes = engine(ROUTES);
    assert_eq!(dns(Mode::Rule, Some(Arc::clone(&routes))), "g-Proxy");
    assert_eq!(dns(Mode::Global, Some(Arc::clone(&routes))), "g-Proxy");
    assert_eq!(dns(Mode::Direct, Some(Arc::clone(&routes))), "direct");
    let blocked = engine(&ROUTES.replace("dport(25) -> block", "dport(53) -> block"));
    for mode in [Mode::Rule, Mode::Global, Mode::Direct] {
        assert_eq!(dns(mode, Some(Arc::clone(&blocked))), "refused", "{mode:?}");
    }
    assert_eq!(dns(Mode::Direct, None), "direct");
    assert_eq!(dns(Mode::Rule, None), "refused");
    let none = Route::dns(
        Inbound::Tun,
        (Mode::Rule, None),
        &[UPSTREAM],
        &outbounds,
        &session,
    );
    assert!(
        none.refused(),
        "no configuration outside Direct: refused up front"
    );
    let empty = Outbounds::new(Arc::default(), 2).unwrap();
    let route = Route::dns(
        Inbound::Tun,
        (Mode::Rule, Some(routes)),
        &[UPSTREAM],
        &empty,
        &session,
    );
    assert!(route.refused(), "every upstream refused: refused up front");
}

/// A node chosen as the GLOBAL target is dialed: the catalog node with that honk endpoint,
/// metered under its id. Rule still routes by the rules.
#[test]
fn a_global_node_target_dials_that_node() {
    use crate::honkconf::runtime::GlobalTarget;
    let (outbounds, model) = published(Options::default());
    let (session, engine) = (session(), engine(ROUTES));
    let flow = runtime::Flow {
        network: runtime::Network::Tcp,
        source: APP,
        destination: tcp(ABROAD, 80).1,
        domain: None,
        domain_verified: true,
        process_name: None,
        mac: None,
        dscp: None,
    };
    let leaf = engine
        .decide_in(runtime::Mode::Rule, &flow)
        .decision
        .candidates[0]
        .clone();
    engine
        .set_global_target(GlobalTarget::Node(leaf.node_id))
        .unwrap();
    let route = |mode| {
        Route::of(
            Inbound::Tun,
            tcp(ABROAD, 80),
            mode,
            Some(&*engine),
            &outbounds,
            &session,
        )
    };
    let Route::Proxy(d, _) = route(Mode::Global) else {
        panic!("through the node");
    };
    let node = model.nodes.iter().find(|n| n.name == leaf.node).unwrap();
    assert_eq!((&*d.group, d.node.name.as_str()), (node.id.as_str(), "a"));
    assert_eq!(target(&route(Mode::Rule)), "g-Proxy");
    assert!(
        Route::of(
            Inbound::Tun,
            tcp(ABROAD, 80),
            Mode::Global,
            Some(&*engine),
            &Outbounds::new(Arc::default(), 2).unwrap(),
            &session
        )
        .refused()
    );
}

/// A configuration with no group and a direct fallback: Rule sends flows direct by the fallback's
/// row and refuses `block`; Global has no target and refuses all but `must`; Direct goes direct.
#[test]
fn zero_groups_route_direct_by_the_fallback() {
    const NO_GROUPS: &str = "\
node {
    a: 'socks5://127.0.0.1:1'
}
routing {
    dport(22) -> direct(must)
    dport(25) -> block
    fallback: direct
}
";
    let (outbounds, _model) = published(Options::default());
    let (session, engine) = (session(), engine(NO_GROUPS));
    assert_eq!(engine.fallback_group(), None);
    let route = |flow, mode| {
        Route::of(
            Inbound::Tun,
            flow,
            mode,
            Some(&*engine),
            &outbounds,
            &session,
        )
    };
    assert!(matches!(
        route(tcp(ABROAD, 80), Mode::Rule),
        Route::RuleDirect(_)
    ));
    assert!(route(tcp(ABROAD, 25), Mode::Rule).refused());
    assert!(route(tcp(ABROAD, 80), Mode::Global).refused());
    assert!(matches!(
        route(tcp(ABROAD, 22), Mode::Global),
        Route::RuleDirect(_)
    ));
    assert!(matches!(
        route(tcp(ABROAD, 80), Mode::Direct),
        Route::Direct
    ));
}

/// Without a committed configuration only Direct routes, straight out.
#[test]
fn no_configuration_routes_only_direct() {
    let (outbounds, _model) = published(Options::default());
    let session = session();
    let route = |mode| Route::of(Inbound::Tun, ANYWHERE, mode, None, &outbounds, &session);
    assert!(matches!(route(Mode::Direct), Route::Direct));
    assert!(route(Mode::Rule).refused());
    assert!(route(Mode::Global).refused());
}
