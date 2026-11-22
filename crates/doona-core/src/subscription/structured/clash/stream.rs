//! The stream transport, TLS and REALITY settings of a Clash proxy: honk-core's `apply_stream`,
//! `resolve_anytls_network`, `apply_reality` and `apply_tls` (`src/subscription/clash.rs`,
//! daeuniverse/honk 8919784a), with `apply_stream` split into helpers.

use honk_config::node::{Node, OutboundConfig, StreamTransportOptions};
use honk_config::options::vocab::{packet_network, xhttp_stream_transport};
use honk_config::types::NodeProtocol;
use serde_yaml::{Mapping, Value};

use super::fields::{
    self, active as yaml_active, bool_alias as yaml_bool_alias, optional_text_alias,
    text as yaml_text, text_alias as yaml_text_alias,
};
use super::{has_active, supports_quic, supports_stream, xhttp, yaml_value};

fn resolve_anytls_network(
    mapping: &Mapping,
    udp: Option<bool>,
) -> Result<Option<String>, &'static str> {
    let mut selected = None::<(&str, bool)>;
    for key in ["anytls-network", "network"] {
        let Some(value) = yaml_value(mapping, key) else {
            continue;
        };
        if matches!(value, Value::Null) {
            continue;
        }
        let Value::String(value) = value else {
            return Err("AnyTLS network must be a string");
        };
        let Some(network_udp) = packet_network(value)? else {
            continue;
        };
        match selected {
            None => selected = Some((value, network_udp)),
            Some((_, previous)) if previous == network_udp => {}
            Some(_) => return Err("AnyTLS network aliases conflict"),
        }
    }
    if let (Some((_, network_udp)), Some(udp)) = (selected, udp)
        && network_udp != udp
    {
        return Err("AnyTLS network conflicts with udp");
    }
    if let Some((value, _)) = selected {
        return Ok(Some(value.to_owned()));
    }
    Ok(udp.map(|udp| if udp { "tcp,udp" } else { "tcp" }.to_owned()))
}

pub(super) fn apply_stream(
    mapping: &Mapping,
    node: &mut Node,
    udp: Option<bool>,
) -> Result<(), &'static str> {
    if let Some(config) = node.anytls_mut() {
        config.network = resolve_anytls_network(mapping, udp)?;
    } else {
        apply_network(mapping, node, udp)?;
    }
    reject_foreign_transport_options(mapping, node)?;
    if let Some(value) = yaml_value(mapping, "xhttp-opts") {
        let transport = node
            .transport_mut()
            .ok_or("XHTTP options require XHTTP transport")?;
        if !transport.is_xhttp() {
            return Err("XHTTP options require XHTTP transport");
        }
        transport.xhttp = Some(xhttp::options(value)?);
    }
    if let Some(transport) = node.transport_mut() {
        apply_ws(mapping, transport)?;
        apply_grpc(mapping, transport)?;
    }
    Ok(())
}

/// The stream transport `network` names, and the packet network `udp` selects.
fn apply_network(
    mapping: &Mapping,
    node: &mut Node,
    udp: Option<bool>,
) -> Result<(), &'static str> {
    if let Some(network) =
        yaml_text_alias(mapping, &["network"])?.filter(|network| !network.trim().is_empty())
        && let Some(transport) = node.transport_mut()
    {
        transport.transport = xhttp_stream_transport(&network)?.to_string();
    }
    if let Some(udp) = udp {
        let network = if udp { "tcp,udp" } else { "tcp" }.to_string();
        match &mut node.outbound {
            OutboundConfig::Trojan(config) => config.network = Some(network),
            // VMess has no UDP path, so only an explicit restriction is
            // representable; `udp: true` keeps the TCP node as-is.
            OutboundConfig::Vmess(config) if !udp => config.network = Some(network),
            OutboundConfig::Vless(config) => config.network = Some(network),
            _ => {}
        }
    }
    Ok(())
}

/// WebSocket or gRPC settings on a node whose transport is another.
fn reject_foreign_transport_options(mapping: &Mapping, node: &Node) -> Result<(), &'static str> {
    if let Some(transport) = node.transport() {
        if transport.transport != "ws"
            && has_active(mapping, &["ws-opts", "ws-path", "ws-host", "ws-headers"])
        {
            return Err("websocket options require websocket transport");
        }
        if transport.transport != "grpc"
            && has_active(mapping, &["grpc-opts", "grpc-service", "grpc_service"])
        {
            return Err("gRPC options require gRPC transport");
        }
    }
    Ok(())
}

