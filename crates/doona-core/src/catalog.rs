//! The catalog behind `snapshot`: what `state.json` holds, the nodes parsed from it, the group
//! template over them, and what lives only as long as the process: latencies, the automatic
//! choices of UrlTest groups, and which groups are testing. It changes only under the core's
//! catalog lock, in sections that never wait on I/O.

use crate::diag::{Diag, Event};
use crate::dto::{self, Catalog, ErrorCode, GroupKind, Member, RuleSourceSpec};
use crate::groups::{self, GroupDef};
use crate::ids::{self, is_group};
use crate::outbounds::{Built, Outbounds};
use crate::rules::policy::Policy;
use crate::rules::template;
use crate::score::Score;
use crate::state::{State, StoredSubscription};
use crate::stats::{Clock, Stats};
use crate::subscription::parse;
use honk_config::node::Node;
use honk_outbound::group::SelectionNetwork;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use std::sync::{Arc, Mutex};
use tokio_util::sync::CancellationToken;

/// The most nodes of all subscriptions together, on top of
/// [`parse::MAX_NODES_PER_SUBSCRIPTION`]: a subscription that would take the catalog over it is
/// `parse_failed`.
pub(crate) const MAX_NODES: usize = 5_000;

/// Nested groups are followed this deep; the template nests one level.
pub(crate) const MAX_DEPTH: u8 = 4;

/// Incarnations are unique in the process: a re-created subscription never matches old work.
static NEXT_INCARNATION: AtomicU64 = AtomicU64::new(1);

pub(crate) fn incarnation() -> u64 {
    NEXT_INCARNATION.fetch_add(1, Relaxed)
}

/// A catalog leaf, either authored locally or owned by one subscription.
#[derive(Debug)]
pub(crate) struct NodeEntry {
    pub id: String,
    pub uuid: uuid::Uuid,
    pub name: String,
    pub location: dto::location::Location,
    pub subscription: Option<String>,
    pub node: Arc<Node>,
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
            let uuid = ids::scoped_uuid(subscription, node.id);
            Arc::new(NodeEntry {
                id: ids::short_id(uuid),
                uuid,
                location: crate::regions::locate(&name),
                name,
                subscription: Some(subscription.to_owned()),
                node: Arc::new(node),
            })
        })
        .collect()
}

/// The nodes `state.json` lists, each subscription with a new incarnation. Only a file edited
/// by hand holds a subscription over a node budget, or a document that gives no node; that one is
/// shown without nodes.
pub(crate) fn load(state: &State, diag: &Diag) -> Model {
    let mut nodes = Vec::new();
    let mut incarnations = HashMap::new();
    for s in &state.subscriptions {
        let parsed = stored_nodes(s).and_then(|p| within_budget(nodes.len(), &p).map(|()| p));
        match parsed {
            Ok(parsed) => nodes.extend(entries(&s.id, parsed)),
            Err(code) => diag.record(Event::warn("loadSubscription").code(code)),
        }
        incarnations.insert(s.id.clone(), incarnation());
    }
    for stored in &state.static_nodes {
        match crate::api::static_nodes::stored_entry(stored) {
            Ok(entry) if nodes.len() < MAX_NODES => nodes.push(entry),
            _ => diag.record(Event::warn("loadStaticNode").code(ErrorCode::ParseFailed)),
        }
    }
    Model::sourced(nodes, incarnations, &state.rule_source, None)
}

/// The nodes `state.json` keeps for `s`, parsed again: from its structured document when it has
/// one, else from its share links.
pub(crate) fn stored_nodes(s: &StoredSubscription) -> Result<Vec<Node>, ErrorCode> {
    let parsed = match &s.document {
        Some(document) => parse::parse(document.as_bytes()),
        None => parse::admit(s.links.iter().map(String::as_str)),
    };
    parsed.map(|p| p.nodes)
}

