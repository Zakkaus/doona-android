//! One tunnel generation: the duplicate tun fd, the tasks that serve it, and the [`Session`] that
//! `snapshot` reads. Every task of a generation is spawned on its [`TaskTracker`] and stops at its
//! [`CancellationToken`]; a task that panics, or a driver that fails, marks the session failed
//! and cancels the rest. So does a panic in a task honk spawned for a carrier, which never passes
//! through [`Gen::spawn`]. Stopping a generation closes its tun at once (see `tun.rs`), so only
//! tasks without tun access are ever left running, and shuts down the carriers it dialed through
//! within the same deadline (see `outbounds.rs`).

mod driver;
mod flow;
pub mod route;
mod tcp;
mod tun;
mod udp;

use crate::api::lock;
use crate::catalog::Shared;
use crate::dto::{ErrorCode, Traffic};
use crate::localnet::Trace;
use crate::outbounds::{Ended, Outbounds};
use futures_util::FutureExt;
use futures_util::future::BoxFuture;
use route::ModeCell;
use std::future::Future;
use std::io::{self, ErrorKind};
use std::net::{Ipv4Addr, SocketAddr};
use std::panic::AssertUnwindSafe;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering::Relaxed};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, Weak};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio::runtime::Handle;
use tokio::sync::oneshot;
use tokio_util::sync::CancellationToken;
use tokio_util::task::TaskTracker;
pub(crate) use tun::Tun;
pub(crate) use udp::IDLE as UDP_IDLE;

/// How long `stop` waits for a halted generation's tasks; it returns within 1 s.
pub(crate) const STOP_WAIT: Duration = Duration::from_millis(900);
/// How often a generation looks for a panic outside its own tasks.
const WATCH: Duration = Duration::from_secs(1);

/// What a generation runs with: `start`'s config and the core's own settings.
#[derive(Clone, Debug)]
pub struct Settings {
    pub mtu: u16,
    /// The virtual resolver the tun advertises.
    pub dns: Ipv4Addr,
    /// Where DNS the core answers is forwarded, in order.
    pub dns_upstreams: Vec<SocketAddr>,
    /// A UDP association with no datagram either way for this long ends.
    pub udp_idle: Duration,
    /// The committed mode; each new flow reads it.
    pub mode: Arc<ModeCell>,
    /// Each group's current node and what dials it; a new Global flow goes through `g-Proxy`'s.
    pub outbounds: Arc<Outbounds>,
    /// The panics on the core's runtime threads so far (see `panics.rs`).
    pub panics: Arc<AtomicU64>,
    /// The catalog whose revision announces a change of the session's warning.
    pub(crate) catalog: Weak<Shared>,
}

/// The tun as the driver holds it.
pub(crate) type TunHandle = Arc<Tun>;

/// What serves a generation's tun: the netstack driver, or a stand-in in unit tests. It sends on
/// its [`StartSignal`] once it can serve the tun.
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

/// The driver's side of a [`Gate`]: `send` reports the tun ready to serve; dropped unsent, it
/// reports that the driver gave up.
pub(crate) struct StartSignal {
    gate: Option<Arc<Gate>>,
    /// Fires when `start` commits the generation; dropped unfired when it is refused.
    commit: Option<oneshot::Receiver<()>>,
}

impl StartSignal {
    pub fn send(mut self) {
        if let Some(gate) = self.gate.take() {
            gate.report(true);
        }
    }

    /// Reports the tun ready, then waits until `start` commits the generation with its config's
    /// resolvers installed. False when `start` refuses it instead, which also cancels it.
    pub async fn committed(mut self) -> bool {
        let commit = self.commit.take();
        self.send();
        match commit {
            Some(commit) => commit.await.is_ok(),
            None => false,
        }
    }
}

