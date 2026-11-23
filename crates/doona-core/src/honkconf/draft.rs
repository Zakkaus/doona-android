use std::collections::BTreeSet;
use std::fs;
use std::io;
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;

use serde::{Deserialize, Serialize};

use super::staged::{LIMITS, Tree};
use super::store::STAGING;
use super::{HonkConfig, HonkStore, StoreError};
use crate::state::{sync_dir, write_synced};

const ENTRY: &str = "config.dae";
const EDIT_TEMP: &str = "edit.tmp";

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Draft {
    pub id: u64,
    pub base: Option<u64>,
    pub entry: PathBuf,
    edited: bool,
}

// The slot is not reachable from head. An earlier failed publish can have left it behind.
fn fresh_dir(dir: &Path) -> io::Result<()> {
    match fs::create_dir(dir) {
        Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {
            fs::remove_dir_all(dir)?;
            fs::create_dir(dir)?;
        }
        result => result?,
    }
    sync_dir(dir.parent().unwrap())
}

fn parents<'a>(file: &'a Path, root: &'a Path) -> impl Iterator<Item = &'a Path> {
    file.ancestors()
        .skip(1)
        .take_while(move |p| p.starts_with(root))
}

impl Tree {
    pub(super) fn copy_to(&self, dir: &Path) -> io::Result<()> {
        fresh_dir(dir)?;
        let root = self.entry.parent().unwrap();
        let mut directories = BTreeSet::new();
        for (path, text) in &self.files {
            let dest = dir.join(path.strip_prefix(root).unwrap());
            fs::create_dir_all(dest.parent().unwrap())?;
            write_synced(&dest, text.as_bytes())?;
            directories.extend(parents(path, root));
        }
        for parent in directories.into_iter().rev() {
            sync_dir(&dir.join(parent.strip_prefix(root).unwrap()))?;
        }
        Ok(())
    }

    fn edit_path(&self, relative: &Path) -> Result<PathBuf, StoreError> {
        if relative.as_os_str().is_empty()
            || !relative
                .components()
                .all(|c| matches!(c, Component::Normal(_)))
        {
            return Err(StoreError::InvalidEditPath);
        }
        super::staged::confined(self.entry.parent().unwrap(), relative, &self.links)
            .map_err(|_| StoreError::InvalidEditPath)
    }
}

impl HonkStore {
    pub fn current_draft(&self) -> Option<&Draft> {
        self.manifest.drafts.last()
    }

    pub(super) fn find(&self, id: u64) -> Result<&Draft, StoreError> {
        self.manifest
            .drafts
            .iter()
            .find(|d| d.id == id)
            .ok_or(StoreError::UnknownDraft)
    }

    pub(super) fn draft_entry(&self, draft: &Draft) -> PathBuf {
        self.root
            .join(STAGING)
            .join(draft.id.to_string())
            .join(&draft.entry)
    }

    /// Copy a historical revision for restore, or head for editing. With no head, create an empty `config.dae`.
    pub fn draft(&mut self, from: Option<u64>) -> Result<u64, StoreError> {
        match from.or(self.head()) {
            Some(rev) => self.import(&self.revision_entry(rev)?),
            None => self.stage(None),
        }
    }

    /// Import the entry and its sources from its containing directory. The caller must
    /// keep that directory unchanged until import returns. Invalid source remains editable.
    pub fn import(&mut self, entry: &Path) -> Result<u64, StoreError> {
        if entry.is_symlink() {
            return Err(StoreError::ImportSymlink(entry.into()));
        }
        let mut tree = Tree::read(entry)?;
        tree.capture_companions(None)?;
        self.stage(Some(tree))
    }

    fn stage(&mut self, tree: Option<Tree>) -> Result<u64, StoreError> {
        let mut next = self.manifest.clone();
        next.last_draft += 1;
        let id = next.last_draft;
        let draft = Draft {
            id,
            base: self.head(),
            edited: false,
            entry: tree
                .as_ref()
                .map_or_else(|| ENTRY.into(), |t| t.entry.file_name().unwrap().into()),
        };
        let entry = self.draft_entry(&draft);
        let dir = entry.parent().unwrap();
        if let Some(tree) = tree {
            tree.copy_to(dir)?;
        } else {
            fresh_dir(dir)?;
            write_synced(&entry, b"")?;
            sync_dir(dir)?;
        }
        let preview_end = next.drafts.partition_point(|d| !d.edited);
        next.drafts.insert(preview_end, draft);
        self.publish(next)?;
        Ok(id)
    }

    pub fn check(&self, id: u64) -> Result<HonkConfig, StoreError> {
        Ok(super::load(self.draft_entry(self.find(id)?))?)
    }

    /// Read original text even when this draft does not parse.
    pub fn text(&self, id: u64, relative: &Path) -> Result<Arc<str>, StoreError> {
        let mut tree = Tree::read(&self.draft_entry(self.find(id)?))?;
        let path = tree.edit_path(relative)?;
        if !tree.files.contains_key(&path) {
            tree.capture_companions(None)?;
        }
        tree.files.remove(&path).ok_or(StoreError::MissingFile)
    }

    /// Replace or add one file. An over-budget edit leaves the previous bytes intact.
    pub fn edit(&mut self, id: u64, relative: &Path, text: &str) -> Result<(), StoreError> {
        let mut tree = Tree::read(&self.draft_entry(self.find(id)?))?;
        let path = tree.edit_path(relative)?;
        if path.extension().is_some_and(|ext| ext == "dae") {
            tree.files.remove(&path);
            let used = tree.files.values().map(|s| s.len()).sum::<usize>();
            let value =
                super::staged::bounded_text(text, LIMITS.max_bytes - used, tree.files.len())?;
            tree.files.insert(path.clone(), value);
        }
        tree.capture_companions(Some((&path, text)))?;
        if !tree.files.contains_key(&path) {
            return Err(StoreError::InvalidEditPath);
        }
        fs::create_dir_all(path.parent().unwrap())?;
        // Select before replacing bytes: a crash must not collect a draft containing the edit.
        if self.current_draft().is_none_or(|d| d.id != id || !d.edited) {
            let mut next = self.manifest.clone();
            let pos = next.drafts.iter().position(|d| d.id == id).unwrap();
            let mut draft = next.drafts.remove(pos);
            draft.edited = true;
            next.drafts.push(draft);
            self.publish(next)?;
        }
        let temp = self.root.join(STAGING).join(EDIT_TEMP);
        write_synced(&temp, text.as_bytes())?;
        fs::rename(temp, &path)?;
        parents(&path, tree.entry.parent().unwrap()).try_for_each(sync_dir)?;
        Ok(())
    }

    pub fn discard(&mut self, id: u64) -> Result<(), StoreError> {
        self.find(id)?;
        let mut next = self.manifest.clone();
        next.drafts.retain(|d| d.id != id);
        Ok(self.publish(next)?)
    }
}
