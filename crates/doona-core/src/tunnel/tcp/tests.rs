//! The driver's side of TCP flows against a real stack, and the tasks that serve them.

use super::*;
use crate::stats::meter::Tally;
use crate::tunnel::flow::{self, CHUNK, DEPTH, Key};
use crate::tunnel::gate::Task;
use crate::tunnel::pause;
use crate::tunnel::splice::splice;
use honk_netstack::{AcceptOpts, AcceptWhen, Config, ExcludeSet, Mode};
use tokio::io::{AsyncReadExt, AsyncWriteExt, duplex};
use tokio::sync::mpsc;

mod gate;

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
    let relay = Task::Relay;
    let (ends, mut io) = flow::pair(Arc::clone(&waker), Tally::new(None), DRAIN_DEADLINE, relay);
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
    assert!(flow.stop(Stop::Reset), "a poll of the relay in progress");
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
        let (app, id) = wire.connect(80);
        (wire, app, id)
    }

    /// Another connection, to `port`: the app's flow and the core's.
    fn connect(&mut self, port: u16) -> (FlowId, FlowId) {
        let now = std::time::Instant::now();
        let app = self.client.tcp_connect(now, ([10, 9, 9, 9], port).into());
        self.settle();
        let syn = self.core.poll_accept().unwrap();
        let now = AcceptOpts {
            when: AcceptWhen::Now,
            ..AcceptOpts::default()
        };
        self.core.tcp_accept(syn.id, now).unwrap();
        self.settle();
        (app.unwrap(), syn.id)
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
    let relay = Task::Relay;
    let (ends, io) = flow::pair(Arc::clone(&waker), Tally::new(None), DRAIN_DEADLINE, relay);
    let up = ends.up.clone().unwrap();
    for _ in 0..DEPTH {
        up.try_send(Bytes::from_static(b"x")).unwrap();
    }
    drop(up);
    let mut flow = TcpFlow::new(ends, waker, false);
    flow.connected = true;
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

/// The relay passes the upstream's reply and FIN on and ends cleanly; the driver hands both to
/// the stack. Nothing after the relay tells the driver it ended (its task is descheduled there,
/// past the deadline): the end is published by the relay's last shutdown, so the flow is released
/// at once, and its timer finds it released too.
#[tokio::test(start_paused = true)]
async fn a_tail_handed_over_is_released_however_late_the_end_is_heard_of() {
    let (mut wire, app, id) = Wire::connected();
    let (events, _) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Tcp(id), events);
    let relay = Task::Relay;
    let (ends, mut io) = flow::pair(Arc::clone(&waker), Tally::new(None), DRAIN_DEADLINE, relay);
    let mut flow = TcpFlow::new(ends, waker, false);
    flow.connected = true;
    let gate = io.gate();
    let (mut upstream, mut server) = duplex(CHUNK);
    let relay = tokio::spawn(async move { splice(&mut io, &mut upstream, &gate).await });
    let mut scratch = vec![0; CHUNK];
    wire.client.tcp_write(app, b"GET").unwrap();
    wire.client.tcp_shutdown(app).unwrap();
    wire.settle();
    assert!(matches!(
        flow.pump(&mut wire.core, id, &mut scratch),
        Step::Keep
    ));
    let mut request = Vec::new();
    server.read_to_end(&mut request).await.unwrap();
    assert_eq!(request, b"GET");
    server.write_all(b"200").await.unwrap();
    drop(server);
    relay.await.unwrap().unwrap();
    let step = flow.pump(&mut wire.core, id, &mut scratch);
    wire.settle();
    assert!(flow.down_shut && flow.down_pending.is_none());
    assert!(matches!(step, Step::Close), "not released");
    tokio::time::advance(2 * DRAIN_DEADLINE).await;
    assert!(matches!(flow.verdict(), Some(Step::Close)), "reset");
}

