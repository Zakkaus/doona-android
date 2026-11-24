//! Subscriptions and selections through `call`, against a loopback feed server: what is stored
//! and shown, the error codes, ids across refreshes and restarts, and an update that finishes
//! after its subscription was removed.

use super::{Core, Options, lock};
use crate::dto::{Catalog, ErrorCode, Snapshot};
use crate::testutil::{TempDir, fixture, http, response, target};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering::Relaxed};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

/// Feeds served by path; a held server keeps every request waiting until released.
pub(super) struct Feeds {
    addr: SocketAddr,
    bodies: Arc<Mutex<HashMap<String, Vec<u8>>>>,
    hold: Arc<(Mutex<(bool, usize)>, Condvar)>,
}

impl Feeds {
    pub fn new() -> Feeds {
        let bodies: Arc<Mutex<HashMap<String, Vec<u8>>>> = Arc::default();
        let hold: Arc<(Mutex<(bool, usize)>, Condvar)> = Arc::default();
        let (b, h) = (Arc::clone(&bodies), Arc::clone(&hold));
        let addr = http(move |head| {
            let (state, cv) = &*h;
            let mut s = state.lock().unwrap();
            s.1 += 1;
            cv.notify_all();
            while s.0 {
                s = cv.wait(s).unwrap();
            }
            drop(s);
            let found = b.lock().unwrap().get(target(head)).cloned();
            found.unwrap_or_else(|| response("404 Not Found", &[], b""))
        });
        Feeds { addr, bodies, hold }
    }

    pub fn set(&self, path: &str, body: &[u8]) {
        let quota = [("Subscription-Userinfo", "upload=1; download=2; total=0")];
        self.set_raw(path, response("200 OK", &quota, body));
    }

    pub fn set_raw(&self, path: &str, raw: Vec<u8>) {
        self.bodies.lock().unwrap().insert(path.to_owned(), raw);
    }

    pub fn url(&self, path: &str) -> String {
        format!("http://{}{path}", self.addr)
    }

    pub fn hold(&self) {
        self.hold.0.lock().unwrap().0 = true;
    }

    /// Waits until `n` requests arrived in all.
    pub fn arrived(&self, n: usize) {
        let (state, cv) = &*self.hold;
        let s = state.lock().unwrap();
        let (_s, timeout) = cv
            .wait_timeout_while(s, Duration::from_secs(10), |s| s.1 < n)
            .unwrap();
        assert!(!timeout.timed_out(), "no request arrived");
    }

    pub fn release(&self) {
        self.hold.0.lock().unwrap().0 = false;
        self.hold.1.notify_all();
    }
}

pub(super) fn call(core: &Core, command: &str, args: Value) -> Result<Value, ErrorCode> {
    let reply: Value = serde_json::from_str(&core.call(command, &args.to_string())).unwrap();
    match reply.get("ok") {
        Some(ok) => Ok(ok.clone()),
        None => Err(serde_json::from_value(reply["error"]["code"].clone()).unwrap()),
    }
}

pub(super) fn catalog(core: &Core) -> Catalog {
    let snapshot: Snapshot = serde_json::from_str(&core.snapshot(-1)).unwrap();
    snapshot.catalog.unwrap()
}

/// Polls the catalog until `done` holds, for 10 s at most.
pub(super) fn wait_for(core: &Core, done: impl Fn(&Catalog) -> bool) -> Catalog {
    let begun = Instant::now();
    loop {
        let c = catalog(core);
        if done(&c) {
            return c;
        }
        assert!(begun.elapsed() < Duration::from_secs(10), "{c:?}");
        std::thread::sleep(Duration::from_millis(10));
    }
}

pub(super) fn add(core: &Core, feeds: &Feeds, path: &str) -> Result<Value, ErrorCode> {
    call(
        core,
        "addSubscription",
        json!({"url": feeds.url(path), "name": null}),
    )
}

fn node_ids(c: &Catalog) -> Vec<(String, String)> {
    let mut ids: Vec<_> = c.groups[1]
        .members
        .iter()
        .map(|m| (m.id.clone(), m.name.clone()))
        .collect();
    ids.sort();
    ids
}

