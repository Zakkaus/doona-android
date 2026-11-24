//! Normalize sing-box outbounds into the shared Clash-shaped vocabulary.
//!
//! honk-core's `src/subscription/json/sing_box.rs` (daeuniverse/honk 8919784a). doona's limits
//! move the transport and TLS settings to [`tls`] and the Hysteria2 and QUIC ones to [`quic`],
//! and split `normalize`, `normalize_vmess`, `normalize_vless` and `normalize_tls` into helpers
//! that make the same checks in the same order. A non-proxy outbound (`selector`, `urltest`,
//! `direct`, `block`, `dns`) and an unsupported type are both [`NormalizedEntry::Skipped`].

mod quic;
mod tls;

use honk_config::options::vocab::packet_network;
use honk_config::types::NodeProtocol;
use serde_yaml::{Mapping, Value};

use self::quic::{normalize_hysteria2_obfs, normalize_quic_fields, normalize_server_ports};
use self::tls::{normalize_tls, normalize_transport};
use super::{
    NodeResult, NormalizedEntry, move_credential_strings, move_strings, put, take_optional_string,
};

pub(super) fn normalize(value: Value) -> NodeResult {
    let Value::Mapping(mut source) = value else {
        return Err("sing-box outbound must be an object");
    };
    let Some(Value::String(kind)) = source.remove("type") else {
        return Err("sing-box outbound type is missing or invalid");
    };
    let Some(protocol) = protocol(&kind) else {
        return Ok(NormalizedEntry::Skipped);
    };
    if protocol == NodeProtocol::VLess && source.contains_key("vless_mode") {
        return Err("VLESS vless_mode was removed");
    }
    if source.remove("detour").is_some_and(|value| active(&value)) {
        return Err("sing-box detour chaining is unsupported");
    }

    let mut proxy = Mapping::new();
    put(&mut proxy, "type", Value::String(protocol.as_str().into()));
    move_strings(
        &mut source,
        &mut proxy,
        &[("tag", "name"), ("server", "server")],
    )?;
    normalize_port(&mut source, &mut proxy, protocol)?;
    normalize_protocol(protocol, source, proxy).map(NormalizedEntry::Node)
}

/// The protocol of a proxy outbound type; `None` for a profile outbound (`selector`, `urltest`,
/// `direct`, `block`, `dns`) or an unsupported type.
fn protocol(kind: &str) -> Option<NodeProtocol> {
    Some(match kind {
        "shadowsocks" | "ss" => NodeProtocol::SS,
        "socks" | "socks5" => NodeProtocol::Socks5,
        "vmess" => NodeProtocol::VMess,
        "vless" => NodeProtocol::VLess,
        "trojan" => NodeProtocol::Trojan,
        "hysteria2" | "hy2" => NodeProtocol::Hysteria2,
        "tuic" => NodeProtocol::Tuic,
        "juicity" => NodeProtocol::Juicity,
        "anytls" => NodeProtocol::AnyTLS,
        _ => return None,
    })
}

fn normalize_port(
    source: &mut Mapping,
    proxy: &mut Mapping,
    protocol: NodeProtocol,
) -> Result<(), &'static str> {
    let hopping_port = if protocol == NodeProtocol::Hysteria2 {
        normalize_server_ports(source, proxy)?
    } else {
        None
    };
    match (source.remove("server_port"), hopping_port) {
        (Some(port), _) if matches!(port.as_u64(), Some(1..=65535)) => {
            put(proxy, "port", port);
        }
        (Some(Value::Null) | None, Some(port)) => {
            put(proxy, "port", Value::Number(port.into()));
        }
        (Some(Value::Null) | None, None) => {}
        _ => return Err("sing-box outbound port must be an integer"),
    }
    Ok(())
}

