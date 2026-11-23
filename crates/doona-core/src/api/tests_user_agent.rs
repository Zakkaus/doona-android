//! A subscription's own User-Agent: what `addSubscription` and `setSubscriptionUserAgent` take,
//! that each fetch of the subscription sends it, that setting it fetches nothing, and that it stays
//! out of diagnostics, `effectiveConfig` and the log (ARCH D21).

use super::Core;
use super::tests_catalog::{call, catalog, path};
use crate::dto::ErrorCode;
use crate::subscription::fetch::USER_AGENT;
use crate::testutil::{TempDir, fixture, http, logs, response};
use serde_json::{Value, json};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

/// A feed at `/sub` that keeps the head of each request it gets.
struct Feed {
    addr: SocketAddr,
    heads: Arc<Mutex<Vec<String>>>,
}

impl Feed {
    fn new() -> Feed {
        let heads: Arc<Mutex<Vec<String>>> = Arc::default();
        let seen = Arc::clone(&heads);
        let body = fixture("links.txt");
        let addr = http(move |head| {
            seen.lock().unwrap().push(head.to_owned());
            response("200 OK", &[], &body)
        });
        Feed { addr, heads }
    }

    fn url(&self) -> String {
        format!("http://{}/sub", self.addr)
    }

    /// The User-Agent of each request so far, in order; a head with two of them would fail here.
    fn agents(&self) -> Vec<String> {
        let heads = self.heads.lock().unwrap();
        let agent = |head: &String| {
            let mut agents = head.lines().filter_map(|l| l.strip_prefix("User-Agent: "));
            let agent = agents.next().unwrap().to_owned();
            assert!(agents.next().is_none(), "{head}");
            agent
        };
        heads.iter().map(agent).collect()
    }
}

fn add(core: &Core, feed: &Feed, agent: Value) -> Result<Value, ErrorCode> {
    let args = json!({"url": feed.url(), "name": null, "userAgent": agent});
    call(core, "addSubscription", args)
}

fn set(core: &Core, id: &str, agent: Value) -> Result<Value, ErrorCode> {
    let args = json!({"id": id, "userAgent": agent});
    call(core, "setSubscriptionUserAgent", args)
}

fn update(core: &Core) -> Result<Value, ErrorCode> {
    call(core, "updateSubscription", json!({"id": "s1"}))
}

fn revision(core: &Core) -> i64 {
    let snapshot: Value = serde_json::from_str(&core.snapshot(-1)).unwrap();
    snapshot["revision"].as_i64().unwrap()
}

fn shown(core: &Core) -> Option<String> {
    catalog(core).subscriptions[0].user_agent.clone()
}

#[test]
fn a_subscriptions_agent_is_sent_shown_and_kept_across_a_restart() {
    let dir = TempDir::new("api-user-agent");
    let feed = Feed::new();
    let core = Core::new(path(&dir)).unwrap();
    let added = add(&core, &feed, json!(" clash-verge/v2.2.3 ")).unwrap();
    assert_eq!(added["userAgent"], "clash-verge/v2.2.3");
    assert_eq!(shown(&core).as_deref(), Some("clash-verge/v2.2.3"));
    drop(core);
    let again = Core::new(path(&dir)).unwrap();
    assert_eq!(shown(&again).as_deref(), Some("clash-verge/v2.2.3"));
    assert_eq!(update(&again).unwrap()["userAgent"], "clash-verge/v2.2.3");
    assert_eq!(feed.agents(), ["clash-verge/v2.2.3"; 2]);
}

