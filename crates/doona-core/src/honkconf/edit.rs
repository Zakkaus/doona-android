//! Form edits are local to one entry-relative source in a private draft. A form that cannot
//! prove ownership of its bytes returns their raw-editor location. Check and Apply remain separate.

pub mod ads;
pub(super) mod carry;
mod diff;
mod fields;
mod form;
mod identity;
mod literal;
mod markers;
mod shadow;

use std::collections::HashSet;
use std::ops::Range;
use std::path::{Path, PathBuf};

use honk_config::Config;
use honk_config::parser::SourceSnapshot;
use honk_config::parser::cursor::{Document, Segment};
use honk_config::parser::lexer::{Source, Token};
pub use honk_config::parser::source_edit::GroupField;
use honk_config::parser::source_edit::{RuleSourceIndex, edit_group_source, source_indices};
use honk_config::routing::RoutingRule;

use super::staged::Tree;
use super::{HonkConfigError, HonkStore, StoreError};
use carry::Carry;
pub use diff::SourceChange;
pub use form::{Earlier, FormEdit, FormError, Formed, RuleForm};
pub use literal::Match;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum List {
    Traffic,
    DnsRequest,
    DnsResponse,
}

impl List {
    fn name(self) -> &'static str {
        match self {
            Self::Traffic => "traffic",
            Self::DnsRequest => "request",
            Self::DnsResponse => "response",
        }
    }

    fn blocks<'d, 'a>(self, doc: &'d Document<'a>) -> Vec<Segment<'d, 'a>> {
        if self == Self::Traffic {
            return doc.sections().filter(|s| s.header() == "routing").collect();
        }
        doc.sections()
            .filter(|s| s.header() == "dns")
            .flat_map(|s| s.body().into_iter().flatten())
            .filter(|s| s.header() == "routing")
            .flat_map(|s| s.body().into_iter().flatten())
            .filter(|s| s.header() == self.name())
            .collect()
    }
}

#[derive(Debug, PartialEq, Eq)]
pub struct RuleRow {
    pub id: String,
    pub list: List,
    pub fallback: bool,
    pub paused: bool,
    pub bytes: Range<usize>,
}

