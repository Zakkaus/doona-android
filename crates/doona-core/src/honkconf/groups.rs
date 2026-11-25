//! Groups and nodes for the catalog, projected from native honk configuration plus the nodes
//! each declared provider last delivered. Membership, nesting, cycle cuts and selection are
//! honk's own: the static and fetched inventory is assembled as honk-core assembles it at
//! startup and handed to honk's `GroupManager`. Nothing here names a group; `Proxy` is an
//! ordinary name. The node list is the inventory itself, so a node in no group still appears.

use std::collections::HashMap;

use honk_config::Config;
use honk_config::config::{BLOCK_NODE_ID, DIRECT_NODE_ID};
use honk_config::error::DetailedConfigError;
use honk_config::group::{Group, GroupPolicy};
use honk_config::node::Node;
use honk_config::parser::cursor::Document;
use honk_config::parser::lexer::Source;
use honk_outbound::group::{
    GroupManager, GroupMember, SelectionNetwork, SelectorMember, SelectorNetworks,
};
use uuid::Uuid;

use super::declarations::location;
use super::{HonkConfig, Location};
use crate::ids;

/// Nodes one declared `subscription` delivered, in feed order.
#[derive(Debug, Clone)]
pub struct Fetched {
    /// The declared subscription name, which `subtag(...)` filters match.
    pub subscription: String,
    /// The sidecar's stable provider ID (`s1`, …), which scopes the node IDs.
    pub scope: String,
    pub nodes: Vec<Node>,
}

/// Where a listed node is declared.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Origin {
    /// A `node` declaration in the configuration.
    Static,
    /// Fetched for the provider with this sidecar ID.
    Subscription(String),
}

/// One leaf of the inventory, whether or not a group holds it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NodeRow {
    /// `n-` and 16 hex digits (`crate::ids`).
    pub id: String,
    /// The node's identity inside the [`GroupManager`].
    pub uuid: Uuid,
    pub name: String,
    pub origin: Origin,
}

/// honk's group policy under its contract name.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Selector,
    UrlTest,
    LoadBalance,
    Fallback,
    Score,
}

impl Kind {
    fn of(policy: GroupPolicy) -> Self {
        match policy {
            GroupPolicy::Selector => Self::Selector,
            GroupPolicy::URLTest => Self::UrlTest,
            GroupPolicy::LoadBalance => Self::LoadBalance,
            GroupPolicy::Fallback => Self::Fallback,
            GroupPolicy::Score => Self::Score,
        }
    }

    /// The wire name (core-api schema 4).
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Selector => "Selector",
            Self::UrlTest => "UrlTest",
            Self::LoadBalance => "LoadBalance",
            Self::Fallback => "Fallback",
            Self::Score => "Score",
        }
    }
}

/// A direct member of a group, by catalog ID.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Member {
    Node(String),
    Group(String),
    /// honk's built-in `direct` or `block`, admitted only by a literal `name(direct)`.
    Builtin(&'static str),
}

impl Member {
    pub fn id(&self) -> &str {
        match self {
            Self::Node(id) | Self::Group(id) => id,
            Self::Builtin(name) => name,
        }
    }
}

/// One effective group.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GroupRow {
    /// `g-` and the authored name.
    pub id: String,
    pub name: String,
    pub kind: Kind,
    /// Direct members in honk's order: nodes, then subgroups. Cut cycle edges are absent.
    pub members: Vec<Member>,
    /// The member `default` names, if it names one.
    pub default: Option<String>,
    /// `direct`, `block`, a group ID or a node ID.
    pub final_target: Option<String>,
    /// The node appended to the selected leaf's hops.
    pub exit: Option<String>,
    /// The effective (last) declaration.
    pub location: Option<Location>,
    /// No filter and no subgroup: honk adds every proxy node, never a built-in.
    pub implicit_all: bool,
}

impl GroupRow {
    /// The name as the UI shows it. The ID keeps honk's raw name, quotes included, because
    /// references spell it that way (owner ruling Q-B2, 2026-11-26).
    pub fn label(&self) -> &str {
        unquote_once(&self.name)
    }
}

/// Remove one pair of matching surrounding quotes, `'` or `"`.
fn unquote_once(name: &str) -> &str {
    ['\'', '"']
        .into_iter()
        .find_map(|q| name.strip_prefix(q)?.strip_suffix(q))
        .unwrap_or(name)
}

