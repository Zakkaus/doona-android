use crate::common::cert;
use base64::{
    Engine,
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
};
use serde_json::json;
use std::io::{BufRead, BufReader};
use std::net::{TcpListener, UdpSocket};
use std::process::{Child, Command, Stdio};

pub struct Peer(Child);

impl Peer {
    pub fn pid(&self) -> u32 {
        self.0.id()
    }
}

fn command() -> Command {
    Command::new(std::env::var_os("SING_BOX").unwrap_or_else(|| "sing-box".into()))
}

pub fn available() -> bool {
    command()
        .arg("version")
        .stdout(Stdio::null())
        .status()
        .is_ok_and(|s| s.success())
}

impl Drop for Peer {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

/// Only this process translates the TEST-NET destination to the payload listener.
pub fn start(protocol: &str, target: u16) -> (Peer, String) {
    for attempt in 1..=3 {
        match launch(protocol, target) {
            Ok(pair) => return pair,
            Err(true) if attempt < 3 => continue,
            Err(_) => panic!("{protocol} peer failed to start"),
        }
    }
    unreachable!()
}

fn launch(protocol: &str, target: u16) -> Result<(Peer, String), bool> {
    let port = if protocol == "hysteria2" {
        UdpSocket::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port()
    } else {
        TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port()
    };
    let addr = format!("127.0.0.1:{port}");
    let mut secret = [0; 32];
    boring::rand::rand_bytes(&mut secret).unwrap();
    let pass = URL_SAFE_NO_PAD.encode(secret);
    let (mut inbound, link) = match protocol {
        "socks" => (json!({"type":"socks"}), format!("socks5://{addr}")),
        "shadowsocks" => {
            let method = "2022-blake3-aes-256-gcm";
            let key = STANDARD.encode(secret);
            let user = URL_SAFE_NO_PAD.encode(format!("{method}:{key}"));
            (
                json!({"type":protocol,"method":method,"password":key,"network":"tcp"}),
                format!("ss://{user}@{addr}"),
            )
        }
        "vless" => {
            let id = uuid::Uuid::from_bytes(secret[..16].try_into().unwrap()).to_string();
            (
                json!({"type":protocol,"users":[{"uuid":id}]}),
                format!("vless://{id}@{addr}?security=none&type=tcp"),
            )
        }
        "trojan" | "hysteria2" => {
            let (cert, key) = cert::localhost();
            let tls = json!({"enabled":true,"certificate":String::from_utf8(cert.to_pem().unwrap()).unwrap(),"key":String::from_utf8(key.private_key_to_pem_pkcs8().unwrap()).unwrap()});
            (
                json!({"type":protocol,"tls":tls,"users":[{"password":pass}]}),
                format!("{protocol}://{pass}@{addr}?sni=localhost&insecure=1&allowInsecure=1"),
            )
        }
        _ => unreachable!(),
    };
    inbound["listen"] = json!("127.0.0.1");
    inbound["listen_port"] = json!(port);
    let config = json!({"inbounds":[inbound],"outbounds":[{"type":"direct","tag":"payload"}],"route":{"rules":[{"ip_cidr":["203.0.113.10/32"],"port":[target],"network":"tcp","action":"route","outbound":"payload","override_address":"127.0.0.1"},{"action":"reject"}]}});
    let mut peer = Peer(
        command()
            .args(["run", "-c", "stdin"])
            .stdin(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap(),
    );
    serde_json::to_writer(peer.0.stdin.take().unwrap(), &config).unwrap();
    let lines = BufReader::new(peer.0.stderr.take().unwrap()).lines();
    let (send, recv) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        for line in lines.map_while(Result::ok) {
            if line.contains("sing-box started") {
                let _ = send.send(Ok(()));
            }
            if line.contains("address already in use") {
                let _ = send.send(Err(true));
            }
        }
    });
    recv.recv_timeout(std::time::Duration::from_secs(10))
        .map_err(|_| false)??;
    Ok((peer, format!("{link}#bench")))
}
