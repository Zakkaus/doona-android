//! Subscription bodies, extracted from honk-core's `src/subscription.rs` (daeuniverse/honk
//! 8919784a): the BOM and base64 handling of `parse_subscription_content_attempt`,
//! `decode_base64_flexible`, and the share-link list path of `parse_auto_subscription` and
//! `parse_uri_subscription` with the admission rules of `AdmissionOwner::emit` (no proxy plugins;
//! the first entry of an endpoint identity wins). Nodes are built by honk-config's
//! `Node::from_share_link`.
//!
//! A body is a share-link list, plain or base64, or a structured document: Clash or Mihomo YAML,
//! sing-box JSON or SIP008 JSON, which [`super::structured`] reads. A body whose first line is a
//! share link is a list and builds no tree; any other is a document when it builds a YAML or JSON
//! mapping or sequence, as upstream decides. Surge-style records are `parse_failed`.

use super::structured;
use crate::dto::{ErrorCode, SubscriptionFormat};
use honk_config::node::Node;
use std::borrow::Cow;
use std::collections::HashSet;
use uuid::Uuid;

/// The most nodes one subscription brings. The 8 MiB download bound does not bound the nodes, and
/// the registry builds a runtime for each endpoint, so a list over this is `parse_failed` whole.
pub(crate) const MAX_NODES_PER_SUBSCRIPTION: usize = 2_000;

/// The longest `vmess://` payload decoded. It is base64 of a JSON object whose unknown fields
/// honk-config keeps as `serde_json::Value`, about 20 times the link (a 7.6 MB link costs 146 MB);
/// real ones are under 1 KiB. A longer link is skipped undecoded, as any invalid link is.
const MAX_VMESS_PAYLOAD: usize = 16 << 10;

/// The admitted nodes of a body and what `state.json` keeps to parse them again: the share link
/// of each node, or for a structured body, the document of the entries they came from.
#[derive(Debug)]
pub(crate) struct Parsed {
    /// Base64-wrapped Clash and SIP008 bodies report their inner format; only wrapped share links
    /// report [`SubscriptionFormat::Base64`].
    pub format: SubscriptionFormat,
    pub links: Vec<String>,
    pub nodes: Vec<Node>,
    pub document: Option<String>,
}

/// Parses a downloaded body, or a stored [`Parsed::document`]: `parse_failed` when it is neither a
/// share-link list nor a document [`super::structured`] reads, or holds more than
/// [`MAX_NODES_PER_SUBSCRIPTION`] nodes; `no_nodes` when none of its entries gives a usable node.
pub(crate) fn parse(body: &[u8]) -> Result<Parsed, ErrorCode> {
    let text = std::str::from_utf8(body).map_err(|_| ErrorCode::ParseFailed)?;
    let text = strip_bom(text);
    let decoded: Cow<'_, str> = match decode_base64_flexible(text) {
        Some(bytes) => String::from_utf8(bytes)
            .map_err(|_| ErrorCode::ParseFailed)?
            .into(),
        None => text.into(),
    };
    let text = strip_bom(&decoded);
    if text.trim().is_empty() {
        return Err(ErrorCode::NoNodes);
    }
    let mut parsed = if starts_with_link(text) {
        admit(text.lines())?
    } else if let Some(parsed) = structured::parse(text)? {
        parsed
    } else if has_uri(text) {
        admit(text.lines())?
    } else {
        return Err(ErrorCode::ParseFailed);
    };
    if parsed.nodes.is_empty() {
        return Err(ErrorCode::NoNodes);
    }
    if matches!(decoded, Cow::Owned(_)) && parsed.format == SubscriptionFormat::ShareLinks {
        parsed.format = SubscriptionFormat::Base64;
    }
    Ok(parsed)
}

/// The nodes of a share-link list, as honk admits them; also re-reads the links `state.json`
/// keeps. Diagnostics are dropped unread: they would quote the links. Stops with `parse_failed`
/// at the first node over [`MAX_NODES_PER_SUBSCRIPTION`].
pub(crate) fn admit<'a>(lines: impl Iterator<Item = &'a str>) -> Result<Parsed, ErrorCode> {
    let mut admission = Admission::default();
    let mut links = Vec::new();
    for line in lines {
        let link = line.trim();
        if skipped(link) {
            continue;
        }
        let Ok(node) = parse_link(link) else {
            continue;
        };
        if admission.admit(node)? {
            links.push(link.to_owned());
        }
    }
    Ok(Parsed {
        format: SubscriptionFormat::ShareLinks,
        links,
        nodes: admission.nodes,
        document: None,
    })
}