/// `parse_failed` when `fresh` nodes and `others` already kept are more than [`MAX_NODES`].
pub(crate) fn within_budget(others: usize, fresh: &[Node]) -> Result<(), ErrorCode> {
    if others + fresh.len() > MAX_NODES {
        return Err(ErrorCode::ParseFailed);
    }
    Ok(())
}

/// A node's last probe result, and the probe whose result it waits for.
#[derive(Clone, Copy, Debug, Default)]
struct Latency {
    ms: Option<u32>,
    failed: bool,
    /// Why the failed probe failed, when the core knows (`local_network`).
    error: Option<ErrorCode>,
    generation: u64,
    /// A probe of `generation` is under way, so `ms` is from an earlier round.
    probing: bool,
}

/// What the core shares with the tasks it spawns.
pub(crate) struct Shared {
    /// What `snapshot` reads; held only to copy or assign, never across I/O.
    pub catalog: Mutex<CatalogState>,
    pub outbounds: Arc<Outbounds>,
    /// Where the probes record each node's latency tests.
    pub stats: Arc<Stats>,
    /// Where probe rounds and the data path's health are recorded.
    pub diag: Arc<Diag>,
}

impl Shared {
    /// Publishes the mode, each group's current node and the policy to the data path; called with
    /// `c` locked.
    pub fn publish(&self, c: &CatalogState) {
        let routing = c.model.routing(&c.state.selections, c.state.mode);
        self.outbounds.select(routing);
    }

    /// Publishes `built`, the registry for `c`'s node set, each group's current node and the
    /// policy in one step, so a new flow never pairs a node with a registry that lacks it, nor a
    /// rule with groups of another node set; `c` is locked.
    pub fn publish_nodes(&self, c: &CatalogState, built: Built) {
        c.model.restore_pins(&c.state.selections);
        self.outbounds
            .replace(built, c.model.routing(&c.state.selections, c.state.mode));
    }
}

pub(crate) struct CatalogState {
    /// What `state.json` holds; changed only after the file is written.
    pub state: State,
    /// Rises with every change Kotlin must re-read; starts above Kotlin's initial -1 and 0.
    pub revision: i64,
    pub model: Model,
    /// Cancelled when a tunnel generation ends; probes begun before then are dropped.
    pub probes: CancellationToken,
}

impl CatalogState {
    pub fn new(state: State, model: Model) -> CatalogState {
        CatalogState {
            state,
            revision: 1,
            model,
            probes: CancellationToken::new(),
        }
    }

    /// Cancels the probes under way; their results are dropped.
    pub fn end_probes(&mut self) {
        std::mem::replace(&mut self.probes, CancellationToken::new()).cancel();
    }
}

mod routing;

/// The nodes of every subscription and the groups over them.
pub(crate) struct Model {
    /// Every subscription's nodes, in subscription then feed order.
    pub nodes: Vec<Arc<NodeEntry>>,
    index: HashMap<String, usize>,
    /// The incarnation each subscription's nodes belong to; a refresh gives a new one.
    pub incarnations: HashMap<String, u64>,
    pub groups: Vec<GroupDef>,
    pub score: Arc<Score>,
    /// What chooses a new flow's group: the main group, and the rules of `state.json`'s source
    /// over `groups`.
    pub policy: Arc<Policy>,
    latency: HashMap<String, Latency>,
    /// The automatic choice of each UrlTest group that has had a successful probe.
    auto: HashMap<String, String>,
    /// How many latency tests of each group are under way.
    testing: HashMap<String, u32>,
    next_probe: u64,
}

/// What one latency test probes, and which groups show `testing` until it ends.
pub(crate) struct Plan {
    pub groups: Vec<String>,
    pub targets: Vec<Target>,
}

impl Plan {
    /// What [`Model::finish`] needs once the test ends.
    pub fn round(&self) -> Round {
        Round {
            groups: self.groups.clone(),
            probes: self
                .targets
                .iter()
                .map(|t| (t.entry.id.clone(), t.generation))
                .collect(),
        }
    }
}

/// A test's groups, and the node and generation of each of its probes.
pub(crate) struct Round {
    groups: Vec<String>,
    probes: Vec<(String, u64)>,
}

