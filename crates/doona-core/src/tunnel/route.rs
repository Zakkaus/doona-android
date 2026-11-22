//! Where a new flow goes: a function of the inbound it arrived on and the committed mode, never of
//! an app or a uid (ARCH D24). Direct sends it straight out. Global sends it through the node the
//! main group (`mainGroupId`, `g-Proxy` under the template) selects when the flow opens, dialed on
//! that node's carriers (see `outbounds.rs`), and refuses it while the group has none: a Global
//! flow is never sent direct. A flow keeps the
//! route it opened with, so a mode or selection change applies to new flows only. doona excludes
//! its own package from the VPN, so these sockets need no `protect()`. What a dial through a node
//! showed on its way to the node goes to the generation's [`Session`] (ARCH D26): one refused at an
//! address on the local network warns, one that came up there clears the warning.

use super::Session;
use super::udp::UdpUpstream;
use crate::dto::Mode;
use crate::localnet::{self, Trace};
use crate::outbounds::{Dialer, Outbounds, View};
use crate::stats::meter::{DIRECT, Meter};
use honk_outbound::proxy::AsyncReadWrite;
use std::io::{self, ErrorKind};
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicU8, Ordering::Relaxed};
use std::time::Duration;
use tokio::net::{TcpStream, UdpSocket};

/// A dial, through a node or straight out, gives up after this long.
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

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
    /// Through this node; what each dial showed on its way to the node goes to the session.
    Proxy(Arc<Dialer>, Arc<Session>),
    /// Global while the main group has no node.
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
                let view = outbounds.view();
                Route::through(&view, view.policy().main(), session)
            }
        }
    }

    /// Through the node `group` selects in `view`; refused while it has none, never direct.
    fn through(view: &View<'_>, group: &str, session: &Arc<Session>) -> Route {
        view.dialer(group).map_or(Route::Refuse, |d| {
            Route::Proxy(Arc::new(d), Arc::clone(session))
        })
    }

    pub fn refused(&self) -> bool {
        matches!(self, Route::Refuse)
    }

    /// The meter of this route's target in `session`: the group it goes through, or `direct`.
    pub fn meter(&self, session: &Session) -> Option<Arc<Meter>> {
        match self {
            Route::Direct => Some(session.meters().get(DIRECT)),
            Route::Proxy(d, _) => Some(session.meters().get(&d.group)),
            Route::Refuse => None,
        }
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
                let (trace, began) = (Trace::default(), session.begin_dial());
                let dial = trace.scope(async move {
                    proxies
                        .dial_runtime(registry, id, dst, None, CONNECT_TIMEOUT)
                        .await
                });
                let dialed = flatten(within(d.on_carriers(dial)).await).map(|c| c.stream);
                session.dialed(began, &trace, dialed.is_ok());
                dialed
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
                let (trace, began) = (Trace::default(), session.begin_dial());
                let dial = trace.scope(async move {
                    proxies
                        .dial_udp_transport_runtime(registry, id, dst, None, CONNECT_TIMEOUT)
                        .await
                });
                let dialed = flatten(within(d.on_carriers(dial)).await);
                session.dialed(began, &trace, dialed.is_ok());
                // A transport that only bound a socket to the node has sent nothing yet: its first
                // send says whether the node's address is reachable. honk does not say where a
                // SOCKS5 node's UDP relay is (its server names it in a reply), so such a session
                // goes by the control connection's address; v0.1 leaves that limit.
                let peer = trace.peer().or_else(|| localnet::literal(d.node.host()));
                let local = peer.is_some_and(localnet::is_local);
                Ok(UdpUpstream::Proxy(
                    dialed?,
                    dst,
                    local.then(|| Arc::clone(session)),
                ))
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
        let session = Arc::new(Session::new(1, std::sync::Weak::new()));
        let global = Route::of(Inbound::Tun, Mode::Global, &outbounds, &session);
        assert!(global.refused() && !global.carries_udp());
        let direct = Route::of(Inbound::Tun, Mode::Direct, &outbounds, &session);
        assert!(matches!(direct, Route::Direct) && direct.carries_udp());
    }
}
