use super::Core;
use super::tests_catalog::{Feeds, add, call, catalog, path};
use crate::dto::{ErrorCode, Snapshot};
use crate::testutil::{TempDir, fixture};
use serde_json::json;

#[test]
fn rename_is_durable_without_changing_node_identity_or_selection() {
    let dir = TempDir::new("rename");
    let feeds = Feeds::new();
    feeds.set("/sub", &fixture("links.txt"));
    let core = Core::new(path(&dir)).unwrap();
    for _ in 0..2 {
        add(&core, &feeds, "/sub").unwrap();
    }
    let node = catalog(&core).groups[1].members[0].id.clone();
    call(&core, "select", json!({"group":"g-Proxy", "memberId":node})).unwrap();
    let before = catalog(&core);
    let revision = serde_json::from_str::<Snapshot>(&core.snapshot(-1))
        .unwrap()
        .revision;
    let name = "界".repeat(64);
    for id in ["s1", "s2"] {
        let renamed = call(
            &core,
            "renameSubscription",
            json!({"id":id,"name":format!("  {name}  ")}),
        )
        .unwrap();
        assert_eq!(renamed["name"], name);
    }
    for bad in [
        " ".to_owned(),
        "\nA".into(),
        "a\u{2028}b".into(),
        "a\u{2029}".into(),
        format!("{name}x"),
    ] {
        assert_eq!(
            call(
                &core,
                "addSubscription",
                json!({"url":feeds.url("/sub"),"name":bad})
            ),
            Err(ErrorCode::InvalidArgument)
        );
        assert_eq!(
            call(&core, "renameSubscription", json!({"id":"s1","name":bad})),
            Err(ErrorCode::InvalidArgument)
        );
    }
    assert!(
        serde_json::from_str::<Snapshot>(&core.snapshot(revision))
            .unwrap()
            .catalog
            .is_some()
    );
    drop(core);
    let core = Core::new(path(&dir)).unwrap();
    let after = catalog(&core);
    assert!(after.subscriptions.iter().all(|s| s.name == name));
    assert_eq!(before.groups, after.groups);
}

#[test]
fn share_link_follows_the_node_across_refresh_and_is_not_exported() {
    let dir = TempDir::new("share-link");
    let feeds = Feeds::new();
    let original = "trojan://fixture-marker@192.0.2.1:443#same%20label";
    let other = "trojan://different@192.0.2.2:443#same%20label";
    feeds.set("/sub", format!("{original}\n{other}").as_bytes());
    let core = Core::new(path(&dir)).unwrap();
    add(&core, &feeds, "/sub").unwrap();
    let nodes = call(&core, "nodes", json!({"ids":null})).unwrap();
    let args = json!({"id":nodes["nodes"][0]["id"]});
    assert_eq!(
        call(&core, "nodeShareLink", args.clone()),
        Ok(json!(original))
    );
    feeds.set("/sub", format!("{other}\n{original}").as_bytes());
    call(&core, "updateSubscription", json!({"id":"s1"})).unwrap();
    assert_eq!(
        call(&core, "nodeShareLink", args.clone()),
        Ok(json!(original)),
        "immediately after reorder"
    );
    drop(core);
    let core = Core::new(path(&dir)).unwrap();
    assert_eq!(
        call(&core, "nodeShareLink", args.clone()),
        Ok(json!(original))
    );
    for command in ["effectiveConfig", "diagnostics"] {
        assert!(
            !call(&core, command, json!({}))
                .unwrap()
                .to_string()
                .contains("fixture-marker")
        );
    }
    for file in ["clash.yaml", "links.b64"] {
        feeds.set("/sub", &fixture(file));
        call(&core, "updateSubscription", json!({"id":"s1"})).unwrap();
        let nodes = call(&core, "nodes", json!({"ids":null})).unwrap();
        assert_eq!(
            call(
                &core,
                "nodeShareLink",
                json!({"id":nodes["nodes"][0]["id"]})
            ),
            Err(ErrorCode::NotFound)
        );
    }
}

#[test]
fn resolver_edits_validate_the_whole_list_and_survive_restart() {
    let dir = TempDir::new("resolver-edit");
    let core = Core::new(path(&dir)).unwrap();
    let effective =
        |core: &Core| call(core, "effectiveConfig", json!({})).unwrap()["dnsUpstreams"].clone();
    assert_eq!(effective(&core), json!(["1.1.1.1:53", "8.8.8.8:53"]));
    let servers = json!(["9.9.9.9", "223.5.5.5:5353", "2001:db8::53", "[::1]:54"]);
    call(&core, "setDnsUpstreams", json!({"servers":servers})).unwrap();
    for invalid in [
        json!([]),
        json!(vec!["1.1.1.1"; 5]),
        json!(["8.8.8.8", "resolver.example"]),
        json!(["[::1]:65536"]),
        json!(["1.1.1.1:0"]),
        json!(["9.9.9.9", "9.9.9.9:53"]),
        json!(["2001:DB8:0::1", "[2001:db8::1]:53"]),
        json!(["[fe80::1%2]:53"]),
        json!(["[2001:db8::1%2]:53"]),
        json!(["::"]),
        json!(["0.0.0.0"]),
        json!(["::ffff:0.0.0.0"]),
        json!(["255.255.255.255"]),
        json!(["ff02::1"]),
        json!(["224.0.0.1"]),
        json!(["fe80::1"]),
        json!(["169.254.0.1"]),
    ] {
        assert_eq!(
            call(&core, "setDnsUpstreams", json!({"servers":invalid})),
            Err(ErrorCode::InvalidArgument)
        );
    }
    drop(core);
    let core = Core::new(path(&dir)).unwrap();
    assert_eq!(
        effective(&core),
        json!([
            "9.9.9.9:53",
            "223.5.5.5:5353",
            "[2001:db8::53]:53",
            "[::1]:54"
        ])
    );
}

