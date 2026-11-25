//! The head rename publishes the revision, its entry name and the remaining draft pins together.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::fs::{self, File};
use std::io;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use super::draft::Draft;
use super::meters::RevisionMeters;
use super::publish::Notice;
use super::staged::Tree;
use super::{Capabilities, HonkConfig, HonkConfigError, Support};
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
    /// The draft was edited after the apply staged it. Nothing is committed and the draft keeps
    /// the edit.
    DraftChanged { prepared: u64, current: u64 },
    /// Another apply is preparing a staged revision.
    ApplyPending,
    /// A refused include leaves some declarations unknown.
    Incomplete,
    /// The report against the supplied facts holds a blocked rule or an unassessed input.
    NotRunnable(Box<Support>),
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
            Self::DraftChanged { .. } => "draft changed while it was being applied",
            Self::ApplyPending => "another apply is in progress",
            Self::Incomplete => "configuration has a refused include",
            Self::NotRunnable(_) => "configuration has a blocked rule or an unassessed input",
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
    meters: RevisionMeters,
}

#[derive(Clone, Default, Deserialize, Serialize)]
pub(super) struct Manifest {
    revisions: BTreeMap<u64, Revision>,
    pub drafts: Vec<Draft>,
    pub last_draft: u64,
    last_meter: u64,
    notice: Option<Notice>,
}

/// A revision tree copied for preparation. Head does not reach it until [`HonkStore::commit`].
pub(super) struct Staged {
    pub entry: PathBuf,
    /// The draft holds head's exact files: commit drops the draft and adds no revision.
    pub unchanged: bool,
    draft: u64,
    version: u64,
    base: Option<u64>,
    rev: u64,
    revision: Revision,
    last_meter: u64,
}

