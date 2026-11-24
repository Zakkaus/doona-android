//! `start` and `stop` against stand-in drivers that are slow or stuck, which the real driver
//! never is on a host: the tun must be closed when `stop` (or a failed `start`) returns, and
//! `start` must keep its deadline and answer `busy` when a `stop` or another `start` overlaps it.
//! A task of the real driver's generation that panics shows as `internal` in the snapshot, and the
//! real driver serves a flow queued before `start` only once the generation is committed.

mod bootstrap;
#[path = "../../tests/common/stop_wait.rs"]
mod integration_stop_wait;

use super::tests_catalog::{Feeds, add, path};
use super::{Core, Life, lock};
use crate::dto::{ErrorCode, Mode, Snapshot};
use crate::state::Store;
use crate::testutil::client::Client;
use crate::testutil::{TempDir, closed, tun};
use crate::tunnel::route::{Inbound, Route, Upstream};
use crate::tunnel::{Gen, STOP_WAIT, Session, Settings, StartSignal, TunHandle};
use futures_util::future::BoxFuture;
use std::io;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering::SeqCst};
use std::sync::{Arc, Condvar, Mutex, mpsc};
use std::time::{Duration, Instant};
use tokio_util::sync::CancellationToken;

const CONFIG: &str =
    r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":["192.168.8.1"]}"#;
/// How long a stuck driver blocks its worker thread.
const STUCK: Duration = Duration::from_secs(3);
/// How long each generation holds its worker in the tests that hold both of them.
const HELD: Duration = Duration::from_secs(5);

/// The integration tests' copy of the stop budget is the core's.
#[test]
fn integration_stop_wait_matches_core() {
    assert_eq!(integration_stop_wait::STOP_WAIT, STOP_WAIT);
}

/// Ready at once, then blocks its worker without yielding, as a driver caught in a long
/// operation would.
fn stuck(tun: TunHandle, _: Settings, _: Gen, ready: StartSignal) -> BoxFuture<'static, ()> {
    Box::pin(async move {
        let _held = tun;
        ready.send();
        std::thread::sleep(STUCK);
    })
}

/// Blocks its worker without ever becoming ready.
fn stuck_unready(
    tun: TunHandle,
    _: Settings,
    _: Gen,
    ready: StartSignal,
) -> BoxFuture<'static, ()> {
    Box::pin(async move {
        let _held = (tun, ready);
        std::thread::sleep(STUCK);
    })
}

/// The first generation is stuck; later ones never become ready.
fn stuck_then_unready(
    tun: TunHandle,
    _: Settings,
    gen_: Gen,
    ready: StartSignal,
) -> BoxFuture<'static, ()> {
    Box::pin(async move {
        let _held = tun;
        if gen_.session.generation == 1 {
            ready.send();
            std::thread::sleep(STUCK);
        } else {
            let _ready = ready;
            std::future::pending::<()>().await;
        }
    })
}

/// Ready at once, then holds its worker for [`HELD`].
fn held(tun: TunHandle, _: Settings, _: Gen, ready: StartSignal) -> BoxFuture<'static, ()> {
    Box::pin(async move {
        let _held = tun;
        ready.send();
        std::thread::sleep(HELD);
    })
}

/// The first generation as [`held`]; later ones hold their worker without becoming ready.
fn held_then_unready(
    tun: TunHandle,
    _: Settings,
    gen_: Gen,
    ready: StartSignal,
) -> BoxFuture<'static, ()> {
    Box::pin(async move {
        let _held = tun;
        let _unsent = if gen_.session.generation == 1 {
            ready.send();
            None
        } else {
            Some(ready)
        };
        std::thread::sleep(HELD);
    })
}

static LATE_START: Mutex<Option<(mpsc::Sender<CancellationToken>, mpsc::Receiver<()>)>> =
    Mutex::new(None);

fn ready_soon(
    tun: TunHandle,
    _: Settings,
    gen_: Gen,
    ready: StartSignal,
) -> BoxFuture<'static, ()> {
    let (entered, release) = lock(&LATE_START).take().unwrap();
    entered.send(gen_.cancel).unwrap();
    release.recv().unwrap();
    ready.send();
    Box::pin(async move {
        let _held = tun;
        std::future::pending::<()>().await;
    })
}

/// Keeps the start pending until it is cancelled or reaches its deadline.
fn slow(tun: TunHandle, _: Settings, _: Gen, ready: StartSignal) -> BoxFuture<'static, ()> {
    Box::pin(async move {
        let _held = (tun, ready);
        std::future::pending::<()>().await;
    })
}

