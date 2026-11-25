//! The structured rule form. A form writes one admitted predicate and target at a position whose
//! effective neighbours it proves, reads the statement back, and reports earlier rules that may
//! take its traffic first. The warning never blocks the write.

use std::ops::Range;
use std::path::{Path, PathBuf};

use honk_config::diagnostic::DiagnosticSources;
use honk_config::parser::cursor::Document;
use honk_config::parser::lexer::Source;

use super::carry::Carry;
use super::literal::{Literal, Match, is_invisible};
use super::shadow::{Relation, relation};
use super::{EditError, Expected, File, List, RuleRow};
use crate::honkconf::staged::Tree;
use crate::honkconf::{HonkStore, StoreError};

/// The new text, the statement's bytes in it, and where the old bytes went.
type Spliced = (String, Range<usize>, Carry);

#[derive(Debug, PartialEq, Eq)]
pub enum FormError {
    Empty,
    /// A control or line separator, whitespace, a quote or an expression delimiter.
    Forbidden,
    /// `*.`, `+.` or `.` before a name: a suffix match already covers subdomains.
    Wildcard,
    Name,
    /// A keyword outside ASCII letters, digits, `-` and `.`: honk matches ASCII A-labels, so use a
    /// full or suffix name, which is IDNA-encoded.
    Keyword,
    Network,
    /// The address has bits set past the prefix length.
    HostBits,
    /// An IPv4-mapped IPv6 network; the IPv4 network must be entered instead.
    MappedIpv6,
    /// Neither `direct`, `block` nor a declared group name that can be written unquoted.
    Target,
    /// No single block provably holds the end of the effective rules; an anchor is required.
    Unplaced,
}

#[derive(Clone, Copy, Debug)]
pub struct RuleForm<'a> {
    pub matcher: Match<'a>,
    pub target: &'a str,
    /// The statement is checked active, then commented out. The form of a paused rule starts
    /// paused, so replacing it keeps it paused unless the user clears this.
    pub paused: bool,
}

#[derive(Clone, Copy, Debug)]
pub enum FormEdit<'a> {
    /// `before` names a rule or paused run in that file. `None` places the rule after the
    /// tree's last effective ordinary rule, which may be in any of its sources.
    Add {
        before: Option<(&'a Path, &'a str)>,
        form: RuleForm<'a>,
    },
    /// Replaces exactly the rule's statement bytes, active or paused, as one change.
    Replace {
        path: &'a Path,
        id: &'a str,
        form: RuleForm<'a>,
    },
}

#[derive(Debug, PartialEq, Eq)]
pub enum Earlier {
    Clear,
    /// Some earlier active rule is not provably disjoint (`rule.earlierMayMatch`).
    MayMatch,
    /// The first earlier active rule that provably matches all of the new rule's traffic.
    /// `position` counts active traffic rules in effective order from 0.
    Shadowed {
        position: usize,
        path: PathBuf,
        bytes: Range<usize>,
    },
}

#[derive(Debug)]
pub struct Formed {
    pub path: PathBuf,
    pub id: String,
    pub earlier: Earlier,
}

impl File {
    fn located(&self, id: Option<&str>) -> EditError {
        EditError::OpenSource {
            path: self.source.path.clone(),
            bytes: id
                .and_then(|id| self.rows.iter().find(|r| r.id == id))
                .map(|r| r.bytes.clone()),
        }
    }

    /// The traffic rule a replacement names.
    fn replaced(&self, edit: FormEdit<'_>) -> Result<Option<&RuleRow>, EditError> {
        let FormEdit::Replace { id, .. } = edit else {
            return Ok(None);
        };
        self.row(id)
            .filter(|r| r.list == List::Traffic && !r.fallback)
            .map(Some)
            .ok_or_else(|| self.located(Some(id)))
    }

    fn target(&self, name: &str) -> bool {
        let plain = !name.is_empty()
            && name.chars().all(|c| {
                !(c.is_control()
                    || is_invisible(c)
                    || c.is_whitespace()
                    || "#{}()'\",!&".contains(c))
            })
            && !name.contains("->");
        plain && (matches!(name, "direct" | "block") || self.targets.contains(name))
    }

    /// Rules of the merged tree before byte `at` of this file.
    fn preceding(&self, at: usize) -> usize {
        self.traffic
            .iter()
            .filter(|p| (p.source, p.bytes.start) < (self.owner, at))
            .count()
    }

