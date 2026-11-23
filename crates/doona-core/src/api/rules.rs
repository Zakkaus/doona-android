//! Rule mode's commands (rule-mode.md, R3): `rules` lists the source's rows a page at a time,
//! `setRuleSource` changes the source, and `effectiveConfig` shows what routes flows and DNS as
//! text. Reads copy what they need under the catalog lock and build their reply with no lock
//! held.

use super::{Core, lock, parse, to_json};
use crate::dto::{
    Catalog, EffectiveConfig, Empty, ErrorCode, Member, Mode, Reply, RuleSourceSpec, RulesArgs,
};
use crate::rules::export::Capped;
use crate::rules::policy::Policy;
use crate::rules::template;
use crate::state::State;
use std::net::SocketAddr;
use std::sync::Arc;

/// The most rows one `rules` call returns.
const MAX_PAGE: u32 = 500;
/// `effectiveConfig`'s text ends at a line boundary within this many bytes.
const MAX_TEXT: usize = 1 << 20;

/// A node's code in `effectiveConfig`: `n-` and the first 8 hex digits of its id.
fn code(id: &str) -> String {
    let hex = id.strip_prefix("n-").unwrap_or(id);
    format!("n-{}", hex.get(..8).unwrap_or(hex))
}

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

    /// `effectiveConfig`: the mode, where DNS goes, the rules' provenance and the rules as text.
    pub(super) fn effective_config(&self, args: &str) -> Result<String, ErrorCode> {
        let Empty {} = parse(args)?;
        let (catalog, policy) = {
            let c = lock(&self.shared.catalog);
            (c.model.catalog(&c.state), Arc::clone(&c.model.policy))
        };
        let upstreams = &self.options.dns_upstreams;
        let config = effective(catalog, &policy, upstreams, MAX_TEXT);
        Ok(to_json(&Reply::Ok(config)))
    }
}

/// `effectiveConfig`'s reply for `catalog` and its `policy`, the text capped at `cap` bytes: a
/// comment each for the mode, the source, the main group, DNS and every group with its members
/// and current choice, then the rules. A group appears by its name, a node only by its [`code`]:
/// a display name can hold anything, the node's server in any spelling or its password, so no
/// filter on names keeps them out (ARCH D21). The page may show names on screen through the
/// snapshot; the text, which is exported and shared, keeps the codes.
pub(super) fn effective(
    catalog: Catalog,
    policy: &Policy,
    upstreams: &[SocketAddr],
    cap: usize,
) -> EffectiveConfig {
    let name = |id: &str| match catalog.groups.iter().find(|g| g.id == id) {
        Some(g) => g.name.clone(),
        None => id.strip_prefix("g-").unwrap_or(id).to_owned(),
    };
    let member = |m: &Member| {
        if m.is_group {
            m.name.clone()
        } else {
            code(&m.id)
        }
    };
    let main = catalog.main_group_id.clone();
    let direct = catalog.mode == Mode::Direct;
    let upstreams: Vec<String> = upstreams.iter().map(ToString::to_string).collect();
    let source = &catalog.rule_source;
    let mut out = Capped::new(cap);
    out.line(&format!("# mode: {:?}", catalog.mode));
    out.line(&format!(
        "# rules: template {} version {}, blockQuic {}, {} rows, {} lines, {} skipped",
        source.template.as_deref().unwrap_or("none"),
        source.version.unwrap_or_default(),
        source.options.block_quic,
        source.rules,
        source.lines,
        source.skipped,
    ));
    out.line(&format!("# main group: {}", name(&main)));
    let via = if direct {
        "DIRECT".to_owned()
    } else {
        name(&main)
    };
    out.line(&format!(
        "# dns: via {via}, upstreams {}",
        upstreams.join(", ")
    ));
    for g in &catalog.groups {
        let selected = g.selected_id.as_deref();
        let now = g.members.iter().find(|m| Some(m.id.as_str()) == selected);
        let now = now.map_or_else(|| "none".to_owned(), member);
        let members: Vec<String> = g.members.iter().map(member).collect();
        let kind = g.kind;
        out.line(&format!(
            "# group {} ({kind:?}, now {now}): {}",
            g.name,
            members.join(", ")
        ));
    }
    policy.listing().export(&name, &mut out);
    let (text, truncated) = out.finish();
    EffectiveConfig {
        mode: catalog.mode,
        dns_via: if direct {
            "direct".to_owned()
        } else {
            main.clone()
        },
        main_group_id: main,
        dns_upstreams: upstreams,
        rule_source: catalog.rule_source,
        text,
        truncated,
    }
}
