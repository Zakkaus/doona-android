//! Publish a committed revision as the live generation that new flows use.
//!
//! An apply copies the draft into the next revision slot under the store lock, prepares that
//! exact copy with the lock released, then, under the store lock again, rechecks the input epoch
//! and the store's compare-and-swap before the head rename. Only an infallible swap follows the
//! rename. Open flows keep the generation they started with until they drop it.
//!
//! Applies and starts run one at a time and prepare with the store unlocked, so edits, reads
//! and discards proceed meanwhile. A replaced generation is dropped after every lock is released.
//!
//! A start re-prepares head. If head cannot prepare, the start is refused with a persistent
//! notice that names the newest retained revision that does prepare; that revision runs only
//! after [`Publisher::restore`] commits it again (decision q-restore1, 2026-11-25).

use std::fmt;
use std::path::Path;
use std::sync::{Arc, Mutex, RwLock};

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
    /// `live` is the generation now running, if any. State that belongs to the running process
    /// rather than to a revision, such as the outbound mode and the GLOBAL target, is carried
    /// over from it here, before the head rename, or the Apply is refused.
    fn prepare(
        &self,
        config: &HonkConfig,
        live: Option<&Self::Generation>,
    ) -> Result<Self::Generation, Self::Error>;
}

/// The prepared state of one committed revision. `id` counts generations installed in this
/// process: it rises by one each time [`Publisher::start`] or a running Apply makes a new
/// generation live, and never otherwise. A stopped Apply commits a revision but installs
/// nothing, so its [`Applied::generation`] is `None`; a no-op Apply keeps the live id.
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
    writer: Mutex<()>,
    live: RwLock<Live<P::Generation>>,
}

impl<P: Preparer> Publisher<P> {
    /// A stopped publisher: applies commit without installing a generation.
    pub fn new(preparer: P) -> Self {
        Self {
            preparer,
            writer: Mutex::new(()),
            live: RwLock::new(Live {
                last: 0,
                current: None,
            }),
        }
    }

    /// The generation a new flow uses; `None` while stopped.
    pub fn current(&self) -> Option<Arc<Generation<P::Generation>>> {
        self.live.read().unwrap().current.clone()
    }

    pub fn stop(&self) {
        let old = self.live.write().unwrap().current.take();
        drop(old);
    }

    /// Commit `draft` and, while running, make its generation live. A draft that holds head's
    /// exact files commits no revision and keeps the live generation, but it is prepared and
    /// refused like any other Apply: an Apply never reports success for bytes that do not
    /// build against today's inputs.
    pub fn apply(
        &self,
        store: &Mutex<HonkStore>,
        draft: u64,
        caps: &Capabilities<'_>,
    ) -> Result<Applied, PublishError<P::Error>> {
        let _writer = self.writer.lock().unwrap();
        let staged = store.lock().unwrap().stage_revision(draft, caps)?;
        let epoch = self.preparer.epoch();
        // Installs take the writer lock, which this apply holds, so `running` stays the live
        // generation until the swap below (or the publisher stops).
        let running = self.current();
        let prepared = self.prepare_entry(&staged.entry, running.as_deref());
        let mut locked = store.lock().unwrap();
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
                locked.abandon();
                return Err(e);
            }
        };
        let unchanged = staged.unchanged;
        let rev = locked.commit(staged)?;
        drop(locked);
        if unchanged {
            drop(prepared);
            return Ok(Applied {
                rev,
                generation: self.current().map(|g| g.id),
            });
        }
        let mut live = self.live.write().unwrap();
        let swapped = live
            .current
            .is_some()
            .then(|| install(&mut live, rev, prepared));
        drop(live);
        Ok(Applied {
            rev,
            generation: swapped.map(|(new, _old)| new.id),
        })
    }

    /// Restore an older revision through a new, freshly prepared commit.
    pub fn restore(
        &self,
        store: &Mutex<HonkStore>,
        rev: u64,
        caps: &Capabilities<'_>,
    ) -> Result<Applied, PublishError<P::Error>> {
        let draft = store.lock().unwrap().draft(Some(rev))?;
        self.apply(store, draft, caps)
    }

    /// Re-prepare head against today's inputs and make it live. An unpreparable head is never
    /// replaced by an older revision here: the start is refused and the notice offers one.
    pub fn start(
        &self,
        store: &Mutex<HonkStore>,
    ) -> Result<Arc<Generation<P::Generation>>, StartError> {
        let _writer = self.writer.lock().unwrap();
        let mut revisions = store.lock().unwrap().revision_entries().into_iter();
        let (head, entry) = revisions.next().ok_or(StartError::NoHead)?;
        let running = self.current();
        let reason = match self.prepare_entry(&entry, running.as_deref()) {
            Ok(prepared) => {
                let mut locked = store.lock().unwrap();
                if locked.notice().is_some() {
                    locked.record_notice(None).map_err(StartError::Io)?;
                }
                drop(locked);
                let (new, _old) = install(&mut self.live.write().unwrap(), head, prepared);
                return Ok(new);
            }
            Err(e) => e.to_string(),
        };
        let restorable = revisions
            .find(|(_, entry)| self.prepare_entry(entry, None).is_ok())
            .map(|(rev, _)| rev);
        let notice = Notice {
            rejected: head,
            reason,
            restorable,
        };
        let mut locked = store.lock().unwrap();
        locked
            .record_notice(Some(notice.clone()))
            .map_err(StartError::Io)?;
        Err(StartError::Refused(notice))
    }

    fn prepare_entry(
        &self,
        entry: &Path,
        live: Option<&Generation<P::Generation>>,
    ) -> Result<P::Generation, PublishError<P::Error>> {
        let config = super::load(entry).map_err(StoreError::from)?;
        self.preparer
            .prepare(&config, live.map(|g| &g.prepared))
            .map_err(PublishError::Prepare)
    }
}

type Swapped<G> = (Arc<Generation<G>>, Option<Arc<Generation<G>>>);

/// Make `prepared` live. Returns it with the generation it replaced, which the caller drops
/// after releasing the live lock.
fn install<G>(live: &mut Live<G>, rev: u64, prepared: G) -> Swapped<G> {
    live.last += 1;
    let generation = Arc::new(Generation {
        id: live.last,
        rev,
        prepared,
    });
    let old = live.current.replace(generation.clone());
    (generation, old)
}
