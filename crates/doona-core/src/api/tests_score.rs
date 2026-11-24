use super::tests::{echo, ping};
use super::tests_catalog::{Feeds, add, call, catalog, path};
use super::{Core, lock};
use crate::dto::{ErrorCode, Mode};
use crate::rules::Network;
use crate::score::Score;
use crate::testutil::{TempDir, client::Client, socks5, socks5_with, tuic, tun_and_peer};
use crate::tunnel::Session;
use crate::tunnel::route::{Inbound, Route};
use honk_outbound::group::SelectionNetwork;
use serde_json::json;
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering::SeqCst};

fn route(core: &Core, dst: SocketAddr) -> Route {
    let session = Arc::new(Session::new(1, Default::default()));
    if let SocketAddr::V4(addr) = dst {
        session.names().map(*addr.ip(), "www.netflix.com");
    }
    Route::of(
        Inbound::Tun,
        (dst, Network::Tcp),
        core.mode.get(),
        &core.shared.outbounds,
        &session,
    )
}

fn pin(core: &Core, group: &str, id: Option<&str>) {
    call(core, "select", json!({"group": group, "memberId": id})).unwrap();
}

#[test]
fn score_routes_through_each_subscriptions_own_alpn() {
    let dir = TempDir::new("score-tuic");
    let core = Core::new(path(&dir)).unwrap();
    let feeds = Feeds::new();
    let (server, alpns) = tuic::tuic_alpns();
    let link = tuic::link(server, "same");
    for (url, link) in [
        ("/h3", link.replace("#same", "&alpn=h3#same")),
        ("/tuic", link),
    ] {
        feeds.set(url, link.as_bytes());
        add(&core, &feeds, url).unwrap();
    }
    let dst = ([127, 0, 0, 1], 9).into();
    let members = &catalog(&core).groups[1].members;
    for (member, expected) in members.iter().zip(["h3", "tuic"]) {
        pin(&core, "g-Auto", Some(&member.id));
        let mut stream = core
            .runtime
            .block_on(route(&core, dst).connect(dst))
            .unwrap();
        assert_eq!(ping(&core, &mut stream, b"score").unwrap(), b"score");
        assert_eq!(lock(&alpns).last().unwrap(), expected);
        let _entered = core.runtime.enter();
        drop(stream);
    }
    assert_eq!(lock(&alpns).as_slice(), ["h3", "tuic"]);
}

#[test]
fn global_and_rule_follow_score_pins_then_return_to_automatic() {
    let dir = TempDir::new("score-pins");
    let core = Core::new(path(&dir)).unwrap();
    let feeds = Feeds::new();
    feeds.set("/sub", b"socks5://127.0.0.1:1#a\nsocks5://127.0.0.1:2#b");
    add(&core, &feeds, "/sub").unwrap();
    let dst = ([104, 16, 0, 1], 443).into();
    for (mode, group) in [(Mode::Global, "g-Proxy"), (Mode::Rule, "g-Streaming")] {
        core.set_mode(mode).unwrap();
        let chosen = || {
            let Route::Proxy(d, _) = route(&core, dst) else {
                panic!("group refused")
            };
            assert_eq!(d.group.as_ref(), group);
            assert_eq!(d.rule.is_some(), mode == Mode::Rule);
            let score = &d.score.as_ref().unwrap().feedback;
            assert_eq!(score.attributions()[0].group, "g-Auto");
            d.node.name.clone()
        };
        let ordinary = chosen();
        let catalog = catalog(&core);
        let other = catalog.groups[1]
            .members
            .iter()
            .find(|m| m.name != ordinary)
            .unwrap();
        pin(&core, "g-Auto", Some(&other.id));
        assert_eq!(chosen(), other.name);
        pin(&core, "g-Auto", None);
        assert_eq!(chosen(), ordinary);
    }
}

fn flow_nodes(core: &Core, group: &str) -> [String; 2] {
    let view = core.shared.outbounds.view();
    let dst = ([1, 1, 1, 1], 443).into();
    [Network::Tcp, Network::Udp].map(|net| {
        view.for_flow(group, dst, net, || None, None)
            .unwrap()
            .node
            .name
            .clone()
    })
}

