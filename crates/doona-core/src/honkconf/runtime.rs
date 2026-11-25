//! The embedded honk engine for one configuration: the traffic decision of every captured flow.
//!
//! [`Runtime`] builds one [`Engine`] from a configuration tree and an explicit geo data root
//! (the active asset generation); honk searches no other directory and reads no process-wide
//! data dir. The engine runs the rules the support report keeps: a rule it skips is left out as
//! a unit, so the next supported rule matches instead (CP:35). Each matched rule maps back to its
//! source row and the row's meter. A decision that still carries a direct fwmark comes from a
//! marked fallback, which the TUN cannot honour: it is refused, never dialled direct.
//!
//! The engine has no health and no runtime group choice, so a group outbound names the group and
//! the caller selects its leaf from the live group state. The mode and the GLOBAL target are set
//! through a shared reference; a flow reads them once, when it opens.

use std::collections::{BTreeMap, HashSet};
use std::fmt;
use std::ops::Range;
use std::path::{Path, PathBuf};
use std::sync::{Arc, RwLock};

use honk_config::config::{BLOCK_NODE_ID, DIRECT_NODE_ID};
use honk_config::parser::source_edit::RuleSourceLocation;
use honk_core::embedded::Engine;
pub use honk_core::embedded::{
    Candidate, Decision, Flow, GlobalTarget, MatchedRule, Mode, ModeResult, Network, UnknownTarget,
};
use uuid::Uuid;

use super::edit::{RuleRow, carry};
use super::meters::RevisionMeters;
use super::{Capabilities, Disposition, HonkConfig, HonkStore, StoreError};

/// Why no engine could be built.
#[derive(Debug)]
pub enum RuntimeError {
    /// The store has no committed revision.
    NoRevision,
    /// The committed tree could not be read or no longer loads.
    Store(StoreError),
    /// A geo asset the rules need could not be read from the data root (`NotFound` when it is
    /// absent), does not parse, or lacks a label they name (`InvalidData`).
    Asset(std::io::Error),
    /// honk refused the configuration. The message is honk's.
    Config(anyhow::Error),
}

impl From<anyhow::Error> for RuntimeError {
    /// Building the engine reads no file but the geo assets, and honk reports those failures
    /// as `io::Error`.
    fn from(error: anyhow::Error) -> Self {
        match error.downcast::<std::io::Error>() {
            Ok(io) => Self::Asset(io),
            Err(error) => Self::Config(error),
        }
    }
}

impl fmt::Display for RuntimeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NoRevision => f.write_str("no committed honk configuration"),
            Self::Store(e) => write!(f, "committed honk configuration: {e}"),
            Self::Asset(e) => write!(f, "honk geo asset: {e}"),
            Self::Config(e) => write!(f, "honk configuration: {e:#}"),
        }
    }
}

impl std::error::Error for RuntimeError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Store(e) => Some(e),
            Self::Asset(e) => Some(e),
            Self::Config(e) => Some(e.as_ref()),
            Self::NoRevision => None,
        }
    }
}

/// A traffic rule or the fallback as written: where its statement is and the meter its flows
/// charge.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Row {
    /// Ordinal in the configuration's rules; `None` for the fallback.
    pub rule: Option<usize>,
    /// Index into [`HonkConfig::sources`].
    pub source: usize,
    pub bytes: Range<usize>,
    /// The revision's meter for this row; `None` for a configuration that was never committed.
    pub meter: Option<Arc<str>>,
}

/// Where a flow goes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Target {
    Direct,
    /// `block`, a group or node with no eligible leaf, or a marked fallback.
    Refuse,
    /// A group of the configuration; its leaf comes from the live group state.
    Group(String),
    Node(Uuid),
}

/// One flow's decision: honk's, the row that decided it and where the flow goes.
#[derive(Clone, Debug)]
pub struct Verdict {
    pub decision: Decision,
    /// The rule or fallback that chose the outbound; `None` when the mode overrode it.
    pub row: Option<Row>,
    pub target: Target,
}

struct State {
    engine: Engine,
    mode: Mode,
    global: Option<GlobalTarget>,
}

/// One configuration's engine with its mode and GLOBAL target. Build a new runtime to apply
/// another revision.
pub struct Runtime {
    revision: Option<u64>,
    state: RwLock<State>,
    /// The row of each rule the engine runs, in its order.
    rules: Box<[Row]>,
    /// `None` when no source writes the fallback and honk's default applies.
    fallback: Option<Row>,
    groups: HashSet<String>,
}

impl Runtime {
    /// Builds the engine for the store's committed revision against `caps`. `data_root` holds
    /// `geoip.dat` and `geosite.dat`.
    pub fn load(
        store: &HonkStore,
        caps: &Capabilities<'_>,
        data_root: &Path,
    ) -> Result<Self, RuntimeError> {
        let revision = store.head().ok_or(RuntimeError::NoRevision)?;
        let doc = store.read_revision(revision).map_err(RuntimeError::Store)?;
        let meters = store
            .revision_meters(revision)
            .map_err(RuntimeError::Store)?;
        let mut runtime = Self::new(&doc, caps, meters, data_root)?;
        runtime.revision = Some(revision);
        Ok(runtime)
    }

