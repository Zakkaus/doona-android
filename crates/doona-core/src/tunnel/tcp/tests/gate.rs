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

/// A task's waker whose first clone holds the cloning thread until the test lets it go: as if the
/// task's thread were descheduled while it registers to be woken.
struct Holding {
    entered: std::sync::Mutex<Option<std::sync::mpsc::Sender<()>>>,
    release: std::sync::Mutex<std::sync::mpsc::Receiver<()>>,
    woken: std::sync::atomic::AtomicBool,
}

impl Holding {
    /// The waker, and the test's ends: told when the clone holds, and to let it go.
    fn waker() -> (
        std::task::Waker,
        Arc<Holding>,
        std::sync::mpsc::Receiver<()>,
        std::sync::mpsc::Sender<()>,
    ) {
        let (entered, held) = std::sync::mpsc::channel();
        let (release, released) = std::sync::mpsc::channel();
        let holding = Arc::new(Holding {
            entered: std::sync::Mutex::new(Some(entered)),
            release: std::sync::Mutex::new(released),
            woken: std::sync::atomic::AtomicBool::new(false),
        });
        let raw = std::task::RawWaker::new(Arc::into_raw(Arc::clone(&holding)).cast(), &HOLDING);
        // SAFETY: the data is an `Arc<Holding>` reference, which `HOLDING`'s functions keep.
        let waker = unsafe { std::task::Waker::from_raw(raw) };
        (waker, holding, held, release)
    }
}

static HOLDING: std::task::RawWakerVTable = std::task::RawWakerVTable::new(
    holding_clone,
    holding_wake,
    holding_wake_by_ref,
    holding_drop,
);

unsafe fn holding_clone(data: *const ()) -> std::task::RawWaker {
    // SAFETY: `data` is an `Arc<Holding>` reference, alive while the waker cloned is.
    let holding = unsafe { &*data.cast::<Holding>() };
    if let Some(entered) = holding.entered.lock().unwrap().take() {
        let _ = entered.send(());
        let _ = holding.release.lock().unwrap().recv();
    }
    // SAFETY: as above; the clone owns one more reference.
    unsafe { Arc::increment_strong_count(data.cast::<Holding>()) };
    std::task::RawWaker::new(data, &HOLDING)
}

unsafe fn holding_wake(data: *const ()) {
    // SAFETY: the waker woken owns the reference it gives up.
    unsafe {
        holding_wake_by_ref(data);
        holding_drop(data);
    }
}

unsafe fn holding_wake_by_ref(data: *const ()) {
    // SAFETY: `data` is an `Arc<Holding>` reference, alive while the waker is.
    let holding = unsafe { &*data.cast::<Holding>() };
    holding
        .woken
        .store(true, std::sync::atomic::Ordering::SeqCst);
}

unsafe fn holding_drop(data: *const ()) {
    // SAFETY: the waker dropped owns this reference.
    unsafe { Arc::decrement_strong_count(data.cast::<Holding>()) };
}

/// The relay registers to be woken at its give-up, and its thread is descheduled while it does
/// (see [`Holding`]). The driver gives the flow up meanwhile: it waits for nothing of the relay's,
/// and the relay sees the give-up.
#[test]
fn a_give_up_does_not_wait_for_the_relay_registering_to_be_woken() {
    let (_wire, _app, id) = Wire::connected();
    let (_flow, io) = relay_flow(id);
    let gate = io.gate();
    let mut given_up = Box::pin(gate.given_up());
    let noop = &mut std::task::Context::from_waker(std::task::Waker::noop());
    assert!(given_up.as_mut().poll(noop).is_pending());
    let (waker, holding, held, release) = Holding::waker();
    let (waited, seen) = std::thread::scope(|s| {
        let relay = s.spawn(move || {
            let cx = &mut std::task::Context::from_waker(&waker);
            given_up.as_mut().poll(cx).is_ready()
        });
        let registering = held.recv_timeout(Duration::from_secs(5)).is_ok();
        let (done, gave_up) = std::sync::mpsc::channel();
        let gate = &gate;
        let driver = s.spawn(move || {
            gate.give_up();
            let _ = done.send(());
        });
        let waited = gave_up.recv_timeout(Duration::from_secs(2)).is_err();
        let _ = release.send(());
        let ready = relay.join().unwrap();
        driver.join().unwrap();
        assert!(registering, "the relay never registered");
        (waited, ready)
    });
    assert!(!waited, "the give-up waited for the relay's registration");
    let woken = holding.woken.load(std::sync::atomic::Ordering::SeqCst);
    assert!(seen || woken, "the relay missed its give-up");
}

