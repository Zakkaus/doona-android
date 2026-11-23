//! The generation's driver: the one task that uses the tun and owns the honk-netstack `Stack` (a
//! transparent terminator: every flow's local end is the address the app dialed). It feeds tun
//! packets into the stack, answers the stack's new connections and datagrams by spawning flow
//! tasks, moves data between the stack and those tasks, and writes the stack's output back.
//! It never waits on a flow; flows wake it through the event channel (see `flow.rs`).
//!
//! The packet loop and its error handling follow crates/honk-android/src/engine.rs.

use super::blocked::Blocked;
use super::flow::{self, Event, Events, Key, Waker};
use super::gate::Drains;
use super::gate::Task;
use super::route::{Inbound, Route};
use super::tcp::{self, Step, Stop, TcpFlow};
use super::udp::{self, Budget, Nat};
use super::{Gen, Session, Settings, StartSignal, Tun};
use crate::dns::names::Learned;
use crate::rules::Network;
use crate::stats::meter::Tally;
use crate::sys;
use honk_netstack::{
    AcceptOpts, AcceptWhen, Config, ExcludeSet, FlowId, Mode, Ready, Refuse, Rx, Stack,
};
use std::collections::HashMap;
use std::io::{self, ErrorKind};
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::Interest;
use tokio::sync::{Semaphore, mpsc};
use tokio::time;

/// Packets read per wakeup before the stack's output gets a turn (the stack's batch limit).
const TUN_BATCH: usize = 64;
/// Events handled per wakeup before the tun gets a turn.
const EVENT_BATCH: usize = 256;
/// Replies of one UDP association forwarded per wake before other events get a turn.
const UDP_DRAIN: usize = 32;
/// A SYN waits this long for its upstream dial (the stack's ceiling is 25 s).
const DIAL_DEADLINE: Duration = Duration::from_secs(15);
/// DNS datagrams answered at once; more are dropped and the client retries.
const DNS_IN_FLIGHT: usize = 64;
/// Per-flow stack buffers and the stack's total, sized for a phone.
const TCP_BUF: usize = 4 << 20;
const STACK_MEMORY: usize = 128 << 20;

pub(crate) async fn run(tun: Arc<Tun>, settings: Settings, gen_: Gen, ready: StartSignal) {
    let (events_tx, mut events) = mpsc::unbounded_channel();
    let Ok(mut d) = Driver::new(settings, gen_.clone(), events_tx) else {
        // `ready` drops unsent: start fails with internal.
        return;
    };
    // Nothing is read from the tun before `start` commits the generation: a SYN queued there
    // would have its node looked up, or its ECH configuration, with the previous resolvers.
    if !ready.committed().await {
        return;
    }
    // A stopped generation's tun is revoked; the error that follows is not a failure.
    if let Err(e) = d.serve(&tun, &mut events).await
        && !gen_.cancel.is_cancelled()
    {
        log::error!(
            "generation {}: tun failed ({:?})",
            gen_.session.generation,
            e.kind()
        );
        gen_.die();
    }
}

struct Driver {
    stack: Stack,
    settings: Settings,
    upstreams: Arc<[SocketAddr]>,
    gen_: Gen,
    events: Events,
    tcp: HashMap<FlowId, TcpFlow>,
    /// How many of `tcp` the core's DNS serves.
    dns_flows: usize,
    /// The deadlines of the flows of `tcp` whose drain has begun.
    drains: Drains,
    nat: Nat,
    /// The UDP five-tuples blockQuic refused lately.
    blocked: Blocked,
    dns_slots: Arc<Semaphore>,
    /// Tun input, stack output, and the buffer flows are read into.
    rx: Vec<u8>,
    tx: Vec<u8>,
    scratch: Vec<u8>,
    /// The length of a packet in `tx` the tun has not taken yet.
    pending_tx: Option<usize>,
    /// What the packet in `tx` maps once the tun takes it, when it is a DNS answer.
    pending_learned: Option<Learned>,
    up: u64,
    down: u64,
    /// Flows the apps opened: dialed TCP connections and UDP associations.
    opened: u64,
}

