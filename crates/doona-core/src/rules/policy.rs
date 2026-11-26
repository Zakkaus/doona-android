//! The rule source the catalog publishes (rule-mode.md, Contract sketch): the main group, the
//! template's rows compiled over the catalog's groups, and the rows as `rules` and
//! `effectiveConfig` list them, with where they came from. honk's engine routes flows; this is
//! what the rules page shows.

use super::listing::Listing;
use super::template::{self, Options};
use super::{RuleSet, RuleSpec, Target};
use crate::dto::{ErrorCode, GroupKind, RuleOptions, RuleSource, SourceKind};
use crate::groups::{self, GroupDef};
use crate::ids::group_id;

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
    /// node the catalog has no group: the rules then compile over the groups they name, empty
    /// (rule-mode.md decision 9). The template always compiles (tests hold it to a full catalog
    /// and to none), so a failure is logged and the source shows `parse_failed`.
    #[cfg(test)]
    pub fn template(
        groups: &[GroupDef],
        is_node: &dyn Fn(&str) -> bool,
        options: Options,
    ) -> Policy {
        Self::template_with_data(groups, is_node, options, None)
    }

    pub fn template_with_data(
        groups: &[GroupDef],
        is_node: &dyn Fn(&str) -> bool,
        options: Options,
        data: Option<&super::dat::Data>,
    ) -> Policy {
        let rows = template::rules();
        let empty;
        let groups = if groups.is_empty() {
            empty = named_groups(&rows);
            &empty[..]
        } else {
            groups
        };
        let rules = RuleSet::compile_source(super::Origin::Template, groups, is_node, &rows, data)
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