pub(super) fn path(dir: &TempDir) -> &str {
    dir.path().to_str().unwrap()
}

#[test]
fn a_subscription_is_added_shown_kept_across_a_restart_and_removed() {
    let dir = TempDir::new("api-subscription");
    let feeds = Feeds::new();
    feeds.set("/sub", &fixture("links.b64"));
    let core = Core::new(path(&dir)).unwrap();
    let added = add(&core, &feeds, "/sub").unwrap();
    assert_eq!(added["id"], "s1");
    assert_eq!(added["format"], "base64");
    assert_eq!(added["nodes"], 9);
    assert_eq!(added["name"], "127.0.0.1");
    assert_eq!(added["usedBytes"], 3);
    assert!(added["totalBytes"].is_null() && added["error"].is_null());
    assert!(added["updatedAtEpochMs"].as_i64().unwrap() > 0);
    let shown = catalog(&core);
    assert_eq!(shown.groups.len(), 10);
    assert_eq!(
        serde_json::to_value(&shown.subscriptions[0]).unwrap(),
        added
    );
    let saved = std::fs::read_to_string(dir.path().join("state.json")).unwrap();
    let saved: Value = serde_json::from_str(&saved).unwrap();
    assert_eq!(
        saved["subscriptions"][0]["links"].as_array().unwrap().len(),
        9
    );
    drop(core);
    let again = Core::new(path(&dir)).unwrap();
    assert_eq!(node_ids(&catalog(&again)), node_ids(&shown));
    assert_eq!(catalog(&again).subscriptions, shown.subscriptions);
    assert_eq!(
        call(&again, "removeSubscription", json!({"id": "s1"})),
        Ok(json!({}))
    );
    let empty = catalog(&again);
    assert!(empty.groups.is_empty() && empty.subscriptions.is_empty());
    // Ids are never given twice.
    assert_eq!(add(&again, &feeds, "/sub").unwrap()["id"], "s2");
}

#[test]
fn an_update_keeps_ids_and_drops_choices_whose_node_went() {
    let dir = TempDir::new("api-update");
    let feeds = Feeds::new();
    feeds.set("/sub", &fixture("links.txt"));
    let core = Core::new(path(&dir)).unwrap();
    add(&core, &feeds, "/sub").unwrap();
    let before = catalog(&core);
    let other = before.groups[0]
        .members
        .iter()
        .find(|m| m.name == "Other")
        .unwrap();
    let pin = json!({"group": "g-Proxy", "memberId": other.id});
    assert_eq!(call(&core, "select", pin), Ok(json!({})));
    let pinned = &catalog(&core).groups[0];
    assert!(pinned.pinned && pinned.selected_id.as_deref() == Some(other.id.as_str()));
    feeds.set("/sub", &fixture("reordered.txt"));
    let updated = call(&core, "updateSubscription", json!({"id": "s1"})).unwrap();
    assert_eq!(updated["nodes"], 8);
    let after = catalog(&core);
    let ids: HashMap<_, _> = node_ids(&before).into_iter().collect();
    assert!(node_ids(&after).iter().all(|(id, _)| ids.contains_key(id)));
    // "Other" left the feed: Proxy is back to its first member.
    assert!(!after.groups[0].pinned);
    assert_eq!(after.groups[0].selected_id.as_deref(), Some("g-Auto"));
}

