use super::tests_catalog::{Feeds, add, call, catalog, path, wait_for};
use super::{Core, Options};
use crate::dto::ErrorCode;
use crate::testutil::{TempDir, http, socks5};
use serde_json::{Value, json};
use std::sync::atomic::Ordering::Relaxed;

fn add_link(core: &Core, link: &str) -> Value {
    call(
        core,
        "addStaticNodes",
        json!({"rows":[{"kind":"link", "link":link}]}),
    )
    .unwrap()["rows"][0]
        .clone()
}

#[test]
fn static_member_wire_contract() {
    let dir = TempDir::new("static-member-contract");
    let core = Core::new(path(&dir)).unwrap();
    let added = add_link(&core, "socks5://192.0.2.1:1080#example");
    let snapshot: Value = serde_json::from_str(&core.snapshot(-1)).unwrap();
    let members = snapshot["catalog"]["groups"][0]["members"]
        .as_array()
        .unwrap();
    let member = members.iter().find(|m| m["id"] == added["id"]).unwrap();
    let fixture = include_str!("../../../../contracts/doona-core/catalog/static-member.json");
    assert_eq!(member, &serde_json::from_str::<Value>(fixture).unwrap());
}

fn read(core: &Core, id: &Value) -> Value {
    call(core, "readStaticNode", json!({"id":id})).unwrap()
}

fn history(core: &Core, id: &Value) -> Value {
    call(core, "nodes", json!({"ids":[id]})).unwrap()["nodes"][0]["tests"].clone()
}

#[test]
fn static_only_restart_dials_and_preserves_only_the_same_endpoints_history() {
    let dir = TempDir::new("static-restart");
    let (proxy, connections) = socks5();
    let target = http(|_| b"HTTP/1.1 204 No Content\r\n\r\n".to_vec());
    let link = format!("socks5://{proxy}#HK");
    let core = Core::new(path(&dir)).unwrap();
    let id = add_link(&core, &link)["id"].clone();
    drop(core);
    let core = Core::with_options(
        path(&dir),
        Options {
            probe_url: format!("http://{target}/probe"),
            ..Options::default()
        },
    )
    .unwrap();
    let shown = catalog(&core);
    assert!(shown.subscriptions.is_empty());
    let leaf = &shown
        .groups
        .iter()
        .find(|g| g.id == "g-HK")
        .unwrap()
        .members[0];
    assert_eq!(leaf.id, id);
    assert!(leaf.subscription_id.is_none() && !leaf.is_group);
    assert_eq!(
        core.shared.outbounds.dialer("g-Proxy").unwrap().node.name,
        "HK"
    );
    call(&core, "testLatency", json!({"group":"g-Auto"})).unwrap();
    wait_for(&core, |c| c.groups.iter().all(|g| !g.testing));
    assert!(connections.load(Relaxed) > 0);
    let tests = history(&core, &id);
    assert!(tests[0]["ms"].is_number());
    let rename = json!({"expected":read(&core,&id),"node":{"kind":"link","link":link,"name":"JP"}});
    assert_eq!(call(&core, "updateStaticNode", rename).unwrap()["id"], id);
    assert_eq!(history(&core, &id), tests);
    let replace =
        json!({"expected":read(&core,&id),"node":{"kind":"link","link":"socks5://192.0.2.1:1#JP"}});
    let new = call(&core, "updateStaticNode", replace).unwrap()["id"].clone();
    assert_ne!(id, new);
    assert_eq!(history(&core, &new), json!([]));
    assert_eq!(add_link(&core, &link)["id"], id);
    assert_eq!(history(&core, &id), json!([]));
    call(&core, "select", json!({"group":"g-Proxy","memberId":id})).unwrap();
    assert!(catalog(&core).groups[0].pinned);
    call(
        &core,
        "removeStaticNode",
        json!({"expected":read(&core,&id),"confirmed":true}),
    )
    .unwrap();
    drop(core);
    let core = Core::new(path(&dir)).unwrap();
    let shown = catalog(&core);
    assert!(!shown.groups[0].pinned);
    assert_eq!(shown.groups[0].selected_id.as_deref(), Some("g-Auto"));
    assert_eq!(shown.groups[1].members.len(), 1);
    assert_eq!(shown.groups[1].members[0].id, new);
    assert_eq!(add_link(&core, &link)["id"], id);
    assert!(!catalog(&core).groups[0].pinned);
}