/// honk's admission of a body's nodes (`AdmissionOwner::emit`), for share links and structured
/// entries alike: a node that is not [`usable`] is skipped, and the first entry of an endpoint
/// identity wins.
#[derive(Default)]
pub(super) struct Admission {
    seen: HashSet<Uuid>,
    pub nodes: Vec<Node>,
}

impl Admission {
    /// Whether `node` is admitted; `parse_failed` when it would be one over
    /// [`MAX_NODES_PER_SUBSCRIPTION`].
    pub(super) fn admit(&mut self, node: Node) -> Result<bool, ErrorCode> {
        if !usable(&node) || !self.seen.insert(node.id) {
            return Ok(false);
        }
        if self.nodes.len() == MAX_NODES_PER_SUBSCRIPTION {
            return Err(ErrorCode::ParseFailed);
        }
        self.nodes.push(node);
        Ok(true)
    }
}

fn strip_bom(text: &str) -> &str {
    text.strip_prefix('\u{feff}').unwrap_or(text)
}

/// Blank lines, comments and the profile metadata lines some providers add.
fn skipped(link: &str) -> bool {
    link.is_empty()
        || ["#", ";", "//"].iter().any(|p| link.starts_with(p))
        || ["REMARKS=", "STATUS="].iter().any(|p| link.starts_with(p))
}

/// Reject a chain before honk-config can interpret its first hop as a standalone node.
fn parse_link(link: &str) -> Result<Node, &'static str> {
    let address = link.split_once('#').map_or(link, |(address, _)| address);
    if address.split("->").skip(1).any(|hop| {
        let hop = hop.trim_start();
        hop.starts_with(|c: char| c.is_ascii_alphabetic()) && is_link(hop)
    }) {
        return Err("chaining is unsupported");
    }
    if link.split_once("://").is_some_and(|(scheme, payload)| {
        scheme.eq_ignore_ascii_case("vmess") && payload.len() > MAX_VMESS_PAYLOAD
    }) {
        return Err("VMess payload is too long");
    }
    // honk-config splits at the first raw arrow, even inside a fragment, before URL decoding.
    let escaped;
    let link = if link.contains("->") {
        escaped = link.replace("->", "-%3E");
        &escaped
    } else {
        link
    };
    Node::from_share_link_with_detailed_diagnostics_emit(link, &mut |_| {})
        .map_err(|_| "invalid share link")
}

/// honk drops Shadowsocks nodes that need a plugin. doona also drops a node honk's outbound
/// registry would refuse, so one bad entry never keeps the others from being dialed.
fn usable(node: &Node) -> bool {
    let plugin = node.shadowsocks().is_some_and(|config| {
        [&config.plugin, &config.plugin_opts]
            .iter()
            .any(|value| value.as_deref().is_some_and(|v| !v.trim().is_empty()))
    });
    let tls_ok = !node
        .tls()
        .is_some_and(|tls| tls.enabled || !tls.alpn.is_empty())
        || honk_outbound::tls::validate_connector_config(node).is_ok();
    !plugin
        && tls_ok
        && honk_config::node::validate_node_collection(std::slice::from_ref(node)).is_ok()
}

/// honk's top-level keys of a Clash, sing-box or SIP008 body.
const DOCUMENT_KEYS: [&str; 3] = ["proxies:", "outbounds:", "servers:"];

/// Whether the first line that is not blank or a `#` or `;` comment is a share link. Upstream reads
/// such a body as links unless it builds a YAML or JSON mapping or sequence, which only a list of
/// links with `: ` in each line does; doona reads it as links without trying a tree. A document
/// key on a later line (`servers: 2`) does not make it a document.
fn starts_with_link(text: &str) -> bool {
    document_start(text).lines().next().is_some_and(is_link)
}

/// Upstream's test of a body that does not parse, on its first line that is not blank or a `#` or
/// `;` comment: when that line opens a document, the body is a malformed one, not links. Upstream
/// takes a line that starts with a brace, a bracket (but an INI header) or a document key. doona
/// takes a brace or a bracket only before JSON (`{"`, `{}`, `[{`, `[]`) or, for a brace, a document
/// key; a document key only before whitespace or the line end; and a document start marker
/// (`---`). A provider's header line above the links (`[Premium / EU]`, `{Premium}`, `servers:12`)
/// is then not one.
pub(super) fn opens_like_a_document(text: &str) -> bool {
    let start = document_start(text);
    let first = start.lines().next().unwrap_or_default().trim_end();
    let mut chars = start.chars();
    let open = chars.next();
    let inner = chars.find(|c| !c.is_whitespace());
    let json = matches!(
        (open, inner),
        (Some('{'), Some('"' | '}')) | (Some('['), Some('{' | ']'))
    );
    json || is_document_key(first)
        || first
            .strip_prefix('{')
            .is_some_and(|rest| is_document_key(rest.trim_start()))
        || first == "---"
        || first.starts_with("--- ")
}

