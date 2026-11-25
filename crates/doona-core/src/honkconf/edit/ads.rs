//! The 封鎖廣告 switch: one DNS request statement placed first in the entry file, where it
//! beats every other request rule of the tree. Off pauses that statement, on resumes or
//! inserts it. A copy the form did not place stays the user's: the form reports where it is
//! and never writes a second one.

use std::ops::Range;
use std::path::{Path, PathBuf};

use honk_config::dns::{DnsCond, DnsDomainMatcher, DnsRequestAction, DnsRequestRule};
use honk_config::parser::cursor::{Document, Segment};
use honk_config::parser::parse_dae_config_with_detailed_diagnostics;
use honk_config::parser::source_edit::source_indices;
use honk_config::parser::{LoadedConfig, SourceSnapshot};

use super::carry::Carry;
use super::{Edit, EditError, File, List, blanks, line_start, markers};
use crate::honkconf::staged::Tree;
use crate::honkconf::{GeoCatalog, HonkConfigError, HonkStore, Label, StoreError};

pub const STATEMENT: &str = "qname(geosite:category-ads-all) -> reject";
const LABEL: &str = "category-ads-all";
const NEST: [&str; 3] = ["dns", "routing", "request"];

#[derive(Debug, PartialEq, Eq)]
pub enum AdBlock {
    On,
    /// Paused in place: resuming it makes it first again.
    Off,
    Absent,
    /// In an include, not first, or more than once. The form only opens this source.
    Elsewhere {
        path: PathBuf,
        bytes: Range<usize>,
    },
}

#[derive(Debug)]
pub enum AdBlockError {
    Edit(EditError),
    /// The installed geosite data lacks the category, so an active statement would block
    /// the configuration.
    Label(Label),
}

impl From<EditError> for AdBlockError {
    fn from(error: EditError) -> Self {
        Self::Edit(error)
    }
}

/// One copy of the statement, active or paused, in source `source` of the tree.
struct Copy {
    source: usize,
    bytes: Range<usize>,
    paused: bool,
}

struct Scan {
    loaded: LoadedConfig,
    copies: Vec<Copy>,
    /// The first active request rule of the tree, as (source, start byte).
    first: Option<(usize, usize)>,
}

fn is_ad(rule: &DnsRequestRule) -> bool {
    let [
        DnsCond::Qname {
            not: false,
            matchers,
        },
    ] = rule.conditions.as_slice()
    else {
        return false;
    };
    rule.action == DnsRequestAction::Reject
        && matches!(matchers.as_slice(), [DnsDomainMatcher::Geosite(label)] if label.eq_ignore_ascii_case(LABEL))
}

/// The paused runs of `source`, a source honk has loaded, that hold the statement, read back
/// by honk without markers, or none when the marker runs cannot be placed in its blocks.
fn paused(source: &SourceSnapshot) -> Option<Vec<Range<usize>>> {
    let runs = markers::runs(source)?;
    let text = &source.content;
    let runs = runs
        .into_iter()
        .filter(|run| run.list == Some(List::DnsRequest))
        .filter(|run| {
            let marker = markers::marker(&run.id);
            let body: String = text[run.bytes.clone()]
                .split_inclusive('\n')
                .map(|line| &line[marker.len()..])
                .collect();
            let program = format!("dns {{ routing {{ request {{\n{body}\n}} }} }}");
            parse_dae_config_with_detailed_diagnostics(&program, &mut Vec::new())
                .is_ok_and(|c| matches!(c.dns.routing.request.rules.as_slice(), [r] if is_ad(r)))
        })
        .map(|run| run.bytes)
        .collect();
    Some(runs)
}

/// The tree as honk loads it; a draft that does not parse opens the raw editor.
pub(super) fn loaded(tree: &Tree, raw: &impl Fn() -> EditError) -> Result<LoadedConfig, EditError> {
    let (loaded, _) = tree.form_sources().map_err(|e| match e {
        StoreError::Config(HonkConfigError::ParseFailed { .. }) | StoreError::Incomplete => raw(),
        other => other.into(),
    })?;
    Ok(loaded)
}