/// The real driver, with a task of its generation that panics 200 ms in.
fn panics_later(
    tun: TunHandle,
    settings: Settings,
    gen_: Gen,
    ready: StartSignal,
) -> BoxFuture<'static, ()> {
    gen_.spawn(async {
        tokio::time::sleep(Duration::from_millis(200)).await;
        panic!("a data-path task failed");
    });
    crate::tunnel::drive(tun, settings, gen_, ready)
}

/// A core in Direct, which starts without nodes, whose generations run `launch`.
fn core(dir: &TempDir, launch: crate::tunnel::Launch, bound: Duration) -> Arc<Core> {
    let core = Core::with_launch(dir.path().to_str().unwrap(), launch, bound);
    core.set_mode(Mode::Direct).unwrap();
    Arc::new(core)
}

#[test]
fn stop_during_a_start_makes_it_busy_and_closes_its_tun() {
    let dir = TempDir::new("api-stop-during-start");
    let core = core(&dir, slow, Duration::from_secs(10));
    let (dup, probe) = tun();
    std::thread::scope(|scope| {
        let (tx, rx) = mpsc::channel();
        let core = &core;
        scope.spawn(move || tx.send(core.start(dup, CONFIG)).ok());
        let begun = Instant::now();
        while !matches!(*lock(&core.life), Life::Starting { .. }) {
            assert!(
                begun.elapsed() < Duration::from_secs(5),
                "start never entered"
            );
            std::thread::sleep(Duration::from_millis(5));
        }
        let begun = Instant::now();
        core.stop();
        assert!(closed(&probe), "stop returned with the starting tun open");
        assert!(begun.elapsed() < STOP_WAIT + STOP_WAIT / 2);
        assert_eq!(
            rx.recv_timeout(Duration::from_secs(5)).unwrap(),
            Err(ErrorCode::Busy)
        );
    });
}

#[test]
fn stop_closes_the_tun_while_the_driver_is_stuck() {
    let dir = TempDir::new("api-stuck-stop");
    let core = core(&dir, stuck, Duration::from_secs(10));
    let (dup, probe) = tun();
    core.start(dup, CONFIG).unwrap();
    let begun = Instant::now();
    core.stop();
    let took = begun.elapsed();
    assert!(closed(&probe), "stop returned with the tun open");
    assert!(took < STOP_WAIT + STOP_WAIT / 2, "{took:?}");
}

#[test]
fn a_failed_start_closes_the_tun_within_its_deadline() {
    let dir = TempDir::new("api-stuck-start");
    let bound = Duration::from_secs(1);
    let core = core(&dir, stuck_unready, bound);
    let (dup, probe) = tun();
    let begun = Instant::now();
    assert_eq!(core.start(dup, CONFIG), Err(ErrorCode::Internal));
    let took = begun.elapsed();
    assert!(closed(&probe), "a failed start returned with the tun open");
    assert!(took < bound + bound / 2, "{took:?}");
}

#[test]
fn start_keeps_its_deadline_while_replacing_a_stuck_generation() {
    let dir = TempDir::new("api-stuck-replace");
    let bound = Duration::from_secs(1);
    let core = core(&dir, stuck_then_unready, bound);
    let (first, old) = tun();
    core.start(first, CONFIG).unwrap();
    let (second, new) = tun();
    let begun = Instant::now();
    assert_eq!(core.start(second, CONFIG), Err(ErrorCode::Internal));
    let took = begun.elapsed();
    assert!(closed(&old) && closed(&new));
    // A fresh deadline after waiting for the old driver would take about 1.9 s.
    assert!(took < bound + bound / 2, "{took:?}");
}

#[test]
fn an_overlapping_start_is_busy() {
    let dir = TempDir::new("api-overlap");
    let core = core(&dir, super::tests_carriers::idle, Duration::from_secs(10));
    let (first, _running) = tun();
    let (entered, finishing) = mpsc::channel();
    let (release, released) = mpsc::channel();
    core.hooks.on_before_finish(move |_| {
        entered.send(()).unwrap();
        released.recv_timeout(Duration::from_secs(5)).unwrap();
    });
    std::thread::scope(|scope| {
        let starter = scope.spawn(|| core.start(first, CONFIG));
        finishing.recv_timeout(Duration::from_secs(5)).unwrap();
        let (second, probe) = tun();
        let result = core.start(second, CONFIG);
        release.send(()).unwrap();
        assert_eq!(result, Err(ErrorCode::Busy));
        assert!(closed(&probe));
        assert_eq!(starter.join().unwrap(), Ok(()));
    });
    core.stop();
}

