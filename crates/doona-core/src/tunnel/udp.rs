//! UDP through the route: one association per (client, destination) pair, served by a task with
//! its own upstream (a socket, or a node's packet transport) and ended after an idle time without
//! traffic ([`IDLE`] in the app). The route is chosen when the association opens and kept: a route that carries no UDP
//! opens none, so the datagram is dropped. Memory stays bounded for a phone: every queued
//! datagram holds part of one [`Budget`] per generation, taken before the copy; sockets read into
//! one buffer per worker thread, and a node's transport into its association's own. Datagrams are
//! dropped, as a network would, when the budget or a queue is full or too many associations are
//! open. Android 17 refuses a datagram to a node on the local network with `EPERM` while doona
//! lacks the permission, so the first send through such a node reports how it went (ARCH D26).
//!
//! Score counts an association once. At idle, a peer reply counts as success; a locally lost
//! reply or an uplink dropped by the shared budget excuses silence, not a failed send.
//! A packet-local congestion timeout drops only that datagram. A stalled QUIC path ends the flow.

mod exchange;
use exchange::exchange;
#[cfg(test)]
mod tests_score;

use super::Session;
use super::flow::{DEPTH, Event, Events, Key, Waker};
use super::route::Route;
use crate::dns;
use crate::dns::names::Learned;
use crate::rules::Network;
use crate::stats::meter::{Meter, Tally};
use bytes::Bytes;
use honk_outbound::proxy::PacketTransport;
use std::cell::RefCell;
use std::collections::HashMap;
use std::io::{self, ErrorKind};
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{
    AtomicBool, AtomicUsize,
    Ordering::{AcqRel, Acquire, Relaxed},
};
use std::time::Duration;
use tokio::net::UdpSocket;
use tokio::sync::{OwnedSemaphorePermit, mpsc};

/// An association with no datagram either way for this long ends, unless the core's options set
/// another time.
pub(crate) const IDLE: Duration = Duration::from_secs(60);
/// Bytes of datagrams queued between the driver and the associations, both ways, per generation.
pub(crate) const QUEUED_BYTES: usize = 4 << 20;
/// Open associations at most; a new pair beyond it is dropped.
const MAX_ASSOCIATIONS: usize = 1024;
/// The largest datagram an upstream reads.
const DATAGRAM: usize = 65535;

thread_local! {
    /// Where a socket reads a datagram before it is copied out: one per worker thread, not one
    /// per association.
    static RECEIVED: RefCell<Vec<u8>> = RefCell::new(vec![0; DATAGRAM]);
}

/// A generation's allowance of queued UDP bytes.
pub(crate) struct Budget {
    used: AtomicUsize,
    limit: usize,
}

impl Budget {
    pub fn new(limit: usize) -> Arc<Budget> {
        Arc::new(Budget {
            used: AtomicUsize::new(0),
            limit,
        })
    }

    /// Takes `n` bytes, or none when they do not fit.
    fn take(self: &Arc<Budget>, n: usize) -> Option<Charge> {
        self.used
            .try_update(AcqRel, Acquire, |used| {
                used.checked_add(n).filter(|&total| total <= self.limit)
            })
            .ok()?;
        Some(Charge {
            budget: Arc::clone(self),
            n,
        })
    }
}

/// Bytes taken from a [`Budget`]; dropping it gives them back.
struct Charge {
    budget: Arc<Budget>,
    n: usize,
}

impl Drop for Charge {
    fn drop(&mut self) {
        self.budget.used.fetch_sub(self.n, AcqRel);
    }
}

/// A queued datagram, holding its share of the budget until it is sent or dropped.
pub(crate) struct Datagram {
    pub bytes: Bytes,
    _charge: Charge,
}

impl Datagram {
    /// Copies `payload` if the budget has room for it.
    fn copy(payload: &[u8], budget: &Arc<Budget>) -> Option<Datagram> {
        let charge = budget.take(payload.len())?;
        Some(Datagram {
            bytes: Bytes::copy_from_slice(payload),
            _charge: charge,
        })
    }
}

pub(super) struct Queues {
    pub waker: Waker,
    local_drop: AtomicBool,
}

/// The driver's side of one association.
pub(crate) struct Association {
    pub client: SocketAddr,
    pub remote: SocketAddr,
    pub recent: Option<super::recent::Handle>,
    up: mpsc::Sender<Datagram>,
    pub down: mpsc::Receiver<Datagram>,
    pub queues: Arc<Queues>,
}

/// The driver's associations, by id and by address pair.
pub(crate) struct Nat {
    by_pair: HashMap<(SocketAddr, SocketAddr), u64>,
    pub flows: HashMap<u64, Association>,
    next: u64,
    budget: Arc<Budget>,
    idle: Duration,
}

