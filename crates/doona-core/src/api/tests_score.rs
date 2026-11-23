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
fn reading_the_catalog_spends_no_score_budget() {
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
    let counts = || {
        score
            .manager
            .score_budget_counters("g-Auto", SelectionNetwork::Tcp)
    };
    let trial = route(&core, dst);
    assert_eq!(counts().reserved, 1);
    drop(trial);
    let before = counts();
    for _ in 0..5 {
        assert!(catalog(&core).groups[1].selected_id.is_some());
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
            model.score.manager.score_state(),
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
}
