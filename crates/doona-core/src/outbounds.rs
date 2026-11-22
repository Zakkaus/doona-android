//! The hand-off from the catalog to the data path: for each group, the node it sends traffic to
//! now, with honk's runtime registry that dials it and the protocol handlers. The catalog
//! publishes a new table whenever a selection, a latency or the node set changes; a new flow
//! reads one with [`Outbounds::dialer`] and keeps what it read.
//!
//! The registry is rebuilt when the node set changes, reusing the runtimes of the nodes that
//! stay; the previous one then retires the rest without cutting streams that use them. A
//! registry's runtimes hold the carriers (pooled sessions, a QUIC connection and its endpoint),
//! and honk spawns their drivers on whatever runtime dials, without keeping a handle to join. So
//! every registry is built for one [`Carriers`], a runtime of its own on which every dial, probe
//! and retirement through it runs: shutting that runtime down ends every task honk spawned there.
//! The carriers belong to the tunnel generation that last claimed them, which dials through them
//! and adopts what dialed while none ran; when it ends, [`Outbounds::end`] takes them out of use
//! and [`Ended::finish`] shuts them down by `stop`'s deadline. The end of an earlier generation
//! leaves them alone. New carriers, and a registry for them, are made when next needed.
//!
//! A registry holds one runtime per honk id, and honk's id leaves out some of what a node dials
//! with: a TUIC ALPN, a gRPC service of `""` against none, a pinned certificate. Nodes of two
//! subscriptions can share an id and dial differently, so a node set is built as [`Registries`]:
//! each node goes in the first registry that does not hold its id, and a node that dials as one a
//! registry holds shares that one's runtime. Almost always there is one registry.

use crate::api::lock;
use crate::catalog::NodeEntry;
use crate::dto::ErrorCode;
use futures_util::FutureExt;
use honk_config::node::Node;
use honk_outbound::proxy::ProxyRegistry;
use honk_outbound::runtime::OutboundRuntimeRegistry;
use std::collections::HashSet;
use std::io::{self, ErrorKind};
use std::sync::atomic::AtomicU64;
use std::sync::{Arc, Mutex, RwLock, RwLockReadGuard, RwLockWriteGuard, Weak};
use std::time::Instant;
use tokio::runtime::{Handle, Runtime};
use tokio_util::task::AbortOnDropHandle;

mod layers;
mod routing;

pub(crate) use self::layers::Registries;
use self::layers::layers;
use self::routing::Table;
pub(crate) use self::routing::{Routing, View};

/// Threads of a carriers runtime: the protocol work of every proxied flow (TLS, QUIC, session
/// framing) runs there.
const WORKERS: usize = 2;

pub struct Outbounds {
    proxies: Arc<ProxyRegistry>,
    table: RwLock<Arc<Table>>,
    /// The carriers new registries are built for, and who ends them. Taken after the table lock
    /// when both are held.
    current: Mutex<Current>,
    /// The panics on the core's threads, the carriers' included (see `panics.rs`).
    panics: Arc<AtomicU64>,
    #[cfg(test)]
    before_build: Mutex<Option<Box<dyn FnOnce() + Send>>>,
}

impl std::fmt::Debug for Outbounds {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Outbounds").finish_non_exhaustive()
    }
}

#[derive(Default)]
struct Current {
    /// None until needed after the last ended.
    carriers: Option<Arc<Carriers>>,
    /// The generation whose end ends them; 0 when none claimed them since.
    owner: i64,
}

/// Registries and the carriers they were built for.
#[derive(Clone)]
struct Published {
    registries: Registries,
    carriers: Arc<Carriers>,
}

/// What a flow needs to go through one group: its current node, and the registry and handlers
/// that dial it (`ProxyRegistry::dial_runtime` with `node.id`) on its carriers.
pub struct Dialer {
    /// The group the node was chosen through: the flow's outbound.
    pub group: Arc<str>,
    pub node: Arc<Node>,
    pub registry: Arc<OutboundRuntimeRegistry>,
    pub proxies: Arc<ProxyRegistry>,
    carriers: Arc<Carriers>,
}

impl Dialer {
    /// Whether the node carries UDP (`dial_udp_transport_runtime`).
    pub fn supports_udp(&self) -> bool {
        (honk_outbound::descriptor::descriptor(self.node.protocol()).supports_udp)(&self.node)
    }

