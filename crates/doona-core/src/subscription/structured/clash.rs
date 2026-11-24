//! Build typed nodes from the shared Clash-shaped import vocabulary.
//!
//! honk-core's `src/subscription/clash.rs` and `clash/` (daeuniverse/honk 8919784a). doona's
//! function limits split `validate_source` and `apply_protocol` into helpers here, and move
//! `apply_stream`, `apply_tls` and `apply_reality` to [`stream`] and `apply_quic` to [`quic`],
//! where they are split too; every check and its order are upstream's. `parse_clash_proxy` takes
//! no subscription id: doona leaves it unset, as `Node::from_share_link` does.

mod fields;
mod options;
mod quic;
mod stream;
mod xhttp;

use honk_config::node::{AnyTlsConfig, Hysteria2Config, Node, OutboundConfig, VlessConfig};
use honk_config::options::vocab::optional_flow;
use honk_config::types::NodeProtocol;
use serde_yaml::Mapping;

use self::fields::{
    active as yaml_active, active_for_key as yaml_active_for_key, bool_alias as yaml_bool_alias,
    duration_alias as yaml_duration_alias, optional_text_alias, text_alias as yaml_text_alias,
    u64_alias as yaml_u64_alias,
};
use super::yaml_value;

const STREAM_WINDOW_KEYS: &[&str] = &[
    "initial-stream-receive-window",
    "initial_stream_receive_window",
    "init-stream-receive-window",
    "init_stream_receive_window",
    "initStreamReceiveWindow",
];
const CONN_WINDOW_KEYS: &[&str] = &[
    "initial-conn-receive-window",
    "initial_conn_receive_window",
    "init-conn-receive-window",
    "init_conn_receive_window",
    "initConnReceiveWindow",
];
const QUIC_MTU_KEYS: &[&str] = &["mtu", "quic-mtu", "quic_mtu"];
const MTU_DISCOVERY_KEYS: &[&str] = &[
    "disable-mtu-discovery",
    "disable-path-mtu-discovery",
    "disablePathMTUDiscovery",
];
const TLS_KEYS: &[&str] = &[
    "tls",
    "servername",
    "server-name",
    "sni",
    "alpn",
    "skip-cert-verify",
    "skip_cert_verify",
    "insecure",
    "pin-sha256",
    "pin_sha256",
    "fingerprint",
    "reality-opts",
];
const STREAM_KEYS: &[&str] = &[
    "ws-opts",
    "ws-path",
    "ws-host",
    "ws-headers",
    "grpc-opts",
    "grpc-service",
];
const PACKET_WRAPPER_KEYS: &[&str] = &[
    "packet-encoding",
    "packet_encoding",
    "packet-addr",
    "packet_addr",
    "xudp",
    "mux",
    "smux",
    "multiplex",
    "udp-over-tcp",
    "udp_over_tcp",
];
const HYSTERIA2_KEYS: &[&str] = &[
    "obfs",
    "obfs-password",
    "obfs_password",
    "ports",
    "mport",
    "port-hopping",
    "port_hopping",
    "up",
    "down",
    "upload-bandwidth",
    "download-bandwidth",
    "up-speed",
    "down-speed",
    "up_mbps",
    "down_mbps",
    "hop-interval",
    "hop_interval",
    "mhop",
];

struct ProxySource {
    protocol: NodeProtocol,
    name: String,
    server: String,
    port: u16,
    udp: Option<bool>,
    tls_explicit: Option<bool>,
    tls_enabled: bool,
}

fn supports_tls(protocol: NodeProtocol) -> bool {
    matches!(
        protocol,
        NodeProtocol::Trojan
            | NodeProtocol::VMess
            | NodeProtocol::VLess
            | NodeProtocol::Hysteria2
            | NodeProtocol::Tuic
            | NodeProtocol::Juicity
            | NodeProtocol::AnyTLS
    )
}

fn supports_stream(protocol: NodeProtocol) -> bool {
    matches!(
        protocol,
        NodeProtocol::Trojan | NodeProtocol::VMess | NodeProtocol::VLess
    )
}

