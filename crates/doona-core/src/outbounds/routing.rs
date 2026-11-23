//! What new flows are routed by, as [`Outbounds`] publishes it: the mode, each group's node and
//! the policy that chooses a group, swapped in one step and read at once.

use super::{Dialer, Outbounds, Published};
use crate::dto::Mode;
use crate::rules::Network;
use crate::rules::policy::Policy;
use crate::score::{Score, family};
use honk_config::node::Node;
use honk_outbound::alive::ProbeDomain;
use honk_outbound::group::{
    ScoreAttempt, ScoreContinuation, ScoreSelectionContext, ScoreSelectionEntry, ScoreTarget,
    SelectionNetwork,
};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;

#[derive(Clone)]
pub(crate) enum Leaf {
    Node(Arc<Node>),
    Score(Arc<str>),
}

impl Leaf {
    pub fn peek<'a>(&'a self, score: &'a Score) -> Option<&'a Arc<Node>> {
        match self {
            Self::Node(node) => Some(node),
            Self::Score(group) => {
                let selected = score.manager.peek_selection(group, SelectionNetwork::Tcp)?;
                Some(&score.entries[&selected.leaf?.id].node)
            }
        }
    }
}

pub(crate) struct Attempt {
    table: Arc<Table>,
    group: Arc<str>,
    uuid: uuid::Uuid,
    final_owners: Vec<String>,
    pub feedback: ScoreAttempt,
}

impl Attempt {
    pub fn available(&self, ip: std::net::IpAddr, available: bool) {
        self.table
            .score
            .as_ref()
            .expect("Score route")
            .available(self.uuid, ip, available);
    }
}

impl Dialer {
    pub(crate) fn retry(&self, original: &ScoreContinuation) -> Option<Self> {
        let attempt = self.score.as_ref()?;
        let plan = attempt
            .table
            .score
            .as_ref()?
            .manager
            .score_retry_plan_for_target(
                &attempt.group,
                attempt.feedback.context(),
                attempt.uuid,
                &attempt.final_owners,
                original,
            );
        let entry = plan.entries.into_iter().next()?;
        attempt
            .table
            .scored(&self.proxies, &self.group, &attempt.group, entry)
    }
}

#[derive(Default)]
pub(super) struct Table {
    pub(super) published: Option<Published>,
    pub(super) score: Option<Arc<Score>>,
    /// Group id to the node it sends traffic to, nested groups followed.
    pub(super) selected: HashMap<Arc<str>, Leaf>,
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
            score: Some(routing.score),
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
    pub score: Arc<Score>,
    pub selected: HashMap<String, Leaf>,
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
        let (group, leaf) = self.table.selected.get_key_value(group)?;
        self.table.dialer(
            &self.outbounds.proxies,
            group,
            leaf.peek(self.table.score.as_deref()?)?,
            None,
        )
    }

    pub fn for_flow<'a>(
        &self,
        group: &str,
        dst: SocketAddr,
        network: Network,
        domain: impl FnOnce() -> Option<&'a str>,
    ) -> Option<Dialer> {
        let (group, leaf) = self.table.selected.get_key_value(group)?;
        let score = self.table.score.as_ref()?;
        let owner = match leaf {
            Leaf::Score(owner) => owner,
            Leaf::Node(node) => {
                return self
                    .table
                    .dialer(&self.outbounds.proxies, group, node, None);
            }
        };
        let probe_domain = match network {
            Network::Tcp => ProbeDomain::Tcp,
            Network::Udp => ProbeDomain::DataUdp,
        };
        let family = family(dst.ip());
        let context = ScoreSelectionContext {
            network: SelectionNetwork::from_probe_domain(probe_domain),
            probe_domain,
            health_family: family,
            target_family: Some(family),
            target: Some(
                domain().map_or_else(|| dst.into(), |d| ScoreTarget::domain(d, dst.port())),
            ),
        };
        let plan = score
            .manager
            .selection_plan_for_target_with_health_fallback(owner, &context, None);
        self.table.scored(
            &self.outbounds.proxies,
            group,
            owner,
            plan.entries.into_iter().next()?,
        )
    }
}

impl Table {
    fn scored(
        self: &Arc<Self>,
        proxies: &Arc<honk_outbound::proxy::ProxyRegistry>,
        group: &Arc<str>,
        owner: &Arc<str>,
        entry: ScoreSelectionEntry<'_>,
    ) -> Option<Dialer> {
        let score = self.score.as_ref()?;
        let uuid = entry.node.id;
        let attempt = entry
            .feedback
            .filter(|f| f.context().network == SelectionNetwork::Tcp)
            .map(|feedback| Attempt {
                table: Arc::clone(self),
                group: Arc::clone(owner),
                uuid,
                final_owners: entry.final_owners,
                feedback,
            });
        self.dialer(proxies, group, &score.entries[&uuid].node, attempt)
    }

    fn dialer(
        &self,
        proxies: &Arc<honk_outbound::proxy::ProxyRegistry>,
        group: &Arc<str>,
        node: &Arc<Node>,
        score: Option<Attempt>,
    ) -> Option<Dialer> {
        let published = self.published.as_ref()?;
        Some(Dialer {
            group: Arc::clone(group),
            rule: None,
            score,
            node: Arc::clone(node),
            registry: Arc::clone(published.registries.of(node)?),
            proxies: Arc::clone(proxies),
            carriers: Arc::clone(&published.carriers),
        })
    }
}