#[test]
fn reload_skips_unadmitted_links_without_shifting_credentials() {
    let dir = TempDir::new("reload-skipped");
    let link = "socks5://192.0.2.1:1080#kept";
    let state = json!({"stateVersion":1,"mode":"Direct","subscriptions":[{"id":"s1","name":"feed","url":"https://example.invalid/","format":"share_links","links":["not a share link",link]}]});
    std::fs::write(dir.path().join("state.json"), state.to_string()).unwrap();
    let core = Core::new(path(&dir)).unwrap();
    let nodes = call(&core, "nodes", json!({"ids":null})).unwrap();
    assert_eq!(
        call(
            &core,
            "nodeShareLink",
            json!({"id":nodes["nodes"][0]["id"]})
        ),
        Ok(json!(link))
    );
}

#[test]
fn legacy_subscription_with_stored_links_cannot_share_until_refreshed() {
    let dir = TempDir::new("share-legacy");
    let state = json!({"stateVersion":1,"mode":"Direct","subscriptions":[{"id":"s1","name":"legacy","url":"https://example.invalid/","format":null,"links":["socks5://192.0.2.1:1080#kept"]}]});
    std::fs::write(dir.path().join("state.json"), state.to_string()).unwrap();
    let core = Core::new(path(&dir)).unwrap();
    let nodes = call(&core, "nodes", json!({"ids":null})).unwrap();
    let id = nodes["nodes"][0]["id"].as_str().unwrap();
    assert_eq!(
        call(&core, "nodeShareLink", json!({"id":id})),
        Err(ErrorCode::NotFound)
    );
}

#[test]
fn unknown_node_has_no_share_link() {
    let dir = TempDir::new("share-unknown");
    let core = Core::new(path(&dir)).unwrap();
    assert_eq!(
        call(&core, "nodeShareLink", json!({"id":"missing"})),
        Err(ErrorCode::NotFound)
    );
}

#[test]
fn static_editor_link_is_not_a_subscription_share_link() {
    let dir = TempDir::new("share-static");
    let core = Core::new(path(&dir)).unwrap();
    let added = call(
        &core,
        "addStaticNodes",
        json!({"rows":[{"kind":"link","link":"socks5://192.0.2.1:1080#local"}]}),
    )
    .unwrap();
    let id = added["rows"][0]["id"].as_str().unwrap();
    assert_eq!(
        call(&core, "nodeShareLink", json!({"id":id})),
        Err(ErrorCode::NotFound)
    );
}

#[test]
fn invalid_stored_dns_defaults_without_discarding_the_catalog() {
    let dir = TempDir::new("dns-store");
    for invalid in [
        json!(["https://future.invalid/dns-query"]),
        json!([]),
        json!(["9.9.9.9:53", "9.9.9.9"]),
    ] {
        let state = json!({"stateVersion":1,"mode":"Direct","dnsUpstreams":invalid,"subscriptions":[{"id":"s1","name":"keep","url":"https://example.invalid/"}]});
        std::fs::write(dir.path().join("state.json"), state.to_string()).unwrap();
        let core = Core::new(path(&dir)).unwrap();
        assert_eq!(catalog(&core).subscriptions[0].name, "keep");
        let report = call(&core, "diagnostics", json!({"level":"warn"})).unwrap();
        assert!(report["events"].as_array().unwrap().iter().any(|e| {
            e["name"] == "init" && e["level"] == "warn" && e["detail"] == "dnsUpstreamsReset"
        }));
        assert_eq!(
            call(&core, "effectiveConfig", json!({})).unwrap()["dnsUpstreams"],
            json!(["1.1.1.1:53", "8.8.8.8:53"])
        );
        assert!(!dir.path().join("state.json.unreadable").exists());
    }
}

#[test]
fn selecting_defaults_survives_a_change_of_builtin_defaults() {
    let dir = TempDir::new("explicit-defaults");
    let core = Core::new(path(&dir)).unwrap();
    call(
        &core,
        "setDnsUpstreams",
        json!({"servers":["1.1.1.1","8.8.8.8"]}),
    )
    .unwrap();
    drop(core);
    let options = super::Options {
        dns_upstreams: vec!["9.9.9.9:53".parse().unwrap()],
        ..super::Options::default()
    };
    let core = Core::with_options(path(&dir), options).unwrap();
    assert_eq!(
        call(&core, "effectiveConfig", json!({})).unwrap()["dnsUpstreams"],
        json!(["1.1.1.1:53", "8.8.8.8:53"])
    );
}
