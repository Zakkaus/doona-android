//! The Hysteria2 obfuscation, server ports and QUIC settings of a sing-box outbound:
//! honk-core's `src/subscription/json/sing_box.rs` (daeuniverse/honk 8919784a).

use serde_yaml::{Mapping, Value};

use super::{
    active, put, reject_active_key, reject_active_remainder, take_alias, take_optional_string,
};

pub(super) fn normalize_hysteria2_obfs(
    source: &mut Mapping,
    proxy: &mut Mapping,
) -> Result<(), &'static str> {
    let Some(value) = source.remove("obfs") else {
        return Ok(());
    };
    if matches!(value, Value::Null) {
        return Ok(());
    }
    let Value::Mapping(mut obfs) = value else {
        return Err("sing-box Hysteria2 obfs settings must be an object");
    };
    let Some(obfs_type) =
        take_optional_string(&mut obfs, "type")?.filter(|value| !value.trim().is_empty())
    else {
        if obfs.values().any(active) {
            return Err("sing-box Hysteria2 obfs type is missing");
        }
        return Ok(());
    };
    if obfs_type != "salamander" {
        return Err("unsupported sing-box Hysteria2 obfs type");
    }
    let Some(password) =
        take_optional_string(&mut obfs, "password")?.filter(|value| !value.trim().is_empty())
    else {
        return Err("sing-box Hysteria2 obfs password is missing");
    };
    reject_active_remainder(&obfs, "unsupported sing-box Hysteria2 obfs settings")?;
    put(proxy, "obfs", Value::String("salamander".into()));
    put(proxy, "obfs-password", Value::String(password));
    Ok(())
}

pub(super) fn normalize_server_ports(
    source: &mut Mapping,
    proxy: &mut Mapping,
) -> Result<Option<u16>, &'static str> {
    let Some(value) = source.remove("server_ports") else {
        return Ok(None);
    };
    let values = match value {
        Value::Null => return Ok(None),
        Value::String(value) => vec![value],
        Value::Sequence(values) => values
            .into_iter()
            .map(|value| match value {
                Value::String(value) => Ok(value),
                _ => Err("sing-box Hysteria2 server ports must be strings"),
            })
            .collect::<Result<Vec<_>, _>>()?,
        _ => return Err("sing-box Hysteria2 server ports must be a string array"),
    };
    if values.is_empty() || (values.len() == 1 && values[0].trim().is_empty()) {
        return Ok(None);
    }
    if values.iter().any(|value| value.trim().is_empty()) {
        return Err("sing-box Hysteria2 server ports must not contain empty entries");
    }
    let ports = values
        .into_iter()
        .map(|value| value.replace(':', "-"))
        .collect::<Vec<_>>()
        .join(",");
    let mut first_port = None;
    for range in ports.split(',') {
        let (start, end) = range
            .split_once('-')
            .map_or((range, range), |(start, end)| (start, end));
        let start = start.trim().parse::<u16>().ok().filter(|port| *port > 0);
        let end = end.trim().parse::<u16>().ok().filter(|port| *port > 0);
        let (Some(start), Some(end)) = (start, end) else {
            return Err("sing-box Hysteria2 server ports are invalid");
        };
        if start > end {
            return Err("sing-box Hysteria2 server ports are invalid");
        }
        first_port.get_or_insert(start);
    }
    put(proxy, "ports", Value::String(ports));
    Ok(first_port)
}

pub(super) fn normalize_quic_fields(
    source: &mut Mapping,
    proxy: &mut Mapping,
) -> Result<(), &'static str> {
    for (keys, target_key, conflict) in [
        (
            [
                "initial_stream_receive_window",
                "init_stream_receive_window",
            ],
            "initial-stream-receive-window",
            "conflicting sing-box QUIC stream window fields",
        ),
        (
            [
                "initial_connection_receive_window",
                "initial_conn_receive_window",
            ],
            "initial-conn-receive-window",
            "conflicting sing-box QUIC connection window fields",
        ),
    ] {
        if let Some(value) = take_alias(source, &keys, conflict)?.filter(active) {
            put(proxy, target_key, value);
        }
    }
    if let Some(mtu) = source.remove("initial_packet_size").filter(active) {
        put(proxy, "mtu", mtu);
    }
    if let Some(disable_mtu) = take_alias(
        source,
        &["disable_path_mtu_discovery", "disable_mtu_discovery"],
        "conflicting sing-box QUIC MTU discovery fields",
    )? {
        let Value::Bool(disabled) = disable_mtu else {
            return Err("sing-box QUIC MTU discovery setting must be boolean");
        };
        if disabled {
            put(proxy, "disable-mtu-discovery", Value::Bool(true));
        }
    }
    for key in [
        "stream_receive_window",
        "connection_receive_window",
        "max_concurrent_streams",
    ] {
        reject_active_key(
            source,
            key,
            "unsupported sing-box QUIC flow-control setting",
        )?;
    }
    Ok(())
}