#[test]
fn network_pins_route_nested_groups_independently() {
    let dir = TempDir::new("api-network-select");
    let feeds = Feeds::new();
    let first = b"socks5://127.0.0.1:1#a\n";
    feeds.set(
        "/sub",
        &[first.as_slice(), b"socks5://127.0.0.1:2#b\n"].concat(),
    );
    let core = Core::new(path(&dir)).unwrap();
    add(&core, &feeds, "/sub").unwrap();
    let ids: Vec<_> = lock(&core.shared.catalog)
        .model
        .nodes
        .iter()
        .map(|n| n.id.clone())
        .collect();
    let select = |c: &Core, net: Option<&str>, member: Option<&String>| {
        let mut args = json!({"group":"g-Proxy", "memberId":member});
        if let Some(net) = net {
            args["network"] = json!(net);
        }
        call(c, "select", args).unwrap();
    };
    let routes = |c: &Core| flow_nodes(c, "g-Streaming");
    select(&core, None, Some(&ids[1]));
    assert_eq!(routes(&core), ["b", "b"]);
    select(&core, Some("tcp"), Some(&ids[0]));
    assert_eq!(routes(&core), ["a", "b"]);
    select(&core, None, Some(&ids[1]));
    assert_eq!(routes(&core), ["b", "b"]);
    select(&core, Some("udp"), None);
    assert_eq!(routes(&core), ["b", "a"]);
    drop(core);
    let core = Core::new(path(&dir)).unwrap();
    assert_eq!(routes(&core), ["b", "a"]);
    select(&core, None, Some(&ids[0]));
    select(&core, Some("udp"), Some(&ids[1]));
    assert_eq!(routes(&core), ["a", "b"]);
    select(&core, Some("tcp"), Some(&ids[1]));
    assert_eq!(routes(&core), ["b", "b"]);
    feeds.set("/sub", first);
    call(&core, "updateSubscription", json!({"id":"s1"})).unwrap();
    assert_eq!(routes(&core), ["a", "a"]);
    feeds.set(
        "/sub",
        &[first.as_slice(), b"socks5://127.0.0.1:2#b\n"].concat(),
    );
    call(&core, "updateSubscription", json!({"id":"s1"})).unwrap();
    assert_eq!(routes(&core), ["a", "a"]);
}

#[test]
fn score_catalog_reports_each_networks_choice_after_restart() {
    let dir = TempDir::new("score-split");
    let core = Core::new(path(&dir)).unwrap();
    let feeds = Feeds::new();
    feeds.set("/sub", b"socks5://127.0.0.1:1#a\nsocks5://127.0.0.1:2#b");
    add(&core, &feeds, "/sub").unwrap();
    let members = &catalog(&core).groups[1].members;
    let (a, b) = (&members[0].id, &members[1].id);
    pin(&core, "g-Auto", Some(a));
    call(
        &core,
        "select",
        json!({"group":"g-Auto", "memberId":b, "network":"udp"}),
    )
    .unwrap();
    assert_eq!(flow_nodes(&core, "g-Auto"), ["a", "b"]);
    let live = &catalog(&core).groups[1];
    assert!(live.networks_differ);
    assert!(live.tcp_pinned && live.udp_pinned);
    assert_eq!(live.tcp_selected_id.as_ref(), Some(a));
    assert_eq!(live.udp_selected_id.as_ref(), Some(b));
    call(
        &core,
        "select",
        json!({"group":"g-Auto", "memberId":null, "network":"tcp"}),
    )
    .unwrap();
    let reset = &catalog(&core).groups[1];
    assert!(!reset.tcp_pinned && reset.udp_pinned);
    assert!(reset.networks_differ);
    drop(core);
    let core = Core::new(path(&dir)).unwrap();
    let group = &catalog(&core).groups[1];
    assert!(!group.tcp_pinned && group.udp_pinned);
    assert!(group.networks_differ);
    assert_eq!(group.tcp_selected_id.as_ref(), Some(a));
    assert_eq!(group.udp_selected_id.as_ref(), Some(b));
    pin(&core, "g-Auto", Some(b));
    let group = &catalog(&core).groups[1];
    assert!(!group.networks_differ);
    assert_eq!(group.tcp_selected_id.as_ref(), Some(b));
    assert_eq!(group.udp_selected_id.as_ref(), Some(b));
}

