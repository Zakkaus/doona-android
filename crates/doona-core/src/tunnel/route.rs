//! Where a new flow goes: a function of the inbound it arrived on, the committed mode and the flow's
//! destination, never of an app or a uid (ARCH D24). Direct sends it straight out. Global sends it
//! through the node the main group (`mainGroupId`, `g-Proxy` under the template) selects when the
//! flow opens, dialed on that node's carriers (see `outbounds.rs`), and refuses it while the group
//! has none: a Global flow is never sent direct. Rule matches the flow once, by the name the
//! generation's DNS answers gave its address, its address, port and transport, and takes the first
//! matching rule's target: direct, reject, or a group, refused while that group has no node and
//! never sent direct instead (rule-mode.md decision 9). DNS the core answers goes through the main
//! group in Rule as in Global (decision 10).
//!
//! A flow keeps the route it opened with, so a mode or selection change applies to new flows only.
//! doona excludes its own package from the VPN, so these sockets need no `protect()`. What a dial
//! through a node showed on its way to the node goes to the generation's [`Session`] (ARCH D26):
//! one refused at an address on the local network warns, one that came up there clears the
//! warning.

use super::Session;
use super::udp::UdpUpstream;
use crate::dto::Mode;
use crate::localnet::{self, Trace};
use crate::outbounds::{Dialer, Outbounds, View};
use crate::rules::policy::Choice;
use crate::rules::{Flow, Network};
use crate::stats::meter::{DIRECT, Meter};
use honk_outbound::proxy::AsyncReadWrite;
use std::io::{self, ErrorKind};
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use std::time::Duration;
use tokio::net::{TcpStream, UdpSocket};

/// A dial, through a node or straight out, gives up after this long.
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

/// The committed mode, read by every new flow, and how many times it was set, in one word: the
/// low [`MODE_BITS`] hold the mode, the rest the count.
#[derive(Debug)]
pub struct ModeCell(AtomicU64);

const MODE_BITS: u32 = 2;

impl ModeCell {
    pub fn new(mode: Mode) -> ModeCell {
        let cell = ModeCell(AtomicU64::new(0));
        cell.set(mode);
        cell
    }

    pub fn get(&self) -> Mode {
        self.snapshot().0
    }

    pub fn set(&self, mode: Mode) {
        let code = match mode {
            Mode::Global => 0,
            Mode::Direct => 1,
            Mode::Rule => 2,
        };
        let next = |word: u64| (((word >> MODE_BITS) + 1) << MODE_BITS) | code;
        self.0.update(Relaxed, Relaxed, next);
    }

    /// The mode and how many times it was set, from one read: a decision taken under the mode can
    /// tell it was set since, and never pairs one set's mode with another's count.
    pub fn snapshot(&self) -> (Mode, u64) {
        let word = self.0.load(Relaxed);
        let mode = match word & ((1 << MODE_BITS) - 1) {
            1 => Mode::Direct,
            2 => Mode::Rule,
            _ => Mode::Global,
        };
        (mode, word >> MODE_BITS)
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
    /// Direct by this rule in Rule mode: as `Direct`, metered under the rule as well.
    RuleDirect(Arc<str>),
    /// Through this node; what each dial showed on its way to the node goes to the session.
    Proxy(Arc<Dialer>, Arc<Session>),
    /// A rule's reject; Global or a rule's group while it has no node. TCP is reset, datagrams
    /// are dropped.
    Refuse,
    /// QUIC bound for a group, which `blockQuic` refuses: as `Refuse`, and the driver refuses the
    /// same five-tuple again for a while without routing it (see `blocked.rs`).
    Blocked,
}

/// An upstream byte stream: a socket, or a node's protocol over one.
pub(crate) type Upstream = Box<dyn AsyncReadWrite>;

impl Route {
    /// The route of a new flow of `session`'s generation from `inbound` to `dst` over `network`,
    /// under `mode`. Called once a flow, when it opens.
    pub fn of(
        inbound: Inbound,
        (dst, network): (SocketAddr, Network),
        mode: Mode,
        outbounds: &Outbounds,
        session: &Arc<Session>,
    ) -> Route {
        let Inbound::Tun = inbound;
        // Direct reads no routing, so it never waits for a catalog swap that holds the table.
        if mode == Mode::Direct {
            return Route::Direct;
        }
        let view = outbounds.view();
        let policy = view.policy();
        let (group, rule) = if mode == Mode::Rule {
            // A name is looked up only when a rule could match it.
            let name = (policy.wants_names())
                .then(|| session.names().name(dst.ip()))
                .flatten();
            let flow = Flow {
                domain: name.as_deref(),
                ip: dst.ip(),
                port: dst.port(),
                network,
            };
            match policy.decide(&flow) {
                (Choice::Direct, rule) => return Route::RuleDirect(rule),
                (Choice::Reject, _) => return Route::Refuse,
                (Choice::Blocked, _) => return Route::Blocked,
                (Choice::Group(group), rule) => (group, Some(rule)),
            }
        } else {
            (policy.main(), None)
        };
        Route::through(&view, group, rule, session)
    }

