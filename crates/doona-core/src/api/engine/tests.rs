//! A start with and without a committed honk configuration, and the codes a failed engine build
//! maps to.

use super::super::{Core, Life, lock};
use super::engine_code;
use crate::dto::{ErrorCode, Mode};
use crate::honkconf::StoreError;
use crate::honkconf::runtime::RuntimeError;
use crate::testutil::{TempDir, honk_store, tun_and_peer};
use std::io;
use std::time::Duration;

const CONFIG: &str =
    r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":["192.168.8.1"]}"#;

/// A core over `dir` with no subscription, in `mode`.
fn core(dir: &TempDir, mode: Mode) -> Core {
    let launch = crate::tunnel::drive as crate::tunnel::Launch;
    let core = Core::with_launch(
        dir.path().to_str().unwrap(),
        launch,
        Duration::from_secs(10),
    );
    core.set_mode(mode).unwrap();
    core
}

fn generation(core: &Core) -> Option<i64> {
    match &*lock(&core.life) {
        Life::Running(t) => Some(t.session().generation),
        _ => None,
    }
}

/// Direct needs no rules: it starts without a committed configuration. Rule and Global have
/// nothing to route by and refuse with `no_nodes`.
#[test]
fn direct_starts_without_a_committed_configuration() {
    let dir = TempDir::new("engine-unconfigured");
    let core = core(&dir, Mode::Direct);
    let (tun, _peer) = tun_and_peer();
    assert_eq!(core.start(tun, CONFIG), Ok(()));
    core.stop();
    for mode in [Mode::Rule, Mode::Global] {
        core.set_mode(mode).unwrap();
        let (tun, _peer) = tun_and_peer();
        assert_eq!(core.start(tun, CONFIG), Err(ErrorCode::NoNodes), "{mode:?}");
    }
}

/// A restart that cannot route is refused before it stops anything: the running generation
/// keeps running.
#[test]
fn a_restart_that_cannot_route_leaves_the_running_generation() {
    let dir = TempDir::new("engine-restart");
    let core = core(&dir, Mode::Direct);
    let (tun, _peer) = tun_and_peer();
    core.start(tun, CONFIG).unwrap();
    let running = generation(&core);
    assert!(running.is_some());
    core.set_mode(Mode::Rule).unwrap();
    let (tun, _second) = tun_and_peer();
    assert_eq!(core.start(tun, CONFIG), Err(ErrorCode::NoNodes));
    assert_eq!(generation(&core), running, "still the first generation");
    core.stop();
}

/// Global's target is the fallback's group: a fallback to `direct` leaves none, and Global is
/// `no_nodes`; Rule starts and sends flows direct by the fallback.
#[test]
fn global_without_a_target_is_no_nodes() {
    let dir = TempDir::new("engine-untargeted");
    honk_store(dir.path(), "routing {\n    fallback: direct\n}\n");
    let core = core(&dir, Mode::Global);
    let (tun, _peer) = tun_and_peer();
    assert_eq!(core.start(tun, CONFIG), Err(ErrorCode::NoNodes));
    core.set_mode(Mode::Rule).unwrap();
    let (tun, _peer) = tun_and_peer();
    assert_eq!(core.start(tun, CONFIG), Ok(()));
    core.stop();
}

#[test]
fn each_engine_failure_has_its_own_code() {
    let io = |kind| io::Error::from(kind);
    let cases = [
        (RuntimeError::NoRevision, ErrorCode::NoNodes),
        (
            RuntimeError::Asset(io(io::ErrorKind::NotFound)),
            ErrorCode::NotFound,
        ),
        (
            RuntimeError::Asset(io(io::ErrorKind::InvalidData)),
            ErrorCode::ParseFailed,
        ),
        (
            RuntimeError::Config(anyhow::anyhow!("refused")),
            ErrorCode::ParseFailed,
        ),
        (
            RuntimeError::Store(StoreError::Incomplete),
            ErrorCode::ParseFailed,
        ),
        (
            RuntimeError::Store(StoreError::Io(io(io::ErrorKind::Other))),
            ErrorCode::Internal,
        ),
        (
            RuntimeError::Layout("/elsewhere".into()),
            ErrorCode::Internal,
        ),
    ];
    for (error, code) in cases {
        assert_eq!(engine_code(&error), code, "{error}");
    }
}