    /// Runs `dial` on the node's carriers, so every task honk spawns for it is theirs.
    pub async fn on_carriers<T: Send + 'static>(
        &self,
        dial: impl Future<Output = T> + Send + 'static,
    ) -> io::Result<T> {
        self.carriers.run(dial).await
    }
}

/// The runtime one stretch of generations dials on, and the registries built for it. Quinn's
/// endpoint and connection drivers and AnyTLS's readers and writers run there, which honk closes
/// without joining; shutting the runtime down drops them, and the sockets they own, at once.
pub(crate) struct Carriers {
    /// Taken when the carriers are shut down.
    runtime: RwLock<Option<Runtime>>,
    /// The registries published for these carriers that are still in use, and so may hold an
    /// endpoint or a session; `None` once they ended. A registry nothing uses any more is freed,
    /// and what it dialed is ended with the runtime.
    registries: Mutex<Option<Vec<Weak<OutboundRuntimeRegistry>>>>,
}

impl Carriers {
    fn new(panics: &Arc<AtomicU64>) -> io::Result<Carriers> {
        let counted = Arc::clone(panics);
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(WORKERS)
            .thread_name("doona-carrier")
            .on_thread_start(move || crate::panics::count_on_this_thread(&counted))
            .enable_all()
            .build()?;
        Ok(Carriers {
            runtime: RwLock::new(Some(runtime)),
            registries: Mutex::new(Some(Vec::new())),
        })
    }

    /// Runs `fut` on the carriers' runtime and waits for it; dropping the wait aborts it. Fails
    /// once the carriers are shut down.
    pub(crate) async fn run<T: Send + 'static>(
        &self,
        fut: impl Future<Output = T> + Send + 'static,
    ) -> io::Result<T> {
        let task = {
            let runtime = read(&self.runtime);
            let runtime = runtime.as_ref().ok_or(ErrorKind::NotConnected)?;
            AbortOnDropHandle::new(runtime.spawn(fut))
        };
        task.await.map_err(|_| ErrorKind::ConnectionAborted.into())
    }

    /// Spawns `fut` on the carriers' runtime, unless they are shut down.
    fn spawn(&self, fut: impl Future<Output = ()> + Send + 'static) {
        if let Some(runtime) = &*read(&self.runtime) {
            runtime.spawn(fut);
        }
    }

    /// A registry for each of `layers`, reusing the runtimes of `previous`'s registry at the same
    /// place where the node is the same, and the ids each reused; none once the carriers are shut
    /// down. The runtime's handle is cloned under the lock, so a shutdown never waits for a build.
    ///
    /// Every registry dials within one dial ceiling. honk gives a registry the ceiling of the one
    /// it is built from, and a new one otherwise; the VLESS carrier limit is honk's own static
    /// whatever the registry. So a registry with none at its place in `previous` is built from
    /// the first one built here: its nodes are all of ids the first holds, each of a node that
    /// dials otherwise, so honk, which reuses a runtime only for an equal node, reuses none of the
    /// first's.
    fn build(
        &self,
        layers: &[Vec<Node>],
        previous: Option<&Registries>,
    ) -> Option<(Registries, Vec<HashSet<uuid::Uuid>>)> {
        let handle = read(&self.runtime).as_ref()?.handle().clone();
        let _entered = handle.enter();
        let dials = honk_config::config::GlobalConfig::default().max_concurrent_dials;
        let mut registries = Vec::with_capacity(layers.len());
        let mut reused = Vec::with_capacity(layers.len());
        for (i, nodes) in layers.iter().enumerate() {
            let previous = previous.and_then(|p| p.0.get(i)).or(registries.first());
            match OutboundRuntimeRegistry::build_reusing(nodes, dials, previous.map(Arc::as_ref)) {
                Ok((registry, kept)) => {
                    registries.push(Arc::new(registry));
                    reused.push(kept);
                }
                Err(_) => {
                    // The parser admits only nodes the registry takes, so this is not expected.
                    log::error!("outbounds: registry refused a set of {} nodes", nodes.len());
                    return None;
                }
            }
        }
        Some((Registries(registries), reused))
    }

    /// Records a published registry, to be shut down with the carriers, and forgets those
    /// freed since; one published after they ended refuses new dials at once.
    fn adopt(&self, registry: &Arc<OutboundRuntimeRegistry>) {
        match &mut *lock(&self.registries) {
            Some(registries) => {
                registries.retain(|r| r.strong_count() > 0);
                registries.push(Arc::downgrade(registry));
            }
            None => registry.begin_retirement(),
        }
    }

    /// Whether a generation's end took the carriers out of use.
    pub(crate) fn ended(&self) -> bool {
        lock(&self.registries).is_none()
    }
}

