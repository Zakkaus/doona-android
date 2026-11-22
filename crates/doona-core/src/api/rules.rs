//! Rule mode's commands (rule-mode.md, R3): `rules` lists the source's rows a page at a time,
//! and `setRuleSource` changes the source. Reads take the published policy under the catalog
//! lock and build their reply with no lock held.

use super::{Core, lock, parse, to_json};
use crate::dto::{ErrorCode, Reply, RuleSourceSpec, RulesArgs};
use crate::rules::template;
use crate::state::State;
use std::sync::Arc;

/// The most rows one `rules` call returns.
const MAX_PAGE: u32 = 500;

impl Core {
    /// `rules`: `limit` rows (1 to 500) from `offset`; none past the end.
    pub(super) fn rules(&self, args: &str) -> Result<String, ErrorCode> {
        let RulesArgs { offset, limit } = parse(args)?;
        if !(1..=MAX_PAGE).contains(&limit) {
            return Err(ErrorCode::InvalidArgument);
        }
        let policy = Arc::clone(&lock(&self.shared.catalog).model.policy);
        let page = policy.listing().page(offset, limit);
        Ok(to_json(&Reply::Ok(page)))
    }

    /// `setRuleSource`: writes the source to `state.json` first, then compiles it over the
    /// current groups and publishes it in one catalog section. New flows use it; open ones keep
    /// their route. An unchanged source writes nothing. Schema 2 knows doona's template only.
    pub(super) fn set_rule_source(&self, spec: RuleSourceSpec) -> Result<(), ErrorCode> {
        let RuleSourceSpec::Template { template, .. } = &spec;
        if template != template::ID {
            return Err(ErrorCode::NotFound);
        }
        let _writer = lock(&self.writer);
        let current = lock(&self.shared.catalog).state.clone();
        if current.rule_source == spec {
            return Ok(());
        }
        let next = State {
            rule_source: spec,
            ..current
        };
        self.write(&next, "setRuleSource", None)?;
        {
            let mut c = lock(&self.shared.catalog);
            c.model.set_source(&next.rule_source);
            c.state = next;
            c.revision += 1;
            self.shared.publish(&c);
        }
        self.store.sync();
        Ok(())
    }
}
