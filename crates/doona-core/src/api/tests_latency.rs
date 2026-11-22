//! Latency tests through `call` with stand-in probes: `testing` until the probes end, the
//! commit rule when a node is re-created or a test overlaps another, `stop` dropping probes, and
//! the bound on probes in flight. The last test runs honk's real probe through a loopback SOCKS5
//! node.

use super::tests_catalog::{Feeds, add, call, catalog, wait_for};
use super::{Core, Options};
use crate::dto::{Catalog, ErrorCode};
use crate::probe::{ProbeJob, Prober};
use crate::testutil::{TempDir, fixture, socks5};
use futures_util::FutureExt;
use serde_json::json;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering::SeqCst};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::watch;

/// A stand-in probe: each node answers once the gate opens, a node named `SG …` in 3 ms, `Other`
/// never, the rest in 50 ms. Counts the probes in flight and the most at once.
struct Stand {
    open: watch::Sender<bool>,
    now: Arc<AtomicUsize>,
    most: Arc<AtomicUsize>,
    seen: Arc<Mutex<Vec<String>>>,
}

impl Stand {
    fn new(open: bool) -> Stand {
        Stand {
            open: watch::channel(open).0,
            now: Arc::default(),
            most: Arc::default(),
            seen: Arc::default(),
        }
    }

    fn prober(&self) -> Prober {
        let (gate, now, most, seen) = (
            self.open.subscribe(),
            Arc::clone(&self.now),
            Arc::clone(&self.most),
            Arc::clone(&self.seen),
        );
        Arc::new(move |job: ProbeJob| {
            let (mut gate, now, most) = (gate.clone(), Arc::clone(&now), Arc::clone(&most));
            seen.lock().unwrap().push(job.node.name.clone());
            async move {
                let inside = now.fetch_add(1, SeqCst) + 1;
                most.fetch_max(inside, SeqCst);
                let _ = gate.wait_for(|open| *open).await;
                now.fetch_sub(1, SeqCst);
                let ms = if job.node.name.starts_with("SG") {
                    3
                } else {
                    50
                };
                (job.node.name != "Other").then(|| Duration::from_millis(ms))
            }
            .boxed()
        })
    }

    fn open(&self) {
        let _ = self.open.send(true);
    }

    fn started(&self) -> usize {
        self.seen.lock().unwrap().len()
    }
}

fn setup(stand: &Stand) -> (TempDir, Feeds, Core) {
    let dir = TempDir::new("api-latency");
    let feeds = Feeds::new();
    feeds.set("/sub", &fixture("links.txt"));
    let core = Core::with_prober(dir.path().to_str().unwrap(), stand.prober());
    add(&core, &feeds, "/sub").unwrap();
    (dir, feeds, core)
}

fn testing(c: &Catalog) -> bool {
    c.groups.iter().any(|g| g.testing)
}

fn wait_started(stand: &Stand, n: usize) {
    let begun = std::time::Instant::now();
    while stand.started() < n {
        assert!(
            begun.elapsed() < Duration::from_secs(10),
            "{}",
            stand.started()
        );
        std::thread::sleep(Duration::from_millis(5));
    }
}

#[test]
fn a_test_returns_at_once_and_its_results_pick_the_fastest() {
    let stand = Stand::new(false);
    let (_dir, _feeds, core) = setup(&stand);
    assert_eq!(
        call(&core, "testLatency", json!({"group": "g-Auto"})),
        Ok(json!({}))
    );
    let during = catalog(&core);
    assert!(during.groups[1].testing && !during.groups[0].testing);
    stand.open();
    let after = wait_for(&core, |c| !testing(c));
    let auto = &after.groups[1];
    let other = auto.members.iter().find(|m| m.name == "Other").unwrap();
    assert!(other.failed && other.latency_ms.is_none());
    let sg = auto.members.iter().find(|m| m.name == "SG 01").unwrap();
    assert_eq!(sg.latency_ms, Some(3));
    assert_eq!(auto.selected_id.as_deref(), Some(sg.id.as_str()));
    // Proxy follows Auto; groups never carry a latency.
    assert!(after.groups[0].members[0].latency_ms.is_none());
    assert_eq!(
        call(&core, "testLatency", json!({"group": "g-DE"})),
        Err(ErrorCode::NotFound)
    );
}

#[test]
fn a_probe_that_finishes_after_its_node_was_re_created_is_dropped() {
    let stand = Stand::new(false);
    let (_dir, _feeds, core) = setup(&stand);
    call(&core, "testLatency", json!({"group": null})).unwrap();
    wait_started(&stand, 9);
    // The same feed again: every node keeps its id but is a new incarnation.
    call(&core, "updateSubscription", json!({"id": "s1"})).unwrap();
    stand.open();
    let after = wait_for(&core, |c| !testing(c));
    assert!(
        after.groups[1]
            .members
            .iter()
            .all(|m| m.latency_ms.is_none() && !m.failed)
    );
}

