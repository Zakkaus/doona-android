//! VLESS packet and multiplex options of a Clash proxy: honk-core's `src/subscription/clash/options.rs`
//! (daeuniverse/honk 8919784a).

use honk_config::node::{Udp443Policy, VlessMultiplex, VlessUdpEncoding};
use serde_yaml::{Mapping, Value};

use super::super::yaml_value;
use super::fields::{active, bool_alias, raw_alias, u64_alias};

fn signed_i16(mapping: &Mapping, key: &str) -> Result<i16, &'static str> {
    match yaml_value(mapping, key) {
        None | Some(Value::Null) => Ok(0),
        Some(Value::Number(value)) => value
            .as_i64()
            .and_then(|value| i16::try_from(value).ok())
            .ok_or("VLESS mux concurrency must be a signed 16-bit integer"),
        Some(Value::String(value)) => value
            .trim()
            .parse()
            .map_err(|_| "VLESS mux concurrency must be a signed 16-bit integer"),
        Some(_) => Err("VLESS mux concurrency must be a signed 16-bit integer"),
    }
}

fn parse_xray_multiplex(mapping: &Mapping) -> Result<Option<VlessMultiplex>, &'static str> {
    let Some(value) = yaml_value(mapping, "mux").filter(|value| active(value)) else {
        return Ok(None);
    };
    let Value::Mapping(options) = value else {
        return Err("VLESS mux must be an Xray mux object");
    };
    let enabled = yaml_value(options, "enabled")
        .map(|value| value.as_bool().ok_or("VLESS mux.enabled must be boolean"))
        .transpose()?
        .unwrap_or(false);
    if !enabled {
        return Ok(None);
    }
    for (key, value) in options {
        if !matches!(
            key.as_str(),
            Some("enabled" | "concurrency" | "xudpConcurrency" | "xudpProxyUDP443")
        ) && active(value)
        {
            return Err("unsupported VLESS Xray mux setting");
        }
    }
    let udp443 = match yaml_value(options, "xudpProxyUDP443") {
        None | Some(Value::Null) => Udp443Policy::default(),
        Some(Value::String(value)) => match value.trim() {
            "reject" => Udp443Policy::Reject,
            "skip" => Udp443Policy::Skip,
            "allow" => Udp443Policy::Allow,
            _ => return Err("unsupported VLESS Xray UDP/443 policy"),
        },
        Some(_) => return Err("VLESS Xray UDP/443 policy must be a string"),
    };
    Ok(Some(VlessMultiplex::xray(
        signed_i16(options, "concurrency")?,
        signed_i16(options, "xudpConcurrency")?,
        udp443,
    )))
}

/// The packet encoding, multiplex and UDP of a VLESS proxy's Clash, Mihomo and sing-box-derived
/// options. honk-core's `parse_vless_external_options`, split into helpers that make its checks in
/// its order.
pub(super) fn parse_vless_external_options(
    mapping: &Mapping,
) -> Result<(VlessUdpEncoding, VlessMultiplex, bool), &'static str> {
    let udp = yaml_value(mapping, "udp")
        .map(|value| value.as_bool().ok_or("VLESS udp must be boolean"))
        .transpose()?;
    let packet_encoding = packet_encoding(mapping)?;
    reject_packet_addr(mapping)?;

    let xray_multiplex = parse_xray_multiplex(mapping)?;
    let h2_multiplex = parse_h2_multiplex(mapping)?;
    let uot_enabled = udp_over_tcp(mapping)?;
    reject_wrapper_conflicts(
        packet_encoding,
        h2_multiplex.is_some(),
        xray_multiplex.is_some(),
        uot_enabled,
    )?;

    let wrapper_enabled = h2_multiplex.is_some() || xray_multiplex.is_some() || uot_enabled;
    let udp_enabled =
        udp != Some(false) && (udp == Some(true) || packet_encoding.is_some() || wrapper_enabled);
    let multiplex = xray_multiplex
        .or(h2_multiplex)
        .unwrap_or(VlessMultiplex::Off);
    let udp_encoding = udp_encoding(uot_enabled, packet_encoding, udp, multiplex);
    Ok((udp_encoding, multiplex, udp_enabled))
}

