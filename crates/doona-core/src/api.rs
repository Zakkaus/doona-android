//! The plain Rust API behind the five JNI functions. Host tests drive it without a JVM; `jni.rs`
//! only converts arguments, holds the process's one [`Core`] and throws.

use crate::bootstrap;
use crate::catalog::{self, CatalogState, Shared};
use crate::dto::{
    self, Empty, ErrorCode, GroupArgs, IdArgs, Mode, ModeArgs, Reply, Snapshot, StartConfig,
    Traffic,
};
use crate::outbounds::Outbounds;
use crate::probe::{self, Prober, Probes};
use crate::state::{Loaded, State, Store};
use crate::tunnel::route::{ModeCell, PROXY_GROUP};
use crate::tunnel::{self, Gate, Launch, STOP_WAIT, Settings, Tun, Tunnel};
use serde::Serialize;
use serde::de::DeserializeOwned;
use std::collections::HashSet;
use std::net::SocketAddr;
use std::os::fd::OwnedFd;
use std::path::Path;
use std::sync::atomic::{AtomicI64, AtomicU64, Ordering::Relaxed};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};
use tokio::runtime::Runtime;

#[cfg(feature = "fault-hook")]
mod fault;
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
    /// What a latency probe fetches through each node.
    pub probe_url: String,
    /// How long `addSubscription` and `updateSubscription` may take up to their commit.
    pub subscription_bound: Duration,
    /// The port `start`'s `bootstrapDns` resolvers listen on, and the resolvers used without them.
    pub bootstrap_port: u16,
    pub bootstrap_fallback: Vec<SocketAddr>,
    /// How long a UDP association lasts without traffic.
    pub udp_idle: Duration,
}

impl Default for Options {
    fn default() -> Options {
        Options {
            dns_upstreams: crate::dns::UPSTREAMS.to_vec(),
            probe_url: probe::DEFAULT_PROBE_URL.to_owned(),
            subscription_bound: subscriptions::SUBSCRIPTION_BOUND,
            bootstrap_port: 53,
            bootstrap_fallback: bootstrap::FALLBACK.to_vec(),
            udp_idle: tunnel::UDP_IDLE,
        }
    }
}

pub struct Core {
    runtime: Runtime,
    options: Options,
    store: Store,
    /// The catalog and the data path's view of it, shared with probe tasks.
    shared: Arc<Shared>,
    /// Serializes the commands that write `state.json`, so a slow write never holds the
    /// catalog. Only they change the node set, so what one reads stays current until it commits.
    writer: Mutex<()>,
    /// The committed mode, shared with the running generation.
    mode: Arc<ModeCell>,
    /// Which generation runs, or that one is starting. Held only to read or swap it, never
    /// across a wait, so `stop` and `snapshot` never wait for a `start`. Taken before the catalog
    /// lock when both are held.
    life: Mutex<Life>,
    /// The last generation `start` began; it stays in snapshots after `stop`.
    generation: AtomicI64,
    /// What serves a generation, and how long `start` may take: the driver and [`START_BOUND`]
    /// in the app, stand-ins in unit tests.
    launch: (Launch, Duration),
    probes: Probes,
    /// Subscriptions with an update under way; a second update of one is `busy`.
    refreshing: Mutex<HashSet<String>>,
    /// The panics on the runtime's threads so far; a generation ends when it rises.
    panics: Arc<AtomicU64>,
    #[cfg(test)]
    hooks: hooks::Hooks,
}