    /// Builds the engine for a loaded document, such as a draft being checked, whose rows charge
    /// `meters`.
    pub fn new(
        doc: &HonkConfig,
        caps: &Capabilities<'_>,
        meters: &RevisionMeters,
        data_root: &Path,
    ) -> Result<Self, RuntimeError> {
        let rows = Rows::new(doc, meters);
        let skipped = skipped(doc, caps);
        let mut config = doc.config().clone();
        let mut rules = Vec::new();
        let mut ordinal = 0..;
        config.routing.rules.retain(|_| {
            let i = ordinal.next().unwrap();
            let kept = !skipped[i];
            if kept {
                rules.push(rows.row(Some(i), &doc.index.rules[i]));
            }
            kept
        });
        let fallback = doc.index.fallback.as_ref().map(|at| rows.row(None, at));
        let groups = config.groups.iter().map(|g| g.name.clone()).collect();
        Ok(Self {
            revision: None,
            state: RwLock::new(State {
                engine: Engine::new(config, data_root)?,
                mode: Mode::Rule,
                global: None,
            }),
            rules: rules.into(),
            fallback,
            groups,
        })
    }

    /// The committed revision this engine was built from; `None` for [`Runtime::new`].
    pub fn revision(&self) -> Option<u64> {
        self.revision
    }

    pub fn mode(&self) -> Mode {
        self.state.read().unwrap().mode
    }

    pub fn set_mode(&self, mode: Mode) {
        let mut state = self.state.write().unwrap();
        state.engine.set_mode(mode);
        state.mode = mode;
    }

    /// `None` until a target is set: GLOBAL then blocks every flow that is not `must`.
    pub fn global_target(&self) -> Option<GlobalTarget> {
        self.state.read().unwrap().global.clone()
    }

    /// Keeps the previous target when `target` is not a node or group of this configuration,
    /// or names a node whose name another node shares.
    pub fn set_global_target(&self, target: GlobalTarget) -> Result<(), UnknownTarget> {
        let mut state = self.state.write().unwrap();
        state.engine.set_global_target(target.clone())?;
        state.global = Some(target);
        Ok(())
    }

    pub fn decide(&self, flow: &Flow) -> Verdict {
        let decision = self.state.read().unwrap().engine.decide(flow);
        let row = match &decision.rule {
            _ if decision.mode != ModeResult::Unchanged => None,
            Some(hit) => Some(self.rules[hit.index as usize].clone()),
            None => self.fallback.clone(),
        };
        let target = if decision.mark.is_some() {
            Target::Refuse
        } else if self.groups.contains(&decision.outbound) {
            Target::Group(decision.outbound.clone())
        } else {
            match decision.candidates.first().map(|leaf| leaf.node_id) {
                Some(DIRECT_NODE_ID) => Target::Direct,
                Some(BLOCK_NODE_ID) | None => Target::Refuse,
                Some(id) => Target::Node(id),
            }
        };
        Verdict {
            decision,
            row,
            target,
        }
    }
}

/// Whether the support report skips each rule, found by its source location.
fn skipped(doc: &HonkConfig, caps: &Capabilities<'_>) -> Vec<bool> {
    let support = doc.support(caps);
    let skips: Vec<_> = support
        .records
        .iter()
        .filter(|r| r.section == "routing" && r.disposition == Disposition::Skip)
        .flat_map(|r| &r.locations)
        .collect();
    doc.index
        .rules
        .iter()
        .map(|at| {
            skips
                .iter()
                .any(|s| s.source == at.source_index && s.bytes == at.bytes)
        })
        .collect()
}

/// Each file's traffic rows with their meters.
struct Rows<'a> {
    doc: &'a HonkConfig,
    rows: BTreeMap<PathBuf, Vec<RuleRow>>,
    meters: &'a RevisionMeters,
}

impl<'a> Rows<'a> {
    fn new(doc: &'a HonkConfig, meters: &'a RevisionMeters) -> Self {
        let root = doc.sources()[0].path.parent().unwrap();
        Self {
            doc,
            rows: carry::tree_rows(&doc.loaded, root),
            meters,
        }
    }

    fn row(&self, rule: Option<usize>, at: &RuleSourceLocation) -> Row {
        let root = self.doc.sources()[0].path.parent().unwrap();
        let path = self.doc.sources()[at.source_index]
            .path
            .strip_prefix(root)
            .unwrap();
        let meter = self.rows.get(path).and_then(|rows| {
            let row = rows
                .iter()
                .find(|row| row.fallback == rule.is_none() && row.bytes == at.bytes)?;
            self.meters
                .get(path)?
                .get(&row.id)
                .map(|m| Arc::from(m.as_str()))
        });
        Row {
            rule,
            source: at.source_index,
            bytes: at.bytes.clone(),
            meter,
        }
    }
}