/// One probe: its result counts only while the node keeps this incarnation and generation.
#[derive(Clone)]
pub(crate) struct Target {
    pub entry: Arc<NodeEntry>,
    pub generation: u64,
    /// When the test round began, epoch ms: its time in the node's latency history.
    pub began_ms: i64,
}

impl Model {
    /// The groups over `nodes` under the template's default policy.
    #[cfg(test)]
    pub fn new(nodes: Vec<Arc<NodeEntry>>, incarnations: HashMap<String, u64>) -> Model {
        Model::sourced(nodes, incarnations, &RuleSourceSpec::default(), None)
    }

    /// The groups over `nodes`, and the policy of `source` over them.
    pub fn sourced(
        nodes: Vec<Arc<NodeEntry>>,
        incarnations: HashMap<String, u64>,
        source: &RuleSourceSpec,
        previous: Option<&Score>,
    ) -> Model {
        let index: HashMap<String, usize> = nodes
            .iter()
            .enumerate()
            .map(|(i, n)| (n.id.clone(), i))
            .collect();
        let named: Vec<(&str, &str)> = nodes
            .iter()
            .map(|n| (n.id.as_str(), n.name.as_str()))
            .collect();
        let groups = groups::template(&named);
        let policy = compile(&groups, &index, source);
        let score = Arc::new(Score::new(&groups, &nodes, &index, previous));
        Model {
            nodes,
            index,
            incarnations,
            groups,
            policy: Arc::new(policy),
            score,
            latency: HashMap::new(),
            auto: HashMap::new(),
            testing: HashMap::new(),
            next_probe: 0,
        }
    }

    /// The model after `subscription` gets `fresh` nodes (a new incarnation), or loses its nodes
    /// when it is no longer in `state`. The other subscriptions keep theirs.
    pub fn with(&self, state: &State, subscription: &str, fresh: Vec<Arc<NodeEntry>>) -> Model {
        let mut fresh = fresh.into_iter();
        let mut nodes = Vec::new();
        let mut incarnations = HashMap::new();
        for s in &state.subscriptions {
            if s.id == subscription {
                nodes.extend(fresh.by_ref());
                incarnations.insert(s.id.clone(), incarnation());
            } else {
                nodes.extend(
                    self.nodes
                        .iter()
                        .filter(|n| n.subscription.as_ref() == Some(&s.id))
                        .cloned(),
                );
                if let Some(&i) = self.incarnations.get(&s.id) {
                    incarnations.insert(s.id.clone(), i);
                }
            }
        }
        nodes.extend(
            self.nodes
                .iter()
                .filter(|n| n.subscription.is_none())
                .cloned(),
        );
        Model::sourced(nodes, incarnations, &state.rule_source, Some(&self.score))
    }

    pub fn with_static(&self, fresh: Vec<Arc<NodeEntry>>, state: &State) -> Model {
        let nodes = self
            .nodes
            .iter()
            .filter(|n| n.subscription.is_some())
            .cloned()
            .chain(fresh)
            .collect();
        Model::sourced(
            nodes,
            self.incarnations.clone(),
            &state.rule_source,
            Some(&self.score),
        )
    }

    /// Compiles `source` over the groups and nodes as they are, for new flows.
    pub fn set_source(&mut self, source: &RuleSourceSpec) {
        self.policy = Arc::new(compile(&self.groups, &self.index, source));
    }

    /// Whether node `id` is in the catalog.
    pub fn has(&self, id: &str) -> bool {
        self.index.contains_key(id)
    }

    /// Node `id`, if it is in the catalog.
    pub fn entry(&self, id: &str) -> Option<Arc<NodeEntry>> {
        self.index.get(id).map(|&i| Arc::clone(&self.nodes[i]))
    }

