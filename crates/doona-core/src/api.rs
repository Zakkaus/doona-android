//! The plain Rust API behind the five JNI functions. Host tests drive it without a JVM; `jni.rs`
//! only converts arguments, holds the process's one [`Core`] and throws.

use crate::catalog::{self, CatalogState, Shared};
use crate::dto::{
    self, Empty, ErrorCode, GroupArgs, IdArgs, Mode, ModeArgs, Reply, Snapshot, StartConfig,
    Traffic,
};
use crate::state::{Loaded, State, Store};
use crate::tunnel::route::ModeCell;
use crate::tunnel::{self, Gate, Launch, STOP_WAIT, Settings, Tun, Tunnel};
use serde::Serialize;
use serde::de::DeserializeOwned;
use std::collections::HashSet;
use std::net::SocketAddr;
use std::os::fd::OwnedFd;
use std::path::Path;
use std::sync::atomic::{AtomicI64, Ordering::Relaxed};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};
use tokio::runtime::Runtime;

mod selection;
mod subscriptions;

/// The tokio runtime's worker threads; flows are I/O bound and the phone has few big cores.
const WORKERS: usize = 2;
/// `start` returns or fails within this bound (core-api.md).
const START_BOUND: Duration = Duration::from_secs(10);
/// The smallest MTU `start` takes. honk's transparent stack raises a smaller one to 1280 while
/// the driver's buffers follow the MTU it was given, so a smaller one is refused.
const MIN_MTU: u16 = 1280;

/// What a host test may change; the app runs with the defaults.
#[derive(Clone, Debug)]
pub struct Options {
    /// Where DNS the core answers is forwarded, in order.
    pub dns_upstreams: Vec<SocketAddr>,
}

impl Default for Options {
    fn default() -> Options {
        Options {
            dns_upstreams: crate::dns::UPSTREAMS.to_vec(),
        }
    }
}

pub struct Core {
    runtime: Runtime,
    options: Options,
    store: Store,
    /// The catalog, shared with the tasks the core spawns.
    shared: Arc<Shared>,
    /// Serializes the commands that write `state.json`, so a slow write never holds the
    /// catalog. Only they change the node set, so what one reads stays current until it commits.
    writer: Mutex<()>,
    /// The committed mode, shared with the running generation.
    mode: Arc<ModeCell>,
    /// Which generation runs, or that one is starting. Held only to read or swap it, never
    /// across a wait, so `stop` and `snapshot` never wait for a `start`.
    life: Mutex<Life>,
    /// The last generation `start` began; it stays in snapshots after `stop`.
    generation: AtomicI64,
    /// What serves a generation, and how long `start` may take: the driver and [`START_BOUND`]
    /// in the app, stand-ins in unit tests.
    launch: (Launch, Duration),
    /// Subscriptions with an update under way; a second update of one is `busy`.
    refreshing: Mutex<HashSet<String>>,
}

enum Life {
    Stopped,
    /// A `start` is under way; a `stop` cancels it and closes its tun.
    Starting {
        gate: Arc<Gate>,
        tun: Arc<Tun>,
    },
    Running(Tunnel),
}

impl Core {
    /// Reads `data_dir/state.json` and builds the runtime.
    pub fn new(data_dir: &str) -> Result<Core, ErrorCode> {
        Core::with_options(data_dir, Options::default())
    }

    pub fn with_options(data_dir: &str, options: Options) -> Result<Core, ErrorCode> {
        let store = Store::new(Path::new(data_dir));
        Core::build(store, options, (tunnel::drive, START_BOUND))
    }

    fn build(
        store: Store,
        options: Options,
        launch: (Launch, Duration),
    ) -> Result<Core, ErrorCode> {
        let (state, loaded) = store.load().map_err(|e| {
            log::error!("init: state unreadable ({:?})", e.kind());
            ErrorCode::Internal
        })?;
        if loaded == Loaded::SetAside {
            log::warn!("init: state.json was unusable; set aside, defaults in use");
        }
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(WORKERS)
            .thread_name("doona-core")
            .enable_all()
            .build()
            .map_err(|_| ErrorCode::Internal)?;
        let model = catalog::load(&state);
        let mode = Arc::new(ModeCell::new(state.mode));
        let catalog = CatalogState::new(state, model);
        let shared = Arc::new(Shared {
            catalog: Mutex::new(catalog),
        });
        Ok(Core {
            runtime,
            options,
            store,
            shared,
            mode,
            writer: Mutex::new(()),
            life: Mutex::new(Life::Stopped),
            generation: AtomicI64::new(0),
            launch,
            refreshing: Mutex::new(HashSet::new()),
        })
    }

    /// `init`'s reply.
    pub fn schema() -> String {
        to_json(&dto::Init {
            schema: dto::SCHEMA,
        })
    }