fn normalize_protocol(
    protocol: NodeProtocol,
    source: Mapping,
    proxy: Mapping,
) -> Result<Mapping, &'static str> {
    match protocol {
        NodeProtocol::SS => normalize_shadowsocks(source, proxy),
        NodeProtocol::Socks5 => normalize_socks5(source, proxy),
        NodeProtocol::VMess => normalize_vmess(source, proxy),
        NodeProtocol::VLess => normalize_vless(source, proxy),
        NodeProtocol::Trojan => normalize_trojan(source, proxy),
        NodeProtocol::Hysteria2 => normalize_hysteria2(source, proxy),
        NodeProtocol::Tuic => normalize_tuic(source, proxy),
        NodeProtocol::Juicity => normalize_juicity(source, proxy),
        NodeProtocol::AnyTLS => normalize_anytls(source, proxy),
        NodeProtocol::Direct | NodeProtocol::Block | NodeProtocol::Chain => unreachable!(),
    }
}

fn normalize_shadowsocks(mut source: Mapping, mut proxy: Mapping) -> Result<Mapping, &'static str> {
    move_strings(
        &mut source,
        &mut proxy,
        &[
            ("method", "cipher"),
            ("plugin", "plugin"),
            ("plugin_opts", "plugin-opts"),
        ],
    )?;
    move_credential_strings(&mut source, &mut proxy, &[("password", "password")])?;
    reject_packet_network(&mut source)?;
    reject_enabled(
        &mut source,
        "udp_over_tcp",
        "sing-box Shadowsocks UoT is unsupported",
    )?;
    reject_enabled(
        &mut source,
        "multiplex",
        "sing-box Shadowsocks multiplex is unsupported",
    )?;
    Ok(proxy)
}

fn normalize_socks5(mut source: Mapping, mut proxy: Mapping) -> Result<Mapping, &'static str> {
    if let Some(version) = source.remove("version") {
        let is_v5 =
            matches!(&version, Value::String(value) if value == "5") || version.as_u64() == Some(5);
        if !is_v5 {
            return Err("only SOCKS5 sing-box outbounds are supported");
        }
    }
    move_credential_strings(
        &mut source,
        &mut proxy,
        &[("username", "username"), ("password", "password")],
    )?;
    reject_packet_network(&mut source)?;
    reject_enabled(
        &mut source,
        "udp_over_tcp",
        "sing-box SOCKS UoT is unsupported",
    )?;
    Ok(proxy)
}

fn normalize_vmess(mut source: Mapping, mut proxy: Mapping) -> Result<Mapping, &'static str> {
    move_credential_strings(&mut source, &mut proxy, &[("uuid", "uuid")])?;
    if let Some(value) = take_optional_string(&mut source, "security")?
        && let Some(cipher) = honk_config::options::vocab::vmess_cipher([value.as_str()])?
    {
        put(&mut proxy, "cipher", Value::String(cipher.into()));
    }
    reject_legacy_vmess(&mut source)?;
    reject_active_key(
        &mut source,
        "packet_encoding",
        "sing-box VMess packet encoding is unsupported",
    )?;
    normalize_packet_network(&mut source, &mut proxy)?;
    reject_enabled(
        &mut source,
        "multiplex",
        "sing-box VMess multiplex is unsupported",
    )?;
    reject_enabled(
        &mut source,
        "udp_over_tcp",
        "sing-box VMess UoT is unsupported",
    )?;
    normalize_transport(&mut source, &mut proxy)?;
    normalize_tls(&mut source, &mut proxy)?;
    Ok(proxy)
}

fn reject_legacy_vmess(source: &mut Mapping) -> Result<(), &'static str> {
    if source
        .remove("alter_id")
        .is_some_and(|value| !matches!(value, Value::Null) && value.as_u64() != Some(0))
    {
        return Err("legacy sing-box VMess alter IDs are unsupported");
    }
    for (key, error) in [
        (
            "global_padding",
            "sing-box VMess global padding is unsupported",
        ),
        (
            "authenticated_length",
            "sing-box VMess authenticated length is unsupported",
        ),
    ] {
        if source.remove(key).is_some_and(|value| active(&value)) {
            return Err(error);
        }
    }
    Ok(())
}

