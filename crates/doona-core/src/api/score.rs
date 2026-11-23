use super::{Core, lock, ok, parse};
use crate::dto::score::{Challenger, Counters, Coverage, Group, Network, ScoreState};
use crate::dto::{Empty, ErrorCode, GroupKind};
use crate::ids::is_group;
use crate::score::Score;
use honk_outbound::group::{
    GroupMember, ScoreVerificationState, ScoreWaitReason, SelectionNetwork,
};
use std::collections::HashMap;
use std::sync::Arc;

impl Core {
    pub(super) fn score_state(&self, args: &str) -> Result<String, ErrorCode> {
        let Empty {} = parse(args)?;
        let (score, ids) = {
            let c = lock(&self.shared.catalog);
            let ids: Vec<_> = c
                .model
                .groups
                .iter()
                .filter(|g| g.kind == GroupKind::Score)
                .map(|g| g.id.clone())
                .collect();
            (Arc::clone(&c.model.score), ids)
        };
        Ok(ok(ScoreState {
            groups: ids.iter().map(|id| score.group_state(id)).collect(),
            root_business_starts: score.manager.score_state().root_business_starts(),
        }))
    }
}

impl Score {
    fn group_state<'a>(&'a self, id: &'a str) -> Group<&'a str> {
        let mut tags = HashMap::new();
        let mut node_ids = Vec::new();
        for member in self.manager.group_members(id) {
            let (tag, member_id) = match member {
                GroupMember::Node(n) => {
                    let id = self.entries[&n.id].id.as_str();
                    node_ids.push(id);
                    (n.name.as_str(), id)
                }
                GroupMember::Group(g) => (g.name.as_str(), g.name.as_str()),
            };
            tags.entry(tag)
                .and_modify(|id| *id = None)
                .or_insert(Some(member_id));
        }
        Group {
            id,
            node_ids,
            tcp: self.network_state(id, SelectionNetwork::Tcp, &tags),
            udp: self.network_state(id, SelectionNetwork::Udp, &tags),
        }
    }

    fn network_state<'a>(
        &self,
        id: &str,
        network: SelectionNetwork,
        tags: &HashMap<&str, Option<&'a str>>,
    ) -> Network<&'a str> {
        let counts = self.manager.score_budget_counters(id, network);
        let mut result = Network {
            selected: None,
            selected_id: None,
            state: ScoreVerificationState::Provisional,
            wait_reason: ScoreWaitReason::None,
            coverage: Coverage::default(),
            challengers: Vec::new(),
            counters: Counters {
                business_starts: counts.business_starts,
                trial_success: counts.trial_success,
                trial_failure: counts.trial_failure,
                trial_cancelled: counts.trial_cancelled,
            },
        };
        if let Some((tag, snapshot)) = self.manager.score_verification_for_network(id, network) {
            result.selected_id = tags[tag.as_str()];
            result.selected = Some(tag);
            result.state = snapshot.state;
            result.wait_reason = snapshot.wait_reason;
            result.coverage = Coverage {
                candidates: snapshot.candidate_count,
                evaluated: snapshot.evaluated_count,
                pending: snapshot.pending_count,
            };
            result.challengers = snapshot
                .challengers
                .into_iter()
                .map(|c| Challenger {
                    node_id: tags[c.name.as_str()].filter(|id| !is_group(id)),
                    name: c.name,
                    basis: c.basis,
                    relation: c.relation,
                    reporters: c.reporters,
                    valid_for_ms: c.valid_for_ms,
                })
                .collect();
        }
        result
    }
}

#[cfg(test)]
mod tests;
