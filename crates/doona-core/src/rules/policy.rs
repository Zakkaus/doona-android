//! What a new flow's route is chosen by (rule-mode.md, Contract sketch): the main group, which
//! Global sends every flow through, and in Rule the compiled rules, whose first match decides and
//! which leave the main group to a flow none matches. The catalog compiles it with its groups and
//! publishes it with each group's node (`Outbounds`), so a flow reads both from one publication.

use super::template::{self, Options};
use super::{Flow, Network, RuleSet, Target};
use crate::groups::{self, GroupDef};
use crate::ids::group_id;

/// QUIC's port, which `blockQuic` refuses over UDP.
const QUIC_PORT: u16 = 443;

#[derive(Debug)]
pub(crate) struct Policy {
    /// The main group's id (`mainGroupId`): `g-Proxy` under the template.
    main: String,
    /// None while the catalog has no group, that is no node.
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
    /// The template's rules over the catalog's `groups`, whose nodes `is_node` knows; none
    /// without groups. The template always compiles over the catalog's groups (a test holds it to
    /// a full catalog), so a failure is logged and leaves every flow to the main group.
    pub fn template(
        groups: &[GroupDef],
        is_node: &dyn Fn(&str) -> bool,
        options: Options,
    ) -> Policy {
        let rules = (!groups.is_empty())
            .then(|| RuleSet::compile_template(groups, is_node, &template::rules()))
            .and_then(|compiled| {
                compiled
                    .inspect_err(|code| log::error!("rules: template refused ({})", code.as_str()))
                    .ok()
            });
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