fn normalize_vless(mut source: Mapping, mut proxy: Mapping) -> Result<Mapping, &'static str> {
    move_credential_strings(&mut source, &mut proxy, &[("uuid", "uuid")])?;
    move_strings(&mut source, &mut proxy, &[("flow", "flow")])?;
    normalize_packet_network(&mut source, &mut proxy)?;
    let packet_encoding = source.remove("packet_encoding");
    let multiplex = normalize_vless_multiplex(&mut source, &mut proxy)?;
    let uot = normalize_vless_uot(&mut source, &mut proxy)?;
    let packet_encoding = vless_packet_encoding(packet_encoding, multiplex || uot)?;
    if let Some(packet_encoding) = packet_encoding {
        put(
            &mut proxy,
            "packet-encoding",
            Value::String(packet_encoding.into()),
        );
    }
    normalize_transport(&mut source, &mut proxy)?;
    normalize_tls(&mut source, &mut proxy)?;
    Ok(proxy)
}

/// The Clash `packet-encoding` of a sing-box VLESS outbound; none when a multiplex or UDP over TCP
/// wrapper carries the packets.
fn vless_packet_encoding(
    packet_encoding: Option<Value>,
    wrapped: bool,
) -> Result<Option<&'static str>, &'static str> {
    Ok(match packet_encoding {
        None | Some(Value::Null) if !wrapped => Some("xudp"),
        None | Some(Value::Null) => None,
        Some(Value::String(value)) => {
            let encoding = match value.as_str() {
                "" => "none",
                "xudp" => "xudp",
                _ => return Err("unsupported sing-box VLESS packet encoding"),
            };
            (!wrapped).then_some(encoding)
        }
        Some(_) => return Err("sing-box VLESS packet encoding must be a string"),
    })
}

fn normalize_trojan(mut source: Mapping, mut proxy: Mapping) -> Result<Mapping, &'static str> {
    move_credential_strings(&mut source, &mut proxy, &[("password", "password")])?;
    normalize_packet_network(&mut source, &mut proxy)?;
    reject_enabled(
        &mut source,
        "multiplex",
        "sing-box Trojan multiplex is unsupported",
    )?;
    reject_enabled(
        &mut source,
        "udp_over_tcp",
        "sing-box Trojan UoT is unsupported",
    )?;
    normalize_transport(&mut source, &mut proxy)?;
    normalize_tls(&mut source, &mut proxy)?;
    Ok(proxy)
}

fn normalize_hysteria2(mut source: Mapping, mut proxy: Mapping) -> Result<Mapping, &'static str> {
    move_credential_strings(&mut source, &mut proxy, &[("password", "password")])?;
    reject_packet_network(&mut source)?;
    if source.remove("realm").is_some_and(|value| active(&value)) {
        return Err("sing-box Hysteria2 realm routing is unsupported");
    }
    if source
        .remove("hop_interval_max")
        .is_some_and(|value| active(&value))
    {
        return Err("sing-box randomized Hysteria2 hop intervals are unsupported");
    }
    for (source_key, target_key) in [("up_mbps", "up"), ("down_mbps", "down")] {
        if let Some(value) = source.remove(source_key).filter(active) {
            put(&mut proxy, target_key, value);
        }
    }
    if let Some(value) = source.remove("hop_interval") {
        put(&mut proxy, "hop-interval", value);
    }
    normalize_hysteria2_obfs(&mut source, &mut proxy)?;
    normalize_quic_fields(&mut source, &mut proxy)?;
    normalize_tls(&mut source, &mut proxy)?;
    Ok(proxy)
}

fn normalize_tuic(mut source: Mapping, mut proxy: Mapping) -> Result<Mapping, &'static str> {
    if source.remove("token").is_some_and(|value| active(&value)) {
        return Err("legacy TUIC tokens are unsupported");
    }
    move_credential_strings(
        &mut source,
        &mut proxy,
        &[("uuid", "uuid"), ("password", "password")],
    )?;
    if let Some(mode) = source.remove("udp_relay_mode")
        && !matches!(&mode, Value::String(value) if value.is_empty() || value == "native")
    {
        return Err("unsupported sing-box TUIC UDP relay mode");
    }
    if source
        .remove("udp_over_stream")
        .is_some_and(|value| active(&value))
    {
        return Err("forced sing-box TUIC UDP-over-stream is unsupported");
    }
    reject_packet_network(&mut source)?;
    if let Some(congestion) = take_optional_string(&mut source, "congestion_control")? {
        if !matches!(congestion.as_str(), "" | "cubic" | "new_reno" | "bbr") {
            return Err("unsupported sing-box TUIC congestion control");
        }
        if !congestion.trim().is_empty() {
            put(&mut proxy, "congestion-control", Value::String(congestion));
        }
    }
    normalize_quic_fields(&mut source, &mut proxy)?;
    normalize_tls(&mut source, &mut proxy)?;
    Ok(proxy)
}

