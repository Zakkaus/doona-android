//! Which addresses Android counts as the local network (ARCH D26). From Android 17 an app that
//! targets SDK 37 reaches them only with `ACCESS_LOCAL_NETWORK`; without it a TCP connect times
//! out and a UDP send fails with `EPERM`, so a refused dial looks like any unreachable server. A
//! dial that failed on its way to one of these addresses is reported as `local_network`, so the app
//! asks for the permission only then.
//!
//! The platform decides per interface (AOSP Connectivity, `ConnectivityService`
//! `updateLocalNetworkAddresses` with `NetworkStackConstants.IPV4_LOCAL_PREFIXES` and
//! `MULTICAST_AND_BROADCAST_PREFIXES`): an IPv4 range below that holds one of the interface's own
//! addresses, each on-link IPv6 prefix of the interface, and multicast and broadcast. Loopback has
//! no network of its own and is never blocked. The core does not follow interfaces, so it counts
//! every IPv4 range on that list, CGNAT `100.64.0.0/10` included (a carrier that numbers the phone
//! from it makes the whole range local), and the IPv6 ranges that are on-link wherever they appear:
//! link-local and unique local. A global IPv6 address in the phone's own /64 is not counted. The
//! code is reported only after a dial failed, so counting too much costs at most one question.
//!
//! A failure is classified by the address the failing hop dialed, never by a host name or by what
//! a name resolved to for another dial. The subscription fetch dials its addresses itself; a dial
//! of the data path carries a [`Trace`] of the node addresses honk chose and of the transport to
//! the node, if one came up. honk keeps its latency probes out of that reporting, so a probe
//! carries the [`Lookups`] the core made for it instead, and counts its node as local only when
//! every address it could have dialed for it is: the literal, or each answer this probe's own
//! lookup of the node's name gave.

use crate::dto::ErrorCode;
use honk_outbound::runtime::flow_observation::{
    FlowContext, FlowEvent, FlowObserver, TransportStatus,
};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::sync::{Arc, Mutex, MutexGuard};

tokio::task_local! {
    /// The probe the core's lookups serve, while it runs.
    static PROBE: Lookups;
}

/// Whether Android may count `ip` as an address on the local network.
pub(crate) fn is_local(ip: IpAddr) -> bool {
    match ip.to_canonical() {
        IpAddr::V4(a) => v4(a),
        IpAddr::V6(a) => v6(a),
    }
}

fn v4(a: Ipv4Addr) -> bool {
    let [first, second, ..] = a.octets();
    let cgnat = first == 100 && second & 0xc0 == 64;
    a.is_private() || a.is_link_local() || cgnat || a.is_multicast() || a.is_broadcast() || lan(a)
}

fn v6(a: Ipv6Addr) -> bool {
    a.is_unique_local() || a.is_unicast_link_local() || a.is_multicast()
}

/// In unit tests `127.77.0.0/16` plays a local network the host reaches: loopback is never local,
/// and no other range lets a test serve a node on the local network.
#[cfg(test)]
fn lan(a: Ipv4Addr) -> bool {
    matches!(a.octets(), [127, 77, _, _])
}

#[cfg(not(test))]
fn lan(_: Ipv4Addr) -> bool {
    false
}

/// The address `host` names when it is an address literal, bracketed or not.
pub(crate) fn literal(host: &str) -> Option<IpAddr> {
    host.trim_start_matches('[')
        .trim_end_matches(']')
        .parse()
        .ok()
}

/// What honk reports a traced dial's events under; nothing reads the ids.
const CONTEXT: FlowContext = FlowContext {
    flow_id: uuid::Uuid::nil(),
    generation: 0,
    attempt_id: None,
    lookup_id: None,
    dns_purpose: "node",
};

/// What one dial through a node did on its way to the node, as honk's flow observation reports
/// it: each node address it chose (a connect it started, or the address it took from a lookup of
/// the node's name) and the address a transport to the node came up on, if one did. A lookup's own
/// connections are the resolver's and are left out.
#[derive(Clone, Default)]
pub(crate) struct Trace(Arc<Mutex<Seen>>);

#[derive(Default)]
struct Seen {
    /// Whether an address it chose for the node, any of them, is local.
    chose_local: bool,
    /// The node address it chose last.
    last: Option<IpAddr>,
    /// `Some` once a transport to the node came up (connected, or an existing one reused), with
    /// its address when honk knows it.
    reached: Option<Option<IpAddr>>,
}

