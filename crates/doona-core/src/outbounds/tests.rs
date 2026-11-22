//! Nodes of one honk id that dial differently, from two subscriptions.

use super::Outbounds;
use crate::catalog::entries;
use crate::subscription::parse::parse;
use std::sync::Arc;

#[test]
fn nodes_of_one_id_that_dial_differently_get_runtimes_of_their_own() {
    // A Clash TUIC proxy has ALPN h3 by default and a sing-box one none; honk's id leaves the TUIC
    // ALPN out.
    let uuid = "b38ad070-88e0-4bcc-9808-924f3c0e1bd5";
    let clash = format!(
        "proxies:\n  - {{name: c, type: tuic, server: 192.0.2.1, port: 443, uuid: {uuid}, \
         password: p, sni: tuic.example.net}}\n"
    );
    let sing_box = format!(
        r#"{{"outbounds": [{{"type": "tuic", "tag": "s", "server": "192.0.2.1",
        "server_port": 443, "uuid": "{uuid}", "password": "p",
        "tls": {{"enabled": true, "server_name": "tuic.example.net"}}}}]}}"#
    );
    let first = parse(clash.as_bytes()).unwrap().nodes;
    let second = parse(sing_box.as_bytes()).unwrap().nodes;
    assert_eq!(first[0].id, second[0].id);
    assert_ne!(first[0].outbound, second[0].outbound);
    // The same proxy under another name, in a third subscription, dials as the first does.
    let again = parse(clash.replace("name: c", "name: c2").as_bytes())
        .unwrap()
        .nodes;
    let mut nodes = entries("s-a", first);
    nodes.extend(entries("s-b", second));
    nodes.extend(entries("s-c", again));

    let outbounds = Outbounds::new(Arc::default()).unwrap();
    let built = outbounds.build(&nodes);
    let selected = nodes
        .iter()
        .map(|n| (n.id.clone(), Arc::clone(&n.node)))
        .collect();
    outbounds.replace(built, selected);
    let runtime = |id: &str| {
        let dialer = outbounds.dialer(id).unwrap();
        dialer.registry.get(&dialer.node.id).unwrap()
    };
    for n in &nodes {
        assert_eq!(runtime(&n.id).node.outbound, n.node.outbound, "{}", n.name);
    }
    assert!(Arc::ptr_eq(&runtime(&nodes[0].id), &runtime(&nodes[2].id)));
    let (registries, _) = outbounds.published().unwrap();
    let runtimes: usize = registries.0.iter().map(|r| r.len()).sum();
    assert_eq!(runtimes, 2);
}
