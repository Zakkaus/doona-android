//! SIP008 and sing-box node imports normalized through the common Clash builder.
//!
//! honk-core's `src/subscription/json.rs` (daeuniverse/honk 8919784a): `emit_json_subscription`
//! hands each entry and its node, or `None` for an entry honk skips, to `emit` rather than an
//! indexed outcome, since doona drops the diagnostics.

use serde_yaml::{Mapping, Value};

use super::Emit;

mod sing_box;

enum NormalizedEntry {
    Node(Mapping),
    /// An entry that is not a proxy, such as a sing-box selector, or of an unsupported type.
    Skipped,
}

type NodeResult = Result<NormalizedEntry, &'static str>;

pub(super) fn emit_json_subscription(value: Value, emit: &mut Emit<'_>) -> anyhow::Result<()> {
    match value {
        Value::Sequence(servers) => parse_entries(servers, normalize_sip008, emit),
        Value::Mapping(mut root) => {
            let outbounds = root.remove("outbounds");
            let servers = root.remove("servers");
            match (outbounds, servers) {
                (Some(_), Some(_)) => anyhow::bail!("ambiguous JSON subscription wrapper"),
                (Some(Value::Sequence(outbounds)), None) => {
                    parse_entries(outbounds, sing_box::normalize, emit)
                }
                (Some(_), None) => anyhow::bail!("sing-box 'outbounds' must be an array"),
                (None, Some(Value::Sequence(servers))) => {
                    if let Some(version) = root.remove("version")
                        && !matches!(version.as_u64(), Some(1 | 2))
                    {
                        anyhow::bail!("unsupported SIP008 version");
                    }
                    parse_entries(servers, normalize_sip008, emit)
                }
                (None, Some(_)) => anyhow::bail!("SIP008 'servers' must be an array"),
                (None, None) => anyhow::bail!("unsupported JSON subscription wrapper"),
            }
        }
        _ => anyhow::bail!("JSON subscription root must be an object or array"),
    }
}

fn parse_entries(
    entries: Vec<Value>,
    normalize: fn(Value) -> NodeResult,
    emit: &mut Emit<'_>,
) -> anyhow::Result<()> {
    for entry in entries {
        let node = match normalize(entry.clone()) {
            Ok(NormalizedEntry::Node(proxy)) => super::clash::parse_clash_proxy(&proxy, None).ok(),
            Ok(NormalizedEntry::Skipped) | Err(_) => None,
        };
        emit(&entry, node)?;
    }
    Ok(())
}

fn normalize_sip008(value: Value) -> NodeResult {
    let Value::Mapping(mut source) = value else {
        return Err("SIP008 server must be an object");
    };
    let mut proxy = Mapping::new();
    put(&mut proxy, "type", Value::String("ss".into()));
    move_strings(
        &mut source,
        &mut proxy,
        &[
            ("server", "server"),
            ("method", "cipher"),
            ("remarks", "name"),
            ("plugin", "plugin"),
            ("plugin_opts", "plugin-opts"),
        ],
    )?;
    move_credential_strings(&mut source, &mut proxy, &[("password", "password")])?;
    match source.remove("server_port") {
        None | Some(Value::Null) => {}
        Some(port) if matches!(port.as_u64(), Some(1..=65535)) => {
            put(&mut proxy, "port", port);
        }
        Some(_) => return Err("SIP008 server port must be an integer"),
    }
    Ok(NormalizedEntry::Node(proxy))
}

fn put(mapping: &mut Mapping, key: &str, value: Value) {
    mapping.insert(Value::String(key.into()), value);
}

fn take_optional_string(mapping: &mut Mapping, key: &str) -> Result<Option<String>, &'static str> {
    match mapping.remove(key) {
        Some(Value::String(value)) => Ok(Some(value)),
        Some(Value::Null) | None => Ok(None),
        Some(_) => Err("JSON string setting has an invalid type"),
    }
}

fn take_optional_credential(
    mapping: &mut Mapping,
    key: &str,
) -> Result<Option<String>, &'static str> {
    match mapping.remove(key) {
        Some(Value::String(value)) => Ok(Some(value)),
        Some(Value::Number(value)) if value.as_f64().is_some_and(|value| value.is_finite()) => {
            Ok(Some(value.to_string()))
        }
        Some(Value::Null) | None => Ok(None),
        Some(_) => Err("JSON credential setting has an invalid type"),
    }
}

fn move_strings(
    source: &mut Mapping,
    target: &mut Mapping,
    fields: &[(&str, &str)],
) -> Result<(), &'static str> {
    for &(source_key, target_key) in fields {
        if let Some(value) =
            take_optional_string(source, source_key)?.filter(|value| !value.trim().is_empty())
        {
            put(target, target_key, Value::String(value));
        }
    }
    Ok(())
}

fn move_credential_strings(
    source: &mut Mapping,
    target: &mut Mapping,
    fields: &[(&str, &str)],
) -> Result<(), &'static str> {
    for &(source_key, target_key) in fields {
        if let Some(value) = take_optional_credential(source, source_key)? {
            put(target, target_key, Value::String(value));
        }
    }
    Ok(())
}