impl Trace {
    /// `dial` with honk reporting to this trace what it tries. Events of a dial cut short still
    /// count: the trace outlives it.
    pub fn scope<F: Future>(&self, dial: F) -> impl Future<Output = F::Output> + use<F> {
        let seen = Arc::clone(&self.0);
        let observer = FlowObserver::new(
            CONTEXT,
            Arc::new(move |context: FlowContext, event: FlowEvent| {
                // Inside a lookup: the resolver's own TCP connection, not the node's.
                if context.lookup_id.is_none() {
                    lock(&seen).record(&event);
                }
            }),
        );
        observer.scope(dial)
    }

    /// Whether the dial stopped on its way to the node at an address on the local network: no
    /// transport to the node came up, and an address it chose for the node is local. A dial that
    /// failed before it chose one, as when the node's name did not resolve, did not.
    pub fn refused_locally(&self) -> bool {
        let seen = lock(&self.0);
        seen.reached.is_none() && seen.chose_local
    }

    /// Whether a transport to the node came up on an address on the local network.
    pub fn reached_locally(&self) -> bool {
        matches!(lock(&self.0).reached, Some(Some(ip)) if is_local(ip))
    }

    /// The node address the dial's transport sends to: the one it came up on, or else the last
    /// one chosen, as for a UDP transport that only binds a socket to it.
    pub fn peer(&self) -> Option<IpAddr> {
        let seen = lock(&self.0);
        seen.reached.flatten().or(seen.last)
    }

    pub fn reached_peer(&self) -> Option<IpAddr> {
        lock(&self.0).reached.flatten()
    }

    /// Records `event` as honk reports it; for tests that stand in for honk.
    #[cfg(test)]
    pub fn record(&self, event: &FlowEvent) {
        lock(&self.0).record(event);
    }
}

impl Seen {
    fn record(&mut self, event: &FlowEvent) {
        match event {
            FlowEvent::Transport {
                server_addr,
                status,
                ..
            } => {
                let ip = server_addr.map(|a| a.ip());
                if let Some(ip) = ip {
                    self.choose(ip);
                }
                if *status == TransportStatus::Succeeded {
                    self.reach(ip);
                }
            }
            FlowEvent::TransportAttached { server_addr, .. } => {
                self.reach(server_addr.map(|a| a.ip()));
            }
            // The address honk took from a lookup of the node's name.
            FlowEvent::Dns(lookup) => {
                if let Some(ip) = lookup.selected_ip {
                    self.choose(ip);
                }
            }
            _ => {}
        }
    }

    fn choose(&mut self, ip: IpAddr) {
        self.chose_local |= is_local(ip);
        self.last = Some(ip);
    }

    /// A transport to the node came up, at `ip` when honk says. honk reports a session it shares,
    /// as AnyTLS does, with no address, also right after the connect that opened it came up at
    /// one: that address stays known.
    fn reach(&mut self, ip: Option<IpAddr>) {
        self.reached = Some(ip.or(self.reached.flatten()));
    }
}

/// What the core's own lookups gave one latency probe: whether its target resolved, after which
/// the probe dials its node, and what the node's name resolved to.
#[derive(Clone, Default)]
pub(crate) struct Lookups(Arc<Mutex<Looked>>);

#[derive(Default)]
struct Looked {
    target: bool,
    /// Whether every answer the node's name resolved to is local; `None` before the first.
    node: Option<bool>,
    /// A lookup started but did not return any addresses before the probe ended.
    unresolved: bool,
}

impl Lookups {
    /// `probe` with the core's lookups for it recorded here; they still count when it is cut short.
    pub fn scope<F: Future>(&self, probe: F) -> impl Future<Output = F::Output> {
        PROBE.scope(self.clone(), probe)
    }

    /// Why a failed probe stopped, if it stalled in DNS or only had local addresses to dial.
    pub fn failure(&self, host: &str) -> Option<ErrorCode> {
        let looked = lock(&self.0);
        if looked.unresolved {
            return Some(ErrorCode::DnsFailed);
        }
        let local = match literal(host) {
            Some(ip) => is_local(ip),
            None => looked.node == Some(true),
        };
        (looked.target && local).then_some(ErrorCode::LocalNetwork)
    }
}

pub(crate) fn lookup_started() {
    let _ = PROBE.try_with(|probe| lock(&probe.0).unresolved = true);
}

/// Records what a lookup the core made for a running probe gave: the probe's own `target`, or its
/// node's name.
pub(crate) fn looked_up(target: bool, ips: &[IpAddr]) {
    let _ = PROBE.try_with(|probe| {
        let mut looked = lock(&probe.0);
        looked.unresolved = ips.is_empty();
        if looked.unresolved {
            return;
        }
        if target {
            looked.target = true;
        } else {
            let all = ips.iter().all(|&ip| is_local(ip));
            looked.node = Some(looked.node.unwrap_or(true) && all);
        }
    });
}

/// A poisoned lock still holds what was recorded: every update only assigns or appends.
fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

#[cfg(test)]
mod tests;