fn normalize_juicity(mut source: Mapping, mut proxy: Mapping) -> Result<Mapping, &'static str> {
    move_credential_strings(
        &mut source,
        &mut proxy,
        &[("uuid", "uuid"), ("password", "password")],
    )?;
    reject_packet_network(&mut source)?;
    normalize_quic_fields(&mut source, &mut proxy)?;
    normalize_tls(&mut source, &mut proxy)?;
    Ok(proxy)
}

fn normalize_anytls(mut source: Mapping, mut proxy: Mapping) -> Result<Mapping, &'static str> {
    move_credential_strings(&mut source, &mut proxy, &[("password", "password")])?;
    if let Some(network) = source.remove("network") {
        match network {
            Value::Null => {}
            Value::String(network) => {
                if packet_network(&network)?.is_some() {
                    put(&mut proxy, "anytls-network", Value::String(network));
                }
            }
            _ => return Err("sing-box packet network must be a string"),
        }
    }
    if let Some(value) = source.remove("min_idle_session").filter(active) {
        put(&mut proxy, "min-idle-session", value);
    }
    for (source_key, target_key) in [
        ("idle_session_check_interval", "idle-session-check-interval"),
        ("idle_session_timeout", "idle-session-timeout"),
    ] {
        if let Some(value) = source.remove(source_key) {
            put(&mut proxy, target_key, value);
        }
    }
    if source
        .remove("client_metadata")
        .is_some_and(|value| active(&value))
    {
        return Err("sing-box AnyTLS client metadata is unsupported");
    }
    reject_enabled(
        &mut source,
        "multiplex",
        "sing-box AnyTLS multiplex settings are unsupported",
    )?;
    normalize_tls(&mut source, &mut proxy)?;
    Ok(proxy)
}

fn normalize_packet_network(source: &mut Mapping, proxy: &mut Mapping) -> Result<(), &'static str> {
    let network = match source.remove("network") {
        None | Some(Value::Null) => return Ok(()),
        Some(network) => network,
    };
    let Value::String(network) = network else {
        return Err("sing-box packet network must be a string");
    };
    if let Some(enabled) = packet_network(&network)? {
        put(proxy, "udp", Value::Bool(enabled));
    }
    Ok(())
}

fn reject_packet_network(source: &mut Mapping) -> Result<(), &'static str> {
    let Some(network) = source.remove("network") else {
        return Ok(());
    };
    if matches!(&network, Value::String(value) if value.is_empty()) {
        return Ok(());
    }
    Err("sing-box packet network restriction is unsupported for this protocol")
}

fn normalize_vless_multiplex(
    source: &mut Mapping,
    proxy: &mut Mapping,
) -> Result<bool, &'static str> {
    let Some(value) = source.remove("multiplex") else {
        return Ok(false);
    };
    if matches!(value, Value::Null) {
        return Ok(false);
    }
    let Value::Mapping(mut multiplex) = value else {
        return Err("sing-box VLESS multiplex settings must be an object");
    };
    if !take_bool(&mut multiplex, "enabled")?.unwrap_or(false) {
        return Ok(false);
    }
    match multiplex.remove("protocol") {
        None => put(&mut multiplex, "protocol", Value::String("h2mux".into())),
        Some(Value::String(protocol)) if protocol.is_empty() || protocol == "h2mux" => {
            put(&mut multiplex, "protocol", Value::String("h2mux".into()))
        }
        Some(Value::String(_)) => return Err("unsupported sing-box VLESS multiplex protocol"),
        Some(_) => return Err("sing-box VLESS multiplex protocol must be a string"),
    }
    reject_unknown_active(
        &multiplex,
        &[
            "protocol",
            "padding",
            "brutal",
            "max_connections",
            "min_streams",
            "max_streams",
        ],
        "unsupported sing-box VLESS multiplex setting",
    )?;
    put(&mut multiplex, "enabled", Value::Bool(true));
    put(proxy, "multiplex", Value::Mapping(multiplex));
    Ok(true)
}

