//! The rule source through `call`: `rules` pages and ids, `setRuleSource` from the file to new
//! flows, `effectiveConfig` without a node's endpoint, and the contract fixtures held to what the
//! core answers.

use super::rules::effective;
use super::tests_catalog::{Feeds, add, call, catalog, path};
use super::{Core, lock};
use crate::dto::{ErrorCode, Snapshot};
use crate::rules::policy::Choice;
use crate::rules::{Flow, Network};
use crate::testutil::TempDir;
use serde_json::{Value, json};
use std::os::unix::fs::MetadataExt;

/// Two trojan nodes of one subscription, by display name `hk-01` and `jp-01`.
const TWO_NODES: &[u8] =
    b"trojan://secret@192.0.2.1:443#hk-01\ntrojan://other@192.0.2.2:443#jp-01\n";

fn contract_text(name: &str) -> String {
    let dir = concat!(env!("CARGO_MANIFEST_DIR"), "/../../contracts/doona-core/");
    std::fs::read_to_string(format!("{dir}{name}")).unwrap()
}

fn contract(name: &str) -> Value {
    serde_json::from_str(&contract_text(name)).unwrap()
}

/// Asserts `fixture` is `actual`, showing the core's JSON when it is not.
fn same(actual: &Value, fixture: &Value, name: &str) {
    let shown = serde_json::to_string_pretty(actual).unwrap();
    assert!(
        actual == fixture,
        "{name} differs from the core's:\n{shown}"
    );
}

#[test]
fn rules_pages_and_refuses_a_limit_out_of_bounds() {
    let dir = TempDir::new("rules-page");
    let core = Core::new(path(&dir)).unwrap();
    for args in [
        json!({"offset": 0, "limit": 0}),
        json!({"offset": 0, "limit": 501}),
        json!({"offset": -1, "limit": 5}),
        json!({"limit": 5}),
        json!({"offset": 0}),
    ] {
        let refused = call(&core, "rules", args.clone());
        assert_eq!(refused, Err(ErrorCode::InvalidArgument), "{args}");
    }
    let page = call(&core, "rules", json!({"offset": 2, "limit": 2})).unwrap();
    assert_eq!(page["total"], 5);
    let rows = page["rows"].as_array().unwrap();
    let indexes: Vec<u64> = rows.iter().map(|r| r["index"].as_u64().unwrap()).collect();
    assert_eq!(indexes, [2, 3]);
    let all = call(&core, "rules", json!({"offset": 0, "limit": 500})).unwrap();
    assert_eq!(all["rows"].as_array().unwrap().len(), 5);
    assert_eq!(all["rows"][2], page["rows"][0]);
    let past = call(&core, "rules", json!({"offset": 5, "limit": 500})).unwrap();
    assert_eq!(past["rows"], json!([]));
    assert_eq!(past["rulesRevision"], all["rulesRevision"]);
}

#[test]
fn rule_ids_and_the_revision_stay_while_subscriptions_come_and_go() {
    let dir = TempDir::new("rules-stable");
    let feeds = Feeds::new();
    feeds.set("/sub", TWO_NODES);
    let core = Core::new(path(&dir)).unwrap();
    let page = || call(&core, "rules", json!({"offset": 0, "limit": 50})).unwrap();
    let before = page();
    let revision = catalog(&core).rule_source.rules_revision;
    assert_eq!(before["rulesRevision"], revision);
    add(&core, &feeds, "/sub").unwrap();
    assert_eq!(page(), before);
    assert_eq!(catalog(&core).rule_source.rules_revision, revision);
    assert_eq!(
        call(&core, "removeSubscription", json!({"id": "s1"})),
        Ok(json!({}))
    );
    assert_eq!(page(), before);
    drop(core);
    let again = Core::new(path(&dir)).unwrap();
    assert_eq!(
        call(&again, "rules", json!({"offset": 0, "limit": 50})),
        Ok(before)
    );
}