fn packet_encoding(mapping: &Mapping) -> Result<Option<VlessUdpEncoding>, &'static str> {
    if yaml_value(mapping, "packet-encoding").is_some_and(|value| !matches!(value, Value::Null))
        && yaml_value(mapping, "packet_encoding").is_some_and(|value| !matches!(value, Value::Null))
    {
        return Err("duplicate VLESS XUDP representations");
    }

    // Clash treats an empty packet-encoding as omitted, while none/legacy and
    // xudp=false explicitly select the native VLESS packet command.
    let packet_encoding =
        raw_alias(mapping, &["packet-encoding", "packet_encoding"])?.filter(|value| {
            value
                .as_str()
                .is_none_or(|encoding| !encoding.trim().is_empty())
        });
    let xudp = yaml_value(mapping, "xudp").filter(|value| match value {
        Value::Null => false,
        Value::String(value) => !value.trim().is_empty(),
        _ => true,
    });
    match (packet_encoding, xudp) {
        (Some(value), None) => named_packet_encoding(value).map(Some),
        (None, Some(value)) => Ok(Some(
            if value.as_bool().ok_or("VLESS xudp must be boolean")? {
                VlessUdpEncoding::Xudp
            } else {
                VlessUdpEncoding::Native
            },
        )),
        (None, None) => Ok(None),
        (Some(_), Some(_)) => Err("duplicate VLESS XUDP representations"),
    }
}

fn reject_packet_addr(mapping: &Mapping) -> Result<(), &'static str> {
    if let Some(value) =
        raw_alias(mapping, &["packet-addr", "packet_addr"])?.filter(|value| active(value))
        && value.as_bool().ok_or("VLESS packet-addr must be boolean")?
    {
        return Err("unsupported VLESS packet-addr mode");
    }
    Ok(())
}

fn named_packet_encoding(value: &Value) -> Result<VlessUdpEncoding, &'static str> {
    match value
        .as_str()
        .ok_or("VLESS packet encoding must be a string")?
        .trim()
    {
        "none" | "legacy" => Ok(VlessUdpEncoding::Native),
        "xudp" => Ok(VlessUdpEncoding::Xudp),
        _ => Err("unsupported VLESS packet encoding"),
    }
}

/// An enabled `smux` or `multiplex` setting, which must be H2 mux without tuning.
fn parse_h2_multiplex(mapping: &Mapping) -> Result<Option<VlessMultiplex>, &'static str> {
    let Some(value) = raw_alias(mapping, &["smux", "multiplex"])?.filter(|value| active(value))
    else {
        return Ok(None);
    };
    let options = value
        .as_mapping()
        .ok_or("VLESS multiplex settings must be a mapping")?;
    let enabled = yaml_value(options, "enabled")
        .map(|value| {
            value
                .as_bool()
                .ok_or("VLESS multiplex.enabled must be boolean")
        })
        .transpose()?
        .unwrap_or(false);
    if !enabled {
        return Ok(None);
    }
    let protocol = yaml_value(options, "protocol")
        .map(|value| {
            value
                .as_str()
                .map(str::trim)
                .ok_or("VLESS multiplex.protocol must be a string")
        })
        .transpose()?
        .filter(|protocol| !protocol.is_empty());
    if protocol.is_some_and(|protocol| protocol != "h2mux") {
        return Err("unsupported VLESS multiplex protocol");
    }
    reject_h2_tuning(options)?;
    let padding = yaml_value(options, "padding")
        .map(|value| {
            value
                .as_bool()
                .ok_or("VLESS multiplex.padding must be boolean")
        })
        .transpose()?;
    if protocol.is_none() && padding.is_none() {
        return Err("enabled VLESS multiplex requires an explicit protocol or padding setting");
    }
    Ok(Some(VlessMultiplex::H2 {
        padding: padding.unwrap_or(false),
    }))
}

