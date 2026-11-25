//! 加入群組: one static node in the entry file plus one exact-name filter line in the winning
//! declaration of each chosen group, checked together and written to one draft. A group with
//! neither filters nor subgroups already holds every node; adding a filter would narrow it to
//! this one, so it is never edited.

use std::collections::HashSet;
use std::ops::Range;
use std::path::{Path, PathBuf};

use honk_config::Config;
use honk_config::diagnostic::DiagnosticSources;
use honk_config::node::Group;
use honk_config::parser::cursor::{Document, Segment};
use honk_config::parser::lexer::Source;
use honk_config::parser::source_edit::{append_node_source, source_indices};

use super::ads::loaded;
use super::carry::Carry;
use super::{EditError, File, blanks, line_start};
use crate::honkconf::staged::Tree;
use crate::honkconf::{HonkStore, StoreError};

#[derive(Debug)]
pub enum AddNodeError {
    Edit(EditError),
    /// honk refuses the name or link, or the tree already declares this name.
    Node,
    /// No group of the tree has this name.
    Group(String),
}

impl From<EditError> for AddNodeError {
    fn from(error: EditError) -> Self {
        Self::Edit(error)
    }
}

impl From<StoreError> for AddNodeError {
    fn from(error: StoreError) -> Self {
        Self::Edit(error.into())
    }
}

/// The winning declaration of each group name: honk keeps the last one.
fn winning<'c>(config: &'c Config, name: &str) -> Option<&'c Group> {
    config.groups.iter().rfind(|g| g.name == name)
}

fn member_names(config: &Config, group: &Group) -> HashSet<String> {
    let names = config.nodes.iter().filter(|n| group.nodes.contains(&n.id));
    names.map(|n| n.name.clone()).collect()
}

/// `name('<name>')`, quoted so the argument reads back as exactly the name.
fn exact(name: &str) -> String {
    let quote = if name.contains('\'') { '"' } else { '\'' };
    format!("name({quote}{name}{quote})")
}

/// The last declaration of `name` under a `group` section.
fn declaration<'d, 'a>(doc: &'d Document<'a>, name: &str) -> Option<Segment<'d, 'a>> {
    doc.sections()
        .filter(|s| s.header() == "group")
        .flat_map(|s| s.body().into_iter().flatten())
        .filter(|s| s.header().trim() == name)
        .last()
}

fn document<'a>(text: &'a str, path: &Path) -> Option<Document<'a>> {
    let source = DiagnosticSources::new(Some(path.into())).root();
    Document::parse(Source::new(text, source), &mut Vec::new()).ok()
}

/// `text` with `line` as the last statement of the declaration's body.
fn append_line(text: &str, path: &Path, name: &str, line: &str) -> Option<String> {
    let doc = document(text, path)?;
    let group = declaration(&doc, name)?;
    let nl = if text.contains("\r\n") { "\r\n" } else { "\n" };
    let close = group.span().end - 1;
    let start = line_start(text, close);
    let own_line = |at: usize| Some(&text[line_start(text, at)..at]).filter(|p| blanks(p));
    let header = own_line(group.header_span().start).unwrap_or("");
    let last = group.body().into_iter().flatten().last();
    let pad = last
        .and_then(|s| own_line(s.span().start))
        .map_or_else(|| format!("{header}    "), str::to_owned);
    let mut out = text.to_string();
    if blanks(&text[start..close]) {
        out.insert_str(start, &format!("{pad}{line}{nl}"));
    } else {
        let at = text[..close].trim_end_matches([' ', '\t']).len();
        out.replace_range(at..close, &format!("{nl}{pad}{line}{nl}{header}"));
    }
    Some(out)
}

/// Whether `group` holds exactly its old members plus `name`, through the same subgroups.
fn read_back(before: &Config, after: &Config, group: &str, name: &str) -> bool {
    let (old, new) = (
        winning(before, group).unwrap(),
        winning(after, group).unwrap(),
    );
    let mut expected = member_names(before, old);
    expected.insert(name.into());
    member_names(after, new) == expected && new.groups == old.groups
}

/// Every group whose winning declaration holds the node, in declaration order.
fn holding(config: &Config, name: &str) -> Vec<String> {
    let node = config.nodes.iter().find(|n| n.name == name).unwrap();
    let mut groups: Vec<String> = Vec::new();
    for group in &config.groups {
        let won = winning(config, &group.name).unwrap();
        if won.nodes.contains(&node.id) && !groups.contains(&group.name) {
            groups.push(group.name.clone());
        }
    }
    groups
}