/// A line that starts with one of [`DOCUMENT_KEYS`] followed by whitespace or the end of the line,
/// as a YAML key is.
fn is_document_key(line: &str) -> bool {
    DOCUMENT_KEYS.iter().any(|key| {
        line.strip_prefix(key)
            .is_some_and(|rest| rest.is_empty() || rest.starts_with(char::is_whitespace))
    })
}

/// `text` from its first line that is not blank or a `#` or `;` comment.
fn document_start(text: &str) -> &str {
    let mut rest = text.trim_start();
    while rest.starts_with(['#', ';']) {
        rest = rest
            .split_once('\n')
            .map_or("", |(_, after)| after)
            .trim_start();
    }
    rest
}

/// A line starting with a URI scheme; without one honk reads the body as Surge-style records.
fn has_uri(text: &str) -> bool {
    text.lines().any(is_link)
}

/// A line that starts with a URI scheme, as a share link does.
fn is_link(line: &str) -> bool {
    line.trim().split_once("://").is_some_and(|(scheme, _)| {
        !scheme.is_empty()
            && scheme
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'+' | b'-' | b'.'))
    })
}

fn decode_base64_flexible(input: &str) -> Option<Vec<u8>> {
    use base64::Engine;

    let input = input.trim();
    if input.bytes().any(|byte| {
        !byte.is_ascii_alphanumeric()
            && !byte.is_ascii_whitespace()
            && !matches!(byte, b'+' | b'/' | b'-' | b'_' | b'=')
    }) {
        return None;
    }
    let compact: Cow<'_, str> = if input.bytes().any(|byte| byte.is_ascii_whitespace()) {
        Cow::Owned(
            input
                .chars()
                .filter(|ch| !ch.is_ascii_whitespace())
                .collect(),
        )
    } else {
        Cow::Borrowed(input)
    };
    [
        &base64::engine::general_purpose::STANDARD,
        &base64::engine::general_purpose::STANDARD_NO_PAD,
        &base64::engine::general_purpose::URL_SAFE,
        &base64::engine::general_purpose::URL_SAFE_NO_PAD,
    ]
    .into_iter()
    .find_map(|engine| engine.decode(compact.as_bytes()).ok())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::alloc::allocated;
    use crate::testutil::fixture;
    use base64::Engine;
    use base64::engine::general_purpose::STANDARD;

    fn names(p: &Parsed) -> Vec<&str> {
        p.nodes.iter().map(|n| n.name.as_str()).collect()
    }

    #[test]
    fn plain_and_base64_lists_give_the_same_nodes() {
        let plain = parse(&fixture("links.txt")).unwrap();
        let encoded = parse(&fixture("links.b64")).unwrap();
        assert_eq!(names(&plain), names(&encoded));
        assert_eq!(plain.links, encoded.links);
        // Nine entries: a comment, a profile line, a plugin node and a repeated endpoint go.
        assert_eq!(plain.nodes.len(), 9, "{:?}", names(&plain));
        assert_eq!(plain.links.len(), plain.nodes.len());
    }

    #[test]
    fn chained_share_link_is_skipped() {
        let chain = format!("trojan://p@front.test:443 -> {}#US", vmess(128));
        let direct = "trojan://p@direct.test:443#direct";
        let body = format!("{chain}\n{direct}");
        let parsed = parse(body.as_bytes()).unwrap();
        assert_eq!(names(&parsed), ["direct"]);
        assert_eq!(parsed.links, [direct]);
        assert_eq!(parse_link(&chain).unwrap_err(), "chaining is unsupported");
    }

    #[test]
    fn arrows_in_share_link_names_survive_import() {
        for (fragment, name) in [
            ("HK->JP", "HK->JP"),
            ("HK%2D%3EJP", "HK->JP"),
            ("HK->vmess://JP", "HK->vmess://JP"),
        ] {
            let link = format!("trojan://p@hk.test:443#{fragment}");
            let parsed = parse(link.as_bytes()).unwrap();
            assert_eq!(names(&parsed), [name]);
            assert_eq!(parsed.nodes[0].host, "hk.test");
            assert_eq!(parsed.nodes[0].port, 443);
        }
    }

    #[test]
    fn a_repeated_endpoint_keeps_its_first_entry() {
        let parsed = parse(&fixture("links.txt")).unwrap();
        let hk: Vec<&str> = names(&parsed)
            .into_iter()
            .filter(|n| n.starts_with("hk-01"))
            .collect();
        // Two endpoints share this name; the third entry repeats the first endpoint.
        assert_eq!(hk, ["hk-01", "hk-01"]);
        let again = admit(parsed.links.iter().map(String::as_str)).unwrap();
        assert_eq!(names(&again), names(&parsed));
    }

    #[test]
    fn records_fail_to_parse_and_empty_lists_have_no_nodes() {
        assert_eq!(
            parse(&fixture("records.conf")).err(),
            Some(ErrorCode::ParseFailed)
        );
        assert_eq!(parse(b"\xff\xfe").err(), Some(ErrorCode::ParseFailed));
        assert_eq!(parse(b" \n").err(), Some(ErrorCode::NoNodes));
        assert_eq!(
            parse(&fixture("unusable.txt")).err(),
            Some(ErrorCode::NoNodes)
        );
    }

    /// A VMess link of `json_len` bytes of JSON, padded with spaces.
    fn vmess(json_len: usize) -> String {
        let json = r#"{"v":"2","ps":"vm","add":"example.com","port":"443","id":"00000000-0000-0000-0000-000000000001","aid":"0","scy":"auto","net":"tcp"}"#;
        format!("vmess://{}", STANDARD.encode(format!("{json:json_len$}")))
    }

    #[test]
    fn a_vmess_link_over_the_cap_is_skipped_before_it_is_decoded() {
        // 12 KiB of JSON is 16 KiB of base64: the longest payload decoded.
        let at_cap = vmess(MAX_VMESS_PAYLOAD / 4 * 3);
        assert_eq!(at_cap.len(), "vmess://".len() + MAX_VMESS_PAYLOAD);
        assert_eq!(names(&parse(at_cap.as_bytes()).unwrap()), ["vm"]);
        let over = vmess(MAX_VMESS_PAYLOAD / 4 * 3 + 1);
        assert_eq!(parse(over.as_bytes()).err(), Some(ErrorCode::NoNodes));
        // The review's link: 7.6 MB, its JSON an unknown field of 2.85 million numbers.
        let numbers = "0,".repeat(2_850_000);
        let json = format!(
            r#"{{"add":"example.com","port":"443","id":"00000000-0000-0000-0000-000000000001","pad":[{numbers}0]}}"#
        );
        let big = format!("vmess://{}", STANDARD.encode(json));
        assert!(big.len() > 7_600_000, "{}", big.len());
        let (parsed, bytes) = allocated(|| parse(big.as_bytes()));
        assert_eq!(parsed.err(), Some(ErrorCode::NoNodes));
        assert!(bytes < 64 << 10, "{bytes} bytes allocated");
    }

    #[test]
    fn base64_variants_decode() {
        assert_eq!(decode_base64_flexible("aGk=").as_deref(), Some(&b"hi"[..]));
        assert_eq!(decode_base64_flexible("aGk").as_deref(), Some(&b"hi"[..]));
        assert_eq!(
            decode_base64_flexible("a G k =\n").as_deref(),
            Some(&b"hi"[..])
        );
        assert_eq!(decode_base64_flexible("trojan://x@h:1"), None);
    }

    fn links(n: usize) -> String {
        (0..n)
            .map(|i| format!("trojan://p{i}@192.0.2.1:443#n{i}\n"))
            .collect()
    }

    #[test]
    fn a_list_over_the_node_budget_is_refused() {
        let full = parse(links(MAX_NODES_PER_SUBSCRIPTION).as_bytes()).unwrap();
        assert_eq!(full.nodes.len(), MAX_NODES_PER_SUBSCRIPTION);
        let over = parse(links(MAX_NODES_PER_SUBSCRIPTION + 1).as_bytes());
        assert_eq!(over.err(), Some(ErrorCode::ParseFailed));
        // A repeated endpoint counts once.
        let repeated = links(MAX_NODES_PER_SUBSCRIPTION) + &links(10);
        assert!(parse(repeated.as_bytes()).is_ok());
    }

    #[test]
    fn a_body_that_looks_structured_is_read_only_as_a_document() {
        // The share link in each is never taken: these are documents with no node, or that do
        // not parse.
        for (body, code) in [
            (
                "port: 7890\nproxies:\n  - {name: a, server: 192.0.2.1}\nrules:\n  - MATCH,trojan://x@h:1",
                ErrorCode::NoNodes,
            ),
            (
                "# exported\n\nproxies:\n- name: a\n  url: trojan://x@h:1",
                ErrorCode::NoNodes,
            ),
            ("[{\"server\": \"trojan://x@h:1\"}]", ErrorCode::NoNodes),
            ("---\nproxies: []\ntrojan://x@h:1", ErrorCode::ParseFailed),
            (
                "{\"outbounds\": []}\ntrojan://x@h:1",
                ErrorCode::ParseFailed,
            ),
            (
                "# exported\n[\n  {\"server\": \"192.0.2.1\"}\n]\ntrojan://x@h:1",
                ErrorCode::ParseFailed,
            ),
            // A YAML sequence is a bare SIP008 array, here of an entry that is not a server.
            ("- name: a\n  link: trojan://x@h:1", ErrorCode::NoNodes),
        ] {
            assert_eq!(parse(body.as_bytes()).err(), Some(code), "{body}");
        }
        // A provider's header line above the links is not a document.
        for links in [
            "STATUS=expires: 2027\ntrojan://secret@192.0.2.1:443#a",
            "updated: 2026-11-01\ntrojan://secret@192.0.2.1:443#a",
        ] {
            assert_eq!(parse(links.as_bytes()).unwrap().nodes.len(), 1, "{links}");
        }
    }

    #[test]
    fn a_bracketed_or_braced_header_above_the_links_is_not_json() {
        for links in [
            "[Premium / EU]\ntrojan://secret@192.0.2.1:443#a",
            "[ 2026-11 ]\ntrojan://secret@192.0.2.1:443#a",
            "{Premium}\ntrojan://secret@192.0.2.1:443#a",
        ] {
            assert_eq!(parse(links.as_bytes()).unwrap().nodes.len(), 1, "{links}");
        }
    }

    #[test]
    fn a_header_that_only_starts_like_a_document_key_is_not_yaml() {
        for links in [
            "servers:12\ntrojan://secret@192.0.2.1:443#a",
            "proxies:EU\ntrojan://secret@192.0.2.1:443#a",
            "outbounds:-\ntrojan://secret@192.0.2.1:443#a",
        ] {
            assert_eq!(parse(links.as_bytes()).unwrap().nodes.len(), 1, "{links}");
        }
        // A key followed by a space, a carriage return or the end of the line is one.
        for body in ["servers: []\ntrojan://x@h:1", "proxies:\r\ntrojan://x@h:1"] {
            assert_eq!(
                parse(body.as_bytes()).err(),
                Some(ErrorCode::ParseFailed),
                "{body:?}"
            );
        }
    }

    #[test]
    fn a_list_is_told_by_its_first_line_and_a_document_by_its_tree() {
        // A document key on a later line does not make a list a document.
        for links in [
            "trojan://secret@192.0.2.1:443#a\nservers: 2\n",
            "# list\ntrojan://secret@192.0.2.1:443#a\nproxies:\n  - x\n",
        ] {
            assert_eq!(parse(links.as_bytes()).unwrap().nodes.len(), 1, "{links:?}");
        }
        // Flow style and an indented key are documents.
        let trojan = "{name: t, type: trojan, server: 192.0.2.1, port: 443, password: p}";
        for body in [
            format!("{{proxies: [{trojan}]}}"),
            format!("  proxies:\n    - {trojan}\n"),
        ] {
            assert_eq!(names(&parse(body.as_bytes()).unwrap()), ["t"], "{body:?}");
        }
        // A Clash document with no proxies has no nodes, whatever links its other fields hold.
        let notes = "'proxies': []\nnotes: |\n  trojan://p@192.0.2.1:443\n";
        assert_eq!(parse(notes.as_bytes()).err(), Some(ErrorCode::NoNodes));
        // A bare SIP008 array skips an entry that is not a server, as any document does.
        let sip008 = r#"[null, {"server": "192.0.2.1", "server_port": 8388, "password": "p",
            "method": "aes-256-gcm"}]"#;
        assert_eq!(parse(sip008.as_bytes()).unwrap().nodes.len(), 1);
    }
}
