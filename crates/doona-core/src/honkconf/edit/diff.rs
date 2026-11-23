use std::collections::HashMap;
use std::ops::Range;
use std::path::PathBuf;

use super::super::staged::Tree;
use super::{HonkStore, StoreError};

/// Half-open UTF-8 byte ranges in the single-file template and the staged tree.
/// `None` distinguishes a newly added (possibly empty) file from an insertion in the entry.
#[derive(Debug, PartialEq, Eq)]
pub struct SourceChange {
    pub path: PathBuf,
    pub template: Option<Range<usize>>,
    pub draft: Range<usize>,
}

impl HonkStore {
    pub fn template_diff(
        &self,
        draft: u64,
        template: &str,
    ) -> Result<Vec<SourceChange>, StoreError> {
        let mut tree = Tree::read(&self.draft_entry(self.find(draft)?))?;
        tree.capture_companions(None)?;
        let root = tree.entry.parent().unwrap();
        let mut changes = Vec::new();
        for (path, text) in tree.files {
            let relative = path.strip_prefix(root).unwrap();
            if path == tree.entry {
                diff(template, &text, |old, new| {
                    changes.push(SourceChange {
                        path: relative.into(),
                        template: Some(old),
                        draft: new,
                    })
                });
            } else {
                changes.push(SourceChange {
                    path: relative.into(),
                    template: None,
                    draft: 0..text.len(),
                });
            }
        }
        changes.sort_by(|a, b| a.path.cmp(&b.path).then(a.draft.start.cmp(&b.draft.start)));
        Ok(changes)
    }
}

pub(super) fn changed(old: &str, new: &str) -> Option<(Range<usize>, Range<usize>)> {
    if old == new {
        return None;
    }
    let prefix = old
        .chars()
        .zip(new.chars())
        .take_while(|(a, b)| a == b)
        .map(|(a, _)| a.len_utf8())
        .sum();
    let suffix: usize = old[prefix..]
        .chars()
        .rev()
        .zip(new[prefix..].chars().rev())
        .take_while(|(a, b)| a == b)
        .map(|(a, _)| a.len_utf8())
        .sum();
    Some((prefix..old.len() - suffix, prefix..new.len() - suffix))
}

// Anchor on equal physical lines in order; trim equal codepoints at both ends of each hunk.
// No quadratic edit-distance table on an 8 MiB draft.
fn diff(old: &str, new: &str, mut emit: impl FnMut(Range<usize>, Range<usize>)) {
    let Some((old_range, new_range)) = changed(old, new) else {
        return;
    };
    let mut places = HashMap::<&str, Vec<usize>>::new();
    let mut new_offset = new_range.start;
    for line in new[new_range.clone()].split_inclusive('\n') {
        places.entry(line).or_default().push(new_offset);
        new_offset += line.len();
    }
    let (mut old_start, mut new_start) = (old_range.start, new_range.start);
    let mut hunk = |old_span: Range<usize>, new_span: Range<usize>| {
        if let Some((old_delta, new_delta)) =
            changed(&old[old_span.clone()], &new[new_span.clone()])
        {
            emit(
                old_span.start + old_delta.start..old_span.start + old_delta.end,
                new_span.start + new_delta.start..new_span.start + new_delta.end,
            );
        }
    };
    let mut old_offset = old_start;
    for line in old[old_range.clone()].split_inclusive('\n') {
        if let Some(&new_match) = places
            .get(line)
            .and_then(|offsets| offsets.get(offsets.partition_point(|p| *p < new_start)))
        {
            hunk(old_start..old_offset, new_start..new_match);
            old_start = old_offset + line.len();
            new_start = new_match + line.len();
        }
        old_offset += line.len();
    }
    hunk(old_start..old_range.end, new_start..new_range.end);
}