    /// Starts a new generation on `tun`, the duplicate of Kotlin's descriptor, which it owns
    /// from here on: every failure closes it. A generation still running is stopped first; a
    /// `start` already under way makes this one `busy`. Returns by one deadline, cleanup
    /// included.
    pub fn start(&self, tun: OwnedFd, config: &str) -> Result<(), ErrorCode> {
        let begun = Instant::now();
        let deadline = begun + self.launch.1;
        let result = self
            .settings(config)
            .and_then(|settings| self.start_generation(tun, settings, deadline));
        let ms = begun.elapsed().as_millis();
        match result {
            Ok(g) => log::info!("start {g}: up in {ms} ms"),
            Err(code) => log::warn!("start failed: {} after {ms} ms", code.as_str()),
        }
        result.map(drop)
    }

    fn start_generation(
        &self,
        tun: OwnedFd,
        settings: Settings,
        deadline: Instant,
    ) -> Result<i64, ErrorCode> {
        let rt = self.runtime.handle();
        let tun = Tun::new(tun, rt).map_err(|_| ErrorCode::Internal)?;
        let gate = Gate::new();
        let old = self.begin(&gate, &tun)?;
        let generation = self.generation.fetch_add(1, Relaxed) + 1;
        if let Some(old) = old {
            old.wait(rt, deadline);
        }
        let started = Tunnel::start(
            rt,
            tun,
            (settings, generation),
            self.launch.0,
            deadline,
            &gate,
        );
        self.finish(started, &gate, deadline)?;
        Ok(generation)
    }

    /// Marks a start under way, or answers `busy` (closing `tun`) while another is. Halts the
    /// running generation, if any, and hands it back for the caller to wait for.
    fn begin(&self, gate: &Arc<Gate>, tun: &Arc<Tun>) -> Result<Option<Tunnel>, ErrorCode> {
        let mut life = lock(&self.life);
        if matches!(*life, Life::Starting { .. }) {
            drop(life);
            tun.revoke();
            return Err(ErrorCode::Busy);
        }
        let starting = Life::Starting {
            gate: Arc::clone(gate),
            tun: Arc::clone(tun),
        };
        match std::mem::replace(&mut *life, starting) {
            Life::Running(old) => {
                old.halt();
                Ok(Some(old))
            }
            _ => Ok(None),
        }
    }

    /// Publishes the started generation, unless a `stop` aborted the start meanwhile (that
    /// `stop` closed the tun already and left the core stopped, which a newer `start` may have
    /// changed since) or the deadline passed as the driver became ready.
    fn finish(
        &self,
        started: Result<Tunnel, ErrorCode>,
        gate: &Gate,
        deadline: Instant,
    ) -> Result<(), ErrorCode> {
        let mut life = lock(&self.life);
        // `stop` aborts under the life lock, which is held here, so this cannot change below.
        let aborted = gate.aborted();
        let (late, code) = if aborted {
            (started.ok(), ErrorCode::Busy)
        } else {
            match started {
                Ok(tunnel) if Instant::now() < deadline => {
                    *life = Life::Running(tunnel);
                    return Ok(());
                }
                Ok(tunnel) => (Some(tunnel), ErrorCode::Internal),
                Err(code) => (None, code),
            }
        };
        // Closed before the core shows stopped: a `stop` that sees `Stopped` returns at once.
        if let Some(tunnel) = &late {
            tunnel.halt();
        }
        if !aborted {
            *life = Life::Stopped;
        }
        drop(life);
        if let Some(tunnel) = late {
            tunnel.wait(self.runtime.handle(), deadline);
        }
        Err(code)
    }

    fn settings(&self, config: &str) -> Result<Settings, ErrorCode> {
        let config: StartConfig = parse(config)?;
        if config.mtu < MIN_MTU {
            return Err(ErrorCode::InvalidArgument);
        }
        Ok(Settings {
            mtu: config.mtu,
            dns: config.dns,
            dns_upstreams: self.options.dns_upstreams.clone(),
            mode: Arc::clone(&self.mode),
        })
    }

    /// Ends the running generation, if any, within 1 s, and cancels a `start` under way. Every
    /// tun the core held is closed when it returns.
    pub fn stop(&self) {
        // Taken first, so the teardown below spends the same second as the wait after it.
        let deadline = Instant::now() + STOP_WAIT;
        let halted = {
            let mut life = lock(&self.life);
            match std::mem::replace(&mut *life, Life::Stopped) {
                Life::Running(tunnel) => {
                    tunnel.halt();
                    Some(tunnel)
                }
                Life::Starting { gate, tun } => {
                    gate.abort();
                    tun.revoke();
                    None
                }
                Life::Stopped => None,
            }
        };
        if let Some(tunnel) = halted {
            tunnel.wait(self.runtime.handle(), deadline);
        }
    }

    pub fn snapshot(&self, since_revision: i64) -> String {
        let (revision, catalog) = {
            let c = lock(&self.shared.catalog);
            let catalog = (c.revision > since_revision).then(|| c.model.catalog(&c.state));
            (c.revision, catalog)
        };
        let session = match &*lock(&self.life) {
            Life::Running(tunnel) => Some(Arc::clone(tunnel.session())),
            _ => None,
        };
        let snapshot = match session {
            Some(s) => Snapshot {
                revision,
                running: !s.failed(),
                generation: s.generation,
                since_epoch_ms: Some(s.since_epoch_ms),
                error: s.failed().then_some(ErrorCode::Internal),
                traffic: s.traffic(),
                catalog,
            },
            None => Snapshot {
                revision,
                running: false,
                generation: self.generation.load(Relaxed),
                since_epoch_ms: None,
                error: None,
                traffic: Traffic::default(),
                catalog,
            },
        };
        to_json(&snapshot)
    }

