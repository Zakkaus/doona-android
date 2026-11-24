//! Clash, sing-box and SIP008 bodies against the share links of the same nodes; what is skipped;
//! the node and item budgets and aliases; malformed bodies; the stored document; a BOM, CRLF line
//! ends and base64. Directives, documents and nesting are in `tests_hostile`.

use super::super::parse::{MAX_NODES_PER_SUBSCRIPTION, Parsed, parse};
use super::guard::{MAX_ITEMS, items};
use crate::dto::ErrorCode;
use crate::testutil::fixture;
use honk_config::node::Node;
use honk_config::types::NodeProtocol;

/// The supported entries of `clash.yaml`, in feed order; `singbox.json` has all but Juicity.
const NODES: [&str; 10] = [
    "ss-01",
    "vmess-01",
    "vless-01",
    "reality-01",
    "trojan-01",
    "hy2-01",
    "tuic-01",
    "anytls-01",
    "socks-01",
    "juicity-01",
];

/// Checks that `left` and `right` are the same nodes, but for the times each was made at.
pub(super) fn assert_same(left: &[Node], right: &[Node], what: &str) {
    assert_eq!(left.len(), right.len(), "{what}");
    for (left, right) in left.iter().zip(right) {
        let mut left = left.clone();
        left.created_at = right.created_at;
        left.updated_at = right.updated_at;
        assert_eq!(&left, right, "{what}");
    }
}

fn names(p: &Parsed) -> Vec<&str> {
    p.nodes.iter().map(|n| n.name.as_str()).collect()
}

/// Checks that `node` is the node of the share link `link`.
fn assert_node_of_link(node: &Node, link: &Node, what: &str) {
    let mut node = node.clone();
    // QUIC always runs TLS: honk's Clash builder turns `enabled` on and its share links leave it
    // off. honk-outbound's QUIC dialers do not read it, and the endpoint id leaves it out.
    let quic = [
        NodeProtocol::Hysteria2,
        NodeProtocol::Tuic,
        NodeProtocol::Juicity,
    ];
    if quic.contains(&node.protocol())
        && let (Some(tls), Some(theirs)) = (node.tls_mut(), link.tls())
    {
        tls.enabled = theirs.enabled;
    }
    assert_same(
        std::slice::from_ref(&node),
        std::slice::from_ref(link),
        what,
    );
}

/// The one node of `body`, and the one of the share link `link`.
fn node_and_link(body: &str, link: &str) -> (Node, Node) {
    let only = |text: &str| {
        let mut nodes = parse(text.as_bytes()).unwrap().nodes;
        assert_eq!(nodes.len(), 1, "{text}");
        nodes.remove(0)
    };
    (only(body), only(link))
}

/// Parses the fixture `body` and checks that each node is the node of the share link in
/// `structured-links.txt` that has its name.
fn same_as_links(body: &str) -> Parsed {
    let parsed = parse(&fixture(body)).unwrap();
    let links = parse(&fixture("structured-links.txt")).unwrap().nodes;
    for node in &parsed.nodes {
        let link = links.iter().find(|l| l.name == node.name);
        let link = link.unwrap_or_else(|| panic!("{body}: no link named {}", node.name));
        assert_node_of_link(node, link, &format!("{body}: {}", node.name));
    }
    assert!(parsed.links.is_empty() && parsed.document.is_some());
    parsed
}

#[test]
fn a_clash_document_gives_the_nodes_of_its_share_links() {
    // Groups and rules are not read; snell, ssr, WireGuard, HTTP, a plugin, a missing password,
    // a repeated endpoint and an entry that is not a mapping are skipped.
    assert_eq!(names(&same_as_links("clash.yaml")), NODES);
}

#[test]
fn chained_clash_proxies_are_skipped() {
    for key in [
        "dialer-proxy",
        "underlying-proxy",
        "dialer_proxy",
        "underlying_proxy",
    ] {
        let entry = format!(
            "{{type: trojan, server: exit.test, port: 443, password: p, name: exit, {key}: front}}"
        );
        let direct = "{type: trojan, server: front.test, port: 443, password: p, name: front}";
        let body = format!("proxies:\n- {entry}\n- {direct}");
        let parsed = parse(body.as_bytes()).unwrap();
        assert_eq!(names(&parsed), ["front"], "{key}");
        let mapping = serde_yaml::from_str(&entry).unwrap();
        assert_eq!(
            super::clash::parse_clash_proxy(&mapping, Some("h3")).unwrap_err(),
            (None, "chaining is unsupported"),
            "{key}"
        );
        for inactive in ["''", "null"] {
            let body = body.replace(&format!("{key}: front"), &format!("{key}: {inactive}"));
            assert_eq!(names(&parse(body.as_bytes()).unwrap()), ["exit", "front"]);
        }
    }
}

