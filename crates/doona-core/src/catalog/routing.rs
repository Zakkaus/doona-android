use super::{GroupDef, GroupKind, MAX_DEPTH, Model, groups, is_group};
use crate::dto::Network;
use crate::outbounds::{Leaf, Routing};
use crate::state::State;
use honk_outbound::group::{SelectionNetwork, SelectorMember, SelectorNetworks};
use std::collections::HashMap;
use std::sync::Arc;

impl Model {
    pub(super) fn choice<'a>(
        &'a self,
        g: &'a GroupDef,
        state: &'a State,
        net: Network,
    ) -> (Option<&'a str>, bool) {
        if g.kind != GroupKind::Score {
            return groups::selected(g, state.pin(&g.id, net), self.auto.get(&g.id));
        }
        let net = match net {
            Network::Tcp => SelectionNetwork::Tcp,
            Network::Udp => SelectionNetwork::Udp,
        };
        (
            self.score.selected(&g.id, net),
            self.score.manager.has_override(&g.id, net),
        )
    }

    pub fn routing(&self, state: &State) -> Routing {
        Routing {
            score: Arc::clone(&self.score),
            selected: self.leaves(state),
            policy: Arc::clone(&self.policy),
            mode: state.mode,
        }
    }

    pub fn leaves(&self, state: &State) -> HashMap<Arc<str>, [Option<Leaf>; 2]> {
        let split = !state.tcp_selections.is_empty() || !state.udp_selections.is_empty();
        self.groups
            .iter()
            .map(|g| {
                let tcp = self.leaf(g, state, Network::Tcp, 0);
                let udp = if split && g.kind != GroupKind::Score {
                    self.leaf(g, state, Network::Udp, 0)
                } else {
                    tcp.clone()
                };
                (Arc::from(g.id.as_str()), [tcp, udp])
            })
            .collect()
    }

    fn leaf(&self, group: &GroupDef, state: &State, network: Network, depth: u8) -> Option<Leaf> {
        if group.kind == GroupKind::Score {
            return Some(Leaf::Score(group.id.as_str().into()));
        }
        let member = groups::selected(
            group,
            state.pin(&group.id, network),
            self.auto.get(&group.id),
        )
        .0?;
        if is_group(member) {
            if depth >= MAX_DEPTH {
                return None;
            }
            return self.leaf(self.group(member)?, state, network, depth + 1);
        }
        Some(Leaf::Node(Arc::clone(
            &self.nodes[*self.index.get(member)?].node,
        )))
    }

    pub fn update_pins(&self, old: Option<&State>, next: &State) {
        for g in self.groups.iter().filter(|g| g.kind == GroupKind::Score) {
            let changed = |net| {
                let member = next.pin(&g.id, net);
                (member != old.and_then(|s| s.pin(&g.id, net))).then_some(member)
            };
            let tcp = changed(Network::Tcp);
            let udp = changed(Network::Udp);
            if let Some(member) = tcp
                && tcp == udp
            {
                self.set_pin(g, member, SelectorNetworks::Both);
            } else {
                if let Some(member) = tcp {
                    self.set_pin(g, member, SelectorNetworks::Tcp);
                }
                if let Some(member) = udp {
                    self.set_pin(g, member, SelectorNetworks::Udp);
                }
            }
        }
    }

    fn set_pin(&self, g: &GroupDef, member: Option<&String>, networks: SelectorNetworks) {
        let member = member.filter(|m| g.members.contains(m)).map(|m| {
            if is_group(m) {
                SelectorMember::Group(m.clone())
            } else {
                SelectorMember::Node(self.nodes[self.index[m]].uuid)
            }
        });
        self.score.pin(&g.id, member, networks);
    }
}