    /// The route of DNS the core answers for `session`'s generation: straight out in Direct, else
    /// through the main group, whatever server the query was sent to.
    pub fn dns(
        inbound: Inbound,
        mode: Mode,
        outbounds: &Outbounds,
        session: &Arc<Session>,
    ) -> Route {
        let Inbound::Tun = inbound;
        match mode {
            Mode::Direct => Route::Direct,
            Mode::Global | Mode::Rule => {
                let view = outbounds.view();
                Route::through(&view, view.policy().main(), None, session)
            }
        }
    }

    /// Through the node `group` selects in `view`, chosen by `rule` in Rule mode; refused while
    /// the group has no node, never direct.
    fn through(
        view: &View<'_>,
        group: &str,
        rule: Option<Arc<str>>,
        session: &Arc<Session>,
    ) -> Route {
        view.dialer(group).map_or(Route::Refuse, |mut d| {
            d.rule = rule;
            Route::Proxy(Arc::new(d), Arc::clone(session))
        })
    }

    pub fn refused(&self) -> bool {
        matches!(self, Route::Refuse | Route::Blocked)
    }

    /// The meter of this route's target in `session`, the group it goes through or `direct`,
    /// and of the rule that chose it in Rule mode.
    pub fn meter(&self, session: &Session) -> Option<Arc<Meter>> {
        let meters = session.meters();
        match self {
            Route::Direct => Some(meters.get(DIRECT)),
            Route::RuleDirect(rule) => Some(meters.get_ruled(DIRECT, rule)),
            Route::Proxy(d, _) => Some(match &d.rule {
                Some(rule) => meters.get_ruled(&d.group, rule),
                None => meters.get(&d.group),
            }),
            Route::Refuse | Route::Blocked => None,
        }
    }

    /// Whether datagrams can take this route; through a node without UDP they are dropped, so
    /// QUIC falls back to TCP.
    pub fn carries_udp(&self) -> bool {
        match self {
            Route::Direct | Route::RuleDirect(_) => true,
            Route::Proxy(d, _) => d.supports_udp(),
            Route::Refuse | Route::Blocked => false,
        }
    }

    pub async fn connect(&self, dst: SocketAddr) -> io::Result<Upstream> {
        match self {
            Route::Direct | Route::RuleDirect(_) => {
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
            Route::Refuse | Route::Blocked => Err(ErrorKind::PermissionDenied.into()),
        }
    }

    /// An upstream that exchanges datagrams with `dst` only.
    pub async fn bind_udp(&self, dst: SocketAddr) -> io::Result<UdpUpstream> {
        match self {
            Route::Direct | Route::RuleDirect(_) => {
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
            Route::Refuse | Route::Blocked => Err(ErrorKind::PermissionDenied.into()),
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
pub(crate) mod tests;
