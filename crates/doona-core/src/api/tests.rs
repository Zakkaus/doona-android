//! `start` and `stop` against stand-in drivers that are slow or stuck, which the real driver
//! never is on a host: the tun must be closed when `stop` (or a failed `start`) returns, and
//! `start` must keep its deadline and answer `busy` when a `stop` or another `start` overlaps it.

use super::Core;
use crate::dto::{ErrorCode, Mode, Snapshot};
use crate::state::Store;
use crate::testutil::{TempDir, closed, tun};
use crate::tunnel::{Gen, Settings, StartSignal, TunHandle};
use futures_util::future::BoxFuture;
use std::io;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering::SeqCst};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

const CONFIG: &str =
    r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":["192.168.8.1"]}"#;
/// How long a stuck driver blocks its worker thread.
const STUCK: Duration = Duration::from_millis(1500);
/// How long each generation holds its worker in the tests that hold both of them.
const HELD: Duration = Duration::from_millis(2500);
/// What a returning call may take beyond its bound.
const SLACK: Duration = Duration::from_millis(250);

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

/// Ready after a second, then idle.
fn slow(tun: TunHandle, _: Settings, _: Gen, ready: StartSignal) -> BoxFuture<'static, ()> {
    Box::pin(async move {
        let _held = tun;
        tokio::time::sleep(Duration::from_secs(1)).await;
        ready.send();
        std::future::pending::<()>().await;
    })
}

fn core(dir: &TempDir, launch: crate::tunnel::Launch, bound: Duration) -> Arc<Core> {
    Arc::new(Core::with_launch(
        dir.path().to_str().unwrap(),
        launch,
        bound,
    ))
}

#[test]
fn stop_during_a_start_makes_it_busy_and_closes_its_tun() {
    let dir = TempDir::new("api-stop-during-start");
    let core = core(&dir, slow, Duration::from_secs(10));
    let (dup, probe) = tun();
    let starter = {
        let core = Arc::clone(&core);
        std::thread::spawn(move || {
            let begun = Instant::now();
            (core.start(dup, CONFIG), begun.elapsed())
        })
    };
    std::thread::sleep(Duration::from_millis(200));
    let begun = Instant::now();
    core.stop();
    assert!(begun.elapsed() < Duration::from_secs(1));
    assert!(closed(&probe), "stop returned with the starting tun open");
    let (result, took) = starter.join().unwrap();
    assert_eq!(result, Err(ErrorCode::Busy));
    assert!(took < Duration::from_secs(1) + SLACK, "{took:?}");
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
    assert!(took < Duration::from_secs(1), "{took:?}");
}

#[test]
fn a_failed_start_closes_the_tun_within_its_deadline() {
    let dir = TempDir::new("api-stuck-start");
    let bound = Duration::from_millis(300);
    let core = core(&dir, stuck_unready, bound);
    let (dup, probe) = tun();
    let begun = Instant::now();
    assert_eq!(core.start(dup, CONFIG), Err(ErrorCode::Internal));
    let took = begun.elapsed();
    assert!(closed(&probe), "a failed start returned with the tun open");
    assert!(took < bound + SLACK, "{took:?}");
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
    assert!(took < bound + SLACK, "{took:?}");
}

#[test]
fn an_overlapping_start_is_busy() {
    let dir = TempDir::new("api-overlap");
    let core = core(&dir, slow, Duration::from_secs(10));
    let (first, _running) = tun();
    let starter = {
        let core = Arc::clone(&core);
        std::thread::spawn(move || core.start(first, CONFIG))
    };
    std::thread::sleep(Duration::from_millis(200));
    let (second, probe) = tun();
    let begun = Instant::now();
    assert_eq!(core.start(second, CONFIG), Err(ErrorCode::Busy));
    assert!(begun.elapsed() < SLACK, "{:?}", begun.elapsed());
    assert!(closed(&probe));
    assert_eq!(starter.join().unwrap(), Ok(()));
    let begun = Instant::now();
    core.stop();
    assert!(begun.elapsed() < Duration::from_secs(1));
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
    assert!(took < bound + SLACK, "{took:?}");
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
    assert!(took < Duration::from_secs(1), "{took:?}");
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
