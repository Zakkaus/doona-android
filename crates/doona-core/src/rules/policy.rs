//! What a new flow's route is chosen by (rule-mode.md, Contract sketch): the main group, which
//! Global sends every flow through, and in Rule the compiled rules, whose first match decides and
//! which leave the main group to a flow none matches. The catalog compiles it with its groups and
//! publishes it with each group's node (`Outbounds`), so a flow reads both from one publication.

use super::template::{self, Options};
use super::{Flow, Network, RuleSet, RuleSpec, Target};
use crate::dto::GroupKind;
use crate::groups::{self, GroupDef};
use crate::ids::group_id;

/// QUIC's port, which `blockQuic` refuses over UDP.
const QUIC_PORT: u16 = 443;

#[derive(Debug)]
pub(crate) struct Policy {
    /// The main group's id (`mainGroupId`): `g-Proxy` under the template.
    main: String,
    /// None before the catalog publishes, or when the rules failed to compile.
    rules: Option<RuleSet>,
    block_quic: bool,
}

/// Where the rules send a flow.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Choice<'a> {
    Direct,
    Reject,
    /// Through this group, by id.
    Group(&'a str),
}

impl Default for Policy {
    fn default() -> Policy {
        Policy {
            main: group_id(groups::PROXY),
            rules: None,
            block_quic: false,
        }
    }
}

impl Policy {
    /// The template's rules over the catalog's `groups`, whose nodes `is_node` knows. Without a
    /// node the catalog has no group: the rules then compile over the groups they name, empty, so
    /// the rows that go direct still apply and a flow a group would carry is refused (rule-mode.md
    /// decision 9). The template always compiles (tests hold it to a full catalog and to none), so
    /// a failure is logged and leaves every flow to the main group.
    pub fn template(
        groups: &[GroupDef],
        is_node: &dyn Fn(&str) -> bool,
        options: Options,
    ) -> Policy {
        let rows = template::rules();
        let empty;
        let groups = if groups.is_empty() {
            empty = named_groups(&rows);
            &empty[..]
        } else {
            groups
        };
        let rules = RuleSet::compile_template(groups, is_node, &rows)
            .inspect_err(|code| log::error!("rules: template refused ({})", code.as_str()))
            .ok();
        Policy {
            rules,
            block_quic: options.block_quic,
            ..Policy::default()
        }
    }

    /// A policy of `rules` with `main` as its main group.
    #[cfg(test)]
    pub fn new(main: &str, rules: Option<RuleSet>, options: Options) -> Policy {
        Policy {
            main: main.to_owned(),
            rules,
            block_quic: options.block_quic,
        }
    }

    pub fn main(&self) -> &str {
        &self.main
    }

    /// Whether a rule matches names, so a flow's name is worth looking up.
    pub fn wants_names(&self) -> bool {
        self.rules.as_ref().is_some_and(RuleSet::has_names)
    }

    /// Where rule mode sends `flow`: its first matching rule's target, the main group when none
    /// matches. With `blockQuic`, QUIC bound for a group is rejected.
    pub fn choose<'a>(&'a self, flow: &Flow<'_>) -> Choice<'a> {
        let rules = self.rules.as_ref();
        let matched = rules.and_then(|r| Some(&r.rows()[r.matched(flow)?].target));
        let group = match matched {
            Some(Target::Direct) => return Choice::Direct,
            Some(Target::Reject) => return Choice::Reject,
            Some(Target::Group(id)) => id.as_str(),
            None => self.main.as_str(),
        };
        if self.block_quic && flow.network == Network::Udp && flow.port == QUIC_PORT {
            return Choice::Reject;
        }
        Choice::Group(group)
    }
}

/// The groups `rows` send flows to, each without a member.
fn named_groups(rows: &[RuleSpec]) -> Vec<GroupDef> {
    let mut groups: Vec<GroupDef> = Vec::new();
    for row in rows {
        if let Target::Group(id) = &row.target
            && !groups.iter().any(|g| &g.id == id)
        {
            groups.push(GroupDef {
                id: id.clone(),
                name: id.strip_prefix("g-").unwrap_or(id).to_owned(),
                kind: GroupKind::Selector,
                members: Vec::new(),
            });
        }
    }
    groups
}