/// What a new association's task needs; the driver spawns it.
pub(crate) struct Opened {
    pub id: u64,
    pub up: mpsc::Receiver<Datagram>,
    pub down: mpsc::Sender<Datagram>,
    queues: Arc<Queues>,
    budget: Arc<Budget>,
    idle: Duration,
}

impl Nat {
    /// Associations that end after `idle` without traffic.
    pub fn new(budget: Arc<Budget>, idle: Duration) -> Nat {
        Nat {
            by_pair: HashMap::new(),
            flows: HashMap::new(),
            next: 0,
            budget,
            idle,
        }
    }

    pub fn len(&self) -> usize {
        self.flows.len()
    }

    /// Queues `payload` from `client` to `remote`. When the pair has no association yet, `route`
    /// gives the new one's route, or none to drop the datagram; the parts of the task to spawn
    /// are returned with it.
    pub fn send(
        &mut self,
        (client, remote): (SocketAddr, SocketAddr),
        payload: &[u8],
        events: &Events,
        route: impl FnOnce() -> Option<Route>,
    ) -> Option<(Opened, Route)> {
        if let Some(id) = self.by_pair.get(&(client, remote)) {
            let flow = &self.flows[id];
            if let Ok(slot) = flow.up.try_reserve() {
                if let Some(d) = Datagram::copy(payload, &self.budget) {
                    slot.send(d);
                } else {
                    flow.queues.local_drop.store(true, Relaxed);
                }
            }
            return None;
        }
        if self.flows.len() >= MAX_ASSOCIATIONS {
            return None;
        }
        let route = route()?;
        let first = Datagram::copy(payload, &self.budget)?;
        Some((self.open(client, remote, events, first), route))
    }

    fn open(
        &mut self,
        client: SocketAddr,
        remote: SocketAddr,
        events: &Events,
        first: Datagram,
    ) -> Opened {
        self.next += 1;
        let id = self.next;
        let (up_tx, up_rx) = mpsc::channel(DEPTH * 8);
        up_tx.try_send(first).expect("new association has room");
        let (down_tx, down_rx) = mpsc::channel(DEPTH * 8);
        let queues = Arc::new(Queues {
            waker: Waker::for_key(Key::Udp(id), events.clone()),
            local_drop: AtomicBool::new(false),
        });
        self.by_pair.insert((client, remote), id);
        self.flows.insert(
            id,
            Association {
                client,
                remote,
                recent: None,
                up: up_tx,
                down: down_rx,
                queues: Arc::clone(&queues),
            },
        );
        Opened {
            id,
            up: up_rx,
            down: down_tx,
            queues,
            budget: Arc::clone(&self.budget),
            idle: self.idle,
        }
    }

    pub fn remove(&mut self, id: u64) {
        if let Some(flow) = self.flows.remove(&id) {
            self.by_pair.remove(&(flow.client, flow.remote));
        }
    }
}

/// Where an association's datagrams go.
pub(crate) enum UdpUpstream {
    /// A socket connected to the destination.
    Socket(UdpSocket),
    /// A node's transport to the destination, which it names. The node relays a reply from any
    /// peer; one from another than the destination is dropped, as a connected socket drops it.
    /// The session hears how the first send went when the node is on the local network.
    Proxy(Arc<dyn PacketTransport>, SocketAddr, Option<Arc<Session>>),
}

impl UdpUpstream {
    async fn send(&self, payload: &[u8]) -> io::Result<()> {
        match self {
            UdpUpstream::Socket(s) => s.send(payload).await.map(drop),
            UdpUpstream::Proxy(t, ..) => t.send_packet(payload).await,
        }
    }

    /// Who hears how the first send went: the session, through a node on the local network.
    pub(crate) fn first_send_to(&self) -> Option<&Session> {
        match self {
            UdpUpstream::Proxy(_, _, session) => session.as_deref(),
            UdpUpstream::Socket(_) => None,
        }
    }

    /// The next datagram, or none when the budget has no room for it and it is dropped. A
    /// transport reads into `own`, which is sized on its first use.
    async fn recv(&self, own: &mut Vec<u8>, budget: &Arc<Budget>) -> io::Result<Option<Datagram>> {
        match self {
            UdpUpstream::Socket(s) => loop {
                s.readable().await?;
                match receive(s, budget) {
                    // A connected socket reports an ICMP error once; it stays usable.
                    Err(e)
                        if matches!(
                            e.kind(),
                            ErrorKind::WouldBlock | ErrorKind::ConnectionRefused
                        ) => {}
                    r => return r,
                }
            },
            UdpUpstream::Proxy(t, remote, _) => {
                if own.is_empty() {
                    own.resize(DATAGRAM, 0);
                }
                loop {
                    let (n, from) = t.recv_packet(own).await?;
                    if same_peer(from, *remote) {
                        return Ok(Datagram::copy(&own[..n], budget));
                    }
                }
            }
        }
    }
}