/// A refresh publishes its registry and each group's node in one step: a flow that opens
/// meanwhile never gets a node its registry lacks. The reader reads before the first refresh and
/// after each one is published.
#[test]
fn a_refresh_publishes_its_registry_and_nodes_together() {
    let dir = TempDir::new("api-swap");
    let feeds = Feeds::new();
    let sets: [&[u8]; 2] = [b"socks5://127.0.0.1:1#a\n", b"socks5://127.0.0.1:2#b\n"];
    feeds.set("/sub", sets[0]);
    let core = Core::new(path(&dir)).unwrap();
    add(&core, &feeds, "/sub").unwrap();
    let outbounds = Arc::clone(&core.shared.outbounds);
    let done = Arc::new(AtomicBool::new(false));
    let reads = Arc::new(AtomicUsize::new(0));
    let reader = {
        let (done, reads) = (Arc::clone(&done), Arc::clone(&reads));
        std::thread::spawn(move || {
            let mut torn = 0usize;
            while !done.load(Relaxed) {
                let d = outbounds.dialer("g-Proxy").expect("Proxy has a node");
                torn += usize::from(d.registry.get(&d.node.id).is_none());
                reads.fetch_add(1, Relaxed);
            }
            torn
        })
    };
    let read_after = |n: usize| {
        let end = Instant::now() + Duration::from_secs(5);
        while reads.load(Relaxed) <= n && Instant::now() < end {
            std::thread::yield_now();
        }
        assert!(reads.load(Relaxed) > n, "the reader stopped reading");
    };
    read_after(0);
    for i in 1..=100 {
        feeds.set("/sub", sets[i % 2]);
        call(&core, "updateSubscription", json!({"id": "s1"})).unwrap();
        read_after(reads.load(Relaxed));
    }
    done.store(true, Relaxed);
    assert_eq!(
        reader.join().unwrap(),
        0,
        "flows saw a node without its registry"
    );
}

/// Refreshes while no generation runs keep only the registries still in use: after 200 of them
/// the first is freed, and the carriers keep the last two.
#[test]
fn refreshes_keep_only_the_registries_in_use() {
    let dir = TempDir::new("api-bounded");
    let feeds = Feeds::new();
    let sets: [&[u8]; 2] = [b"socks5://127.0.0.1:1#a\n", b"socks5://127.0.0.1:2#b\n"];
    feeds.set("/sub", sets[0]);
    let core = Core::new(path(&dir)).unwrap();
    add(&core, &feeds, "/sub").unwrap();
    let outbounds = &core.shared.outbounds;
    let first = Arc::downgrade(&outbounds.registry().unwrap());
    for i in 1..=200 {
        feeds.set("/sub", sets[i % 2]);
        if i == 200 {
            // Every registry replaced so far has retired: the last publish finds them freed.
            outbounds.wait_retired();
            assert_eq!(first.strong_count(), 0, "the first registry is still held");
        }
        call(&core, "updateSubscription", json!({"id": "s1"})).unwrap();
    }
    // The published registry, and the one it replaced, retiring now.
    assert_eq!(outbounds.retained(), 2);
}

/// A refresh built while a generation ends would reuse runtimes that the end shuts down: it is
/// published with runtimes of its own, on new carriers, instead.
#[test]
fn a_refresh_built_before_an_end_shares_no_runtime_with_it() {
    let dir = TempDir::new("api-renew");
    let feeds = Feeds::new();
    feeds.set("/sub", b"socks5://127.0.0.1:1#a\nsocks5://127.0.0.1:2#b\n");
    let core = Core::new(path(&dir)).unwrap();
    add(&core, &feeds, "/sub").unwrap();
    let outbounds = &core.shared.outbounds;
    let ended = outbounds.registry().unwrap();
    let (built, routing) = {
        let c = lock(&core.shared.catalog);
        let routing = c.model.routing(&c.state.selections, c.state.mode);
        (outbounds.build(&c.model.nodes), routing)
    };
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(1);
    outbounds.claim(7);
    let ended_carriers = outbounds.end(7).unwrap();
    assert!(ended_carriers.finish(core.runtime.handle(), deadline));
    assert!(ended.is_shutdown());
    outbounds.replace(built, routing);
    let published = outbounds.registry().unwrap();
    assert_eq!(published.len(), 2);
    assert!(!published.is_shutdown());
    let shared = |r| ended.values().any(|e| Arc::ptr_eq(e, r));
    assert!(!published.values().any(shared));
}