/// `before` names a rule or fallback in the same file/list. `None` appends before fallback
/// (or the closing brace), but only when a single block owns that position.
#[derive(Clone, Copy)]
pub enum Edit<'a> {
    Insert {
        list: List,
        before: Option<&'a str>,
        statement: &'a str,
    },
    Move {
        id: &'a str,
        before: Option<&'a str>,
    },
    Delete(&'a str),
    Pause(&'a str),
    Resume(&'a str),
    SetFallback {
        id: &'a str,
        target: &'a str,
    },
    Group {
        name: &'a str,
        field: GroupField,
        value: &'a str,
    },
    /// One existing filter in the named group's winning declaration in this file.
    GroupFilter {
        name: &'a str,
        occurrence: usize,
        expression: &'a str,
    },
    Upstream {
        name: &'a str,
        field: UpstreamField,
        value: &'a str,
    },
}

#[derive(Clone, Copy)]
pub enum UpstreamField {
    Uri,
    Detour,
}

#[derive(Debug)]
pub enum EditError {
    Store(StoreError),
    OpenSource {
        path: PathBuf,
        bytes: Option<Range<usize>>,
    },
    Form(FormError),
}

impl From<StoreError> for EditError {
    fn from(error: StoreError) -> Self {
        Self::Store(error)
    }
}

/// An active traffic rule of the whole tree, in honk's effective order.
struct Placed {
    source: usize,
    bytes: Range<usize>,
    rule: RoutingRule,
}

struct File {
    source: SourceSnapshot,
    stray_markers: Vec<markers::Run>,
    groups: HashSet<String>,
    upstreams: HashSet<String>,
    rows: Vec<RuleRow>,
    warnings: Vec<Range<usize>>,
    /// Index of this file in `paths`, the tree's sources in merge order.
    owner: usize,
    paths: Vec<PathBuf>,
    traffic: Vec<Placed>,
    fallback_source: Option<usize>,
    /// Every declared group name: the traffic targets besides the built-ins.
    targets: HashSet<String>,
}

impl File {
    fn parse(tree: &Tree, path: &Path, raw: &impl Fn() -> EditError) -> Result<Self, EditError> {
        let (loaded, diagnostics) = tree.form_sources().map_err(|e| match e {
            StoreError::Config(
                HonkConfigError::ParseFailed { .. } | HonkConfigError::NonNativeSource,
            )
            | StoreError::Incomplete => raw(),
            other => other.into(),
        })?;
        let (groups, index) = source_indices(&loaded.sources).map_err(|_| raw())?;
        let paths: Vec<PathBuf> = loaded.sources.iter().map(|s| s.path.clone()).collect();
        let owner = paths.iter().position(|p| p == path).ok_or_else(raw)?;
        let traffic = index
            .rules
            .iter()
            .zip(&loaded.config.routing.rules)
            .map(|(l, rule)| Placed {
                source: l.source_index,
                bytes: l.bytes.clone(),
                rule: rule.clone(),
            })
            .collect();
        let fallback_source = index.fallback.as_ref().map(|l| l.source_index);
        let mut source = loaded.sources.into_iter().nth(owner).unwrap();
        source.path = path
            .strip_prefix(tree.entry.parent().unwrap())
            .unwrap()
            .into();
        let (rows, markers) = source_rows(&source, &loaded.config, index, owner).ok_or_else(raw)?;
        let mut upstreams = HashSet::new();
        let mut duplicates = HashSet::new();
        for u in loaded.config.dns.upstream {
            if !upstreams.insert(u.name.clone()) {
                duplicates.insert(u.name);
            }
        }
        upstreams.retain(|name| !duplicates.contains(name));
        let warnings = diagnostics
            .into_iter()
            .filter(|d| d.source.index() == source.source.index())
            .filter_map(|d| d.span)
            .collect();
        Ok(Self {
            source,
            stray_markers: markers,
            rows,
            warnings,
            targets: groups.keys().cloned().collect(),
            groups: groups
                .into_iter()
                .filter_map(|(name, i)| (i == owner).then_some(name))
                .collect(),
            upstreams,
            owner,
            paths,
            traffic,
            fallback_source,
        })
    }

    fn row(&self, id: &str) -> Option<&RuleRow> {
        let mut matching = self.rows.iter().filter(|r| r.id == id);
        let row = matching.next()?;
        (!row.paused
            || (matching.next().is_none() && !self.stray_markers.iter().any(|r| r.id == id)))
        .then_some(row)
    }

    fn document(&self) -> Option<Document<'_>> {
        Document::parse(
            Source::new(&self.source.content, self.source.source.clone()),
            &mut Vec::new(),
        )
        .ok()
    }

    fn rule_block<'d, 'a>(&self, doc: &'d Document<'a>, row: &RuleRow) -> Option<Segment<'d, 'a>> {
        let block = row
            .list
            .blocks(doc)
            .into_iter()
            .find(|b| b.header_span().end < row.bytes.start && row.bytes.end < b.span().end)?;
        // The pin can join continuation lines across repeated sections and skip ignored
        // declarations between them. Neither gap belongs to a single editable statement.
        let crosses_child = block.body()?.any(|s| {
            row.bytes.contains(&s.span().start)
                && (s.header_span() != s.span() || s.header().starts_with("/*"))
        });
        (!crosses_child).then_some(block)
    }

    fn position(&self, list: List, before: Option<&str>) -> Option<usize> {
        let doc = self.document()?;
        let (block, target) = if let Some(id) = before {
            let row = self.row(id).filter(|r| r.list == list)?;
            (self.rule_block(&doc, row)?, Some(row.bytes.start))
        } else {
            let mut blocks = list.blocks(&doc).into_iter();
            let only = blocks.next()?;
            if blocks.next().is_some() {
                return None;
            }
            (only, None)
        };
        self.slot(list, &block, target)
    }

    /// `target`, or else the end of `block` before its fallback, unless that is past a fallback.
    fn slot(&self, list: List, block: &Segment<'_, '_>, target: Option<usize>) -> Option<usize> {
        let fallback = block
            .body()
            .into_iter()
            .flatten()
            .find(|s| s.header().starts_with("fallback:") || s.header().starts_with("default:"))
            .map(|s| s.span().start);
        let at = target.or(fallback).unwrap_or(block.span().end - 1);
        let terminal = self.rows.iter().find(|r| r.list == list && r.fallback);
        (fallback.is_none_or(|first| at <= first) && terminal.is_none_or(|r| at <= r.bytes.start))
            .then_some(at)
    }

    fn lines(&self, row: &RuleRow) -> Option<Range<usize>> {
        self.rule_block(&self.document()?, row)?;
        if row.paused {
            return Some(row.bytes.clone());
        }
        let text = &self.source.content;
        let start = line_start(text, row.bytes.start);
        let end = text[row.bytes.end..]
            .find('\n')
            .map_or(text.len(), |i| row.bytes.end + i + 1);
        let suffix = text[row.bytes.end..end].trim_start_matches([' ', '\t']);
        (blanks(&text[start..row.bytes.start])
            && (suffix.trim().is_empty() || suffix.starts_with('#')))
        .then_some(start..end)
    }

    fn raw(&self, edit: Edit<'_>) -> EditError {
        let row = |id| {
            self.rows
                .iter()
                .find(|r| r.id == id)
                .map(|r| r.bytes.clone())
        };
        let bytes = match edit {
            Edit::Resume(id) => self
                .stray_markers
                .iter()
                .find(|r| r.id == id)
                .map(|r| r.bytes.clone())
                .or_else(|| row(id)),
            Edit::Move { id, .. }
            | Edit::Pause(id)
            | Edit::Delete(id)
            | Edit::SetFallback { id, .. } => row(id),
            Edit::Insert { before, .. } => before.and_then(row),
            _ => None,
        };
        EditError::OpenSource {
            path: self.source.path.clone(),
            bytes,
        }
    }

    fn insert(
        &self,
        list: List,
        before: Option<&str>,
        statement: &str,
    ) -> Option<(String, Expected, Carry)> {
        let (out, bytes, carry) = self.insert_at(self.position(list, before)?, statement);
        Some((out, Expected::Inserted(list, bytes), carry))
    }

    /// The text with `statement` on its own line at `at`, and the statement's bytes.
    fn insert_at(&self, at: usize, statement: &str) -> (String, Range<usize>, Carry) {
        let text = &self.source.content;
        let start = line_start(text, at);
        let prefix = &text[start..at];
        let nl = if text[start..]
            .split_once('\n')
            .is_some_and(|(line, _)| line.ends_with('\r'))
            || text[..start].ends_with("\r\n")
        {
            "\r\n"
        } else {
            "\n"
        };
        let (insertion, indent, lead) = if blanks(prefix) {
            (start, prefix, "")
        } else {
            (at, "", nl)
        };
        let mut out = String::with_capacity(
            text.len() + lead.len() + indent.len() + statement.len() + nl.len(),
        );
        for part in [
            &text[..insertion],
            lead,
            indent,
            statement,
            nl,
            &text[insertion..],
        ] {
            out.push_str(part);
        }
        let offset = insertion + lead.len() + indent.len();
        let carry = Carry::splice(text.len(), insertion..insertion, out.len() - text.len());
        (out, offset..offset + statement.len(), carry)
    }

    fn move_rule(&self, row: &RuleRow, before: Option<&str>) -> Option<(String, Carry)> {
        let text = &self.source.content;
        let range = self.lines(row)?;
        let target = self.position(row.list, before)?;
        let at = line_start(text, target);
        if !blanks(&text[at..target]) {
            return None;
        }
        let (a, b, c) = if at <= range.start {
            (at, range.start, range.end)
        } else {
            (range.start, range.end, at)
        };
        let mut out = String::with_capacity(text.len());
        for part in [&text[..a], &text[b..c], &text[a..b], &text[c..]] {
            out.push_str(part);
        }
        Some((out, Carry::swap(text.len(), a, b, c)))
    }

    fn change(&self, edit: Edit<'_>) -> Option<(String, Expected, Carry)> {
        let rule = |id| self.row(id).filter(|r| !r.fallback);
        let text = &self.source.content;
        match edit {
            Edit::Insert {
                list,
                before,
                statement,
            } => self.insert(list, before, statement),
            Edit::Resume(id) => self.resume(id),
            Edit::Pause(id) => {
                let (out, carry) = self.pause(rule(id)?)?;
                Some((out, Expected::Parsed, carry))
            }
            Edit::Move { id, before } => {
                let (out, carry) = self.move_rule(rule(id)?, before)?;
                Some((out, Expected::Parsed, carry))
            }
            Edit::Delete(id) => {
                let range = self.lines(rule(id)?)?;
                let carry = Carry::splice(text.len(), range.clone(), 0);
                let mut out = text.to_string();
                out.replace_range(range, "");
                Some((out, Expected::Parsed, carry))
            }
            field => self.field(field).map(|out| {
                let carry = Carry::diff(text, &out);
                (out, Expected::Field, carry)
            }),
        }
    }

    fn field(&self, edit: Edit<'_>) -> Option<String> {
        match edit {
            Edit::Group { name, field, value } if self.groups.contains(name) => {
                edit_group_source(&self.source, name, &[(field, Some(value.into()))]).ok()
            }
            Edit::GroupFilter {
                name,
                occurrence,
                expression,
            } if self.groups.contains(name) => fields::filter(self, name, occurrence, expression),
            Edit::Upstream { name, field, value } if self.upstreams.contains(name) => {
                fields::upstream(self, name, field, value)
            }
            Edit::SetFallback { id, target } => {
                fields::fallback(self, self.row(id).filter(|r| r.fallback)?, target)
            }
            _ => None,
        }
    }

    fn pause(&self, row: &RuleRow) -> Option<(String, Carry)> {
        let text = &self.source.content;
        let span = self.lines(row)?;
        if row.paused || self.stray_markers.iter().any(|r| r.id == row.id) {
            return None;
        }
        let marker = markers::marker(&row.id);
        let lines = text[span.clone()].split_inclusive('\n');
        let mut out = String::with_capacity(text.len() + lines.clone().count() * marker.len());
        out.push_str(&text[..span.start]);
        for line in lines {
            out.push_str(&marker);
            out.push_str(line);
        }
        let paused = span.start..out.len();
        out.push_str(&text[span.end..]);
        let carry = Carry::splice(text.len(), span, paused.len()).keep(row.bytes.clone(), paused);
        Some((out, carry))
    }

    fn resume(&self, id: &str) -> Option<(String, Expected, Carry)> {
        let run = self.row(id).filter(|r| r.paused)?;
        let text = &self.source.content;
        let mut out = String::with_capacity(text.len());
        out.push_str(&text[..run.bytes.start]);
        let marker = markers::marker(id);
        for line in text[run.bytes.clone()].split_inclusive('\n') {
            out.push_str(&line[marker.len()..]);
        }
        let restored = run.bytes.start..out.len();
        out.push_str(&text[run.bytes.end..]);
        let carry = Carry::splice(text.len(), run.bytes.clone(), restored.len())
            .keep(run.bytes.clone(), restored.clone());
        Some((out, Expected::Restored(run.list, restored), carry))
    }
}

/// The rows of `source`, whose path is entry-relative, and its marker runs that are not rows.
fn source_rows(
    source: &SourceSnapshot,
    cfg: &Config,
    index: RuleSourceIndex,
    owner: usize,
) -> Option<(Vec<RuleRow>, Vec<markers::Run>)> {
    let mut markers = markers::runs(source)?;
    let reserved = markers
        .iter()
        .filter(|r| r.list.is_some())
        .map(|r| r.id.as_str())
        .collect();
    let mut rows = identity::rows(source, cfg, index, owner, &reserved)?;
    rows.extend(
        markers
            .extract_if(.., |run| run.list.is_some())
            .filter_map(markers::Run::row),
    );
    Some((rows, markers))
}

fn unquoted<'a>(
    text: &'a str,
    token: &'a Token,
    delimiter: &'a str,
) -> impl Iterator<Item = usize> + 'a {
    let mut quotes = token.quoted.iter().peekable();
    text[token.span.start..token.span.end]
        .match_indices(delimiter)
        .filter_map(move |(i, _)| {
            let pos = token.span.start + i;
            while quotes.next_if(|q| q.end <= pos).is_some() {}
            quotes.peek().is_none_or(|q| pos < q.start).then_some(pos)
        })
}

