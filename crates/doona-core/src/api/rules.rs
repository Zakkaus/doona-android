//! Rule mode's commands (rule-mode.md, R3): `rules` lists the source's rows a page at a time,
//! `setRuleSource` changes the source, and `effectiveConfig` shows what routes flows and DNS as
//! text. Reads copy what they need under the catalog lock and build their reply with no lock
//! held.

use super::{Core, lock, parse, to_json};
use crate::catalog::NodeEntry;
use crate::dto::{
    Catalog, EffectiveConfig, Empty, ErrorCode, Member, Mode, Reply, RuleSourceSpec, RulesArgs,
};
use crate::ids;
use crate::rules::export::Capped;
use crate::rules::policy::Policy;
use crate::rules::template;
use crate::state::State;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;

/// The most rows one `rules` call returns.
const MAX_PAGE: u32 = 500;
/// `effectiveConfig`'s text ends at a line boundary within this many bytes.
const MAX_TEXT: usize = 1 << 20;
/// Fragments of a name that may carry a credential, matched in lowercase.
const SECRET_HINTS: [&str; 6] = ["://", "@", "token=", "key=", "pass=", "uuid"];

/// What `effectiveConfig` needs of a node to name it (ARCH D21): its id, display name and
/// endpoint, copied under the catalog lock.
pub(super) struct NodeName {
    id: String,
    name: String,
    host: String,
    port: u16,
}

impl NodeName {
    fn of(entry: &NodeEntry) -> Self {
        NodeName {
            id: entry.id.clone(),
            name: entry.name.clone(),
            host: entry.node.host.clone(),
            port: entry.node.port,
        }
    }

    /// The display name, or `n-` and the id's first 8 hex digits when the name holds the
    /// node's server, its port after a colon, or a fragment that may carry a credential.
    fn shown(&self) -> String {
        let name = self.name.to_lowercase();
        let host = self.host.to_lowercase();
        let bare = host.trim_start_matches('[').trim_end_matches(']');
        let leaks = (!bare.is_empty() && name.contains(bare))
            || name.contains(&format!(":{}", self.port))
            || SECRET_HINTS.iter().any(|hint| name.contains(hint));
        if leaks {
            code(&self.id)
        } else {
            self.name.clone()
        }
    }
}

/// `n-` and the first 8 hex digits of node `id`.
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
        let (catalog, policy, nodes) = {
            let c = lock(&self.shared.catalog);
            let nodes: Vec<NodeName> = c.model.nodes.iter().map(|e| NodeName::of(e)).collect();
            (
                c.model.catalog(&c.state),
                Arc::clone(&c.model.policy),
                nodes,
            )
        };
        let upstreams = &self.options.dns_upstreams;
        let config = effective(catalog, &policy, &nodes, upstreams, MAX_TEXT);
        Ok(to_json(&Reply::Ok(config)))
    }
}

/// `effectiveConfig`'s reply for `catalog` and its `policy`, the text capped at `cap` bytes: a
/// comment each for the mode, the source, the main group, DNS and every group with its members
/// and current choice, then the rules. A node of `nodes` appears by its display name, or by a
/// code where that name could show its endpoint or a credential (ARCH D21).
pub(super) fn effective(
    catalog: Catalog,
    policy: &Policy,
    nodes: &[NodeName],
    upstreams: &[SocketAddr],
    cap: usize,
) -> EffectiveConfig {
    let name = |id: &str| match catalog.groups.iter().find(|g| g.id == id) {
        Some(g) => g.name.clone(),
        None => id.strip_prefix("g-").unwrap_or(id).to_owned(),
    };
    let shown: HashMap<&str, String> = nodes.iter().map(|n| (n.id.as_str(), n.shown())).collect();
    let member = |m: &Member| match shown.get(m.id.as_str()) {
        Some(name) => name.clone(),
        None if ids::is_group(&m.id) => m.name.clone(),
        None => code(&m.id),
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