/// The task that serves one association until it idles out or its upstream fails; its payload
/// counts on the route's meter. The association is one flow of the meter from the call, as the
/// driver creates it, whether or not its upstream comes up.
pub(crate) fn relay(
    o: Opened,
    remote: SocketAddr,
    (route, meter): (Route, Option<Arc<Meter>>),
    events: Events,
) -> impl Future<Output = ()> {
    // One flow, whichever way its datagrams move.
    let tally = Tally::new(meter);
    async move {
        let id = o.id;
        if let Ok((upstream, reporter)) = route.bind_udp(remote).await {
            let outcome = exchange(o, &upstream, tally, reporter.as_ref()).await;
            if let Some(r) = reporter {
                r.finish(outcome);
            }
        }
        let _ = events.send(Event::UdpEnded(id));
    }
}

/// Whether `a` and `b` are one peer; a node may name an IPv4 peer in its IPv6-mapped form.
fn same_peer(a: SocketAddr, b: SocketAddr) -> bool {
    a.port() == b.port() && a.ip().to_canonical() == b.ip().to_canonical()
}

fn millis(d: Duration) -> u64 {
    u64::try_from(d.as_millis()).unwrap_or(u64::MAX)
}

/// Reads one datagram; none when the budget has no room for it, and it is dropped.
fn receive(socket: &UdpSocket, budget: &Arc<Budget>) -> io::Result<Option<Datagram>> {
    RECEIVED.with_borrow_mut(|buf| {
        let n = socket.try_recv(buf)?;
        Ok(Datagram::copy(&buf[..n], budget))
    })
}

/// Answers one DNS datagram from `client` to `server`, through `route`. What the answer the client
/// gets maps is read here, off the driver, which puts it in the generation's names once the tun
/// takes the answer.
pub(crate) async fn answer_dns(
    query: Vec<u8>,
    (client, server): (SocketAddr, SocketAddr),
    route: Route,
    upstreams: Arc<[SocketAddr]>,
    events: Events,
    slot: OwnedSemaphorePermit,
) {
    if let Some(payload) = dns::answer(&upstreams, &route, &query, Network::Udp).await {
        let learned = Learned::read(&query, &payload);
        let _ = events.send(Event::DnsAnswer {
            from: server,
            to: client,
            payload,
            learned,
            _slot: slot,
        });
    }
}