fn supports_quic(protocol: NodeProtocol) -> bool {
    matches!(
        protocol,
        NodeProtocol::Hysteria2 | NodeProtocol::Tuic | NodeProtocol::Juicity
    )
}

fn has_active(mapping: &Mapping, keys: &[&str]) -> bool {
    keys.iter()
        .any(|key| yaml_value(mapping, key).is_some_and(yaml_active))
}

fn has_active_for_key(mapping: &Mapping, keys: &[&str]) -> bool {
    keys.iter()
        .any(|key| yaml_value(mapping, key).is_some_and(|value| yaml_active_for_key(key, value)))
}

fn reject_active_unless(
    mapping: &Mapping,
    keys: &[&str],
    supported: bool,
    error: &'static str,
) -> Result<(), &'static str> {
    if !supported && has_active(mapping, keys) {
        Err(error)
    } else {
        Ok(())
    }
}

fn protocol_from_name(proxy_type: &str) -> Result<NodeProtocol, &'static str> {
    if proxy_type.eq_ignore_ascii_case("socks5") {
        Ok(NodeProtocol::Socks5)
    } else if proxy_type.eq_ignore_ascii_case("ss")
        || proxy_type.eq_ignore_ascii_case("shadowsocks")
    {
        Ok(NodeProtocol::SS)
    } else if proxy_type.eq_ignore_ascii_case("trojan") {
        Ok(NodeProtocol::Trojan)
    } else if proxy_type.eq_ignore_ascii_case("vmess") {
        Ok(NodeProtocol::VMess)
    } else if proxy_type.eq_ignore_ascii_case("vless") {
        Ok(NodeProtocol::VLess)
    } else if proxy_type.eq_ignore_ascii_case("hysteria2")
        || proxy_type.eq_ignore_ascii_case("hysteria")
    {
        Ok(NodeProtocol::Hysteria2)
    } else if proxy_type.eq_ignore_ascii_case("tuic") {
        Ok(NodeProtocol::Tuic)
    } else if proxy_type.eq_ignore_ascii_case("juicity") {
        Ok(NodeProtocol::Juicity)
    } else if proxy_type.eq_ignore_ascii_case("anytls") {
        Ok(NodeProtocol::AnyTLS)
    } else {
        Err("unsupported proxy type")
    }
}

fn validate_source(mapping: &Mapping) -> Result<ProxySource, &'static str> {
    if has_active(
        mapping,
        &[
            "dialer-proxy",
            "underlying-proxy",
            "dialer_proxy",
            "underlying_proxy",
        ],
    ) {
        return Err("chaining is unsupported");
    }
    let proxy_type = yaml_text_alias(mapping, &["type"])?.ok_or("proxy type is missing")?;
    let protocol = protocol_from_name(&proxy_type)?;
    if protocol == NodeProtocol::VLess && yaml_value(mapping, "vless_mode").is_some() {
        return Err("VLESS vless_mode was removed");
    }
    if yaml_value(mapping, "xhttp-opts").is_some() && !supports_stream(protocol) {
        return Err("XHTTP options require a stream protocol");
    }
    reject_foreign_options(mapping, protocol)?;
    let udp = udp_setting(mapping, protocol)?;
    reject_foreign_quic_options(mapping, protocol)?;
    let (server, port, name) = endpoint(mapping, &proxy_type)?;
    let (tls_explicit, tls_enabled) = tls_setting(mapping, protocol)?;
    Ok(ProxySource {
        protocol,
        name,
        server,
        port,
        udp,
        tls_explicit,
        tls_enabled,
    })
}

