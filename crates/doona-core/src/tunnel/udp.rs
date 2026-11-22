//! UDP through the route: one association per (client, destination) pair, served by a task with
//! its own socket and ended after [`IDLE`] without traffic. Datagrams are dropped, as a network
//! would, when a queue is full or too many associations are open.

use super::flow::{DEPTH, Event, Events, Key, Waker};
use super::route::Route;
use crate::dns;
use bytes::Bytes;
use std::collections::HashMap;
use std::io::ErrorKind;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::{OwnedSemaphorePermit, mpsc};
use tokio::time::Instant;

/// An association with no datagram either way for this long ends.
pub(crate) const IDLE: Duration = Duration::from_secs(60);
/// Open associations at most; a new pair beyond it is dropped.
const MAX_ASSOCIATIONS: usize = 1024;
/// The largest datagram a socket reads.
const DATAGRAM: usize = 65535;

/// The driver's side of one association.
pub(crate) struct Association {
    pub client: SocketAddr,
    pub remote: SocketAddr,
    up: mpsc::Sender<Bytes>,
    pub down: mpsc::Receiver<Bytes>,
    pub waker: Arc<Waker>,
}

/// The driver's associations, by id and by address pair.
#[derive(Default)]
pub(crate) struct Nat {
    by_pair: HashMap<(SocketAddr, SocketAddr), u64>,
    pub flows: HashMap<u64, Association>,
    next: u64,
}

/// What a new association's task needs; the driver spawns it.
pub(crate) struct Opened {
    pub id: u64,
    pub up: mpsc::Receiver<Bytes>,
    pub down: mpsc::Sender<Bytes>,
    pub waker: Arc<Waker>,
}

impl Nat {
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
        let mut opened = None;
        let id = match self.by_pair.get(&(client, remote)) {
            Some(&id) => id,
            None if self.flows.len() >= MAX_ASSOCIATIONS => return None,
            None => {
                let o = self.open(client, remote, events);
                let id = o.id;
                opened = Some(o);
                id
            }
        };
        let flow = self.flows.get(&id)?;
        // Full: dropped. Closed: the task is ending and its UdpEnded event removes the entry.
        let _ = flow.up.try_send(Bytes::copy_from_slice(payload));
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
    } = o;
    if let Ok(socket) = route.bind_udp(remote).await {
        let mut buf = vec![0u8; DATAGRAM];
        let idle = tokio::time::sleep(IDLE);
        tokio::pin!(idle);
        loop {
            tokio::select! {
                d = up.recv() => {
                    let Some(d) = d else { break };
                    // A failed send is a lost datagram.
                    let _ = socket.send(&d).await;
                    idle.as_mut().reset(Instant::now() + IDLE);
                }
                r = socket.recv(&mut buf) => match r {
                    Ok(n) => {
                        if down.try_send(Bytes::copy_from_slice(&buf[..n])).is_ok() {
                            waker.wake();
                        }
                        idle.as_mut().reset(Instant::now() + IDLE);
                    }
                    // A connected socket reports an ICMP error once; it stays usable.
                    Err(e) if e.kind() == ErrorKind::ConnectionRefused => {}
                    Err(_) => break,
                },
                () = &mut idle => break,
            }
        }
    }
    let _ = events.send(Event::UdpEnded(id));
}

/// Answers one DNS datagram from `client` to `server`, through `route`.
pub(crate) async fn answer_dns(
    query: Vec<u8>,
    (client, server): (SocketAddr, SocketAddr),
    route: Route,
    upstreams: Arc<[SocketAddr]>,
    events: Events,
    _slot: OwnedSemaphorePermit,
) {
    if let Some(a) = dns::answer(&upstreams, route, &query).await {
        let _ = events.send(Event::DnsAnswer {
            from: server,
            to: client,
            payload: dns::fit_udp(a, &query),
        });
    }
}
