//! The hand-off from the catalog to the data path: for each group, the node it sends traffic to
//! now, with honk's runtime registry that dials it and the protocol handlers. The catalog
//! publishes a new table whenever a selection, a latency or the node set changes; a new flow
//! reads one with [`Outbounds::dialer`] and keeps what it read.
//!
//! The registry is rebuilt when the node set changes, reusing the runtimes of the nodes that
//! stay; the previous one then retires the rest without cutting streams that use them. A
//! registry's runtimes hold the carriers (pooled sessions, a QUIC connection and its endpoint).
//! They belong to the tunnel generation that dials through them: when it ends,
//! [`Outbounds::renew`] shuts down every registry published since the previous generation ended
//! and publishes new runtimes for the same nodes.

use crate::api::lock;
use crate::catalog::NodeEntry;
use crate::dto::ErrorCode;
use honk_config::node::Node;
use honk_outbound::proxy::ProxyRegistry;
use honk_outbound::runtime::OutboundRuntimeRegistry;
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex, RwLock};
use tokio::runtime::Handle;

pub struct Outbounds {
    proxies: Arc<ProxyRegistry>,
    runtime: Handle,
    table: RwLock<Arc<Table>>,
    /// Every registry published since the last [`Outbounds::renew`], the current one last. A
    /// retired one still serves the streams that use its runtimes; it is shut down with the
    /// others when the generation ends.
    published: Mutex<Vec<Arc<OutboundRuntimeRegistry>>>,
}

impl std::fmt::Debug for Outbounds {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Outbounds").finish_non_exhaustive()
    }
}

#[derive(Default)]
struct Table {
    registry: Option<Arc<OutboundRuntimeRegistry>>,
    /// Group id to the node it sends traffic to, nested groups followed.
    selected: HashMap<String, Arc<Node>>,
}

/// What a flow needs to go through one group: its current node, and the registry and handlers
/// that dial it (`ProxyRegistry::dial_runtime` with `node.id`).
pub struct Dialer {
    pub node: Arc<Node>,
    pub registry: Arc<OutboundRuntimeRegistry>,
    pub proxies: Arc<ProxyRegistry>,
}

impl Dialer {
    /// Whether the node carries UDP (`dial_udp_transport_runtime`).
    pub fn supports_udp(&self) -> bool {
        (honk_outbound::descriptor::descriptor(self.node.protocol()).supports_udp)(&self.node)
    }
}

/// A registry built for a new node set, not yet published.
#[derive(Default)]
pub(crate) struct Built {
    registry: Option<Arc<OutboundRuntimeRegistry>>,
    reused: HashSet<uuid::Uuid>,
    /// The registry whose runtimes it reuses, and its nodes: a renew before it is published shuts
    /// that registry down, and the set is then built again with new runtimes.
    base: Option<Arc<OutboundRuntimeRegistry>>,
    nodes: Vec<Node>,
}

impl Outbounds {
    pub(crate) fn new(runtime: Handle) -> Result<Outbounds, ErrorCode> {
        let proxies = ProxyRegistry::default_resolver().map_err(|_| ErrorCode::Internal)?;
        Ok(Outbounds {
            proxies: Arc::new(proxies),
            runtime,
            table: RwLock::new(Arc::default()),
            published: Mutex::new(Vec::new()),
        })
    }

    /// The node `group` (an id such as `g-Proxy`) sends traffic to now, and what dials it;
    /// `None` while the group does not exist or has no node.
    pub fn dialer(&self, group: &str) -> Option<Dialer> {
        let table = self.table();
        Some(Dialer {
            node: Arc::clone(table.selected.get(group)?),
            registry: Arc::clone(table.registry.as_ref()?),
            proxies: Arc::clone(&self.proxies),
        })
    }

    pub(crate) fn registry(&self) -> Option<Arc<OutboundRuntimeRegistry>> {
        self.table().registry.clone()
    }

    pub(crate) fn proxies(&self) -> Arc<ProxyRegistry> {
        Arc::clone(&self.proxies)
    }

