//! UDP through the route: one association per (client, destination) pair, served by a task with
//! its own upstream (a socket, or a node's packet transport) and ended after an idle time without
//! traffic ([`IDLE`] in the app). The route is chosen when the association opens and kept: a route that carries no UDP
//! opens none, so the datagram is dropped. Memory stays bounded for a phone: every queued
//! datagram holds part of one [`Budget`] per generation, taken before the copy; sockets read into
//! one buffer per worker thread, and a node's transport into its association's own. Datagrams are
//! dropped, as a network would, when the budget or a queue is full or too many associations are
//! open.

use super::flow::{DEPTH, Event, Events, Key, Waker};
use super::route::Route;
use crate::dns;
use bytes::Bytes;
use honk_outbound::proxy::PacketTransport;
use std::cell::RefCell;
use std::collections::HashMap;
use std::io::{self, ErrorKind};
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{
    AtomicU64, AtomicUsize,
    Ordering::{AcqRel, Acquire, Relaxed},
};
use std::time::Duration;
use tokio::net::UdpSocket;
use tokio::sync::{OwnedSemaphorePermit, mpsc};
use tokio::time::Instant;

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

/// The driver's side of one association.
pub(crate) struct Association {
    pub client: SocketAddr,
    pub remote: SocketAddr,
    up: mpsc::Sender<Datagram>,
    pub down: mpsc::Receiver<Datagram>,
    pub waker: Arc<Waker>,
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
    pub waker: Arc<Waker>,
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
        let existing = self.by_pair.get(&(client, remote)).copied();
        let new_route = match existing {
            Some(_) => None,
            None if self.flows.len() >= MAX_ASSOCIATIONS => return None,
            None => Some(route()?),
        };
        let datagram = Datagram::copy(payload, &self.budget)?;
        let (id, opened) = match (existing, new_route) {
            (Some(id), _) => (id, None),
            (None, route) => {
                let o = self.open(client, remote, events);
                (o.id, route.map(|r| (o, r)))
            }
        };
        let flow = self.flows.get(&id)?;
        // Full: dropped. Closed: the task is ending and its UdpEnded event removes the entry.
        let _ = flow.up.try_send(datagram);
        opened
    }

    fn open(&mut self, client: SocketAddr, remote: SocketAddr, events: &Events) -> Opened {
        self.next += 1;
        let id = self.next;
        let (up_tx, up_rx) = mpsc::channel(DEPTH * 8);
        let (down_tx, down_rx) = mpsc::channel(DEPTH * 8);
        let waker = Waker::new(Key::Udp(id), events.clone());
        self.by_pair.insert((client, remote), id);
        self.flows.insert(
            id,
            Association {
                client,
                remote,
                up: up_tx,
                down: down_rx,
                waker: Arc::clone(&waker),
            },
        );
        Opened {
            id,
            up: up_rx,
            down: down_tx,
            waker,
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
    Proxy(Arc<dyn PacketTransport>, SocketAddr),
}

impl UdpUpstream {
    async fn send(&self, payload: &[u8]) -> io::Result<()> {
        match self {
            UdpUpstream::Socket(s) => s.send(payload).await.map(drop),
            UdpUpstream::Proxy(t, _) => t.send_packet(payload).await,
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
            UdpUpstream::Proxy(t, remote) => {
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

/// Serves one association until it idles out or its upstream fails.
pub(crate) async fn relay(o: Opened, remote: SocketAddr, route: Route, events: Events) {
    let id = o.id;
    if let Ok(upstream) = route.bind_udp(remote).await {
        exchange(o, &upstream).await;
    }
    let _ = events.send(Event::UdpEnded(id));
}

/// Moves datagrams both ways until neither way moved one for the idle time or the upstream fails.
/// Each way is one future polled to its end, never dropped mid-datagram, so a transport that
/// frames datagrams on a stream is never cut inside a frame.
async fn exchange(o: Opened, upstream: &UdpUpstream) {
    let Opened {
        mut up,
        down,
        waker,
        budget,
        idle,
        ..
    } = o;
    let begun = Instant::now();
    // When either way last moved a datagram, in milliseconds since `begun`.
    let last = AtomicU64::new(0);
    let moved = || last.store(millis(begun.elapsed()), Relaxed);
    let sending = async {
        while let Some(d) = up.recv().await {
            // A failed send is a lost datagram; one still blocked after `idle` ends the association.
            if tokio::time::timeout(idle, upstream.send(&d.bytes))
                .await
                .is_err()
            {
                return;
            }
            moved();
        }
    };
    let receiving = async {
        let mut own = Vec::new();
        while let Ok(d) = upstream.recv(&mut own, &budget).await {
            // None: over the budget, dropped.
            if d.is_some_and(|d| down.try_send(d).is_ok()) {
                waker.wake();
            }
            moved();
        }
    };
    let idled = async {
        loop {
            let until = begun + Duration::from_millis(last.load(Relaxed)) + idle;
            if Instant::now() >= until {
                return;
            }
            tokio::time::sleep_until(until).await;
        }
    };
    tokio::select! {
        () = sending => {}
        () = receiving => {}
        () = idled => {}
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

/// Answers one DNS datagram from `client` to `server`, through `route`.
pub(crate) async fn answer_dns(
    query: Vec<u8>,
    (client, server): (SocketAddr, SocketAddr),
    route: Route,
    upstreams: Arc<[SocketAddr]>,
    events: Events,
    slot: OwnedSemaphorePermit,
) {
    if let Some(a) = dns::answer(&upstreams, &route, &query).await {
        let _ = events.send(Event::DnsAnswer {
            from: server,
            to: client,
            payload: dns::fit_udp(a, &query),
            _slot: slot,
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
        let task = tokio::spawn(relay(opened, remote, Route::Direct, events.clone()));
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