#[test]
fn feed_refresh_and_removal_cannot_write_a_static_or_expose_a_provider_link() {
    let dir = TempDir::new("static-feed");
    let core = Core::new(path(&dir)).unwrap();
    let feeds = Feeds::new();
    let link = "trojan://demo@192.0.2.1:443#HK";
    let local = add_link(&core, link)["id"].clone();
    feeds.set("/sub", link.as_bytes());
    let feed = add(&core, &feeds, "/sub").unwrap();
    let shown = catalog(&core);
    let provider = &shown.groups[1].members[0];
    assert_eq!(provider.subscription_id.as_deref(), Some("s1"));
    assert_ne!(provider.id, local);
    let expected = json!({"id":provider.id,"name":"HK","link":link});
    for (command, args) in [
        ("readStaticNode", json!({"id":provider.id})),
        (
            "updateStaticNode",
            json!({"expected":expected,"node":{"kind":"link","link":link}}),
        ),
        (
            "removeStaticNode",
            json!({"expected":expected,"confirmed":true}),
        ),
    ] {
        assert_eq!(call(&core, command, args), Err(ErrorCode::NotFound));
    }
    feeds.set("/sub", b"socks5://192.0.2.2:1080#TW");
    for command in ["updateSubscription", "removeSubscription"] {
        call(&core, command, json!({"id":feed["id"]})).unwrap();
        assert_eq!(read(&core, &local)["link"], link);
        assert!(
            catalog(&core).groups[1]
                .members
                .iter()
                .any(|m| m.id == local && m.subscription_id.is_none())
        );
    }
    assert!(!core.snapshot(-1).contains("demo"));
    assert!(
        !core
            .call("diagnostics", r#"{"level":"debug"}"#)
            .contains("demo")
    );
}

#[test]
fn chain_never_installs_its_first_hop() {
    let dir = TempDir::new("static-chain");
    let core = Core::new(path(&dir)).unwrap();
    let reply = add_link(
        &core,
        "socks5://192.0.2.8:1080#HK->JP -> socks5://192.0.2.9:1080",
    );
    assert_eq!(reply, json!({"status":"unsupported","reason":"chain"}));
    assert!(catalog(&core).groups.is_empty());
    assert!(!dir.path().join("state.json").exists());
}

#[test]
fn a_rename_keeps_an_in_flight_latency_result() {
    use futures_util::FutureExt;
    use std::{sync::Arc, time::Duration};
    let dir = TempDir::new("static-rename-probe");
    let (release, gate) = tokio::sync::watch::channel(false);
    let core = Core::with_prober(
        path(&dir),
        Arc::new(move |_| {
            let mut gate = gate.clone();
            async move {
                gate.wait_for(|ready| *ready).await.unwrap();
                Some(Duration::from_millis(42))
            }
            .boxed()
        }),
    );
    let link = "socks5://192.0.2.1:1080#HK";
    let id = add_link(&core, link)["id"].clone();
    call(&core, "testLatency", json!({"group":"g-Auto"})).unwrap();
    let rename = json!({"expected":read(&core,&id),"node":{"kind":"link","link":link,"name":"JP"}});
    call(&core, "updateStaticNode", rename).unwrap();
    release.send(true).unwrap();
    let shown = wait_for(&core, |c| c.groups.iter().all(|g| !g.testing));
    let leaf = &shown.groups[1].members[0];
    assert_eq!(leaf.name, "JP");
    assert_eq!(leaf.latency_ms, Some(42));
}

#[test]
fn a_reloaded_endpoint_uses_its_stored_editor_id() {
    let dir = TempDir::new("static-stored-id");
    let link = "socks5://192.0.2.1:1080";
    let id = "n-0000000000000001";
    let stored = json!({"id":id,"name":"HK","link":link});
    let state = json!({"stateVersion":1,"mode":"Global","staticNodes":[stored]});
    std::fs::write(dir.path().join("state.json"), state.to_string()).unwrap();
    let core = Core::new(path(&dir)).unwrap();
    assert_eq!(catalog(&core).groups[1].members[0].id, id);
    assert_eq!(add_link(&core, link), json!({"status":"duplicate","id":id}));
    let rename = json!({"expected":stored,"node":{"kind":"link","link":link,"name":"JP"}});
    assert_eq!(call(&core, "updateStaticNode", rename).unwrap()["id"], id);
}
