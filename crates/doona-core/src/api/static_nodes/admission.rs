use super::{StaticResult, invalid, unsupported};
use crate::subscription::parse::admit;
use honk_config::node::Node;
use url::form_urlencoded;

/// The feed parser silently drops unusable entries and honk reads only the first hop of a chain.
/// An editor must explain those cases without ever returning the link or a parser diagnostic.
pub(super) fn parse(link: &str) -> Result<Node, StaticResult> {
    if link.contains("->") {
        return Err(unsupported("chain"));
    }
    if link.len() > 8192 {
        return Err(invalid("link_length"));
    }
    if link.chars().any(char::is_control) {
        return Err(invalid("link"));
    }
    let (scheme, payload) = link.split_once("://").ok_or_else(|| invalid("link"))?;
    let scheme = scheme.to_ascii_lowercase();
    if !matches!(
        scheme.as_str(),
        "trojan"
            | "ss"
            | "socks5"
            | "vless"
            | "vmess"
            | "hysteria2"
            | "hy2"
            | "tuic"
            | "juicity"
            | "anytls"
    ) {
        return Err(unsupported("protocol"));
    }
    let body = payload.split('#').next().unwrap();
    let (authority, query) = body.split_once('?').unwrap_or((body, ""));
    if scheme == "vmess" {
        vmess_options(authority)?;
    }
    let options = form_urlencoded::parse(query.as_bytes());
    check_uri(&scheme, authority, options)?;
    let mut parsed = admit(std::iter::once(link)).expect("one link is within the feed node budget");
    let node = parsed.nodes.pop().ok_or_else(|| invalid("link"))?;
    check_transport(&node, options)?;
    credentials(&node)?;
    Ok(node)
}

fn check_uri(
    scheme: &str,
    authority: &str,
    options: form_urlencoded::Parse<'_>,
) -> Result<(), StaticResult> {
    // Base64 authorities can contain slashes, but an ordinary share URI has no path options.
    if (authority.contains('@') || !matches!(scheme, "ss" | "vmess" | "vless"))
        && authority.trim_end_matches('/').contains('/')
    {
        return Err(unsupported("path"));
    }
    let mut seen = std::collections::HashSet::new();
    for (key, value) in options {
        if !supported_key(scheme, &key, &value) {
            return Err(unsupported("option"));
        }
        if !seen.insert(key) {
            return Err(invalid("duplicate_option"));
        }
    }
    Ok(())
}

fn check_transport(node: &Node, options: form_urlencoded::Parse<'_>) -> Result<(), StaticResult> {
    let transport = node.transport().map(|t| t.transport.as_str());
    for (key, value) in options {
        let applies = match key.as_ref() {
            "mode" | "extra" => transport == Some("xhttp"),
            "alpn" => transport == Some("xhttp") || node.tuic().is_some(),
            "path" | "host" => matches!(transport, Some("ws" | "xhttp")),
            "serviceName" => transport == Some("grpc"),
            "obfs-password" => node.hysteria2().is_some_and(|c| c.obfs.is_some()),
            "obfs" if !value.is_empty() => node.hysteria2().is_some_and(|c| c.obfs.is_some()),
            "sni" | "peer" | "pinSHA256" | "pin_sha256" | "insecure" | "allowInsecure"
            | "allow_insecure"
                if node.transport().is_some() =>
            {
                node.tls().is_some_and(|t| t.is_secure())
            }
            _ => true,
        };
        if !applies {
            return Err(unsupported("option"));
        }
    }
    Ok(())
}

fn credentials(node: &Node) -> Result<(), StaticResult> {
    use honk_config::node::OutboundConfig::*;
    let password = match &node.outbound {
        Trojan(c) => &c.password,
        AnyTls(c) => &c.password,
        Hysteria2(c) => &c.auth,
        Juicity(c) => &c.password,
        Tuic(c) => &c.password,
        Shadowsocks(c) => return ss_credentials(c),
        Socks5(c) => {
            return match (c.username.as_deref(), c.password.as_deref()) {
                (None, None) => Ok(()),
                (Some(u), Some(p))
                    if (1..=255).contains(&u.len()) && (1..=255).contains(&p.len()) =>
                {
                    Ok(())
                }
                _ => Err(invalid("credentials")),
            };
        }
        _ => return Ok(()),
    };
    if password.as_deref().is_none_or(str::is_empty) {
        return Err(invalid("password"));
    }
    Ok(())
}