#[test]
fn overlapping_tests_keep_the_latest_results_and_test_until_both_end() {
    let stand = Stand::new(false);
    let (_dir, _feeds, core) = setup(&stand);
    call(&core, "testLatency", json!({"group": "g-HK"})).unwrap();
    wait_started(&stand, 3);
    call(&core, "testLatency", json!({"group": "g-HK"})).unwrap();
    wait_started(&stand, 6);
    assert!(catalog(&core).groups[2].testing);
    stand.open();
    let after = wait_for(&core, |c| !testing(c));
    let hk = &after.groups[2];
    assert!(hk.members.iter().all(|m| m.latency_ms.is_some()));
}

#[test]
fn stop_drops_the_probes_under_way() {
    let stand = Stand::new(false);
    let (_dir, _feeds, core) = setup(&stand);
    call(&core, "testLatency", json!({"group": null})).unwrap();
    wait_started(&stand, 9);
    core.stop();
    let after = wait_for(&core, |c| !testing(c));
    stand.open();
    std::thread::sleep(Duration::from_millis(100));
    assert_eq!(catalog(&core), after);
    assert!(
        after.groups[1]
            .members
            .iter()
            .all(|m| m.latency_ms.is_none())
    );
}

/// A probe that failed after it went on to dial its node at an address on the local network
/// carries `local_network`: Linux refuses TCP to a multicast address, which Android counts as
/// local, at once. One whose node is on loopback carries no code.
#[test]
fn a_probe_refused_on_its_way_to_a_local_node_says_so() {
    let dir = TempDir::new("api-latency-local");
    let closed = {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.local_addr().unwrap()
    };
    let feeds = Feeds::new();
    let feed = format!("socks5://224.0.0.1:1080#LAN\nsocks5://{closed}#LO\n");
    feeds.set("/lan", feed.as_bytes());
    let options = Options {
        probe_url: format!("http://{closed}/generate_204"),
        ..Options::default()
    };
    let core = Core::with_options(dir.path().to_str().unwrap(), options).unwrap();
    add(&core, &feeds, "/lan").unwrap();
    call(&core, "testLatency", json!({"group": null})).unwrap();
    let after = wait_for(&core, |c| {
        !testing(c) && c.groups[1].members.iter().all(|m| m.failed)
    });
    let code = |name: &str| {
        let m = after.groups[1]
            .members
            .iter()
            .find(|m| m.name == name)
            .unwrap();
        m.error
    };
    assert_eq!(code("LAN"), Some(ErrorCode::LocalNetwork));
    assert_eq!(code("LO"), None);
}

#[test]
fn at_most_32_probes_run_at_once() {
    let stand = Stand::new(false);
    let dir = TempDir::new("api-latency-many");
    let feeds = Feeds::new();
    let feed: String = (1..=40)
        .map(|i| format!("trojan://secret@192.0.2.{i}:443#n{i}\n"))
        .collect();
    feeds.set("/many", feed.as_bytes());
    let core = Core::with_prober(dir.path().to_str().unwrap(), stand.prober());
    add(&core, &feeds, "/many").unwrap();
    call(&core, "testLatency", json!({"group": null})).unwrap();
    wait_started(&stand, 32);
    std::thread::sleep(Duration::from_millis(100));
    assert_eq!(stand.started(), 32);
    stand.open();
    let after = wait_for(&core, |c| !testing(c));
    assert_eq!(stand.most.load(SeqCst), 32);
    assert!(
        after.groups[1]
            .members
            .iter()
            .all(|m| m.latency_ms.is_some())
    );
}

#[test]
fn honk_probes_a_node_through_its_outbound() {
    let dir = TempDir::new("api-latency-honk");
    let target = crate::testutil::http(|_| b"HTTP/1.1 204 No Content\r\n\r\n".to_vec());
    let (proxy, _connections) = socks5();
    let feeds = Feeds::new();
    feeds.set("/local", format!("socks5://{proxy}#local\n").as_bytes());
    let options = Options {
        probe_url: format!("http://{target}/generate_204"),
        ..Options::default()
    };
    let core = Core::with_options(dir.path().to_str().unwrap(), options).unwrap();
    add(&core, &feeds, "/local").unwrap();
    call(&core, "testLatency", json!({"group": "g-Auto"})).unwrap();
    let after = wait_for(&core, |c| !testing(c));
    let node = &after.groups[1].members[0];
    assert!(node.latency_ms.is_some() && !node.failed, "{node:?}");
    // The data path's view of the groups names the node.
    let dialer = core.shared.outbounds.dialer("g-Proxy").unwrap();
    assert_eq!(dialer.node.name, "local");
}

