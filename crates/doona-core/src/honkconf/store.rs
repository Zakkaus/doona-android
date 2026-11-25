//! The head rename publishes the revision, its entry name and the remaining draft pins together.

use std::collections::BTreeMap;
use std::fs::{self, File};
use std::io;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use super::draft::Draft;
use super::staged::Tree;
use super::{HonkConfig, HonkConfigError};
use crate::state::{sync_dir, write_synced};

const CONFIG: &str = "config";
const HEAD: &str = "head";
const HEAD_TEMP: &str = "head.tmp";
pub(super) const STAGING: &str = "staging";
const REVISIONS: &str = "revisions";
const KEEP_REVISIONS: u64 = 10;

/// Storage and source failures that the configuration editor can distinguish.
#[derive(Debug)]
pub enum StoreError {
    /// File I/O or exclusive store ownership failed.
    Io(io::Error),
    /// Source admission or the staged text budget failed.
    Config(HonkConfigError),
    /// Another draft has already advanced the committed revision.
    Conflict {
        base: Option<u64>,
        head: Option<u64>,
    },
    /// A refused include leaves some declarations unknown.
    Incomplete,
    /// The edit does not identify a confined configuration file.
    InvalidEditPath,
    /// The imported entry is itself a symlink; its path is private editor data.
    ImportSymlink(PathBuf),
    /// No such file exists in the selected source tree.
    MissingFile,
    /// The draft was discarded or applied.
    UnknownDraft,
    /// The revision does not exist or has expired.
    UnknownRevision,
}

impl std::fmt::Display for StoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Io(_) => "configuration storage I/O failed",
            Self::Config(e) => return e.fmt(f),
            Self::Conflict { .. } => "draft base is not the current revision",
            Self::Incomplete => "configuration has a refused include",
            Self::InvalidEditPath => "invalid draft file path",
            Self::ImportSymlink(_) => "configuration entry is a symlink",
            Self::MissingFile => "configuration file not found",
            Self::UnknownDraft => "unknown draft",
            Self::UnknownRevision => "unknown revision",
        })
    }
}

impl std::error::Error for StoreError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(e) => Some(e),
            Self::Config(e) => Some(e),
            _ => None,
        }
    }
}

impl From<io::Error> for StoreError {
    fn from(e: io::Error) -> Self {
        Self::Io(e)
    }
}

impl From<HonkConfigError> for StoreError {
    fn from(e: HonkConfigError) -> Self {
        Self::Config(e)
    }
}

#[derive(Clone, Deserialize, Serialize)]
struct Revision {
    entry: PathBuf,
}

#[derive(Clone, Default, Deserialize, Serialize)]
pub(super) struct Manifest {
    revisions: BTreeMap<u64, Revision>,
    pub drafts: Vec<Draft>,
    pub last_draft: u64,
}

/// One writer owns the app-private config directory. Share this handle between editors.
/// Only the last edited draft (or newest preview if none has edits) survives restart.
/// Opening an already owned store fails without running recovery.
pub struct HonkStore {
    pub(super) root: PathBuf,
    pub(super) manifest: Manifest,
    _lock: File,
    #[cfg(test)]
    before_head_rename: fn(&Path) -> io::Result<()>,
}

impl HonkStore {
    pub fn open(data_dir: &Path) -> Result<Self, StoreError> {
        let root = data_dir.join(CONFIG);
        fs::create_dir_all(&root)?;
        let lock = File::open(&root)?;
        lock.try_lock().map_err(io::Error::from)?;
        sync_dir(data_dir)?;
        for dir in [STAGING, REVISIONS] {
            fs::create_dir_all(root.join(dir))?;
        }
        sync_dir(&root)?;
        let manifest = match fs::read(root.join(HEAD)) {
            Ok(bytes) => serde_json::from_slice(&bytes).map_err(io::Error::other)?,
            Err(e) if e.kind() == io::ErrorKind::NotFound => Manifest::default(),
            Err(e) => return Err(e.into()),
        };
        let mut store = Self {
            root,
            manifest,
            _lock: lock,
            #[cfg(test)]
            before_head_rename: |_| Ok(()),
        };
        if store.manifest.drafts.len() > 1 {
            let mut next = store.manifest.clone();
            next.drafts.drain(..next.drafts.len() - 1);
            if let Err(e) = store.publish(next) {
                log::warn!("honk store: draft cleanup deferred ({:?})", e.kind());
            }
        } else {
            store.collect();
        }
        Ok(store)
    }

    pub fn head(&self) -> Option<u64> {
        self.manifest.revisions.last_key_value().map(|(&id, _)| id)
    }

