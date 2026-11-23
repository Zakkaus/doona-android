use super::{GroupDef, GroupKind, MAX_DEPTH, Model, groups, is_group};
use crate::dto::Mode;
use crate::outbounds::{Leaf, Routing};
use honk_outbound::group::SelectorMember;
use std::collections::{BTreeMap, HashMap};
use std::sync::Arc;

type Pins = BTreeMap<String, String>;

impl Model {
    pub fn routing(&self, pins: &Pins, mode: Mode) -> Routing {
        Routing {
            score: Arc::clone(&self.score),
            selected: self.leaves(pins),
            policy: Arc::clone(&self.policy),
            mode,
        }
    }

    pub fn leaves(&self, pins: &Pins) -> HashMap<String, Leaf> {
        self.groups
            .iter()
            .filter_map(|g| Some((g.id.clone(), self.leaf(g, pins, 0)?)))
            .collect()
    }

    fn leaf(&self, group: &GroupDef, pins: &Pins, depth: u8) -> Option<Leaf> {
        if group.kind == GroupKind::Score {
            return Some(Leaf::Score(group.id.as_str().into()));
        }
        let member = groups::selected(group, pins.get(&group.id), self.auto.get(&group.id)).0?;
        if is_group(member) {
            if depth >= MAX_DEPTH {
                return None;
            }
            return self.leaf(self.group(member)?, pins, depth + 1);
        }
        Some(Leaf::Node(Arc::clone(
            &self.nodes[*self.index.get(member)?].node,
        )))
    }

    pub(super) fn restore_pins(&self, pins: &Pins) {
        for (group, member) in pins {
            self.set_pin(group, Some(member));
        }
    }

    pub fn update_pins(&self, old: &Pins, next: &Pins) {
        for g in self.groups.iter().filter(|g| g.kind == GroupKind::Score) {
            if next.get(&g.id) != old.get(&g.id) {
                self.set_pin(&g.id, next.get(&g.id));
            }
        }
    }

    fn set_pin(&self, group: &str, member: Option<&String>) {
        let Some(g) = self.group(group).filter(|g| g.kind == GroupKind::Score) else {
            return;
        };
        let member = member.filter(|m| g.members.contains(m)).map(|m| {
            if is_group(m) {
                SelectorMember::Group(m.clone())
            } else {
                SelectorMember::Node(self.nodes[self.index[m]].uuid)
            }
        });
        self.score.pin(group, member);
    }
}