#[test]
fn the_rules_fixtures_are_what_the_core_answers() {
    let dir = TempDir::new("rules-fixture");
    let core = Core::new(path(&dir)).unwrap();
    let reply = core.call("rules", &contract_text("call-rules-args.json"));
    let reply: Value = serde_json::from_str(&reply).unwrap();
    same(
        &reply,
        &contract("call-ok-rules.json"),
        "call-ok-rules.json",
    );
}

#[test]
fn the_catalog_fixture_shows_the_template_as_the_core_does() {
    let dir = TempDir::new("rules-catalog-fixture");
    let core = Core::new(path(&dir)).unwrap();
    let shown = serde_json::to_value(catalog(&core)).unwrap();
    let fixture = contract("snapshot-stopped-catalog.json");
    for key in ["mainGroupId", "ruleSource"] {
        same(&shown[key], &fixture["catalog"][key], key);
    }
}

fn revision(core: &Core) -> i64 {
    serde_json::from_str::<Snapshot>(&core.snapshot(-1))
        .unwrap()
        .revision
}

/// Where the published policy sends QUIC to an address abroad, which the template leaves to Proxy.
fn quic_abroad(core: &Core) -> String {
    let view = core.shared.outbounds.view();
    let flow = Flow {
        domain: None,
        ip: [104, 16, 0, 1].into(),
        port: 443,
        network: Network::Udp,
    };
    match view.policy().choose(&flow) {
        Choice::Blocked => "blocked".to_owned(),
        Choice::Group(id) => id.to_owned(),
        other => format!("{other:?}"),
    }
}

fn block_quic(on: bool) -> Value {
    json!({"kind": "template", "template": "cn-direct", "options": {"blockQuic": on}})
}

#[test]
fn block_quic_is_written_published_and_kept_across_a_restart() {
    let dir = TempDir::new("rules-set-source");
    let core = Core::new(path(&dir)).unwrap();
    let before = revision(&core);
    assert_eq!(quic_abroad(&core), "g-Proxy");
    assert_eq!(
        call(&core, "setRuleSource", block_quic(true)),
        Ok(json!({}))
    );
    assert!(revision(&core) > before);
    assert_eq!(quic_abroad(&core), "blocked");
    assert!(catalog(&core).rule_source.options.block_quic);
    let saved = std::fs::read_to_string(dir.path().join("state.json")).unwrap();
    let saved: Value = serde_json::from_str(&saved).unwrap();
    assert_eq!(saved["ruleSource"], block_quic(true));
    drop(core);
    let again = Core::new(path(&dir)).unwrap();
    assert_eq!(quic_abroad(&again), "blocked");
    assert!(catalog(&again).rule_source.options.block_quic);
    assert_eq!(
        call(&again, "setRuleSource", block_quic(false)),
        Ok(json!({}))
    );
    assert_eq!(quic_abroad(&again), "g-Proxy");
}

#[test]
fn an_unchanged_rule_source_writes_nothing() {
    let dir = TempDir::new("rules-set-same");
    let core = Core::new(path(&dir)).unwrap();
    let state = dir.path().join("state.json");
    // The default source, on a core that has written no file yet.
    assert_eq!(
        call(&core, "setRuleSource", block_quic(false)),
        Ok(json!({}))
    );
    assert!(!state.exists());
    assert_eq!(
        call(&core, "setRuleSource", block_quic(true)),
        Ok(json!({}))
    );
    let (written, shown) = (std::fs::metadata(&state).unwrap().ino(), revision(&core));
    assert_eq!(
        call(&core, "setRuleSource", block_quic(true)),
        Ok(json!({}))
    );
    assert_eq!(std::fs::metadata(&state).unwrap().ino(), written);
    assert_eq!(revision(&core), shown);
}