impl Drop for Carriers {
    fn drop(&mut self) {
        // Dropped on any thread, a runtime thread included, where waiting is not allowed.
        let runtime = self.runtime.get_mut().unwrap_or_else(|e| e.into_inner());
        if let Some(runtime) = runtime.take() {
            runtime.shutdown_background();
        }
    }
}

/// Carriers taken out of use: their registries refuse new dials.
pub(crate) struct Ended {
    carriers: Arc<Carriers>,
    registries: Vec<Arc<OutboundRuntimeRegistry>>,
}

impl Ended {
    /// Shuts the carriers down by `deadline`: every task on their runtime is dropped, the
    /// drivers honk spawned and dials under way included, and then each registry lets go of
    /// what it still holds (a cached QUIC connection and its endpoint, a pooled session) on
    /// this thread, so none of it waits for a worker of the core's runtime `rt`. Runs outside
    /// any runtime. Returns whether all of it finished in time.
    pub fn finish(self, rt: &Handle, deadline: Instant) -> bool {
        let runtime = write(&self.carriers.runtime).take();
        let mut done = true;
        if let Some(runtime) = runtime {
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() || Handle::try_current().is_ok() {
                runtime.shutdown_background();
                done = false;
            } else {
                runtime.shutdown_timeout(left);
                done = Instant::now() < deadline;
            }
        }
        let registries = self.registries;
        let mut shutdown = Box::pin(async move {
            futures_util::future::join_all(registries.iter().map(|r| r.shutdown())).await;
        });
        // Nothing runs on the carriers any more, so nothing holds what the shutdown locks: it
        // ends in one poll. In `rt`'s context, which needs none of its workers, anything it
        // spawns has a runtime; should it not end, `rt` finishes it later.
        let _entered = rt.enter();
        if shutdown.as_mut().now_or_never().is_some() {
            return done;
        }
        rt.spawn(shutdown);
        false
    }
}

impl Outbounds {
    pub(crate) fn new(panics: Arc<AtomicU64>) -> Result<Outbounds, ErrorCode> {
        let proxies = ProxyRegistry::default_resolver().map_err(|_| ErrorCode::Internal)?;
        Ok(Outbounds {
            proxies: Arc::new(proxies),
            table: RwLock::new(Arc::default()),
            current: Mutex::default(),
            panics,
            #[cfg(test)]
            before_build: Mutex::default(),
        })
    }