fn ss_credentials(c: &honk_config::node::ShadowsocksConfig) -> Result<(), StaticResult> {
    use base64::{Engine, engine::general_purpose::STANDARD};
    let password = c
        .password
        .as_deref()
        .filter(|s| !s.is_empty())
        .ok_or_else(|| invalid("password"))?;
    let cipher = c
        .encryption
        .as_deref()
        .ok_or_else(|| invalid("cipher"))?
        .to_ascii_lowercase();
    let bytes = match cipher.as_str() {
        "aes-128-gcm" | "aes-256-gcm" | "chacha20-ietf-poly1305" | "chacha20-poly1305" => {
            return Ok(());
        }
        "2022-blake3-aes-128-gcm" => 16,
        "2022-blake3-aes-256-gcm" => 32,
        "2022-blake3-chacha20-poly1305" if !password.contains(':') => 32,
        _ => return Err(unsupported("cipher")),
    };
    let mut key = [0; 32];
    if password
        .split(':')
        .all(|part| STANDARD.decode_slice(part.trim(), &mut key) == Ok(bytes))
    {
        Ok(())
    } else {
        Err(invalid("password"))
    }
}

fn supported_key(protocol: &str, key: &str, value: &str) -> bool {
    let stream = matches!(protocol, "trojan" | "vless" | "vmess");
    let tls = !matches!(protocol, "ss" | "socks5");
    match key {
        "remark" => true,
        "security" => stream && matches!(value, "tls" | "reality" | "none"),
        "tls" => stream && matches!(value, "0" | "1"),
        "sni" | "peer" | "allowInsecure" | "allow_insecure" | "insecure" | "pinSHA256"
        | "pin_sha256" => tls,
        "pbk" | "sid" | "spx" => matches!(protocol, "vless" | "trojan"),
        "type" | "network" => {
            stream && matches!(value, "tcp" | "ws" | "grpc" | "xhttp" | "splithttp")
        }
        "path" | "host" | "serviceName" | "mode" | "extra" => stream,
        "alpn" => stream || protocol == "tuic",
        _ => protocol_key(protocol, key),
    }
}

fn protocol_key(protocol: &str, key: &str) -> bool {
    match key {
        "flow" | "mux" | "packetEncoding" | "udp" | "padding" | "concurrency"
        | "xudpConcurrency" | "xudpProxyUDP443" => protocol == "vless",
        "encryption" => matches!(protocol, "vmess" | "vless"),
        "scy" => protocol == "vmess",
        "congestion_control" | "udp_relay_mode" => protocol == "tuic",
        "obfs" | "obfs-password" => matches!(protocol, "hysteria2" | "hy2"),
        _ => false,
    }
}

/// honk accepts unknown JSON members and nonzero alterId on legacy VMess links but does not dial
/// those settings. Encoded authorities instead carry their options in the ordinary URI query.
fn vmess_options(authority: &str) -> Result<(), StaticResult> {
    use base64::{
        Engine,
        engine::general_purpose::{STANDARD, STANDARD_NO_PAD, URL_SAFE, URL_SAFE_NO_PAD},
    };
    let bytes = [STANDARD, STANDARD_NO_PAD, URL_SAFE, URL_SAFE_NO_PAD]
        .into_iter()
        .find_map(|e| e.decode(authority).ok())
        .ok_or_else(|| invalid("link"))?;
    if !bytes
        .iter()
        .find(|b| !b.is_ascii_whitespace())
        .is_some_and(|b| *b == b'{')
    {
        return Ok(());
    }
    let obj: serde_json::Map<String, serde_json::Value> =
        serde_json::from_slice(&bytes).map_err(|_| invalid("link"))?;
    vmess_fields(&obj)
}

fn vmess_fields(obj: &serde_json::Map<String, serde_json::Value>) -> Result<(), StaticResult> {
    let xhttp = matches!(
        obj.get("net").and_then(|v| v.as_str()),
        Some("xhttp" | "splithttp")
    );
    for (key, value) in obj {
        let supported = match key.as_str() {
            "v" | "ps" | "add" | "port" | "id" | "scy" | "security" | "net" | "host" | "sni" => {
                true
            }
            "aid" => value == 0 || value == "0",
            "tls" => matches!(value.as_str(), Some("tls" | "" | "none")),
            "type" => xhttp || matches!(value.as_str(), Some("" | "none")),
            "path" => {
                xhttp
                    || value == ""
                    || matches!(obj.get("net").and_then(|v| v.as_str()), Some("ws" | "grpc"))
            }
            "mode" | "extra" | "alpn" => xhttp,
            _ => false,
        };
        if !supported {
            return Err(unsupported("option"));
        }
    }
    Ok(())
}