    /// After the tree's last effective rule, which must be in this file; with none, before
    /// this file's winning fallback or at the end of its last traffic block.
    fn end(&self) -> Option<usize> {
        let doc = self.document()?;
        let blocks = List::Traffic.blocks(&doc);
        let after = match self.traffic.last() {
            Some(last) => Some((last.source == self.owner).then_some(last.bytes.end)?),
            None => self
                .rows
                .iter()
                .find(|r| r.list == List::Traffic && r.fallback)
                .map(|r| r.bytes.start),
        };
        let block = match after {
            Some(byte) => blocks
                .into_iter()
                .find(|b| b.span().start < byte && byte < b.span().end)?,
            None => blocks.into_iter().last()?,
        };
        let at = self.slot(List::Traffic, &block, None)?;
        after.is_none_or(|byte| byte <= at).then_some(at)
    }

    fn replace(&self, row: &RuleRow, statement: &str) -> Option<Spliced> {
        self.rule_block(&self.document()?, row)?;
        let mut out = self.source.content.to_string();
        out.replace_range(row.bytes.clone(), statement);
        let start = row.bytes.start;
        let carry = Carry::splice(
            self.source.content.len(),
            row.bytes.clone(),
            statement.len(),
        );
        Some((out, start..start + statement.len(), carry))
    }
}

fn routed(tree: &Tree, path: &Path) -> bool {
    tree.files.get(path).is_some_and(|text| {
        let source = Source::new(text, DiagnosticSources::new(Some(path.into())).root());
        Document::parse(source, &mut Vec::new())
            .is_ok_and(|doc| !List::Traffic.blocks(&doc).is_empty())
    })
}

/// The source holding the tree's effective end: its last rule's, else its fallback's, else
/// the last one with a traffic block.
fn end_path(tree: &Tree, root: &Path) -> Result<PathBuf, EditError> {
    let entry = File::parse(tree, &tree.entry, &|| EditError::OpenSource {
        path: tree.entry.strip_prefix(root).unwrap().into(),
        bytes: None,
    })?;
    let source = entry
        .traffic
        .last()
        .map(|p| p.source)
        .or(entry.fallback_source)
        .or_else(|| (0..entry.paths.len()).rfind(|&i| routed(tree, &entry.paths[i])))
        .ok_or(EditError::Form(FormError::Unplaced))?;
    Ok(entry.paths[source].strip_prefix(root).unwrap().into())
}

fn reparse(
    tree: &mut Tree,
    absolute: &Path,
    text: String,
    raw: &impl Fn() -> EditError,
) -> Result<File, EditError> {
    tree.files.insert(absolute.into(), text.into());
    File::parse(tree, absolute, raw)
}

fn earlier(file: &File, rules: &[super::Placed], literal: &Literal, root: &Path) -> Earlier {
    let mut may = false;
    for (position, placed) in rules.iter().enumerate() {
        match relation(literal, &placed.rule.condition) {
            Relation::Covers => {
                return Earlier::Shadowed {
                    position,
                    path: file.paths[placed.source].strip_prefix(root).unwrap().into(),
                    bytes: placed.bytes.clone(),
                };
            }
            Relation::May => may = true,
            Relation::Disjoint => {}
        }
    }
    if may {
        Earlier::MayMatch
    } else {
        Earlier::Clear
    }
}

/// The new row's ID when honk reads back exactly the admitted predicate and target, with no
/// modifiers, as the `index`th effective rule at `bytes`.
fn read_back(
    base: &File,
    candidate: &File,
    index: usize,
    bytes: Range<usize>,
    literal: &Literal,
    target: &str,
) -> Option<String> {
    let placed = candidate.traffic.get(index)?;
    let rule = &placed.rule;
    let row = candidate
        .rows
        .iter()
        .find(|r| !r.paused && r.bytes == bytes)?;
    (placed.source == candidate.owner
        && placed.bytes == bytes
        && rule.condition == literal.condition()
        && rule.outbound.as_str() == target
        && !rule.must
        && rule.mark == 0
        && Expected::Inserted(List::Traffic, bytes).matches(&base.source.content, candidate))
    .then(|| row.id.clone())
}

/// The file with the paused rule `id` uncommented, as the scratch base of its replacement.
fn resume(
    tree: &mut Tree,
    absolute: &Path,
    file: &File,
    id: &str,
    raw: &impl Fn() -> EditError,
) -> Result<(File, Carry), EditError> {
    let (text, expected, carry) = file.resume(id).ok_or_else(raw)?;
    let resumed = reparse(tree, absolute, text, raw)?;
    if expected.matches(&file.source.content, &resumed) {
        Ok((resumed, carry))
    } else {
        Err(raw())
    }
}

/// The candidate with its new rule commented out.
fn pause(
    tree: &mut Tree,
    absolute: &Path,
    candidate: &File,
    id: &str,
    raw: &impl Fn() -> EditError,
) -> Result<(File, Carry), EditError> {
    let (text, carry) = candidate
        .row(id)
        .and_then(|row| candidate.pause(row))
        .ok_or_else(raw)?;
    let paused = reparse(tree, absolute, text, raw)?;
    if paused.traffic.len() + 1 == candidate.traffic.len()
        && paused.rows.iter().any(|r| r.paused && r.id == id)
    {
        Ok((paused, carry))
    } else {
        Err(raw())
    }
}

