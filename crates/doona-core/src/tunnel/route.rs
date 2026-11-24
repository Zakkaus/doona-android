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
use honk_outbound::group::ScoreContinuation;
use honk_outbound::proxy::AsyncReadWrite;
use std::io::{self, ErrorKind};
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use std::time::Duration;
use tokio::net::{TcpStream, UdpSocket};
use tokio::time::{Instant, timeout_at};

mod connect;

/// A dial, through a node or straight out, gives up after this long.
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

/// The committed mode, which every new flow reads first (see [`Route::of`]), and how many times it
/// was set, in one word: the low [`MODE_BITS`] hold the mode, the rest the count. A commit sets it
/// before it publishes the routing.
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
    /// The DNS session keeps its routing publication but selects for each upstream socket.
    Resolver(View, Arc<Session>),
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
    /// The route of a new flow of `session`'s generation from `inbound` to `dst` over `network`.
    /// `mode` is the mode cell's reading, taken first: Direct sends the flow straight out, which a
    /// committed setting does, as the cell is set before the routing is published. Any other
    /// reading routes by the mode and the policy the routing holds, published together, so a flow
    /// that paused across commits never pairs one commit's mode with another's policy. Called once
    /// a flow, when it opens.
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
        let mode = view.mode().unwrap_or(mode);
        if mode == Mode::Direct {
            return Route::Direct;
        }
        let policy = view.policy();
        let name = std::cell::OnceCell::new();
        let domain = || {
            name.get_or_init(|| session.names().name(dst.ip()))
                .as_deref()
        };
        let (group, rule) = if mode == Mode::Rule {
            let flow = Flow {
                domain: if policy.wants_names() { domain() } else { None },
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
        Route::through(
            view.for_flow(group, dst, network, domain, None),
            rule,
            session,
        )
    }

    /// The route of DNS the core answers for `session`'s generation: straight out in Direct, else
    /// through the main group, whatever server the query was sent to. `mode` is read as in
    /// [`Route::of`].
    pub fn dns(
        inbound: Inbound,
        mode: Mode,
        outbounds: &Outbounds,
        session: &Arc<Session>,
    ) -> Route {
        let Inbound::Tun = inbound;
        if mode == Mode::Direct {
            return Route::Direct;
        }
        let view = outbounds.view();
        match view.mode().unwrap_or(mode) {
            Mode::Direct => Route::Direct,
            Mode::Global | Mode::Rule if view.has_main_node() => {
                Route::Resolver(view, Arc::clone(session))
            }
            _ => Route::Refuse,
        }
    }

    /// Through the node `group` selects in `view`, chosen by `rule` in Rule mode; refused while
    /// the group has no node, never direct.
    fn through(dialer: Option<Dialer>, rule: Option<Arc<str>>, session: &Arc<Session>) -> Route {
        dialer.map_or(Route::Refuse, |mut d| {
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
            Route::Resolver(view, _) => Some(meters.get(view.policy().main())),
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
            Route::Resolver(..) | Route::Refuse | Route::Blocked => false,
        }
    }

    pub(crate) async fn connect_host(&self, host: &str, port: u16) -> io::Result<Upstream> {
        let Self::Proxy(d, session) = self else {
            return Err(io::ErrorKind::NotConnected.into());
        };
        let dst = ([0, 0, 0, 0], port).into();
        connect::connect(
            d,
            session,
            (dst, Some(host)),
            Instant::now() + CONNECT_TIMEOUT,
            &mut None,
        )
        .await
        .map(|(s, _)| s)
    }
    #[cfg(test)]
    pub async fn connect(&self, dst: SocketAddr) -> io::Result<Upstream> {
        self.connect_tcp(dst).await.map(|(stream, _)| stream)
    }

    pub(super) async fn connect_tcp(&self, dst: SocketAddr) -> io::Result<connect::Connected> {
        self.connect_until(dst, Instant::now() + CONNECT_TIMEOUT, &mut None)
            .await
    }

    pub(crate) async fn connect_until(
        &self,
        dst: SocketAddr,
        deadline: Instant,
        original: &mut Option<ScoreContinuation>,
    ) -> io::Result<connect::Connected> {
        match self {
            Route::Direct | Route::RuleDirect(_) => {
                let s = timeout_at(deadline, TcpStream::connect(dst)).await??;
                // The app's own segments arrive already sized; Nagle would only add delay.
                s.set_nodelay(true)?;
                Ok((Box::new(s), None))
            }
            Route::Proxy(d, session) => {
                connect::connect(d, session, (dst, None), deadline, original).await
            }
            Route::Resolver(view, session) => {
                let dialer = view
                    .for_flow(
                        view.policy().main(),
                        dst,
                        Network::Tcp,
                        || None,
                        original.as_ref(),
                    )
                    .ok_or(ErrorKind::PermissionDenied)?;
                connect::connect(&dialer, session, (dst, None), deadline, original).await
            }
            Route::Refuse | Route::Blocked => Err(ErrorKind::PermissionDenied.into()),
        }
    }

    /// An upstream that exchanges datagrams with `dst` only.
    pub async fn bind_udp(&self, dst: SocketAddr) -> io::Result<connect::Connected<UdpUpstream>> {
        match self {
            Route::Direct | Route::RuleDirect(_) => {
                let any: SocketAddr = match dst {
                    SocketAddr::V4(_) => (Ipv4Addr::UNSPECIFIED, 0).into(),
                    SocketAddr::V6(_) => (Ipv6Addr::UNSPECIFIED, 0).into(),
                };
                let s = UdpSocket::bind(any).await?;
                s.connect(dst).await?;
                Ok((UdpUpstream::Socket(s), None))
            }
            Route::Proxy(d, session) => {
                let (registry, proxies, id) =
                    (Arc::clone(&d.registry), Arc::clone(&d.proxies), d.node.id);
                let trace = Trace::default();
                let dial = async move {
                    proxies
                        .dial_udp_transport_runtime(registry, id, dst, None, CONNECT_TIMEOUT)
                        .await
                };
                let (transport, reporter) = connect::setup(
                    d,
                    session,
                    &trace,
                    Instant::now() + CONNECT_TIMEOUT,
                    |_| {},
                    dial,
                )
                .await
                .map_err(|(_, e)| e)?;
                // A transport that only bound a socket to the node has sent nothing yet: its first
                // send says whether the node's address is reachable. honk does not say where a
                // SOCKS5 node's UDP relay is (its server names it in a reply), so such a session
                // goes by the control connection's address; v0.1 leaves that limit.
                let peer = trace.peer().or_else(|| localnet::literal(d.node.host()));
                let local = peer.is_some_and(localnet::is_local);
                Ok((
                    UdpUpstream::Proxy(transport, dst, local.then(|| Arc::clone(session))),
                    reporter,
                ))
            }
            Route::Resolver(..) | Route::Refuse | Route::Blocked => {
                Err(ErrorKind::PermissionDenied.into())
            }
        }
    }
}

#[cfg(test)]
pub(crate) mod tests;
