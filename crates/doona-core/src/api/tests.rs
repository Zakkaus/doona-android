//! `start` and `stop` against stand-in drivers that are slow or stuck, which the real driver
//! never is on a host: the tun must be closed when `stop` (or a failed `start`) returns, and
//! `start` must keep its deadline and answer `busy` when a `stop` or another `start` overlaps it.

use super::Core;
use crate::dto::ErrorCode;
use crate::testutil::{TempDir, closed, tun};
use crate::tunnel::{Gen, Settings, TunHandle};
use futures_util::future::BoxFuture;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::oneshot;

const CONFIG: &str =
    r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":["192.168.8.1"]}"#;
/// How long a stuck driver blocks its worker thread.
const STUCK: Duration = Duration::from_millis(1500);
/// What a returning call may take beyond its bound.
const SLACK: Duration = Duration::from_millis(250);

/// Ready at once, then blocks its worker without yielding, as a driver caught in a long
/// operation would.
fn stuck(
    tun: TunHandle,
    _: Settings,
    _: Gen,
    ready: oneshot::Sender<()>,
) -> BoxFuture<'static, ()> {
    Box::pin(async move {
        let _held = tun;
        let _ = ready.send(());
        std::thread::sleep(STUCK);
    })
}

/// Blocks its worker without ever becoming ready.
fn stuck_unready(
    tun: TunHandle,
    _: Settings,
    _: Gen,
    ready: oneshot::Sender<()>,
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
    ready: oneshot::Sender<()>,
) -> BoxFuture<'static, ()> {
    Box::pin(async move {
        let _held = tun;
        if gen_.session.generation == 1 {
            let _ = ready.send(());
            std::thread::sleep(STUCK);
        } else {
            let _ready = ready;
            std::future::pending::<()>().await;
        }
    })
}

/// Ready after a second, then idle.
fn slow(tun: TunHandle, _: Settings, _: Gen, ready: oneshot::Sender<()>) -> BoxFuture<'static, ()> {
    Box::pin(async move {
        let _held = tun;
        tokio::time::sleep(Duration::from_secs(1)).await;
        let _ = ready.send(());
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