#[test]
fn a_sing_box_document_gives_the_nodes_of_its_share_links() {
    // Selector, URLTest, direct, block and DNS outbounds, SSH, Tor and a trojan without TLS are
    // skipped; so is the route.
    assert_eq!(names(&same_as_links("singbox.json")), NODES[..9]);
}

#[test]
fn a_sip008_document_gives_the_nodes_of_its_share_links() {
    // A plugin and a repeated endpoint are skipped.
    assert_eq!(names(&same_as_links("sip008.json")), ["ss-01", "ss-02"]);
}

#[test]
fn skipped_entries_are_not_an_error_and_a_mixed_list_keeps_its_nodes() {
    let snell = "  - {name: s, type: snell, server: 192.0.2.2, port: 44046, psk: x}\n";
    let trojan = "  - {name: t, type: trojan, server: 192.0.2.1, port: 443, password: p}\n";
    assert_eq!(
        names(&parse(format!("proxies:\n{snell}{trojan}{snell}").as_bytes()).unwrap()),
        ["t"]
    );
    for body in [
        format!("proxies:\n{snell}"),
        "proxies: []\n".to_owned(),
        r#"{"outbounds": [{"type": "direct"}, {"type": "selector"}, {"type": "wireguard"}]}"#
            .to_owned(),
        r#"{"servers": [{"server": "192.0.2.1", "server_port": 8388, "password": "p",
            "method": "aes-256-gcm", "plugin": "obfs-local"}]}"#
            .to_owned(),
        "[{\"remarks\": \"no server\"}, 2]".to_owned(),
    ] {
        assert_eq!(
            parse(body.as_bytes()).err(),
            Some(ErrorCode::NoNodes),
            "{body}"
        );
    }
}

fn trojans(n: usize) -> String {
    (0..n)
        .map(|i| {
            format!(
                "  - {{name: n{i}, type: trojan, server: 192.0.2.1, port: 443, password: p{i}}}\n"
            )
        })
        .collect()
}

#[test]
fn a_document_over_the_node_budget_is_refused() {
    let full = format!("proxies:\n{}", trojans(MAX_NODES_PER_SUBSCRIPTION));
    let parsed = parse(full.as_bytes()).unwrap();
    assert_eq!(parsed.nodes.len(), MAX_NODES_PER_SUBSCRIPTION);
    let over = format!("proxies:\n{}", trojans(MAX_NODES_PER_SUBSCRIPTION + 1));
    assert_eq!(parse(over.as_bytes()).err(), Some(ErrorCode::ParseFailed));
    // A repeated endpoint and a skipped entry count for nothing.
    let snell = "  - {name: s, type: snell, server: 192.0.2.2, port: 44046, psk: x}\n";
    let padded = full + &trojans(10) + &snell.repeat(10);
    assert!(parse(padded.as_bytes()).is_ok());
    let servers: Vec<_> = (0..=MAX_NODES_PER_SUBSCRIPTION)
        .map(|i| {
            serde_json::json!({"server": "192.0.2.1", "server_port": 1 + i,
                "password": "p", "method": "aes-256-gcm"})
        })
        .collect();
    let sip008 = serde_json::json!({ "servers": servers }).to_string();
    assert_eq!(parse(sip008.as_bytes()).err(), Some(ErrorCode::ParseFailed));
}

/// A Mihomo profile as large providers serve it: 2,000 nodes, a group of all of them and 20,000
/// rules.
fn large_profile() -> String {
    let mut profile = String::from("mixed-port: 7890\nmode: rule\nproxies:\n");
    for i in 0..2000 {
        let host = format!("hk-{i:04}.example.net");
        profile += &format!(
            "  - name: \"HK-{i:04} | IEPL\"\n    type: vless\n    server: {host}\n    port: 443\n    \
             uuid: ef0c8917-ad5e-4c82-8e5b-5c07d818d829\n    network: ws\n    tls: true\n    \
             udp: true\n    servername: {host}\n    client-fingerprint: chrome\n    ws-opts:\n      \
             path: /ws\n      headers:\n        Host: {host}\n"
        );
    }
    profile += "proxy-groups:\n  - name: Proxy\n    type: select\n    proxies:\n";
    for i in 0..2000 {
        profile += &format!("      - \"HK-{i:04} | IEPL\"\n");
    }
    profile += "rules:\n";
    for i in 0..20_000 {
        profile += &format!("  - DOMAIN-SUFFIX,site-{i}.example.net,Proxy\n");
    }
    profile
}

