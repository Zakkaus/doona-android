//! Usage is not an edit address: each revision maps its traffic rows to meter IDs, committed
//! with the head rename. A row keeps its meter only through an edit that copied, moved, paused
//! or resumed its bytes ([`edit::carry`](super::edit::carry)); a changed or ambiguous row starts
//! a fresh one. Flows already open keep charging the meter they started on.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use honk_config::parser::LoadedConfig;
use uuid::Uuid;

use super::draft::Draft;
use super::edit::carry;
use super::staged::Tree;

/// Row ID to meter ID.
pub type Meters = BTreeMap<String, String>;

/// A revision's [`Meters`] by entry-relative file.
pub type RevisionMeters = BTreeMap<PathBuf, Meters>;

/// A draft's carried meters by file, then by the digest of the bytes they belong to. An edit
/// records the new bytes' meters beside the old before it replaces the file, so either bytes a
/// crash leaves find theirs.
pub(super) type DraftMeters = BTreeMap<PathBuf, BTreeMap<String, Meters>>;

pub(super) struct Carried {
    path: PathBuf,
    from: String,
    to: String,
    meters: Meters,
}

fn digest(text: &str) -> String {
    Uuid::new_v5(&Uuid::NAMESPACE_OID, text.as_bytes())
        .simple()
        .to_string()
}

/// A draft of a revision's bytes starts with that revision's meters.
pub(super) fn staged(tree: &Tree, meters: &RevisionMeters) -> DraftMeters {
    let root = tree.entry.parent().unwrap();
    meters
        .iter()
        .filter_map(|(path, meters)| {
            let text = tree.files.get(&root.join(path))?;
            Some((
                path.clone(),
                BTreeMap::from([(digest(text), meters.clone())]),
            ))
        })
        .collect()
}

impl Draft {
    /// The meters `follow` carries from `old` to `new` bytes of `path`.
    pub(super) fn carry(
        &self,
        path: &Path,
        old: &str,
        new: &str,
        follow: impl FnOnce(&Meters) -> Meters,
    ) -> Carried {
        let from = digest(old);
        let meters = self
            .meters
            .get(path)
            .and_then(|states| states.get(&from))
            .map(follow)
            .unwrap_or_default();
        Carried {
            path: path.into(),
            from,
            to: digest(new),
            meters,
        }
    }

    pub(super) fn record(&mut self, carried: Carried) {
        let states = self.meters.entry(carried.path).or_default();
        states.retain(|digest, _| *digest == carried.from);
        states.insert(carried.to, carried.meters);
    }

    /// Each traffic row's meter in a revision of `loaded`: carried, or the next after `last`.
    pub(super) fn assign(
        &self,
        loaded: &LoadedConfig,
        root: &Path,
        last: &mut u64,
    ) -> RevisionMeters {
        let digests: BTreeMap<_, _> = loaded
            .sources
            .iter()
            .map(|s| (s.path.strip_prefix(root).unwrap(), digest(&s.content)))
            .collect();
        let mut out = RevisionMeters::new();
        for (path, rows) in carry::tree_rows(loaded, root) {
            let carried = self
                .meters
                .get(&path)
                .and_then(|states| states.get(&digests[path.as_path()]));
            let meters = rows
                .into_iter()
                .map(|row| {
                    let meter = carried.and_then(|m| m.get(&row.id)).cloned();
                    let meter = meter.unwrap_or_else(|| {
                        *last += 1;
                        format!("m-{last}")
                    });
                    (row.id, meter)
                })
                .collect();
            out.insert(path, meters);
        }
        out
    }
}

#[cfg(test)]
mod tests;