/// One writer owns the app-private config directory. Share this handle between editors.
/// Only the last edited draft (or newest preview if none has edits) survives restart.
/// Opening an already owned store fails without running recovery.
pub struct HonkStore {
    pub(super) root: PathBuf,
    pub(super) manifest: Manifest,
    staged: Option<u64>,
    /// Edits per draft in this process; an apply commits only the version it prepared.
    pub(super) versions: HashMap<u64, u64>,
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
            staged: None,
            versions: HashMap::new(),
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
        Ok(self.tree_entry(rev, revision))
    }

    /// Every retained revision with its entry, newest first.
    pub(super) fn revision_entries(&self) -> Vec<(u64, PathBuf)> {
        let revisions = self.manifest.revisions.iter().rev();
        revisions
            .map(|(&rev, revision)| (rev, self.tree_entry(rev, revision)))
            .collect()
    }

    fn tree_entry(&self, rev: u64, revision: &Revision) -> PathBuf {
        self.root
            .join(REVISIONS)
            .join(rev.to_string())
            .join(&revision.entry)
    }

    /// Each traffic row's meter in `rev`, by entry-relative file and row ID.
    pub fn revision_meters(&self, rev: u64) -> Result<&RevisionMeters, StoreError> {
        let revision = self.manifest.revisions.get(&rev);
        Ok(&revision.ok_or(StoreError::UnknownRevision)?.meters)
    }

    pub fn read_revision(&self, rev: u64) -> Result<HonkConfig, StoreError> {
        Ok(super::load(self.revision_entry(rev)?)?)
    }

    /// Recheck the exact bytes to be published. On conflict or validation failure the draft stays editable.
    /// `caps` are the ingress and asset facts the revision will run against; a report that is not
    /// [`Support::runnable`] refuses the apply and returns that report.
    /// The rename commits the revision. If the following directory sync fails, power loss
    /// can still roll it back; the old tree is retained until the new head is durable.
    pub fn apply(&mut self, id: u64, caps: &Capabilities<'_>) -> Result<u64, StoreError> {
        let staged = self.stage_revision(id, caps)?;
        self.commit(staged)
    }

    /// The refused start recorded by the publisher, until a start succeeds.
    pub fn notice(&self) -> Option<&Notice> {
        self.manifest.notice.as_ref()
    }

    pub(super) fn record_notice(&mut self, notice: Option<Notice>) -> io::Result<()> {
        let mut next = self.manifest.clone();
        next.notice = notice;
        self.publish(next)
    }

    /// Validate the draft and copy its bytes into the next revision slot, which cleanup keeps
    /// until [`Self::commit`] or [`Self::abandon`]. One slot is staged at a time, so head moves
    /// only through the staged commit.
    pub(super) fn stage_revision(
        &mut self,
        id: u64,
        caps: &Capabilities<'_>,
    ) -> Result<Staged, StoreError> {
        if self.staged.is_some() {
            return Err(StoreError::ApplyPending);
        }
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
        let support = config.support(caps);
        if !support.runnable() {
            return Err(StoreError::NotRunnable(Box::new(support)));
        }
        let mut last_meter = self.manifest.last_meter;
        let meters = draft.assign(
            &config.loaded,
            tree.entry.parent().unwrap(),
            &mut last_meter,
        );
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
        let unchanged = match head {
            Some(head) => self.holds(head, &draft.entry, &tree)?,
            None => false,
        };
        let (rev, entry) = if unchanged {
            let rev = head.unwrap();
            (rev, self.revision_entry(rev)?)
        } else {
            let rev = head.unwrap_or(0) + 1;
            let dir = self.root.join(REVISIONS).join(rev.to_string());
            tree.copy_to(&dir)?;
            (rev, dir.join(&draft.entry))
        };
        let staged = Staged {
            entry,
            unchanged,
            draft: id,
            version: self.version(id),
            base: head,
            rev,
            revision: Revision {
                entry: draft.entry.clone(),
                meters,
            },
            last_meter,
        };
        self.staged = Some(rev);
        Ok(staged)
    }

    /// Compare and swap: publish `staged` only if its draft still exists at the version that was
    /// staged. Staging is exclusive, so head is still the staged base.
    pub(super) fn commit(&mut self, staged: Staged) -> Result<u64, StoreError> {
        self.staged = None;
        debug_assert_eq!(self.head(), staged.base);
        self.find(staged.draft)?;
        let current = self.version(staged.draft);
        if current != staged.version {
            return Err(StoreError::DraftChanged {
                prepared: staged.version,
                current,
            });
        }
        let mut next = self.manifest.clone();
        if !staged.unchanged {
            next.last_meter = staged.last_meter;
            next.revisions.insert(staged.rev, staged.revision);
        }
        next.drafts.retain(|d| d.id != staged.draft);
        self.publish(next)?;
        Ok(staged.rev)
    }

    /// Whether revision `rev` holds exactly the files and links a copy of `tree` would write.
    fn holds(&self, rev: u64, entry: &Path, tree: &Tree) -> Result<bool, StoreError> {
        let revision = &self.manifest.revisions[&rev];
        if revision.entry != entry {
            return Ok(false);
        }
        let committed = Tree::read(&self.tree_entry(rev, revision))?;
        Ok(contents(&committed, &committed.links)? == contents(tree, &tree.kept)?)
    }

    fn version(&self, draft: u64) -> u64 {
        self.versions.get(&draft).copied().unwrap_or(0)
    }

    /// Release a staged slot that will not be committed; the next cleanup removes it.
    pub(super) fn abandon(&mut self) {
        self.staged = None;
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
            self.manifest.revisions.contains_key(&id) || self.staged == Some(id)
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

type Contents<'a> = (BTreeMap<&'a Path, &'a str>, BTreeMap<&'a Path, PathBuf>);

/// Entry-relative file texts and link targets of `tree`, with `links` as its links.
fn contents<'a>(tree: &'a Tree, links: &'a HashSet<PathBuf>) -> io::Result<Contents<'a>> {
    let root = tree.entry.parent().unwrap();
    let relative = |path: &'a PathBuf| path.strip_prefix(root).unwrap();
    let files = tree
        .files
        .iter()
        .map(|(path, text)| (relative(path), &**text));
    let links = links
        .iter()
        .map(|link| Ok((relative(link), fs::read_link(link)?)))
        .collect::<io::Result<_>>()?;
    Ok((files.collect(), links))
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
        let old = store.apply(first, &Capabilities::linked()).unwrap();
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
            matches!(store.apply(second, &Capabilities::linked()), Err(StoreError::Io(e)) if e.kind() == io::ErrorKind::Interrupted)
        );
        drop(store);
        let mut store = HonkStore::open(data.path()).unwrap();
        assert_eq!(store.head(), Some(old));
        let cfg = store.read_revision(old).unwrap();
        assert_eq!(&*cfg.sources()[0].content, "include { nested/child.dae }");
        assert_eq!(&*cfg.sources()[1].content, "routing { fallback: direct }");
        let new = store.apply(second, &Capabilities::linked()).unwrap();
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