#[test]
fn a_body_past_the_item_bound_is_refused_before_a_tree_is_built() {
    let profile = large_profile();
    assert!(items(&profile) < MAX_ITEMS);
    assert_eq!(parse(profile.as_bytes()).unwrap().nodes.len(), 2000);
    for (open, close) in [("proxies:\n- [", "0]\n"), ("{\"proxies\": [[", "0]]}")] {
        let at = format!("{open}{}{close}", "0,".repeat(MAX_ITEMS - 6));
        assert!(items(&at) <= MAX_ITEMS, "{open}");
        assert_eq!(
            parse(at.as_bytes()).err(),
            Some(ErrorCode::NoNodes),
            "{open}"
        );
        let past = format!("{open}{}{close}", "0,".repeat(MAX_ITEMS));
        assert_eq!(
            parse(past.as_bytes()).err(),
            Some(ErrorCode::ParseFailed),
            "{open}"
        );
    }
    // An admitted entry that would store past the bound in block style is refused at the fetch,
    // so state.json never keeps a document it cannot read back.
    let junk = "0,".repeat(MAX_ITEMS * 2 / 3);
    let wide = format!(
        "proxies:\n  - {{name: t, type: trojan, server: 192.0.2.1, port: 443, password: p, \
         junk: [{junk}0]}}\n"
    );
    assert!(items(&wide) < MAX_ITEMS);
    assert_eq!(parse(wide.as_bytes()).err(), Some(ErrorCode::ParseFailed));
}

#[test]
fn an_alias_counts_every_time_it_is_used() {
    // Used once, an alias is the value it names, here and in the stored document.
    let one = "secret: &pw secret\nproxies:\n  \
               - {name: t, type: trojan, server: 192.0.2.1, port: 443, password: *pw}\n";
    let parsed = parse(one.as_bytes()).unwrap();
    assert_eq!(names(&parsed), ["t"]);
    let document = parsed.document.unwrap();
    assert!(document.contains("password: secret"), "{document}");
    assert_same(
        &parse(document.as_bytes()).unwrap().nodes,
        &parsed.nodes,
        "*pw",
    );
    // 1,000 values used 1,000 times would build a million; a string used as often, a gigabyte.
    let values = format!("[{}0]", "0,".repeat(999));
    let string = format!("\"{}\"", "x".repeat(1 << 20));
    for anchored in [values, string] {
        let uses = vec!["*a"; 1000].join(", ");
        let bomb = format!("a: &a {anchored}\nproxies: [{uses}]\n");
        assert!(items(&bomb) < MAX_ITEMS);
        assert_eq!(parse(bomb.as_bytes()).err(), Some(ErrorCode::ParseFailed));
    }
}

#[test]
fn a_document_that_does_not_parse_or_is_of_no_known_shape_fails_to_parse() {
    for body in [
        "proxies:\n  - {name: a, type: trojan\n",
        "proxies:\n  - name: a\n bad: [\n",
        "{\"outbounds\": [}",
        "{\"outbounds\": {}}",
        "{\"outbounds\": [], \"servers\": []}",
        "{\"version\": 3, \"servers\": []}",
        "{\"servers\": 5}",
        "{\"proxies\": 5}",
        "{\"name\": \"a profile\"}",
        "proxies:\n  - {name: a}\n---\nproxies: []\n",
    ] {
        assert_eq!(
            parse(body.as_bytes()).err(),
            Some(ErrorCode::ParseFailed),
            "{body}"
        );
    }
}

#[test]
fn the_stored_document_gives_the_same_nodes_from_their_entries_alone() {
    let bare = r#"[{"server": "192.0.2.11", "server_port": 8388, "password": "pass",
        "method": "aes-256-gcm", "remarks": "ss-01"}]"#;
    let bodies = ["clash.yaml", "singbox.json", "sip008.json"].map(fixture);
    for body in bodies.iter().map(Vec::as_slice).chain([bare.as_bytes()]) {
        let parsed = parse(body).unwrap();
        let document = parsed.document.clone().unwrap();
        let again = parse(document.as_bytes()).unwrap();
        assert_same(&again.nodes, &parsed.nodes, &document);
        assert_eq!(again.document.as_ref(), Some(&document));
        for gone in [
            "rules",
            "proxy-groups",
            "route",
            "snell",
            "selector",
            "plugin",
            "again",
        ] {
            assert!(!document.contains(gone), "{gone} in {document}");
        }
    }
}