    /// What new flows are routed by now.
    pub(crate) fn view(&self) -> View<'_> {
        View {
            outbounds: self,
            table: self.table(),
        }
    }

    /// The node `group` (an id such as `g-Proxy`) sends traffic to now, and what dials it;
    /// `None` while the group does not exist or has no node.
    pub fn dialer(&self, group: &str) -> Option<Dialer> {
        self.view().dialer(group)
    }

    /// The published registries and the carriers they dial on.
    pub(crate) fn published(&self) -> Option<(Registries, Arc<Carriers>)> {
        let p = self.table().published.clone()?;
        Some((p.registries, p.carriers))
    }

    /// The first published registry, which holds every node but those of an id another holds.
    #[cfg(test)]
    pub(crate) fn registry(&self) -> Option<Arc<OutboundRuntimeRegistry>> {
        self.published().and_then(|(r, _)| r.0.first().cloned())
    }

    /// Every published registry.
    #[cfg(test)]
    pub(crate) fn registries(&self) -> Vec<Arc<OutboundRuntimeRegistry>> {
        self.published().map(|(r, _)| r.0).unwrap_or_default()
    }

    pub(crate) fn proxies(&self) -> Arc<ProxyRegistry> {
        Arc::clone(&self.proxies)
    }

    /// Registries for `nodes` on the current carriers, reusing the published ones' runtimes
    /// when they are on them too. Nodes of several subscriptions that dial alike share a runtime.
    pub(crate) fn build(&self, nodes: &[Arc<NodeEntry>]) -> Built {
        let layers = layers(nodes.iter().map(|n| n.node.as_ref()));
        let base = self.table().published.clone();
        let (published, reused) = self.registry_for(&layers, base.as_ref());
        Built {
            published,
            reused,
            base: base.map(|p| p.registries),
            layers,
        }
    }

    /// Registries for `layers` on the current carriers, made if there are none, reusing
    /// `previous`'s runtimes if it is on them; none for no nodes.
    fn registry_for(
        &self,
        layers: &[Vec<Node>],
        previous: Option<&Published>,
    ) -> (Option<Published>, Vec<HashSet<uuid::Uuid>>) {
        if layers.is_empty() {
            return (None, Vec::new());
        }
        let Some(carriers) = self.carriers() else {
            return (None, Vec::new());
        };
        #[cfg(test)]
        {
            let f = lock(&self.before_build).take();
            if let Some(f) = f {
                f();
            }
        }
        let previous = previous
            .filter(|p| Arc::ptr_eq(&p.carriers, &carriers))
            .map(|p| &p.registries);
        match carriers.build(layers, previous) {
            Some((registries, reused)) => (
                Some(Published {
                    registries,
                    carriers,
                }),
                reused,
            ),
            None => (None, Vec::new()),
        }
    }

    /// Registries for `layers` with runtimes of their own, on carriers still current once they
    /// are built; called with the table locked. A stop that ends the carriers under the first try
    /// spoils it; the second goes on carriers made since, which no generation can end before
    /// its `prepare`, and that waits for the table.
    fn rebuilt(&self, layers: &[Vec<Node>]) -> (Option<Published>, Vec<HashSet<uuid::Uuid>>) {
        let first = self.registry_for(layers, None);
        let spoiled = match &first.0 {
            Some(p) => !self.is_current(&p.carriers),
            None => !layers.is_empty(),
        };
        if spoiled {
            self.registry_for(layers, None)
        } else {
            first
        }
    }

    /// The current carriers, made if there are none.
    fn carriers(&self) -> Option<Arc<Carriers>> {
        let mut current = lock(&self.current);
        if let Some(carriers) = &current.carriers {
            return Some(Arc::clone(carriers));
        }
        match Carriers::new(&self.panics) {
            Ok(carriers) => Some(Arc::clone(current.carriers.insert(Arc::new(carriers)))),
            Err(e) => {
                log::error!("outbounds: no runtime for the carriers ({:?})", e.kind());
                None
            }
        }
    }

    fn is_current(&self, carriers: &Arc<Carriers>) -> bool {
        lock(&self.current)
            .carriers
            .as_ref()
            .is_some_and(|c| Arc::ptr_eq(c, carriers))
    }

    /// Publishes `built` and the routing of its node set in one swap; the previous registries
    /// hand over the runtimes they share and retire. One built on carriers that ended since, or
    /// from registries no longer published, is built again with runtimes of its own.
    pub(crate) fn replace(&self, built: Built, routing: Routing) {
        let (old, reused) = {
            let mut table = self.table.write().unwrap_or_else(|e| e.into_inner());
            let published = table.published.as_ref().map(|p| &p.registries);
            let current = match &built.published {
                Some(p) => self.is_current(&p.carriers) && same(built.base.as_ref(), published),
                // No registry for a set of nodes: its carriers may have been shut down under it.
                None => built.layers.is_empty(),
            };
            let (published, reused) = if current {
                (built.published, built.reused)
            } else {
                self.rebuilt(&built.layers)
            };
            if let Some(p) = &published {
                p.registries.0.iter().for_each(|r| p.carriers.adopt(r));
            }
            let old = table.published.clone();
            *table = Arc::new(Table::new(published, routing));
            (old, reused)
        };
        if let Some(old) = old.filter(|old| !old.carriers.ended()) {
            let mut reused = reused.into_iter();
            for registry in old.registries.0 {
                registry.mark_moved_out(reused.next().unwrap_or_default());
                old.carriers
                    .spawn(async move { registry.retire_reusable_state().await });
            }
        }
    }

    /// Publishes each group's current node and the policy.
    pub(crate) fn select(&self, routing: Routing) {
        let mut table = self.table.write().unwrap_or_else(|e| e.into_inner());
        *table = Arc::new(Table::new(table.published.clone(), routing));
    }

    /// Builds the published registry again, for the same nodes on new carriers, when its own
    /// ended: a generation or a latency test is about to dial through it.
    pub(crate) fn prepare(&self) -> Result<(), ErrorCode> {
        let mut table = self.table.write().unwrap_or_else(|e| e.into_inner());
        let Some(stale) = table.published.as_ref().filter(|p| p.carriers.ended()) else {
            return Ok(());
        };
        let layers: Vec<Vec<Node>> = (stale.registries.0.iter())
            .map(|r| {
                r.values()
                    .map(|runtime| runtime.node.as_ref().clone())
                    .collect()
            })
            .collect();
        let (published, _) = self.rebuilt(&layers);
        let published = published.ok_or(ErrorCode::Internal)?;
        (published.registries.0.iter()).for_each(|r| published.carriers.adopt(r));
        *table = Arc::new(Table {
            published: Some(published),
            selected: table.selected.clone(),
            policy: Arc::clone(&table.policy),
        });
        Ok(())
    }

    /// Makes `generation` the one whose end ends the current carriers, and those made until it
    /// ends; it adopts whatever dialed on them before.
    pub(crate) fn claim(&self, generation: i64) {
        lock(&self.current).owner = generation;
    }

    /// Takes the carriers `generation` claimed out of use, unless a later generation claimed
    /// them since: every registry built for them refuses new dials at once, and the caller shuts
    /// them down with [`Ended::finish`].
    pub(crate) fn end(&self, generation: i64) -> Option<Ended> {
        let (carriers, registries) = {
            let mut current = lock(&self.current);
            if current.owner != generation {
                return None;
            }
            current.owner = 0;
            let carriers = current.carriers.take()?;
            // Ended in the same step as they stop being current, so a `prepare` sees one or the
            // other.
            let registries = lock(&carriers.registries).take().unwrap_or_default();
            (carriers, registries)
        };
        let registries: Vec<_> = registries.iter().filter_map(Weak::upgrade).collect();
        for r in &registries {
            r.begin_retirement();
        }
        Some(Ended {
            carriers,
            registries,
        })
    }

    fn table(&self) -> Arc<Table> {
        Arc::clone(&read(&self.table))
    }

    /// Runs `f` once in the next registry build, once it took its carriers and before it uses
    /// them.
    #[cfg(test)]
    pub(crate) fn on_build(&self, f: impl FnOnce() + Send + 'static) {
        *lock(&self.before_build) = Some(Box::new(f));
    }

    /// How many registries the current carriers keep for their end, freed or not.
    #[cfg(test)]
    pub(crate) fn retained(&self) -> usize {
        let carriers = lock(&self.current).carriers.clone();
        carriers.map_or(0, |c| lock(&c.registries).as_ref().map_or(0, Vec::len))
    }
}

