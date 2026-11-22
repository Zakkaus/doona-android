//! One tunnel generation: the duplicate tun fd, the tasks that serve it, and the [`Session`] that
//! `snapshot` reads. Every task of a generation is spawned on its [`TaskTracker`] and stops at its
//! [`CancellationToken`]; a task that panics, or a driver that fails, marks the session failed
//! and cancels the rest. Stopping a generation closes its tun at once (see `tun.rs`), so only
//! tasks without tun access are ever left running.

mod driver;
mod flow;
pub mod route;
mod tcp;
mod tun;
mod udp;

use crate::dto::{ErrorCode, Traffic};
use crate::outbounds::Outbounds;
use futures_util::FutureExt;
use futures_util::future::BoxFuture;
use route::ModeCell;
use std::future::Future;
use std::net::{Ipv4Addr, SocketAddr};
use std::panic::AssertUnwindSafe;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering::Relaxed};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio::runtime::Handle;
use tokio_util::sync::CancellationToken;
use tokio_util::task::TaskTracker;
pub(crate) use tun::Tun;

/// How long `stop` waits for a halted generation's tasks; it returns within 1 s.
pub(crate) const STOP_WAIT: Duration = Duration::from_millis(900);

/// What a generation runs with: `start`'s config and the core's own settings.
#[derive(Clone, Debug)]
pub struct Settings {
    pub mtu: u16,
    /// The virtual resolver the tun advertises.
    pub dns: Ipv4Addr,
    /// Where DNS the core answers is forwarded, in order.
    pub dns_upstreams: Vec<SocketAddr>,
    /// The committed mode; each new flow reads it.
    pub mode: Arc<ModeCell>,
    /// Each group's current node and what dials it; a new Global flow goes through `g-Proxy`'s.
    pub outbounds: Arc<Outbounds>,
}

/// The tun as the driver holds it.
pub(crate) type TunHandle = Arc<Tun>;

/// What serves a generation's tun: the netstack driver, or a stand-in in unit tests. It sends on
/// its [`StartSignal`] once it serves the tun.
pub(crate) type Launch = fn(TunHandle, Settings, Gen, StartSignal) -> BoxFuture<'static, ()>;

/// The [`Launch`] the app runs.
pub(crate) fn drive(
    tun: TunHandle,
    settings: Settings,
    gen_: Gen,
    ready: StartSignal,
) -> BoxFuture<'static, ()> {
    Box::pin(driver::run(tun, settings, gen_, ready))
}

/// How a starting generation reports to the thread that waits in `start`. That thread waits on
/// it with its own timer, so `start` keeps its deadline even while every runtime worker is held.
pub(crate) struct Gate {
    state: Mutex<GateState>,
    changed: Condvar,
}

#[derive(Default)]
struct GateState {
    /// `Some(true)` once the tun is served, `Some(false)` if the driver gave up first.
    ready: Option<bool>,
    /// A `stop` cancelled the start.
    aborted: bool,
}

impl Gate {
    pub fn new() -> Arc<Gate> {
        Arc::new(Gate {
            state: Mutex::new(GateState::default()),
            changed: Condvar::new(),
        })
    }

    /// Cancels the start; this wins over a readiness reported at the same time.
    pub fn abort(&self) {
        self.update(|s| s.aborted = true);
    }

    pub fn aborted(&self) -> bool {
        self.lock().aborted
    }

    fn report(&self, ready: bool) {
        self.update(|s| {
            s.ready.get_or_insert(ready);
        });
    }

    fn update(&self, f: impl FnOnce(&mut GateState)) {
        f(&mut self.lock());
        self.changed.notify_all();
    }

    /// Waits on the calling thread until the driver reports, a `stop` aborts, or `deadline`
    /// passes.
    fn wait(&self, deadline: Instant) -> Result<(), ErrorCode> {
        let mut s = self.lock();
        loop {
            if s.aborted {
                return Err(ErrorCode::Busy);
            }
            match s.ready {
                Some(true) => return Ok(()),
                Some(false) => return Err(ErrorCode::Internal),
                None => {}
            }
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                return Err(ErrorCode::Internal);
            }
            s = self
                .changed
                .wait_timeout(s, left)
                .unwrap_or_else(|e| e.into_inner())
                .0;
        }
    }

    /// A poisoned lock still holds a consistent state: every update only assigns.
    fn lock(&self) -> MutexGuard<'_, GateState> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// The driver's side of a [`Gate`]: `send` reports the tun served; dropped unsent, it reports
/// that the driver gave up.
pub(crate) struct StartSignal(Option<Arc<Gate>>);

impl StartSignal {
    pub fn send(mut self) {
        if let Some(gate) = self.0.take() {
            gate.report(true);
        }
    }
}

impl Drop for StartSignal {
    fn drop(&mut self) {
        if let Some(gate) = self.0.take() {
            gate.report(false);
        }
    }
}

/// What `snapshot` reports about one generation; its tasks update it.
pub struct Session {
    pub generation: i64,
    pub since_epoch_ms: i64,
    up: AtomicU64,
    down: AtomicU64,
    active: AtomicU32,
    failed: AtomicBool,
}

