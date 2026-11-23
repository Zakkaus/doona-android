//! The rows of a source as `rules` lists them (rule-mode.md, R3), with stable ids. A row's id hashes the source, the
//! kind, the target and the values with the row's occurrence among identical rows before it, so
//! it stays across node changes and restarts, and two identical rows still get two ids. The
//! listing's revision hashes the ids in order.

use super::{Kind, RuleSet, RuleSpec, Skip, Target};
use crate::dto::{RuleRow, Rules, TargetKind};
use std::collections::HashMap;
use std::sync::Arc;
use uuid::Uuid;

/// The rows of one source, as compiled.
#[derive(Debug, Default)]
pub(crate) struct Listing {
    pub rows: Vec<Listed>,
    /// 48 bits of a hash of the row ids: `rulesRevision`.
    pub revision: u64,
}

/// One row: what the source says, and what of it was not compiled.
#[derive(Debug)]
pub(crate) struct Listed {
    /// Shared with the meters of the flows the row routes, so a flow copies no text.
    pub id: Arc<str>,
    pub kind: Kind,
    pub values: Vec<String>,
    pub target: Target,
    /// Why lines of the row were not compiled, and how many were not.
    pub skip: Option<Skip>,
    pub skipped: u32,
}

impl Listing {
    /// The rows of `specs`, taken from `source` (`template:cn-direct`), with the lines `compiled`
    /// skipped; none are counted when the source did not compile.
    pub fn new(source: &str, specs: &[RuleSpec], compiled: Option<&RuleSet>) -> Listing {
        let mut seen: HashMap<Uuid, u32> = HashMap::new();
        let rows: Vec<Listed> = specs
            .iter()
            .enumerate()
            .map(|(i, spec)| {
                let base = digest(source, spec);
                let occurrence = seen.entry(base).or_default();
                let id = Uuid::new_v5(&base, &occurrence.to_be_bytes());
                *occurrence += 1;
                let skipped = compiled.map_or(0, |set| set.rows()[i].skipped);
                Listed {
                    id: format!("r-{:016x}", first_bits(id, 8)).into(),
                    kind: spec.kind.clone(),
                    values: spec.values.clone(),
                    target: spec.target.clone(),
                    skip: skip(&spec.kind, skipped),
                    skipped,
                }
            })
            .collect();
        let ids: Vec<&str> = rows.iter().map(|r| &*r.id).collect();
        let revision = first_bits(Uuid::new_v5(&namespace(), ids.join("\n").as_bytes()), 6);
        Listing { rows, revision }
    }

    /// The values of every row, a row without values counting one.
    pub fn lines(&self) -> usize {
        self.rows.iter().map(|r| r.values.len().max(1)).sum()
    }

    /// `rules`' reply: at most `limit` rows from `offset`, none past the end.
    pub fn page(&self, offset: u32, limit: u32) -> Rules {
        let rows = self.rows.iter().enumerate();
        let rows = rows.skip(offset as usize).take(limit as usize);
        Rules {
            rules_revision: self.revision,
            total: count(self.rows.len()),
            rows: rows.map(|(i, row)| row.dto(count(i))).collect(),
        }
    }
}

impl Listed {
    fn dto(&self, index: u32) -> RuleRow {
        let (target, target_kind) = match &self.target {
            Target::Direct => ("direct", TargetKind::Direct),
            Target::Reject => ("reject", TargetKind::Reject),
            Target::Group(id) => (id.as_str(), TargetKind::Group),
        };
        RuleRow {
            id: self.id.to_string(),
            index,
            kind: kind_name(&self.kind).to_owned(),
            sample: self.values.first().cloned(),
            count: count(self.values.len()),
            target: target.to_owned(),
            target_kind,
            skipped: self.skip.map(|s| s.as_str().to_owned()),
            skipped_lines: self.skipped,
        }
    }
}

/// Why a row's lines were not compiled: its kind's reason, or a GeoIP code without data.
fn skip(kind: &Kind, skipped: u32) -> Option<Skip> {
    match kind {
        Kind::Skipped { reason, .. } => Some(*reason),
        Kind::GeoIp if skipped > 0 => Some(Skip::GeodataMissing),
        _ => None,
    }
}

/// The contract's name of a kind; a kind not compiled keeps the source's.
fn kind_name(kind: &Kind) -> &str {
    match kind {
        Kind::Domain => "Domain",
        Kind::DomainSuffix => "DomainSuffix",
        Kind::DomainKeyword => "DomainKeyword",
        Kind::IpCidr => "IpCidr",
        Kind::GeoIp => "GeoIp",
        Kind::DstPort => "DstPort",
        Kind::Network => "Network",
        Kind::Match => "Match",
        Kind::Skipped { name, .. } => name,
    }
}

fn namespace() -> Uuid {
    Uuid::new_v5(&Uuid::NAMESPACE_URL, b"doona:rules")
}

/// A hash of a row without its position: each field with its length, so no two rows encode the
/// same.
fn digest(source: &str, spec: &RuleSpec) -> Uuid {
    let target = match &spec.target {
        Target::Direct => "direct",
        Target::Reject => "reject",
        Target::Group(id) => id,
    };
    let fields = [source, kind_name(&spec.kind), target];
    let values = spec.values.iter().map(String::as_str);
    let mut bytes = Vec::new();
    for field in fields.into_iter().chain(values) {
        bytes.extend_from_slice(&(field.len() as u64).to_be_bytes());
        bytes.extend_from_slice(field.as_bytes());
    }
    Uuid::new_v5(&namespace(), &bytes)
}

/// The first `n` bytes of `id` as a number.
fn first_bits(id: Uuid, n: usize) -> u64 {
    let mut first = [0; 8];
    first[8 - n..].copy_from_slice(&id.as_bytes()[..n]);
    u64::from_be_bytes(first)
}

fn count(n: usize) -> u32 {
    u32::try_from(n).unwrap_or(u32::MAX)
}

#[cfg(test)]
mod tests;
