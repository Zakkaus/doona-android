//! The driver's side of TCP flows against a real stack, and the tasks that serve them.

use super::*;
use crate::stats::meter::Tally;
use crate::tunnel::flow::{self, CHUNK, DEPTH, Key};
use honk_netstack::{AcceptOpts, AcceptWhen, Config, ExcludeSet, Mode};
use tokio::io::{AsyncReadExt, AsyncWriteExt, duplex};
use tokio::sync::mpsc;

/// How late a timer of the paused clock may fire: tokio's timers count whole milliseconds.
const LATE: Duration = Duration::from_millis(10);

/// Lets the relay fill the queue to the driver, and the driver move what the stack takes.
async fn settle_all(wire: &mut Wire, flow: &mut TcpFlow, id: FlowId, scratch: &mut [u8]) {
    for _ in 0..32 {
        for _ in 0..4 {
            tokio::task::yield_now().await;
        }
        assert!(matches!(flow.pump(&mut wire.core, id, scratch), Step::Keep));
        wire.settle();
    }
}

/// After its FIN the client reads a little every two thirds of the deadline. The stack takes
/// each piece from the chunk the driver holds, so the relay, waiting on the full queue, moves
/// nothing all that time; the pieces count as progress, and the flow is due to be cut only a
/// deadline after the client stops. The driver's reset then ends the relay.
#[tokio::test(start_paused = true)]
async fn a_client_that_reads_slowly_keeps_the_flow_in_the_middle_of_a_chunk() {
    let (mut wire, app, id) = Wire::connected();
    let (events, _) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Tcp(id), events);
    let (ends, mut io) = flow::pair(Arc::clone(&waker), Tally::new(None), DRAIN_DEADLINE);
    let mut flow = TcpFlow::new(ends, waker, false);
    flow.connected = true;
    let gate = io.gate();
    let (mut upstream, mut server) = duplex(CHUNK);
    let relay =
        tokio::spawn(
            async move { until_given_up(&gate, splice(&mut io, &mut upstream, &gate)).await },
        );
    // The upstream sends more than the client, the stack and the queue hold, and never closes.
    tokio::spawn(async move {
        let _ = server.write_all(&vec![7; 4 << 20]).await;
        std::future::pending::<()>().await
    });
    wire.client.tcp_shutdown(app).unwrap();
    wire.settle();
    let mut scratch = vec![0; CHUNK];
    settle_all(&mut wire, &mut flow, id, &mut scratch).await;
    // Leave most of a chunk with the driver: the reads below never finish it.
    let held = flow.down_pending.as_ref().map_or(0, Bytes::len);
    if held < CHUNK / 2 {
        wire.take(app, held + 4096);
        settle_all(&mut wire, &mut flow, id, &mut scratch).await;
    }
    let mut held = flow.down_pending.as_ref().map_or(0, Bytes::len);
    assert!(held >= CHUNK / 2, "{held}");
    for _ in 0..3 {
        tokio::time::sleep(DRAIN_DEADLINE * 2 / 3).await;
        assert!(flow.verdict().is_none(), "a client that reads was cut");
        wire.take(app, 4096);
        assert!(matches!(
            flow.pump(&mut wire.core, id, &mut scratch),
            Step::Keep
        ));
        wire.settle();
        let now = flow.down_pending.as_ref().map_or(0, Bytes::len);
        assert!(now > 0 && now < held, "{now} of {held}");
        held = now;
    }
    let stopped = Instant::now();
    tokio::time::sleep_until(stopped + DRAIN_DEADLINE - LATE).await;
    assert!(flow.verdict().is_none(), "cut before the deadline");
    tokio::time::sleep_until(stopped + DRAIN_DEADLINE).await;
    assert!(matches!(flow.verdict(), Some(Step::Expired)));
    assert!(matches!(
        flow.pump(&mut wire.core, id, &mut scratch),
        Step::Expired
    ));
    assert!(!relay.is_finished(), "the relay never ends itself");
    flow.give_up();
    assert!(matches!(relay.await, Ok(None)));
}

/// An app's stack and the core's, back to back, with one connection between them.
struct Wire {
    client: Stack,
    core: Stack,
    packet: Vec<u8>,
}

impl Wire {
    /// The app's flow and the core's.
    fn connected() -> (Wire, FlowId, FlowId) {
        let now = std::time::Instant::now();
        let mut client = Config::new([7; 32]);
        client.local_v4 = Some(std::net::Ipv4Addr::new(172, 19, 0, 1));
        let mut core = Config::new([9; 32]);
        core.mode = Mode::Transparent {
            exclude: ExcludeSet::default(),
        };
        let mut wire = Wire {
            client: Stack::new(client, now),
            core: Stack::new(core, now),
            packet: vec![0; 2048],
        };
        let app = wire.client.tcp_connect(now, ([10, 9, 9, 9], 80).into());
        wire.settle();
        let syn = wire.core.poll_accept().unwrap();
        let now = AcceptOpts {
            when: AcceptWhen::Now,
            ..AcceptOpts::default()
        };
        wire.core.tcp_accept(syn.id, now).unwrap();
        wire.settle();
        (wire, app.unwrap(), syn.id)
    }