impl Driver {
    fn new(settings: Settings, gen_: Gen, events: Events) -> io::Result<Driver> {
        let mut cfg = Config::new(sys::random_secret()?);
        cfg.mode = Mode::Transparent {
            exclude: ExcludeSet::default(),
        };
        cfg.mtu = settings.mtu;
        cfg.tcp_snd_buf_max = TCP_BUF;
        cfg.tcp_rcv_buf_max = TCP_BUF;
        cfg.memory_limit = STACK_MEMORY;
        let mtu = usize::from(settings.mtu);
        let idle = settings.udp_idle;
        Ok(Driver {
            stack: Stack::new(cfg, Instant::now()),
            upstreams: settings.dns_upstreams.clone().into(),
            settings,
            gen_,
            events,
            tcp: HashMap::new(),
            dns_flows: 0,
            drains: Drains::default(),
            nat: Nat::new(Budget::new(udp::QUEUED_BYTES), idle),
            blocked: Blocked::new(),
            dns_slots: Arc::new(Semaphore::new(DNS_IN_FLIGHT)),
            rx: vec![0; mtu.max(1500) + 64],
            tx: vec![0; mtu + 64],
            scratch: vec![0; flow::CHUNK],
            pending_tx: None,
            pending_learned: None,
            up: 0,
            down: 0,
            opened: 0,
        })
    }

    async fn serve(
        &mut self,
        tun: &Tun,
        events: &mut mpsc::UnboundedReceiver<Event>,
    ) -> io::Result<()> {
        loop {
            let deadline = self.next_deadline();
            let blocked = self.pending_tx.is_some();
            let far = time::Instant::now() + Duration::from_secs(3600);
            tokio::select! {
                ev = events.recv() => {
                    // The driver holds a sender, so the channel never closes.
                    if let Some(ev) = ev {
                        self.on_event(ev, tun)?;
                    }
                    for _ in 0..EVENT_BATCH {
                        let Ok(ev) = events.try_recv() else { break };
                        self.on_event(ev, tun)?;
                    }
                }
                r = tun.ready(Interest::READABLE), if !blocked => {
                    r?;
                    self.read_tun(tun)?;
                }
                r = tun.ready(Interest::WRITABLE), if blocked => {
                    r?;
                    self.retry_tx(tun)?;
                }
                () = time::sleep_until(deadline.unwrap_or(far)), if deadline.is_some() => {}
            }
            self.settle(tun)?;
        }
    }

    /// The stack's next timer or the first drain's deadline, whichever comes first.
    fn next_deadline(&self) -> Option<time::Instant> {
        let stack = self.stack.poll_timeout().map(time::Instant::from_std);
        stack.into_iter().chain(self.drains.next()).min()
    }

    /// Timers, new connections, stack events, output, and the counters `snapshot` reads.
    fn settle(&mut self, tun: &Tun) -> io::Result<()> {
        let now = Instant::now();
        if self.stack.poll_timeout().is_some_and(|d| d <= now) {
            self.stack.handle_timeout(now);
        }
        self.accept();
        while let Some((id, ready)) = self.stack.poll_event() {
            if ready.contains(Ready::ERROR) {
                self.stop_tcp(id, Stop::Release);
            } else {
                self.service_tcp(id);
            }
        }
        // Last, so the timer's earliest entry is a live one when the driver next sleeps.
        self.expire_drains();
        self.stack.take_tx_wakeup();
        while self.pending_tx.is_none() {
            let Some(len) = self.stack.poll_transmit(now, &mut self.tx) else {
                break;
            };
            self.write_tun(tun, len)?;
        }
        // DNS the core answers is not an app's flow, on either transport.
        let flows = self.tcp.len() - self.dns_flows + self.nat.len();
        let active = u32::try_from(flows).unwrap_or(u32::MAX);
        let session = &self.gen_.session;
        session.publish(self.up, self.down, active, self.opened);
        Ok(())
    }

    fn read_tun(&mut self, tun: &Tun) -> io::Result<()> {
        let mut rx = std::mem::take(&mut self.rx);
        let result = self.read_batch(tun, &mut rx);
        self.rx = rx;
        result
    }

    fn read_batch(&mut self, tun: &Tun, rx: &mut [u8]) -> io::Result<()> {
        let now = Instant::now();
        for _ in 0..TUN_BATCH {
            let n = match tun.read(rx) {
                Ok(0) => return Err(ErrorKind::UnexpectedEof.into()),
                Ok(n) => n,
                Err(e) if e.kind() == ErrorKind::Interrupted => continue,
                Err(e) if e.kind() == ErrorKind::WouldBlock => break,
                Err(e) => return Err(e),
            };
            self.up += n as u64;
            match self.stack.receive(now, &rx[..n]) {
                Rx::Transparent {
                    src, dst, payload, ..
                } => self.on_datagram(src, dst, &rx[payload]),
                Rx::TransparentOwned {
                    src,
                    dst,
                    packet,
                    payload,
                    ..
                } => self.on_datagram(src, dst, &packet[payload]),
                _ => {}
            }
        }
        Ok(())
    }