    pub fn revisions(&self) -> impl Iterator<Item = u64> + '_ {
        self.manifest.revisions.keys().copied()
    }

    pub(super) fn revision_entry(&self, rev: u64) -> Result<PathBuf, StoreError> {
        let revision = self
            .manifest
            .revisions
            .get(&rev)
            .ok_or(StoreError::UnknownRevision)?;
        Ok(self
            .root
            .join(REVISIONS)
            .join(rev.to_string())
            .join(&revision.entry))
    }

    pub fn read_revision(&self, rev: u64) -> Result<HonkConfig, StoreError> {
        Ok(super::load(self.revision_entry(rev)?)?)
    }

    /// Recheck the exact bytes to be published. On conflict or validation failure the draft stays editable.
    /// The rename commits the revision. If the following directory sync fails, power loss
    /// can still roll it back; the old tree is retained until the new head is durable.
    pub fn apply(&mut self, id: u64) -> Result<u64, StoreError> {
        let draft = self.find(id)?;
        let head = self.head();
        if draft.base != head {
            return Err(StoreError::Conflict {
                base: draft.base,
                head,
            });
        }
        let mut tree = Tree::read(&self.draft_entry(draft))?;
        let (config, mut files) = tree.load()?;
        if !config.complete() {
            return Err(StoreError::Incomplete);
        }
        // A complete tree reaches symlinks only through hosts; keep reporting those escapes.
        tree.kept = tree.hosts_links(config.config());
        files.extend(
            config
                .loaded
                .sources
                .into_iter()
                .map(|s| (s.path, s.content)),
        );
        tree.files = files;
        let rev = head.unwrap_or(0) + 1;
        let dir = self.root.join(REVISIONS).join(rev.to_string());
        tree.copy_to(&dir)?;
        let mut next = self.manifest.clone();
        next.revisions.insert(
            rev,
            Revision {
                entry: draft.entry.clone(),
            },
        );
        next.drafts.retain(|d| d.id != id);
        self.publish(next)?;
        Ok(rev)
    }

    pub(super) fn publish(&mut self, mut next: Manifest) -> io::Result<()> {
        if let Some((&head, _)) = next.revisions.last_key_value() {
            next.revisions.retain(|rev, _| {
                head - rev < KEEP_REVISIONS || next.drafts.iter().any(|d| d.base == Some(*rev))
            });
        }
        let temp = self.root.join(STAGING).join(HEAD_TEMP);
        let head = self.root.join(HEAD);
        write_synced(&temp, &serde_json::to_vec(&next).map_err(io::Error::other)?)?;
        #[cfg(test)]
        (self.before_head_rename)(&temp)?;
        fs::rename(temp, head)?;
        self.manifest = next;
        match sync_dir(&self.root) {
            Ok(()) => self.collect(),
            Err(e) => log::warn!("honk store: head not synced ({:?})", e.kind()),
        }
        Ok(())
    }

    fn collect(&self) {
        sweep(&self.root.join(STAGING), |id| {
            self.manifest.drafts.iter().any(|d| d.id == id)
        });
        sweep(&self.root.join(REVISIONS), |id| {
            self.manifest.revisions.contains_key(&id)
        });
    }
}

fn sweep(dir: &Path, keep: impl Fn(u64) -> bool) {
    let result = fs::read_dir(dir).and_then(|mut items| {
        items.try_for_each(|item| {
            let item = item?;
            let id = item.file_name().to_str().and_then(|s| s.parse().ok());
            if id.is_some_and(&keep) {
                Ok(())
            } else if item.file_type()?.is_dir() {
                fs::remove_dir_all(item.path())
            } else {
                fs::remove_file(item.path())
            }
        })
    });
    if let Err(e) = result {
        log::warn!("honk store: cleanup deferred ({:?})", e.kind());
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::TempDir;

    #[test]
    fn interruption_before_head_rename_preserves_old_revision_and_new_draft() {
        let data = TempDir::new("honk-head-rename");
        let mut store = HonkStore::open(data.path()).unwrap();
        let first = store.draft(None).unwrap();
        store
            .edit(
                first,
                Path::new("config.dae"),
                "include { nested/child.dae }",
            )
            .unwrap();
        store
            .edit(
                first,
                Path::new("nested/child.dae"),
                "routing { fallback: direct }",
            )
            .unwrap();
        let old = store.apply(first).unwrap();
        let second = store.draft(None).unwrap();
        store
            .edit(
                second,
                Path::new("config.dae"),
                "# new\ninclude { nested/child.dae }",
            )
            .unwrap();
        store
            .edit(
                second,
                Path::new("nested/child.dae"),
                "routing { fallback: block }",
            )
            .unwrap();
        store.before_head_rename = |temp| {
            let pending: Manifest = serde_json::from_slice(&fs::read(temp)?).unwrap();
            assert_eq!(pending.revisions.last_key_value().unwrap().0, &2);
            let root = temp.parent().unwrap().parent().unwrap();
            let tree = root.join("revisions/2");
            assert_eq!(
                fs::read(tree.join("config.dae"))?,
                b"# new\ninclude { nested/child.dae }"
            );
            assert_eq!(
                fs::read(tree.join("nested/child.dae"))?,
                b"routing { fallback: block }"
            );
            let committed: Manifest = serde_json::from_slice(&fs::read(root.join(HEAD))?).unwrap();
            assert_eq!(committed.revisions.last_key_value().unwrap().0, &1);
            Err(io::ErrorKind::Interrupted.into())
        };
        assert!(
            matches!(store.apply(second), Err(StoreError::Io(e)) if e.kind() == io::ErrorKind::Interrupted)
        );
        drop(store);
        let mut store = HonkStore::open(data.path()).unwrap();
        assert_eq!(store.head(), Some(old));
        let cfg = store.read_revision(old).unwrap();
        assert_eq!(&*cfg.sources()[0].content, "include { nested/child.dae }");
        assert_eq!(&*cfg.sources()[1].content, "routing { fallback: direct }");
        let new = store.apply(second).unwrap();
        drop(store);
        let store = HonkStore::open(data.path()).unwrap();
        assert_eq!(store.head(), Some(new));
        let cfg = store.read_revision(new).unwrap();
        assert_eq!(
            &*cfg.sources()[0].content,
            "# new\ninclude { nested/child.dae }"
        );
        assert_eq!(&*cfg.sources()[1].content, "routing { fallback: block }");
    }
}
