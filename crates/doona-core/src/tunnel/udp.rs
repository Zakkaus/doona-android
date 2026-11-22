//! UDP through the route: one association per (client, destination) pair, served by a task with
//! its own socket and ended after [`IDLE`] without traffic. Memory stays bounded for a phone:
//! every queued datagram holds part of one [`Budget`] per generation, taken before the copy, and
//! sockets read into one buffer per worker thread. Datagrams are dropped, as a network would,
//! when the budget or a queue is full or too many associations are open.

use super::flow::{DEPTH, Event, Events, Key, Waker};
use super::route::Route;
use crate::dns;
use bytes::Bytes;
use std::cell::RefCell;
use std::collections::HashMap;
use std::io::{self, ErrorKind};
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{
    AtomicUsize,
    Ordering::{AcqRel, Acquire},
};
use std::time::Duration;
use tokio::net::UdpSocket;
use tokio::sync::{OwnedSemaphorePermit, mpsc};
use tokio::time::Instant;

/// An association with no datagram either way for this long ends.
pub(crate) const IDLE: Duration = Duration::from_secs(60);
/// Bytes of datagrams queued between the driver and the associations, both ways, per generation.
pub(crate) const QUEUED_BYTES: usize = 4 << 20;
/// Open associations at most; a new pair beyond it is dropped.
const MAX_ASSOCIATIONS: usize = 1024;
/// The largest datagram a socket reads.
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
}

/// What a new association's task needs; the driver spawns it.
pub(crate) struct Opened {
    pub id: u64,
    pub up: mpsc::Receiver<Datagram>,
    pub down: mpsc::Sender<Datagram>,
    pub waker: Arc<Waker>,
    budget: Arc<Budget>,
}

impl Nat {
    pub fn new(budget: Arc<Budget>) -> Nat {
        Nat {
            by_pair: HashMap::new(),
            flows: HashMap::new(),
            next: 0,
            budget,
        }
    }

    pub fn len(&self) -> usize {
        self.flows.len()
    }

    /// Queues `payload` from `client` to `remote`. Returns the parts of a task to spawn when the
    /// pair had no association yet.
    pub fn send(
        &mut self,
        client: SocketAddr,
        remote: SocketAddr,
        payload: &[u8],
        events: &Events,
    ) -> Option<Opened> {
        let existing = self.by_pair.get(&(client, remote)).copied();
        if existing.is_none() && self.flows.len() >= MAX_ASSOCIATIONS {
            return None;
        }
        let datagram = Datagram::copy(payload, &self.budget)?;
        let (id, opened) = match existing {
            Some(id) => (id, None),
            None => {
                let o = self.open(client, remote, events);
                (o.id, Some(o))
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
        }
    }

    pub fn remove(&mut self, id: u64) {
        if let Some(flow) = self.flows.remove(&id) {
            self.by_pair.remove(&(flow.client, flow.remote));
        }
    }
}

/// Serves one association until it idles out or its socket fails.
pub(crate) async fn relay(o: Opened, remote: SocketAddr, route: Route, events: Events) {
    let Opened {
        id,
        mut up,
        down,
        waker,
        budget,
    } = o;
    if let Ok(socket) = route.bind_udp(remote).await {
        let idle = tokio::time::sleep(IDLE);
        tokio::pin!(idle);
        loop {
            tokio::select! {
                d = up.recv() => {
                    let Some(d) = d else { break };
                    // A failed send is a lost datagram; one still blocked when the association
                    // would idle out ends it.
                    let sent = tokio::time::timeout_at(idle.deadline(), socket.send(&d.bytes));
                    if sent.await.is_err() {
                        break;
                    }
                    idle.as_mut().reset(Instant::now() + IDLE);
                }
                r = socket.readable() => {
                    if r.is_err() {
                        break;
                    }
                    match receive(&socket, &budget) {
                        Ok(d) => {
                            // None: over the budget, dropped.
                            if d.is_some_and(|d| down.try_send(d).is_ok()) {
                                waker.wake();
                            }
                            idle.as_mut().reset(Instant::now() + IDLE);
                        }
                        // A connected socket reports an ICMP error once; it stays usable.
                        Err(e) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::ConnectionRefused) => {}
                        Err(_) => break,
                    }
                }
                () = &mut idle => break,
            }
        }
    }
    let _ = events.send(Event::UdpEnded(id));
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
    if let Some(a) = dns::answer(&upstreams, route, &query).await {
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
        let mut nat = Nat::new(Arc::clone(&budget));
        let client: SocketAddr = "172.19.0.1:40000".parse().unwrap();
        let opened = nat.send(client, remote, b"go", &events).unwrap();
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