fn normalize_vless_uot(source: &mut Mapping, proxy: &mut Mapping) -> Result<bool, &'static str> {
    let Some(value) = source.remove("udp_over_tcp") else {
        return Ok(false);
    };
    let mut options = match value {
        Value::Bool(false) | Value::Null => return Ok(false),
        Value::Bool(true) => Mapping::new(),
        Value::Mapping(mut options) => {
            if !take_bool(&mut options, "enabled")?.unwrap_or(false) {
                return Ok(false);
            }
            options
        }
        _ => return Err("sing-box VLESS UoT settings must be boolean or an object"),
    };
    match options.remove("version") {
        None => put(&mut options, "version", Value::Number(2.into())),
        Some(version) if version.as_u64() == Some(2) => put(&mut options, "version", version),
        Some(_) => return Err("only sing-box UoT version 2 is supported"),
    }
    reject_unknown_active(
        &options,
        &["version"],
        "unsupported sing-box VLESS UoT setting",
    )?;
    put(&mut options, "enabled", Value::Bool(true));
    put(proxy, "udp-over-tcp", Value::Mapping(options));
    Ok(true)
}
fn reject_enabled(
    source: &mut Mapping,
    key: &str,
    error: &'static str,
) -> Result<(), &'static str> {
    let Some(value) = source.remove(key) else {
        return Ok(());
    };
    let enabled = match value {
        Value::Null | Value::Bool(false) => false,
        Value::Bool(true) => true,
        Value::Mapping(mut options) => take_bool(&mut options, "enabled")?.unwrap_or(false),
        _ => return Err("sing-box feature settings must be boolean or an object"),
    };
    if enabled { Err(error) } else { Ok(()) }
}

fn reject_active_key(
    mapping: &mut Mapping,
    key: &str,
    error: &'static str,
) -> Result<(), &'static str> {
    if mapping.remove(key).is_some_and(|value| active(&value)) {
        Err(error)
    } else {
        Ok(())
    }
}

fn reject_active_remainder(mapping: &Mapping, error: &'static str) -> Result<(), &'static str> {
    if mapping.values().any(active) {
        Err(error)
    } else {
        Ok(())
    }
}

fn reject_unknown_active(
    mapping: &Mapping,
    allowed: &[&str],
    error: &'static str,
) -> Result<(), &'static str> {
    if mapping
        .iter()
        .any(|(key, value)| active(value) && key.as_str().is_none_or(|key| !allowed.contains(&key)))
    {
        Err(error)
    } else {
        Ok(())
    }
}

fn active(value: &Value) -> bool {
    match value {
        Value::Null => false,
        Value::Bool(value) => *value,
        Value::Number(value) => value.as_f64() != Some(0.0),
        Value::String(value) => !value.trim().is_empty(),
        Value::Sequence(value) => !value.is_empty(),
        Value::Mapping(value) => value.values().any(active),
        Value::Tagged(value) => active(&value.value),
    }
}

fn take_bool(mapping: &mut Mapping, key: &str) -> Result<Option<bool>, &'static str> {
    match mapping.remove(key) {
        Some(Value::Bool(value)) => Ok(Some(value)),
        Some(Value::Null) => Ok(None),
        Some(_) => Err("sing-box boolean setting has an invalid type"),
        None => Ok(None),
    }
}

fn take_alias(
    source: &mut Mapping,
    keys: &[&str],
    conflict: &'static str,
) -> Result<Option<Value>, &'static str> {
    let mut value = None;
    for key in keys {
        if let Some(found) = source.remove(*key) {
            if value.is_some() {
                return Err(conflict);
            }
            value = Some(found);
        }
    }
    Ok(value)
}
