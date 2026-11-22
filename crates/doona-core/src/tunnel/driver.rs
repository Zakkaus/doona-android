//! The generation's driver: the one task that owns the tun fd and the honk-netstack `Stack` (a
//! transparent terminator: every flow's local end is the address the app dialed). It feeds tun
//! packets into the stack, answers the stack's new connections and datagrams by spawning flow
//! tasks, moves data between the stack and those tasks, and writes the stack's output back.
//! It never waits on a flow; flows wake it through the event channel (see `flow.rs`).
//!
//! The packet loop and its error handling follow crates/honk-android/src/engine.rs.

use super::flow::{self, Event, Events, Key, Waker};
use super::route::Route;
use super::tcp::{self, Step, TcpFlow};
use super::udp::{self, Nat};
use super::{Gen, Settings};
use crate::sys;
use honk_netstack::{
    AcceptOpts, AcceptWhen, Config, ExcludeSet, FlowId, Mode, Ready, Refuse, Rx, Stack,
};
use std::collections::HashMap;
use std::io::{self, ErrorKind};
use std::net::{IpAddr, SocketAddr};
use std::os::fd::{AsRawFd, OwnedFd};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::unix::{AsyncFd, AsyncFdReadyGuard};
use tokio::sync::{Semaphore, mpsc, oneshot};

/// Packets read per wakeup before the stack's output gets a turn (the stack's batch limit).
const TUN_BATCH: usize = 64;
/// Events handled per wakeup before the tun gets a turn.
const EVENT_BATCH: usize = 256;
/// A SYN waits this long for its upstream dial (the stack's ceiling is 25 s).
const DIAL_DEADLINE: Duration = Duration::from_secs(15);
/// DNS datagrams answered at once; more are dropped and the client retries.
const DNS_IN_FLIGHT: usize = 64;
/// Per-flow stack buffers and the stack's total, sized for a phone.
const TCP_BUF: usize = 4 << 20;
const STACK_MEMORY: usize = 128 << 20;

