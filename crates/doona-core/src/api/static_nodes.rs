//! Local nodes use the same registry transaction as a feed, but only the editor can read a link.

use super::{Core, lock};
use crate::catalog::{MAX_NODES, NodeEntry};
use crate::dto::{
    ErrorCode, IdArgs, StaticBatch, StaticDraft, StaticInput, StaticNode, StaticRemove,
    StaticResult, StaticResults, StaticUpdate,
};
use crate::ids;
use crate::state::State;
use honk_config::node::Node;
use std::collections::{HashMap, HashSet};
use std::sync::Arc;

mod admission;
mod manual;

fn entry(mut node: Node, name: &str, uuid: uuid::Uuid) -> Arc<NodeEntry> {
    name.clone_into(&mut node.name);
    Arc::new(NodeEntry {
        id: ids::short_id(uuid),
        uuid,
        name: name.to_owned(),
        subscription: None,
        node: Arc::new(node),
    })
}

pub(crate) fn stored_entry(stored: &StaticNode) -> Result<Arc<NodeEntry>, StaticResult> {
    let node = admission::parse(&stored.link)?;
    let uuid = ids::static_uuid(&node);
    Ok(entry(node, &stored.name, uuid))
}

struct Candidate {
    stored: StaticNode,
    node: Node,
    uuid: uuid::Uuid,
}

impl Candidate {
    fn parse(row: StaticInput) -> Result<Self, StaticResult> {
        let (name, link) = match row {
            StaticInput::Draft(StaticDraft::Link { name, link }) => (name, link.trim().to_owned()),
            StaticInput::Draft(StaticDraft::Manual(manual)) => manual::link(manual)?,
            StaticInput::Malformed(_) => return Err(invalid("fields")),
        };
        let node = admission::parse(&link)?;
        let fallback = if link.contains('#') || !node.name.ends_with(&node.host) {
            &node.name
        } else {
            let scheme = link.split_once("://").unwrap().0;
            match node.name.split_once('-') {
                Some((prefix, host))
                    if prefix.eq_ignore_ascii_case(scheme) && host == node.host =>
                {
                    &node.host
                }
                _ => &node.name,
            }
        };
        let name = name.as_deref().unwrap_or(fallback).trim();
        if name.is_empty() || name.chars().any(char::is_control) {
            return Err(invalid("name"));
        }
        let name = name.to_owned();
        let uuid = ids::static_uuid(&node);
        let stored = StaticNode {
            id: ids::short_id(uuid),
            name,
            link,
        };
        Ok(Self { stored, node, uuid })
    }

    fn into_parts(self) -> (StaticNode, Arc<NodeEntry>) {
        let entry = entry(self.node, &self.stored.name, self.uuid);
        (self.stored, entry)
    }
}

fn invalid(reason: &str) -> StaticResult {
    StaticResult::Invalid {
        reason: reason.to_owned(),
    }
}
fn unsupported(reason: &str) -> StaticResult {
    StaticResult::Unsupported {
        reason: reason.to_owned(),
    }
}

impl Core {
    pub(super) fn static_or_query(
        &self,
        command: &str,
        args: &str,
        event: &mut crate::diag::Event,
    ) -> Result<String, ErrorCode> {
        use super::{empty, ok, parse};
        match command {
            "previewStaticNodes" => self.static_batch(parse(args)?, false).map(ok),
            "addStaticNodes" => self.static_batch(parse(args)?, true).map(ok),
            "readStaticNode" => self.read_static(parse(args)?).map(ok),
            "updateStaticNode" => self.update_static(parse(args)?).map(ok),
            "removeStaticNode" => self.remove_static(parse(args)?).map(empty),
            _ => self.query_or_diagnostic(command, args, event),
        }
    }

