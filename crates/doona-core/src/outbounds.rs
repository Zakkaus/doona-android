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

use crate::api::lock;
use crate::catalog::NodeEntry;
use crate::dto::ErrorCode;
use honk_config::node::Node;
use honk_outbound::proxy::ProxyRegistry;
use honk_outbound::runtime::OutboundRuntimeRegistry;
use std::collections::{HashMap, HashSet};
use std::io::{self, ErrorKind};
use std::sync::atomic::AtomicU64;
use std::sync::{Arc, Mutex, RwLock, RwLockReadGuard, RwLockWriteGuard};
use std::time::Instant;
use tokio::runtime::{Handle, Runtime};
use tokio_util::task::AbortOnDropHandle;

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

#[derive(Default)]
struct Table {
    published: Option<Published>,
    /// Group id to the node it sends traffic to, nested groups followed.
    selected: HashMap<String, Arc<Node>>,
}

/// A registry and the carriers it was built for.
#[derive(Clone)]
struct Published {
    registry: Arc<OutboundRuntimeRegistry>,
    carriers: Arc<Carriers>,
}

/// What a flow needs to go through one group: its current node, and the registry and handlers
/// that dial it (`ProxyRegistry::dial_runtime` with `node.id`) on its carriers.
pub struct Dialer {
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
    /// Every registry published for these carriers, which may hold an endpoint or a session;
    /// `None` once they ended.
    registries: Mutex<Option<Vec<Arc<OutboundRuntimeRegistry>>>>,
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

    /// A registry for `nodes`, reusing `previous`'s runtimes where the node is the same.
    fn build(
        &self,
        nodes: &[Node],
        previous: Option<&OutboundRuntimeRegistry>,
    ) -> Option<(Arc<OutboundRuntimeRegistry>, HashSet<uuid::Uuid>)> {
        let runtime = read(&self.runtime);
        let _entered = runtime.as_ref()?.enter();
        let dials = honk_config::config::GlobalConfig::default().max_concurrent_dials;
        match OutboundRuntimeRegistry::build_reusing(nodes, dials, previous) {
            Ok((registry, reused)) => Some((Arc::new(registry), reused)),
            Err(_) => {
                // The parser admits only nodes the registry takes, so this is not expected.
                log::error!("outbounds: registry refused a set of {} nodes", nodes.len());
                None
            }
        }
    }

    /// Records a published registry, to be shut down with the carriers; one published after
    /// they ended refuses new dials at once.
    fn adopt(&self, registry: &Arc<OutboundRuntimeRegistry>) {
        match &mut *lock(&self.registries) {
            Some(registries) => registries.push(Arc::clone(registry)),
            None => registry.begin_retirement(),
        }
    }