/// Whether `life` runs a generation whose snapshot carries a warning; leaving it clears the
/// warning, which raises the revision like any other change of it.
fn warned(life: &Life) -> bool {
    matches!(life, Life::Running(tunnel) if tunnel.session().warning().is_some())
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
        Core::build(
            store,
            options,
            (tunnel::drive, START_BOUND),
            probe::urltest(),
        )
    }

    fn build(
        store: Store,
        options: Options,
        launch: (Launch, Duration),
        prober: Prober,
    ) -> Result<Core, ErrorCode> {
        let (state, loaded) = store.load().map_err(|e| {
            log::error!("init: state unreadable ({:?})", e.kind());
            ErrorCode::Internal
        })?;
        if loaded == Loaded::SetAside {
            log::warn!("init: state.json was unusable; set aside, defaults in use");
        }
        let panics = Arc::new(AtomicU64::new(0));
        crate::panics::install();
        let counted = Arc::clone(&panics);
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(WORKERS)
            .thread_name("doona-core")
            .on_thread_start(move || crate::panics::count_on_this_thread(&counted))
            .enable_all()
            .build()
            .map_err(|_| ErrorCode::Internal)?;
        bootstrap::install();
        bootstrap::use_servers(options.bootstrap_fallback.clone());
        let model = catalog::load(&state);
        let outbounds = Arc::new(Outbounds::new(Arc::clone(&panics))?);
        let built = outbounds.build(&model.nodes);
        let mode = Arc::new(ModeCell::new(state.mode));
        let catalog = CatalogState::new(state, model);
        let shared = Arc::new(Shared {
            catalog: Mutex::new(catalog),
            outbounds,
        });
        shared.publish_nodes(&lock(&shared.catalog), built);
        let probes = Probes::new(prober, &options.probe_url);
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
            probes,
            refreshing: Mutex::new(HashSet::new()),
            panics,
            #[cfg(test)]
            hooks: hooks::Hooks::default(),
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
        (settings, resolvers): (Settings, Vec<SocketAddr>),
        deadline: Instant,
    ) -> Result<i64, ErrorCode> {
        let rt = self.runtime.handle();
        let tun = Tun::new(tun, rt).map_err(|_| ErrorCode::Internal)?;
        let gate = Gate::new();
        let (generation, old) = self.begin(&gate, &tun)?;
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
        self.finish(started, &gate, deadline, resolvers)?;
        Ok(generation)
    }

    /// Marks a start under way and numbers its generation, or answers `busy` (closing `tun`)
    /// while another is. Halts the running generation, if any, and hands it back for the caller
    /// to wait for. The new generation claims the carriers here, in the order of the lifecycle:
    /// a start that `stop` aborted cleans up later, and must not end a newer one's carriers.
    fn begin(&self, gate: &Arc<Gate>, tun: &Arc<Tun>) -> Result<(i64, Option<Tunnel>), ErrorCode> {
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
        // As in `stop`: a running generation's probes end in the same step as its lifecycle.
        let catalog = matches!(*life, Life::Running(_)).then(|| lock(&self.shared.catalog));
        let old = std::mem::replace(&mut *life, starting);
        if let Some(mut c) = catalog {
            c.end_probes();
            if warned(&old) {
                c.revision += 1;
            }
        }
        let generation = self.generation.fetch_add(1, Relaxed) + 1;
        let old = match old {
            Life::Running(old) => {
                old.halt();
                Some(old)
            }
            _ => None,
        };
        self.shared.outbounds.claim(generation);
        Ok((generation, old))
    }

    /// Publishes the started generation with its config's `resolvers`, unless it failed, a
    /// `stop` aborted the start meanwhile (that `stop` closed the tun already and left the core
    /// stopped, which a newer `start` may have changed since) or the deadline passed as the
    /// driver became ready; then it halts and waits for the generation by the deadline. Its
    /// driver serves the tun only once the resolvers are in use; a refused one never does.
    fn finish(
        &self,
        (mut tunnel, ready): (Tunnel, Result<(), ErrorCode>),
        gate: &Gate,
        deadline: Instant,
        resolvers: Vec<SocketAddr>,
    ) -> Result<(), ErrorCode> {
        #[cfg(test)]
        self.hooks.before_finish(self);
        let mut life = lock(&self.life);
        // `stop` aborts under the life lock, which is held here, so this cannot change below.
        let aborted = gate.aborted();
        let code = match ready {
            _ if aborted => ErrorCode::Busy,
            Ok(()) if Instant::now() < deadline => {
                bootstrap::use_servers(resolvers);
                tunnel.release();
                *life = Life::Running(tunnel);
                return Ok(());
            }
            Ok(()) => ErrorCode::Internal,
            Err(code) => code,
        };
        // Closed before the core shows stopped: a `stop` that sees `Stopped` returns at once.
        tunnel.halt();
        if !aborted {
            *life = Life::Stopped;
        }
        drop(life);
        tunnel.wait(self.runtime.handle(), deadline);
        Err(code)
    }

    /// What a generation runs with, and the resolvers that serve host lookups once it is up (also
    /// after `stop`). A resolver that is not an address is `invalid_argument`; Global with no node
    /// in the `Proxy` group is `no_nodes`: the tunnel would carry nothing.
    fn settings(&self, config: &str) -> Result<(Settings, Vec<SocketAddr>), ErrorCode> {
        let config: StartConfig = parse(config)?;
        if config.mtu < MIN_MTU {
            return Err(ErrorCode::InvalidArgument);
        }
        let (port, fallback) = (
            self.options.bootstrap_port,
            &self.options.bootstrap_fallback,
        );
        let resolvers = bootstrap::servers(&config.bootstrap_dns, port, fallback)
            .ok_or(ErrorCode::InvalidArgument)?;
        if self.mode.get() == Mode::Global && self.shared.outbounds.dialer(PROXY_GROUP).is_none() {
            return Err(ErrorCode::NoNodes);
        }
        let settings = Settings {
            mtu: config.mtu,
            dns: config.dns,
            dns_upstreams: self.options.dns_upstreams.clone(),
            udp_idle: self.options.udp_idle,
            mode: Arc::clone(&self.mode),
            outbounds: Arc::clone(&self.shared.outbounds),
            panics: Arc::clone(&self.panics),
            catalog: Arc::downgrade(&self.shared),
        };
        Ok((settings, resolvers))
    }

    /// Ends the running generation, if any, within 1 s, and cancels a `start` under way. Every
    /// tun the core held is closed when it returns.
    pub fn stop(&self) {
        // Taken first, so the teardown below spends the same second as the wait after it.
        let deadline = Instant::now() + STOP_WAIT;
        let halted = {
            let mut life = lock(&self.life);
            // The probes end and the lifecycle changes under one catalog guard, taken after the
            // lifecycle lock. Registration and probe commits take the catalog lock, so a test
            // registers wholly before both, and is dropped, or after both.
            let mut catalog = lock(&self.shared.catalog);
            catalog.end_probes();
            #[cfg(test)]
            self.hooks.teardown(self);
            let old = std::mem::replace(&mut *life, Life::Stopped);
            if warned(&old) {
                catalog.revision += 1;
            }
            drop(catalog);
            match old {
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
        // One moment of the lifecycle, the catalog and the warning: the locks in the order `stop`
        // takes them, and the warning read under the catalog lock its changes take.
        let (revision, catalog, session, warning) = {
            let life = lock(&self.life);
            let c = lock(&self.shared.catalog);
            let catalog = (c.revision > since_revision).then(|| c.model.catalog(&c.state));
            let session = match &*life {
                Life::Running(tunnel) => Some(Arc::clone(tunnel.session())),
                _ => None,
            };
            let warning = session.as_ref().and_then(|s| s.warning());
            (c.revision, catalog, session, warning)
        };
        let snapshot = match session {
            Some(s) => Snapshot {
                revision,
                running: !s.failed(),
                generation: s.generation,
                since_epoch_ms: Some(s.since_epoch_ms),
                error: s.failed().then_some(ErrorCode::Internal),
                warning,
                traffic: s.traffic(),
                catalog,
            },
            None => Snapshot {
                revision,
                running: false,
                generation: self.generation.load(Relaxed),
                since_epoch_ms: None,
                error: None,
                warning: None,
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
            "testLatency" => {
                parse(args).and_then(|a: GroupArgs| self.test_latency(a.group).map(empty))
            }
            "addSubscription" => parse(args).and_then(|a| self.add_subscription(a).map(ok)),
            "updateSubscription" => {
                parse(args).and_then(|a: IdArgs| self.update_subscription(&a.id).map(ok))
            }
            "removeSubscription" => {
                parse(args).and_then(|a: IdArgs| self.remove_subscription(&a.id).map(empty))
            }
            #[cfg(feature = "fault-hook")]
            "debugFault" => self.fault().map(empty),
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
        self.commit_state(State { mode, ..current }, "setMode", None)
    }

    /// Writes `next`, then makes it what `snapshot`, new flows and the data path's groups see.
    /// The caller holds the writer lock and read the current state under it, so nothing else
    /// changed the file meanwhile. Past `deadline`, as [`Core::write`] checks it, nothing changes.
    fn commit_state(
        &self,
        next: State,
        command: &str,
        deadline: Option<Instant>,
    ) -> Result<(), ErrorCode> {
        self.write(&next, command, deadline)?;
        {
            let mut c = lock(&self.shared.catalog);
            c.state = next;
            c.revision += 1;
            self.mode.set(c.state.mode);
            self.shared.publish(&c);
        }
        // A restart reads the new file already, so new flows and `snapshot` must too; the
        // directory sync that follows holds only the writer lock.
        self.store.sync();
        Ok(())
    }

    /// Writes `next` to `state.json`. A `deadline` is checked once the state is serialized,
    /// right before the first write to the file system: past it nothing is written and the
    /// command is `busy`.
    fn write(
        &self,
        next: &State,
        command: &str,
        deadline: Option<Instant>,
    ) -> Result<(), ErrorCode> {
        let mut late = false;
        let written = self.store.commit(next, || {
            #[cfg(test)]
            if let Some(deadline) = deadline {
                self.hooks.before_write(deadline);
            }
            late = deadline.is_some_and(|d| Instant::now() >= d);
            !late
        });
        if late {
            return Err(ErrorCode::Busy);
        }
        written.map_err(|e| {
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
        Core::build(store, Options::default(), (launch, bound), probe::urltest()).unwrap()
    }

    /// A core that keeps its state through `store`.
    pub(crate) fn with_store(store: Store) -> Core {
        let launch = (tunnel::drive as Launch, START_BOUND);
        Core::build(store, Options::default(), launch, probe::urltest()).unwrap()
    }

    /// A core whose latency probes run `prober`.
    pub(crate) fn with_prober(data_dir: &str, prober: Prober) -> Core {
        let store = Store::new(Path::new(data_dir));
        let launch = (tunnel::drive as Launch, START_BOUND);
        Core::build(store, Options::default(), launch, prober).unwrap()
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
        warning: None,
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
mod hooks;
#[cfg(test)]
mod tests;
#[cfg(test)]
mod tests_carriers;
#[cfg(test)]
mod tests_catalog;
#[cfg(test)]
mod tests_latency;
#[cfg(test)]
mod tests_localnet;
