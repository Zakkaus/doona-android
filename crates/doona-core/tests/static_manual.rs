mod common;

use common::TempDir;
use doonacore::api::Core;
use serde_json::{Value, json};

fn call(core: &Core, command: &str, args: Value) -> Value {
    let reply: Value = serde_json::from_str(&core.call(command, &args.to_string())).unwrap();
    reply["ok"].clone()
}

#[test]
fn manual_endpoints_match_their_links_and_preserve_transport_options() {
    let dir = TempDir::new("static-manual");
    let core = Core::new(dir.path().to_str().unwrap()).unwrap();
    let uuid = "00000000-0000-4000-8000-000000000001";
    for (protocol, fields, transport) in [
        (
            "vmess",
            json!({"uuid":uuid,"options":{"type":"ws","tls":"1","path":"/ws"}}),
            "ws",
        ),
        (
            "vless",
            json!({"uuid":uuid,"options":{"security":"tls","type":"xhttp","mode":"packet-up","host":"x.example.net","path":"/path","alpn":"h2"}}),
            "xhttp",
        ),
        (
            "vless",
            json!({"uuid":uuid,"options":{"security":"reality","sni":"www.example.net","pbk":"d-Z80R7ouznLhLvLJAs5iS2sEeiGJ_KHIQQkiicVTnA","sid":"6ba85179","type":"tcp"}}),
            "tcp",
        ),
        (
            "vless",
            json!({"uuid":uuid,"options":{"type":"grpc","serviceName":"hello"}}),
            "grpc",
        ),
        ("trojan", json!({"password":"a%2F@:#/?"}), "tcp"),
        (
            "ss",
            json!({"cipher":"aes-256-gcm","password":"a%2F@:#/?"}),
            "tcp",
        ),
        ("socks5", json!({"username":"a:b","password":"?&=#"}), "tcp"),
        ("hysteria2", json!({"password":"p"}), "quic"),
        ("anytls", json!({"password":"p"}), "tcp"),
        (
            "tuic",
            json!({"uuid":uuid,"password":"p","options":{"alpn":"h3"}}),
            "quic",
        ),
        ("juicity", json!({"uuid":uuid,"password":"p"}), "quic"),
    ] {
        let mut node = json!({"kind":"manual","port":443,"server":"192.0.2.1","name":"Node","protocol":protocol});
        node.as_object_mut()
            .unwrap()
            .extend(fields.as_object().unwrap().clone());
        let added = &call(&core, "addStaticNodes", json!({"rows":[node]}))["rows"][0];
        assert_eq!(added["status"], "added", "{protocol}: {added}");
        let id = &added["id"];
        let stored = call(&core, "readStaticNode", json!({"id":id}));
        let replay = json!({"rows":[{"kind":"link","link":stored["link"]}]});
        assert_eq!(
            call(&core, "addStaticNodes", replay)["rows"][0],
            json!({"status":"duplicate","id":id})
        );
        let details = call(&core, "nodes", json!({"ids":[id]}));
        assert_eq!(details["nodes"][0]["transport"], transport);
    }
    let added = call(
        &core,
        "addStaticNodes",
        json!({"rows":[{"kind":"link","link":"trojan://a%252F%40%3A%23%2F%3F@192.0.2.1:443"}]}),
    );
    assert_eq!(added["rows"][0]["status"], "duplicate");
}

#[test]
fn changing_certificate_verification_does_not_reuse_the_old_endpoint_id() {
    let dir = TempDir::new("static-tls-id");
    let core = Core::new(dir.path().to_str().unwrap()).unwrap();
    let result = call(
        &core,
        "addStaticNodes",
        json!({"rows":[
            {"kind":"link","link":"trojan://demo@192.0.2.1:443#same"},
            {"kind":"link","link":"trojan://demo@192.0.2.1:443?allowInsecure=1#same"}
        ]}),
    );
    assert_eq!(result["rows"][1]["name"], "same-2");
    assert_ne!(result["rows"][0]["id"], result["rows"][1]["id"]);
}
