//! The catalog behind `snapshot`: what `state.json` holds, the nodes parsed from it and the group
//! template over them. It changes only under the core's catalog lock, in sections that never
//! wait on I/O.

use crate::dto::{self, Catalog, Member};
use crate::groups::{self, GroupDef};
use crate::ids::{self, is_group};
use crate::state::{State, StoredSubscription};
use crate::subscription::parse;
use honk_config::node::Node;
use std::collections::{BTreeMap, HashMap};
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use std::sync::{Arc, Mutex};

/// Incarnations are unique in the process: a re-created subscription never matches old work.
static NEXT_INCARNATION: AtomicU64 = AtomicU64::new(1);

pub(crate) fn incarnation() -> u64 {
    NEXT_INCARNATION.fetch_add(1, Relaxed)
}

/// One node of one subscription.
#[derive(Debug)]
pub(crate) struct NodeEntry {
    pub id: String,
    pub name: String,
    pub subscription: String,
}

/// Entries for the nodes `subscription` holds, in feed order.
pub(crate) fn entries(subscription: &str, nodes: Vec<Node>) -> Vec<Arc<NodeEntry>> {
    nodes
        .into_iter()
        .map(|node| {
            let name = if node.name.trim().is_empty() {
                node.protocol().as_str().to_owned()
            } else {
                node.name.clone()
            };
            Arc::new(NodeEntry {
                id: ids::node_id(subscription, node.id),
                name,
                subscription: subscription.to_owned(),
            })
        })
        .collect()
}

/// The nodes `state.json` lists, each subscription with a new incarnation.
pub(crate) fn load(state: &State) -> Model {
    let mut nodes = Vec::new();
    let mut incarnations = HashMap::new();
    for s in &state.subscriptions {
        let parsed = parse::admit(s.links.iter().map(String::as_str));
        nodes.extend(entries(&s.id, parsed.nodes));
        incarnations.insert(s.id.clone(), incarnation());
    }
    Model::new(nodes, incarnations)
}

/// What the core shares with the tasks it spawns.
pub(crate) struct Shared {
    /// What `snapshot` reads; held only to copy or assign, never across I/O.
    pub catalog: Mutex<CatalogState>,
}

pub(crate) struct CatalogState {
    /// What `state.json` holds; changed only after the file is written.
    pub state: State,
    /// Rises with every change Kotlin must re-read; starts above Kotlin's initial -1 and 0.
    pub revision: i64,
    pub model: Model,
}

impl CatalogState {
    pub fn new(state: State, model: Model) -> CatalogState {
        CatalogState {
            state,
            revision: 1,
            model,
        }
    }
}

/// The nodes of every subscription and the groups over them.
#[derive(Default)]
pub(crate) struct Model {
    /// Every subscription's nodes, in subscription then feed order.
    pub nodes: Vec<Arc<NodeEntry>>,
    index: HashMap<String, usize>,
    /// The incarnation each subscription's nodes belong to; a refresh gives a new one.
    pub incarnations: HashMap<String, u64>,
    pub groups: Vec<GroupDef>,
}

impl Model {
    pub fn new(nodes: Vec<Arc<NodeEntry>>, incarnations: HashMap<String, u64>) -> Model {
        let index = nodes
            .iter()
            .enumerate()
            .map(|(i, n)| (n.id.clone(), i))
            .collect();
        let named: Vec<(&str, &str)> = nodes
            .iter()
            .map(|n| (n.id.as_str(), n.name.as_str()))
            .collect();
        let groups = groups::template(&named);
        Model {
            nodes,
            index,
            incarnations,
            groups,
        }
    }

    /// The model after `subscription` gets `fresh` nodes (a new incarnation), or loses its nodes
    /// when it is no longer in `state`. The other subscriptions keep theirs.
    pub fn with(&self, state: &State, subscription: &str, fresh: Vec<Arc<NodeEntry>>) -> Model {
        let mut fresh = Some(fresh);
        let mut nodes = Vec::new();
        let mut incarnations = HashMap::new();
        for s in &state.subscriptions {
            if s.id == subscription {
                nodes.extend(fresh.take().unwrap_or_default());
                incarnations.insert(s.id.clone(), incarnation());
            } else {
                nodes.extend(
                    self.nodes
                        .iter()
                        .filter(|n| n.subscription == s.id)
                        .cloned(),
                );
                if let Some(&i) = self.incarnations.get(&s.id) {
                    incarnations.insert(s.id.clone(), i);
                }
            }
        }
        Model::new(nodes, incarnations)
    }

    pub fn group(&self, id: &str) -> Option<&GroupDef> {
        self.groups.iter().find(|g| g.id == id)
    }

    pub fn nodes_of(&self, subscription: &str) -> usize {
        self.nodes
            .iter()
            .filter(|n| n.subscription == subscription)
            .count()
    }

    pub fn catalog(&self, state: &State) -> Catalog {
        Catalog {
            mode: state.mode,
            groups: self
                .groups
                .iter()
                .map(|g| self.group_dto(g, &state.selections))
                .collect(),
            subscriptions: state
                .subscriptions
                .iter()
                .map(|s| self.subscription(s))
                .collect(),
        }
    }

    pub fn subscription(&self, s: &StoredSubscription) -> dto::Subscription {
        dto::Subscription {
            id: s.id.clone(),
            name: s.name.clone(),
            url: s.url.clone(),
            nodes: u32::try_from(self.nodes_of(&s.id)).unwrap_or(u32::MAX),
            updated_at_epoch_ms: s.updated_at_epoch_ms,
            error: s.error,
            used_bytes: s.used_bytes,
            total_bytes: s.total_bytes,
            expires_at_epoch_ms: s.expires_at_epoch_ms,
        }
    }

    fn group_dto(&self, g: &GroupDef, selections: &BTreeMap<String, String>) -> dto::Group {
        let (selected, pinned) = groups::selected(g, selections.get(&g.id), None);
        dto::Group {
            id: g.id.clone(),
            name: g.name.clone(),
            kind: g.kind,
            selected_id: selected.map(str::to_owned),
            pinned,
            testing: false,
            members: g.members.iter().map(|m| self.member(m)).collect(),
        }
    }

    fn member(&self, id: &str) -> Member {
        let mut member = Member {
            id: id.to_owned(),
            name: String::new(),
            latency_ms: None,
            failed: false,
            is_group: is_group(id),
            subscription_id: None,
        };
        if member.is_group {
            member.name = self.group(id).map(|g| g.name.clone()).unwrap_or_default();
        } else if let Some(&i) = self.index.get(id) {
            let entry = &self.nodes[i];
            member.name = entry.name.clone();
            member.subscription_id = Some(entry.subscription.clone());
        }
        member
    }
}

#[cfg(test)]
mod tests;
