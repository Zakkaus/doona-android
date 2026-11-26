//! Where a new flow goes: a function of the inbound it arrived on, the committed mode and the flow's
//! five-tuple, never of an app or a uid (ARCH D24). honk's engine decides every captured flow (see
//! `honkconf/runtime.rs`): its rules, fallback, `must` and `block`, and the mode on top of them.
//! Without a committed configuration only Direct routes, straight out. DNS the core answers is
//! decided the same way for each upstream it dials, as a TCP flow to that upstream's port 53.
//!
//! Which node a group or a node target dials is still the catalog's, through one bridge
//! ([`dial`]): a group by its name, a node by its honk endpoint. honk item 03b's dial plan and
//! lane c7's projection replace that bridge; a target the catalog does not hold is refused,
//! never sent direct instead.
//!
//! A flow keeps the route it opened with, so a mode or selection change applies to new flows only.
//! doona excludes its own package from the VPN, so these sockets need no `protect()`. What a dial
//! through a node showed on its way to the node goes to the generation's [`Session`] (ARCH D26):
//! one refused at an address on the local network warns, one that came up there clears the
//! warning.

use super::Session;
use super::udp::UdpUpstream;
use crate::dto::Mode;
use crate::dto::Network;
use crate::honkconf::runtime::{self, Flow, Runtime, Target};
use crate::ids;
use crate::localnet::{self, Trace};
use crate::outbounds::{Dialer, Outbounds, View};
use crate::stats::meter::{DIRECT, Meter};
use honk_outbound::group::ScoreContinuation;
use honk_outbound::proxy::AsyncReadWrite;
use std::io::{self, ErrorKind};
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV4};
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
    /// Direct by a rule or the fallback: as `Direct`, metered under that row's meter as well.
    RuleDirect(Arc<str>),
    /// Through this node; what each dial showed on its way to the node goes to the session.
    Proxy(Arc<Dialer>, Arc<Session>),
    /// DNS the core answers: each upstream dial is decided as a flow to that upstream.
    Resolver(Arc<Resolver>),
    /// honk's refusal (`block`, a marked fallback, GLOBAL with no target), a target the catalog
    /// has no node for, or no configuration outside Direct. TCP is reset, datagrams are dropped, and the driver refuses the same
    /// five-tuple again for a while without deciding it (see `blocked.rs`).
    Refuse,
}

/// An upstream byte stream: a socket, or a node's protocol over one.
pub(crate) type Upstream = Box<dyn AsyncReadWrite>;

/// What DNS the core answers routes by: the mode and engine it was asked under and the routing
/// publication, kept for each upstream the query is sent to.
pub(crate) struct Resolver {
    mode: Mode,
    engine: Option<Arc<Runtime>>,
    view: View,
    session: Arc<Session>,
}

/// The source of a flow the core opens itself, such as a DNS upstream dial.
const CORE: SocketAddr = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0));

impl Resolver {
    fn route(&self, dst: SocketAddr, original: Option<&ScoreContinuation>) -> Route {
        let flow = (CORE, dst, Network::Tcp);
        let engine = self.engine.as_deref();
        let view = || self.view.clone();
        decided(engine, self.mode, flow, view, &self.session, original)
    }
}

impl Route {
    /// The route of a new flow of `session`'s generation from `inbound`, `src` to `dst` over
    /// `network`, as `engine` decides it under `mode`, the mode cell's reading. The domain is the
    /// name the generation's DNS answers gave `dst`, so it resolves to `dst` by construction.
    /// No mode short-cuts the engine: `must` and `block` hold in Direct and Global too. Called
    /// once a flow, when it opens.
    pub fn of(
        inbound: Inbound,
        flow: (SocketAddr, SocketAddr, Network),
        mode: Mode,
        engine: Option<&Runtime>,
        outbounds: &Outbounds,
        session: &Arc<Session>,
    ) -> Route {
        let Inbound::Tun = inbound;
        // The publication is read only to dial: Direct does not wait for a swap.
        decided(engine, mode, flow, || outbounds.view(), session, None)
    }

    /// The route of DNS the core answers for `session`'s generation: each of `upstreams` it
    /// dials is decided by `engine` under `mode` as a TCP flow to that upstream, whatever server
    /// the query was sent to (rule-mode.md decision 10 until lane c9's DNS hookup). Refused up
    /// front when there are upstreams and every one is; with none, each query fails on its own.
    pub fn dns(
        inbound: Inbound,
        (mode, engine): (Mode, Option<Arc<Runtime>>),
        upstreams: &[SocketAddr],
        outbounds: &Outbounds,
        session: &Arc<Session>,
    ) -> Route {
        let Inbound::Tun = inbound;
        let resolver = Resolver {
            mode,
            engine,
            view: outbounds.view(),
            session: Arc::clone(session),
        };
        let refused = |&u: &SocketAddr| resolver.route(u, None).refused();
        if !upstreams.is_empty() && upstreams.iter().all(refused) {
            return Route::Refuse;
        }
        Route::Resolver(Arc::new(resolver))
    }

