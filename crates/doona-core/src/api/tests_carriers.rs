//! The carriers against `stop`: their sockets are closed when it returns, even while a task of
//! the generation holds each of the core's workers, and a registry built on carriers it ended
//! is built again on new ones before it is published; two registries, for nodes of one honk id
//! that dial differently, through probes, a restart and `prepare`.

use super::tests::ping;
use super::tests_catalog::{Feeds, add, call, catalog, path, wait_for};
use super::{Core, Options};
use crate::dto::Mode;
use crate::testutil::{TempDir, closed, one_dial_ceiling, tuic, tun};
use crate::tunnel::route::{Inbound, Route};
use crate::tunnel::{Gen, Launch, STOP_WAIT, Session, Settings, StartSignal, TunHandle};
use futures_util::future::BoxFuture;
use serde_json::json;
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering::SeqCst};
use std::time::{Duration, Instant};

const CONFIG: &str =
    r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":["192.168.8.1"]}"#;
/// How long each held worker stays held.
const HELD: Duration = Duration::from_millis(2500);

/// Ready at once, then idle.
pub(super) fn idle(
    tun: TunHandle,
    _: Settings,
    _: Gen,
    ready: StartSignal,
) -> BoxFuture<'static, ()> {
    Box::pin(async move {
        let _held = tun;
        ready.send();
        std::future::pending::<()>().await;
    })
}

/// Holds both of the core's workers for [`HELD`], as two tasks caught in long operations would.
fn hold_both_workers(core: &Core) {
    let held = Arc::new(AtomicUsize::new(0));
    for _ in 0..2 {
        let held = Arc::clone(&held);
        core.runtime.spawn(async move {
            held.fetch_add(1, SeqCst);
            std::thread::sleep(HELD);
        });
    }
    let begun = Instant::now();
    while held.load(SeqCst) < 2 {
        assert!(begun.elapsed() < Duration::from_secs(5), "a worker is free");
        std::thread::sleep(Duration::from_millis(5));
    }
}

/// The UDP socket bound to `port`, as a descriptor links to it.
fn udp_socket(port: u16) -> Option<String> {
    ["/proc/net/udp", "/proc/net/udp6"]
        .into_iter()
        .filter_map(|table| std::fs::read_to_string(table).ok())
        .find_map(|text| {
            text.lines().skip(1).find_map(|line| {
                let f: Vec<&str> = line.split_whitespace().collect();
                let (local, inode) = (f.get(1)?.rsplit(':').next()?, f.get(9)?);
                (u16::from_str_radix(local, 16).ok()? == port).then(|| format!("socket:[{inode}]"))
            })
        })
}

/// Whether a descriptor of this process refers to `socket`.
fn open(socket: &str) -> bool {
    std::fs::read_dir("/proc/self/fd")
        .unwrap()
        .flatten()
        .any(|e| std::fs::read_link(e.path()).is_ok_and(|t| t.to_str() == Some(socket)))
}

/// The TUIC client keeps an established QUIC connection, and the endpoint that owns its
/// socket, for the next stream. `stop` closes that socket before it returns, although no task
/// of the core's runtime can run.
#[test]
fn stop_closes_an_established_quic_connection_while_the_workers_are_held() {
    let dir = TempDir::new("api-held-quic");
    let (server, clients) = tuic::tuic();
    let feeds = Feeds::new();
    feeds.set("/sub", format!("{}\n", tuic::link(server, "tq")).as_bytes());
    let core = Core::with_launch(path(&dir), idle, Duration::from_secs(10));
    add(&core, &feeds, "/sub").unwrap();
    let (dup, probe) = tun();
    core.start(dup, CONFIG).unwrap();
    // The server echoes; the destination is never dialed.
    let destination: SocketAddr = ([127, 0, 0, 1], 9).into();
    let route = Route::of(
        Inbound::Tun,
        crate::tunnel::route::tests::ANYWHERE,
        Mode::Global,
        &core.shared.outbounds,
        &Arc::new(Session::new(0, std::sync::Weak::new())),
    );
    let mut stream = core.runtime.block_on(route.connect(destination)).unwrap();
    assert_eq!(ping(&core, &mut stream, b"kept").unwrap(), b"kept");
    {
        let _entered = core.runtime.enter();
        drop(stream);
    }
    let client = clients.lock().unwrap()[0];
    let socket = udp_socket(client.port()).expect("the client's QUIC socket");
    assert!(open(&socket));
    hold_both_workers(&core);
    let begun = Instant::now();
    core.stop();
    let still_open = open(&socket);
    let took = begun.elapsed();
    assert!(!still_open, "the QUIC socket was open when stop returned");
    assert!(took < STOP_WAIT + STOP_WAIT / 2, "{took:?}");
    assert!(closed(&probe));
}

