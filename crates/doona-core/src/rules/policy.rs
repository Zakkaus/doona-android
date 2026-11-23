//! What a new flow's route is chosen by (rule-mode.md, Contract sketch): the main group, which
//! Global sends every flow through, and in Rule the compiled rules, whose first match decides and
//! which leave the main group to a flow none matches. The catalog compiles it with its groups and
//! publishes it with each group's node (`Outbounds`), so a flow reads both from one publication.
//! It also keeps the rows as `rules` and `effectiveConfig` list them, and where they came from.

use super::listing::Listing;
use super::template::{self, Options};
use super::{Flow, Network, RuleSet, RuleSpec, Target};
use crate::dto::{ErrorCode, GroupKind, RuleOptions, RuleSource, SourceKind};
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
    /// The source's rows with their ids.
    listing: Listing,
}

/// Where the rules send a flow.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Choice<'a> {
    Direct,
    Reject,
    /// QUIC bound for a group, which `blockQuic` refuses.
    Blocked,
    /// Through this group, by id.
    Group(&'a str),
}

impl Default for Policy {
    fn default() -> Policy {
        Policy {
            main: group_id(groups::PROXY),
            rules: None,
            block_quic: false,
            listing: Listing::default(),
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
        let source = format!("template:{}", template::ID);
        Policy {
            listing: Listing::new(&source, &rows, rules.as_ref()),
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
            listing: Listing::default(),
        }
    }

    /// A policy of `specs` compiled over `groups`, with the rows listed as `rules` lists them.
    #[cfg(test)]
    pub fn listed(
        main: &str,
        groups: &[GroupDef],
        is_node: &dyn Fn(&str) -> bool,
        specs: &[RuleSpec],
    ) -> Policy {
        let rules = RuleSet::compile(groups, is_node, specs).ok();
        Policy {
            listing: Listing::new("test", specs, rules.as_ref()),
            ..Policy::new(main, rules, Options::default())
        }
    }

    pub fn main(&self) -> &str {
        &self.main
    }

    /// The source's rows as `rules` lists them.
    pub fn listing(&self) -> &Listing {
        &self.listing
    }

    /// `ruleSource`: the template and its options, and what compiled of its rows.
    pub fn source(&self) -> RuleSource {
        let count = |n: usize| u32::try_from(n).unwrap_or(u32::MAX);
        RuleSource {
            kind: SourceKind::Template,
            template: Some(template::ID.to_owned()),
            version: Some(template::VERSION),
            subscription_id: None,
            options: RuleOptions {
                block_quic: self.block_quic,
            },
            rules: count(self.listing.rows.len()),
            lines: count(self.listing.lines()),
            skipped: count(self.rules.as_ref().map_or(0, RuleSet::skipped)),
            rules_revision: self.listing.revision,
            error: self.rules.is_none().then_some(ErrorCode::ParseFailed),
        }
    }

    /// Whether a rule matches names, so a flow's name is worth looking up.
    pub fn wants_names(&self) -> bool {
        self.rules.as_ref().is_some_and(RuleSet::has_names)
    }

    /// Where rule mode sends `flow`: its first matching rule's target, the main group when none
    /// matches. With `blockQuic`, QUIC bound for a group is rejected.
    pub fn choose<'a>(&'a self, flow: &Flow<'_>) -> Choice<'a> {
        self.route(flow).0
    }

    /// [`Policy::choose`]'s choice, and the rule that made it for `byRule`: the matched row's id
    /// as `rules` lists it, or `unmatched` when no row matched and the flow went to the main
    /// group.
    #[cfg_attr(
        not(test),
        expect(dead_code, reason = "route.rs records the rule once r2-fix2 merges")
    )]
    pub fn decide<'a>(&'a self, flow: &Flow<'_>) -> (Choice<'a>, &'a str) {
        let (choice, row) = self.route(flow);
        let listed = row.and_then(|i| self.listing.rows.get(i));
        (choice, listed.map_or("unmatched", |r| r.id.as_str()))
    }

    /// The choice, and the index of the row that matched.
    fn route<'a>(&'a self, flow: &Flow<'_>) -> (Choice<'a>, Option<usize>) {
        let rules = self.rules.as_ref();
        let row = rules.and_then(|r| r.matched(flow));
        let matched = rules.zip(row).map(|(r, i)| &r.rows()[i].target);
        let group = match matched {
            Some(Target::Direct) => return (Choice::Direct, row),
            Some(Target::Reject) => return (Choice::Reject, row),
            Some(Target::Group(id)) => id.as_str(),
            None => self.main.as_str(),
        };
        if self.block_quic && flow.network == Network::Udp && flow.port == QUIC_PORT {
            return (Choice::Blocked, row);
        }
        (Choice::Group(group), row)
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
