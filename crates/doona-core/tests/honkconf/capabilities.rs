use super::{TempDir, load, write};

#[test]
fn explicit_unsupported_settings() {
    let dir = TempDir::new("honk-settings");
    for (section, field, value, code) in [
        (
            "experimental.clash_api",
            "external_controller",
            "'127.0.0.1:9090'",
            "daemon_listener",
        ),
        ("experimental.clash_api", "default_mode", "rule", "vpn_mode"),
        (
            "experimental.cache_file",
            "path",
            "'/var/cache/honk'",
            "host_path",
        ),
        (
            "experimental.cache_file",
            "store_fakeip",
            "true",
            "unsupported_option",
        ),
        ("global", "data_dir", "'/var/lib/honk'", "host_path"),
        ("global", "wan_interface", "'eth0'", "linux_interface"),
        ("global", "tproxy_port_protect", "true", "linux_ingress"),
        ("global", "mptcp", "true", "unsupported_option"),
        ("global", "tls_fragment", "true", "unsupported_option"),
        ("global", "allow_insecure", "true", "unsupported_option"),
        (
            "global",
            "fallback_resolver",
            "'1.1.1.1:53'",
            "unsupported_option",
        ),
        (
            "global",
            "bandwidth_max_tx",
            "'5 mbps'",
            "unsupported_option",
        ),
        (
            "global",
            "bandwidth_max_rx",
            "'5 mbps'",
            "unsupported_option",
        ),
    ] {
        let mut text = format!("{field}: {value}");
        for block in section.split('.').rev() {
            text = format!("{block} {{ {text} }}");
        }
        let doc = load(write(&dir, "main.dae", &text)).unwrap_or_else(|e| panic!("{text}: {e}"));
        let records = doc.unsupported();
        assert_eq!(records.len(), 1, "{text}: {records:?}");
        assert_eq!(
            (
                records[0].section,
                records[0].name.as_ref(),
                records[0].reasons[0].code
            ),
            (section, field, code)
        );
    }
}

#[test]
fn active_native_api_requires_two_skipped_settings() {
    let dir = TempDir::new("honk-native-api");
    let text = "experimental { native_api { enabled: true\n secret: 'fixture-only' } }";
    let records = load(write(&dir, "main.dae", text)).unwrap().unsupported();
    assert_eq!(
        records
            .iter()
            .map(|r| (r.name.as_ref(), r.reasons[0].code))
            .collect::<Vec<_>>(),
        [
            ("enabled", "daemon_listener"),
            ("secret", "daemon_listener")
        ]
    );
}

#[test]
fn cipher_availability_and_source_location() {
    use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
    // The method check is private upstream. Require a capability review on every
    // pin change, including additions our known positive/negative cases cannot name.
    // honk-config and honk-outbound come from one honk tree. Reviewed at the development
    // combine (dev/c9-combine): no commit since 63de98ba touches proxy/shadowsocks/mod.rs.
    assert_eq!(
        super::honk().id,
        "path+/scratch/ssd/honk-up/c9-combine",
        "review CipherConf::for_method and is_2022_method before accepting this pin"
    );
    let dir = TempDir::new("honk-ciphers");
    let entry = write(&dir, "main.dae", "include { nodes.dae }");
    let mut text = String::from(
        "node {\n 'trojan://fixture@example.invalid:443?insecure=yes'\n 'socks5://127.0.0.1:1080' trailing\n",
    );
    for method in [
        "aes-128-gcm",
        "aes-256-gcm",
        "chacha20-ietf-poly1305",
        "chacha20-poly1305",
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305",
        "rc4-md5",
    ] {
        let user = URL_SAFE_NO_PAD.encode(format!("{method}:pass"));
        text.push_str(&format!(" '{method}': 'ss://{user}@127.0.0.1:8388'\n"));
    }
    text.push('}');
    write(&dir, "nodes.dae", &text);
    let doc = load(entry).unwrap();
    assert_eq!(doc.config().nodes.len(), 9);
    let records = doc.unsupported();
    let [r] = records.as_slice() else {
        panic!("{records:?}");
    };
    assert_eq!(
        (r.name.as_ref(), r.reasons[0].code),
        ("node[8]", "unavailable_transport")
    );
    let loc = &r.locations[0];
    assert_eq!((loc.source, loc.line), (1, 11));
    assert!(text[loc.bytes.clone()].starts_with("'rc4-md5':"));
}