fn blanks(text: &str) -> bool {
    text.bytes().all(|b| matches!(b, b' ' | b'\t'))
}
fn line_start(text: &str, at: usize) -> usize {
    text[..at].rfind('\n').map_or(0, |n| n + 1)
}

enum Expected {
    Parsed,
    Field,
    Inserted(List, Range<usize>),
    Restored(List, Range<usize>),
}

impl Expected {
    fn matches(self, original: &str, candidate: &File) -> bool {
        let without_warnings = |bytes: &Range<usize>| {
            !candidate
                .warnings
                .iter()
                .any(|w| w.start < bytes.end && bytes.start < w.end)
        };
        let active = candidate.rows.iter().filter(|r| !r.paused && !r.fallback);
        match self {
            Self::Parsed => true,
            Self::Field => diff::changed(original, &candidate.source.content)
                .is_none_or(|(_, bytes)| without_warnings(&bytes)),
            Self::Inserted(list, bytes) => active
                .filter(|r| r.list == list)
                .find(|r| r.bytes == bytes)
                .is_some_and(|r| {
                    candidate
                        .document()
                        .is_some_and(|doc| candidate.rule_block(&doc, r).is_some())
                        && without_warnings(&bytes)
                }),
            Self::Restored(list, bytes) => active
                .filter(|r| r.list == list)
                .find(|r| bytes.contains(&r.bytes.start))
                .is_some_and(|r| candidate.lines(r) == Some(bytes)),
        }
    }
}