#[test]
fn start_keeps_its_deadline_while_both_workers_are_held() {
    let dir = TempDir::new("api-held-start");
    let bound = Duration::from_secs(1);
    // The first generation, detached, holds one worker; its replacement holds the other.
    let core = core(&dir, held_then_unready, bound);
    let (first, _old) = tun();
    core.start(first, CONFIG).unwrap();
    let (second, new) = tun();
    let begun = Instant::now();
    assert_eq!(core.start(second, CONFIG), Err(ErrorCode::Internal));
    let took = begun.elapsed();
    assert!(closed(&new));
    assert!(took < bound + bound / 2, "{took:?}");
}

#[test]
fn stop_keeps_its_bound_while_both_workers_are_held() {
    let dir = TempDir::new("api-held-stop");
    let core = core(&dir, held, Duration::from_secs(10));
    let (first, _old) = tun();
    core.start(first, CONFIG).unwrap();
    let (second, running) = tun();
    core.start(second, CONFIG).unwrap();
    let begun = Instant::now();
    core.stop();
    let took = begun.elapsed();
    assert!(closed(&running));
    assert!(took < STOP_WAIT + STOP_WAIT / 2, "{took:?}");
}

static SYNC_STARTED: AtomicBool = AtomicBool::new(false);
static SYNC_RELEASED: (Mutex<bool>, Condvar) = (Mutex::new(false), Condvar::new());

/// A directory sync that blocks until the test releases it.
fn stalled_sync(_: &Path) -> io::Result<()> {
    SYNC_STARTED.store(true, SeqCst);
    let (released, cv) = &SYNC_RELEASED;
    let mut released = released.lock().unwrap_or_else(|e| e.into_inner());
    while !*released {
        released = cv.wait(released).unwrap_or_else(|e| e.into_inner());
    }
    Ok(())
}

/// Releases the stalled sync when dropped, even when the test fails first.
struct Release;

impl Drop for Release {
    fn drop(&mut self) {
        let (released, cv) = &SYNC_RELEASED;
        *released.lock().unwrap_or_else(|e| e.into_inner()) = true;
        cv.notify_all();
    }
}

