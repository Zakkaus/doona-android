//! Clash, sing-box and SIP008 bodies against the share links of the same nodes; what is skipped;
//! the node budget; malformed bodies; the stored document; a BOM, CRLF line ends and base64.

use super::super::parse::{MAX_NODES_PER_SUBSCRIPTION, Parsed, parse};
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
fn assert_same(left: &[Node], right: &[Node], what: &str) {
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

/// Parses the fixture `body` and checks that each node is the node of the share link in
/// `structured-links.txt` that has its name.
fn same_as_links(body: &str) -> Parsed {
    let parsed = parse(&fixture(body)).unwrap();
    let links = parse(&fixture("structured-links.txt")).unwrap().nodes;
    for node in &parsed.nodes {
        let link = links.iter().find(|l| l.name == node.name);
        let link = link.unwrap_or_else(|| panic!("{body}: no link named {}", node.name));
        let mut node = node.clone();
        // QUIC always runs TLS: honk's Clash builder turns `enabled` on and its share links leave
        // it off. honk-outbound's QUIC dialers do not read it, and the endpoint id leaves it out.
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
            &format!("{body}: {}", node.name),
        );
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
    assert!(super::items(&profile) < super::MAX_ITEMS);
    assert_eq!(parse(profile.as_bytes()).unwrap().nodes.len(), 2000);
    for (open, close) in [("proxies:\n- [", "0]\n"), ("{\"proxies\": [[", "0]]}")] {
        let at = format!("{open}{}{close}", "0,".repeat(super::MAX_ITEMS - 6));
        assert!(super::items(&at) <= super::MAX_ITEMS, "{open}");
        assert_eq!(
            parse(at.as_bytes()).err(),
            Some(ErrorCode::NoNodes),
            "{open}"
        );
        let past = format!("{open}{}{close}", "0,".repeat(super::MAX_ITEMS));
        assert_eq!(
            parse(past.as_bytes()).err(),
            Some(ErrorCode::ParseFailed),
            "{open}"
        );
    }
    // An admitted entry that would store past the bound in block style is refused at the fetch,
    // so state.json never keeps a document it cannot read back.
    let junk = "0,".repeat(super::MAX_ITEMS * 2 / 3);
    let wide = format!(
        "proxies:\n  - {{name: t, type: trojan, server: 192.0.2.1, port: 443, password: p, \
         junk: [{junk}0]}}\n"
    );
    assert!(super::items(&wide) < super::MAX_ITEMS);
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
        assert!(super::items(&bomb) < super::MAX_ITEMS);
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