fn reject_h2_tuning(options: &Mapping) -> Result<(), &'static str> {
    if bool_alias(options, &["only-tcp", "only_tcp"])? == Some(true) {
        return Err("VLESS multiplex.only-tcp is unsupported");
    }
    if let Some(value) = raw_alias(options, &["brutal", "brutal-opts", "brutal_opts"])?
        && !brutal_disabled(value)?
    {
        return Err("VLESS multiplex Brutal is unsupported");
    }
    for keys in [
        ["max-connections", "max_connections"],
        ["min-streams", "min_streams"],
        ["max-streams", "max_streams"],
    ] {
        if u64_alias(options, &keys)?.is_some_and(|limit| limit != 0) {
            return Err("VLESS multiplex tuning is unsupported");
        }
    }
    Ok(())
}

fn brutal_disabled(value: &Value) -> Result<bool, &'static str> {
    Ok(match value {
        Value::Bool(false) => true,
        Value::Mapping(brutal) if brutal.is_empty() => true,
        Value::Mapping(brutal) => match yaml_value(brutal, "enabled") {
            Some(value) => !value
                .as_bool()
                .ok_or("VLESS multiplex Brutal enabled must be boolean")?,
            None => false,
        },
        _ => false,
    })
}

/// Whether UDP over TCP is enabled; only its version 2 is supported.
fn udp_over_tcp(mapping: &Mapping) -> Result<bool, &'static str> {
    let Some(value) =
        raw_alias(mapping, &["udp-over-tcp", "udp_over_tcp"])?.filter(|value| active(value))
    else {
        return Ok(false);
    };
    let options = match value {
        Value::Bool(enabled) => return Ok(*enabled),
        Value::Mapping(options) => options,
        _ => return Err("VLESS udp-over-tcp must be boolean or a mapping"),
    };
    let uot_enabled = yaml_value(options, "enabled")
        .map(|value| {
            value
                .as_bool()
                .ok_or("VLESS udp-over-tcp.enabled must be boolean")
        })
        .transpose()?
        .unwrap_or(false);
    if uot_enabled {
        let version = yaml_value(options, "version")
            .map(|value| {
                value
                    .as_u64()
                    .ok_or("VLESS udp-over-tcp.version must be an integer")
            })
            .transpose()?
            .unwrap_or(0);
        if !matches!(version, 0 | 2) {
            return Err("unsupported VLESS udp-over-tcp version");
        }
    }
    Ok(uot_enabled)
}

fn reject_wrapper_conflicts(
    packet_encoding: Option<VlessUdpEncoding>,
    h2_multiplex: bool,
    xray_multiplex: bool,
    uot_enabled: bool,
) -> Result<(), &'static str> {
    if h2_multiplex && xray_multiplex {
        return Err("VLESS H2 and Xray multiplex cannot both be enabled");
    }
    if packet_encoding == Some(VlessUdpEncoding::Xudp) && (h2_multiplex || uot_enabled) {
        return Err("VLESS XUDP cannot be combined with H2 multiplex or udp-over-tcp");
    }
    if uot_enabled && (h2_multiplex || xray_multiplex) {
        return Err("VLESS multiplex and udp-over-tcp cannot both be enabled");
    }
    Ok(())
}

fn udp_encoding(
    uot_enabled: bool,
    packet_encoding: Option<VlessUdpEncoding>,
    udp: Option<bool>,
    multiplex: VlessMultiplex,
) -> VlessUdpEncoding {
    if uot_enabled {
        VlessUdpEncoding::UotV2
    } else if let Some(packet_encoding) = packet_encoding {
        packet_encoding
    } else if udp == Some(true) && multiplex == VlessMultiplex::Off {
        VlessUdpEncoding::Xudp
    } else {
        VlessUdpEncoding::Auto
    }
}