#[test]
fn a_mode_is_live_while_its_directory_sync_stalls() {
    let _release = Release;
    let dir = TempDir::new("api-stalled-sync");
    let core = Arc::new(Core::with_store(Store::with_dir_sync(
        dir.path(),
        stalled_sync,
    )));
    let before: Snapshot = serde_json::from_str(&core.snapshot(-1)).unwrap();
    let setter = {
        let core = Arc::clone(&core);
        std::thread::spawn(move || core.call("setMode", r#"{"mode":"Direct"}"#))
    };
    let begun = Instant::now();
    while !SYNC_STARTED.load(SeqCst) {
        assert!(
            begun.elapsed() < Duration::from_secs(5),
            "the sync never started"
        );
        std::thread::sleep(Duration::from_millis(5));
    }
    // The file is renamed into place: new flows and snapshot must see the mode already.
    let during: Snapshot = serde_json::from_str(&core.snapshot(-1)).unwrap();
    assert_eq!(during.revision, before.revision + 1);
    assert_eq!(during.catalog.unwrap().mode, Mode::Direct);
    assert_eq!(core.mode.get(), Mode::Direct);
    drop(_release);
    assert_eq!(setter.join().unwrap(), r#"{"ok":{}}"#);
}

#[test]
fn stop_waits_for_a_late_start_to_close_its_tun() {
    let dir = TempDir::new("api-late-start");
    let bound = Duration::from_millis(300);
    let core = core(&dir, ready_soon, bound);
    let (dup, probe) = tun();
    let (entered, starting) = mpsc::channel();
    let (release, released) = mpsc::channel();
    *lock(&LATE_START) = Some((entered, released));
    let starter = {
        let core = Arc::clone(&core);
        std::thread::spawn(move || core.start(dup, CONFIG))
    };
    let cancelled = starting.recv_timeout(Duration::from_secs(5)).unwrap();
    let life = lock(&core.life);
    let Life::Starting { tun, .. } = &*life else {
        panic!("the start is not under way");
    };
    let tun = Arc::clone(tun);
    drop(life);
    // Hold the tun's lock so the late start's revoke waits, as a slow deregistration would.
    let slow_close = tun.hold();
    std::thread::sleep(bound);
    release.send(()).unwrap();
    core.runtime.block_on(async {
        tokio::time::timeout(Duration::from_secs(5), cancelled.cancelled())
            .await
            .unwrap();
    });
    let stopper = {
        let core = Arc::clone(&core);
        std::thread::spawn(move || core.stop())
    };
    std::thread::sleep(Duration::from_millis(100));
    if stopper.is_finished() {
        assert!(
            closed(&probe),
            "stop returned with the late start's tun open"
        );
    }
    drop(slow_close);
    stopper.join().unwrap();
    assert!(closed(&probe));
    assert_eq!(starter.join().unwrap(), Err(ErrorCode::Internal));
}

#[test]
fn stop_spends_one_second_on_teardown_and_waiting_together() {
    let dir = TempDir::new("api-slow-teardown");
    let core = core(&dir, stuck, Duration::from_secs(10));
    let (dup, probe) = tun();
    core.start(dup, CONFIG).unwrap();
    let tun = match &*lock(&core.life) {
        Life::Running(tunnel) => Arc::clone(tunnel.tun()),
        _ => panic!("not running"),
    };
    // Spend the whole budget in teardown, with the driver still stuck afterwards. The hold ends
    // with teardown, before `halt` revokes the tun, so the revoke's wait for it is not covered.
    core.hooks.on_teardown(move |_| {
        let _slow = tun.hold();
        std::thread::sleep(STOP_WAIT);
    });
    let begun = Instant::now();
    core.stop();
    let took = begun.elapsed();
    assert!(closed(&probe));
    // A fresh wait budget after teardown would double the total.
    assert!(took < STOP_WAIT + STOP_WAIT / 2, "{took:?}");
}

#[test]
fn a_panicking_data_path_task_fails_the_generation() {
    let dir = TempDir::new("api-panic");
    let core = core(&dir, panics_later, Duration::from_secs(10));
    let (dup, probe) = tun();
    core.start(dup, CONFIG).unwrap();
    assert!(!failed_within_10s(&core).running);
    let begun = Instant::now();
    core.stop();
    let took = begun.elapsed();
    assert!(took < STOP_WAIT + STOP_WAIT / 2, "{took:?}");
    assert!(closed(&probe));
}

/// honk spawns its carriers' tasks (a QUIC connection's driver, a session's reader) on the core's
/// runtime itself, never through the generation: a panic in one fails the generation too.
#[test]
fn a_panic_in_a_task_honk_spawned_fails_the_generation() {
    let dir = TempDir::new("api-worker-panic");
    let core = core(&dir, crate::tunnel::drive, Duration::from_secs(10));
    let (dup, probe) = tun();
    core.start(dup, CONFIG).unwrap();
    core.runtime
        .spawn(async { panic!("a carrier's task failed") });
    assert!(!failed_within_10s(&core).running);
    core.stop();
    assert!(closed(&probe));
}

/// A debug build's fault ends the running data path as a panicking task does.
#[cfg(feature = "fault-hook")]
#[test]
fn the_debug_fault_fails_the_running_generation() {
    let dir = TempDir::new("api-fault");
    let core = core(&dir, crate::tunnel::drive, Duration::from_secs(10));
    let not_found = r#"{"error":{"code":"not_found"}}"#;
    assert_eq!(core.call("debugFault", "{}"), not_found);
    let (dup, probe) = tun();
    core.start(dup, CONFIG).unwrap();
    assert_eq!(core.call("debugFault", "{}"), r#"{"ok":{}}"#);
    assert!(!failed_within_10s(&core).running);
    core.stop();
    assert!(closed(&probe));
}

/// Without the `fault-hook` feature, as in a release build, the fault is an unknown command.
#[cfg(not(feature = "fault-hook"))]
#[test]
fn without_the_feature_there_is_no_debug_fault() {
    let dir = TempDir::new("api-no-fault");
    let core = core(&dir, crate::tunnel::drive, Duration::from_secs(10));
    let (dup, probe) = tun();
    core.start(dup, CONFIG).unwrap();
    let unknown = r#"{"error":{"code":"invalid_argument"}}"#;
    assert_eq!(core.call("debugFault", "{}"), unknown);
    std::thread::sleep(Duration::from_millis(300));
    let s: Snapshot = serde_json::from_str(&core.snapshot(i64::MAX)).unwrap();
    assert!(s.running && s.error.is_none(), "{s:?}");
    core.stop();
    assert!(closed(&probe));
}

/// The snapshot once it reports `internal`, which must be within 10 s.
fn failed_within_10s(core: &Core) -> Snapshot {
    let begun = Instant::now();
    loop {
        let s: Snapshot = serde_json::from_str(&core.snapshot(i64::MAX)).unwrap();
        if s.error == Some(ErrorCode::Internal) {
            return s;
        }
        assert!(begun.elapsed() < Duration::from_secs(10), "{s:?}");
        std::thread::sleep(Duration::from_millis(20));
    }
}

/// The first generation never becomes ready; later ones are ready at once, then idle.
fn unready_then_ready(
    tun: TunHandle,
    _: Settings,
    gen_: Gen,
    ready: StartSignal,
) -> BoxFuture<'static, ()> {
    Box::pin(async move {
        let _held = tun;
        let _unsent = if gen_.session.generation == 1 {
            Some(ready)
        } else {
            ready.send();
            None
        };
        std::future::pending::<()>().await;
    })
}

/// A loopback server that echoes each connection.
pub(super) fn echo() -> std::net::SocketAddr {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    std::thread::spawn(move || {
        for conn in listener.incoming().flatten() {
            std::thread::spawn(move || {
                let mut read = conn.try_clone().unwrap();
                let _ = std::io::copy(&mut read, &mut &conn);
            });
        }
    });
    addr
}

/// Sends `msg` on `s` and reads its echo.
pub(super) fn ping(core: &Core, s: &mut Upstream, msg: &[u8]) -> io::Result<Vec<u8>> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    core.runtime.block_on(async {
        s.write_all(msg).await?;
        let mut got = vec![0; msg.len()];
        s.read_exact(&mut got).await?;
        Ok(got)
    })
}

/// `stop` makes a start under way busy and returns before that start has cleaned up. A start
/// that comes then claims the carriers, and the first start's late cleanup leaves them alone: a
/// stream the second generation opened through a node carries on.
#[test]
fn a_late_cleanup_leaves_the_next_generations_carriers_alone() {
    let dir = TempDir::new("api-late-cleanup");
    let (proxy, _) = crate::testutil::socks5();
    let echo = echo();
    let feeds = Feeds::new();
    feeds.set("/sub", format!("socks5://{proxy}#s5\n").as_bytes());
    let core = Core::with_launch(path(&dir), unready_then_ready, Duration::from_secs(10));
    let core = Arc::new(core);
    add(&core, &feeds, "/sub").unwrap();
    let opened: Arc<Mutex<Option<Upstream>>> = Arc::default();
    let kept = Arc::clone(&opened);
    let (second, _second_probe) = tun();
    core.hooks.on_before_finish(move |core| {
        core.start(second, CONFIG).unwrap();
        let route = Route::of(
            Inbound::Tun,
            crate::tunnel::route::tests::ANYWHERE,
            Mode::Global,
            &core.shared.outbounds,
            &Arc::new(Session::new(0, std::sync::Weak::new())),
        );
        let mut s = core.runtime.block_on(route.connect(echo)).unwrap();
        assert_eq!(ping(core, &mut s, b"before").unwrap(), b"before");
        *lock(&kept) = Some(s);
    });
    let (first, _first_probe) = tun();
    let starter = {
        let core = Arc::clone(&core);
        std::thread::spawn(move || core.start(first, CONFIG))
    };
    while !matches!(*lock(&core.life), Life::Starting { .. }) {
        std::thread::sleep(Duration::from_millis(5));
    }
    core.stop();
    assert_eq!(starter.join().unwrap(), Err(ErrorCode::Busy));
    let mut s = lock(&opened).take().expect("a stream on the carriers");
    assert_eq!(ping(&core, &mut s, b"after").unwrap(), b"after");
    assert!(matches!(*lock(&core.life), Life::Running(_)));
}

/// A flow whose SYN waits in the tun before the data path is ready is served only once `start`
/// commits the generation, with its config's resolvers installed: however long the commit
/// takes, nothing reaches the flow's node before.
#[test]
fn a_queued_flow_waits_for_the_commit() {
    let dir = TempDir::new("api-queued");
    let (proxy, dials) = crate::testutil::socks5();
    let echo = echo();
    let feeds = Feeds::new();
    feeds.set("/sub", format!("socks5://{proxy}#s5\n").as_bytes());
    let core = Core::with_launch(path(&dir), crate::tunnel::drive, Duration::from_secs(10));
    add(&core, &feeds, "/sub").unwrap();
    let (tun, peer) = crate::testutil::tun_and_peer();
    let mut client = Client::new(peer);
    let id = client.connect(echo);
    let before_commit = Arc::new(AtomicU32::new(u32::MAX));
    let seen = Arc::clone(&before_commit);
    core.hooks.on_before_finish(move |_| {
        std::thread::sleep(Duration::from_millis(300));
        seen.store(dials.load(SeqCst), SeqCst);
    });
    core.start(tun, CONFIG).unwrap();
    assert_eq!(before_commit.load(SeqCst), 0, "the node was dialed first");
    assert_eq!(client.exchange(id, b"queued").unwrap(), b"queued");
}
