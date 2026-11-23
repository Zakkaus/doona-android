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
/// `DOMAIN` and `DOMAIN-SUFFIX`, which drop it. A `DST-PORT` value has [`MAX_PORT_VALUE`].
pub(crate) const MAX_VALUE: usize = 253;

/// The longest `DST-PORT` value: Mihomo's 28 pieces, each a full range in brackets
/// (`[65535-65535]`, 13 bytes) with its separator and a space on either side. It is checked before
/// the value is split, so a long one is never scanned for pieces.
pub(crate) const MAX_PORT_VALUE: usize = ports::MAX_PIECES * 16;

/// The most groups a source may define.
pub(crate) const MAX_GROUPS: usize = 256;

/// The most members an imported group may have, nodes and groups together (rule-mode.md, Safety
/// limits). doona's own template is not held to it ([`Origin::Template`]).
pub(crate) const MAX_MEMBERS: usize = MAX_NODES;

/// The longest chain of groups in groups, as the catalog follows it (`catalog::MAX_DEPTH`): a
/// group four levels below another is the last one followed.
pub(crate) const MAX_DEPTH: u8 = crate::catalog::MAX_DEPTH;

/// Whose groups a source holds.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Origin {
    /// A subscription's own: held to every bound above.
    Imported,
    /// doona's template: its groups hold every node of the catalog, which [`MAX_NODES`] bounds, and
    /// a few of its own groups, so they follow the node cap instead of [`MAX_MEMBERS`]: a full
    /// catalog gives `Proxy` more than [`MAX_MEMBERS`] members.
    Template,
}

/// Refuses a source of `origin` past any bound above.
pub(super) fn check(
    origin: Origin,
    groups: &[GroupDef],
    rules: &[RuleSpec],
) -> Result<(), ErrorCode> {
    let refused = Err(ErrorCode::ParseFailed);
    let capped = origin == Origin::Imported;
    if groups.len() > MAX_GROUPS || (capped && groups.iter().any(|g| g.members.len() > MAX_MEMBERS))
    {
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

/// Whether a value of `kind` is past [`MAX_VALUE`], or a `DST-PORT` value past
/// [`MAX_PORT_VALUE`]. A skipped kind's value is not kept, so it has no bound of its own.
fn too_long(kind: &Kind, value: &str) -> bool {
    let kept = match kind {
        Kind::Skipped { .. } => return false,
        Kind::DstPort => return value.len() > MAX_PORT_VALUE,
        Kind::Domain | Kind::DomainSuffix => value.strip_suffix('.').unwrap_or(value),
        _ => value,
    };
    kept.len() > MAX_VALUE
}