#[test]
fn a_rejected_reload_keeps_score_evidence_and_authority() {
    let dir = TempDir::new("score-reload");
    let core = Core::new(path(&dir)).unwrap();
    let feeds = Feeds::new();
    let (proxy, _) = socks5();
    feeds.set("/sub", format!("socks5://{proxy}#a").as_bytes());
    add(&core, &feeds, "/sub").unwrap();
    let dst = echo();
    let dial = |r: &Route| {
        core.runtime.block_on(r.connect(dst)).unwrap();
    };
    let score = || Arc::clone(&lock(&core.shared.catalog).model.score);
    let r = route(&core, dst);
    dial(&r.clone());
    if let Err(error) = core.runtime.block_on(r.connect(dst)) {
        assert_eq!(error.kind(), std::io::ErrorKind::PermissionDenied);
    }
    assert_eq!(score().manager.score_state().root_business_starts(), 1);
    let cells = score().manager.score_cache_snapshot().exact_cells;
    assert!(cells > 0);
    let temp = dir.path().join("state.json.tmp");
    std::fs::create_dir(&temp).unwrap();
    feeds.set("/sub", b"socks5://127.0.0.1:1#replacement");
    let reload = || call(&core, "updateSubscription", json!({"id":"s1"}));
    assert_eq!(reload(), Err(ErrorCode::Internal));
    assert_eq!(score().manager.score_cache_snapshot().exact_cells, cells);
    dial(&route(&core, dst));
    assert_eq!(score().manager.score_state().root_business_starts(), 2);
    std::fs::remove_dir(temp).unwrap();
    feeds.set("/sub", format!("socks5://{proxy}#renamed").as_bytes());
    reload().unwrap();
    assert_eq!(score().manager.score_cache_snapshot().exact_cells, cells);
    assert_eq!(score().manager.score_state().root_business_starts(), 2);
}

#[test]
fn reading_catalog_and_score_state_spends_no_score_budget() {
    let dir = TempDir::new("score-read");
    let core = Core::new(path(&dir)).unwrap();
    let feeds = Feeds::new();
    feeds.set("/sub", b"socks5://127.0.0.1:1#a\nsocks5://127.0.0.1:2#b");
    add(&core, &feeds, "/sub").unwrap();
    let dst = ([104, 16, 0, 1], 443).into();
    let Route::Proxy(d, _) = route(&core, dst) else {
        panic!("no Score leaf")
    };
    let reporter = d.score.as_ref().unwrap().feedback.begin().unwrap().start();
    reporter.setup_succeeded();
    reporter.first_response();
    reporter.finish(honk_outbound::group::ScoreOutcome::Success);
    let score = Arc::clone(&lock(&core.shared.catalog).model.score);
    let manager = &score.manager;
    let counts = || {
        [SelectionNetwork::Tcp, SelectionNetwork::Udp].map(|net| {
            (
                manager.score_budget_counters("g-Auto", net),
                manager.score_verification_counters("g-Auto", net),
                manager
                    .score_verification_for_network("g-Auto", net)
                    .unwrap()
                    .0,
            )
        })
    };
    let trial = route(&core, dst);
    assert_eq!(counts()[0].0.reserved, 1);
    drop(trial);
    let before = counts();
    assert!(catalog(&core).groups[1].selected_id.is_some());
    for _ in 0..2 {
        let state = call(&core, "scoreState", json!({})).unwrap();
        assert_eq!(state["rootBusinessStarts"], 1);
        for (net, (budget, _, selected)) in ["tcp", "udp"].into_iter().zip(&before) {
            let row = &state["groups"][0][net];
            assert_eq!(row["selected"], *selected);
            assert_eq!(
                row["counters"],
                json!({
                    "businessStarts": budget.business_starts, "trialSuccess": budget.trial_success,
                    "trialFailure": budget.trial_failure, "trialCancelled": budget.trial_cancelled,
                })
            );
        }
    }
    assert_eq!(counts(), before);
    assert_eq!(score.manager.score_state().root_business_starts(), 1);
}

#[test]
fn nested_score_retries_one_setup_then_relays_the_reply() {
    fn first_fails(target: &str) -> std::io::Result<std::net::TcpStream> {
        static FIRST: AtomicBool = AtomicBool::new(true);
        if FIRST.swap(false, SeqCst) {
            return Err(std::io::ErrorKind::ConnectionRefused.into());
        }
        std::net::TcpStream::connect(target)
    }
    let dir = TempDir::new("score-retry");
    let core = Core::new(path(&dir)).unwrap();
    let feeds = Feeds::new();
    let (a, at_a) = socks5_with([127, 0, 0, 1], first_fails);
    let (b, at_b) = socks5_with([127, 0, 0, 1], first_fails);
    feeds.set(
        "/sub",
        format!("socks5://{a}#HK01\nsocks5://{b}#HK02").as_bytes(),
    );
    add(&core, &feeds, "/sub").unwrap();
    let score = {
        let mut c = lock(&core.shared.catalog);
        let model = &mut c.model;
        model.groups[1].members = vec!["g-HK".into()];
        let index = model
            .nodes
            .iter()
            .enumerate()
            .map(|(i, n)| (n.id.clone(), i))
            .collect();
        model.score = Arc::new(Score::new(
            &model.groups,
            &model.nodes,
            &index,
            Some(&model.score),
        ));
        core.shared
            .publish_nodes(&c, core.shared.outbounds.build(&c.model.nodes));
        Arc::clone(&c.model.score)
    };
    let (tun, peer) = tun_and_peer();
    core.start(
        tun,
        r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":[]}"#,
    )
    .unwrap();
    let mut client = Client::new(peer);
    let id = client.connect(echo());
    assert_eq!(client.exchange(id, b"once").unwrap(), b"once");
    assert_eq!((at_a.load(SeqCst), at_b.load(SeqCst)), (1, 1));
    assert_eq!(score.manager.score_state().root_business_starts(), 1);
    for group in ["g-Auto", "g-HK"] {
        let budget = score
            .manager
            .score_budget_counters(group, SelectionNetwork::Tcp);
        assert_eq!(budget.business_starts, 1, "{group}");
    }
    let state = call(&core, "scoreState", json!({})).unwrap();
    assert_eq!(state["rootBusinessStarts"], 1);
    let groups = state["groups"].as_array().unwrap();
    assert_eq!(groups.len(), 2);
    for group in groups {
        assert_eq!(group["tcp"]["counters"]["businessStarts"], 1);
        assert_eq!(group["udp"]["counters"]["businessStarts"], 0);
    }
}