    /// Takes over what only lives at run time from `old`, for the nodes and groups still here.
    pub fn carry(&mut self, old: &Model) {
        self.latency = old
            .latency
            .iter()
            .filter(|(id, _)| self.index.contains_key(*id))
            .map(|(id, l)| (id.clone(), *l))
            .collect();
        // The members changed. A choice that is still a member stays; a group whose choice left
        // ranks its members by the results kept, including those of nodes being probed: the
        // refresh drops the probes of the nodes it re-creates, and `finish` never chooses.
        self.auto = old
            .auto
            .iter()
            .filter(|(g, m)| self.group(g).is_some_and(|g| g.members.contains(m)))
            .map(|(g, m)| (g.clone(), m.clone()))
            .collect();
        let latency = &self.latency;
        let kept = |m: &str| latency.get(m).and_then(|l| l.ms);
        let picks: Vec<(String, String)> = self
            .groups
            .iter()
            .filter(|g| g.kind == GroupKind::UrlTest && !self.auto.contains_key(&g.id))
            .filter_map(|g| Some((g.id.clone(), groups::fastest(g, kept)?.clone())))
            .collect();
        self.auto.extend(picks);
        // Every test under way ends with a decrement, whatever happened to its groups.
        self.testing = old.testing.clone();
        self.next_probe = old.next_probe;
    }

    pub fn group(&self, id: &str) -> Option<&GroupDef> {
        self.groups.iter().find(|g| g.id == id)
    }

    pub fn nodes_of(&self, subscription: &str) -> usize {
        self.nodes
            .iter()
            .filter(|n| n.subscription.as_deref() == Some(subscription))
            .count()
    }

    pub fn catalog(&self, state: &State) -> Catalog {
        Catalog {
            mode: state.mode,
            main_group_id: self.policy.main().to_owned(),
            rule_source: self.policy.source(),
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
            format: s.format,
            updated_at_epoch_ms: s.updated_at_epoch_ms,
            error: s.error,
            used_bytes: s.used_bytes,
            total_bytes: s.total_bytes,
            expires_at_epoch_ms: s.expires_at_epoch_ms,
            user_agent: s.user_agent.clone(),
        }
    }

    fn group_dto(&self, g: &GroupDef, selections: &BTreeMap<String, String>) -> dto::Group {
        let (selected, pinned) = if g.kind == GroupKind::Score {
            (
                self.score.selected(&g.id),
                self.score
                    .manager
                    .has_override(&g.id, SelectionNetwork::Tcp),
            )
        } else {
            groups::selected(g, selections.get(&g.id), self.auto.get(&g.id))
        };
        dto::Group {
            id: g.id.clone(),
            name: g.name.clone(),
            kind: g.kind,
            selected_id: selected.map(str::to_owned),
            pinned,
            testing: self.testing.contains_key(&g.id),
            members: g.members.iter().map(|m| self.member(m)).collect(),
        }
    }

    fn member(&self, id: &str) -> Member {
        let mut member = Member {
            id: id.to_owned(),
            name: String::new(),
            latency_ms: None,
            failed: false,
            error: None,
            is_group: is_group(id),
            subscription_id: None,
            location: Default::default(),
        };
        if member.is_group {
            member.name = self.group(id).map(|g| g.name.clone()).unwrap_or_default();
        } else if let Some(&i) = self.index.get(id) {
            let entry = &self.nodes[i];
            let latency = self.latency.get(id).copied().unwrap_or_default();
            member.name = entry.name.clone();
            member.location = entry.location.clone();
            member.latency_ms = latency.ms;
            member.failed = latency.failed;
            member.error = latency.error;
            member.subscription_id.clone_from(&entry.subscription);
        }
        member
    }

