//! What the data path makes of the local network (ARCH D26), through real dials by honk: the
//! snapshot's `warning` follows the hop to the node, raises the revision when it changes and
//! clears at `stop`; a UDP transport to a node on the local network reports its first send.
//! `127.77.0.0/16` plays a local network the host reaches (see `localnet.rs`), and Linux refuses
//! TCP to a multicast address, which Android counts as local, at once.

use super::tests_carriers::idle;
use super::tests_catalog::{Feeds, add, call, catalog, path};
use super::{Core, Life, lock};
use crate::dto::{ErrorCode, Mode, Snapshot};
use crate::testutil::{TempDir, http, socks5_on, tun};
use crate::tunnel::Session;
use crate::tunnel::route::{Inbound, Route};
use base64::Engine;
use honk_outbound::runtime::flow_observation::{FlowEvent, ResolutionLocation, TransportStatus};
use serde_json::json;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

const CONFIG: &str = r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":[]}"#;

/// A started core, with an idle data path, over the nodes of `feed`.
fn started(dir: &TempDir, feed: &str) -> Core {
    let feeds = Feeds::new();
    feeds.set("/sub", feed.as_bytes());
    let core = Core::with_launch(path(dir), idle, Duration::from_secs(10));
    add(&core, &feeds, "/sub").unwrap();
    let (tun, _probe) = tun();
    core.start(tun, CONFIG).unwrap();
    core
}

fn snapshot(core: &Core) -> Snapshot {
    serde_json::from_str(&core.snapshot(-1)).unwrap()
}

/// A Global route of the running generation, through the node `g-Proxy` has now.
fn route(core: &Core) -> Route {
    let session: Arc<Session> = match &*lock(&core.life) {
        Life::Running(tunnel) => Arc::clone(tunnel.session()),
        _ => panic!("not running"),
    };
    Route::of(Inbound::Tun, Mode::Global, &core.shared.outbounds, &session)
}

/// Makes the node named `name` the one `g-Proxy` sends traffic to.
fn through(core: &Core, name: &str) {
    let c = catalog(core);
    let node = c.groups[1].members.iter().find(|m| m.name == name).unwrap();
    let select = json!({"group": "g-Proxy", "memberId": node.id});
    call(core, "select", select).unwrap();
}

fn closed_port() -> SocketAddr {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    listener.local_addr().unwrap()
}

/// A dial refused on its way to a node on the local network warns. A node there that came up
/// clears the warning, also when it then fails to reach the destination: only the hop to the node
/// is Android's to refuse. Each change raises the revision; `stop` clears the warning too.
#[test]
fn the_warning_follows_the_hop_to_a_node_on_the_local_network() {
    let dir = TempDir::new("api-lan-warning");
    let (lan, _) = socks5_on([127, 77, 0, 1]);
    let target = http(|_| b"HTTP/1.1 204 No Content\r\n\r\n".to_vec());
    let core = started(
        &dir,
        &format!("socks5://224.0.0.1:1080#gone\nsocks5://{lan}#lan\n"),
    );
    let dial = |to: SocketAddr| core.runtime.block_on(route(&core).connect(to)).is_ok();
    let revision = || snapshot(&core).revision;

    through(&core, "lan");
    let before = revision();
    assert!(
        !dial(closed_port()),
        "the node came up, the destination refused"
    );
    assert_eq!(snapshot(&core).warning, None);
    assert_eq!(revision(), before);

    through(&core, "gone");
    let before = revision();
    assert!(!dial(target));
    let warned = snapshot(&core);
    assert!(warned.running && warned.error.is_none());
    assert_eq!(warned.warning, Some(ErrorCode::LocalNetwork));
    assert!(warned.revision > before);
    assert!(!dial(target));
    assert_eq!(revision(), warned.revision, "unchanged, so no new revision");

    through(&core, "lan");
    let before = revision();
    assert!(!dial(closed_port()));
    let cleared = snapshot(&core);
    assert_eq!(cleared.warning, None);
    assert!(cleared.revision > before);

    through(&core, "gone");
    assert!(!dial(target));
    let before = revision();
    core.stop();
    let stopped = snapshot(&core);
    assert_eq!(stopped.warning, None);
    assert!(stopped.revision > before);
}

/// A Shadowsocks UDP transport only binds a socket to its node, so the node's address is all that
/// says it is on the local network: such a transport reports its first send, one to another
/// node does not.
#[test]
fn a_udp_transport_to_a_node_on_the_local_network_reports_its_first_send() {
    let user = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode("aes-128-gcm:doona-test");
    let destination: SocketAddr = ([127, 0, 0, 1], 9).into();
    for (server, reports) in [("127.77.0.2:8388", true), ("127.0.0.1:8388", false)] {
        let dir = TempDir::new("api-lan-udp");
        let core = started(&dir, &format!("ss://{user}@{server}#ss\n"));
        let route = route(&core);
        let upstream = core.runtime.block_on(route.bind_udp(destination)).unwrap();
        assert_eq!(upstream.first_send_to().is_some(), reports, "{server}");
        core.stop();
    }
}

/// honk's report of a connect to a node at `addr`.
fn connect_to(addr: &str, status: TransportStatus) -> FlowEvent {
    FlowEvent::Transport {
        attempt_id: uuid::Uuid::nil(),
        server_addr: Some(addr.parse().unwrap()),
        status,
        resolution_location: ResolutionLocation::Unknown,
        error: None,
    }
}

/// The warning follows what the dial that began last showed: one that waited out its timeout from
/// before the permission was granted does not bring back a warning a later dial cleared, and an
/// earlier first datagram does not clear one a later dial set.
#[test]
fn an_older_dial_does_not_undo_what_a_later_one_showed() {
    let session = Session::new(1, std::sync::Weak::new());
    let refused = crate::localnet::Trace::default();
    refused.record(&connect_to("224.0.0.1:1080", TransportStatus::Failed));
    let reached = crate::localnet::Trace::default();
    reached.record(&connect_to("127.77.0.1:1080", TransportStatus::Succeeded));

    let (before, after) = (session.begin_dial(), session.begin_dial());
    session.dialed(before, &refused, false);
    assert_eq!(session.warning(), Some(ErrorCode::LocalNetwork));
    session.dialed(after, &reached, true);
    assert_eq!(session.warning(), None);
    let late = session.begin_dial();
    session.dialed(before, &refused, false);
    assert_eq!(session.warning(), None, "a dial from before the grant");

    session.dialed(late, &refused, false);
    assert_eq!(session.warning(), Some(ErrorCode::LocalNetwork));
    session.first_sent(after, &Ok(()));
    assert_eq!(session.warning(), Some(ErrorCode::LocalNetwork));
}