#[test]
fn a_recovered_network_can_dial_after_ten_failures_on_every_leaf() {
    use tokio::net::TcpSocket;

    let dir = TempDir::new("score-uplink");
    let core = Core::new(path(&dir)).unwrap();
    let _enter = core.runtime.enter();
    // Bound but not listening: both proxies refuse TCP until the uplink returns.
    let sockets = [(); 2].map(|()| {
        let socket = TcpSocket::new_v4().unwrap();
        socket.bind(([127, 0, 0, 1], 0).into()).unwrap();
        socket
    });
    let [a, b] = sockets.each_ref().map(|s| s.local_addr().unwrap());
    let feeds = Feeds::new();
    feeds.set("/sub", format!("socks5://{a}#a\nsocks5://{b}#b").as_bytes());
    add(&core, &feeds, "/sub").unwrap();
    // honk measures registration grace with std::time, not Tokio's clock.
    std::thread::sleep(std::time::Duration::from_secs(60));
    let dst = echo();
    for member in &catalog(&core).groups[1].members {
        pin(&core, "g-Auto", Some(&member.id));
        for _ in 0..10 {
            let r = route(&core, dst);
            assert!(!r.refused());
            assert!(core.runtime.block_on(r.connect(dst)).is_err());
        }
    }
    pin(&core, "g-Auto", None);
    let listeners = sockets.map(|s| s.listen(1).unwrap());
    let recovered = route(&core, dst);
    assert!(
        !recovered.refused(),
        "the restored proxies must still be candidates"
    );
    core.runtime.block_on(async {
        let serve = async {
            let (stream, _) =
                tokio::select! { a = listeners[0].accept() => a, b = listeners[1].accept() => b }
                    .unwrap();
            let stream = stream.into_std().unwrap();
            stream.set_nonblocking(false).unwrap();
            tokio::task::spawn_blocking(move || {
                crate::testutil::relay_socks(stream, |s| std::net::TcpStream::connect(s))
            })
            .await
            .unwrap()
            .unwrap();
        };
        let dial = async {
            drop(recovered.connect(dst).await.unwrap());
        };
        tokio::join!(serve, dial);
    });
}

#[test]
fn an_unchanged_refresh_keeps_probe_latency_and_registration_age() {
    use honk_outbound::alive::{IpVersion, ProbeDomain};
    use std::time::Duration;

    let dir = TempDir::new("score-health");
    let core = Core::new(path(&dir)).unwrap();
    let feeds = Feeds::new();
    feeds.set("/sub", b"socks5://127.0.0.1:1#a");
    add(&core, &feeds, "/sub").unwrap();
    let score = || Arc::clone(&lock(&core.shared.catalog).model.score);
    let id = lock(&core.shared.catalog).model.nodes[0].uuid;
    let (domain, family) = (ProbeDomain::Tcp, IpVersion::V4);
    score().probe(id, [127, 0, 0, 1].into(), Some(37));
    std::thread::sleep(Duration::from_secs(60));
    call(&core, "updateSubscription", json!({"id":"s1"})).unwrap();
    let health = &score().alive;
    assert_eq!(
        health.get_last_latency(id, domain, family),
        Some(Duration::from_millis(37))
    );
    for _ in 0..10 {
        health.report_unavailable_traffic(id, domain, family);
    }
    assert!(
        !health.is_alive_for(id, domain, family),
        "refresh restarted grace"
    );
}
