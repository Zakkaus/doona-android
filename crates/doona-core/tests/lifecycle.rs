//! Generations on a socketpair standing in for the tun: the core owns only its duplicate, `stop`
//! returns within 1 s and closes it, a dead tun shows as `internal`, Global without a node does
//! not start, and no path leaks a descriptor. The fd counts read /proc/self/fd, so these tests
//! run one at a time. The cores run in Direct, which needs no node.

mod common;

use common::stop_wait::STOP_WAIT;
use common::{TempDir, seqpacket_pair, wait_for};
use doonacore::api::Core;
use doonacore::dto::{ErrorCode, Snapshot};
use doonacore::sys;
use std::os::fd::{AsRawFd, OwnedFd};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

const CONFIG: &str =
    r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":["192.168.8.1"]}"#;

fn serial() -> MutexGuard<'static, ()> {
    static SERIAL: Mutex<()> = Mutex::new(());
    SERIAL.lock().unwrap_or_else(|e| e.into_inner())
}

fn open_fds() -> usize {
    std::fs::read_dir("/proc/self/fd").unwrap().count()
}

fn new_core(dir: &TempDir) -> Core {
    let core = Core::new(common::seeded(dir)).unwrap();
    assert_eq!(core.call("setMode", r#"{"mode":"Direct"}"#), r#"{"ok":{}}"#);
    core
}

fn snapshot(core: &Core) -> Snapshot {
    serde_json::from_str(&core.snapshot(i64::MAX)).unwrap()
}

/// As jni.rs does: duplicate the borrowed fd first; the caller keeps and closes its own.
fn start(core: &Core, tun: &OwnedFd, config: &str) -> Result<(), ErrorCode> {
    core.start(sys::dup_cloexec(tun.as_raw_fd()).unwrap(), config)
}

/// True once every copy of the other end is closed: a SOCK_SEQPACKET read then returns 0.
fn peer_sees_eof(peer: &OwnedFd) -> bool {
    let mut b = [0u8; 1];
    // SAFETY: `b` is valid for one byte; MSG_DONTWAIT keeps the call from blocking.
    let n = unsafe {
        libc::recv(
            peer.as_raw_fd(),
            b.as_mut_ptr().cast(),
            1,
            libc::MSG_DONTWAIT,
        )
    };
    n == 0
}

fn send(peer: &OwnedFd, packet: &[u8]) {
    // SAFETY: `packet` is valid for its length.
    let n = unsafe { libc::send(peer.as_raw_fd(), packet.as_ptr().cast(), packet.len(), 0) };
    assert_eq!(n, packet.len() as isize);
}

#[test]
fn start_serves_the_duplicate_and_stop_closes_it_promptly() {
    let _s = serial();
    let dir = TempDir::new("life-basic");
    let core = new_core(&dir);
    let (tun, peer) = seqpacket_pair();
    start(&core, &tun, CONFIG).unwrap();
    // Kotlin closes its descriptor once start returns.
    drop(tun);
    let s = snapshot(&core);
    assert!(s.running && s.error.is_none());
    assert_eq!(s.generation, 1);
    assert!(s.since_epoch_ms.is_some());
    send(&peer, &[0u8; 40]);
    assert!(wait_for(Duration::from_secs(2), || snapshot(&core)
        .traffic
        .up_total
        >= 40));
    assert!(!peer_sees_eof(&peer));

    let begun = Instant::now();
    core.stop();
    let took = begun.elapsed();
    assert!(took < STOP_WAIT + STOP_WAIT / 2, "{took:?}");
    let s = snapshot(&core);
    assert!(!s.running && s.error.is_none() && s.since_epoch_ms.is_none());
    assert_eq!(s.generation, 1);
    assert_eq!(s.traffic.up_total, 0);
    assert!(peer_sees_eof(&peer), "the core's duplicate is still open");
    core.stop();
}

#[test]
fn a_tun_that_closes_fails_the_generation_until_stop() {
    let _s = serial();
    let dir = TempDir::new("life-died");
    let core = new_core(&dir);
    let (tun, peer) = seqpacket_pair();
    start(&core, &tun, CONFIG).unwrap();
    drop(tun);
    drop(peer);
    assert!(wait_for(Duration::from_secs(2), || !snapshot(&core).running));
    let s = snapshot(&core);
    assert_eq!(s.error, Some(ErrorCode::Internal));
    assert_eq!(s.generation, 1);
    assert!(s.since_epoch_ms.is_some());
    core.stop();
    let s = snapshot(&core);
    assert!(!s.running && s.error.is_none());
}

#[test]
fn start_while_running_replaces_the_generation() {
    let _s = serial();
    let dir = TempDir::new("life-replace");
    let core = new_core(&dir);
    let (tun_a, peer_a) = seqpacket_pair();
    let (tun_b, peer_b) = seqpacket_pair();
    start(&core, &tun_a, CONFIG).unwrap();
    drop(tun_a);
    start(&core, &tun_b, CONFIG).unwrap();
    drop(tun_b);
    let s = snapshot(&core);
    assert!(s.running);
    assert_eq!(s.generation, 2);
    assert!(peer_sees_eof(&peer_a));
    assert!(!peer_sees_eof(&peer_b));
    core.stop();
    assert!(peer_sees_eof(&peer_b));
}

#[test]
fn a_bad_config_fails_without_starting() {
    let _s = serial();
    let dir = TempDir::new("life-config");
    let core = new_core(&dir);
    let (tun, peer) = seqpacket_pair();
    for config in [
        "{}",
        "not json",
        r#"{"mtu":100,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":[]}"#,
        // honk's transparent stack clamps anything smaller to 1280.
        r#"{"mtu":1279,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":[]}"#,
        r#"{"mtu":8500,"ipv4":"x","dns":"172.19.0.2","bootstrapDns":[]}"#,
    ] {
        assert_eq!(
            start(&core, &tun, config),
            Err(ErrorCode::InvalidArgument),
            "{config}"
        );
    }
    drop(tun);
    assert!(peer_sees_eof(&peer), "a failed start kept its duplicate");
    let s = snapshot(&core);
    assert!(!s.running && s.generation == 0);
}

#[test]
fn global_without_a_node_does_not_start() {
    let _s = serial();
    let dir = TempDir::new("life-no-nodes");
    // A fresh state.json is in Global, and there is no subscription.
    let core = Core::new(common::seeded(&dir)).unwrap();
    let (tun, peer) = seqpacket_pair();
    assert_eq!(start(&core, &tun, CONFIG), Err(ErrorCode::NoNodes));
    drop(tun);
    assert!(peer_sees_eof(&peer), "a refused start kept its duplicate");
    let s = snapshot(&core);
    assert!(!s.running && s.generation == 0 && s.error.is_none());
}

#[test]
fn stop_when_stopped_is_harmless() {
    let _s = serial();
    let dir = TempDir::new("life-idle");
    let core = new_core(&dir);
    core.stop();
    core.stop();
    assert!(!snapshot(&core).running);
}

#[test]
fn no_path_leaks_a_descriptor() {
    let _s = serial();
    let dir = TempDir::new("life-leak");
    let core = Arc::new(new_core(&dir));
    // Warm up whatever the runtime opens lazily.
    let (tun, _peer) = seqpacket_pair();
    start(&core, &tun, CONFIG).unwrap();
    core.stop();
    drop(tun);
    let before = open_fds();

    for _ in 0..20 {
        let (tun, peer) = seqpacket_pair();
        start(&core, &tun, CONFIG).unwrap();
        drop(tun);
        core.stop();
        assert!(peer_sees_eof(&peer));
    }
    let (tun, peer) = seqpacket_pair();
    assert!(start(&core, &tun, "{}").is_err());
    start(&core, &tun, CONFIG).unwrap();
    start(&core, &tun, CONFIG).unwrap();
    drop(peer);
    assert!(wait_for(Duration::from_secs(2), || !snapshot(&core).running));
    core.stop();
    drop(tun);

    // Starts racing stops from another thread.
    let stopper = {
        let core = Arc::clone(&core);
        std::thread::spawn(move || {
            for _ in 0..50 {
                core.stop();
                std::thread::yield_now();
            }
        })
    };
    for _ in 0..50 {
        let (tun, _peer) = seqpacket_pair();
        match start(&core, &tun, CONFIG) {
            Ok(()) | Err(ErrorCode::Busy) => {}
            Err(other) => panic!("start failed with {other:?}"),
        }
    }
    stopper.join().unwrap();
    core.stop();
    assert!(!snapshot(&core).running);
    assert_eq!(open_fds(), before);
}
