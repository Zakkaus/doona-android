//! Where a new flow goes: a function of the inbound it arrived on and the committed mode, never of
//! an app or a uid (ARCH D24). Direct sends it straight out. Global sends it through the node the
//! `Proxy` group selects when the flow opens, dialed on that node's carriers (see `outbounds.rs`),
//! and refuses it while the group has none: a Global flow is never sent direct. A flow keeps the
//! route it opened with, so a mode or selection change applies to new flows only. doona excludes
//! its own package from the VPN, so these sockets need no `protect()`. A dial that fails through a
//! node on the local network is noted on the generation's [`Session`] (ARCH D26).

use super::Session;
use super::udp::UdpUpstream;
use crate::dto::Mode;
use crate::outbounds::{Dialer, Outbounds};
use honk_outbound::proxy::AsyncReadWrite;
use std::io::{self, ErrorKind};
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicU8, Ordering::Relaxed};
use std::time::Duration;
use tokio::net::{TcpStream, UdpSocket};

/// A dial, through a node or straight out, gives up after this long.
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
/// The group Global sends every flow through.
pub const PROXY_GROUP: &str = "g-Proxy";

/// The committed mode, read by every new flow.
#[derive(Debug)]
pub struct ModeCell(AtomicU8);

impl ModeCell {
    pub fn new(mode: Mode) -> ModeCell {
        let cell = ModeCell(AtomicU8::new(0));
        cell.set(mode);
        cell
    }

    pub fn get(&self) -> Mode {
        match self.0.load(Relaxed) {
            1 => Mode::Direct,
            _ => Mode::Global,
        }
    }

    pub fn set(&self, mode: Mode) {
        self.0.store(
            match mode {
                Mode::Global => 0,
                Mode::Direct => 1,
            },
            Relaxed,
        );
    }
}

/// Where a flow arrived. v0.1 has the tun only; inbounds split by app (D24) would be more
/// variants here, never a rule on the app itself.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Inbound {
    Tun,
}

/// A flow's way out, fixed when the flow opens.
#[derive(Clone)]
pub(crate) enum Route {
    Direct,
    /// Through this node; a failed dial to it is noted on the session when it is on the local
    /// network.
    Proxy(Arc<Dialer>, Arc<Session>),
    /// Global while the `Proxy` group has no node.
    Refuse,
}

/// An upstream byte stream: a socket, or a node's protocol over one.
pub(crate) type Upstream = Box<dyn AsyncReadWrite>;

impl Route {
    /// The route of a new flow of `session`'s generation from `inbound` under `mode`.
    pub fn of(
        inbound: Inbound,
        mode: Mode,
        outbounds: &Outbounds,
        session: &Arc<Session>,
    ) -> Route {
        match (inbound, mode) {
            (Inbound::Tun, Mode::Direct) => Route::Direct,
            (Inbound::Tun, Mode::Global) => {
                outbounds.dialer(PROXY_GROUP).map_or(Route::Refuse, |d| {
                    Route::Proxy(Arc::new(d), Arc::clone(session))
                })
            }
        }
    }

    pub fn refused(&self) -> bool {
        matches!(self, Route::Refuse)
    }

    /// Whether datagrams can take this route; through a node without UDP they are dropped, so
    /// QUIC falls back to TCP.
    pub fn carries_udp(&self) -> bool {
        match self {
            Route::Direct => true,
            Route::Proxy(d, _) => d.supports_udp(),
            Route::Refuse => false,
        }
    }

    pub async fn connect(&self, dst: SocketAddr) -> io::Result<Upstream> {
        match self {
            Route::Direct => {
                let s = within(TcpStream::connect(dst)).await??;
                // The app's own segments arrive already sized; Nagle would only add delay.
                s.set_nodelay(true)?;
                Ok(Box::new(s))
            }
            Route::Proxy(d, session) => {
                let (registry, proxies, id) =
                    (Arc::clone(&d.registry), Arc::clone(&d.proxies), d.node.id);
                let dial = async move {
                    proxies
                        .dial_runtime(registry, id, dst, None, CONNECT_TIMEOUT)
                        .await
                };
                let dialed = flatten(within(d.on_carriers(dial)).await).map(|c| c.stream);
                noted(dialed, d, session)
            }
            Route::Refuse => Err(ErrorKind::PermissionDenied.into()),
        }
    }

    /// An upstream that exchanges datagrams with `dst` only.
    pub async fn bind_udp(&self, dst: SocketAddr) -> io::Result<UdpUpstream> {
        match self {
            Route::Direct => {
                let any: SocketAddr = match dst {
                    SocketAddr::V4(_) => (Ipv4Addr::UNSPECIFIED, 0).into(),
                    SocketAddr::V6(_) => (Ipv6Addr::UNSPECIFIED, 0).into(),
                };
                let s = UdpSocket::bind(any).await?;
                s.connect(dst).await?;
                Ok(UdpUpstream::Socket(s))
            }
            Route::Proxy(d, session) => {
                let (registry, proxies, id) =
                    (Arc::clone(&d.registry), Arc::clone(&d.proxies), d.node.id);
                let dial = async move {
                    proxies
                        .dial_udp_transport_runtime(registry, id, dst, None, CONNECT_TIMEOUT)
                        .await
                };
                let transport = noted(flatten(within(d.on_carriers(dial)).await), d, session)?;
                Ok(UdpUpstream::Proxy(transport, dst))
            }
            Route::Refuse => Err(ErrorKind::PermissionDenied.into()),
        }
    }
}

/// `fut` within [`CONNECT_TIMEOUT`]: honk bounds only some steps of a dial.
async fn within<T>(fut: impl Future<Output = T>) -> io::Result<T> {
    tokio::time::timeout(CONNECT_TIMEOUT, fut)
        .await
        .map_err(|_| ErrorKind::TimedOut.into())
}

/// A dial through a node within [`within`] on its carriers, as one result. honk's error text may
/// name the server, so a failure it reports is kept only as refused.
fn flatten<T>(dialed: io::Result<io::Result<anyhow::Result<T>>>) -> io::Result<T> {
    dialed?.and_then(|r| r.map_err(|_| ErrorKind::ConnectionRefused.into()))
}

/// Notes a failed dial to a node on the local network on its generation's session.
fn noted<T>(dialed: io::Result<T>, d: &Dialer, session: &Session) -> io::Result<T> {
    if dialed.is_err() && crate::localnet::host_is_local(d.node.host()) {
        session.note_local_network();
    }
    dialed
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mode_cell_round_trips() {
        let cell = ModeCell::new(Mode::Direct);
        assert_eq!(cell.get(), Mode::Direct);
        cell.set(Mode::Global);
        assert_eq!(cell.get(), Mode::Global);
    }

    #[tokio::test]
    async fn global_without_a_node_is_refused_never_sent_direct() {
        let outbounds = Outbounds::new(Arc::default()).unwrap();
        let session = Arc::new(Session::new(1));
        let global = Route::of(Inbound::Tun, Mode::Global, &outbounds, &session);
        assert!(global.refused() && !global.carries_udp());
        let direct = Route::of(Inbound::Tun, Mode::Direct, &outbounds, &session);
        assert!(matches!(direct, Route::Direct) && direct.carries_udp());
    }
}