    /// Starts a latency test of `group`, or of every group: gives each of its nodes a new probe
    /// generation, so a result of an earlier test still under way is dropped, and marks the
    /// groups testing.
    pub fn plan(&mut self, group: Option<&str>) -> Result<Plan, ErrorCode> {
        let groups: Vec<String> = match group {
            Some(id) => vec![self.group(id).ok_or(ErrorCode::NotFound)?.id.clone()],
            None if self.nodes.is_empty() => return Err(ErrorCode::NoNodes),
            None => self.groups.iter().map(|g| g.id.clone()).collect(),
        };
        let mut wanted = HashSet::new();
        for g in &groups {
            self.reach(g, &mut wanted, 0);
        }
        let mut targets = Vec::with_capacity(wanted.len());
        let began_ms = Clock::now().wall_ms;
        for entry in self.nodes.iter().filter(|n| wanted.contains(&n.id)) {
            self.next_probe += 1;
            let latency = self.latency.entry(entry.id.clone()).or_default();
            latency.generation = self.next_probe;
            latency.probing = true;
            targets.push(Target {
                entry: Arc::clone(entry),
                generation: self.next_probe,
                began_ms,
            });
        }
        for g in &groups {
            *self.testing.entry(g.clone()).or_default() += 1;
        }
        Ok(Plan { groups, targets })
    }

    /// The node ids `group` reaches through its members.
    fn reach(&self, group: &str, out: &mut HashSet<String>, depth: u8) {
        let Some(g) = self.group(group) else { return };
        for m in &g.members {
            if !is_group(m) {
                out.insert(m.clone());
            } else if depth < MAX_DEPTH {
                self.reach(m, out, depth + 1);
            }
        }
    }

    /// Commits a probe result, a time or a failure with its `error` code when it has one, if its
    /// node still has the incarnation and probe generation the probe began with. A success lets the
    /// UrlTest groups with the node pick their fastest member again; a failure changes no choice,
    /// so a round where every probe fails keeps the current member whatever order the failures
    /// arrive in.
    pub fn record(&mut self, target: &Target, ms: Option<u32>, error: Option<ErrorCode>) -> bool {
        let entry = &target.entry;
        // A rename replaces the immutable name but keeps a static endpoint's probe generation.
        // Removing it drops its latency entry; a later probe gets a fresh generation on re-add.
        let current = entry.subscription.is_none()
            || self
                .index
                .get(&entry.id)
                .is_some_and(|&i| Arc::ptr_eq(&self.nodes[i], entry));
        let Some(latency) = self
            .latency
            .get_mut(&entry.id)
            .filter(|l| current && l.generation == target.generation)
        else {
            return false;
        };
        latency.ms = ms;
        latency.failed = ms.is_none();
        latency.error = if ms.is_none() { error } else { None };
        latency.probing = false;
        if ms.is_some() {
            self.choose(&entry.id);
        }
        true
    }

    /// Lets each UrlTest group with `member` pick its fastest member. Only results of nodes with
    /// no probe under way count: an earlier round's time of a node being probed again never
    /// wins. A group with no such result keeps its choice.
    fn choose(&mut self, member: &str) {
        let latency = &self.latency;
        let known = |m: &str| latency.get(m).filter(|l| !l.probing).and_then(|l| l.ms);
        for g in self.groups.iter().filter(|g| g.kind == GroupKind::UrlTest) {
            if !g.members.iter().any(|m| m == member) {
                continue;
            }
            if let Some(best) = groups::fastest(g, known) {
                self.auto.insert(g.id.clone(), best.clone());
            }
        }
    }

    /// A latency test ended, finished or cancelled: its probes that never reported leave their
    /// nodes with the earlier result.
    pub fn finish(&mut self, round: &Round) {
        for (id, generation) in &round.probes {
            if let Some(l) = self.latency.get_mut(id)
                && l.generation == *generation
            {
                l.probing = false;
            }
        }
        for g in &round.groups {
            if let Some(n) = self.testing.get_mut(g) {
                *n = n.saturating_sub(1);
                if *n == 0 {
                    self.testing.remove(g);
                }
            }
        }
    }
}

/// The policy of `source` over `groups`, whose nodes `index` holds.
fn compile(groups: &[GroupDef], index: &HashMap<String, usize>, source: &RuleSourceSpec) -> Policy {
    let RuleSourceSpec::Template { options, .. } = source;
    let is_node = |id: &str| index.contains_key(id);
    Policy::template(groups, &is_node, template::Options::from(*options))
}

#[cfg(test)]
mod tests;