    /// Through `dialer`, charging `rule`'s meter as well; refused without a dialer, never direct.
    fn through(dialer: Option<Dialer>, rule: Option<Arc<str>>, session: &Arc<Session>) -> Route {
        dialer.map_or(Route::Refuse, |mut d| {
            d.rule = rule;
            Route::Proxy(Arc::new(d), Arc::clone(session))
        })
    }

    pub fn refused(&self) -> bool {
        matches!(self, Route::Refuse)
    }

    /// The meter of this route's target in `session`, the group or node it goes through or
    /// `direct`, and of the row that chose it. DNS the core answers is not metered here.
    pub fn meter(&self, session: &Session) -> Option<Arc<Meter>> {
        let meters = session.meters();
        match self {
            Route::Direct => Some(meters.get(DIRECT)),
            Route::RuleDirect(rule) => Some(meters.get_ruled(DIRECT, rule)),
            Route::Proxy(d, _) => Some(match &d.rule {
                Some(rule) => meters.get_ruled(&d.group, rule),
                None => meters.get(&d.group),
            }),
            Route::Resolver(_) | Route::Refuse => None,
        }
    }

    /// Whether datagrams can take this route; through a node without UDP they are dropped, so
    /// QUIC falls back to TCP.
    pub fn carries_udp(&self) -> bool {
        match self {
            Route::Direct | Route::RuleDirect(_) => true,
            Route::Proxy(d, _) => d.supports_udp(),
            Route::Resolver(..) | Route::Refuse => false,
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
        let decided;
        let route = match self {
            Route::Resolver(r) => {
                decided = r.route(dst, original.as_ref());
                &decided
            }
            route => route,
        };
        match route {
            Route::Direct | Route::RuleDirect(_) => {
                let s = timeout_at(deadline, TcpStream::connect(dst)).await??;
                // The app's own segments arrive already sized; Nagle would only add delay.
                s.set_nodelay(true)?;
                Ok((Box::new(s), None))
            }
            Route::Proxy(d, session) => {
                connect::connect(d, session, (dst, None), deadline, original).await
            }
            Route::Resolver(_) | Route::Refuse => Err(ErrorKind::PermissionDenied.into()),
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
            Route::Resolver(..) | Route::Refuse => Err(ErrorKind::PermissionDenied.into()),
        }
    }
}

/// The route `engine` gives a flow under `mode`, dialed through the publication `view` reads;
/// refused outside Direct without an engine.
fn decided(
    engine: Option<&Runtime>,
    mode: Mode,
    (src, dst, network): (SocketAddr, SocketAddr, Network),
    view: impl FnOnce() -> View,
    session: &Arc<Session>,
    original: Option<&ScoreContinuation>,
) -> Route {
    let Some(engine) = engine else {
        return match mode {
            Mode::Direct => Route::Direct,
            Mode::Rule | Mode::Global => Route::Refuse,
        };
    };
    let domain = session.names().name(dst.ip());
    let flow = Flow {
        network: match network {
            Network::Tcp => runtime::Network::Tcp,
            Network::Udp => runtime::Network::Udp,
        },
        source: src,
        destination: dst,
        domain: domain.as_deref().map(str::to_owned),
        domain_verified: true,
        process_name: None,
        mac: None,
        dscp: None,
    };
    let mode = match mode {
        Mode::Rule => runtime::Mode::Rule,
        Mode::Global => runtime::Mode::Global,
        Mode::Direct => runtime::Mode::Direct,
    };
    let verdict = engine.decide_in(mode, &flow);
    let meter = verdict.row.and_then(|row| row.meter);
    match verdict.target {
        Target::Direct => meter.map_or(Route::Direct, Route::RuleDirect),
        Target::Refuse => Route::Refuse,
        target => {
            let dialer = dial(
                &view(),
                &target,
                (dst, network),
                domain.as_deref(),
                original,
            );
            Route::through(dialer, meter, session)
        }
    }
}

/// The catalog's dialer for honk's group or node `target`: the one place honk's targets meet the
/// catalog. A group dials the node the publication selects for `g-<name>`, a node the catalog
/// node with that honk endpoint. Waits on honk item 03b (group choice and dial plan in honk) and
/// lane c7 (catalog ids from the projection); `None` for anything else.
pub(crate) fn dial(
    view: &View,
    target: &Target,
    (dst, network): (SocketAddr, Network),
    domain: Option<&str>,
    original: Option<&ScoreContinuation>,
) -> Option<Dialer> {
    match target {
        Target::Group(name) => {
            view.for_flow(&ids::group_id(name), dst, network, || domain, original)
        }
        Target::Node(endpoint) => view.node(*endpoint),
        Target::Direct | Target::Refuse => None,
    }
}

#[cfg(test)]
pub(crate) mod tests;