/// `validate_source`'s refusal of plugins, and of TLS, stream, network, flow, cipher and VLESS
/// packet wrapper settings on a protocol without them.
fn reject_foreign_options(mapping: &Mapping, protocol: NodeProtocol) -> Result<(), &'static str> {
    reject_active_unless(
        mapping,
        &["plugin", "plugin-opts", "plugin_opts"],
        false,
        "proxy plugins are unsupported",
    )?;
    if !supports_tls(protocol) && has_active_for_key(mapping, TLS_KEYS) {
        return Err("TLS settings are unsupported for this protocol");
    }
    reject_active_unless(
        mapping,
        STREAM_KEYS,
        supports_stream(protocol),
        "stream transport settings are unsupported for this protocol",
    )?;
    reject_active_unless(
        mapping,
        &["network"],
        supports_stream(protocol) || protocol == NodeProtocol::AnyTLS,
        "network settings are unsupported for this protocol",
    )?;
    reject_active_unless(
        mapping,
        &["flow"],
        supports_stream(protocol),
        "VLESS flow is unsupported for this protocol",
    )?;
    reject_active_unless(
        mapping,
        &["cipher", "encryption"],
        matches!(
            protocol,
            NodeProtocol::SS | NodeProtocol::VMess | NodeProtocol::VLess
        ),
        "encryption settings are unsupported for this protocol",
    )?;
    if protocol != NodeProtocol::VLess && has_active_for_key(mapping, PACKET_WRAPPER_KEYS) {
        return Err("VLESS packet wrappers are unsupported for this protocol");
    }
    Ok(())
}

/// `udp`, refused as `false` on a protocol that cannot restrict UDP.
fn udp_setting(mapping: &Mapping, protocol: NodeProtocol) -> Result<Option<bool>, &'static str> {
    let udp = yaml_bool_alias(mapping, &["udp"])?;
    // VMess has no UDP path: `udp: true` there is a capability claim honk
    // cannot honor, not a restriction, so the node still imports for TCP.
    let udp_restrictable = matches!(
        protocol,
        NodeProtocol::Trojan | NodeProtocol::VMess | NodeProtocol::VLess | NodeProtocol::AnyTLS
    );
    if udp == Some(false) && !udp_restrictable {
        return Err("UDP restriction is unsupported for this protocol");
    }
    Ok(udp)
}

/// `validate_source`'s refusal of Hysteria2 and QUIC settings on a protocol without them.
fn reject_foreign_quic_options(
    mapping: &Mapping,
    protocol: NodeProtocol,
) -> Result<(), &'static str> {
    reject_active_unless(
        mapping,
        HYSTERIA2_KEYS,
        protocol == NodeProtocol::Hysteria2,
        "Hysteria2 options are unsupported for this protocol",
    )?;
    reject_active_unless(
        mapping,
        MTU_DISCOVERY_KEYS,
        protocol == NodeProtocol::Hysteria2,
        "Hysteria2 MTU discovery options are unsupported",
    )?;
    if !supports_quic(protocol)
        && (has_active(mapping, QUIC_MTU_KEYS)
            || has_active(mapping, STREAM_WINDOW_KEYS)
            || has_active(mapping, CONN_WINDOW_KEYS))
    {
        return Err("QUIC options are unsupported for this protocol");
    }
    Ok(())
}

/// The server, the port and the name, which defaults to the type and the address.
fn endpoint(mapping: &Mapping, proxy_type: &str) -> Result<(String, u16, String), &'static str> {
    let server = yaml_text_alias(mapping, &["server"])?
        .filter(|value| !value.trim().is_empty())
        .map(bracket_ipv6)
        .ok_or("proxy server is missing")?;
    let port = yaml_u64_alias(mapping, &["port"])?
        .and_then(|value| u16::try_from(value).ok())
        .ok_or("proxy port is invalid")?;
    let name = yaml_text_alias(mapping, &["name"])?
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| format!("{proxy_type}-{server}:{port}"));
    Ok((server, port, name))
}

/// An IPv6 literal as a share link has it: in brackets and written as the URL parser writes it,
/// so a node keeps its id whether its subscription comes as links or as a document. honk's share
/// links take the URL's host (`[2001:db8::1]`), its Clash builder the `server` as written. doona's
/// change.
fn bracket_ipv6(server: String) -> String {
    let bare = server
        .strip_prefix('[')
        .and_then(|inner| inner.strip_suffix(']'))
        .unwrap_or(&server);
    match bare.parse() {
        Ok(address) => url::Host::<String>::Ipv6(address).to_string(),
        Err(_) => server,
    }
}