pub(crate) async fn run(
    tun: AsyncFd<OwnedFd>,
    settings: Settings,
    gen_: Gen,
    ready: oneshot::Sender<()>,
) {
    let (events_tx, mut events) = mpsc::unbounded_channel();
    let Ok(mut d) = Driver::new(settings, gen_.clone(), events_tx) else {
        // `ready` drops unsent: start fails with internal.
        return;
    };
    let _ = ready.send(());
    // Cancellation drops this future, and `tun` with it: the duplicate fd is closed by the task
    // that used it.
    if let Err(e) = d.serve(&tun, &mut events).await {
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
    nat: Nat,
    dns_slots: Arc<Semaphore>,
    /// Tun input, stack output, and the buffer flows are read into.
    rx: Vec<u8>,
    tx: Vec<u8>,
    scratch: Vec<u8>,
    /// The length of a packet in `tx` the tun has not taken yet.
    pending_tx: Option<usize>,
    up: u64,
    down: u64,
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
        Ok(Driver {
            stack: Stack::new(cfg, Instant::now()),
            upstreams: settings.dns_upstreams.clone().into(),
            settings,
            gen_,
            events,
            tcp: HashMap::new(),
            nat: Nat::default(),
            dns_slots: Arc::new(Semaphore::new(DNS_IN_FLIGHT)),
            rx: vec![0; mtu.max(1500) + 64],
            tx: vec![0; mtu + 64],
            scratch: vec![0; flow::CHUNK],
            pending_tx: None,
            up: 0,
            down: 0,
        })
    }

    async fn serve(
        &mut self,
        tun: &AsyncFd<OwnedFd>,
        events: &mut mpsc::UnboundedReceiver<Event>,
    ) -> io::Result<()> {
        loop {
            let deadline = self.stack.poll_timeout();
            let blocked = self.pending_tx.is_some();
            let far = Instant::now() + Duration::from_secs(3600);
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
                r = tun.readable(), if !blocked => self.read_tun(&mut r?)?,
                r = tun.writable(), if blocked => self.retry_tx(&mut r?)?,
                () = tokio::time::sleep_until(deadline.unwrap_or(far).into()), if deadline.is_some() => {}
            }
            self.settle(tun)?;
        }
    }

    /// Timers, new connections, stack events, output, and the counters `snapshot` reads.
    fn settle(&mut self, tun: &AsyncFd<OwnedFd>) -> io::Result<()> {
        let now = Instant::now();
        if self.stack.poll_timeout().is_some_and(|d| d <= now) {
            self.stack.handle_timeout(now);
        }
        self.accept();
        while let Some((id, ready)) = self.stack.poll_event() {
            if ready.contains(Ready::ERROR) {
                self.end_tcp(id, Step::Failed);
            } else {
                self.service_tcp(id);
            }
        }
        self.stack.take_tx_wakeup();
        while self.pending_tx.is_none() {
            let Some(len) = self.stack.poll_transmit(now, &mut self.tx) else {
                break;
            };
            self.write_tun(tun, len)?;
        }
        let active = u32::try_from(self.tcp.len() + self.nat.len()).unwrap_or(u32::MAX);
        self.gen_.session.publish(self.up, self.down, active);
        Ok(())
    }

    fn read_tun(&mut self, guard: &mut AsyncFdReadyGuard<'_, OwnedFd>) -> io::Result<()> {
        let mut rx = std::mem::take(&mut self.rx);
        let result = self.read_batch(guard, &mut rx);
        self.rx = rx;
        result
    }

    fn read_batch(
        &mut self,
        guard: &mut AsyncFdReadyGuard<'_, OwnedFd>,
        rx: &mut [u8],
    ) -> io::Result<()> {
        let now = Instant::now();
        for _ in 0..TUN_BATCH {
            let n = match guard.try_io(|fd| sys::read(fd.as_raw_fd(), rx)) {
                Ok(Ok(0)) => return Err(ErrorKind::UnexpectedEof.into()),
                Ok(Ok(n)) => n,
                Ok(Err(e)) if e.kind() == ErrorKind::Interrupted => continue,
                Ok(Err(e)) => return Err(e),
                Err(_would_block) => break,
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
    fn write_tun(&mut self, tun: &AsyncFd<OwnedFd>, len: usize) -> io::Result<()> {
        match sys::write(tun.as_raw_fd(), &self.tx[..len]) {
            Ok(_) => self.down += len as u64,
            Err(e) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::Interrupted) => {
                self.pending_tx = Some(len);
            }
            Err(e) => return dropped_or_fatal(e),
        }
        Ok(())
    }

    fn retry_tx(&mut self, guard: &mut AsyncFdReadyGuard<'_, OwnedFd>) -> io::Result<()> {
        let Some(len) = self.pending_tx else {
            return Ok(());
        };
        let tx = &self.tx[..len];
        match guard.try_io(|fd| sys::write(fd.as_raw_fd(), tx)) {
            Ok(Ok(_)) => self.down += len as u64,
            Ok(Err(e)) if e.kind() == ErrorKind::Interrupted => return Ok(()),
            Ok(Err(e)) => dropped_or_fatal(e)?,
            Err(_would_block) => return Ok(()),
        }
        self.pending_tx = None;
        Ok(())
    }

    fn on_event(&mut self, ev: Event, tun: &AsyncFd<OwnedFd>) -> io::Result<()> {
        match ev {
            Event::Wake(Key::Tcp(id)) => self.service_tcp(id),
            Event::Wake(Key::Udp(n)) => return self.service_udp(n, tun),
            Event::Connected(id) => {
                if let Some(f) = self.tcp.get_mut(&id) {
                    f.connected = true;
                    if self.stack.tcp_complete(id).is_err() {
                        self.end_tcp(id, Step::Failed);
                    }
                }
            }
            Event::Finished { id, failed } => {
                if let Some(f) = self.tcp.get_mut(&id) {
                    f.finished = true;
                    if failed {
                        self.reset_tcp(id);
                    } else {
                        self.service_tcp(id);
                    }
                }
            }
            Event::UdpEnded(n) => self.nat.remove(n),
            Event::DnsAnswer { from, to, payload } => {
                return self.send_udp(from, to, &payload, tun);
            }
        }
        Ok(())
    }

    /// Answers each new SYN: DNS to the virtual resolver is served here, anything else to it is
    /// reset (TCP/853, so Private DNS falls back at once), and other flows follow the route.
    fn accept(&mut self) {
        while let Some(syn) = self.stack.poll_accept() {
            let dst = syn.original_dst;
            let route = Route::for_mode(self.settings.mode.get());
            if dst.ip() == IpAddr::V4(self.settings.dns) {
                if dst.port() == 53 {
                    self.open_tcp(syn.id, route, None);
                } else {
                    self.stack.tcp_refuse(syn.id, Refuse::Rst);
                }
            } else if route == Route::Refuse {
                self.stack.tcp_refuse(syn.id, Refuse::Rst);
            } else {
                self.open_tcp(syn.id, route, Some(dst));
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
        let (ends, io) = flow::pair(Arc::clone(&waker));
        let flow = TcpFlow::new(ends, waker, dst.is_none());
        let (events, cancel) = (self.events.clone(), flow.cancel.clone());
        self.tcp.insert(id, flow);
        match dst {
            Some(dst) => self
                .gen_
                .spawn(tcp::dial(io, id, dst, route, events, cancel)),
            None => {
                let upstreams = Arc::clone(&self.upstreams);
                self.gen_
                    .spawn(tcp::serve_dns(io, id, route, upstreams, events, cancel));
            }
        }
    }

    fn service_tcp(&mut self, id: FlowId) {
        let Some(flow) = self.tcp.get_mut(&id) else {
            return;
        };
        flow.waker.rearm();
        match flow.pump(&mut self.stack, id, &mut self.scratch) {
            Step::Keep => {}
            step => self.end_tcp(id, step),
        }
    }

    /// Releases a flow whose directions are done, or whose stack side failed.
    fn end_tcp(&mut self, id: FlowId, step: Step) {
        if let Some(flow) = self.tcp.remove(&id) {
            if matches!(step, Step::Failed) {
                flow.cancel.cancel();
            }
            self.stack.tcp_close(id);
        }
    }

    /// The upstream failed: reset the client, or refuse its SYN if it was never answered.
    fn reset_tcp(&mut self, id: FlowId) {
        if let Some(flow) = self.tcp.remove(&id) {
            if flow.connected {
                self.stack.tcp_abort(id);
            } else {
                self.stack.tcp_refuse(id, Refuse::Rst);
            }
        }
    }

    /// A datagram from an app: UDP/53 to any address is DNS the core answers; nothing else
    /// listens on the virtual resolver; other datagrams follow the route.
    fn on_datagram(&mut self, src: SocketAddr, dst: SocketAddr, payload: &[u8]) {
        let route = Route::for_mode(self.settings.mode.get());
        if dst.port() == 53 {
            let Ok(slot) = Arc::clone(&self.dns_slots).try_acquire_owned() else {
                return;
            };
            let (upstreams, events) = (Arc::clone(&self.upstreams), self.events.clone());
            let task =
                udp::answer_dns(payload.to_vec(), (src, dst), route, upstreams, events, slot);
            self.gen_.spawn(task);
        } else if dst.ip() != IpAddr::V4(self.settings.dns)
            && route != Route::Refuse
            && let Some(opened) = self.nat.send(src, dst, payload, &self.events)
        {
            self.gen_
                .spawn(udp::relay(opened, dst, route, self.events.clone()));
        }
    }

    fn service_udp(&mut self, n: u64, tun: &AsyncFd<OwnedFd>) -> io::Result<()> {
        let Some(flow) = self.nat.flows.get_mut(&n) else {
            return Ok(());
        };
        flow.waker.rearm();
        let (from, to) = (flow.remote, flow.client);
        let mut replies = Vec::new();
        while let Ok(d) = flow.down.try_recv() {
            replies.push(d);
        }
        for d in replies {
            self.send_udp(from, to, &d, tun)?;
        }
        Ok(())
    }

    /// Sends a datagram to an app from `from`; dropped while the tun is full.
    fn send_udp(
        &mut self,
        from: SocketAddr,
        to: SocketAddr,
        payload: &[u8],
        tun: &AsyncFd<OwnedFd>,
    ) -> io::Result<()> {
        if self.pending_tx.is_some() {
            return Ok(());
        }
        match self.stack.udp_send_from(from, to, payload, &mut self.tx) {
            Ok(len) => self.write_tun(tun, len),
            Err(_) => Ok(()),
        }
    }
}

/// A tun write that failed for lack of memory or room loses its packet, as hev does for
/// ENOBUFS and ENOMEM (TCP retransmits); any other failure ends the data path.
fn dropped_or_fatal(e: io::Error) -> io::Result<()> {
    match e.raw_os_error() {
        Some(libc::ENOBUFS | libc::ENOMEM | libc::EMSGSIZE) => Ok(()),
        _ => Err(e),
    }
}