impl Session {
    fn new(generation: i64) -> Session {
        let since = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX));
        Session {
            generation,
            since_epoch_ms: since,
            up: AtomicU64::new(0),
            down: AtomicU64::new(0),
            active: AtomicU32::new(0),
            failed: AtomicBool::new(false),
        }
    }

    pub fn traffic(&self) -> Traffic {
        Traffic {
            up_total: self.up.load(Relaxed),
            down_total: self.down.load(Relaxed),
            active_flows: self.active.load(Relaxed),
        }
    }

    /// The data path died after `start` returned.
    pub fn failed(&self) -> bool {
        self.failed.load(Relaxed)
    }

    fn fail(&self) {
        self.failed.store(true, Relaxed);
    }

    /// Bytes read from and written to the tun since `start`, and the flows open now.
    fn publish(&self, up: u64, down: u64, active: u32) {
        self.up.store(up, Relaxed);
        self.down.store(down, Relaxed);
        self.active.store(active, Relaxed);
    }
}

/// The handles every task of a generation shares.
#[derive(Clone)]
pub(crate) struct Gen {
    pub session: Arc<Session>,
    pub cancel: CancellationToken,
    pub tracker: TaskTracker,
    pub runtime: Handle,
}

impl Gen {
    /// Spawns `fut` on the core's runtime. It is dropped when the generation is cancelled; a
    /// panic marks the session failed and ends the generation.
    pub fn spawn<F: Future<Output = ()> + Send + 'static>(&self, fut: F) {
        let gen_ = self.clone();
        let task = async move {
            tokio::select! {
                () = gen_.cancel.cancelled() => {}
                r = AssertUnwindSafe(fut).catch_unwind() => {
                    if r.is_err() {
                        log::error!("generation {}: a task panicked", gen_.session.generation);
                        gen_.die();
                    }
                }
            }
        };
        self.tracker.spawn_on(task, &self.runtime);
    }

    /// The data path cannot go on: `snapshot` reports `internal` until `stop`.
    pub fn die(&self) {
        self.session.fail();
        self.cancel.cancel();
    }
}

pub struct Tunnel {
    gen_: Gen,
    tun: Arc<Tun>,
}

impl Tunnel {
    /// Serves `tun` and returns once `launch` does, by `deadline`, cleanup included. A `stop`
    /// arriving meanwhile aborts `gate`, which ends the wait with `busy`. On any failure the
    /// generation is halted, which closes `tun`.
    pub(crate) fn start(
        rt: &Handle,
        tun: Arc<Tun>,
        (settings, generation): (Settings, i64),
        launch: Launch,
        deadline: Instant,
        gate: &Arc<Gate>,
    ) -> Result<Tunnel, ErrorCode> {
        let tunnel = Tunnel {
            gen_: Gen {
                session: Arc::new(Session::new(generation)),
                cancel: CancellationToken::new(),
                tracker: TaskTracker::new(),
                runtime: rt.clone(),
            },
            tun: Arc::clone(&tun),
        };
        let signal = StartSignal(Some(Arc::clone(gate)));
        let gen_ = tunnel.gen_.clone();
        tunnel.gen_.spawn(launch(tun, settings, gen_, signal));
        match gate.wait(deadline) {
            Ok(()) => Ok(tunnel),
            Err(code) => {
                tunnel.halt();
                tunnel.wait(rt, deadline);
                Err(code)
            }
        }
    }

    pub fn session(&self) -> &Arc<Session> {
        &self.gen_.session
    }

    #[cfg(test)]
    pub(crate) fn tun(&self) -> &Arc<Tun> {
        &self.tun
    }

    /// Cancels every task and closes the tun. Quick: it waits for at most one tun syscall.
    pub fn halt(&self) {
        self.gen_.cancel.cancel();
        self.gen_.tracker.close();
        self.tun.revoke();
    }

    /// Waits for the halted generation's tasks until `deadline`, for [`STOP_WAIT`] at most. A
    /// task still running then is left to finish on its own: the tun is closed already, and a
    /// flow owns its socket, so none can reach a descriptor number reused later.
    pub fn wait(self, rt: &Handle, deadline: Instant) {
        let begun = Instant::now();
        let until = deadline.min(begun + STOP_WAIT);
        let g = &self.gen_;
        let done = g.tracker.is_empty() || {
            // A runtime task reports the end, but the wait is timed on this thread, so it ends
            // on time even while every worker is held.
            let (tx, rx) = std::sync::mpsc::channel();
            let tracker = g.tracker.clone();
            rt.spawn(async move {
                tracker.wait().await;
                let _ = tx.send(());
            });
            rx.recv_timeout(until.saturating_duration_since(Instant::now()))
                .is_ok()
        };
        let ms = begun.elapsed().as_millis();
        let generation = g.session.generation;
        if done {
            log::info!("generation {generation} stopped in {ms} ms");
        } else {
            log::warn!(
                "generation {generation}: {} tasks detached after {ms} ms",
                g.tracker.len()
            );
        }
    }
}
