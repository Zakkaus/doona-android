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
//! Which body is a document follows upstream's `parse_structured_subscription`: one that builds a
//! YAML or JSON mapping or sequence is one; one that does not parse is a malformed document when its
//! first line opens like one ([`super::parse::opens_like_a_document`]), and is read as links
//! otherwise. doona adds what [`guard`] refuses before any tree is built, and reads a body whose
//! first line is a share link as links without trying a tree.
//!
//! `state.json` keeps a structured subscription as [`Parsed::document`]: the entries its nodes came
//! from, under their original key, which [`super::parse::parse`] reads back to the same nodes.

mod bounded;
mod clash;
mod guard;
mod json;

use self::bounded::{Bounded, Budget};
use super::parse::{Admission, Parsed, opens_like_a_document};
use crate::dto::ErrorCode;
use honk_config::node::Node;
use serde::de::DeserializeSeed;
use serde_yaml::{Mapping, Value};

/// Takes each entry of a structured body and its node, or `None` for an entry honk skips. An
/// error ends the parse.
type Emit<'a> = dyn FnMut(&Value, Option<Node>) -> anyhow::Result<()> + 'a;

/// The nodes of a body that is not a share-link list by its first line; `None` when it is not a
/// document either, as upstream then reads it as links. `parse_failed` when [`guard`] refuses it,
/// when it does not parse and opens like a document, when it is a mapping or sequence but not a
/// Clash, sing-box or SIP008 document, or when it holds more than
/// [`super::parse::MAX_NODES_PER_SUBSCRIPTION`] nodes. No node is `no_nodes`, which the caller
/// decides.
pub(super) fn parse(text: &str) -> Result<Option<Parsed>, ErrorCode> {
    guard::check(text)?;
    match parse_structured_value(text) {
        Some(value @ (Value::Mapping(_) | Value::Sequence(_))) => nodes(value).map(Some),
        Some(_) => Ok(None),
        None if opens_like_a_document(text) => Err(ErrorCode::ParseFailed),
        None => Ok(None),
    }
}

/// The admitted nodes of a document, and the entries they came from to store.
fn nodes(value: Value) -> Result<Parsed, ErrorCode> {
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
    // A bare SIP008 array is stored bare: under a key, every entry would sit one level deeper
    // than in the body, and one at serde_yaml's depth limit would not read back.
    let document = match key {
        Some(key) => {
            let mut document = Mapping::new();
            document.insert(Value::String(key.into()), Value::Sequence(kept));
            Value::Mapping(document)
        }
        None => Value::Sequence(kept),
    };
    let document = serde_yaml::to_string(&document).map_err(|_| ErrorCode::ParseFailed)?;
    // Block style and expanded aliases can count more than the body did; refused now, a document
    // that state.json could not read back is never committed.
    guard::check(&document)?;
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
    let budget = || Budget::new(guard::MAX_ITEMS, content.len());
    let json = || {
        let mut json = serde_json::Deserializer::from_str(content);
        let value = Bounded(&budget()).deserialize(&mut json)?;
        json.end().map(|()| value)
    };
    let yaml = || Bounded(&budget()).deserialize(serde_yaml::Deserializer::from_str(content));
    // YAML's quoted-scalar decoder does not accept JSON UTF-16 surrogate pairs.
    json().ok().or_else(|| yaml().ok())
}

/// `parse_structured_subscription` once the body parsed to a mapping or sequence: a mapping with a
/// `proxies` sequence is a Clash document, any other a sing-box or SIP008 one, and a sequence a
/// bare SIP008 array. Returns the key the entries go under in the stored document, `None` for a
/// bare array.
fn emit_structured(value: Value, emit: &mut Emit<'_>) -> anyhow::Result<Option<&'static str>> {
    if let Value::Mapping(root) = &value
        && let Some(proxies) = yaml_value(root, "proxies").and_then(Value::as_sequence)
    {
        emit_clash_proxies(proxies, emit)?;
        return Ok(Some("proxies"));
    }
    let key = match &value {
        Value::Mapping(root) if root.contains_key("outbounds") => Some("outbounds"),
        Value::Mapping(_) => Some("servers"),
        _ => None,
    };
    json::emit_json_subscription(value, emit)?;
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