/// `tls` as given, and whether TLS is on: the protocols that require it default to it.
fn tls_setting(
    mapping: &Mapping,
    protocol: NodeProtocol,
) -> Result<(Option<bool>, bool), &'static str> {
    let tls_explicit = yaml_bool_alias(mapping, &["tls"])?;
    let mandatory_tls = matches!(
        protocol,
        NodeProtocol::Trojan
            | NodeProtocol::Hysteria2
            | NodeProtocol::Tuic
            | NodeProtocol::Juicity
            | NodeProtocol::AnyTLS
    );
    let tls_enabled = tls_explicit.unwrap_or(mandatory_tls);
    if mandatory_tls && !tls_enabled {
        return Err("this imported protocol requires TLS");
    }
    Ok((tls_explicit, tls_enabled))
}

fn apply_protocol(mapping: &Mapping, node: &mut Node) -> Result<(), &'static str> {
    let username = yaml_text_alias(mapping, &["username"])?;
    let password = yaml_text_alias(mapping, &["password"])?;
    let cipher = yaml_text_alias(mapping, &["cipher"])?;

    match &mut node.outbound {
        OutboundConfig::Vmess(config) => {
            config.uuid = yaml_text_alias(mapping, &["uuid"])?.or(password);
            config.encryption = fields::vmess_cipher_alias(mapping, &["encryption", "cipher"])?;
        }
        OutboundConfig::Vless(config) => apply_vless(mapping, config, password, cipher)?,
        OutboundConfig::Hysteria2(config) => apply_hysteria2(mapping, config, password)?,
        OutboundConfig::AnyTls(config) => apply_anytls(mapping, config, password)?,
        outbound => apply_credentials(mapping, outbound, username, password, cipher)?,
    }
    Ok(())
}

/// `apply_protocol` for the protocols whose settings are only credentials.
fn apply_credentials(
    mapping: &Mapping,
    outbound: &mut OutboundConfig,
    username: Option<String>,
    password: Option<String>,
    cipher: Option<String>,
) -> Result<(), &'static str> {
    match outbound {
        OutboundConfig::Shadowsocks(config) => {
            config.password = password;
            config.encryption = cipher;
        }
        OutboundConfig::Socks5(config) => {
            config.username = username;
            config.password = password;
        }
        OutboundConfig::Trojan(config) => config.password = password,
        OutboundConfig::Tuic(config) => {
            config.uuid = yaml_text_alias(mapping, &["uuid"])?.or(username);
            config.password = password;
        }
        OutboundConfig::Juicity(config) => {
            config.uuid = yaml_text_alias(mapping, &["uuid"])?.or(username);
            config.password = password;
        }
        _ => unreachable!(),
    }
    Ok(())
}

fn apply_vless(
    mapping: &Mapping,
    config: &mut VlessConfig,
    password: Option<String>,
    cipher: Option<String>,
) -> Result<(), &'static str> {
    config.uuid = yaml_text_alias(mapping, &["uuid"])?.or(password);
    config.encryption = yaml_text_alias(mapping, &["encryption"])?.or(cipher);
    let flow = optional_text_alias(mapping, &["flow"])?;
    config.flow = optional_flow(flow.as_deref())
        .map_err(|_| "VLESS flow is unsupported")?
        .map(str::to_owned);
    let (udp_encoding, multiplex, udp_enabled) = options::parse_vless_external_options(mapping)?;
    config.udp_encoding = udp_encoding;
    config.multiplex = multiplex;
    if !udp_enabled {
        config.network = Some("tcp".into());
    }
    Ok(())
}

