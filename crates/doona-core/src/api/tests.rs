//! `start` and `stop` against stand-in drivers that are slow or stuck, which the real driver
//! never is on a host: the tun must be closed when `stop` (or a failed `start`) returns, and
//! `start` must keep its deadline and answer `busy` when a `stop` or another `start` overlaps it.

use super::Core;
use crate::dto::ErrorCode;
use crate::sys;
use crate::testutil::TempDir;
use crate::tunnel::{Gen, Settings, TunHandle};
use futures_util::future::BoxFuture;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
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

/// Ready after a second, then idle.
fn slow(tun: TunHandle, _: Settings, _: Gen, ready: oneshot::Sender<()>) -> BoxFuture<'static, ()> {
    Box::pin(async move {
        let _held = tun;
        tokio::time::sleep(Duration::from_secs(1)).await;
        let _ = ready.send(());
        std::future::pending::<()>().await;
    })
}

/// A duplicate's number and what it refers to, and the far end of its socketpair.
struct Probe {
    raw: RawFd,
    identity: (u64, u64),
    _peer: OwnedFd,
}

/// A duplicate as jni.rs makes it, and a probe that tells whether it is closed.
fn tun() -> (OwnedFd, Probe) {
    let mut fds = [0; 2];
    // SAFETY: `fds` has room for the two descriptors socketpair writes.
    let r = unsafe {
        libc::socketpair(
            libc::AF_UNIX,
            libc::SOCK_SEQPACKET | libc::SOCK_CLOEXEC,
            0,
            fds.as_mut_ptr(),
        )
    };
    assert_eq!(r, 0);
    // SAFETY: socketpair returned two open descriptors that nothing else owns.
    let (a, b) = unsafe { (OwnedFd::from_raw_fd(fds[0]), OwnedFd::from_raw_fd(fds[1])) };
    let dup = sys::dup_cloexec(a.as_raw_fd()).unwrap();
    let raw = dup.as_raw_fd();
    let probe = Probe {
        identity: identity(raw).unwrap(),
        raw,
        _peer: b,
    };
    (dup, probe)
}

fn identity(fd: RawFd) -> Option<(u64, u64)> {
    // SAFETY: fstat only writes the struct it is given.
    let mut st: libc::stat = unsafe { std::mem::zeroed() };
    // SAFETY: `st` is valid for writes.
    (unsafe { libc::fstat(fd, &mut st) } == 0).then_some((st.st_dev, st.st_ino))
}

/// The duplicate is closed: its number is free, or another test reopened it for something else.
fn closed(p: &Probe) -> bool {
    // SAFETY: F_GETFD only reads the descriptor's flags.
    let open = unsafe { libc::fcntl(p.raw, libc::F_GETFD) } != -1;
    !open || identity(p.raw) != Some(p.identity)
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