/// Registries for a new node set, not yet published.
#[derive(Default)]
pub(crate) struct Built {
    /// None for no nodes, or when none could be made, as when the carriers it was built for
    /// were shut down under it.
    published: Option<Published>,
    /// The ids each registry reused from the previous one at its place.
    reused: Vec<HashSet<uuid::Uuid>>,
    /// The registries whose runtimes it reuses, and its nodes in layers: when those are no
    /// longer published by the time this is, the set is built again with runtimes of its own.
    base: Option<Registries>,
    layers: Vec<Vec<Node>>,
}

fn same(a: Option<&Registries>, b: Option<&Registries>) -> bool {
    match (a, b) {
        (Some(a), Some(b)) => {
            a.0.len() == b.0.len() && a.0.iter().zip(&b.0).all(|(a, b)| Arc::ptr_eq(a, b))
        }
        (None, None) => true,
        _ => false,
    }
}

/// Poisoned locks still hold consistent data here: every critical section only assigns.
fn read<T>(l: &RwLock<T>) -> RwLockReadGuard<'_, T> {
    l.read().unwrap_or_else(|e| e.into_inner())
}

fn write<T>(l: &RwLock<T>) -> RwLockWriteGuard<'_, T> {
    l.write().unwrap_or_else(|e| e.into_inner())
}

#[cfg(test)]
mod tests;
