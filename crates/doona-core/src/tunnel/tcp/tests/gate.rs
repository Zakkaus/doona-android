//! The driver against flows whose task holds its thread in the middle of a poll of the gate (see
//! `pause::stuck`): it waits for none, and decides on such a flow once the poll has ended.

use super::*;

/// A relay flow `id` as the driver holds it, connected, and its task's end.
fn relay_flow(id: FlowId) -> (TcpFlow, FlowIo) {
    let (events, _) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Tcp(id), events);
    let relay = Task::Relay;
    let (ends, io) = flow::pair(Arc::clone(&waker), Tally::new(None), DRAIN_DEADLINE, relay);
    let mut flow = TcpFlow::new(ends, waker, false);
    flow.connected = true;
    (flow, io)
}

/// Flow A's task is in the middle of a write upstream that holds its thread, as a Hysteria2 write
/// waits for Quinn's connection lock (see `pause::stuck`). Meanwhile the driver serves A, whose
/// client sent its FIN, then B, whose client sent a request: it waits for nothing of A's, and B's
/// request reaches B's task.
#[test]
fn a_poll_that_holds_its_gate_does_not_stop_the_driver_serving_another_flow() {
    let (mut wire, app_a, a) = Wire::connected();
    let (app_b, b) = wire.connect(81);
    let (mut flow_a, io_a) = relay_flow(a);
    let (mut flow_b, mut io_b) = relay_flow(b);
    let gate_a = io_a.gate();
    // A's relay takes its client's request; its write upstream holds its thread.
    let up = flow_a.ends.up.as_ref().unwrap();
    up.try_send(Bytes::from_static(b"GET")).unwrap();
    let (upstream, unstick) = pause::stuck(pause::Stall::Write);
    let relay = pause::relay_apart(io_a, upstream);
    assert!(unstick.entered(), "the relay never wrote");
    wire.client.tcp_shutdown(app_a).unwrap();
    wire.client.tcp_write(app_b, b"GET").unwrap();
    wire.settle();
    // The driver, on a thread of its own: an event of A, then one of B.
    let (done, served) = std::sync::mpsc::channel();
    let driver = std::thread::spawn(move || {
        let mut scratch = vec![0; CHUNK];
        let a = flow_a.pump(&mut wire.core, a, &mut scratch);
        let b = flow_b.pump(&mut wire.core, b, &mut scratch);
        let _ = done.send(());
        (matches!((a, b), (Step::Keep, Step::Keep)), flow_a, flow_b)
    });
    let waited = served.recv_timeout(Duration::from_secs(5)).is_err();
    unstick.release();
    let (kept, _flow_a, _flow_b) = driver.join().unwrap();
    assert!(!waited, "the driver waited for flow A's write");
    assert!(kept);
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    let mut request = [0; 3];
    runtime.block_on(io_b.read_exact(&mut request)).unwrap();
    assert_eq!(&request, b"GET", "B's request did not reach its task");
    gate_a.give_up();
    relay.join().unwrap();
}

/// The upstream's FIN begins the drain and has passed to the client; the relay's last shutdown,
/// the upstream's after the client's FIN, holds its thread when the deadline passes. The driver
/// neither waits nor resets the flow, as that shutdown may yet end the task cleanly: its exit wakes
/// the driver, which releases the flow then.
#[test]
fn a_deadline_that_passes_in_the_last_shutdown_waits_for_it_and_the_flow_is_released() {
    // Real time, from the relay's start: wide enough for the relay to enter its last shutdown first.
    const IDLE: Duration = Duration::from_secs(2);
    let (mut wire, app, id) = Wire::connected();
    let (events, mut woken) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Tcp(id), events);
    let (ends, io) = flow::pair(Arc::clone(&waker), Tally::new(None), IDLE, Task::Relay);
    let mut flow = TcpFlow::new(ends, waker, false);
    flow.connected = true;
    let (upstream, unstick) = pause::stuck(pause::Stall::Shutdown);
    let relay = pause::relay_apart(io, upstream);
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    // The upstream's FIN: the relay shuts the client's side, and the driver passes it on.
    assert_eq!(runtime.block_on(flow.ends.down.recv()), None);
    let closed = std::time::Instant::now();
    let mut scratch = vec![0; CHUNK];
    let step = flow.pump(&mut wire.core, id, &mut scratch);
    assert!(matches!(step, Step::Keep) && flow.down_shut);
    // The client's FIN: the relay shuts the upstream, and holds there.
    wire.client.tcp_shutdown(app).unwrap();
    wire.settle();
    let step = flow.pump(&mut wire.core, id, &mut scratch);
    assert!(matches!(step, Step::Keep));
    assert!(unstick.entered(), "the relay never shut the upstream");
    let late = closed + IDLE + Duration::from_millis(50);
    std::thread::sleep(late.saturating_duration_since(std::time::Instant::now()));
    while woken.try_recv().is_ok() {}
    flow.waker.rearm();
    let step = flow.verdict();
    assert!(matches!(step, Some(Step::Busy)), "decided in the shutdown");
    unstick.release();
    let wake = runtime
        .block_on(async { tokio::time::timeout(Duration::from_secs(5), woken.recv()).await });
    assert!(wake.is_ok(), "the shutdown's end did not wake the driver");
    let step = flow.verdict();
    assert!(matches!(step, Some(Step::Close)), "not released");
    relay.join().unwrap();
}