#[test]
fn selections_pin_and_unpin_and_survive_a_restart() {
    let dir = TempDir::new("api-select");
    let feeds = Feeds::new();
    feeds.set("/sub", &fixture("links.txt"));
    let core = Core::new(path(&dir)).unwrap();
    add(&core, &feeds, "/sub").unwrap();
    let hk = hk_group(&catalog(&core)).members[1].id.clone();
    let select = |group: &str, member: Value| {
        call(&core, "select", json!({"group": group, "memberId": member}))
    };
    assert_eq!(select("g-HK", json!(hk)), Ok(json!({})));
    assert_eq!(
        select("g-HK", json!("n-0000000000000000")),
        Err(ErrorCode::NotFound)
    );
    assert_eq!(select("g-DE", Value::Null), Err(ErrorCode::NotFound));
    assert_eq!(select("g-Auto", json!("g-HK")), Err(ErrorCode::NotFound));
    let shown = catalog(&core);
    let group = hk_group(&shown);
    assert!(group.pinned && group.selected_id.as_deref() == Some(hk.as_str()));
    drop(core);
    let core = Core::new(path(&dir)).unwrap();
    assert!(hk_group(&catalog(&core)).pinned);
    let before = catalog(&core);
    assert_eq!(
        call(&core, "select", json!({"group": "g-HK", "memberId": null})),
        Ok(json!({}))
    );
    let shown = catalog(&core);
    let group = hk_group(&shown);
    assert!(!group.pinned && group.selected_id.as_deref() == Some(group.members[0].id.as_str()));
    // Unpinning twice changes nothing more.
    let revision = |c: &Core| {
        serde_json::from_str::<Snapshot>(&c.snapshot(-1))
            .unwrap()
            .revision
    };
    let r = revision(&core);
    assert_eq!(
        call(&core, "select", json!({"group": "g-HK", "memberId": null})),
        Ok(json!({}))
    );
    assert_eq!(revision(&core), r);
    assert_ne!(hk_group(&before), hk_group(&catalog(&core)));
}

#[test]
fn a_clash_subscription_keeps_its_ids_across_an_update_and_a_restart() {
    let dir = TempDir::new("api-clash");
    let feeds = Feeds::new();
    feeds.set("/clash", &fixture("clash.yaml"));
    let core = Core::new(path(&dir)).unwrap();
    assert_eq!(add(&core, &feeds, "/clash").unwrap()["nodes"], 10);
    let shown = node_ids(&catalog(&core));
    call(&core, "updateSubscription", json!({"id": "s1"})).unwrap();
    assert_eq!(node_ids(&catalog(&core)), shown);
    let saved = std::fs::read(dir.path().join("state.json")).unwrap();
    let saved: Value = serde_json::from_slice(&saved).unwrap();
    let saved = &saved["subscriptions"][0];
    assert!(saved["links"].as_array().unwrap().is_empty());
    assert!(saved["document"].as_str().unwrap().starts_with("proxies:"));
    drop(core);
    let again = Core::new(path(&dir)).unwrap();
    assert_eq!(node_ids(&catalog(&again)), shown);
}

#[test]
fn subscription_format_tracks_the_last_successful_parse() {
    let dir = TempDir::new("api-format");
    let feeds = Feeds::new();
    let core = Core::new(path(&dir)).unwrap();
    feeds.set("/sub", &fixture("links.txt"));
    assert_eq!(add(&core, &feeds, "/sub").unwrap()["format"], "share_links");
    for (file, format) in [
        ("sip008.json", "sip008"),
        ("clash.yaml", "clash"),
        ("singbox.json", "sing_box"),
        ("links.b64", "base64"),
    ] {
        feeds.set("/sub", &fixture(file));
        let sub = call(&core, "updateSubscription", json!({"id": "s1"})).unwrap();
        assert_eq!(sub["format"], format, "{file}");
    }
}