impl HonkStore {
    fn form_file(&self, draft: u64, path: &Path) -> Result<(Tree, PathBuf, File), EditError> {
        let tree = Tree::read(&self.draft_entry(self.find(draft)?)).map_err(StoreError::from)?;
        let absolute = tree.edit_path(path)?;
        let file = File::parse(&tree, &absolute, &|| EditError::OpenSource {
            path: path.into(),
            bytes: None,
        })?;
        Ok((tree, absolute, file))
    }

    /// Rows in source order, including paused marker runs and the tree's winning fallbacks.
    pub fn rule_rows(&self, draft: u64, path: &Path) -> Result<Vec<RuleRow>, EditError> {
        let (_, _, mut file) = self.form_file(draft, path)?;
        file.rows.sort_unstable_by_key(|r| r.bytes.start);
        Ok(file.rows)
    }

    pub fn form_edit(&mut self, draft: u64, path: &Path, edit: Edit<'_>) -> Result<(), EditError> {
        let (mut tree, absolute, file) = self.form_file(draft, path)?;
        let raw = || file.raw(edit);
        let field = match edit {
            Edit::Group { value, .. } | Edit::Upstream { value, .. } => value,
            Edit::GroupFilter { expression, .. } => expression,
            Edit::SetFallback { target, .. } => target,
            _ => "",
        };
        if field.chars().any(char::is_control) {
            return Err(raw());
        }
        let (out, expected, carry) = file.change(edit).ok_or_else(raw)?;
        tree.files.insert(absolute.clone(), out.into());
        let candidate = File::parse(&tree, &absolute, &raw)?;
        if !expected.matches(&file.source.content, &candidate) {
            return Err(raw());
        }
        let root = tree.entry.parent().unwrap();
        self.write_steps(draft, &absolute, root, &file, &[(candidate, carry)])
            .map_err(EditError::from)
    }
}