    fn ended(&self) -> bool {
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
    /// what it still holds (a QUIC endpoint, a pooled session) on `rt`. Runs outside any
    /// runtime. Returns whether both finished in time.
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
        let (tx, rx) = std::sync::mpsc::channel();
        rt.spawn(async move {
            futures_util::future::join_all(registries.iter().map(|r| r.shutdown())).await;
            let _ = tx.send(());
        });
        let left = deadline.saturating_duration_since(Instant::now());
        rx.recv_timeout(left).is_ok() && done
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
        })
    }

    /// The node `group` (an id such as `g-Proxy`) sends traffic to now, and what dials it;
    /// `None` while the group does not exist or has no node.
    pub fn dialer(&self, group: &str) -> Option<Dialer> {
        let table = self.table();
        let published = table.published.as_ref()?;
        Some(Dialer {
            node: Arc::clone(table.selected.get(group)?),
            registry: Arc::clone(&published.registry),
            proxies: Arc::clone(&self.proxies),
            carriers: Arc::clone(&published.carriers),
        })
    }

    /// The published registry and the carriers it dials on.
    pub(crate) fn published(&self) -> Option<(Arc<OutboundRuntimeRegistry>, Arc<Carriers>)> {
        let p = self.table().published.clone()?;
        Some((p.registry, p.carriers))
    }

    #[cfg(test)]
    pub(crate) fn registry(&self) -> Option<Arc<OutboundRuntimeRegistry>> {
        self.published().map(|(registry, _)| registry)
    }

    pub(crate) fn proxies(&self) -> Arc<ProxyRegistry> {
        Arc::clone(&self.proxies)
    }

    /// A registry for `nodes` on the current carriers, reusing the published one's runtimes
    /// when it is on them too. Nodes of several subscriptions with one endpoint identity share a
    /// runtime.
    pub(crate) fn build(&self, nodes: &[Arc<NodeEntry>]) -> Built {
        let mut seen = HashSet::new();
        let nodes: Vec<Node> = nodes
            .iter()
            .filter(|n| seen.insert(n.node.id))
            .map(|n| n.node.as_ref().clone())
            .collect();
        let base = self.table().published.clone();
        let (published, reused) = self.registry_for(&nodes, base.as_ref());
        Built {
            published,
            reused,
            base: base.map(|p| p.registry),
            nodes,
        }
    }

    /// A registry for `nodes` on the current carriers, made if there are none, reusing
    /// `previous`'s runtimes if it is on them; none for no nodes.
    fn registry_for(
        &self,
        nodes: &[Node],
        previous: Option<&Published>,
    ) -> (Option<Published>, HashSet<uuid::Uuid>) {
        if nodes.is_empty() {
            return (None, HashSet::new());
        }
        let Some(carriers) = self.carriers() else {
            return (None, HashSet::new());
        };
        let previous = previous
            .filter(|p| Arc::ptr_eq(&p.carriers, &carriers))
            .map(|p| p.registry.as_ref());
        match carriers.build(nodes, previous) {
            Some((registry, reused)) => (Some(Published { registry, carriers }), reused),
            None => (None, HashSet::new()),
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

    /// Publishes `built` and each group's node for its node set in one swap; the previous
    /// registry hands over the runtimes it shares and retires. One built on carriers that ended
    /// since, or from a registry no longer published, is built again with runtimes of its own.
    pub(crate) fn replace(&self, built: Built, selected: HashMap<String, Arc<Node>>) {
        let (old, reused) = {
            let mut table = self.table.write().unwrap_or_else(|e| e.into_inner());
            let published = table.published.as_ref().map(|p| &p.registry);
            let current = built.published.as_ref().is_none_or(|p| {
                self.is_current(&p.carriers) && same(built.base.as_ref(), published)
            });
            let (published, reused) = if current {
                (built.published, built.reused)
            } else {
                self.registry_for(&built.nodes, None)
            };
            if let Some(p) = &published {
                p.carriers.adopt(&p.registry);
            }
            let old = table.published.clone();
            *table = Arc::new(Table {
                published,
                selected,
            });
            (old, reused)
        };
        if let Some(old) = old.filter(|old| !old.carriers.ended()) {
            old.registry.mark_moved_out(reused);
            let registry = old.registry;
            old.carriers
                .spawn(async move { registry.retire_reusable_state().await });
        }
    }

    /// Publishes each group's current node.
    pub(crate) fn select(&self, selected: HashMap<String, Arc<Node>>) {
        let mut table = self.table.write().unwrap_or_else(|e| e.into_inner());
        *table = Arc::new(Table {
            published: table.published.clone(),
            selected,
        });
    }

    /// Builds the published registry again, for the same nodes on new carriers, when its own
    /// ended: a generation or a latency test is about to dial through it.
    pub(crate) fn prepare(&self) -> Result<(), ErrorCode> {
        let mut table = self.table.write().unwrap_or_else(|e| e.into_inner());
        let Some(stale) = table.published.as_ref().filter(|p| p.carriers.ended()) else {
            return Ok(());
        };
        let nodes: Vec<Node> = stale
            .registry
            .values()
            .map(|runtime| runtime.node.as_ref().clone())
            .collect();
        let (published, _) = self.registry_for(&nodes, None);
        let published = published.ok_or(ErrorCode::Internal)?;
        published.carriers.adopt(&published.registry);
        *table = Arc::new(Table {
            published: Some(published),
            selected: table.selected.clone(),
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
        let carriers = {
            let mut current = lock(&self.current);
            if current.owner != generation {
                return None;
            }
            current.owner = 0;
            current.carriers.take()?
        };
        let registries = lock(&carriers.registries).take().unwrap_or_default();
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
}

/// A registry for a new node set, not yet published.
#[derive(Default)]
pub(crate) struct Built {
    published: Option<Published>,
    reused: HashSet<uuid::Uuid>,
    /// The registry whose runtimes it reuses, and its nodes: when that one is no longer
    /// published by the time this is, the set is built again with runtimes of its own.
    base: Option<Arc<OutboundRuntimeRegistry>>,
    nodes: Vec<Node>,
}

fn same(
    a: Option<&Arc<OutboundRuntimeRegistry>>,
    b: Option<&Arc<OutboundRuntimeRegistry>>,
) -> bool {
    match (a, b) {
        (Some(a), Some(b)) => Arc::ptr_eq(a, b),
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
