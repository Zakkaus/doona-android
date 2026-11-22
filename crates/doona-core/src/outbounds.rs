//! The hand-off from the catalog to the data path: for each group, the node it sends traffic to
//! now, with honk's runtime registry that dials it and the protocol handlers. The catalog
//! publishes a new table whenever a selection, a latency or the node set changes; a new flow
//! reads one with [`Outbounds::dialer`] and keeps what it read.
//!
//! The registry is rebuilt when the node set changes, reusing the runtimes of the nodes that
//! stay; the previous one then retires the rest without cutting streams that use them.

use crate::catalog::NodeEntry;
use crate::dto::ErrorCode;
use honk_config::node::Node;
use honk_outbound::bootstrap::{NodeHostLookup, NodeHostResolver};
use honk_outbound::proxy::ProxyRegistry;
use honk_outbound::runtime::OutboundRuntimeRegistry;
use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::{Arc, Once, RwLock};
use tokio::runtime::Handle;

pub struct Outbounds {
    proxies: Arc<ProxyRegistry>,
    runtime: Handle,
    table: RwLock<Arc<Table>>,
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
}

impl Outbounds {
    pub(crate) fn new(runtime: Handle) -> Result<Outbounds, ErrorCode> {
        let proxies = ProxyRegistry::default_resolver().map_err(|_| ErrorCode::Internal)?;
        Ok(Outbounds {
            proxies: Arc::new(proxies),
            runtime,
            table: RwLock::new(Arc::default()),
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
        let unique: Vec<Node> = nodes
            .iter()
            .filter(|n| seen.insert(n.node.id))
            .map(|n| n.node.as_ref().clone())
            .collect();
        if unique.is_empty() {
            return Built::default();
        }
        let previous = self.registry();
        let dials = honk_config::config::GlobalConfig::default().max_concurrent_dials;
        let _runtime = self.runtime.enter();
        match OutboundRuntimeRegistry::build_reusing(&unique, dials, previous.as_deref()) {
            Ok((registry, reused)) => Built {
                registry: Some(Arc::new(registry)),
                reused,
            },
            Err(_) => {
                // The parser admits only nodes the registry takes, so this is not expected.
                log::error!(
                    "outbounds: registry refused a set of {} nodes",
                    unique.len()
                );
                Built::default()
            }
        }
    }

    /// Publishes `built`; the previous registry hands over the runtimes it shares and retires.
    pub(crate) fn replace(&self, built: Built) {
        let old = {
            let mut table = self.table.write().unwrap_or_else(|e| e.into_inner());
            let old = table.registry.clone();
            *table = Arc::new(Table {
                registry: built.registry,
                selected: table.selected.clone(),
            });
            old
        };
        if let Some(old) = old {
            old.mark_moved_out(built.reused);
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

    fn table(&self) -> Arc<Table> {
        Arc::clone(&self.table.read().unwrap_or_else(|e| e.into_inner()))
    }
}

/// Makes honk resolve proxy-server hosts and probe URL hosts with the platform resolver
/// (`getaddrinfo`, which on Android asks the system resolver for this app's network), never
/// `/etc/resolv.conf`. honk keeps these hooks per process, so they are installed once.
pub(crate) fn install_resolvers() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        honk_outbound::bootstrap::set_node_host_resolver(Some(Arc::new(Platform)));
        honk_outbound::urltest::set_urltest_resolver(Arc::new(resolve_probe_host));
    });
}

struct Platform;

impl NodeHostResolver for Platform {
    fn lookup(&self, host: &str) -> Option<NodeHostLookup> {
        let host = host.to_owned();
        let lookup: NodeHostLookup = Box::pin(async move {
            let addrs = tokio::net::lookup_host((host.as_str(), 0)).await?;
            Ok(addrs.map(|a| a.ip()).collect())
        });
        Some(lookup)
    }
}

type Resolved = Pin<Box<dyn Future<Output = anyhow::Result<Vec<SocketAddr>>> + Send>>;

fn resolve_probe_host(host: String, port: u16) -> Resolved {
    Box::pin(async move {
        let addrs = tokio::net::lookup_host((host.as_str(), port)).await?;
        Ok(addrs.collect())
    })
}