/// The upstream's FIN has passed to the client, and the client's FIN begun the drain. The driver
/// looks at the gate before the deadline; the relay's last shutdown enters then, and the deadline
/// passes before the driver reads the clock. The driver judges the gate as it is at that instant,
/// a poll in progress: it waits for that shutdown, which ends the relay cleanly, and does not
/// reset the flow.
#[test]
#[cfg_attr(
    not(debug_assertions),
    ignore = "the driver's test hooks run in debug builds"
)]
fn a_shutdown_that_enters_as_the_driver_reads_the_gate_is_waited_for() {
    const IDLE: Duration = Duration::from_secs(3);
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_time()
        .start_paused(true)
        .build()
        .unwrap();
    let clock = runtime.handle().clone();
    let _paused = runtime.enter();
    let (_wire, _app, id) = Wire::connected();
    let (events, mut woken) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Tcp(id), events);
    let (_ends, io) = flow::pair(Arc::clone(&waker), Tally::new(None), IDLE, Task::Relay);
    let gate = io.gate();
    assert!(
        gate.poll_shutdown(|| std::task::Poll::Ready(Ok(())))
            .is_ready()
    );
    gate.fin();
    while woken.try_recv().is_ok() {}
    waker.rearm();
    let (start, started) = std::sync::mpsc::channel();
    let (entered, in_shutdown) = std::sync::mpsc::channel();
    let (release, released) = std::sync::mpsc::channel::<()>();
    let (relay_gate, relay_clock) = (Arc::clone(&gate), clock.clone());
    let (first, joined) = std::thread::scope(|s| {
        let relay = s.spawn(move || {
            let _paused = relay_clock.enter();
            started.recv().unwrap();
            relay_gate.poll_shutdown(|| {
                entered.send(()).unwrap();
                let _ = released.recv();
                std::task::Poll::Ready(Ok(()))
            })
        });
        let late = clock.clone();
        pause::then_at(pause::Point::Reading, move || {
            start.send(()).unwrap();
            in_shutdown.recv().unwrap();
            late.block_on(tokio::time::advance(IDLE));
        });
        let first = gate.read();
        let _ = release.send(());
        (first, relay.join().unwrap())
    });
    assert!(joined.is_ready());
    assert!(
        first.busy && !first.expired,
        "judged on what it saw before the shutdown"
    );
    assert!(
        woken.try_recv().is_ok(),
        "the shutdown's end did not wake the driver"
    );
    assert!(gate.read().ended, "the shutdown did not end the relay");
}

/// The client's FIN begun the drain; a write of the relay enters before the deadline and holds its
/// thread past it. The driver finds the deadline passed, and the write ends, a byte moved, before
/// the driver takes the gate's state: judged at the same instant on that state, the deadline counts
/// from the write's entry, and the flow is kept.
#[test]
#[cfg_attr(
    not(debug_assertions),
    ignore = "the driver's test hooks run in debug builds"
)]
fn a_byte_moved_as_the_driver_takes_the_gate_keeps_the_flow() {
    const IDLE: Duration = Duration::from_secs(3);
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_time()
        .start_paused(true)
        .build()
        .unwrap();
    let clock = runtime.handle().clone();
    let _paused = runtime.enter();
    let (_wire, _app, id) = Wire::connected();
    let (events, _woken) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Tcp(id), events);
    let (_ends, io) = flow::pair(waker, Tally::new(None), IDLE, Task::Relay);
    let gate = io.gate();
    gate.fin();
    runtime.block_on(tokio::time::advance(Duration::from_secs(2)));
    let (entered, in_write) = std::sync::mpsc::channel();
    let (release, released) = std::sync::mpsc::channel::<()>();
    let (relay_gate, relay_clock) = (Arc::clone(&gate), clock.clone());
    let relay = std::thread::spawn(move || {
        let _paused = relay_clock.enter();
        relay_gate.poll_write(|| {
            entered.send(()).unwrap();
            let _ = released.recv();
            std::task::Poll::Ready(Ok(1))
        })
    });
    in_write.recv().unwrap();
    runtime.block_on(tokio::time::advance(Duration::from_secs(2)));
    let ended = Arc::new(std::sync::Mutex::new(None));
    let wrote = Arc::clone(&ended);
    pause::then_at(pause::Point::Taking, move || {
        let _ = release.send(());
        *wrote.lock().unwrap() = Some(relay.join().unwrap());
    });
    let reading = gate.read();
    let wrote = ended.lock().unwrap().take();
    assert!(wrote.is_some_and(|w| w.is_ready()), "the write did not end");
    assert!(!reading.expired && !reading.busy, "cut though a byte moved");
    let due = gate.deadline().map(|at| at - tokio::time::Instant::now());
    assert_eq!(
        due,
        Some(Duration::from_secs(1)),
        "not counted from the write's entry"
    );
}
