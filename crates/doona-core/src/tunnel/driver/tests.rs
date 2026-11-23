//! The driver with a tun the apps stop reading: a DNS answer the core forwards maps its addresses
//! only once the tun takes it. And a TCP/53 session's deadline, queued at accept.

use super::*;
use crate::dns::tests::{answer_to, query, upstream_with};
use crate::dto::Mode;
use crate::outbounds::Outbounds;
use crate::stats::Stats;
use crate::testutil::{TempDir, tun_and_peer};
use crate::tunnel::pause;
use crate::tunnel::route::ModeCell;
use bytes::Bytes;
use honk_netstack::{StreamError, TcpState};
use std::net::Ipv4Addr;
use std::os::fd::{AsRawFd, OwnedFd};
use std::sync::Weak;
use tokio::sync::Semaphore;
use tokio_util::sync::CancellationToken;
use tokio_util::task::TaskTracker;

/// A driver in Direct mode on a fresh tun, the tun's far end, where the apps are (nothing reads it
/// until the test does), and the driver's events.
fn driver(dir: &TempDir) -> (Driver, Arc<Tun>, OwnedFd, mpsc::UnboundedReceiver<Event>) {
    let runtime = tokio::runtime::Handle::current();
    let settings = Settings {
        mtu: 1500,
        dns: Ipv4Addr::new(172, 19, 0, 2),
        dns_upstreams: Vec::new(),
        udp_idle: udp::IDLE,
        tcp_drain: Duration::from_secs(30),
        mode: Arc::new(ModeCell::new(Mode::Direct)),
        outbounds: Arc::new(Outbounds::new(Arc::default()).unwrap()),
        panics: Arc::default(),
        catalog: Weak::new(),
        stats: Arc::new(Stats::new(dir.path(), runtime.clone())),
    };
    let gen_ = Gen {
        session: Arc::new(Session::new(1, Weak::new())),
        cancel: CancellationToken::new(),
        tracker: TaskTracker::new(),
        runtime: runtime.clone(),
    };
    let (events, got) = mpsc::unbounded_channel();
    let d = Driver::new(settings, gen_, events).unwrap();
    let (fd, peer) = tun_and_peer();
    (d, Tun::new(fd, &runtime).unwrap(), peer, got)
}

/// One A record for the query numbered `id`: 10.0.0.<id>.
fn one_address(q: &[u8]) -> Vec<u8> {
    let mut a = answer_to(q, 1);
    let last = a.len() - 1;
    a[last] = q[1];
    a
}

/// What the core's DNS task gives the driver for the query numbered `id`, for `name`.
async fn answered(id: u16, name: &str, upstreams: &Arc<[SocketAddr]>) -> Event {
    let (events, mut got) = mpsc::unbounded_channel();
    let slot = Arc::new(Semaphore::new(1)).try_acquire_owned().unwrap();
    let ends = (
        SocketAddr::from(([10, 0, 0, 2], 40_000)),
        SocketAddr::from(([172, 19, 0, 2], 53)),
    );
    let q = query(id, name, None);
    udp::answer_dns(q, ends, Route::Direct, Arc::clone(upstreams), events, slot).await;
    got.recv().await.unwrap()
}

/// Writes to the tun until a write would block: the apps are not reading.
async fn fill(tun: &Tun) {
    tun.ready(Interest::WRITABLE).await.unwrap();
    loop {
        match tun.write(&[0; 64]) {
            Ok(_) => {}
            Err(e) if e.kind() == ErrorKind::WouldBlock => return,
            Err(e) => panic!("{e}"),
        }
    }
}

/// Reads every packet the tun holds for the apps.
fn drain(peer: &OwnedFd) {
    let mut buf = [0u8; 2048];
    loop {
        // SAFETY: `buf` is valid for its length.
        let n = unsafe {
            libc::recv(
                peer.as_raw_fd(),
                buf.as_mut_ptr().cast(),
                buf.len(),
                libc::MSG_DONTWAIT,
            )
        };
        if n <= 0 {
            return;
        }
    }
}

/// While the tun is full, the first answer waits for room and the second is dropped: neither maps
/// an address then. Once the apps read, the waiting answer goes out, and only then maps.
#[tokio::test]
async fn a_dns_answer_maps_its_addresses_once_the_tun_takes_it() {
    let dir = TempDir::new("driver-dns");
    let (mut d, tun, peer, _) = driver(&dir);
    let session = Arc::clone(&d.gen_.session);
    let upstreams: Arc<[SocketAddr]> = Arc::from([upstream_with(one_address)]);
    let name = |host: u8| {
        let ip = IpAddr::from([10, 0, 0, host]);
        session.names().name(ip).map(|n| n.to_string())
    };
    fill(&tun).await;
    let first = answered(1, "kept.example", &upstreams).await;
    d.on_event(first, &tun).unwrap();
    assert!(d.pending_tx.is_some(), "the tun took the answer");
    assert_eq!(name(1), None, "mapped before the tun took it");
    let second = answered(2, "dropped.example", &upstreams).await;
    d.on_event(second, &tun).unwrap();
    assert_eq!(name(2), None, "mapped an answer the tun dropped");
    drain(&peer);
    let room = time::timeout(Duration::from_secs(5), tun.ready(Interest::WRITABLE)).await;
    room.unwrap().unwrap();
    d.retry_tx(&tun).unwrap();
    assert!(d.pending_tx.is_none());
    assert_eq!(name(1).as_deref(), Some("kept.example"));
    assert_eq!(name(2), None);
}