    pub fn call(&self, command: &str, args: &str) -> String {
        let reply = match command {
            "setMode" => parse(args).and_then(|a: ModeArgs| self.set_mode(a.mode).map(empty)),
            "select" => parse(args).and_then(|a| self.select(a).map(empty)),
            // Latency tests arrive with the next change.
            "testLatency" => parse(args).and_then(|a: GroupArgs| match a.group {
                Some(_) => Err(ErrorCode::NotFound),
                None => Err(ErrorCode::NoNodes),
            }),
            "addSubscription" => parse(args).and_then(|a| self.add_subscription(a).map(ok)),
            "updateSubscription" => {
                parse(args).and_then(|a: IdArgs| self.update_subscription(&a.id).map(ok))
            }
            "removeSubscription" => {
                parse(args).and_then(|a: IdArgs| self.remove_subscription(&a.id).map(empty))
            }
            _ => Err(ErrorCode::InvalidArgument),
        };
        reply.unwrap_or_else(|code| {
            log::info!("call {}: {}", command_name(command), code.as_str());
            error_reply(code)
        })
    }

    /// Commits the mode to `state.json` first; new flows read it from then on.
    fn set_mode(&self, mode: Mode) -> Result<(), ErrorCode> {
        let _writer = lock(&self.writer);
        let current = lock(&self.shared.catalog).state.clone();
        if current.mode == mode {
            return Ok(());
        }
        self.commit_state(State { mode, ..current }, "setMode")
    }

    /// Writes `next`, then makes it what `snapshot` and new flows see.
    /// The caller holds the writer lock and read the current state under it, so nothing else
    /// changed the file meanwhile.
    fn commit_state(&self, next: State, command: &str) -> Result<(), ErrorCode> {
        self.write(&next, command)?;
        {
            let mut c = lock(&self.shared.catalog);
            c.state = next;
            c.revision += 1;
            self.mode.set(c.state.mode);
        }
        // A restart reads the new file already, so new flows and `snapshot` must too; the
        // directory sync that follows holds only the writer lock.
        self.store.sync();
        Ok(())
    }

    fn write(&self, next: &State, command: &str) -> Result<(), ErrorCode> {
        self.store.commit(next).map_err(|e| {
            log::error!("{command}: state not written ({:?})", e.kind());
            ErrorCode::Internal
        })
    }
}

#[cfg(test)]
impl Core {
    /// A core whose generations run `launch` instead of the driver and must start within
    /// `bound`.
    pub(crate) fn with_launch(data_dir: &str, launch: Launch, bound: Duration) -> Core {
        let store = Store::new(Path::new(data_dir));
        Core::build(store, Options::default(), (launch, bound)).unwrap()
    }

    /// A core that keeps its state through `store`.
    pub(crate) fn with_store(store: Store) -> Core {
        Core::build(store, Options::default(), (tunnel::drive, START_BOUND)).unwrap()
    }
}

impl Drop for Core {
    fn drop(&mut self) {
        self.stop();
    }
}

fn empty((): ()) -> String {
    to_json(&Reply::Ok(Empty {}))
}

fn ok<T: Serialize>(value: T) -> String {
    to_json(&Reply::Ok(value))
}

/// `{"error":{"code":…}}`.
pub fn error_reply(code: ErrorCode) -> String {
    to_json(&Reply::<Empty>::Error(dto::CallError { code }))
}

/// What `snapshot` answers before `init`: stopped, nothing to show.
pub fn snapshot_before_init() -> String {
    to_json(&Snapshot {
        revision: 0,
        running: false,
        generation: 0,
        since_epoch_ms: None,
        error: None,
        traffic: Traffic::default(),
        catalog: None,
    })
}

/// A command name fit for the log: the closed list, never caller text.
fn command_name(command: &str) -> &'static str {
    const KNOWN: [&str; 6] = [
        "select",
        "testLatency",
        "addSubscription",
        "updateSubscription",
        "removeSubscription",
        "setMode",
    ];
    KNOWN
        .into_iter()
        .find(|k| *k == command)
        .unwrap_or("unknown")
}

fn parse<T: DeserializeOwned>(args: &str) -> Result<T, ErrorCode> {
    serde_json::from_str(args).map_err(|_| ErrorCode::InvalidArgument)
}

fn to_json<T: Serialize>(value: &T) -> String {
    // The DTOs hold only strings, numbers and maps with string keys, which always serialize.
    serde_json::to_string(value).unwrap_or_default()
}

/// A poisoned lock still holds consistent data here: every critical section only assigns.
pub(crate) fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

#[cfg(test)]
mod tests;
#[cfg(test)]
mod tests_catalog;