fn apply_hysteria2(
    mapping: &Mapping,
    config: &mut Hysteria2Config,
    password: Option<String>,
) -> Result<(), &'static str> {
    config.auth = yaml_text_alias(mapping, &["auth"])?.or(password);
    if let Some(obfs) = yaml_text_alias(mapping, &["obfs"])? {
        if !obfs.eq_ignore_ascii_case("salamander") {
            return Err("unsupported Hysteria2 obfuscation algorithm");
        }
        config.obfs = yaml_text_alias(mapping, &["obfs-password", "obfs_password"])?
            .filter(|password| !password.trim().is_empty())
            .ok_or("Hysteria2 salamander password is missing")
            .map(Some)?;
    } else if ["obfs-password", "obfs_password"].iter().any(|key| {
        yaml_value(mapping, key).is_some_and(|value| !matches!(value, serde_yaml::Value::Null))
    }) {
        return Err("Hysteria2 obfs-password requires salamander");
    }
    Ok(())
}

fn apply_anytls(
    mapping: &Mapping,
    config: &mut AnyTlsConfig,
    password: Option<String>,
) -> Result<(), &'static str> {
    config.password = password;
    config.min_idle_session = yaml_u64_alias(mapping, &["min-idle-session", "min_idle_session"])?
        .map(|value| usize::try_from(value).map_err(|_| "AnyTLS session count is too large"))
        .transpose()?;
    config.idle_session_check_interval = yaml_duration_alias(
        mapping,
        &["idle-session-check-interval", "idle_session_check_interval"],
    )?;
    config.idle_session_timeout =
        yaml_duration_alias(mapping, &["idle-session-timeout", "idle_session_timeout"])?;
    Ok(())
}

fn validate_imported_node(node: &Node) -> Result<(), &'static str> {
    fn nonempty(value: Option<&String>) -> Result<&String, &'static str> {
        value
            .filter(|value| !value.trim().is_empty())
            .ok_or("required proxy credential is missing")
    }

    match &node.outbound {
        OutboundConfig::Shadowsocks(config) => {
            nonempty(config.password.as_ref())?;
            nonempty(config.encryption.as_ref())?;
        }
        OutboundConfig::Trojan(config) => {
            nonempty(config.password.as_ref())?;
        }
        OutboundConfig::Juicity(config) => {
            nonempty(config.password.as_ref())?;
        }
        OutboundConfig::AnyTls(config) => {
            nonempty(config.password.as_ref())?;
        }
        _ => {}
    }
    Ok(())
}

/// The node of one Clash proxy mapping. sing-box and SIP008 entries come here as the Clash
/// mapping they normalize to, with no default TUIC ALPN; a Clash document's TUIC proxies default
/// to `h3`, as Mihomo's do.
pub(super) fn parse_clash_proxy(
    mapping: &Mapping,
    tuic_default_alpn: Option<&str>,
) -> Result<Node, (Option<&'static str>, &'static str)> {
    let entry_error = |message| (None, message);
    let ProxySource {
        protocol,
        name,
        server,
        port,
        udp,
        tls_explicit,
        tls_enabled,
    } = validate_source(mapping).map_err(entry_error)?;
    let mut node = Node {
        name,
        address: format!("{server}:{port}"),
        host: server,
        port,
        outbound: OutboundConfig::from_protocol(protocol),
        ..Default::default()
    };

    apply_protocol(mapping, &mut node).map_err(entry_error)?;
    stream::apply_stream(mapping, &mut node, udp).map_err(entry_error)?;
    stream::apply_tls(mapping, &mut node, protocol, tls_explicit, tls_enabled)
        .map_err(entry_error)?;
    quic::apply_quic(mapping, &mut node, tuic_default_alpn).map_err(entry_error)?;
    node.normalize_stream_transport()
        .map_err(|(field, message)| (Some(field), message))?;
    if let Some(config) = node.vless_mut() {
        config.normalize();
    }
    validate_imported_node(&node).map_err(entry_error)?;
    node.validate()
        .map_err(|_| (None, "invalid imported node protocol settings"))?;
    node.id = node.derive_id();
    Ok(node)
}