/// A stored choice: a Selector's runtime choice or an automatic group's override.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Pin {
    pub group: String,
    pub member: String,
}

/// Why a pin was refused.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PinError {
    UnknownGroup,
    NotMember,
    /// A Selector always has a choice; only automatic groups return to their policy.
    SelectorClear,
}

/// The catalog's groups and nodes over one assembled inventory.
pub struct Projection {
    pub nodes: Vec<NodeRow>,
    pub groups: Vec<GroupRow>,
    /// Stored pins whose group or member is gone; the sidecar drops them.
    pub pruned: Vec<Pin>,
    manager: GroupManager,
    /// Manager identity of every member, built-ins included.
    members: HashMap<String, SelectorMember>,
    ids: HashMap<Uuid, String>,
}

impl Projection {
    /// Assemble `config` with `fetched` as honk-core does at startup, then apply `pins`. Nodes
    /// of a provider the configuration no longer declares are not admitted, and an endpoint
    /// already present (a static node or an earlier provider's) is kept once, first wins.
    pub fn build(
        config: &HonkConfig,
        fetched: Vec<Fetched>,
        pins: &[Pin],
    ) -> Result<Self, DetailedConfigError> {
        let (assembled, origins) = assemble(config.config(), fetched)?;
        let manager = GroupManager::new(&assembled.groups, &assembled.nodes);
        let mut ids = HashMap::new();
        let mut nodes = Vec::new();
        for (node, origin) in assembled.nodes.iter().zip(origins) {
            let id = match &origin {
                Some(Origin::Static) => ids::short_id(ids::static_uuid(node)),
                Some(Origin::Subscription(scope)) => {
                    ids::short_id(ids::scoped_uuid(scope, node.id))
                }
                None => node.name.clone(),
            };
            ids.insert(node.id, id.clone());
            if let Some(origin) = origin {
                nodes.push(NodeRow {
                    id,
                    uuid: node.id,
                    name: node.name.clone(),
                    origin,
                });
            }
        }
        let mut projection = Self {
            nodes,
            groups: Vec::new(),
            pruned: Vec::new(),
            members: HashMap::new(),
            ids,
            manager,
        };
        let located = locations(config);
        for group in effective(&assembled.groups) {
            let row = projection.row(group, located.get(group.name.as_str()).cloned());
            projection.groups.push(row);
        }
        for pin in pins {
            if projection.pin(&pin.group, Some(&pin.member)).is_err() {
                projection.pruned.push(pin.clone());
            }
        }
        Ok(projection)
    }

    /// honk's runtime over this inventory.
    pub fn manager(&self) -> &GroupManager {
        &self.manager
    }

    /// Choose `member` for both networks; `None` returns an automatic group to its policy.
    pub fn pin(&self, group: &str, member: Option<&str>) -> Result<(), PinError> {
        let row = self
            .groups
            .iter()
            .find(|g| g.id == group)
            .ok_or(PinError::UnknownGroup)?;
        let target = match member {
            Some(m) if row.members.iter().any(|x| x.id() == m) => Some(&self.members[m]),
            Some(_) => return Err(PinError::NotMember),
            None => None,
        };
        let manager = &self.manager;
        let networks = SelectorNetworks::Both;
        match (row.kind, target) {
            (Kind::Selector, None) => return Err(PinError::SelectorClear),
            (Kind::Selector, Some(m)) => manager.publish_selector_choice(&row.name, m, networks),
            (_, Some(m)) => manager.publish_override(&row.name, m, networks),
            (_, None) => manager.clear_override(&row.name, networks),
        }
        .expect("a direct member of a managed group")
        .run_callbacks();
        Ok(())
    }

    /// The member a TCP flow through `group` takes now, if the policy has one: a Selector's
    /// choice, `default` or first member; an automatic group's pin or its last result.
    pub fn selected(&self, group: &str) -> Option<&str> {
        let name = group.strip_prefix("g-")?;
        let selection = self.manager.peek_selection(name, SelectionNetwork::Tcp)?;
        Some(self.member_id(selection.member))
    }

    fn member_id(&self, member: GroupMember<'_>) -> &str {
        match member {
            GroupMember::Node(node) => &self.ids[&node.id],
            GroupMember::Group(group) => {
                &self
                    .groups
                    .iter()
                    .find(|g| g.name == group.name)
                    .unwrap()
                    .id
            }
        }
    }