#[test]
fn oversized_subscription_on_add_and_update_keeps_the_previous_format() {
    let dir = TempDir::new("api-too-large");
    let feeds = Feeds::new();
    let core = Core::new(path(&dir)).unwrap();
    feeds.set("/sub", &fixture("sip008.json"));
    add(&core, &feeds, "/sub").unwrap();
    feeds.set("/sub", &vec![b'x'; 9 << 20]);
    let added = add(&core, &feeds, "/sub").unwrap_err();
    let updated = call(&core, "updateSubscription", json!({"id": "s1"})).unwrap_err();
    assert_eq!(
        (added.as_str(), updated.as_str()),
        ("too_large", "too_large")
    );
    let snapshot: Value = serde_json::from_str(&core.snapshot(-1)).unwrap();
    let sub = &snapshot["catalog"]["subscriptions"][0];
    assert_eq!(sub["error"], "too_large");
    assert_eq!(sub["format"], "sip008");
}

#[test]
fn failures_have_their_codes_and_a_failed_update_keeps_the_nodes() {
    let dir = TempDir::new("api-sub-errors");
    let feeds = Feeds::new();
    feeds.set("/broken", b"proxies:\n  - {name: a, type: trojan\n");
    feeds.set("/unusable", &fixture("unusable.txt"));
    let core = Core::new(path(&dir)).unwrap();
    assert_eq!(add(&core, &feeds, "/missing"), Err(ErrorCode::FetchFailed));
    assert_eq!(add(&core, &feeds, "/broken"), Err(ErrorCode::ParseFailed));
    assert_eq!(add(&core, &feeds, "/unusable"), Err(ErrorCode::NoNodes));
    let bad = json!({"url": "ftp://example.org/s", "name": null});
    assert_eq!(
        call(&core, "addSubscription", bad),
        Err(ErrorCode::InvalidArgument)
    );
    for command in ["updateSubscription", "removeSubscription"] {
        assert_eq!(
            call(&core, command, json!({"id": "s9"})),
            Err(ErrorCode::NotFound)
        );
    }
    assert!(catalog(&core).subscriptions.is_empty());
    feeds.set("/sub", &fixture("links.txt"));
    let named = json!({"url": feeds.url("/sub"), "name": " harbor "});
    assert_eq!(
        call(&core, "addSubscription", named).unwrap()["name"],
        "harbor"
    );
    feeds.set_raw("/sub", response("503 Service Unavailable", &[], b""));
    let failed = call(&core, "updateSubscription", json!({"id": "s1"}));
    assert_eq!(failed, Err(ErrorCode::FetchFailed));
    let shown = catalog(&core);
    assert_eq!(shown.subscriptions[0].error, Some(ErrorCode::FetchFailed));
    assert_eq!(shown.subscriptions[0].nodes, 9);
    assert_eq!(shown.groups.len(), 10);
    feeds.set("/sub", &fixture("links.txt"));
    call(&core, "updateSubscription", json!({"id": "s1"})).unwrap();
    assert_eq!(catalog(&core).subscriptions[0].error, None);
}

#[test]
fn an_update_that_finishes_after_a_removal_is_dropped() {
    let dir = TempDir::new("api-update-removed");
    let feeds = Feeds::new();
    feeds.set("/sub", &fixture("links.txt"));
    let core = Arc::new(Core::new(path(&dir)).unwrap());
    add(&core, &feeds, "/sub").unwrap();
    feeds.hold();
    let update = {
        let core = Arc::clone(&core);
        std::thread::spawn(move || call(&core, "updateSubscription", json!({"id": "s1"})))
    };
    feeds.arrived(2);
    // A second update of the same subscription is busy meanwhile.
    let busy = call(&core, "updateSubscription", json!({"id": "s1"}));
    assert_eq!(busy, Err(ErrorCode::Busy));
    assert_eq!(
        call(&core, "removeSubscription", json!({"id": "s1"})),
        Ok(json!({}))
    );
    feeds.release();
    assert_eq!(update.join().unwrap(), Err(ErrorCode::NotFound));
    let shown = catalog(&core);
    assert!(shown.subscriptions.is_empty() && shown.groups.is_empty());
    let saved = std::fs::read_to_string(dir.path().join("state.json")).unwrap();
    assert!(!saved.contains("192.0.2."));
}