/// The statement's offset in `base` and the spliced text: over the replaced rule, or inserted
/// before the anchor or at the effective end.
fn splice(
    base: &File,
    replaced: Option<&RuleRow>,
    before: Option<&str>,
    statement: &str,
    raw: &impl Fn() -> EditError,
) -> Result<(usize, Spliced), EditError> {
    if let Some(old) = replaced {
        // The marker precedes the indent, so a resumed statement starts at or after its run.
        let row = base.rows.iter().find(|r| {
            r.list == List::Traffic && !r.paused && !r.fallback && r.bytes.start >= old.bytes.start
        });
        let row = row.ok_or_else(raw)?;
        let spliced = base.replace(row, statement).ok_or_else(raw)?;
        return Ok((row.bytes.start, spliced));
    }
    let at = match before {
        Some(id) => base.position(List::Traffic, Some(id)).ok_or_else(raw)?,
        None => base.end().ok_or(EditError::Form(FormError::Unplaced))?,
    };
    Ok((at, base.insert_at(at, statement)))
}

impl<'a> FormEdit<'a> {
    /// The file to edit, the form, and the row it replaces or precedes.
    fn parts(
        self,
        tree: &Tree,
        root: &Path,
    ) -> Result<(PathBuf, RuleForm<'a>, Option<&'a str>), EditError> {
        Ok(match self {
            Self::Add {
                before: Some((path, id)),
                form,
            }
            | Self::Replace { path, id, form } => (path.to_owned(), form, Some(id)),
            Self::Add { before: None, form } => (end_path(tree, root)?, form, None),
        })
    }
}

/// The admitted literal and the statement it writes with `form`'s target.
fn admit(file: &File, form: RuleForm<'_>) -> Result<(Literal, String), EditError> {
    let literal = Literal::admit(form.matcher).map_err(EditError::Form)?;
    if !file.target(form.target) {
        return Err(EditError::Form(FormError::Target));
    }
    let statement = format!("{} -> {}", literal.predicate(), form.target);
    Ok((literal, statement))
}

impl HonkStore {
    /// Writes nothing when the steps end on `file`'s own bytes, as a replacement with the same
    /// statement does, so its row keeps its meter.
    fn write_form(
        &mut self,
        draft: u64,
        absolute: &Path,
        root: &Path,
        file: &File,
        steps: &[(File, Carry)],
    ) -> Result<(), EditError> {
        if steps.last().unwrap().0.source.content == file.source.content {
            return Ok(());
        }
        Ok(self.write_steps(draft, absolute, root, file, steps)?)
    }

    /// With `paused: true`, `Earlier` describes the rule's active form.
    pub fn rule_form(&mut self, draft: u64, edit: FormEdit<'_>) -> Result<Formed, EditError> {
        let mut tree =
            Tree::read(&self.draft_entry(self.find(draft)?)).map_err(StoreError::from)?;
        let root = tree.entry.parent().unwrap().to_owned();
        let (path, form, id) = edit.parts(&tree, &root)?;
        let absolute = tree.edit_path(&path)?;
        let file = File::parse(&tree, &absolute, &|| EditError::OpenSource {
            path: path.clone(),
            bytes: None,
        })?;
        let (literal, statement) = admit(&file, form)?;
        let raw = || file.located(id);
        let replaced = file.replaced(edit)?;
        let resumed = replaced
            .filter(|row| row.paused)
            .map(|row| resume(&mut tree, &absolute, &file, &row.id, &raw))
            .transpose()?;
        let base = resumed.as_ref().map_or(&file, |(resumed, _)| resumed);
        let before = id.filter(|_| replaced.is_none());
        let (at, (text, bytes, carry)) = splice(base, replaced, before, &statement, &raw)?;
        let index = base.preceding(at);
        let candidate = reparse(&mut tree, &absolute, text, &raw)?;
        let id =
            read_back(base, &candidate, index, bytes, &literal, form.target).ok_or_else(raw)?;
        let earlier = earlier(&candidate, &candidate.traffic[..index], &literal, &root);
        let paused = (form.paused)
            .then(|| pause(&mut tree, &absolute, &candidate, &id, &raw))
            .transpose()?;
        let steps: Vec<_> = (resumed.into_iter())
            .chain([(candidate, carry)])
            .chain(paused)
            .collect();
        self.write_form(draft, &absolute, &root, &file, &steps)?;
        Ok(Formed { path, id, earlier })
    }
}
