//! honk selects subscription-scoped identities; the runtime registry dials the original node.

use crate::catalog::NodeEntry;
use crate::dto::GroupKind;
use crate::groups::GroupDef;
use crate::ids::is_group;
use honk_config::group::{Group, GroupPolicy};
use honk_outbound::alive::{AliveDialerSet, IpVersion, ProbeDomain};
use honk_outbound::group::{
    GroupManager, GroupMember, SelectionNetwork, SelectorMember, SelectorNetworks,
};
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Arc;
use std::time::Duration;
use uuid::Uuid;

pub(crate) struct Score {
    pub manager: GroupManager,
    pub alive: Arc<AliveDialerSet>,
    pub entries: HashMap<Uuid, Arc<NodeEntry>>,
}

impl Score {
    pub fn new(
        groups: &[GroupDef],
        entries: &[Arc<NodeEntry>],
        index: &HashMap<String, usize>,
        previous: Option<&Self>,
    ) -> Self {
        let alive = previous.map(|s| Arc::clone(&s.alive)).unwrap_or_default();
        let nodes: Vec<_> = entries
            .iter()
            .map(|entry| {
                let mut node = (*entry.node).clone();
                node.id = entry.uuid;
                node
            })
            .collect();
        let groups: Vec<_> = groups
            .iter()
            .filter(|g| g.kind == GroupKind::Score)
            .map(|g| {
                let mut group = Group {
                    name: g.id.clone(),
                    policy: GroupPolicy::Score,
                    ..Group::default()
                };
                for member in &g.members {
                    if is_group(member) {
                        group.groups.push(member.clone());
                    } else {
                        group.nodes.push(entries[index[member]].uuid);
                    }
                }
                group
            })
            .collect();
        let manager = GroupManager::with_alive_set_and_score_state(
            &groups,
            &nodes,
            Some(Arc::clone(&alive)),
            previous
                .map(|s| s.manager.score_state())
                .unwrap_or_default(),
        );
        Self {
            manager,
            alive,
            entries: entries.iter().map(|n| (n.uuid, Arc::clone(n))).collect(),
        }
    }

    // A staged reload must not change registrations used by the live catalog.
    pub fn publish(&self, previous: Option<&Self>) {
        let previous = previous.filter(|p| Arc::ptr_eq(&p.alive, &self.alive));
        for (&id, entry) in &self.entries {
            let node = &entry.node;
            let old = previous.and_then(|p| p.entries.get(&id));
            if !old.is_some_and(|e| e.node.name == node.name && e.node.address == node.address) {
                self.alive
                    .register_node(id, node.name.clone(), node.address.clone());
            }
        }
        if let Some(previous) = previous {
            for id in previous
                .entries
                .keys()
                .filter(|id| !self.entries.contains_key(id))
            {
                self.alive.remove_node(*id);
            }
        }
        self.manager.publish_score_membership();
    }

    pub fn selected(&self, group: &str) -> Option<&str> {
        match self
            .manager
            .peek_selection(group, SelectionNetwork::Tcp)?
            .member
        {
            GroupMember::Node(n) => Some(&self.entries[&n.id].id),
            GroupMember::Group(g) => Some(&g.name),
        }
    }

    pub fn pin(&self, group: &str, member: Option<SelectorMember>) {
        let networks = SelectorNetworks::Both;
        match member {
            Some(m) => self.manager.publish_override(group, &m, networks),
            None => self.manager.clear_override(group, networks),
        }
        .expect("validated Score group member")
        .run_callbacks();
    }

    pub fn available(&self, id: Uuid, ip: IpAddr) {
        self.alive
            .report_available_traffic(id, ProbeDomain::Tcp, family(ip));
    }

    pub fn probe(&self, id: Uuid, ip: IpAddr, latency: Option<u32>) {
        let family = family(ip);
        if let Some(ms) = latency {
            self.alive.record_probe_latency(
                id,
                ProbeDomain::Tcp,
                family,
                Duration::from_millis(ms.into()),
            );
        }
    }
}

pub(crate) fn family(ip: IpAddr) -> IpVersion {
    if ip.is_ipv4() {
        IpVersion::V4
    } else {
        IpVersion::V6
    }
}