    /// The app reads `n` bytes it has received, then the packets move.
    fn take(&mut self, app: FlowId, n: usize) {
        let mut buf = vec![0; n];
        let mut got = 0;
        while got < n {
            got += self.client.tcp_read(app, &mut buf[got..]).unwrap();
        }
        self.settle();
    }

    /// Moves packets both ways until neither stack has one to send.
    fn settle(&mut self) {
        let now = std::time::Instant::now();
        let mut moved = true;
        while moved {
            moved = false;
            while let Some(n) = self.client.poll_transmit(now, &mut self.packet) {
                let _ = self.core.receive(now, &self.packet[..n]);
                moved = true;
            }
            while let Some(n) = self.core.poll_transmit(now, &mut self.packet) {
                let _ = self.client.receive(now, &self.packet[..n]);
                moved = true;
            }
        }
    }
}

/// The task has taken nothing, so the driver holds what it read and leaves the client's FIN
/// in the stack: the drain begins all the same, from when the driver saw the FIN (see
/// [`splice`]).
#[test]
fn the_clients_fin_reaches_the_task_while_the_data_before_it_waits() {
    let (mut wire, app, id) = Wire::connected();
    let (events, _) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Tcp(id), events);
    let (ends, io) = flow::pair(Arc::clone(&waker), Tally::new(None), DRAIN_DEADLINE);
    let up = ends.up.clone().unwrap();
    for _ in 0..DEPTH {
        up.try_send(Bytes::from_static(b"x")).unwrap();
    }
    drop(up);
    let mut flow = TcpFlow::new(ends, waker, true);
    wire.client.tcp_write(app, b"GET").unwrap();
    wire.client.tcp_shutdown(app).unwrap();
    wire.settle();
    let mut scratch = vec![0; CHUNK];
    let seen = Instant::now();
    assert!(matches!(
        flow.pump(&mut wire.core, id, &mut scratch),
        Step::Keep
    ));
    let pumped = Instant::now();
    assert_eq!(flow.up_pending.as_deref(), Some(&b"GET"[..]));
    assert!(!flow.up_eof, "the FIN is still in the stack");
    let deadline = io.gate().deadline().expect("the drain has begun");
    assert!(seen + DRAIN_DEADLINE <= deadline && deadline <= pumped + DRAIN_DEADLINE);
}

/// The task passes the upstream's reply and FIN on and ends cleanly; the driver hands both to
/// the stack, and hears of the end only after the drain's deadline (the process stopped). The
/// tail is the client's: the flow is released, not reset, whether the driver's timer or the
/// task's event comes first.
#[tokio::test(start_paused = true)]
async fn a_tail_handed_over_is_released_however_late_the_end_is_heard_of() {
    let (mut wire, app, id) = Wire::connected();
    let (events, _) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Tcp(id), events);
    let (ends, mut io) = flow::pair(Arc::clone(&waker), Tally::new(None), DRAIN_DEADLINE);
    let mut flow = TcpFlow::new(ends, waker, false);
    flow.connected = true;
    let mut scratch = vec![0; CHUNK];
    wire.client.tcp_write(app, b"GET").unwrap();
    wire.client.tcp_shutdown(app).unwrap();
    wire.settle();
    assert!(matches!(
        flow.pump(&mut wire.core, id, &mut scratch),
        Step::Keep
    ));
    let mut request = Vec::new();
    io.read_to_end(&mut request).await.unwrap();
    assert_eq!(request, b"GET");
    io.write_all(b"200").await.unwrap();
    io.shutdown().await.unwrap();
    let gate = io.gate();
    drop(io);
    assert!(matches!(
        flow.pump(&mut wire.core, id, &mut scratch),
        Step::Keep
    ));
    wire.settle();
    assert!(flow.down_shut && flow.down_pending.is_none());
    // What the task does as it ends, before it sends `Finished`.
    gate.ended();
    tokio::time::advance(2 * DRAIN_DEADLINE).await;
    // The driver's timer settles the flow without I/O, the event with a pump: either releases.
    assert!(matches!(flow.verdict(), Some(Step::Close)), "reset");
    let step = flow.pump(&mut wire.core, id, &mut scratch);
    assert!(matches!(step, Step::Close), "reset");
}