fn scan(tree: &Tree, raw: &impl Fn() -> EditError) -> Result<Scan, EditError> {
    let loaded = loaded(tree, raw)?;
    let (_, index) = source_indices(&loaded.sources).map_err(|_| raw())?;
    let request = &index.dns_request.rules;
    let mut copies: Vec<Copy> = request
        .iter()
        .zip(&loaded.config.dns.routing.request.rules)
        .filter(|(_, rule)| is_ad(rule))
        .map(|(l, _)| Copy {
            source: l.source_index,
            bytes: l.bytes.clone(),
            paused: false,
        })
        .collect();
    let root = tree.entry.parent().unwrap();
    for (source, snapshot) in loaded.sources.iter().enumerate() {
        // honk loads a file with no block; the cursor that places marker runs does not.
        let runs = paused(snapshot).ok_or_else(|| EditError::OpenSource {
            path: snapshot.path.strip_prefix(root).unwrap().into(),
            bytes: None,
        })?;
        copies.extend(runs.into_iter().map(|bytes| Copy {
            source,
            bytes,
            paused: true,
        }));
    }
    let first = request.first().map(|l| (l.source_index, l.bytes.start));
    Ok(Scan {
        loaded,
        copies,
        first,
    })
}

impl Scan {
    /// The state, and the copy it refers to.
    fn state(&self, root: &Path) -> (AdBlock, Option<&Copy>) {
        let in_place = |c: &Copy| {
            c.source == 0
                && if c.paused {
                    self.first
                        .is_none_or(|first| (c.source, c.bytes.start) < first)
                } else {
                    self.first == Some((c.source, c.bytes.start))
                }
        };
        match self.copies.as_slice() {
            [] => (AdBlock::Absent, None),
            [only] if in_place(only) => {
                let state = if only.paused {
                    AdBlock::Off
                } else {
                    AdBlock::On
                };
                (state, Some(only))
            }
            copies => {
                // A paused copy can sit right before an active first one: both are in place.
                let stray = copies
                    .iter()
                    .find(|c| !in_place(c))
                    .unwrap_or_else(|| &copies[1]);
                let path = &self.loaded.sources[stray.source].path;
                let elsewhere = AdBlock::Elsewhere {
                    path: path.strip_prefix(root).unwrap().into(),
                    bytes: stray.bytes.clone(),
                };
                (elsewhere, Some(stray))
            }
        }
    }
}

/// The nested blocks `NEST[depth..]` around the statement, each line starting with `base`.
fn nested(depth: usize, base: &str, nl: &str) -> String {
    let headers = &NEST[depth..];
    let pad = |level: usize| format!("{base}{}", "    ".repeat(level));
    let mut out = String::new();
    for (level, header) in headers.iter().enumerate() {
        out += &format!("{}{header} {{{nl}", pad(level));
    }
    out += &format!("{}{STATEMENT}{nl}", pad(headers.len()));
    for level in (0..headers.len()).rev() {
        out += &format!("{}}}{nl}", pad(level));
    }
    out
}

/// The innermost of the first `dns`, its first `routing` and that one's first `request`.
fn deepest<'d, 'a>(doc: &'d Document<'a>) -> Option<(usize, Segment<'d, 'a>)> {
    let mut found = doc.sections().find(|s| s.header() == NEST[0])?;
    let mut depth = 1;
    while let Some(child) = NEST.get(depth).and_then(|header| {
        found
            .body()
            .into_iter()
            .flatten()
            .find(|s| s.header() == *header)
    }) {
        found = child;
        depth += 1;
    }
    Some((depth, found))
}

impl File {
    /// The entry with the statement as the only rule of a new or empty request block, or none
    /// when the cursor cannot read the entry.
    fn ad_block_nested(&self) -> Option<(String, Carry)> {
        let text = &self.source.content;
        let nl = if text.contains("\r\n") { "\r\n" } else { "\n" };
        let doc = self.document()?;
        let (bytes, insertion) = match deepest(&doc) {
            None => {
                let lead = if text.is_empty() || text.ends_with('\n') {
                    ""
                } else {
                    nl
                };
                (
                    text.len()..text.len(),
                    format!("{lead}{}", nested(0, "", nl)),
                )
            }
            Some((depth, block)) => {
                let close = block.span().end - 1;
                let start = line_start(text, close);
                if blanks(&text[start..close]) {
                    let base = format!("{}    ", &text[start..close]);
                    (start..start, nested(depth, &base, nl))
                } else {
                    let header = line_start(text, block.header_span().start);
                    let indent = &text[header..block.header_span().start];
                    let indent = if blanks(indent) { indent } else { "" };
                    let inner = nested(depth, &format!("{indent}    "), nl);
                    let at = text[..close].trim_end_matches([' ', '\t']).len();
                    (at..close, format!("{nl}{inner}{indent}"))
                }
            }
        };
        let mut out = text.to_string();
        out.replace_range(bytes.clone(), &insertion);
        Some((out, Carry::splice(text.len(), bytes, insertion.len())))
    }
}