#[test]
fn no_test_registers_between_the_probe_token_and_the_lifecycle_change() {
    let stand = Stand::new(false);
    let (_dir, _feeds, core) = setup(&stand);
    let ran = Arc::new(AtomicBool::new(false));
    let seen = Arc::clone(&ran);
    // Inside `stop`, once the probe token is new: a test registers here if the catalog lock,
    // which registration takes, is free.
    core.hooks.on_teardown(move |core| {
        seen.store(true, SeqCst);
        let free = core.shared.catalog.try_lock().is_ok();
        if free {
            core.test_latency(Some("g-HK".to_owned())).unwrap();
        }
    });
    core.stop();
    assert!(ran.load(SeqCst));
    stand.open();
    let after = wait_for(&core, |c| !testing(c));
    assert!(
        after.groups[2]
            .members
            .iter()
            .all(|m| m.latency_ms.is_none() && !m.failed)
    );
    // A test registered after `stop` runs and commits.
    call(&core, "testLatency", json!({"group": "g-HK"})).unwrap();
    let after = wait_for(&core, |c| !testing(c));
    assert!(
        after.groups[2]
            .members
            .iter()
            .all(|m| m.latency_ms.is_some())
    );
}

/// Never becomes ready.
fn never_ready(
    tun: crate::tunnel::TunHandle,
    _: crate::tunnel::Settings,
    _: crate::tunnel::Gen,
    ready: crate::tunnel::StartSignal,
) -> futures_util::future::BoxFuture<'static, ()> {
    Box::pin(async move {
        let _held = (tun, ready);
        std::future::pending::<()>().await;
    })
}

/// Probes begun while no generation runs go on the carriers the next start claims. When that
/// start fails, its carriers end under the probes, which measured nothing: no node is shown as
/// failed.
#[test]
fn probes_whose_carriers_end_under_them_record_nothing() {
    let stand = Stand::new(false);
    let dir = TempDir::new("api-latency-ended");
    let feeds = Feeds::new();
    feeds.set("/sub", &fixture("links.txt"));
    let store = crate::state::Store::new(dir.path());
    let bound = Duration::from_millis(300);
    let launch = (never_ready as crate::tunnel::Launch, bound);
    let core = Core::build(store, Options::default(), launch, stand.prober()).unwrap();
    add(&core, &feeds, "/sub").unwrap();
    call(&core, "testLatency", json!({"group": null})).unwrap();
    wait_started(&stand, 9);
    let (tun, _probe) = crate::testutil::tun();
    let config = r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":[]}"#;
    assert_eq!(core.start(tun, config), Err(ErrorCode::Internal));
    let after = wait_for(&core, |c| !testing(c));
    let members = &after.groups[1].members;
    let failed: Vec<_> = members.iter().filter(|m| m.failed).collect();
    assert!(failed.is_empty(), "{failed:?}");
}

/// A test whose carriers were prepared when a `stop` ended them registers after that stop, so the
/// stop does not drop it: it runs on new carriers and records each probe's result.
#[test]
fn a_test_prepared_before_a_stop_runs_on_new_carriers() {
    let stand = Stand::new(true);
    let (_dir, _feeds, core) = setup(&stand);
    let (tun, _probe) = crate::testutil::tun();
    let config = r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":[]}"#;
    core.start(tun, config).unwrap();
    core.hooks.on_prepared(|core| core.stop());
    call(&core, "testLatency", json!({"group": "g-HK"})).unwrap();
    let after = wait_for(&core, |c| !testing(c));
    let members = &after.groups[2].members;
    assert!(
        members.iter().all(|m| m.latency_ms.is_some()),
        "{members:?}"
    );
}

/// A stand-in probe that, as honk's does, reaches a node only through the registry it is given:
/// a node that registry lacks fails, any other answers in 7 ms.
fn through_registry() -> Prober {
    Arc::new(|job: ProbeJob| {
        let found = job.registry.as_ref().and_then(|r| r.get(&job.node.id));
        async move { found.map(|_| Duration::from_millis(7)) }.boxed()
    })
}

/// A refresh that replaces node A with B while a test registers: the test probes B through the
/// registry published with B, so B gets a latency.
#[test]
fn a_test_registered_across_a_refresh_probes_the_new_nodes_through_their_registry() {
    let dir = TempDir::new("api-latency-refresh");
    let feeds = Feeds::new();
    feeds.set("/sub", b"trojan://secret@192.0.2.1:443#A\n");
    let core = Core::with_prober(dir.path().to_str().unwrap(), through_registry());
    add(&core, &feeds, "/sub").unwrap();
    feeds.set("/sub", b"trojan://secret@192.0.2.2:443#B\n");
    core.hooks.on_registering(|core| {
        call(core, "updateSubscription", json!({"id": "s1"})).unwrap();
    });
    call(&core, "testLatency", json!({"group": "g-Auto"})).unwrap();
    let after = wait_for(&core, |c| !testing(c));
    let members = &after.groups[1].members;
    assert_eq!(members.len(), 1, "{members:?}");
    let b = &members[0];
    assert_eq!(b.name, "B");
    assert!(b.latency_ms.is_some() && !b.failed, "{b:?}");
}