/// A query arrives as the driver gives the DNS flow up: the task does not answer it, though it
/// could at once. A select without `biased` picks its first branch at random: 32 rounds.
#[tokio::test]
async fn a_flow_given_up_moves_nothing_more_though_its_io_is_ready() {
    let (_wire, _app, id) = Wire::connected();
    let session = Arc::new(Session::new(1, std::sync::Weak::new()));
    let mut answered = 0;
    for _ in 0..32 {
        let (events, _) = mpsc::unbounded_channel();
        let waker = Waker::new(Key::Tcp(id), events.clone());
        let (mut ends, io) = flow::pair(waker, Tally::new(None), DRAIN_DEADLINE);
        let gate = io.gate();
        let via = (Route::Direct, Arc::clone(&session));
        let none: Arc<[SocketAddr]> = Arc::from([]);
        let task = tokio::spawn(serve_dns(io, id, via, none, DRAIN_DEADLINE, events));
        tokio::task::yield_now().await;
        let q = crate::dns::tests::query(7, "example.com", None);
        let mut framed = u16::try_from(q.len()).unwrap().to_be_bytes().to_vec();
        framed.extend_from_slice(&q);
        let up = ends.up.take().unwrap();
        up.try_send(Bytes::from(framed)).unwrap();
        gate.give_up();
        task.await.unwrap();
        answered += usize::from(ends.down.try_recv().is_ok());
    }
    assert_eq!(answered, 0, "rounds answered after the cancel");
}

/// The task sees the first FIN, the upstream's: the drain wakes the driver, which queues the
/// deadline once. A later FIN changes nothing and a byte moved puts the deadline off, so neither
/// queues an entry; the one queued comes, finds the flow alive, and the later deadline is queued
/// in its place.
#[tokio::test(start_paused = true)]
async fn the_first_fin_from_the_task_wakes_the_driver_to_queue_the_drain() {
    let (_wire, _app, id) = Wire::connected();
    let (events, mut woken) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Tcp(id), events);
    let (ends, io) = flow::pair(Arc::clone(&waker), Tally::new(None), DRAIN_DEADLINE);
    let mut flow = TcpFlow::new(ends, waker, false);
    assert_eq!(flow.due(), None, "no drain before a FIN");
    let gate = io.gate();
    gate.fin();
    assert!(matches!(woken.try_recv(), Ok(Event::Wake(Key::Tcp(k))) if k == id));
    flow.waker.rearm();
    let first = flow.due().unwrap();
    assert_eq!(first, Instant::now() + DRAIN_DEADLINE);
    assert_eq!(flow.due(), None, "queued once");
    tokio::time::sleep(DRAIN_DEADLINE / 2).await;
    gate.fin();
    gate.moved();
    assert!(woken.try_recv().is_err(), "woken for no earlier deadline");
    assert_eq!(flow.due(), None);
    tokio::time::sleep_until(first).await;
    assert!(flow.fires(first));
    assert!(flow.verdict().is_none());
    assert_eq!(flow.due(), Some(first + DRAIN_DEADLINE / 2));
    assert!(!flow.fires(first), "an entry the flow no longer waits for");
}

/// A DNS query for `name` as the client frames it over TCP.
fn framed(id: u16, name: &str) -> Vec<u8> {
    let q = crate::dns::tests::query(id, name, None);
    let mut framed = u16::try_from(q.len()).unwrap().to_be_bytes().to_vec();
    framed.extend_from_slice(&q);
    framed
}

/// The core's DNS for flow `id`, with no upstream: each query is answered SERVFAIL at once.
fn serve(io: FlowIo, id: FlowId, events: Events) -> tokio::task::JoinHandle<()> {
    let session = Arc::new(Session::new(1, std::sync::Weak::new()));
    let none: Arc<[SocketAddr]> = Arc::from([]);
    let via = (Route::Direct, session);
    tokio::spawn(serve_dns(io, id, via, none, DRAIN_DEADLINE, events))
}

/// After the client's FIN the upstream is slow to answer; the answer is queued for the client, and
/// the stack cannot take it yet. Queuing it moved the answer: the deadline counts from there.
#[tokio::test(start_paused = true)]
async fn after_the_fin_a_dns_answer_queued_puts_the_deadline_off() {
    let (_wire, _app, id) = Wire::connected();
    let (events, _got) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Tcp(id), events.clone());
    let (mut ends, io) = flow::pair(waker, Tally::new(None), DRAIN_DEADLINE);
    let answered = crate::tunnel::pause::hold(crate::tunnel::pause::Point::Answered);
    let _task = serve(io, id, events);
    // What the driver passes on: the query, then the client's FIN.
    let up = ends.up.take().unwrap();
    up.try_send(Bytes::from(framed(7, "example.com"))).unwrap();
    drop(up);
    let fin = Instant::now();
    ends.gate.fin();
    answered.reached().await;
    tokio::time::advance(DRAIN_DEADLINE * 2 / 3).await;
    answered.release();
    // The driver takes the answer, which the stack does not take.
    let _held = ends.down.recv().await.unwrap();
    tokio::time::sleep_until(fin + DRAIN_DEADLINE).await;
    assert!(!ends.gate.read().expired, "cut a deadline after the FIN");
}