#[test]
fn set_rule_source_refuses_another_kind_or_template_and_missing_options() {
    let dir = TempDir::new("rules-set-refused");
    let core = Core::new(path(&dir)).unwrap();
    let other = json!({"kind": "template", "template": "later", "options": {"blockQuic": true}});
    assert_eq!(
        call(&core, "setRuleSource", other),
        Err(ErrorCode::NotFound)
    );
    for args in [
        json!({"kind": "subscription", "subscriptionId": "s1", "options": {"blockQuic": true}}),
        json!({"kind": "template", "template": "cn-direct"}),
        json!({"kind": "template", "template": "cn-direct", "options": {}}),
        json!({"template": "cn-direct", "options": {"blockQuic": true}}),
    ] {
        let refused = call(&core, "setRuleSource", args.clone());
        assert_eq!(refused, Err(ErrorCode::InvalidArgument), "{args}");
    }
    assert!(!dir.path().join("state.json").exists());
    assert!(!catalog(&core).rule_source.options.block_quic);
}

/// A core in Rule over [`TWO_NODES`].
fn two_nodes(tag: &str) -> (Core, TempDir, Feeds) {
    let dir = TempDir::new(tag);
    let feeds = Feeds::new();
    feeds.set("/sub", TWO_NODES);
    let core = Core::new(path(&dir)).unwrap();
    add(&core, &feeds, "/sub").unwrap();
    assert_eq!(
        call(&core, "setMode", json!({"mode": "Rule"})),
        Ok(json!({}))
    );
    (core, dir, feeds)
}

#[test]
fn the_effective_config_names_nodes_but_never_their_endpoints() {
    let (core, _dir, _feeds) = two_nodes("rules-effective");
    let mut reply = call(&core, "effectiveConfig", json!({})).unwrap();
    let text = reply["text"].as_str().unwrap().to_owned();
    assert!(text.contains("hk-01") && text.contains("jp-01"), "{text}");
    assert!(text.contains("\nMATCH,Proxy\n") && reply["truncated"] == false);
    assert_eq!(
        (&reply["mode"], &reply["dnsVia"]),
        (&json!("Rule"), &json!("g-Proxy"))
    );
    // The revision is a number whose digits are no endpoint.
    reply["ruleSource"]["rulesRevision"] = json!(0);
    let whole = reply.to_string();
    for secret in ["192.0.2.", "secret", "443"] {
        assert!(
            !text.contains(secret) && !whole.contains(secret),
            "{secret}: {whole}"
        );
    }
    assert_eq!(
        call(&core, "setMode", json!({"mode": "Direct"})),
        Ok(json!({}))
    );
    let direct = call(&core, "effectiveConfig", json!({})).unwrap();
    assert_eq!(direct["dnsVia"], "direct");
    assert_eq!(
        call(&core, "effectiveConfig", json!(null)),
        Err(ErrorCode::InvalidArgument)
    );
}

#[test]
fn the_effective_config_text_ends_at_a_line_boundary_past_its_cap() {
    let (core, _dir, _feeds) = two_nodes("rules-effective-cap");
    let (catalog, policy) = {
        let c = lock(&core.shared.catalog);
        (
            c.model.catalog(&c.state),
            std::sync::Arc::clone(&c.model.policy),
        )
    };
    let full = effective(catalog.clone(), &policy, &[], usize::MAX);
    assert!(!full.truncated);
    let cap = full.text.len() / 2;
    let cut = effective(catalog, &policy, &[], cap);
    assert!(cut.truncated && cut.text.len() <= cap);
    assert!(cut.text.ends_with('\n') && full.text.starts_with(&cut.text));
    assert!(full.text.len() > cut.text.len());
}

#[test]
fn the_effective_config_fixture_is_what_the_core_answers() {
    let (core, _dir, _feeds) = two_nodes("rules-effective-fixture");
    let reply: Value = serde_json::from_str(&core.call("effectiveConfig", "{}")).unwrap();
    let name = "call-ok-effective-config.json";
    same(&reply, &contract(name), name);
}

#[test]
fn the_set_rule_source_fixture_turns_block_quic_on() {
    let dir = TempDir::new("rules-set-fixture");
    let core = Core::new(path(&dir)).unwrap();
    let args = contract_text("call-set-rule-source-args.json");
    assert_eq!(core.call("setRuleSource", &args), r#"{"ok":{}}"#);
    assert_eq!(quic_abroad(&core), "blocked");
}