/// A query arrives as the driver gives the DNS flow up: the task does not answer it, though it
/// could at once. A select without `biased` picks its first branch at random: 32 rounds.
#[tokio::test]
async fn a_flow_given_up_moves_nothing_more_though_its_io_is_ready() {
    let (_wire, _app, id) = Wire::connected();
    let session = Arc::new(Session::new(1, std::sync::Weak::new()));
    let honk = crate::dns::tests::rejecting();
    let mut answered = 0;
    for _ in 0..32 {
        let (events, _) = mpsc::unbounded_channel();
        let waker = Waker::new(Key::Tcp(id), events.clone());
        let (mut ends, io) = flow::pair(waker, Tally::new(None), DRAIN_DEADLINE, Task::Dns);
        let gate = io.gate();
        let via = (Some(Arc::clone(&honk)), Arc::clone(&session));
        let task = tokio::spawn(serve_dns(io, id, via, (CLIENT, None), events));
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
    let relay = Task::Relay;
    let (ends, io) = flow::pair(Arc::clone(&waker), Tally::new(None), DRAIN_DEADLINE, relay);
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

/// The app that sends the DNS queries.
const CLIENT: SocketAddr = SocketAddr::new(
    std::net::IpAddr::V4(std::net::Ipv4Addr::new(10, 0, 0, 2)),
    40_000,
);

/// The core's DNS for flow `id` under a configuration that rejects every query these tests send:
/// each is answered at once, without a dial.
fn serve(io: FlowIo, id: FlowId, events: Events) -> tokio::task::JoinHandle<()> {
    let session = Arc::new(Session::new(1, std::sync::Weak::new()));
    let via = (Some(crate::dns::tests::rejecting()), session);
    tokio::spawn(serve_dns(io, id, via, (CLIENT, None), events))
}

/// The client sends a query and its FIN; the core's DNS answers and ends cleanly, and the driver
/// hands the answer and the FIN to the stack. The task is descheduled before it reports its end,
/// past the deadline: the session is released, not reset.
#[tokio::test(start_paused = true)]
async fn a_dns_session_that_ended_cleanly_is_released_however_late_its_task_runs() {
    let (mut wire, app, id) = Wire::connected();
    let (events, _got) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Tcp(id), events.clone());
    let (ends, io) = flow::pair(
        Arc::clone(&waker),
        Tally::new(None),
        DRAIN_DEADLINE,
        Task::Dns,
    );
    let mut flow = TcpFlow::new(ends, waker, true);
    let served = crate::tunnel::pause::hold(crate::tunnel::pause::Point::Served);
    let task = serve(io, id, events);
    wire.client
        .tcp_write(app, &framed(7, "example.com"))
        .unwrap();
    wire.client.tcp_shutdown(app).unwrap();
    wire.settle();
    let mut scratch = vec![0; CHUNK];
    assert!(matches!(
        flow.pump(&mut wire.core, id, &mut scratch),
        Step::Keep
    ));
    served.reached().await;
    let step = flow.pump(&mut wire.core, id, &mut scratch);
    wire.settle();
    assert!(flow.down_shut && flow.down_pending.is_none());
    tokio::time::advance(2 * DRAIN_DEADLINE).await;
    // Settled by the pump, or else by the driver's timer.
    let settled = match step {
        Step::Keep => flow.verdict(),
        step => Some(step),
    };
    assert!(matches!(settled, Some(Step::Close)), "reset");
    served.release();
    task.await.unwrap();
}

/// After the client's FIN the upstream is slow to answer; the answer is queued for the client, and
/// the stack cannot take it yet. Queuing it moved the answer: the deadline counts from there.
#[tokio::test(start_paused = true)]
async fn after_the_fin_a_dns_answer_queued_puts_the_deadline_off() {
    let (_wire, _app, id) = Wire::connected();
    let (events, _got) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Tcp(id), events.clone());
    let (mut ends, io) = flow::pair(waker, Tally::new(None), DRAIN_DEADLINE, Task::Dns);
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

/// After its FIN the client reads a little every two thirds of the deadline: each time the stack
/// takes part of the answer the driver holds, while the next answers wait behind it, one in the
/// task. The session is kept as long as the client reads.
#[tokio::test(start_paused = true)]
async fn a_dns_client_that_keeps_reading_a_little_keeps_its_session() {
    let (_wire, _app, id) = Wire::connected();
    let (events, _got) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Tcp(id), events.clone());
    let (mut ends, io) = flow::pair(waker, Tally::new(None), DRAIN_DEADLINE, Task::Dns);
    let task = serve(io, id, events);
    // Six queries and the FIN: one answer for the driver, DEPTH in the queue, one in the task.
    let mut queries = Vec::new();
    for i in 0..u16::try_from(DEPTH + 2).unwrap() {
        queries.extend_from_slice(&framed(i, "example.com"));
    }
    let up = ends.up.take().unwrap();
    up.try_send(Bytes::from(queries)).unwrap();
    drop(up);
    ends.gate.fin();
    for _ in 0..8 {
        tokio::task::yield_now().await;
    }
    let _held = ends.down.try_recv().unwrap();
    for _ in 0..8 {
        tokio::task::yield_now().await;
    }
    for _ in 0..3 {
        tokio::time::sleep(DRAIN_DEADLINE * 2 / 3).await;
        // What the driver reports as the stack takes part of the answer it holds.
        ends.gate.moved();
        assert!(
            !task.is_finished(),
            "the session ended though the client reads"
        );
        assert!(!ends.gate.read().expired);
    }
}

