//! The stream transport, TLS and REALITY settings of a sing-box outbound: honk-core's
//! `src/subscription/json/sing_box.rs` (daeuniverse/honk 8919784a), with `normalize_tls` split.

use honk_config::options::vocab::{optional_text, stream_transport};
use serde_yaml::{Mapping, Value};

use super::{active, move_strings, put, reject_active_remainder, take_bool, take_optional_string};

pub(super) fn normalize_transport(
    source: &mut Mapping,
    proxy: &mut Mapping,
) -> Result<(), &'static str> {
    let Some(value) = source.remove("transport") else {
        return Ok(());
    };
    if matches!(value, Value::Null) {
        return Ok(());
    }
    let Value::Mapping(mut transport) = value else {
        return Err("sing-box transport settings must be an object");
    };
    let kind = match transport.remove("type") {
        Some(Value::String(kind)) => kind,
        Some(_) => return Err("sing-box transport type must be a string"),
        None if transport.values().all(|value| !active(value)) => return Ok(()),
        None => return Err("sing-box transport type is missing"),
    };
    let normalized = stream_transport(&kind)?;
    if kind.is_empty() {
        return if transport.values().any(active) {
            Err("sing-box raw TCP transport has unsupported settings")
        } else {
            Ok(())
        };
    }
    match normalized {
        "tcp" => {
            reject_active_remainder(
                &transport,
                "sing-box raw TCP transport has unsupported settings",
            )?;
            put(proxy, "network", Value::String(kind));
            Ok(())
        }
        "ws" => normalize_ws_transport(transport, proxy),
        "grpc" => normalize_grpc_transport(transport, proxy),
        _ => unreachable!("stream_transport returned an unknown transport"),
    }
}

fn normalize_ws_transport(mut transport: Mapping, proxy: &mut Mapping) -> Result<(), &'static str> {
    let mut options = Mapping::new();
    move_strings(&mut transport, &mut options, &[("path", "path")])?;
    if let Some(headers) = transport.remove("headers") {
        let Value::Mapping(headers) = headers else {
            return Err("sing-box WebSocket headers must be an object");
        };
        let mut normalized = Mapping::new();
        for (key, value) in headers {
            let Some(key) = key.as_str() else {
                return Err("sing-box WebSocket header names must be strings");
            };
            if key.eq_ignore_ascii_case("host") {
                if !value.is_string() {
                    return Err("sing-box WebSocket Host header must be a string");
                }
                if normalized
                    .insert(Value::String("Host".into()), value)
                    .is_some()
                {
                    return Err("duplicate sing-box WebSocket Host headers");
                }
            } else if !value.is_null() {
                if !value.is_string() {
                    return Err("sing-box WebSocket header values must be strings");
                }
                normalized.insert(Value::String(key.into()), value);
            }
        }
        if !normalized.is_empty() {
            put(&mut options, "headers", Value::Mapping(normalized));
        }
    }
    reject_active_remainder(&transport, "unsupported sing-box WebSocket settings")?;
    put(proxy, "network", Value::String("ws".into()));
    if !options.is_empty() {
        put(proxy, "ws-opts", Value::Mapping(options));
    }
    Ok(())
}

fn normalize_grpc_transport(
    mut transport: Mapping,
    proxy: &mut Mapping,
) -> Result<(), &'static str> {
    let mut options = Mapping::new();
    let service = take_optional_string(&mut transport, "service_name")?.unwrap_or_default();
    put(&mut options, "grpc-service-name", Value::String(service));
    reject_active_remainder(&transport, "unsupported sing-box gRPC settings")?;
    put(proxy, "network", Value::String("grpc".into()));
    put(proxy, "grpc-opts", Value::Mapping(options));
    Ok(())
}

pub(super) fn normalize_tls(source: &mut Mapping, proxy: &mut Mapping) -> Result<(), &'static str> {
    let Some(value) = source.remove("tls") else {
        put(proxy, "tls", Value::Bool(false));
        return Ok(());
    };
    let Value::Mapping(tls) = value else {
        return Err("sing-box TLS settings must be an object");
    };
    let Tls {
        enabled,
        server_name,
        insecure,
        reality,
        alpn,
    } = take_tls(tls)?;
    put(proxy, "tls", Value::Bool(enabled));
    if let Some(server_name) = server_name {
        put(proxy, "servername", Value::String(server_name));
    }
    if insecure {
        put(proxy, "skip-cert-verify", Value::Bool(true));
    }
    if let Some(reality) = reality {
        normalize_reality(reality, proxy)?;
    }
    if let Some(alpn) = alpn.filter(|value| !value.is_null()) {
        validate_alpn(&alpn)?;
        put(proxy, "alpn", alpn);
    }
    Ok(())
}

/// What `normalize_tls` reads from a `tls` object.
struct Tls {
    enabled: bool,
    server_name: Option<String>,
    insecure: bool,
    reality: Option<Value>,
    alpn: Option<Value>,
}

fn take_tls(mut tls: Mapping) -> Result<Tls, &'static str> {
    let enabled = take_bool(&mut tls, "enabled")?.unwrap_or(false);
    let mut server_name = take_optional_string(&mut tls, "server_name")?;
    if optional_text([server_name.as_deref()])?.is_none() {
        server_name = None;
    }
    let insecure = take_bool(&mut tls, "insecure")?.unwrap_or(false);
    let reality = tls.remove("reality");
    let alpn = tls.remove("alpn");
    reject_tls_engine(&mut tls)?;
    if !enabled
        && (server_name.is_some()
            || insecure
            || reality.as_ref().is_some_and(active)
            || alpn.as_ref().is_some_and(active))
    {
        return Err("disabled sing-box TLS has active TLS-only settings");
    }
    reject_active_remainder(&tls, "unsupported sing-box TLS settings")?;
    Ok(Tls {
        enabled,
        server_name,
        insecure,
        reality,
        alpn,
    })
}

fn reject_tls_engine(tls: &mut Mapping) -> Result<(), &'static str> {
    if let Some(engine) = take_optional_string(tls, "engine")?
        && !matches!(engine.as_str(), "" | "go")
    {
        return Err("unsupported sing-box TLS engine");
    }
    // uTLS changes the ClientHello fingerprint, not endpoint authentication or
    // the negotiated proxy protocol. honk selects fingerprints process-wide.
    tls.remove("utls");
    Ok(())
}

fn normalize_reality(value: Value, proxy: &mut Mapping) -> Result<(), &'static str> {
    if matches!(value, Value::Null) {
        return Ok(());
    }
    let Value::Mapping(mut reality) = value else {
        return Err("sing-box REALITY settings must be an object");
    };
    if !take_bool(&mut reality, "enabled")?.unwrap_or(false) {
        return Ok(());
    }
    let Some(public_key) =
        take_optional_string(&mut reality, "public_key")?.filter(|value| !value.trim().is_empty())
    else {
        return Err("sing-box REALITY public key is missing");
    };
    let short_id = take_optional_string(&mut reality, "short_id")?;
    reject_active_remainder(&reality, "unsupported sing-box REALITY settings")?;
    let mut options = Mapping::new();
    put(&mut options, "public-key", Value::String(public_key));
    if let Some(short_id) = short_id {
        put(&mut options, "short-id", Value::String(short_id));
    }
    put(proxy, "reality-opts", Value::Mapping(options));
    Ok(())
}

fn validate_alpn(value: &Value) -> Result<(), &'static str> {
    match value {
        Value::String(_) => Ok(()),
        Value::Sequence(values) if values.iter().all(Value::is_string) => Ok(()),
        _ => Err("sing-box TLS ALPN must be a string or string array"),
    }
}