#[test]
fn setting_an_agent_fetches_nothing_and_the_next_update_sends_it() {
    let dir = TempDir::new("api-user-agent-set");
    let feed = Feed::new();
    let core = Core::new(path(&dir)).unwrap();
    // A caller from before the key sends none, which is the default.
    let args = json!({"url": feed.url(), "name": null});
    let added = call(&core, "addSubscription", args).unwrap();
    assert!(added["userAgent"].is_null());
    let before = revision(&core);
    let changed = set(&core, "s1", json!("mihomo/1.19.16")).unwrap();
    assert_eq!(changed["userAgent"], "mihomo/1.19.16");
    assert_eq!(changed["nodes"], added["nodes"]);
    assert_eq!(changed["updatedAtEpochMs"], added["updatedAtEpochMs"]);
    assert_eq!(feed.agents().len(), 1, "setting the agent fetched");
    let after = revision(&core);
    assert!(after > before);
    assert_eq!(shown(&core).as_deref(), Some("mihomo/1.19.16"));
    // The same agent again changes nothing.
    assert_eq!(set(&core, "s1", json!("mihomo/1.19.16")).unwrap(), changed);
    assert_eq!(revision(&core), after);
    update(&core).unwrap();
    // Null returns to the default.
    assert!(set(&core, "s1", Value::Null).unwrap()["userAgent"].is_null());
    assert_eq!(shown(&core), None);
    update(&core).unwrap();
    assert_eq!(feed.agents(), [USER_AGENT, "mihomo/1.19.16", USER_AGENT]);
    assert_eq!(
        set(&core, "s9", json!("a")).err(),
        Some(ErrorCode::NotFound)
    );
    // The key is required, as core-api.md writes the other nullable ones.
    let keyless = call(&core, "setSubscriptionUserAgent", json!({"id": "s1"}));
    assert_eq!(keyless.err(), Some(ErrorCode::InvalidArgument));
}

/// CR, LF, NUL and the other controls could end the header and start another; non-ASCII, more than
/// 256 bytes or a blank value are refused too. Nothing is fetched or changed.
#[test]
fn an_agent_that_is_not_printable_ascii_of_1_to_256_bytes_is_refused() {
    let dir = TempDir::new("api-user-agent-refused");
    let feed = Feed::new();
    let core = Core::new(path(&dir)).unwrap();
    let refused = [
        json!("doona\r\nX-Injected: 1"),
        json!("doona\nX-Injected: 1"),
        json!("doona\u{0}"),
        json!("caf\u{e9}"),
        json!("x".repeat(257)),
        json!(""),
        json!("   "),
        json!(7),
    ];
    for agent in &refused {
        let got = add(&core, &feed, agent.clone());
        assert_eq!(got.err(), Some(ErrorCode::InvalidArgument), "{agent}");
    }
    assert!(feed.agents().is_empty(), "a refused agent was sent");
    assert!(catalog(&core).subscriptions.is_empty());
    add(&core, &feed, json!("x".repeat(256))).unwrap();
    let before = revision(&core);
    for agent in &refused {
        let got = set(&core, "s1", agent.clone());
        assert_eq!(got.err(), Some(ErrorCode::InvalidArgument), "{agent}");
    }
    assert_eq!(revision(&core), before);
    drop(core);
    let again = Core::new(path(&dir)).unwrap();
    assert_eq!(shown(&again), Some("x".repeat(256)));
}

/// ARCH D21: the agent may identify the user to a provider, so only the subscription carries it.
#[test]
fn the_agent_stays_out_of_diagnostics_effective_config_and_the_log() {
    const AGENT: &str = "d21-agent-7f3a9c";
    let dir = TempDir::new("api-user-agent-d21");
    let feed = Feed::new();
    let core = Core::new(path(&dir)).unwrap();
    let ((diagnostics, config), logged) = logs::mentioning(AGENT, || {
        call(&core, "setLogLevel", json!({"level": "debug"})).unwrap();
        add(&core, &feed, json!(AGENT)).unwrap();
        set(&core, "s1", Value::Null).unwrap();
        set(&core, "s1", json!(AGENT)).unwrap();
        update(&core).unwrap();
        let injected = set(&core, "s1", json!(format!("{AGENT}\r\nX: 1")));
        assert_eq!(injected.err(), Some(ErrorCode::InvalidArgument));
        let diagnostics = call(&core, "diagnostics", json!({"level": "debug"})).unwrap();
        (
            diagnostics,
            call(&core, "effectiveConfig", json!({})).unwrap(),
        )
    });
    assert_eq!(feed.agents(), [AGENT; 2]);
    let events = diagnostics["events"].as_array().unwrap();
    let named = |name: &str| events.iter().filter(|e| e["name"] == name).count();
    assert_eq!(named("setSubscriptionUserAgent"), 3, "{events:?}");
    assert!(config["text"].as_str().unwrap().contains("n-"));
    for reply in [&diagnostics, &config] {
        assert!(!reply.to_string().contains(AGENT), "{reply}");
    }
    assert_eq!(logged, 0);
}