/// A TCP/53 session to the virtual resolver has its deadline queued as the driver accepts it: the
/// core's DNS is bounded from accept, without waiting for an event of the flow.
#[tokio::test]
async fn a_dns_session_is_queued_in_the_drain_timer_as_it_is_accepted() {
    let dir = TempDir::new("driver-dns-tcp");
    let (mut d, _tun, _peer, _) = driver(&dir);
    let now = Instant::now();
    let mut app = Config::new([7; 32]);
    app.local_v4 = Some(Ipv4Addr::new(172, 19, 0, 1));
    let mut app = Stack::new(app, now);
    app.tcp_connect(now, SocketAddr::from(([172, 19, 0, 2], 53)))
        .unwrap();
    let mut packet = vec![0; 2048];
    while let Some(n) = app.poll_transmit(now, &mut packet) {
        let _ = d.stack.receive(now, &packet[..n]);
    }
    let accepted = time::Instant::now();
    d.accept();
    assert_eq!(d.dns_flows, 1, "the session was not accepted");
    let (at, id) = d.drains.peek().expect("no deadline queued at accept");
    assert!(d.tcp.contains_key(&id));
    assert!(at >= accepted + d.settings.tcp_drain, "{at:?}");
}

/// Moves packets between an app's stack and the driver's until neither has one to send.
fn settle_wire(app: &mut Stack, core: &mut Stack) {
    let now = Instant::now();
    let mut packet = vec![0; 2048];
    let mut moved = true;
    while moved {
        moved = false;
        while let Some(n) = app.poll_transmit(now, &mut packet) {
            let _ = core.receive(now, &packet[..n]);
            moved = true;
        }
        while let Some(n) = core.poll_transmit(now, &mut packet) {
            let _ = app.receive(now, &packet[..n]);
            moved = true;
        }
    }
}

/// A flow's task is in the middle of a write upstream that holds its thread (see `pause::stuck`)
/// when the driver resets the flow. The driver goes on at once and leaves the client alone until
/// the write has ended; the write's end wakes it, and the client is reset then.
#[test]
fn a_flow_reset_in_the_middle_of_a_poll_is_reset_once_the_poll_ends() {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(1)
        .enable_all()
        .build()
        .unwrap();
    let _entered = runtime.enter();
    let dir = TempDir::new("driver-stuck");
    let (mut d, tun, _peer, mut events) = driver(&dir);
    let now = Instant::now();
    let mut app = Config::new([7; 32]);
    app.local_v4 = Some(Ipv4Addr::new(172, 19, 0, 1));
    let mut app = Stack::new(app, now);
    let to = SocketAddr::from(([10, 9, 9, 9], 80));
    let conn = app.tcp_connect(now, to).unwrap();
    settle_wire(&mut app, &mut d.stack);
    let id = d.stack.poll_accept().unwrap().id;
    let opts = AcceptOpts {
        when: AcceptWhen::Now,
        ..AcceptOpts::default()
    };
    d.stack.tcp_accept(id, opts).unwrap();
    settle_wire(&mut app, &mut d.stack);
    let waker = Waker::new(Key::Tcp(id), d.events.clone());
    let drain = d.settings.tcp_drain;
    let (ends, io) = flow::pair(Arc::clone(&waker), Tally::new(None), drain, Task::Relay);
    let up = ends.up.as_ref().unwrap();
    up.try_send(Bytes::from_static(b"GET")).unwrap();
    let mut flow = TcpFlow::new(ends, waker, false);
    flow.connected = true;
    d.tcp.insert(id, flow);
    let (upstream, unstick) = pause::stuck(pause::Stall::Write);
    let relay = pause::relay_apart(io, upstream);
    assert!(unstick.entered(), "the relay never wrote");
    // The driver resets the flow, on a thread of its own.
    let (done, reset) = std::sync::mpsc::channel();
    let driver = std::thread::spawn(move || {
        d.stop_tcp(id, Stop::Reset);
        let _ = done.send(());
        d
    });
    let waited = reset.recv_timeout(Duration::from_secs(5)).is_err();
    if waited {
        unstick.release();
    }
    let mut d = driver.join().unwrap();
    assert!(!waited, "the driver waited for the flow's write");
    assert!(d.tcp.contains_key(&id), "let go in the middle of the write");
    settle_wire(&mut app, &mut d.stack);
    let state = app.tcp_state(conn);
    assert_eq!(state, Some(TcpState::Established), "reset mid-write");
    unstick.release();
    // The write's end wakes the driver, which resets the client then.
    while d.tcp.contains_key(&id) {
        let woken = runtime.block_on(time::timeout(Duration::from_secs(5), events.recv()));
        let event = woken.expect("the write's end did not wake the driver");
        d.on_event(event.unwrap(), &tun).unwrap();
    }
    settle_wire(&mut app, &mut d.stack);
    let read = app.tcp_read(conn, &mut [0; 8]);
    assert!(matches!(read, Err(StreamError::Reset)), "{read:?}");
    relay.join().unwrap();
}