/// A DNS client that sends nothing more is reset once the session has moved nothing for the idle
/// time, whether or not it sent its FIN: the drain begins at accept, and the driver queues its
/// deadline then, as no event may come.
#[tokio::test(start_paused = true)]
async fn a_silent_dns_client_is_reset_an_idle_time_after_the_session_last_moved() {
    let (_wire, _app, id) = Wire::connected();
    let (events, _got) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Tcp(id), events.clone());
    let accepted = Instant::now();
    let (mut ends, io) = flow::pair(
        Arc::clone(&waker),
        Tally::new(None),
        DRAIN_DEADLINE,
        Task::Dns,
    );
    let up = ends.up.take().unwrap();
    let mut flow = TcpFlow::new(ends, waker, true);
    assert_eq!(
        flow.due(),
        Some(accepted + DRAIN_DEADLINE),
        "not queued at accept"
    );
    let _task = serve(io, id, events);
    tokio::time::sleep(DRAIN_DEADLINE * 2 / 3).await;
    assert!(flow.verdict().is_none());
    // A query, answered; the driver's stack takes the answer at once.
    up.try_send(Bytes::from(framed(7, "example.com"))).unwrap();
    let answered = Instant::now();
    let _answer = flow.ends.down.recv().await.unwrap();
    flow.ends.gate.moved();
    tokio::time::sleep_until(answered + DRAIN_DEADLINE - LATE).await;
    assert!(flow.verdict().is_none(), "cut before the idle time");
    tokio::time::sleep_until(answered + DRAIN_DEADLINE).await;
    assert!(matches!(flow.verdict(), Some(Step::Expired)));
}

/// The client sends a query and keeps its side open; the resolver answers after more than the
/// drain time, which is shorter than the resolver's own timeout. Waiting for the resolver is not
/// idle: the session is not cut, and the answer reaches the client.
#[tokio::test(start_paused = true)]
async fn a_query_the_resolver_answers_after_the_drain_time_is_answered() {
    let (_wire, _app, id) = Wire::connected();
    let (events, _got) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Tcp(id), events.clone());
    let drain = Duration::from_secs(3);
    let (mut ends, io) = flow::pair(waker, Tally::new(None), drain, Task::Dns);
    let resolving = pause::hold(pause::Point::Resolving);
    let _task = serve(io, id, events);
    let up = ends.up.take().unwrap();
    up.try_send(Bytes::from(framed(7, "example.com"))).unwrap();
    resolving.reached().await;
    // The resolver answers 4 s later.
    tokio::time::advance(Duration::from_secs(4)).await;
    assert!(!ends.gate.read().expired, "cut while the resolver works");
    resolving.release();
    let answer = ends.down.recv().await;
    assert!(answer.is_some_and(|a| a.len() > 2), "no answer");
}

/// As above, but the client sends its FIN while the resolver still works: the wait is idle from
/// the FIN on, and the session is cut a drain time after the FIN, not at once.
#[tokio::test(start_paused = true)]
async fn waiting_for_the_resolver_is_idle_from_the_clients_fin_on() {
    let (_wire, _app, id) = Wire::connected();
    let (events, _got) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Tcp(id), events.clone());
    let drain = Duration::from_secs(3);
    let (mut ends, io) = flow::pair(waker, Tally::new(None), drain, Task::Dns);
    let resolving = pause::hold(pause::Point::Resolving);
    let _task = serve(io, id, events);
    let up = ends.up.take().unwrap();
    up.try_send(Bytes::from(framed(7, "example.com"))).unwrap();
    resolving.reached().await;
    let deadline = Some(Instant::now() + crate::dns::RESOLVE_BOUND + drain);
    assert_eq!(ends.gate.deadline(), deadline, "the wait counts as idle");
    tokio::time::advance(Duration::from_secs(4)).await;
    // What the driver does when it sees the client's FIN.
    let fin = Instant::now();
    ends.gate.fin();
    assert!(!ends.gate.read().expired, "cut at the FIN");
    assert_eq!(ends.gate.deadline(), Some(fin + drain));
    tokio::time::advance(drain).await;
    assert!(
        ends.gate.read().expired,
        "the wait after the FIN is not idle"
    );
}

/// The resolver never returns, its own timeout included (its write holds its thread in a lock of
/// the outbound), and the client keeps its side open. The driver cuts the session on its own: the
/// resolver's bound, then a drain time, after the query.
#[tokio::test(start_paused = true)]
async fn a_resolver_that_never_returns_is_cut_its_bound_and_a_drain_time_after_the_query() {
    let (_wire, _app, id) = Wire::connected();
    let (events, _got) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Tcp(id), events.clone());
    let drain = Duration::from_secs(3);
    let (mut ends, io) = flow::pair(waker, Tally::new(None), drain, Task::Dns);
    let resolving = pause::hold(pause::Point::Resolving);
    let _task = serve(io, id, events);
    let up = ends.up.take().unwrap();
    up.try_send(Bytes::from(framed(7, "example.com"))).unwrap();
    resolving.reached().await;
    let cut = Instant::now() + crate::dns::RESOLVE_BOUND + drain;
    assert_eq!(
        ends.gate.deadline(),
        Some(cut),
        "no deadline while the resolver works"
    );
    tokio::time::advance(cut - Instant::now() - Duration::from_millis(1)).await;
    assert!(
        !ends.gate.read().expired,
        "cut within the resolver's bound and a drain time"
    );
    tokio::time::advance(Duration::from_millis(1)).await;
    assert!(ends.gate.read().expired, "the resolver holds the session");
}
