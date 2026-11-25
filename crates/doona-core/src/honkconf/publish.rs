//! Publish a committed revision as the live generation that new flows use.
//!
//! An apply copies the draft into the next revision slot under the store lock, prepares that
//! exact copy with the lock released, then, under the store lock again, rechecks the input epoch
//! and the store's compare-and-swap before the head rename. Only an infallible swap follows the
//! rename. Open flows keep the generation they started with until they drop it.
//!
//! A start re-prepares head. If head cannot prepare, the start is refused with a persistent
//! notice that names the newest retained revision that does prepare; that revision runs only
//! after [`Publisher::restore`] commits it again (decision q-restore1, 2026-11-25).

use std::fmt;
use std::sync::{Arc, Mutex, MutexGuard, RwLock};

use serde::{Deserialize, Serialize};

use super::{Capabilities, HonkConfig, HonkStore, StoreError};

/// Builds the runtime state for one revision: traffic and group tables, DNS, provider snapshot
/// and geo pair. The preparer owns the inputs those depend on.
pub trait Preparer {
    type Generation;
    type Error: fmt::Display;
    /// Changes whenever an input the preparer reads changes. Advance it only while holding the
    /// store lock, so that the recheck before the head rename sees every change.
    fn epoch(&self) -> u64;
    fn prepare(&self, config: &HonkConfig) -> Result<Self::Generation, Self::Error>;
}

/// The prepared state of one committed revision. `id` counts installs in this process.
pub struct Generation<G> {
    pub id: u64,
    pub rev: u64,
    pub prepared: G,
}

/// The committed revision, and the generation now live, or `None` when nothing was running.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Applied {
    pub rev: u64,
    pub generation: Option<u64>,
}

/// Why head is not running: the rejected revision, the preparer's reason, and the newest older
/// retained revision that prepares against today's inputs.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize, Serialize)]
pub struct Notice {
    pub rejected: u64,
    pub reason: String,
    pub restorable: Option<u64>,
}

/// Every failure leaves head and the live generation as they were.
#[derive(Debug)]
pub enum PublishError<E> {
    Store(StoreError),
    Prepare(E),
    /// An input changed while the revision was being prepared.
    StaleInputs {
        prepared: u64,
        current: u64,
    },
}

impl<E: fmt::Display> fmt::Display for PublishError<E> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Store(e) => e.fmt(f),
            Self::Prepare(e) => e.fmt(f),
            Self::StaleInputs { .. } => f.write_str("inputs changed during preparation"),
        }
    }
}

impl<E> From<StoreError> for PublishError<E> {
    fn from(e: StoreError) -> Self {
        Self::Store(e)
    }
}

#[derive(Debug)]
pub enum StartError {
    /// No revision has been committed.
    NoHead,
    /// Head does not prepare; the notice is also recorded in the store.
    Refused(Notice),
    /// Recording or clearing the notice failed; nothing was started.
    Io(std::io::Error),
}

struct Live<G> {
    last: u64,
    current: Option<Arc<Generation<G>>>,
}

pub struct Publisher<P: Preparer> {
    preparer: P,
    applying: Mutex<()>,
    live: RwLock<Live<P::Generation>>,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

impl<P: Preparer> Publisher<P> {
    /// A stopped publisher: applies commit without installing a generation.
    pub fn new(preparer: P) -> Self {
        Self {
            preparer,
            applying: Mutex::new(()),
            live: RwLock::new(Live {
                last: 0,
                current: None,
            }),
        }
    }

    /// The generation a new flow uses; `None` while stopped.
    pub fn current(&self) -> Option<Arc<Generation<P::Generation>>> {
        self.live
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .current
            .clone()
    }

    pub fn stop(&self) {
        let old = self
            .live
            .write()
            .unwrap_or_else(|e| e.into_inner())
            .current
            .take();
        drop(old);
    }

    /// Commit `draft` and, while running, make its generation live. Applies run one at a time.
    pub fn apply(
        &self,
        store: &Mutex<HonkStore>,
        draft: u64,
        caps: &Capabilities<'_>,
    ) -> Result<Applied, PublishError<P::Error>> {
        let _applying = lock(&self.applying);
        let staged = lock(store).stage_revision(draft, caps)?;
        let epoch = self.preparer.epoch();
        let prepared = self.prepare_entry(&staged.entry);
        let mut store = lock(store);
        let checked = prepared.and_then(|prepared| match self.preparer.epoch() {
            current if current == epoch => Ok(prepared),
            current => Err(PublishError::StaleInputs {
                prepared: epoch,
                current,
            }),
        });
        let prepared = match checked {
            Ok(prepared) => prepared,
            Err(e) => {
                store.abandon(staged);
                return Err(e);
            }
        };
        let rev = store.commit(staged)?;
        let mut live = self.live.write().unwrap_or_else(|e| e.into_inner());
        let generation = live
            .current
            .is_some()
            .then(|| install(&mut live, rev, prepared).id);
        Ok(Applied { rev, generation })
    }

    /// Restore an older revision through a new, freshly prepared commit.
    pub fn restore(
        &self,
        store: &Mutex<HonkStore>,
        rev: u64,
        caps: &Capabilities<'_>,
    ) -> Result<Applied, PublishError<P::Error>> {
        let draft = lock(store).draft(Some(rev))?;
        self.apply(store, draft, caps)
    }

    /// Re-prepare head against today's inputs and make it live. An unpreparable head is never
    /// replaced by an older revision here: the start is refused and the notice offers one.
    pub fn start(
        &self,
        store: &Mutex<HonkStore>,
    ) -> Result<Arc<Generation<P::Generation>>, StartError> {
        let mut store = lock(store);
        let head = store.head().ok_or(StartError::NoHead)?;
        let reason = match self.prepare_revision(&store, head) {
            Ok(prepared) => {
                if store.notice().is_some() {
                    store.record_notice(None).map_err(StartError::Io)?;
                }
                let mut live = self.live.write().unwrap_or_else(|e| e.into_inner());
                return Ok(install(&mut live, head, prepared));
            }
            Err(e) => e.to_string(),
        };
        let older: Vec<u64> = store.revisions().filter(|&rev| rev != head).collect();
        let restorable = older
            .into_iter()
            .rev()
            .find(|&rev| self.prepare_revision(&store, rev).is_ok());
        let notice = Notice {
            rejected: head,
            reason,
            restorable,
        };
        store
            .record_notice(Some(notice.clone()))
            .map_err(StartError::Io)?;
        Err(StartError::Refused(notice))
    }

    fn prepare_revision(
        &self,
        store: &HonkStore,
        rev: u64,
    ) -> Result<P::Generation, PublishError<P::Error>> {
        self.prepare_entry(&store.revision_entry(rev)?)
    }

    fn prepare_entry(
        &self,
        entry: &std::path::Path,
    ) -> Result<P::Generation, PublishError<P::Error>> {
        let config = super::load(entry).map_err(StoreError::from)?;
        self.preparer
            .prepare(&config)
            .map_err(PublishError::Prepare)
    }
}

fn install<G>(live: &mut Live<G>, rev: u64, prepared: G) -> Arc<Generation<G>> {
    live.last += 1;
    let generation = Arc::new(Generation {
        id: live.last,
        rev,
        prepared,
    });
    live.current = Some(generation.clone());
    generation
}
