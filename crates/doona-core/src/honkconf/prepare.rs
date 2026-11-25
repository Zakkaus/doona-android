//! The preparer the publisher runs for real: honk's own code builds each generation.
//!
//! The store has already parsed and validated the revision with honk's `Config`
//! (`validate_detailed`), so a refusal there carries honk's diagnostic and span. Preparation
//! then builds the embedded engine with honk's [`Engine::new`](honk_core::embedded::Engine::new)
//! through [`Runtime::new`]: validate, assemble the groups, load the geo pair the rules need
//! from the data root, and build the router. doona re-implements none of it.
//!
//! The data root is the active geo asset generation. It and every other input the build reads
//! change only through [`HonkPreparer::set_data_root`] and [`HonkPreparer::advance`], which take
//! the locked store, so the publisher's recheck before the head rename sees each change.

use std::path::PathBuf;
use std::sync::RwLock;
use std::sync::atomic::{AtomicU64, Ordering};

use super::publish::Preparer;
use super::runtime::{Runtime, RuntimeError};
use super::{HonkConfig, HonkStore};

pub struct HonkPreparer {
    data_root: RwLock<PathBuf>,
    epoch: AtomicU64,
}

impl HonkPreparer {
    /// `data_root` holds `geoip.dat` and `geosite.dat`; honk searches no other directory.
    pub fn new(data_root: impl Into<PathBuf>) -> Self {
        Self {
            data_root: RwLock::new(data_root.into()),
            epoch: AtomicU64::new(0),
        }
    }

    pub fn data_root(&self) -> PathBuf {
        self.data_root.read().unwrap().clone()
    }

    /// Switch to another asset generation. `_store` is the locked store: an apply preparing
    /// against the old root meanwhile fails its recheck instead of committing.
    pub fn set_data_root(&self, _store: &mut HonkStore, root: impl Into<PathBuf>) {
        *self.data_root.write().unwrap() = root.into();
        self.epoch.fetch_add(1, Ordering::SeqCst);
    }

    /// Another input the build reads has changed, such as the provider snapshot.
    pub fn advance(&self, _store: &mut HonkStore) {
        self.epoch.fetch_add(1, Ordering::SeqCst);
    }
}

impl Preparer for HonkPreparer {
    type Generation = Runtime;
    type Error = RuntimeError;

    fn epoch(&self) -> u64 {
        self.epoch.load(Ordering::SeqCst)
    }

    fn prepare(&self, config: &HonkConfig) -> Result<Runtime, RuntimeError> {
        Runtime::new(config, &self.data_root())
    }
}