impl Drop for StartSignal {
    fn drop(&mut self) {
        if let Some(gate) = self.gate.take() {
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
    /// Dials through a node begun so far, which numbers each in the order it began.
    dials: AtomicU64,
    /// The warning, and the dial that last showed it (ARCH D26).
    local_network: Mutex<Warned>,
    /// Whose revision rises when the warning changes; none in tests that do not read it.
    catalog: Weak<Shared>,
}

/// Whether a dial through a node was refused at an address on the local network, with none that
/// began later reaching a node there; `began` numbers the dial that showed it.
#[derive(Default)]
struct Warned {
    on: bool,
    began: u64,
}

impl Session {
    pub(crate) fn new(generation: i64, catalog: Weak<Shared>) -> Session {
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
            dials: AtomicU64::new(0),
            local_network: Mutex::default(),
            catalog,
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

    /// What `snapshot` reports besides a dead data path: `local_network` while the latest dial
    /// through a node on the local network that showed anything was refused there.
    pub fn warning(&self) -> Option<ErrorCode> {
        lock(&self.local_network)
            .on
            .then_some(ErrorCode::LocalNetwork)
    }

    /// Numbers a dial through a node, or a first datagram through one, as it begins.
    pub(crate) fn begin_dial(&self) -> u64 {
        self.dials.fetch_add(1, Relaxed) + 1
    }

    /// What the dial numbered `began` showed on its way to its node (`ok` when it succeeded):
    /// refused at an address on the local network, as Android 17 refuses one without the
    /// permission, it warns; a transport that came up on one, as once the permission is granted,
    /// clears the warning.
    pub(crate) fn dialed(&self, began: u64, trace: &Trace, ok: bool) {
        if !ok && trace.refused_locally() {
            self.warn(began, true);
        } else if trace.reached_locally() {
            self.warn(began, false);
        }
    }

    /// How the first datagram through a node on the local network, numbered `began`, went:
    /// refused (`EPERM`) warns, sent clears; any other failure says nothing about the permission.
    pub(crate) fn first_sent(&self, began: u64, sent: &io::Result<()>) {
        match sent {
            Ok(()) => self.warn(began, false),
            Err(e) if e.kind() == ErrorKind::PermissionDenied => self.warn(began, true),
            Err(_) => {}
        }
    }

    /// Sets the warning as the dial numbered `began` showed it, unless a dial that began later
    /// already showed it: a dial that waited out its timeout from before a grant does not undo
    /// what one after the grant showed. A change raises the catalog's revision, under the catalog
    /// lock, so a snapshot of the new revision shows it.
    fn warn(&self, began: u64, on: bool) {
        {
            let mut warned = lock(&self.local_network);
            if began < warned.began {
                return;
            }
            if warned.on == on {
                warned.began = began;
                return;
            }
        }
        let shared = self.catalog.upgrade();
        let mut catalog = shared.as_ref().map(|s| lock(&s.catalog));
        let mut warned = lock(&self.local_network);
        if began < warned.began {
            return;
        }
        warned.began = began;
        if warned.on != on {
            warned.on = on;
            if let Some(c) = &mut catalog {
                c.revision += 1;
            }
        }
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
                // A cancelled task never polls `fut` again: its carriers may be shut down.
                biased;
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
    outbounds: Arc<Outbounds>,
    /// Lets the driver serve once `start` commits (see [`StartSignal::committed`]).
    release: Option<oneshot::Sender<()>>,
    /// The carriers the generation dialed through, taken out of use when it halted.
    ended: Mutex<Option<Ended>>,
}

impl Tunnel {
    /// Serves `tun` on the current carriers and waits until `launch` can serve it, by
    /// `deadline`; a `stop` arriving meanwhile aborts `gate`, which ends the wait with `busy`. The
    /// caller halts the generation after any failure, which closes `tun`.
    pub(crate) fn start(
        rt: &Handle,
        tun: Arc<Tun>,
        (settings, generation): (Settings, i64),
        launch: Launch,
        deadline: Instant,
        gate: &Arc<Gate>,
    ) -> (Tunnel, Result<(), ErrorCode>) {
        let (release, commit) = oneshot::channel();
        let tunnel = Tunnel {
            gen_: Gen {
                session: Arc::new(Session::new(generation, settings.catalog.clone())),
                cancel: CancellationToken::new(),
                tracker: TaskTracker::new(),
                runtime: rt.clone(),
            },
            tun: Arc::clone(&tun),
            outbounds: Arc::clone(&settings.outbounds),
            release: Some(release),
            ended: Mutex::new(None),
        };
        // The previous generation's carriers ended with it: this one dials on new ones.
        if let Err(code) = settings.outbounds.prepare() {
            return (tunnel, Err(code));
        }
        let seen = settings.panics.load(Relaxed);
        let watch = watch(Arc::clone(&settings.panics), seen, tunnel.gen_.clone());
        tunnel.gen_.spawn(watch);
        let signal = StartSignal {
            gate: Some(Arc::clone(gate)),
            commit: Some(commit),
        };
        let gen_ = tunnel.gen_.clone();
        tunnel.gen_.spawn(launch(tun, settings, gen_, signal));
        let ready = gate.wait(deadline);
        (tunnel, ready)
    }

    pub fn session(&self) -> &Arc<Session> {
        &self.gen_.session
    }

    /// Panics in a task of the generation, which ends its data path as a crash would.
    #[cfg(feature = "fault-hook")]
    pub fn fault(&self) {
        self.gen_.spawn(async { panic!("debugFault") });
    }

    /// Lets the driver serve the tun: `start` committed the generation.
    pub fn release(&mut self) {
        if let Some(release) = self.release.take() {
            let _ = release.send(());
        }
    }

    #[cfg(test)]
    pub(crate) fn tun(&self) -> &Arc<Tun> {
        &self.tun
    }

    /// Cancels every task, closes the tun, and takes the carriers the generation claimed out of
    /// use, which [`Tunnel::wait`] shuts down, unless a later generation claimed them since.
    /// Quick: it waits for at most one tun syscall, and builds nothing.
    pub fn halt(&self) {
        self.gen_.cancel.cancel();
        self.tun.revoke();
        if let Some(ended) = self.outbounds.end(self.gen_.session.generation) {
            *lock(&self.ended) = Some(ended);
        }
        self.gen_.tracker.close();
    }

    /// Shuts the halted generation's carriers down, then waits for its tasks, all until
    /// `deadline` and for [`STOP_WAIT`] at most. The carriers come first: closing them needs no
    /// worker of the core's runtime, so a task holding one cannot delay it. A task still running
    /// at the end is left to finish on its own: the tun is closed already, and a flow owns its
    /// socket, so none can reach a descriptor number reused later.
    pub fn wait(self, rt: &Handle, deadline: Instant) {
        let begun = Instant::now();
        let until = deadline.min(begun + STOP_WAIT);
        let ended = lock(&self.ended).take();
        let closed = ended.is_none_or(|e| e.finish(rt, until));
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
        if done && closed {
            log::info!("generation {generation} stopped in {ms} ms");
        } else {
            log::warn!(
                "generation {generation}: {} tasks detached, carriers closed {closed}, {ms} ms",
                g.tracker.len()
            );
        }
    }
}

/// Ends the generation once the core's threads have had a panic since `seen`. Its own tasks
/// report theirs through [`Gen::spawn`] at once; honk's tasks for its carriers run on the
/// carriers' threads and are spawned by honk, so their panics are only counted.
async fn watch(panics: Arc<AtomicU64>, seen: u64, gen_: Gen) {
    loop {
        tokio::time::sleep(WATCH).await;
        if panics.load(Relaxed) != seen {
            log::error!(
                "generation {}: a task on the core's runtime panicked",
                gen_.session.generation
            );
            gen_.die();
            return;
        }
    }
}
