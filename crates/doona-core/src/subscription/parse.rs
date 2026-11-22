//! Subscription bodies, extracted from honk-core's `src/subscription.rs` (daeuniverse/honk
//! 8919784a): the BOM and base64 handling of `parse_subscription_content_attempt`,
//! `decode_base64_flexible`, the structured-body detection of `parse_structured_subscription`, and
//! the share-link list path of `parse_auto_subscription` and `parse_uri_subscription` with the
//! admission rules of `AdmissionOwner::emit` (no proxy plugins; the first entry of an endpoint
//! identity wins). Nodes are built by honk-config's `Node::from_share_link`.
//!
//! v0.1 takes share-link lists, plain or base64. Structured bodies (Clash YAML, SIP008 and
//! sing-box JSON) and Surge-style records are refused as `parse_failed`, told by syntax a
//! share-link list never has, so that refusing one builds no tree.

use crate::dto::ErrorCode;
use honk_config::node::Node;
use std::borrow::Cow;
use std::collections::HashSet;

/// The most nodes one subscription brings. The 8 MiB download bound does not bound the nodes, and
/// the registry builds a runtime for each endpoint, so a list over this is `parse_failed` whole.
pub(crate) const MAX_NODES_PER_SUBSCRIPTION: usize = 2_000;

/// The admitted nodes of a body and the share link each was parsed from.
#[derive(Debug, Default)]
pub(crate) struct Parsed {
    pub links: Vec<String>,
    pub nodes: Vec<Node>,
}

/// Parses a downloaded body: `parse_failed` when it is not a share-link list or holds more than
/// [`MAX_NODES_PER_SUBSCRIPTION`] nodes, `no_nodes` when none of its links gives a usable node.
pub(crate) fn parse(body: &[u8]) -> Result<Parsed, ErrorCode> {
    let text = std::str::from_utf8(body).map_err(|_| ErrorCode::ParseFailed)?;
    let text = strip_bom(text);
    let text: Cow<'_, str> = match decode_base64_flexible(text) {
        Some(bytes) => String::from_utf8(bytes)
            .map_err(|_| ErrorCode::ParseFailed)?
            .into(),
        None => text.into(),
    };
    let text = strip_bom(&text);
    if text.trim().is_empty() {
        return Err(ErrorCode::NoNodes);
    }
    if structured(text) || !has_uri(text) {
        return Err(ErrorCode::ParseFailed);
    }
    let parsed = admit(text.lines())?;
    if parsed.nodes.is_empty() {
        return Err(ErrorCode::NoNodes);
    }
    Ok(parsed)
}

/// The nodes of a share-link list, as honk admits them; also re-reads the links `state.json`
/// keeps. Diagnostics are dropped unread: they would quote the links. Stops with `parse_failed`
/// at the first node over [`MAX_NODES_PER_SUBSCRIPTION`].
pub(crate) fn admit<'a>(lines: impl Iterator<Item = &'a str>) -> Result<Parsed, ErrorCode> {
    let mut seen = HashSet::new();
    let mut parsed = Parsed::default();
    for line in lines {
        let link = line.trim();
        if skipped(link) {
            continue;
        }
        let Ok(node) = Node::from_share_link_with_detailed_diagnostics_emit(link, &mut |_| {})
        else {
            continue;
        };
        if usable(&node) && seen.insert(node.id) {
            if parsed.nodes.len() == MAX_NODES_PER_SUBSCRIPTION {
                return Err(ErrorCode::ParseFailed);
            }
            parsed.links.push(link.to_owned());
            parsed.nodes.push(node);
        }
    }
    Ok(parsed)
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

/// A JSON or YAML document, which honk reads as a Clash, SIP008 or sing-box body, told without
/// building a tree and only by syntax a share-link list never has: a JSON object or array that
/// opens (`{"`, `{}`, `[{`, `[]`) after any comment lines, or a line that starts with one of
/// [`DOCUMENT_KEYS`] followed by whitespace or the end of the line, as a YAML key is. A
/// provider's header line above the links (`[Premium / EU]`, `updated: …`, `servers:12`) is
/// neither. Any other document has no line that starts with a scheme and fails
/// as records.
fn structured(text: &str) -> bool {
    let mut chars = document_start(text).chars();
    let open = chars.next();
    let inner = chars.find(|c| !c.is_whitespace());
    let json = matches!(
        (open, inner),
        (Some('{'), Some('"' | '}')) | (Some('['), Some('{' | ']'))
    );
    json || text.lines().any(|line| {
        DOCUMENT_KEYS.iter().any(|key| {
            line.strip_prefix(key)
                .is_some_and(|rest| rest.is_empty() || rest.starts_with(char::is_whitespace))
        })
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
    text.lines().map(str::trim).any(|line| {
        line.split_once("://").is_some_and(|(scheme, _)| {
            !scheme.is_empty()
                && scheme
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'+' | b'-' | b'.'))
        })
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
    use crate::testutil::fixture;

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
    fn other_formats_fail_to_parse_and_empty_lists_have_no_nodes() {
        for name in ["clash.yaml", "sip008.json", "records.conf"] {
            assert_eq!(
                parse(&fixture(name)).err(),
                Some(ErrorCode::ParseFailed),
                "{name}"
            );
        }
        assert_eq!(parse(b"\xff\xfe").err(), Some(ErrorCode::ParseFailed));
        assert_eq!(parse(b" \n").err(), Some(ErrorCode::NoNodes));
        assert_eq!(
            parse(&fixture("unusable.txt")).err(),
            Some(ErrorCode::NoNodes)
        );
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
    fn structured_bodies_are_known_without_a_tree() {
        for body in [
            "port: 7890\nproxies:\n  - {name: a, server: 192.0.2.1}\nrules:\n  - MATCH,trojan://x@h:1",
            "# exported\n\nproxies:\n- name: a\n  url: trojan://x@h:1",
            "---\nproxies: []\ntrojan://x@h:1",
            "- name: a\n  link: trojan://x@h:1",
            "[{\"server\": \"trojan://x@h:1\"}]",
            "{\"outbounds\": []}\ntrojan://x@h:1",
            "# exported\n[\n  {\"server\": \"192.0.2.1\"}\n]\ntrojan://x@h:1",
        ] {
            assert_eq!(
                parse(body.as_bytes()).err(),
                Some(ErrorCode::ParseFailed),
                "{body}"
            );
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
}