    pub(super) fn static_batch(
        &self,
        args: StaticBatch,
        commit: bool,
    ) -> Result<StaticResults, ErrorCode> {
        if !(1..=200).contains(&args.rows.len()) {
            return Err(ErrorCode::InvalidArgument);
        }
        let _writer = commit.then(|| lock(&self.writer));
        let (mut ids, mut names, mut remaining) = {
            let c = lock(&self.shared.catalog);
            let nodes = &c.state.static_nodes;
            (
                nodes.iter().map(|s| s.id.clone()).collect::<HashSet<_>>(),
                nodes.iter().map(|s| s.name.clone()).collect::<HashSet<_>>(),
                MAX_NODES - c.model.nodes.len(),
            )
        };
        let mut staged = Vec::new();
        let mut rows = Vec::with_capacity(args.rows.len());
        for input in args.rows {
            let mut candidate = match Candidate::parse(input) {
                Ok(c) => c,
                Err(row) => {
                    rows.push(row);
                    continue;
                }
            };
            let id = candidate.stored.id.clone();
            if ids.contains(&id) {
                rows.push(StaticResult::Duplicate { id });
                continue;
            }
            if remaining == 0 {
                rows.push(invalid("catalog_full"));
                continue;
            }
            ids.insert(id.clone());
            remaining -= 1;
            candidate.unique_name(&mut names);
            let name = candidate.stored.name.clone();
            let protocol = candidate.node.protocol().as_str().to_owned();
            rows.push(if commit {
                staged.push(candidate);
                StaticResult::Added { id, name, protocol }
            } else {
                StaticResult::Ready { id, name, protocol }
            });
        }
        if !staged.is_empty() {
            let mut next = lock(&self.shared.catalog).state.clone();
            let mut entries = HashMap::with_capacity(staged.len());
            for c in staged {
                let (stored, entry) = c.into_parts();
                entries.insert(stored.id.clone(), entry);
                next.static_nodes.push(stored);
            }
            self.publish_static(next, entries, "addStaticNodes")?;
        }
        Ok(StaticResults { rows })
    }

    pub(super) fn read_static(&self, args: IdArgs) -> Result<StaticNode, ErrorCode> {
        lock(&self.shared.catalog)
            .state
            .static_nodes
            .iter()
            .find(|s| s.id == args.id)
            .cloned()
            .ok_or(ErrorCode::NotFound)
    }

    pub(super) fn update_static(&self, args: StaticUpdate) -> Result<StaticResult, ErrorCode> {
        let _writer = lock(&self.writer);
        let (mut next, i) = self.editor_state(&args.expected)?;
        let candidate = match Candidate::parse(args.node) {
            Ok(c) => c,
            Err(row) => return Ok(row),
        };
        let id = candidate.stored.id.clone();
        if id != args.expected.id && next.static_nodes.iter().any(|s| s.id == id) {
            return Ok(StaticResult::Duplicate { id });
        }
        let name = candidate.stored.name.clone();
        let protocol = candidate.node.protocol().as_str().to_owned();
        if candidate.stored != args.expected {
            let (stored, entry) = candidate.into_parts();
            next.static_nodes[i] = stored;
            self.publish_static(
                next,
                HashMap::from([(id.clone(), entry)]),
                "updateStaticNode",
            )?;
        }
        Ok(StaticResult::Updated { id, name, protocol })
    }

    pub(super) fn remove_static(&self, args: StaticRemove) -> Result<(), ErrorCode> {
        if !args.confirmed {
            return Err(ErrorCode::InvalidArgument);
        }
        let _writer = lock(&self.writer);
        let (mut next, i) = self.editor_state(&args.expected)?;
        next.static_nodes.remove(i);
        self.publish_static(next, HashMap::new(), "removeStaticNode")
    }

    fn editor_state(&self, expected: &StaticNode) -> Result<(State, usize), ErrorCode> {
        let c = lock(&self.shared.catalog);
        let i = c
            .state
            .static_nodes
            .iter()
            .position(|s| s.id == expected.id)
            .ok_or(ErrorCode::NotFound)?;
        if c.state.static_nodes[i] != *expected {
            return Err(ErrorCode::Busy);
        }
        Ok((c.state.clone(), i))
    }

    fn publish_static(
        &self,
        next: State,
        mut changed: HashMap<String, Arc<NodeEntry>>,
        command: &'static str,
    ) -> Result<(), ErrorCode> {
        let admitting = !changed.is_empty();
        let model = {
            let c = lock(&self.shared.catalog);
            let entries = next
                .static_nodes
                .iter()
                .filter_map(|s| changed.remove(&s.id).or_else(|| c.model.entry(&s.id)))
                .collect();
            c.model.with_static(entries, &next)
        };
        self.commit_nodes(next, model, admitting, command, None)
    }
}

impl Candidate {
    fn unique_name(&mut self, used: &mut HashSet<String>) {
        if used.insert(self.stored.name.clone()) {
            return;
        }
        for n in 2.. {
            let name = format!("{}-{n}", self.stored.name);
            if used.insert(name.clone()) {
                self.stored.name = name;
                break;
            }
        }
    }
}