/// The new text of each file an edit touched, by absolute path, in edit order.
struct Edited<'t> {
    tree: &'t Tree,
    root: PathBuf,
    texts: Vec<(PathBuf, String)>,
    /// The chosen groups that gain a filter, with the file and declaration to open instead.
    groups: Vec<(String, PathBuf, Range<usize>)>,
}

impl Edited<'_> {
    fn text(&mut self, path: &Path) -> &mut String {
        let at = match self.texts.iter().position(|(p, _)| p == path) {
            Some(at) => at,
            None => {
                let text = self.tree.files[path].to_string();
                self.texts.push((path.into(), text));
                self.texts.len() - 1
            }
        };
        &mut self.texts[at].1
    }

    /// Appends `line` to the winning declaration of `group`, held by source `path`.
    fn filter(&mut self, path: &Path, group: &str, line: &str) -> Result<(), EditError> {
        let relative: PathBuf = path.strip_prefix(&self.root).unwrap().into();
        let original = document(&self.tree.files[path], path);
        let bytes = original
            .as_ref()
            .and_then(|doc| declaration(doc, group))
            .map(|g| g.span().start..g.span().end);
        let text = self.text(path);
        let Some(out) = append_line(text, path, group, line) else {
            return Err(EditError::OpenSource {
                path: relative,
                bytes,
            });
        };
        *text = out;
        self.groups.push((group.into(), relative, bytes.unwrap()));
        Ok(())
    }
}

impl HonkStore {
    /// Adds the node and returns every group that holds it afterwards, in declaration order.
    pub fn add_node(
        &mut self,
        draft: u64,
        name: &str,
        link: &str,
        groups: &[&str],
    ) -> Result<Vec<String>, AddNodeError> {
        let mut tree =
            Tree::read(&self.draft_entry(self.find(draft)?)).map_err(StoreError::from)?;
        let root = tree.entry.parent().unwrap().to_owned();
        let entry: PathBuf = tree.entry.strip_prefix(&root).unwrap().into();
        let raw = || EditError::OpenSource {
            path: entry.clone(),
            bytes: None,
        };
        let before = loaded(&tree, &raw)?;
        let config = &before.config;
        if config.nodes.iter().any(|n| n.name == name)
            || config.groups.iter().any(|g| g.name == name)
        {
            return Err(AddNodeError::Node);
        }
        let (owners, _) = source_indices(&before.sources).map_err(|_| raw())?;
        let appended =
            append_node_source(&before.sources[0], name, link).map_err(|_| AddNodeError::Node)?;
        let mut edited = Edited {
            tree: &tree,
            root: root.clone(),
            texts: vec![(tree.entry.clone(), appended)],
            groups: Vec::new(),
        };
        let line = format!("filter: {}", exact(name));
        for &group in groups {
            let won = winning(config, group).ok_or_else(|| AddNodeError::Group(group.into()))?;
            if !won.own.implicit_all_nodes {
                edited.filter(&before.sources[owners[group]].path, group, &line)?;
            }
        }
        let (texts, filtered) = (edited.texts, edited.groups);
        for (path, text) in &texts {
            tree.files.insert(path.clone(), text.as_str().into());
        }
        let after = loaded(&tree, &raw)?;
        let unread = filtered
            .iter()
            .find(|(g, ..)| !read_back(config, &after.config, g, name));
        if let Some((_, path, bytes)) = unread {
            let (path, bytes) = (path.clone(), Some(bytes.clone()));
            return Err(EditError::OpenSource { path, bytes }.into());
        }
        self.write_files(draft, &root, &tree, &texts)?;
        Ok(holding(&after.config, name))
    }

    fn write_files(
        &mut self,
        draft: u64,
        root: &Path,
        edited: &Tree,
        texts: &[(PathBuf, String)],
    ) -> Result<(), EditError> {
        let original =
            Tree::read(&self.draft_entry(self.find(draft)?)).map_err(StoreError::from)?;
        for (path, text) in texts {
            let raw = || EditError::OpenSource {
                path: path.strip_prefix(root).unwrap().into(),
                bytes: None,
            };
            let file = File::parse(&original, path, &raw)?;
            let candidate = File::parse(edited, path, &raw)?;
            let carry = Carry::diff(&file.source.content, text);
            self.write_steps(draft, path, root, &file, &[(candidate, carry)])?;
        }
        Ok(())
    }
}
