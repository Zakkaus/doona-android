//! A core in Global behind a loopback subscription feed, for the tests of proxied flows.

use super::TempDir;
use super::client::Client;
use super::seqpacket_pair;
use super::servers;
use doonacore::api::{Core, Options};
use doonacore::dto::Snapshot;
use doonacore::sys;
use honk_netstack::FlowId;
use std::net::SocketAddr;
use std::os::fd::AsRawFd;
use std::time::Duration;

pub const CONFIG: &str =
    r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":["192.168.8.1"]}"#;
pub const WAIT: Duration = Duration::from_secs(5);

pub struct Global {
    pub core: Core,
    pub subscription: String,
    _dir: TempDir,
}

impl Global {
    /// A stopped core in Global with one subscription of `links`, its DNS going to `upstreams`.
    /// The `Proxy` group starts on `Auto`, which before any probe carries the first link.
    pub fn new(links: &[String], upstreams: Vec<SocketAddr>) -> Global {
        let options = Options {
            dns_upstreams: upstreams,
            ..Options::default()
        };
        Global::with_options(links, options)
    }

    /// [`Global::new`] with the core's `options`.
    pub fn with_options(links: &[String], options: Options) -> Global {
        let dir = TempDir::new("global");
        let core = Core::with_options(dir.path().to_str().unwrap(), options).unwrap();
        let feed = servers::feed(links.join("\n"));
        let reply = core.call(
            "addSubscription",
            &format!(r#"{{"url":"http://{feed}/sub","name":null,"userAgent":null}}"#),
        );
        let reply: serde_json::Value = serde_json::from_str(&reply).unwrap();
        let subscription = reply["ok"]["id"].as_str().expect("added").to_owned();
        Global {
            core,
            subscription,
            _dir: dir,
        }
    }

    /// Starts a generation; the client plays the apps behind it.
    pub fn start(&self) -> Client {
        let (tun, peer) = seqpacket_pair();
        self.core
            .start(sys::dup_cloexec(tun.as_raw_fd()).unwrap(), CONFIG)
            .unwrap();
        Client::new(peer)
    }

    pub fn snapshot(&self) -> Snapshot {
        serde_json::from_str(&self.core.snapshot(-1)).unwrap()
    }

    pub fn call_ok(&self, command: &str, args: &str) {
        assert_eq!(self.core.call(command, args), r#"{"ok":{}}"#, "{command}");
    }

    /// Points the `Proxy` group at the node named `name`.
    pub fn select(&self, name: &str) {
        let catalog = self.snapshot().catalog.unwrap();
        let member = catalog.groups[0]
            .members
            .iter()
            .find(|m| m.name == name)
            .expect("a member of Proxy");
        let args = format!(r#"{{"group":"g-Proxy","memberId":"{}"}}"#, member.id);
        self.call_ok("select", &args);
    }
}

pub fn socks_link(addr: SocketAddr, name: &str) -> String {
    format!("socks5://{addr}#{name}")
}

/// A VMess node on `addr`, which may be a plain listener: VMess carries no UDP.
pub fn vmess_link(addr: SocketAddr, name: &str) -> String {
    use base64::Engine;
    let json = format!(
        r#"{{"v":"2","ps":"{name}","add":"{}","port":"{}","id":"b831381d-6324-4d53-ad4f-8cda48b30811","aid":"0","net":"tcp","type":"none","tls":""}}"#,
        addr.ip(),
        addr.port()
    );
    format!(
        "vmess://{}",
        base64::engine::general_purpose::STANDARD.encode(json)
    )
}

pub fn pattern(len: usize) -> Vec<u8> {
    (0..len).map(|i| (i * 31 % 251) as u8).collect()
}

/// Sends `msg` on an open flow and waits for the echo.
pub fn ping(c: &mut Client, id: FlowId, msg: &[u8]) -> bool {
    let mut sent = 0;
    let mut got = Vec::new();
    c.until(WAIT, |c| {
        sent += c.write_now(id, &msg[sent..]);
        got.extend(c.read_now(id).unwrap_or_default());
        got == msg
    })
}
