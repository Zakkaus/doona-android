//! What new flows are routed by, as [`Outbounds`] publishes it: the mode, each group's node and
//! the policy that chooses a group, swapped in one step and read at once.

use super::{Dialer, Outbounds, Published};
use crate::dto::Mode;
use crate::rules::policy::Policy;
use honk_config::node::Node;
use std::collections::HashMap;
use std::sync::Arc;

#[derive(Default)]
pub(super) struct Table {
    pub(super) published: Option<Published>,
    /// Group id to the node it sends traffic to, nested groups followed.
    pub(super) selected: HashMap<Arc<str>, Arc<Node>>,
    /// What chooses a new flow's group.
    pub(super) policy: Arc<Policy>,
    /// The mode it was published with; `None` before the first publication.
    pub(super) mode: Option<Mode>,
}

impl Table {
    pub(super) fn new(published: Option<Published>, routing: Routing) -> Table {
        let selected = routing.selected.into_iter();
        Table {
            published,
            selected: selected.map(|(group, node)| (group.into(), node)).collect(),
            policy: routing.policy,
            mode: Some(routing.mode),
        }
    }
}

/// What new flows are routed by: the committed mode, each group's node, nested groups followed,
/// and the policy that chooses a group. Published in one step, so a flow never pairs a rule with
/// the groups of another node set, nor one commit's mode with another's policy.
pub(crate) struct Routing {
    pub selected: HashMap<String, Arc<Node>>,
    pub policy: Arc<Policy>,
    pub mode: Mode,
}

/// One publication of what new flows are routed by, read at once.
pub(crate) struct View<'a> {
    pub(super) outbounds: &'a Outbounds,
    pub(super) table: Arc<Table>,
}

impl View<'_> {
    pub fn policy(&self) -> &Policy {
        &self.table.policy
    }

    /// The mode published with the policy; `None` before the first publication.
    pub fn mode(&self) -> Option<Mode> {
        self.table.mode
    }

    /// The node `group` (an id such as `g-Proxy`) sends traffic to, and what dials it; `None`
    /// while the group does not exist or has no node.
    pub fn dialer(&self, group: &str) -> Option<Dialer> {
        let published = self.table.published.as_ref()?;
        let (group, node) = self.table.selected.get_key_value(group)?;
        Some(Dialer {
            group: Arc::clone(group),
            rule: None,
            node: Arc::clone(node),
            registry: Arc::clone(published.registries.of(node)?),
            proxies: Arc::clone(&self.outbounds.proxies),
            carriers: Arc::clone(&published.carriers),
        })
    }
}