#[test]
fn a_bom_crlf_line_ends_and_base64_change_nothing() {
    use base64::Engine;
    for name in ["clash.yaml", "singbox.json", "sip008.json"] {
        let plain = parse(&fixture(name)).unwrap();
        let text = String::from_utf8(fixture(name)).unwrap();
        let windows = format!("\u{feff}{}", text.replace('\n', "\r\n"));
        let encoded = base64::engine::general_purpose::STANDARD.encode(&text);
        for body in [windows, encoded] {
            let parsed = parse(body.as_bytes()).unwrap();
            assert_same(&parsed.nodes, &plain.nodes, name);
            assert_eq!(parsed.document, plain.document, "{name}");
        }
    }
}

#[test]
fn a_refetch_of_the_same_body_gives_the_same_nodes() {
    for name in ["clash.yaml", "singbox.json", "sip008.json"] {
        let first = parse(&fixture(name)).unwrap();
        let again = parse(&fixture(name)).unwrap();
        assert_same(&first.nodes, &again.nodes, name);
        assert_eq!(first.document, again.document, "{name}");
    }
}

pub(super) fn nested(n: usize) -> String {
    format!("{}{}", "[".repeat(n), "]".repeat(n))
}

#[test]
fn the_deepest_entry_that_imports_reads_back_from_its_stored_document() {
    let entry = |depth: usize| {
        format!(
            r#"{{"server": "192.0.2.1", "server_port": 8388, "password": "p",
            "method": "aes-256-gcm", "meta": {}}}"#,
            nested(depth)
        )
    };
    let bare = |entry: String| format!("[{entry}]");
    let wrapped = |entry: String| format!(r#"{{"servers": [{entry}]}}"#);
    for wrap in [&bare as &dyn Fn(String) -> String, &wrapped] {
        let imports = |depth| parse(wrap(entry(depth)).as_bytes()).is_ok();
        let deepest = (100..140).take_while(|&depth| imports(depth)).last();
        let deepest = deepest.expect("an entry 100 deep imports");
        assert!(deepest < 139);
        let parsed = parse(wrap(entry(deepest)).as_bytes()).unwrap();
        let document = parsed.document.as_deref().unwrap();
        let again = parse(document.as_bytes()).map(|p| p.nodes);
        let what = format!("{deepest} deep");
        assert_same(&again.expect(&what), &parsed.nodes, &what);
    }
}

#[test]
fn a_websocket_that_upgrades_over_http_is_skipped() {
    let vless = |upgrade: bool| {
        format!(
            "proxies:\n  - {{name: v, type: vless, server: 192.0.2.1, port: 443, \
             uuid: ef0c8917-ad5e-4c82-8e5b-5c07d818d829, tls: true, network: ws, \
             ws-opts: {{path: /ws, v2ray-http-upgrade: {upgrade}}}}}\n"
        )
    };
    let skipped = parse(vless(true).as_bytes());
    assert_eq!(skipped.err(), Some(ErrorCode::NoNodes));
    assert_eq!(names(&parse(vless(false).as_bytes()).unwrap()), ["v"]);
}

#[test]
fn an_ipv6_server_gives_the_node_of_its_share_link() {
    let trojan = |server: &str| {
        format!(
            "proxies:\n  - name: t\n    type: trojan\n    server: {server}\n    port: 443\n    \
             password: p\n"
        )
    };
    let sip008 = r#"[{"server": "2001:DB8:0::1", "server_port": 8388, "password": "pass",
        "method": "aes-256-gcm", "remarks": "s"}]"#;
    for (body, link) in [
        (trojan("2001:db8::1"), "trojan://p@[2001:db8::1]:443#t"),
        (trojan("'[2001:db8::1]'"), "trojan://p@[2001:db8::1]:443#t"),
        (
            sip008.to_owned(),
            "ss://YWVzLTI1Ni1nY206cGFzcw@[2001:db8::1]:8388#s",
        ),
    ] {
        let (node, link) = node_and_link(&body, link);
        assert_node_of_link(&node, &link, &body);
    }
}

const UUID: &str = "ef0c8917-ad5e-4c82-8e5b-5c07d818d829";
const PIN: &str = "4f5a8e7d0c1b2a39485766758493a2b1c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5";

/// The share link of a VLESS node with TLS and the settings `query`; its packets go as XUDP, as
/// a sing-box outbound's do by default.
fn vless_link(query: &str) -> String {
    format!(
        "vless://{UUID}@v.example.net:443?security=tls&sni=v.example.net&packetEncoding=xudp\
         &{query}#n"
    )
}