    /// Writes the packet in `tx[..len]`; a full tun keeps it for [`Driver::retry_tx`].
    fn write_tun(&mut self, tun: &Tun, len: usize) -> io::Result<Sent> {
        match tun.write(&self.tx[..len]) {
            Ok(_) => {
                self.down += len as u64;
                Ok(Sent::Taken)
            }
            Err(e) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::Interrupted) => {
                self.pending_tx = Some(len);
                Ok(Sent::Waiting)
            }
            Err(e) => dropped_or_fatal(e).map(|()| Sent::Dropped),
        }
    }

    /// Writes the packet the tun had no room for. A DNS answer's names are put once it is taken,
    /// and never when it is dropped.
    fn retry_tx(&mut self, tun: &Tun) -> io::Result<()> {
        let Some(len) = self.pending_tx else {
            return Ok(());
        };
        let learned = self.pending_learned.take();
        match tun.write(&self.tx[..len]) {
            Ok(_) => {
                self.down += len as u64;
                if let Some(learned) = learned {
                    self.gen_.session.names().put(learned);
                }
            }
            Err(e) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::Interrupted) => {
                self.pending_learned = learned;
                return Ok(());
            }
            Err(e) => dropped_or_fatal(e)?,
        }
        self.pending_tx = None;
        Ok(())
    }

    fn on_event(&mut self, ev: Event, tun: &Tun) -> io::Result<()> {
        match ev {
            Event::Wake(Key::Tcp(id)) => self.service_tcp(id),
            Event::Wake(Key::Udp(n)) => return self.service_udp(n, tun),
            Event::Connected(id) => {
                if let Some(f) = self.tcp.get_mut(&id).filter(|f| f.stopping.is_none()) {
                    f.connected = true;
                    if self.stack.tcp_complete(id).is_err() {
                        self.stop_tcp(id, Stop::Release);
                    }
                }
            }
            Event::Finished { id, failed: true } => self.stop_tcp(id, Stop::Reset),
            // The clean end is in the flow's gate already.
            Event::Finished { id, failed: false } => self.service_tcp(id),
            Event::UdpEnded(n) => self.nat.remove(n),
            Event::DnsAnswer {
                from,
                to,
                payload,
                learned,
                ..
            } => return self.send_dns((from, to), &payload, learned, tun),
        }
        Ok(())
    }

    /// Answers each new SYN: anything to the virtual resolver but DNS is reset (TCP/853, so
    /// Private DNS falls back at once), as is any flow its route refuses, DNS included; DNS to the
    /// virtual resolver is served here, and other flows follow their route.
    fn accept(&mut self) {
        while let Some(syn) = self.stack.poll_accept() {
            let dst = syn.original_dst;
            let to_resolver = dst.ip() == IpAddr::V4(self.settings.dns);
            if to_resolver && dst.port() != 53 {
                self.stack.tcp_refuse(syn.id, Refuse::Rst);
                continue;
            }
            let (settings, session) = (&self.settings, &self.gen_.session);
            let route = if to_resolver {
                dns_route(settings, session)
            } else {
                let (to, mode) = ((dst, Network::Tcp), settings.mode.get());
                Route::of(Inbound::Tun, to, mode, &settings.outbounds, session)
            };
            if route.refused() {
                self.stack.tcp_refuse(syn.id, Refuse::Rst);
            } else {
                self.open_tcp(syn.id, route, (!to_resolver).then_some(dst));
            }
        }
    }

    /// Accepts `id` and spawns its task: a dial to `dst`, or with no `dst` the DNS server. A
    /// dialed flow's SYN-ACK waits for the upstream, so a refused dial reaches the app as RST.
    fn open_tcp(&mut self, id: FlowId, route: Route, dst: Option<SocketAddr>) {
        let when = match dst {
            Some(_) => AcceptWhen::Deferred {
                deadline: DIAL_DEADLINE,
            },
            None => AcceptWhen::Now,
        };
        let opts = AcceptOpts {
            when,
            ..AcceptOpts::default()
        };
        if self.stack.tcp_accept(id, opts).is_err() {
            return;
        }
        let waker = Waker::new(Key::Tcp(id), self.events.clone());
        // The core's own DNS is not an app's flow, nor an outbound's bytes.
        let meter = dst.and_then(|_| route.meter(&self.gen_.session));
        self.opened += u64::from(dst.is_some());
        let drain = self.settings.tcp_drain;
        let serves = if dst.is_some() {
            Task::Relay
        } else {
            Task::Dns
        };
        let (ends, io) = flow::pair(Arc::clone(&waker), Tally::new(meter), drain, serves);
        let mut flow = TcpFlow::new(ends, waker, dst.is_none());
        // A drain begun at accept (the core's DNS) is queued now, not at the flow's first event.
        if let Some(at) = flow.due() {
            self.drains.push(at, id);
        }
        let events = self.events.clone();
        self.dns_flows += usize::from(flow.dns);
        self.tcp.insert(id, flow);
        match dst {
            Some(dst) => self.gen_.spawn(tcp::dial(io, id, dst, route, events)),
            None => {
                let upstreams = Arc::clone(&self.upstreams);
                let via = (route, Arc::clone(&self.gen_.session));
                let task = tcp::serve_dns(io, id, via, upstreams, events);
                self.gen_.spawn(task);
            }
        }
    }

    fn service_tcp(&mut self, id: FlowId) {
        let Some(flow) = self.tcp.get_mut(&id) else {
            return;
        };
        flow.waker.rearm();
        // Given up while a poll of its task was in progress: this may be that poll's end.
        if let Some(how) = flow.stopping {
            return self.stop_tcp(id, how);
        }
        let step = flow.pump(&mut self.stack, id, &mut self.scratch);
        self.settle_tcp(id, step);
    }

    /// Keeps a flow, with its drain's deadline queued once the drain has begun; or releases or
    /// resets it.
    fn settle_tcp(&mut self, id: FlowId, step: Step) {
        match step {
            Step::Keep => {
                if let Some(at) = self.tcp.get_mut(&id).and_then(TcpFlow::due) {
                    self.drains.push(at, id);
                }
            }
            // The poll in progress wakes the driver as it exits.
            Step::Busy => {}
            Step::Expired => self.stop_tcp(id, Stop::Reset),
            Step::Close | Step::Failed => self.stop_tcp(id, Stop::Release),
        }
    }

    /// Settles each flow whose queued drain deadline has come, without I/O (see
    /// [`TcpFlow::verdict`]): released, reset, or queued again when a byte moved since. An entry
    /// no flow waits for, as the flow is gone or queued another, is dropped once it is the
    /// earliest, so the driver never wakes for one.
    fn expire_drains(&mut self) {
        let now = time::Instant::now();
        while let Some((at, id)) = self.drains.peek() {
            let Some(flow) = self.tcp.get_mut(&id).filter(|flow| flow.waits_for(at)) else {
                self.drains.pop();
                continue;
            };
            if at > now {
                return;
            }
            self.drains.pop();
            flow.fires(at);
            let step = flow.verdict().unwrap_or(Step::Keep);
            self.settle_tcp(id, step);
        }
    }

    fn remove_tcp(&mut self, id: FlowId) -> Option<TcpFlow> {
        let flow = self.tcp.remove(&id)?;
        self.dns_flows -= usize::from(flow.dns);
        Some(flow)
    }

    /// Gives a flow up and ends it `how` (see `TcpFlow::stop`): released when its directions are
    /// done or its stack side failed; reset (its SYN refused, if never answered) when its upstream
    /// failed or its drain ran out. At once, or when the poll of its task in progress ends and wakes
    /// the driver: the driver never waits for a poll.
    fn stop_tcp(&mut self, id: FlowId, how: Stop) {
        if self.tcp.get_mut(&id).is_some_and(|flow| flow.stop(how))
            && let Some(flow) = self.remove_tcp(id)
        {
            flow.end(&mut self.stack, id);
        }
    }

    /// A datagram from an app: UDP/53 to any address is DNS the core answers; nothing else
    /// listens on the virtual resolver; other datagrams follow the route their association
    /// opened with. A five-tuple blockQuic refused lately is refused again without a route, unless
    /// the mode was set, a routing published or an answer put in the names since.
    fn on_datagram(&mut self, src: SocketAddr, dst: SocketAddr, payload: &[u8]) {
        if dst.port() == 53 {
            let Ok(slot) = Arc::clone(&self.dns_slots).try_acquire_owned() else {
                return;
            };
            let (upstreams, events) = (Arc::clone(&self.upstreams), self.events.clone());
            let route = dns_route(&self.settings, &self.gen_.session);
            let task =
                udp::answer_dns(payload.to_vec(), (src, dst), route, upstreams, events, slot);
            self.gen_.spawn(task);
        } else if dst.ip() != IpAddr::V4(self.settings.dns) {
            let (settings, session) = (&self.settings, &self.gen_.session);
            let blocked = &mut self.blocked;
            let open = || {
                // Read before the route looks anything up, and its mode from the same read.
                let (mode, sets) = settings.mode.snapshot();
                let (routings, names) = (settings.outbounds.routings(), session.names());
                let stamp = (sets, routings, names.version());
                let now = Instant::now();
                if blocked.holds((src, dst), stamp, now) {
                    return None;
                }
                let to = (dst, Network::Udp);
                let route = Route::of(Inbound::Tun, to, mode, &settings.outbounds, session);
                if let Route::Blocked = route {
                    blocked.refuse((src, dst), stamp, now);
                }
                Some(route).filter(Route::carries_udp)
            };
            if let Some((opened, route)) = self.nat.send((src, dst), payload, &self.events, open) {
                self.opened += 1;
                let meter = route.meter(&self.gen_.session);
                let relay = udp::relay(opened, dst, (route, meter), self.events.clone());
                self.gen_.spawn(relay);
            }
        }
    }

    fn service_udp(&mut self, n: u64, tun: &Tun) -> io::Result<()> {
        let Some(flow) = self.nat.flows.get_mut(&n) else {
            return Ok(());
        };
        flow.queues.waker.rearm();
        let (from, to) = (flow.remote, flow.client);
        let mut replies = Vec::with_capacity(UDP_DRAIN);
        while replies.len() < UDP_DRAIN
            && let Ok(d) = flow.down.try_recv()
        {
            replies.push(d);
        }
        if replies.len() == UDP_DRAIN {
            // More may be queued: come back after the events already waiting.
            flow.queues.waker.wake();
        }
        for d in replies {
            self.send_udp((from, to), &d.bytes, tun)?;
        }
        Ok(())
    }

    /// Sends a datagram to an app from `from`; dropped while the tun is full.
    fn send_udp(
        &mut self,
        (from, to): (SocketAddr, SocketAddr),
        payload: &[u8],
        tun: &Tun,
    ) -> io::Result<Sent> {
        if self.pending_tx.is_some() {
            return Ok(Sent::Dropped);
        }
        match self.stack.udp_send_from(from, to, payload, &mut self.tx) {
            Ok(len) => self.write_tun(tun, len),
            Err(_) => Ok(Sent::Dropped),
        }
    }

    /// Sends a DNS answer to an app. What it maps is put in the names once the tun takes it, before
    /// the next packet is read, so a flow the app opens to an address it got finds its name; when
    /// the answer waits for room, once [`Driver::retry_tx`] writes it; when it is dropped, never.
    fn send_dns(
        &mut self,
        ends: (SocketAddr, SocketAddr),
        payload: &[u8],
        learned: Option<Learned>,
        tun: &Tun,
    ) -> io::Result<()> {
        match self.send_udp(ends, payload, tun)? {
            Sent::Taken => {
                if let Some(learned) = learned {
                    self.gen_.session.names().put(learned);
                }
            }
            Sent::Waiting => self.pending_learned = learned,
            Sent::Dropped => {}
        }
        Ok(())
    }
}

/// What became of a packet written to the tun.
enum Sent {
    Taken,
    /// Kept in `tx` until the tun has room.
    Waiting,
    Dropped,
}

/// The route of DNS the core answers for `session`'s generation.
fn dns_route(settings: &Settings, session: &Arc<Session>) -> Route {
    let mode = settings.mode.get();
    Route::dns(Inbound::Tun, mode, &settings.outbounds, session)
}

/// A tun write that failed for lack of memory or room loses its packet, as hev does for
/// ENOBUFS and ENOMEM (TCP retransmits); any other failure ends the data path.
fn dropped_or_fatal(e: io::Error) -> io::Result<()> {
    match e.raw_os_error() {
        Some(libc::ENOBUFS | libc::ENOMEM | libc::EMSGSIZE) => Ok(()),
        _ => Err(e),
    }
}

#[cfg(test)]
mod tests;