impl HonkStore {
    fn ad_scan(&self, draft: u64) -> Result<(Tree, PathBuf, Scan), EditError> {
        let tree = Tree::read(&self.draft_entry(self.find(draft)?)).map_err(StoreError::from)?;
        let root = tree.entry.parent().unwrap().to_owned();
        let entry = tree.entry.strip_prefix(&root).unwrap().to_owned();
        let scan = scan(&tree, &|| EditError::OpenSource {
            path: entry.clone(),
            bytes: None,
        })?;
        Ok((tree, root, scan))
    }

    pub fn ad_block(&self, draft: u64) -> Result<AdBlock, EditError> {
        let (_, root, scan) = self.ad_scan(draft)?;
        Ok(scan.state(&root).0)
    }

    /// Turning it on needs the category in `geo`, the data the next activation checks against.
    pub fn set_ad_block(
        &mut self,
        draft: u64,
        on: bool,
        geo: &dyn GeoCatalog,
    ) -> Result<(), AdBlockError> {
        let (tree, root, scan) = self.ad_scan(draft)?;
        let entry = tree.entry.strip_prefix(&root).unwrap().to_owned();
        let (state, copy) = scan.state(&root);
        if on && matches!(state, AdBlock::Off | AdBlock::Absent) {
            let label = geo.geosite(LABEL);
            if label != Label::Present {
                return Err(AdBlockError::Label(label));
            }
        }
        let raw = || EditError::OpenSource {
            path: entry.clone(),
            bytes: None,
        };
        let edit = match (state, on) {
            (AdBlock::Elsewhere { path, bytes }, _) => {
                let bytes = Some(bytes);
                return Err(EditError::OpenSource { path, bytes }.into());
            }
            (AdBlock::On, false) => Edit::Pause,
            (AdBlock::Off, true) => Edit::Resume,
            (AdBlock::Absent, true) => {
                let file = File::parse(&tree, &tree.entry, &raw)?;
                return Ok(self.insert_ad_block(draft, tree, &scan, file)?);
            }
            _ => return Ok(()),
        };
        let file = File::parse(&tree, &tree.entry, &raw)?;
        let row = copy.and_then(|c| file.rows.iter().find(|r| r.bytes == c.bytes));
        Ok(self.form_edit(draft, &entry, edit(&row.ok_or_else(raw)?.id))?)
    }

    /// Before the entry's first request row, or as the only rule of a new request block.
    fn insert_ad_block(
        &mut self,
        draft: u64,
        mut tree: Tree,
        old: &Scan,
        file: File,
    ) -> Result<(), EditError> {
        let root = tree.entry.parent().unwrap().to_owned();
        let first = (file.rows.iter())
            .filter(|r| r.list == List::DnsRequest)
            .min_by_key(|r| r.bytes.start);
        if let Some(first) = first {
            let insert = Edit::Insert {
                list: List::DnsRequest,
                before: Some(&first.id),
                statement: STATEMENT,
            };
            return self.form_edit(draft, &file.source.path, insert);
        }
        let raw = || EditError::OpenSource {
            path: file.source.path.clone(),
            bytes: None,
        };
        let (text, carry) = file.ad_block_nested().ok_or_else(raw)?;
        let absolute = tree.entry.clone();
        tree.files.insert(absolute.clone(), text.into());
        let candidate = File::parse(&tree, &absolute, &raw)?;
        let after = scan(&tree, &raw)?;
        let (old, new) = (
            &old.loaded.config.dns.routing.request,
            &after.loaded.config.dns.routing.request,
        );
        if after.state(&root).0 != AdBlock::On
            || new.rules[1..] != old.rules[..]
            || new.fallback != old.fallback
        {
            return Err(raw());
        }
        Ok(self.write_steps(draft, &absolute, &root, &file, &[(candidate, carry)])?)
    }
}
