//! Structured subscription bodies, extracted from honk-core's `src/subscription.rs` (daeuniverse/honk
//! 8919784a): `parse_structured_value`, and the structured path of `parse_auto_subscription`,
//! which is `parse_structured_subscription` with `emit_clash_proxies`. A Clash or Mihomo document's
//! `proxies` are built by [`clash`] (`subscription/clash.rs` and `clash/`); sing-box `outbounds` and
//! SIP008 `servers`, as an object or a bare array, by [`json`] (`subscription/json.rs` and
//! `json/sing_box.rs`), which turns each entry into a Clash proxy first. A node is built as a
//! honk-config `Node`, checked by `Node::validate` and identified by `Node::derive_id`, as a share
//! link is, so a Clash entry and its share link give the same node.
//!
//! Only the nodes are read: Clash `proxy-groups` and `rules` and sing-box `route` are ignored, since
//! doona builds its own groups (ARCH D29). An entry honk skips, of an unsupported type such as `ssr`
//! or `snell`, malformed, or a sing-box `direct`, `block`, `dns`, `selector` or `urltest` outbound,
//! counts toward neither the nodes nor an error. Nodes are admitted as share links are
//! ([`Admission`]). doona drops the diagnostics honk reports for skipped entries, so their
//! plumbing (`IndexedOutcome`, `AdmissionOwner`) is not extracted.
//!
//! `state.json` keeps a structured subscription as [`Parsed::document`]: the entries its nodes came
//! from, under their original key, which [`super::parse::parse`] reads back to the same nodes.

mod bounded;
mod clash;
mod json;

use self::bounded::{Bounded, Budget};
use super::parse::{Admission, Parsed};
use crate::dto::ErrorCode;
use honk_config::node::Node;
use serde::de::DeserializeSeed;
use serde_yaml::{Mapping, Value};

/// The most items, YAML events or JSON values, a structured body may describe; past it a body is
/// `parse_failed` before any tree is built. serde_yaml lists every event of a document before it
/// builds a value, at about 200 bytes an event: an 8 MiB body of one-digit items peaked at 836 MB,
/// one at this bound at 84 MB. A Mihomo profile of 2,000 nodes, a group of all of them and 20,000
/// rules (1.7 MB) counts 226,000 and peaked at 30 MB (release build on the desktop, 2026-11-23).
/// The tree is held to as many values, and to the body's length in string bytes, aliases
/// included ([`bounded`]).
const MAX_ITEMS: usize = 400_000;

/// An upper bound on the items `text` describes: every item after the first follows one of these
/// bytes, a separator, a collection opening, a block entry, a key, an alias or a line break.
fn items(text: &str) -> usize {
    text.bytes()
        .filter(|b| matches!(b, b',' | b':' | b'-' | b'?' | b'[' | b'{' | b'*' | b'\n'))
        .count()
}

/// Takes each entry of a structured body and its node, or `None` for an entry honk skips. An
/// error ends the parse.
type Emit<'a> = dyn FnMut(&Value, Option<Node>) -> anyhow::Result<()> + 'a;

/// The nodes of a body that [`super::parse`] took as a structured document: `parse_failed` when
/// it does not parse, is not a Clash, sing-box or SIP008 document, or holds more than
/// [`super::parse::MAX_NODES_PER_SUBSCRIPTION`] nodes. No node is `no_nodes`, which the caller
/// decides.
pub(super) fn parse(text: &str) -> Result<Parsed, ErrorCode> {
    if items(text) > MAX_ITEMS {
        return Err(ErrorCode::ParseFailed);
    }
    let value = parse_structured_value(text).ok_or(ErrorCode::ParseFailed)?;
    let mut admission = Admission::default();
    let mut kept = Vec::new();
    let key = emit_structured(value, &mut |entry, node| {
        let over = || anyhow::anyhow!("more nodes than one subscription may bring");
        if let Some(node) = node
            && admission.admit(node).map_err(|_| over())?
        {
            kept.push(entry.clone());
        }
        Ok(())
    })
    .map_err(|_| ErrorCode::ParseFailed)?;
    let mut document = Mapping::new();
    document.insert(Value::String(key.into()), Value::Sequence(kept));
    let document = serde_yaml::to_string(&document).map_err(|_| ErrorCode::ParseFailed)?;
    // Block style and expanded aliases can count more than the body did; refused now, a document
    // that state.json could not read back is never committed.
    if items(&document) > MAX_ITEMS {
        return Err(ErrorCode::ParseFailed);
    }
    Ok(Parsed {
        links: Vec::new(),
        nodes: admission.nodes,
        document: Some(document),
    })
}

/// Upstream's `parse_structured_value`, which builds the tree with `serde_json::from_str` or else
/// `serde_yaml::from_str`; here each attempt builds it within a [`Budget`].
fn parse_structured_value(content: &str) -> Option<Value> {
    let content = content.trim_start().trim_start_matches('\u{feff}');
    let budget = || Budget::new(MAX_ITEMS, content.len());
    let json = || {
        let mut json = serde_json::Deserializer::from_str(content);
        let value = Bounded(&budget()).deserialize(&mut json)?;
        json.end().map(|()| value)
    };
    let yaml = || Bounded(&budget()).deserialize(serde_yaml::Deserializer::from_str(content));
    // YAML's quoted-scalar decoder does not accept JSON UTF-16 surrogate pairs.
    json().ok().or_else(|| yaml().ok())
}

/// `parse_structured_subscription` once the body parsed: a mapping with a `proxies` sequence is a
/// Clash document, any other mapping or sequence a sing-box or SIP008 one. Returns the key the
/// entries go under in the stored document; a bare SIP008 array goes under `servers`, which reads
/// the same.
fn emit_structured(value: Value, emit: &mut Emit<'_>) -> anyhow::Result<&'static str> {
    if let Value::Mapping(root) = &value
        && let Some(proxies) = yaml_value(root, "proxies").and_then(Value::as_sequence)
    {
        emit_clash_proxies(proxies, emit)?;
        return Ok("proxies");
    }
    let key = match &value {
        Value::Mapping(root) if root.contains_key("outbounds") => "outbounds",
        _ => "servers",
    };
    match value {
        Value::Mapping(_) | Value::Sequence(_) => json::emit_json_subscription(value, emit)?,
        // Upstream reads such a body as links or records; doona took it as a document already.
        _ => anyhow::bail!("not a structured subscription"),
    }
    Ok(key)
}

fn emit_clash_proxies(proxies: &[Value], emit: &mut Emit<'_>) -> anyhow::Result<()> {
    for proxy in proxies {
        let node = proxy
            .as_mapping()
            .and_then(|mapping| clash::parse_clash_proxy(mapping, Some("h3")).ok());
        emit(proxy, node)?;
    }
    Ok(())
}

fn yaml_value<'a>(mapping: &'a Mapping, key: &str) -> Option<&'a Value> {
    mapping.get(key)
}

#[cfg(test)]
mod tests;