fn apply_ws(mapping: &Mapping, transport: &mut StreamTransportOptions) -> Result<(), &'static str> {
    if let Some(options) = yaml_value(mapping, "ws-opts").filter(|value| yaml_active(value)) {
        let options = options.as_mapping().ok_or("ws-opts must be a mapping")?;
        transport.ws_path = yaml_text_alias(options, &["path"])?;
        if let Some(headers) = yaml_value(options, "headers") {
            apply_ws_headers(headers, transport)?;
        }
    }
    transport.ws_path = transport
        .ws_path
        .take()
        .or(yaml_text_alias(mapping, &["ws-path"])?.filter(|value| !value.trim().is_empty()));
    if transport.ws_host.is_none() {
        transport.ws_host =
            yaml_text_alias(mapping, &["ws-headers"])?.filter(|value| !value.trim().is_empty());
    }
    if transport.ws_host.is_none() {
        transport.ws_host =
            yaml_text_alias(mapping, &["ws-host"])?.filter(|value| !value.trim().is_empty());
    }
    Ok(())
}

fn apply_ws_headers(
    headers: &Value,
    transport: &mut StreamTransportOptions,
) -> Result<(), &'static str> {
    let headers = headers
        .as_mapping()
        .ok_or("ws-opts.headers must be a mapping")?;
    let mut ws_host = None;
    for (key, value) in headers {
        let key = key.as_str().ok_or("websocket header name is invalid")?;
        if key.eq_ignore_ascii_case("host") {
            if ws_host.is_none() {
                ws_host = yaml_text(value)
                    .ok()
                    .flatten()
                    .filter(|value| !value.trim().is_empty());
            }
        } else if let Some(value) = yaml_text(value)? {
            transport.ws_headers.insert(key.to_owned(), value);
        }
    }
    transport.ws_host = ws_host;
    Ok(())
}

fn apply_grpc(
    mapping: &Mapping,
    transport: &mut StreamTransportOptions,
) -> Result<(), &'static str> {
    if let Some(options) = yaml_value(mapping, "grpc-opts").filter(|value| yaml_active(value)) {
        let options = options.as_mapping().ok_or("grpc-opts must be a mapping")?;
        transport.grpc_service =
            yaml_text_alias(options, &["grpc-service-name", "grpc_service_name"])?;
    }
    transport.grpc_service = transport
        .grpc_service
        .take()
        .or(yaml_text_alias(mapping, &["grpc-service", "grpc_service"])?);
    Ok(())
}

fn apply_reality(
    mapping: &Mapping,
    node: &mut Node,
    protocol: NodeProtocol,
    tls_explicit: Option<bool>,
) -> Result<(), &'static str> {
    let Some(value) = yaml_value(mapping, "reality-opts") else {
        return Ok(());
    };
    if !yaml_active(value) {
        return Ok(());
    }
    if !supports_stream(protocol) {
        return Err("REALITY is unsupported for this protocol");
    }
    if tls_explicit == Some(false) {
        return Err("REALITY conflicts with tls=false");
    }
    let reality = value.as_mapping().ok_or("reality-opts must be a mapping")?;
    let public_key = yaml_text_alias(reality, &["public-key", "public_key"])?
        .filter(|value| !value.trim().is_empty())
        .ok_or("REALITY public key is missing")?;
    let short_id = yaml_text_alias(reality, &["short-id", "short_id"])?;
    let spider_x = yaml_text_alias(reality, &["spider-x", "spider_x"])?
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "/".to_string());
    let tls = node.tls_mut().ok_or("REALITY requires TLS")?;
    tls.enabled = true;
    tls.reality_public_key = Some(public_key);
    tls.reality_short_id = short_id;
    tls.reality_spider_x = Some(spider_x);
    Ok(())
}

pub(super) fn apply_tls(
    mapping: &Mapping,
    node: &mut Node,
    protocol: NodeProtocol,
    tls_explicit: Option<bool>,
    tls_enabled: bool,
) -> Result<(), &'static str> {
    if yaml_bool_alias(mapping, &["disable-sni", "disable_sni"])? == Some(true) {
        return Err("disable-sni is unsupported");
    }
    if let Some(tls) = node.tls_mut() {
        tls.enabled = tls_enabled;
        tls.sni = optional_text_alias(mapping, &["servername", "server-name", "sni"])?;
        tls.skip_cert_verify = yaml_bool_alias(
            mapping,
            &["skip-cert-verify", "skip_cert_verify", "insecure"],
        )?
        .unwrap_or(false);
        tls.pin_sha256 = yaml_text_alias(mapping, &["pin-sha256", "pin_sha256", "fingerprint"])?;
        if !supports_quic(protocol)
            && let Some(alpn) = yaml_value(mapping, "alpn")
        {
            tls.alpn = fields::alpn_list(alpn)?;
        }
    }
    apply_reality(mapping, node, protocol, tls_explicit)
}