#[cfg(test)]
mod tests_dns;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dto::ErrorCode;
    use std::pin::Pin;
    use std::sync::Weak;

    fn used(budget: &Budget) -> usize {
        budget.used.load(Acquire)
    }

    #[test]
    fn the_budget_refuses_what_does_not_fit_until_it_is_given_back() {
        let budget = Budget::new(100);
        let a = budget.take(60).unwrap();
        assert!(budget.take(50).is_none());
        let b = budget.take(40).unwrap();
        assert_eq!(used(&budget), 100);
        drop(a);
        assert!(budget.take(50).is_some());
        drop(b);
        assert_eq!(used(&budget), 0);
    }

    /// A node that answers each send with `sent` and never with a datagram: one on the local
    /// network as Android 17 treats it without the permission (`PermissionDenied`) and with it.
    #[derive(Debug)]
    struct Node(Option<ErrorKind>);

    impl PacketTransport for Node {
        fn relay_addr(&self) -> SocketAddr {
            ([192, 168, 8, 2], 8388).into()
        }

        fn send_packet<'a, 'b, 't>(
            &'a self,
            _: &'b [u8],
        ) -> Pin<Box<dyn Future<Output = io::Result<()>> + Send + 't>>
        where
            'a: 't,
            'b: 't,
            Self: 't,
        {
            let sent = self.0.map_or(Ok(()), |kind| Err(kind.into()));
            Box::pin(async move { sent })
        }

        fn recv_packet<'a, 'b, 't>(
            &'a self,
            _: &'b mut [u8],
        ) -> Pin<Box<dyn Future<Output = io::Result<(usize, SocketAddr)>> + Send + 't>>
        where
            'a: 't,
            'b: 't,
            Self: 't,
        {
            Box::pin(std::future::pending())
        }
    }

    /// The session's warning after one datagram through `node`, warned before it or not.
    async fn after_first_send(node: Node, warned: bool) -> Option<ErrorCode> {
        let session = Arc::new(Session::new(0, Weak::new()));
        if warned {
            let began = session.begin_dial();
            session.first_sent(began, &Err(ErrorKind::PermissionDenied.into()));
        }
        let (events, _wakes) = mpsc::unbounded_channel();
        let mut nat = Nat::new(Budget::new(QUEUED_BYTES), Duration::from_millis(100));
        let pair = (
            "172.19.0.1:40000".parse().unwrap(),
            "1.1.1.1:53".parse().unwrap(),
        );
        let (opened, _) = nat
            .send(pair, b"first", &events, || Some(Route::Direct))
            .unwrap();
        let upstream = UdpUpstream::Proxy(Arc::new(node), pair.1, Some(Arc::clone(&session)));
        exchange(opened, &upstream, Tally::new(None), None).await;
        session.warning()
    }

    /// A transport to a node on the local network binds a socket and sends nothing until its
    /// first datagram, which Android 17 refuses with `EPERM` without the permission: refused, it
    /// warns; sent, it clears the warning; any other failure says nothing about the permission.
    #[tokio::test]
    async fn the_first_send_through_a_node_on_the_local_network_says_how_it_went() {
        let refused = Node(Some(ErrorKind::PermissionDenied));
        assert_eq!(
            after_first_send(refused, false).await,
            Some(ErrorCode::LocalNetwork)
        );
        assert_eq!(after_first_send(Node(None), true).await, None);
        let unreachable = Node(Some(ErrorKind::NetworkUnreachable));
        assert_eq!(after_first_send(unreachable, false).await, None);
    }

    /// An association is one flow of its rule, however many ways its datagrams move.
    #[tokio::test]
    async fn an_association_counts_one_flow() {
        let meters = crate::stats::meter::Meters::default();
        let (events, _wakes) = mpsc::unbounded_channel();
        let mut nat = Nat::new(Budget::new(QUEUED_BYTES), Duration::from_millis(100));
        let pair = (
            "172.19.0.1:40000".parse().unwrap(),
            "1.1.1.1:53".parse().unwrap(),
        );
        let (opened, _) = nat
            .send(pair, b"first", &events, || Some(Route::Direct))
            .unwrap();
        let upstream = UdpUpstream::Proxy(Arc::new(Node(None)), pair.1, None);
        let meter = meters.get_ruled("g-Proxy", "r-1");
        exchange(opened, &upstream, Tally::new(Some(meter)), None).await;
        let taken = meters.take();
        assert_eq!(taken.rules, [("r-1".to_owned(), [5, 0, 1])]);
        assert_eq!(taken.outbounds, [("g-Proxy".to_owned(), 5, 0)]);
    }

    /// An association whose upstream never comes up is still one flow of its rule: connect
    /// refuses the limited broadcast address, as a bind fails on `EMFILE`.
    #[tokio::test]
    async fn an_association_whose_socket_fails_still_counts_one_flow() {
        let meters = crate::stats::meter::Meters::default();
        let (events, _wakes) = mpsc::unbounded_channel();
        let mut nat = Nat::new(Budget::new(QUEUED_BYTES), IDLE);
        let remote: SocketAddr = "255.255.255.255:123".parse().unwrap();
        let pair = ("172.19.0.1:40000".parse().unwrap(), remote);
        let route = || Some(Route::RuleDirect("r-1".into()));
        let (opened, route) = nat.send(pair, b"ntp", &events, route).unwrap();
        assert!(route.bind_udp(remote).await.is_err(), "the socket came up");
        let meter = meters.get_ruled(crate::stats::meter::DIRECT, "r-1");
        relay(opened, remote, (route, Some(meter)), events.clone()).await;
        assert_eq!(meters.take().rules, [("r-1".to_owned(), [0, 0, 1])]);
    }

    #[tokio::test]
    async fn an_association_queues_replies_only_within_the_budget() {
        const REPLY: usize = 30_000;
        // A server that answers one datagram with ten large ones.
        let server = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
        let remote = server.local_addr().unwrap();
        let burst = std::thread::spawn(move || {
            let mut buf = [0u8; 16];
            let (_, from) = server.recv_from(&mut buf).unwrap();
            for _ in 0..10 {
                server.send_to(&[7u8; REPLY], from).unwrap();
            }
        });
        let budget = Budget::new(3 * REPLY + REPLY / 2);
        let (events, _wakes) = mpsc::unbounded_channel();
        let mut nat = Nat::new(Arc::clone(&budget), IDLE);
        let client: SocketAddr = "172.19.0.1:40000".parse().unwrap();
        let (opened, _) = nat
            .send((client, remote), b"go", &events, || Some(Route::Direct))
            .unwrap();
        let id = opened.id;
        let route = (Route::Direct, None);
        let task = tokio::spawn(relay(opened, remote, route, events.clone()));
        for _ in 0..200 {
            if burst.is_finished() && used(&budget) == 3 * REPLY {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        // Let the relay read, and drop, whatever is left in its socket.
        tokio::time::sleep(Duration::from_millis(100)).await;
        assert_eq!(used(&budget), 3 * REPLY);
        assert_eq!(nat.flows[&id].down.len(), 3);
        task.abort();
        let _ = task.await;
        drop(nat);
        assert_eq!(used(&budget), 0, "dropped datagrams give their bytes back");
    }
}