    /// A registry for `nodes`, reusing the published one's runtimes. Nodes of several
    /// subscriptions with one endpoint identity share a runtime.
    pub(crate) fn build(&self, nodes: &[Arc<NodeEntry>]) -> Built {
        let mut seen = HashSet::new();
        let nodes: Vec<Node> = nodes
            .iter()
            .filter(|n| seen.insert(n.node.id))
            .map(|n| n.node.as_ref().clone())
            .collect();
        let base = self.registry();
        let (registry, reused) = self.registry_for(&nodes, base.as_deref());
        Built {
            registry,
            reused,
            base,
            nodes,
        }
    }

    /// A registry for `nodes`, reusing `previous`'s runtimes where the node is the same; none for
    /// no nodes.
    fn registry_for(
        &self,
        nodes: &[Node],
        previous: Option<&OutboundRuntimeRegistry>,
    ) -> (Option<Arc<OutboundRuntimeRegistry>>, HashSet<uuid::Uuid>) {
        if nodes.is_empty() {
            return (None, HashSet::new());
        }
        let dials = honk_config::config::GlobalConfig::default().max_concurrent_dials;
        let _runtime = self.runtime.enter();
        match OutboundRuntimeRegistry::build_reusing(nodes, dials, previous) {
            Ok((registry, reused)) => (Some(Arc::new(registry)), reused),
            Err(_) => {
                // The parser admits only nodes the registry takes, so this is not expected.
                log::error!("outbounds: registry refused a set of {} nodes", nodes.len());
                (None, HashSet::new())
            }
        }
    }

    /// Publishes `built` and each group's node for its node set in one swap; the previous
    /// registry hands over the runtimes it shares and retires.
    pub(crate) fn replace(&self, built: Built, selected: HashMap<String, Arc<Node>>) {
        let (old, reused) = {
            let mut table = self.table.write().unwrap_or_else(|e| e.into_inner());
            let renewed = !same(built.base.as_ref(), table.registry.as_ref());
            let (registry, reused) = if renewed {
                self.registry_for(&built.nodes, None)
            } else {
                (built.registry, built.reused)
            };
            if let Some(r) = &registry {
                lock(&self.published).push(Arc::clone(r));
            }
            let old = table.registry.clone();
            *table = Arc::new(Table { registry, selected });
            (old, reused)
        };
        if let Some(old) = old {
            old.mark_moved_out(reused);
            self.runtime
                .spawn(async move { old.retire_reusable_state().await });
        }
    }

    /// Publishes each group's current node.
    pub(crate) fn select(&self, selected: HashMap<String, Arc<Node>>) {
        let mut table = self.table.write().unwrap_or_else(|e| e.into_inner());
        *table = Arc::new(Table {
            registry: table.registry.clone(),
            selected,
        });
    }

    /// Ends the runtimes the ending generation dialed through: every registry published since the
    /// last renew refuses new dials at once, and the returned future shuts them down, carriers
    /// and honk's tasks included. New runtimes for the same nodes take the current one's place.
    pub(crate) fn renew(&self) -> impl Future<Output = ()> + Send + 'static {
        let ended = {
            let mut table = self.table.write().unwrap_or_else(|e| e.into_inner());
            let nodes: Vec<Node> = table
                .registry
                .iter()
                .flat_map(|r| r.values())
                .map(|runtime| runtime.node.as_ref().clone())
                .collect();
            let (registry, _) = self.registry_for(&nodes, None);
            let fresh = registry.iter().cloned().collect();
            let ended = std::mem::replace(&mut *lock(&self.published), fresh);
            for r in &ended {
                r.begin_retirement();
            }
            *table = Arc::new(Table {
                registry,
                selected: table.selected.clone(),
            });
            ended
        };
        async move {
            futures_util::future::join_all(ended.iter().map(|r| r.shutdown())).await;
        }
    }

    fn table(&self) -> Arc<Table> {
        Arc::clone(&self.table.read().unwrap_or_else(|e| e.into_inner()))
    }
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