/// A Clash VLESS proxy with TLS and `extra` settings, and its share link with `query`.
fn vless(extra: &str, query: &str) -> (String, String) {
    (
        format!(
            "proxies:\n  - {{name: n, type: vless, server: v.example.net, port: 443, \
             uuid: {UUID}, tls: true, servername: v.example.net, packet-encoding: xudp, \
             {extra}}}\n"
        ),
        vless_link(query),
    )
}

#[test]
fn clash_transport_and_tls_settings_give_the_nodes_of_their_share_links() {
    let trojan = |extra: &str| {
        format!(
            "proxies:\n  - {{name: t, type: trojan, server: t.example.net, port: 443, \
             password: p, sni: t.example.net, {extra}}}\n"
        )
    };
    let tuic = "proxies:\n  - {name: u, type: tuic, server: u.example.net, port: 443, \
                uuid: b38ad070-88e0-4bcc-9808-924f3c0e1bd5, password: p, sni: u.example.net, \
                alpn: [h3-29]}\n";
    for (body, link) in [
        vless(
            "network: grpc, grpc-opts: {grpc-service-name: svc}",
            "type=grpc&serviceName=svc",
        ),
        vless("network: grpc", "type=grpc"),
        vless(
            "network: xhttp, xhttp-opts: {path: /x, host: x.example.net, mode: packet-up}",
            "type=xhttp&path=%2Fx&host=x.example.net&mode=packet-up",
        ),
        (
            trojan("skip-cert-verify: true"),
            "trojan://p@t.example.net:443?sni=t.example.net&allowInsecure=1#t".to_owned(),
        ),
        (
            trojan(&format!("pin-sha256: {PIN}")),
            format!("trojan://p@t.example.net:443?sni=t.example.net&pinSHA256={PIN}#t"),
        ),
        (
            tuic.to_owned(),
            "tuic://b38ad070-88e0-4bcc-9808-924f3c0e1bd5:p@u.example.net:443?alpn=h3-29\
             &sni=u.example.net#u"
                .to_owned(),
        ),
    ] {
        let (node, link) = node_and_link(&body, &link);
        assert_node_of_link(&node, &link, &body);
    }
}

#[test]
fn sing_box_transport_and_tls_settings_give_the_nodes_of_their_share_links() {
    let outbound = |entry: &str| format!(r#"{{"outbounds": [{entry}]}}"#);
    let vless = |transport: &str| {
        outbound(&format!(
            r#"{{"type": "vless", "tag": "n", "server": "v.example.net", "server_port": 443,
            "uuid": "{UUID}", "tls": {{"enabled": true, "server_name": "v.example.net"}},
            "transport": {transport}}}"#
        ))
    };
    let link = vless_link;
    let trojan = outbound(
        r#"{"type": "trojan", "tag": "t", "server": "t.example.net", "server_port": 443,
        "password": "p", "tls": {"enabled": true, "server_name": "t.example.net",
        "insecure": true}}"#,
    );
    let tuic = outbound(
        r#"{"type": "tuic", "tag": "u", "server": "u.example.net", "server_port": 443,
        "uuid": "b38ad070-88e0-4bcc-9808-924f3c0e1bd5", "password": "p",
        "tls": {"enabled": true, "server_name": "u.example.net", "alpn": ["h3-29"]}}"#,
    );
    for (body, link) in [
        (
            vless(r#"{"type": "grpc", "service_name": "svc"}"#),
            link("type=grpc&serviceName=svc"),
        ),
        (
            trojan,
            "trojan://p@t.example.net:443?sni=t.example.net&allowInsecure=1#t".to_owned(),
        ),
        (
            tuic,
            "tuic://b38ad070-88e0-4bcc-9808-924f3c0e1bd5:p@u.example.net:443?alpn=h3-29\
             &sni=u.example.net#u"
                .to_owned(),
        ),
    ] {
        let (node, link) = node_and_link(&body, &link);
        assert_node_of_link(&node, &link, &body);
    }
    // Without a service name, sing-box's gRPC service is empty, as upstream reads it, where a
    // share link's is none, which honk dials as `GunService`; the endpoint id is the same.
    let (mut node, link) = node_and_link(&vless(r#"{"type": "grpc"}"#), &link("type=grpc"));
    let service = &mut node.transport_mut().unwrap().grpc_service;
    assert_eq!(service.take().as_deref(), Some(""));
    assert_node_of_link(&node, &link, "grpc without a service name");
}