#[test]
fn an_update_over_the_node_budget_fails_and_keeps_the_nodes() {
    let dir = TempDir::new("api-budget");
    let feeds = Feeds::new();
    feeds.set("/sub", &fixture("links.txt"));
    let core = Core::new(path(&dir)).unwrap();
    add(&core, &feeds, "/sub").unwrap();
    let big: String = (0..=2_000)
        .map(|i| format!("trojan://p{i}@192.0.2.1:443#n{i}\n"))
        .collect();
    feeds.set("/sub", big.as_bytes());
    assert_eq!(
        call(&core, "updateSubscription", json!({"id": "s1"})),
        Err(ErrorCode::ParseFailed)
    );
    let shown = catalog(&core);
    assert_eq!(shown.subscriptions[0].nodes, 9);
    assert_eq!(shown.subscriptions[0].error, Some(ErrorCode::ParseFailed));
    assert_eq!(shown.groups[1].members.len(), 9);
}

#[test]
fn a_subscription_command_gives_up_at_its_deadline() {
    let dir = TempDir::new("api-deadline");
    let feeds = Feeds::new();
    feeds.set("/sub", &fixture("links.txt"));
    let options = Options {
        subscription_bound: Duration::from_secs(2),
        ..Options::default()
    };
    let core = Core::with_options(path(&dir), options).unwrap();
    std::thread::scope(|scope| {
        let (core, feeds) = (&core, &feeds);
        // Another command holds the writer past the deadline.
        let writer = super::lock(&core.writer);
        let (send, received) = std::sync::mpsc::channel();
        scope.spawn(move || send.send(add(core, feeds, "/sub")).ok());
        let result = received.recv_timeout(Duration::from_secs(5));
        drop(writer);
        let result = result.expect("add waited for the writer to be released");
        assert_eq!(result, Err(ErrorCode::Busy));
    });
    assert!(catalog(&core).subscriptions.is_empty());
}

/// Returns once `deadline` has passed: the work it stands in for ran out of time.
fn past(deadline: Instant) {
    while Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(5));
    }
}

#[test]
fn a_command_whose_deadline_passes_while_it_serializes_writes_nothing() {
    let dir = TempDir::new("api-late-write");
    let feeds = Feeds::new();
    feeds.set("/sub", &fixture("links.txt"));
    let options = Options {
        subscription_bound: Duration::from_secs(2),
        ..Options::default()
    };
    let core = Core::with_options(path(&dir), options.clone()).unwrap();
    // Each hook runs once the state is serialized and returns past the deadline.
    core.hooks.on_before_write(past);
    assert_eq!(add(&core, &feeds, "/sub"), Err(ErrorCode::Busy));
    assert!(catalog(&core).subscriptions.is_empty());
    let id = add(&core, &feeds, "/sub").unwrap()["id"].clone();
    // A successful update.
    feeds.set("/sub", &fixture("reordered.txt"));
    core.hooks.on_before_write(past);
    assert_eq!(
        call(&core, "updateSubscription", json!({"id": id})),
        Err(ErrorCode::Busy)
    );
    assert_eq!(catalog(&core).subscriptions[0].nodes, 9);
    // An update whose download failed records nothing.
    feeds.set_raw("/sub", response("404 Not Found", &[], b""));
    core.hooks.on_before_write(past);
    assert_eq!(
        call(&core, "updateSubscription", json!({"id": id})),
        Err(ErrorCode::FetchFailed)
    );
    assert_eq!(catalog(&core).subscriptions[0].error, None);
    drop(core);
    let again = Core::with_options(path(&dir), options).unwrap();
    let kept = catalog(&again);
    assert_eq!(kept.subscriptions.len(), 1);
    assert_eq!(kept.subscriptions[0].nodes, 9);
    assert_eq!(kept.subscriptions[0].error, None);
}

/// The catalog's HK group.
fn hk_group(c: &crate::dto::Catalog) -> &crate::dto::Group {
    c.groups.iter().find(|g| g.id == "g-HK").unwrap()
}
