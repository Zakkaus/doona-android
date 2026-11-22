//! `bootstrapDns`: subscription and proxy-server host names are resolved by the resolvers
//! `start` passes, over the direct path, or by the fallback when there are none; before the first
//! `start` and after `stop` the last list serves. A loopback UDP resolver answers every name with
//! 127.0.0.1, and none of the names exists anywhere else, so a lookup that went to the system
//! resolver would fail. honk's hooks are per process, so these tests run one at a time.

mod common;

use common::client::Client;
use common::servers::{self, Seen};
use common::{TempDir, seqpacket_pair, socks};
use doonacore::api::{Core, Options};
use doonacore::dto::ErrorCode;
use doonacore::sys;
use std::net::SocketAddr;
use std::os::fd::AsRawFd;
use std::sync::atomic::Ordering::Relaxed;
use std::sync::{Mutex, MutexGuard};

fn serial() -> MutexGuard<'static, ()> {
    static SERIAL: Mutex<()> = Mutex::new(());
    SERIAL.lock().unwrap_or_else(|e| e.into_inner())
}

/// A core whose resolvers listen on `port`, with `fallback` when `start` names none.
fn new_core(dir: &TempDir, port: u16, fallback: Vec<SocketAddr>) -> Core {
    let options = Options {
        bootstrap_port: port,
        bootstrap_fallback: fallback,
        ..Options::default()
    };
    Core::with_options(dir.path().to_str().unwrap(), options).unwrap()
}

fn subscribe(core: &Core, url: &str) -> Result<(), ErrorCode> {
    let reply = core.call(
        "addSubscription",
        &format!(r#"{{"url":"{url}","name":null}}"#),
    );
    let reply: serde_json::Value = serde_json::from_str(&reply).unwrap();
    match reply["error"]["code"].as_str() {
        None => Ok(()),
        Some(code) => Err(serde_json::from_value(code.into()).unwrap()),
    }
}

fn start(core: &Core, bootstrap: &str) -> Client {
    let config = format!(
        r#"{{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":{bootstrap}}}"#
    );
    let (tun, peer) = seqpacket_pair();
    core.start(sys::dup_cloexec(tun.as_raw_fd()).unwrap(), &config)
        .unwrap();
    Client::new(peer)
}

fn queries(seen: &Seen) -> usize {
    seen.datagrams.load(Relaxed)
}

/// A UDP port with nothing listening: a query there is refused at once.
fn dead_resolver() -> SocketAddr {
    let s = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
    s.local_addr().unwrap()
}

/// Proxy-server names resolve through the resolvers `start` names: a Global flow reaches its
/// destination through a SOCKS5 node known only by name.
#[test]
fn node_hosts_are_resolved_with_the_start_config() {
    let _s = serial();
    let (resolver, seen) = servers::udp_resolver();
    let (proxy, proxy_seen) = socks::socks5();
    let (echo, _) = servers::tcp_echo();
    let feed = servers::feed(format!("socks5://proxy.doona.test:{}#s5", proxy.port()));
    let dir = TempDir::new("boot-node");
    let core = new_core(&dir, resolver.port(), vec![dead_resolver()]);
    subscribe(&core, &format!("http://{feed}/sub")).unwrap();
    let mut client = start(&core, r#"["127.0.0.1"]"#);
    let id = client.connect(echo);
    assert_eq!(client.exchange(id, b"by name").unwrap(), b"by name");
    assert_eq!(proxy_seen.connections.load(Relaxed), 1);
    assert!(queries(&seen) >= 1);
}

/// With no resolver in the config, the fallback list serves.
#[test]
fn the_fallback_serves_when_start_names_no_resolver() {
    let _s = serial();
    let (resolver, seen) = servers::udp_resolver();
    let (proxy, proxy_seen) = socks::socks5();
    let (echo, _) = servers::tcp_echo();
    let feed = servers::feed(format!("socks5://proxy.doona.test:{}#s5", proxy.port()));
    let dir = TempDir::new("boot-fallback");
    // The port of the configured resolvers is dead; the fallback is the live resolver.
    let core = new_core(&dir, dead_resolver().port(), vec![resolver]);
    subscribe(&core, &format!("http://{feed}/sub")).unwrap();
    let mut client = start(&core, "[]");
    let id = client.connect(echo);
    assert_eq!(client.exchange(id, b"fallback").unwrap(), b"fallback");
    assert_eq!(proxy_seen.connections.load(Relaxed), 1);
    assert!(queries(&seen) >= 1);
}

/// Before any `start` the fallback resolves a subscription's host; after `stop` the list the last
/// `start` named does.
#[test]
fn subscription_hosts_use_the_fallback_then_the_last_list() {
    let _s = serial();
    let (resolver, seen) = servers::udp_resolver();
    let feed = servers::feed("socks5://127.0.0.1:1#s5".to_owned());
    let by_name = format!("http://feed.doona.test:{}/sub", feed.port());
    let dir = TempDir::new("boot-feed");
    let core = new_core(&dir, resolver.port(), vec![resolver]);
    assert_eq!(subscribe(&core, &by_name), Ok(()));
    assert!(queries(&seen) >= 1);

    let dir = TempDir::new("boot-feed-last");
    let core = new_core(&dir, resolver.port(), vec![dead_resolver()]);
    assert_eq!(subscribe(&core, &by_name), Err(ErrorCode::FetchFailed));
    assert_eq!(core.call("setMode", r#"{"mode":"Direct"}"#), r#"{"ok":{}}"#);
    let _client = start(&core, r#"["127.0.0.1"]"#);
    core.stop();
    let before = queries(&seen);
    assert_eq!(subscribe(&core, &by_name), Ok(()));
    assert!(queries(&seen) > before);
}
