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
use futures_util::FutureExt;
use futures_util::future::BoxFuture;
use route::ModeCell;
use std::future::Future;
use std::net::{Ipv4Addr, SocketAddr};
use std::panic::AssertUnwindSafe;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering::Relaxed};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio::runtime::Handle;
use tokio::sync::oneshot;
use tokio_util::sync::CancellationToken;
use tokio_util::task::TaskTracker;
pub(crate) use tun::Tun;

/// `stop` returns within 1 s; the rest is left for the locks around it.
const STOP_WAIT: Duration = Duration::from_millis(900);

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
}

/// The tun as the driver holds it.
pub(crate) type TunHandle = Arc<Tun>;

/// What serves a generation's tun: the netstack driver, or a stand-in in unit tests. It sends on
/// `ready` once it serves the tun.
pub(crate) type Launch =
    fn(TunHandle, Settings, Gen, oneshot::Sender<()>) -> BoxFuture<'static, ()>;

/// The [`Launch`] the app runs.
pub(crate) fn drive(
    tun: TunHandle,
    settings: Settings,
    gen_: Gen,
    ready: oneshot::Sender<()>,
) -> BoxFuture<'static, ()> {
    Box::pin(driver::run(tun, settings, gen_, ready))
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
    /// Serves `tun` and returns once `launch` does, at most `bound` later. `abort` (a `stop`
    /// arriving meanwhile) ends the wait with `busy`. On any failure the generation is stopped,
    /// which closes `tun`.
    pub(crate) fn start(
        rt: &Handle,
        tun: Arc<Tun>,
        settings: Settings,
        generation: i64,
        (launch, bound): (Launch, Duration),
        abort: &CancellationToken,
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
        let (ready_tx, ready_rx) = oneshot::channel();
        let gen_ = tunnel.gen_.clone();
        tunnel.gen_.spawn(launch(tun, settings, gen_, ready_tx));
        let up = rt.block_on(async {
            tokio::select! {
                r = ready_rx => r.map_err(|_| ErrorCode::Internal),
                () = abort.cancelled() => Err(ErrorCode::Busy),
                () = tokio::time::sleep(bound) => Err(ErrorCode::Internal),
            }
        });
        match up {
            Ok(()) => Ok(tunnel),
            Err(code) => {
                tunnel.stop(rt);
                Err(code)
            }
        }
    }

    pub fn session(&self) -> &Arc<Session> {
        &self.gen_.session
    }

    /// Cancels the generation, closes its tun, and waits up to [`STOP_WAIT`] for its tasks. A
    /// task still running then is left to finish on its own: the tun is closed already, and a
    /// flow owns its socket, so none can reach a descriptor number reused later.
    pub fn stop(self, rt: &Handle) {
        let begun = Instant::now();
        let g = &self.gen_;
        g.cancel.cancel();
        g.tracker.close();
        self.tun.revoke();
        // The timer must be created inside block_on, where the runtime's context is set.
        let done = rt
            .block_on(async { tokio::time::timeout(STOP_WAIT, g.tracker.wait()).await })
            .is_ok();
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
