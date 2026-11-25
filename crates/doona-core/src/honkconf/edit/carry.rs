//! Which traffic rows keep their meter across one write of a file. A row keeps it only where the
//! write is known to have copied its bytes, or to have paused or resumed that very rule; a
//! recomputed row ID is never the evidence, since a twin's occurrence shifts when its sibling
//! changes.

use std::collections::BTreeMap;
use std::ops::Range;
use std::path::{Path, PathBuf};

use honk_config::parser::LoadedConfig;
use honk_config::parser::source_edit::source_indices;

use super::super::meters::Meters;
use super::super::staged::Tree;
use super::super::{HonkStore, StoreError};
use super::{EditError, File, List, RuleRow};

pub(in super::super) struct Carry {
    /// Old byte ranges copied unchanged, each with its new start.
    copies: Vec<(Range<usize>, usize)>,
    /// The rule a pause or resume rewrote: its old bytes, and the new bytes that hold it.
    kept: Option<(Range<usize>, Range<usize>)>,
}

impl Carry {
    /// `removed` became `inserted` bytes; everything else was copied.
    pub(super) fn splice(len: usize, removed: Range<usize>, inserted: usize) -> Self {
        let after = removed.start + inserted;
        Self {
            copies: vec![(0..removed.start, 0), (removed.end..len, after)],
            kept: None,
        }
    }

    /// Only the one span that differs between the texts is new. A replaced statement inside
    /// it starts a fresh meter, as does any row a raw edit touched.
    pub(in super::super) fn diff(old: &str, new: &str) -> Self {
        match super::diff::changed(old, new) {
            Some((removed, inserted)) => Self::splice(old.len(), removed, inserted.len()),
            None => Self::splice(old.len(), old.len()..old.len(), 0),
        }
    }

    /// `[..a]`, then `[b..c]`, then `[a..b]`, then `[c..]`.
    pub(super) fn swap(len: usize, a: usize, b: usize, c: usize) -> Self {
        Self {
            copies: vec![(0..a, 0), (b..c, a), (a..b, a + c - b), (c..len, c)],
            kept: None,
        }
    }

    pub(super) fn keep(mut self, old: Range<usize>, new: Range<usize>) -> Self {
        self.kept = Some((old, new));
        self
    }

    fn locate(&self, old: &Range<usize>) -> Option<Range<usize>> {
        let (from, to) = self
            .copies
            .iter()
            .find(|(from, _)| from.start <= old.start && old.end <= from.end)?;
        Some(old.start - from.start + to..old.end - from.start + to)
    }

    /// The meters of `new`'s rows that continue a row of `old` holding one in `meters`.
    pub(in super::super) fn follow(
        &self,
        old: &[RuleRow],
        new: &[RuleRow],
        meters: &Meters,
    ) -> Meters {
        let same = |a: &RuleRow, b: &RuleRow| a.list == b.list && a.fallback == b.fallback;
        let mut out = Meters::new();
        for row in old {
            let Some(meter) = meters.get(&row.id) else {
                continue;
            };
            let found = match &self.kept {
                Some((from, to)) if *from == row.bytes => new
                    .iter()
                    .find(|r| same(r, row) && to.start <= r.bytes.start && r.bytes.end <= to.end),
                _ => self.locate(&row.bytes).and_then(|at| {
                    new.iter()
                        .find(|r| same(r, row) && r.paused == row.paused && r.bytes == at)
                }),
            };
            if let Some(r) = found {
                out.insert(r.id.clone(), meter.clone());
            }
        }
        out
    }
}

impl HonkStore {
    /// Writes the last step's bytes over `file`, with the meters its rows carry through each
    /// step's rewrite.
    pub(super) fn write_steps(
        &mut self,
        draft: u64,
        absolute: &Path,
        root: &Path,
        file: &File,
        steps: &[(File, Carry)],
    ) -> Result<(), StoreError> {
        let content = &steps.last().unwrap().0.source.content;
        let meters =
            self.find(draft)?
                .carry(&file.source.path, &file.source.content, content, |meters| {
                    let mut rows = &file.rows;
                    let mut meters = meters.clone();
                    for (next, carry) in steps {
                        meters = carry.follow(rows, &next.rows, &meters);
                        rows = &next.rows;
                    }
                    meters
                });
        self.write_edit(draft, absolute, root, content, Some(meters))
    }
}

/// The traffic rows of one file of `tree`, or none when the tree does not parse for forms.
pub(in super::super) fn file_rows(tree: &Tree, path: &Path) -> Option<Vec<RuleRow>> {
    let raw = || EditError::OpenSource {
        path: PathBuf::new(),
        bytes: None,
    };
    let file = File::parse(tree, path, &raw).ok()?;
    Some(traffic(file.rows))
}

/// Every traffic row of a loaded tree in source order, by entry-relative file.
pub(in super::super) fn tree_rows(
    loaded: &LoadedConfig,
    root: &Path,
) -> BTreeMap<PathBuf, Vec<RuleRow>> {
    let Ok((_, index)) = source_indices(&loaded.sources) else {
        return BTreeMap::new();
    };
    let mut files = BTreeMap::new();
    for (owner, source) in loaded.sources.iter().enumerate() {
        let mut source = source.clone();
        source.path = source.path.strip_prefix(root).unwrap().into();
        if let Some((rows, _)) = super::source_rows(&source, &loaded.config, index.clone(), owner) {
            files.insert(source.path, traffic(rows));
        }
    }
    files
}

fn traffic(mut rows: Vec<RuleRow>) -> Vec<RuleRow> {
    rows.retain(|r| r.list == List::Traffic);
    rows.sort_unstable_by_key(|r| r.bytes.start);
    rows
}