    fn row(&mut self, group: &Group, location: Option<Location>) -> GroupRow {
        let mut members = Vec::new();
        let mut default = None;
        for member in self.manager.group_members(&group.name) {
            let (entry, tag, identity) = match member {
                GroupMember::Node(node) => (
                    self.leaf(node),
                    node.name.as_str(),
                    SelectorMember::Node(node.id),
                ),
                GroupMember::Group(child) => (
                    Member::Group(ids::group_id(&child.name)),
                    child.name.as_str(),
                    SelectorMember::Group(child.name.clone()),
                ),
            };
            if default.is_none() && group.default.as_deref() == Some(tag) {
                default = Some(entry.id().to_owned());
            }
            self.members.insert(entry.id().to_owned(), identity);
            members.push(entry);
        }
        GroupRow {
            id: ids::group_id(&group.name),
            name: group.name.clone(),
            kind: Kind::of(group.policy),
            members,
            default,
            final_target: group.final_outbound.as_deref().and_then(|t| self.target(t)),
            exit: group
                .exit
                .as_deref()
                .and_then(|n| self.manager.node_by_name(n))
                .map(|n| self.ids[&n.id].clone()),
            location,
            implicit_all: group.own.implicit_all_nodes,
        }
    }

    fn leaf(&self, node: &Node) -> Member {
        match builtin(node) {
            Some(name) => Member::Builtin(name),
            None => Member::Node(self.ids[&node.id].clone()),
        }
    }

    /// honk's `final` lookup: a built-in, then a node by name, then a group.
    fn target(&self, name: &str) -> Option<String> {
        if matches!(
            name,
            Config::BUILTIN_DIRECT_NODE | Config::BUILTIN_BLOCK_NODE
        ) {
            return Some(name.to_owned());
        }
        match self.manager.node_by_name(name) {
            Some(node) => Some(self.ids[&node.id].clone()),
            None => self
                .manager
                .group(name)
                .map(|group| ids::group_id(&group.name)),
        }
    }
}

/// honk's injected `direct` or `block` node, by identity.
fn builtin(node: &Node) -> Option<&'static str> {
    match node.id {
        DIRECT_NODE_ID => Some(Config::BUILTIN_DIRECT_NODE),
        BLOCK_NODE_ID => Some(Config::BUILTIN_BLOCK_NODE),
        _ => None,
    }
}

/// The assembled candidate and, per node, where it comes from (`None` for a built-in).
fn assemble(
    config: &Config,
    fetched: Vec<Fetched>,
) -> Result<(Config, Vec<Option<Origin>>), DetailedConfigError> {
    let mut candidate = config.clone();
    candidate.ensure_builtin_nodes();
    let mut origins: Vec<_> = candidate
        .nodes
        .iter()
        .map(|n| builtin(n).is_none().then_some(Origin::Static))
        .collect();
    for provider in fetched {
        let Some(declared) = config
            .subscriptions
            .iter()
            .find(|s| s.name == provider.subscription)
        else {
            continue;
        };
        for mut node in provider.nodes {
            // honk IDs are canonical endpoint identities and must be unique in one snapshot.
            if candidate.nodes.iter().any(|n| n.id == node.id) {
                continue;
            }
            node.subscription_id = Some(declared.id);
            candidate.nodes.push(node);
            origins.push(Some(Origin::Subscription(provider.scope.clone())));
        }
    }
    let assembled = candidate.assemble(honk_config::parser::resolve_group_filters)?;
    Ok((assembled, origins))
}

/// honk's runtime keeps the last definition of a repeated name, at its own position.
fn effective(groups: &[Group]) -> impl Iterator<Item = &Group> {
    groups
        .iter()
        .enumerate()
        .filter(|(i, g)| !groups[i + 1..].iter().any(|later| later.name == g.name))
        .map(|(_, g)| g)
}

/// The last `group` block declaring each name, across the entry and its includes.
fn locations(config: &HonkConfig) -> HashMap<String, Location> {
    let mut found = HashMap::new();
    for (index, source) in config.sources().iter().enumerate() {
        let text = Source::new(&source.content, source.source.clone());
        let doc = Document::parse(text, &mut Vec::new()).expect("an admitted source parses");
        for section in doc.sections().filter(|s| s.header() == "group") {
            for block in section.body().into_iter().flatten() {
                if block.body().is_some() {
                    let name = block.header().trim().to_owned();
                    found.insert(name, location(&doc, block.header_span(), index));
                }
            }
        }
    }
    found
}

#[cfg(test)]
mod tests;
