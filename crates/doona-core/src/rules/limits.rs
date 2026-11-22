//! The bounds of a rule source (rule-mode.md, Safety limits), checked as C1d's guard checks a
//! body: over the whole source, before anything is built, and `parse_failed` past any of them.
//! [`check`] allocates nothing.

use super::{Kind, RuleSpec, ports};
use crate::catalog::MAX_NODES;
use crate::dto::ErrorCode;
use crate::groups::GroupDef;

/// The most lines a source may hold. A row counts one line a value, a `DST-PORT` value one a
/// port or range in it (`ports::pieces`), a row without values one. The Mihomo profile C1d
/// measured holds 20,000 (`subscription/structured/guard.rs`); its compile time and peak memory
/// are in `rules-STATE.md`.
pub(crate) const MAX_LINES: usize = 20_000;

/// The most `DOMAIN-KEYWORD` values a source may hold: one automaton takes them all.
pub(crate) const MAX_KEYWORDS: usize = 1_000;

/// The longest value a compiled kind may have: the longest DNS name, without a final dot for
/// `DOMAIN` and `DOMAIN-SUFFIX`, which drop it. A `DST-PORT` value is bounded by its pieces
/// instead (Mihomo's 28), as nothing of its text is kept.
pub(crate) const MAX_VALUE: usize = 253;

/// The most groups a source may define.
pub(crate) const MAX_GROUPS: usize = 256;

/// The most members a group may have, nodes and groups together (rule-mode.md, Safety limits).
pub(crate) const MAX_MEMBERS: usize = MAX_NODES;

/// The longest chain of groups in groups, as the catalog follows it (`catalog::MAX_DEPTH`): a
/// group four levels below another is the last one followed.
pub(crate) const MAX_DEPTH: u8 = crate::catalog::MAX_DEPTH;

/// Refuses a source past any bound above.
pub(super) fn check(groups: &[GroupDef], rules: &[RuleSpec]) -> Result<(), ErrorCode> {
    let refused = Err(ErrorCode::ParseFailed);
    if groups.len() > MAX_GROUPS || groups.iter().any(|g| g.members.len() > MAX_MEMBERS) {
        return refused;
    }
    let (mut lines, mut keywords) = (0usize, 0usize);
    for rule in rules {
        if rule.values.iter().any(|v| too_long(&rule.kind, v)) {
            return refused;
        }
        lines += match rule.kind {
            Kind::DstPort => rule.values.iter().map(|v| ports::pieces(v).count()).sum(),
            _ => rule.values.len(),
        }
        .max(1);
        if rule.kind == Kind::DomainKeyword {
            keywords += rule.values.len();
        }
        if lines > MAX_LINES || keywords > MAX_KEYWORDS {
            return refused;
        }
    }
    Ok(())
}

/// Whether a value of `kind` is past [`MAX_VALUE`]. A skipped kind's value is not kept, so it has
/// no bound of its own.
fn too_long(kind: &Kind, value: &str) -> bool {
    let kept = match kind {
        Kind::Skipped { .. } | Kind::DstPort => return false,
        Kind::Domain | Kind::DomainSuffix => value.strip_suffix('.').unwrap_or(value),
        _ => value,
    };
    kept.len() > MAX_VALUE
}
