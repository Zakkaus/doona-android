//! The embedded honk engine for the committed native configuration.
//!
//! [`Runtime`] builds one [`Engine`] from a configuration tree and an explicit geo data root
//! (the active asset generation); honk searches no other directory and reads no process-wide
//! data dir. It holds the outbound mode and the GLOBAL target, which the engine applies but
//! does not report, so callers read them here. The engine owns no sockets, tasks or probes:
//! [`Runtime::decide`] is a pure lookup for one flow.

use std::fmt;
use std::path::Path;

use honk_core::embedded::Engine;
pub use honk_core::embedded::{
    Candidate, Decision, Flow, GlobalTarget, MatchedRule, Mode, ModeResult, Network, UnknownTarget,
};

use super::{HonkConfig, HonkStore, StoreError};

/// Why no engine could be built.
#[derive(Debug)]
pub enum RuntimeError {
    /// The store has no committed revision.
    NoRevision,
    /// The committed tree could not be read or no longer loads.
    Store(StoreError),
    /// honk refused the configuration, or a geo asset it requires is missing from the data
    /// root or does not parse. The message is honk's.
    Engine(anyhow::Error),
}

impl fmt::Display for RuntimeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NoRevision => f.write_str("no committed honk configuration"),
            Self::Store(e) => write!(f, "committed honk configuration: {e}"),
            Self::Engine(e) => write!(f, "honk engine: {e:#}"),
        }
    }
}

impl std::error::Error for RuntimeError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Store(e) => Some(e),
            Self::Engine(e) => Some(e.as_ref()),
            Self::NoRevision => None,
        }
    }
}

/// One configuration's engine with its mode and GLOBAL target. Build a new runtime to apply
/// another revision.
pub struct Runtime {
    revision: Option<u64>,
    engine: Engine,
    mode: Mode,
    global: Option<GlobalTarget>,
}

impl Runtime {
    /// Builds the engine for the store's committed revision. `data_root` holds `geoip.dat`
    /// and `geosite.dat`.
    pub fn load(store: &HonkStore, data_root: &Path) -> Result<Self, RuntimeError> {
        let revision = store.head().ok_or(RuntimeError::NoRevision)?;
        let doc = store.read_revision(revision).map_err(RuntimeError::Store)?;
        let mut runtime = Self::new(&doc, data_root)?;
        runtime.revision = Some(revision);
        Ok(runtime)
    }

    /// Builds the engine for a loaded document, such as a draft being checked.
    pub fn new(doc: &HonkConfig, data_root: &Path) -> Result<Self, RuntimeError> {
        let engine = Engine::new(doc.config().clone(), data_root).map_err(RuntimeError::Engine)?;
        Ok(Self {
            revision: None,
            engine,
            mode: Mode::Rule,
            global: None,
        })
    }

    /// The committed revision this engine was built from; `None` for [`Runtime::new`].
    pub fn revision(&self) -> Option<u64> {
        self.revision
    }

    pub fn engine(&self) -> &Engine {
        &self.engine
    }

    pub fn mode(&self) -> Mode {
        self.mode
    }

    pub fn set_mode(&mut self, mode: Mode) {
        self.engine.set_mode(mode);
        self.mode = mode;
    }

    /// `None` until a target is set: GLOBAL then blocks every flow that is not `must`.
    pub fn global_target(&self) -> Option<&GlobalTarget> {
        self.global.as_ref()
    }

    /// Keeps the previous target when `target` is not a node or group of this configuration,
    /// or names a node whose name another node shares.
    pub fn set_global_target(&mut self, target: GlobalTarget) -> Result<(), UnknownTarget> {
        self.engine.set_global_target(target.clone())?;
        self.global = Some(target);
        Ok(())
    }

    pub fn decide(&self, flow: &Flow) -> Decision {
        self.engine.decide(flow)
    }
}
