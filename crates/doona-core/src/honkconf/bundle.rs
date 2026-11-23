//! Private export payloads for a fresh directory. The manifest is out of band: writing it
//! into the source tree could overwrite an authored file or change a glob's matches.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use glob::Pattern;
use honk_config::parser::SourceSnapshot;

use super::HonkStore;
use super::staged::{Parsed, Tree, confined, include_patterns, is_honk_source};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ExportMode {
    Archive,
    /// The destination directory path must not contain glob metacharacters.
    Portable,
}

/// Only source indices, UTF-8 offsets and effective hosts-source indices leave the payload.
#[derive(Debug, Eq, PartialEq)]
pub enum ExportIssue {
    IncludeEscape {
        source: usize,
        offset: usize,
    },
    IncludeExpansion {
        source: usize,
    },
    HostsEscape {
        index: usize,
    },
    /// Stock honk searches its data directories and working directory, not the entry directory.
    RelativeHosts {
        index: usize,
    },
    MissingHosts {
        index: usize,
    },
}

#[derive(Debug, Eq, PartialEq)]
pub enum ExportError {
    UnknownRevision,
    UnreadableRevision,
    NotPortable(Vec<ExportIssue>),
}

pub struct BundleFile {
    pub path: PathBuf,
    pub content: Arc<str>,
}

pub enum HonkBundle {
    Single(BundleFile),
    Tree {
        /// Entry first, then includes in honk merge order. Paths are entry-relative.
        manifest: Vec<BundleFile>,
        /// Hosts files not already in the manifest, in declaration order.
        companions: Vec<BundleFile>,
        issues: Vec<ExportIssue>,
    },
}

impl HonkStore {
    /// Return the selected revision's bytes and disclose unresolved source dependencies.
    pub fn export(&self, rev: u64, mode: ExportMode) -> Result<HonkBundle, ExportError> {
        let entry = self
            .revision_entry(rev)
            .map_err(|_| ExportError::UnknownRevision)?;
        let mut tree = Tree::read(&entry).map_err(|_| ExportError::UnreadableRevision)?;
        let parsed = tree.parse().map_err(|_| ExportError::UnreadableRevision)?;
        let entry = &parsed
            .prepared
            .get(tree.entry.as_path())
            .ok_or(ExportError::UnreadableRevision)?
            .doc;
        if parsed.loaded.config.dns.hosts.is_empty()
            && entry.sections().all(|s| s.header() != "include")
        {
            return Ok(HonkBundle::Single(BundleFile {
                path: tree.entry.file_name().unwrap().into(),
                content: tree.files.remove(&tree.entry).unwrap(),
            }));
        }
        let mut issues = parsed.include_issues(&tree)?;
        let config = parsed.loaded.config;
        let mut hosts = tree
            .read_companions(&config, None)
            .map_err(|_| ExportError::UnreadableRevision)?;
        let root = tree.entry.parent().unwrap();
        let manifest = parsed
            .loaded
            .sources
            .into_iter()
            .map(|source| {
                hosts.remove(&source.path);
                BundleFile {
                    path: source.path.strip_prefix(root).unwrap().into(),
                    content: tree.files.remove(&source.path).unwrap(),
                }
            })
            .collect();
        let mut companions = Vec::with_capacity(hosts.len());
        for (index, name) in config.dns.hosts.iter().enumerate() {
            let Ok(path) = confined(root, Path::new(name), &tree.links) else {
                issues.push(ExportIssue::HostsEscape { index });
                continue;
            };
            issues.push(if root.join(name).is_file() {
                ExportIssue::RelativeHosts { index }
            } else {
                ExportIssue::MissingHosts { index }
            });
            if let Some(content) = hosts.remove(&path) {
                companions.push(BundleFile {
                    path: path.strip_prefix(root).unwrap().into(),
                    content,
                });
            }
        }
        if mode == ExportMode::Portable && !issues.is_empty() {
            return Err(ExportError::NotPortable(issues));
        }
        Ok(HonkBundle::Tree {
            manifest,
            companions,
            issues,
        })
    }
}

impl Parsed<'_> {
    fn include_issues(&self, tree: &Tree) -> Result<Vec<ExportIssue>, ExportError> {
        let root = tree.entry.parent().unwrap();
        let sources = &self.loaded.sources;
        let mut issues = Vec::new();
        for (i, source) in sources.iter().enumerate() {
            let doc = &self
                .prepared
                .get(source.path.as_path())
                .ok_or(ExportError::UnreadableRevision)?
                .doc;
            let mut expected = sources.iter().filter(|s| s.parent == Some(i));
            let mut identical = true;
            let mut escaped = false;
            for (pattern, span) in include_patterns(doc) {
                if include_can_escape(pattern) {
                    escaped = true;
                    issues.push(ExportIssue::IncludeEscape {
                        source: i,
                        offset: span.start,
                    });
                } else if !escaped && identical {
                    identical = matches_children(root, pattern, &mut expected)?;
                }
            }
            if !escaped && (!identical || expected.next().is_some()) {
                issues.push(ExportIssue::IncludeExpansion { source: i });
            }
        }
        Ok(issues)
    }
}

// glob(3)-style disk traversal synthesizes `.` and `..` for dot-prefixed patterns.
// The staged map has neither; never enumerate a possible parent to check the expansion.
fn include_can_escape(pattern: &str) -> bool {
    let mut depth: i32 = 0;
    for part in pattern.split('/').filter(|s| !s.is_empty()) {
        let dot = part
            .starts_with('.')
            .then(|| Pattern::new(part).ok())
            .flatten();
        match dot {
            Some(p) if p.matches("..") => {
                depth -= 1;
                if depth < 0 {
                    return true;
                }
            }
            Some(p) if p.matches(".") => (),
            _ => depth += 1,
        }
    }
    false
}

// The staged loader normalizes `..` lexically. Stock honk expands the spelling on disk
// before canonicalizing: e.g. `absent/../child.dae` does not match `child.dae` there.
fn matches_children<'a>(
    root: &Path,
    pattern: &str,
    expected: &mut impl Iterator<Item = &'a SourceSnapshot>,
) -> Result<bool, ExportError> {
    let mut glob = Pattern::escape(root.to_str().ok_or(ExportError::UnreadableRevision)?);
    for part in pattern.split('/') {
        glob.push('/');
        glob.push_str(if part == "**" { "*" } else { part });
    }
    let Ok(paths) = glob::glob(&glob) else {
        return Ok(false);
    };
    // glob yields matches in lexical order, before honk canonicalizes their paths.
    for path in paths {
        let path = path.map_err(|_| ExportError::UnreadableRevision)?;
        if !is_honk_source(&path) || path.is_dir() {
            continue;
        }
        let path = path
            .canonicalize()
            .map_err(|_| ExportError::UnreadableRevision)?;
        if expected.next().is_none_or(|s| s.path != path) {
            return Ok(false);
        }
    }
    Ok(true)
}
