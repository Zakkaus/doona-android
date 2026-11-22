//! The QUIC settings of a Clash proxy: honk-core's `apply_quic` (`src/subscription/clash.rs`,
//! daeuniverse/honk 8919784a), split into helpers.

use honk_config::node::{Hysteria2Config, Node, OutboundConfig, TuicConfig};
use honk_config::types::NodeProtocol;
use serde_yaml::Mapping;

use super::fields::{
    self, active as yaml_active, bool_alias as yaml_bool_alias,
    duration_alias as yaml_duration_alias, list_alias as yaml_list_alias, ports as yaml_ports,
    rate_alias as yaml_rate_alias, raw_alias as yaml_alias, text_alias as yaml_text_alias,
    u64_alias as yaml_u64_alias,
};
use super::{
    CONN_WINDOW_KEYS, MTU_DISCOVERY_KEYS, QUIC_MTU_KEYS, STREAM_WINDOW_KEYS, has_active, yaml_value,
};

pub(super) fn apply_quic(
    mapping: &Mapping,
    node: &mut Node,
    tuic_default_alpn: Option<&str>,
) -> Result<(), &'static str> {
    reject_unsupported_quic(mapping, node.protocol())?;
    match &mut node.outbound {
        OutboundConfig::Hysteria2(config) => apply_hysteria2_quic(mapping, config)?,
        OutboundConfig::Tuic(config) => apply_tuic_quic(mapping, config, tuic_default_alpn)?,
        _ => {}
    }
    apply_mtu(mapping, node)
}

/// A fixed ALPN other than `h3`, a TUIC relay mode on another protocol, and a Juicity receive
/// window other than the default.
fn reject_unsupported_quic(mapping: &Mapping, protocol: NodeProtocol) -> Result<(), &'static str> {
    if matches!(protocol, NodeProtocol::Hysteria2 | NodeProtocol::Juicity)
        && yaml_value(mapping, "alpn").is_some_and(yaml_active)
    {
        let alpn = yaml_list_alias(mapping, &["alpn"])?.unwrap_or_default();
        if !alpn.split(',').all(|value| value.trim() == "h3") {
            return Err("unsupported fixed QUIC ALPN");
        }
    }
    if fields::tuic_relay_alias(mapping)?.is_some() && protocol != NodeProtocol::Tuic {
        return Err("unsupported TUIC UDP relay mode");
    }
    if protocol == NodeProtocol::Juicity {
        for keys in [STREAM_WINDOW_KEYS, CONN_WINDOW_KEYS] {
            if has_active(mapping, keys)
                && let Some(window) = yaml_u64_alias(mapping, keys)?
                && !matches!(window, 0 | 8_388_608)
            {
                return Err("Juicity receive-window override is unsupported");
            }
        }
    }
    Ok(())
}

fn apply_hysteria2_quic(
    mapping: &Mapping,
    config: &mut Hysteria2Config,
) -> Result<(), &'static str> {
    config.up_mbps = yaml_rate_alias(mapping, &["up", "upload-bandwidth", "up-speed", "up_mbps"])?;
    config.down_mbps = yaml_rate_alias(
        mapping,
        &["down", "download-bandwidth", "down-speed", "down_mbps"],
    )?;
    let ports = yaml_alias(mapping, &["ports"])?
        .map(yaml_ports)
        .transpose()?
        .flatten();
    let mport = yaml_text_alias(mapping, &["mport", "port-hopping", "port_hopping"])?
        .map(|value| value.replace(':', "-"));
    if ports.is_some() && mport.is_some() {
        return Err("Hysteria2 port hopping aliases conflict");
    }
    config.port_hopping = ports.or(mport);
    config.hop_interval = yaml_duration_alias(mapping, &["hop-interval", "hop_interval", "mhop"])?;
    config.init_stream_recv_window = yaml_u64_alias(mapping, STREAM_WINDOW_KEYS)?;
    config.init_conn_recv_window = yaml_u64_alias(mapping, CONN_WINDOW_KEYS)?;
    config.disable_mtu_discovery = yaml_bool_alias(mapping, MTU_DISCOVERY_KEYS)?;
    Ok(())
}

fn apply_tuic_quic(
    mapping: &Mapping,
    config: &mut TuicConfig,
    tuic_default_alpn: Option<&str>,
) -> Result<(), &'static str> {
    config.congestion = yaml_text_alias(
        mapping,
        &[
            "congestion-controller",
            "congestion-control",
            "congestion_control",
            "congestion",
        ],
    )?;
    config.alpn =
        yaml_list_alias(mapping, &["alpn"])?.or_else(|| tuic_default_alpn.map(str::to_owned));
    config.init_stream_recv_window = yaml_u64_alias(mapping, STREAM_WINDOW_KEYS)?;
    config.init_conn_recv_window = yaml_u64_alias(mapping, CONN_WINDOW_KEYS)?;
    Ok(())
}

fn apply_mtu(mapping: &Mapping, node: &mut Node) -> Result<(), &'static str> {
    if let Some(mtu) = yaml_u64_alias(mapping, QUIC_MTU_KEYS)? {
        let mtu = u16::try_from(mtu).map_err(|_| "QUIC MTU is invalid")?;
        if !(1200..=65527).contains(&mtu) {
            return Err("QUIC MTU is invalid");
        }
        match &mut node.outbound {
            OutboundConfig::Hysteria2(config) => config.quic.mtu = Some(mtu),
            OutboundConfig::Tuic(config) => config.quic.mtu = Some(mtu),
            OutboundConfig::Juicity(config) => config.quic.mtu = Some(mtu),
            _ => {}
        }
    }
    Ok(())
}