/// A refresh took the current carriers for its registry when a `stop` ended them and shut them
/// down. The registry is built again on new carriers before it is published, so a Global start
/// that follows finds the node, not `no_nodes`.
#[test]
fn a_refresh_whose_carriers_a_stop_ended_is_built_again() {
    let dir = TempDir::new("api-stale-build");
    let (proxy, _) = crate::testutil::socks5();
    let feeds = Feeds::new();
    feeds.set("/sub", format!("socks5://{proxy}#s5\n").as_bytes());
    let core = Arc::new(Core::with_launch(path(&dir), idle, Duration::from_secs(10)));
    let added = add(&core, &feeds, "/sub").unwrap();
    let (first, _first) = tun();
    core.start(first, CONFIG).unwrap();
    let stopping = Arc::clone(&core);
    core.shared.outbounds.on_build(move || stopping.stop());
    call(&core, "updateSubscription", json!({"id": added["id"]})).unwrap();
    let (second, _second) = tun();
    assert_eq!(core.start(second, CONFIG), Ok(()));
}

/// Opens a stream through the Proxy group's node, as a flow from the tun would in Global mode,
/// and checks that it echoes.
fn dial_proxy(core: &Core) {
    let destination: SocketAddr = ([127, 0, 0, 1], 9).into();
    let session = Arc::new(Session::new(0, std::sync::Weak::new()));
    let route = Route::of(
        Inbound::Tun,
        crate::tunnel::route::tests::ANYWHERE,
        Mode::Global,
        &core.shared.outbounds,
        &session,
    );
    let mut stream = core.runtime.block_on(route.connect(destination)).unwrap();
    assert_eq!(ping(core, &mut stream, b"echo").unwrap(), b"echo");
    let _entered = core.runtime.enter();
    drop(stream);
}

/// Two subscriptions bring a TUIC node of one honk id, one with ALPN `h3` and one with none,
/// which offers `tuic`; honk's id leaves the TUIC ALPN out, so each is in a registry of its own.
/// A latency test probes each through its own runtime. After a stop and a start, whose `prepare`
/// builds both registries again on new carriers within one dial ceiling, a flow through each
/// node offers that node's ALPN, and so does a probe of a test prepared after the next stop.
#[test]
fn nodes_of_one_id_keep_their_own_alpn_through_probes_and_a_restart() {
    let dir = TempDir::new("api-two-registries");
    let (server, alpns) = tuic::tuic_alpns();
    let feeds = Feeds::new();
    let link = tuic::link(server, "tuic");
    feeds.set(
        "/h3",
        format!("{}\n", link.replace("#tuic", "&alpn=h3#h3")).as_bytes(),
    );
    feeds.set("/tuic", format!("{link}\n").as_bytes());
    // The server echoes each request instead of relaying it, so every probe fails, after the
    // handshake the test reads.
    let options = Options {
        probe_url: "http://127.0.0.1:9/generate_204".into(),
        ..Options::default()
    };
    let store = crate::state::Store::new(dir.path());
    let launch = (idle as Launch, Duration::from_secs(10));
    let core = Core::build(store, options, launch, crate::probe::urltest()).unwrap();
    add(&core, &feeds, "/h3").unwrap();
    add(&core, &feeds, "/tuic").unwrap();
    let registries = || core.shared.outbounds.registries();
    assert_eq!(registries().len(), 2);
    // The ALPN of each connection the server took while `f` ran, sorted.
    let offered = |f: &dyn Fn()| {
        let from = alpns.lock().unwrap().len();
        f();
        let mut offered = alpns.lock().unwrap()[from..].to_vec();
        offered.sort();
        offered
    };
    let probe = || {
        call(&core, "testLatency", json!({"group": null})).unwrap();
        wait_for(&core, |c| !c.groups.iter().any(|g| g.testing));
    };
    assert_eq!(offered(&probe), ["h3", "tuic"]);

    let (first, _first) = tun();
    core.start(first, CONFIG).unwrap();
    core.stop();
    let (second, _second) = tun();
    core.start(second, CONFIG).unwrap();
    assert_eq!(registries().len(), 2);
    assert!(one_dial_ceiling(&registries()));
    let proxy = catalog(&core)
        .groups
        .into_iter()
        .find(|g| g.id == "g-Proxy");
    for member in proxy.unwrap().members.iter().filter(|m| !m.is_group) {
        let pin = json!({"group": "g-Proxy", "memberId": member.id});
        assert_eq!(call(&core, "select", pin), Ok(json!({})));
        let dial = || dial_proxy(&core);
        assert_eq!(offered(&dial), [member.name.as_str()], "{}", member.name);
    }

    core.stop();
    assert_eq!(offered(&probe), ["h3", "tuic"]);
}
