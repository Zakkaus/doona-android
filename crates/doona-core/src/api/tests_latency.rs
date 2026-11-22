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
use std::sync::atomic::{AtomicUsize, Ordering::SeqCst};
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
fn a_test_registered_while_stop_runs_is_dropped_with_it() {
    let stand = Stand::new(false);
    let (_dir, _feeds, core) = setup(&stand);
    std::thread::scope(|scope| {
        // Holding the lifecycle lock stops `stop` where it changes the lifecycle.
        let life = super::lock(&core.life);
        let stopping = scope.spawn(|| core.stop());
        std::thread::sleep(Duration::from_millis(200));
        call(&core, "testLatency", json!({"group": "g-HK"})).unwrap();
        wait_started(&stand, 3);
        drop(life);
        stopping.join().unwrap();
    });
    stand.open();
    let after = wait_for(&core, |c| !testing(c));
    assert!(
        after.groups[2]
            .members
            .iter()
            .all(|m| m.latency_ms.is_none() && !m.failed)
    );
}
