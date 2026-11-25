//! The publisher protocol against a fake preparer: prepare outside the store lock, recheck and
//! commit under it, swap only after the head rename, and refuse a start whose head cannot prepare.

mod common;

use std::fs;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering::SeqCst};
use std::sync::{Arc, Mutex};

use common::TempDir;
use doonacore::honkconf::publish::{
    Applied, Generation, Notice, Preparer, PublishError, Publisher, StartError,
};
use doonacore::honkconf::{Capabilities, HonkConfig, HonkStore, StoreError};

const A: &str = "# a\nrouting { fallback: direct }";
const B: &str = "# b\nrouting { fallback: block }";
const C: &str = "# c needs:geo\nrouting { fallback: direct }";
const CRASH: &str = "# crash-on-drop\nrouting { fallback: block }";

type Hook = Box<dyn FnOnce() + Send>;

/// The inputs a test controls: the epoch, the labels that are missing, and one action to run
/// in the middle of the next prepare, while the store is unlocked.
#[derive(Default)]
struct Inputs {
    epoch: AtomicU64,
    missing: Mutex<Vec<&'static str>>,
    during: Mutex<Option<Hook>>,
}

impl Inputs {
    fn during(&self, hook: impl FnOnce() + Send + 'static) {
        *self.during.lock().unwrap() = Some(Box::new(hook));
    }
}

struct Fake(Arc<Inputs>);

/// The entry text a generation was prepared from; `crash-on-drop` panics when it is discarded.
struct Rules(String);

impl Drop for Rules {
    fn drop(&mut self) {
        if self.0.contains("crash-on-drop") {
            panic!("crash after the head rename");
        }
    }
}

impl Preparer for Fake {
    type Generation = Rules;
    type Error = String;

    fn epoch(&self) -> u64 {
        self.0.epoch.load(SeqCst)
    }

    fn prepare(&self, config: &HonkConfig) -> Result<Rules, String> {
        let hook = self.0.during.lock().unwrap().take();
        if let Some(hook) = hook {
            hook();
        }
        let text = config.sources()[0].content.to_string();
        match self
            .0
            .missing
            .lock()
            .unwrap()
            .iter()
            .find(|m| text.contains(*m))
        {
            Some(m) => Err(format!("missing {m}")),
            None => Ok(Rules(text)),
        }
    }
}

struct Setup {
    data: TempDir,
    store: Arc<Mutex<HonkStore>>,
    inputs: Arc<Inputs>,
    publisher: Publisher<Fake>,
}

fn setup(tag: &str) -> Setup {
    let data = TempDir::new(tag);
    let store = Arc::new(Mutex::new(HonkStore::open(data.path()).unwrap()));
    let inputs = Arc::new(Inputs::default());
    let publisher = Publisher::new(Fake(inputs.clone()));
    Setup {
        data,
        store,
        inputs,
        publisher,
    }
}

impl Setup {
    fn draft(&self, text: &str) -> u64 {
        let mut store = self.store.lock().unwrap();
        let id = store.draft(None).unwrap();
        store.edit(id, Path::new("config.dae"), text).unwrap();
        id
    }

    fn apply(&self, draft: u64) -> Result<Applied, PublishError<String>> {
        self.publisher
            .apply(&self.store, draft, &Capabilities::linked())
    }

    fn head(&self) -> (u64, String) {
        let store = self.store.lock().unwrap();
        let head = store.head().unwrap();
        let config = store.read_revision(head).unwrap();
        (head, config.sources()[0].content.to_string())
    }

    fn live_is(&self, generation: &Arc<Generation<Rules>>) -> bool {
        Arc::ptr_eq(&self.publisher.current().unwrap(), generation)
    }

    /// Drop the process state as a crash would and reopen the store from disk.
    fn reopen(self) -> (TempDir, HonkStore) {
        let Setup { data, store, .. } = self;
        drop(store);
        let store = HonkStore::open(data.path()).unwrap();
        (data, store)
    }
}

fn revision_dirs(data: &TempDir) -> Vec<u64> {
    let mut ids: Vec<u64> = fs::read_dir(data.path().join("config/revisions"))
        .unwrap()
        .map(|e| e.unwrap().file_name().to_str().unwrap().parse().unwrap())
        .collect();
    ids.sort_unstable();
    ids
}

fn text(store: &HonkStore, rev: u64) -> String {
    store.read_revision(rev).unwrap().sources()[0]
        .content
        .to_string()
}

#[test]
fn failed_prepare_or_stale_epoch_leaves_head_and_live_generation() {
    let t = setup("publish-refused");
    t.apply(t.draft(A)).unwrap();
    let live = t.publisher.start(&t.store).unwrap();
    t.inputs.missing.lock().unwrap().push("needs:geo");
    let c = t.draft(C);
    assert!(matches!(t.apply(c), Err(PublishError::Prepare(r)) if r == "missing needs:geo"));
    assert_eq!(t.head(), (1, A.to_owned()));
    assert!(t.live_is(&live));

    let inputs = t.inputs.clone();
    t.inputs.during(move || {
        inputs.epoch.fetch_add(1, SeqCst);
    });
    let b = t.draft(B);
    assert!(matches!(
        t.apply(b),
        Err(PublishError::StaleInputs {
            prepared: 0,
            current: 1
        })
    ));
    assert_eq!(t.head(), (1, A.to_owned()));
    assert!(t.live_is(&live));

    // Both refused drafts stay editable, and the next attempt prepares against today's inputs.
    assert!(t.store.lock().unwrap().check(c).is_ok());
    let applied = t.apply(b).unwrap();
    assert_eq!(
        applied,
        Applied {
            rev: 2,
            generation: Some(2)
        }
    );
}

#[test]
fn swap_follows_the_head_rename_and_open_flows_keep_their_generation() {
    let t = setup("publish-swap");
    t.apply(t.draft(A)).unwrap();
    let flow = t.publisher.start(&t.store).unwrap();
    let b = t.draft(B);

    // The rename itself fails: head is a non-empty directory.
    let head = t.data.path().join("config/head");
    let committed = fs::read(&head).unwrap();
    fs::remove_file(&head).unwrap();
    fs::create_dir_all(head.join("blocker")).unwrap();
    assert!(matches!(
        t.apply(b),
        Err(PublishError::Store(StoreError::Io(_)))
    ));
    assert!(t.live_is(&flow));
    fs::remove_dir_all(&head).unwrap();
    fs::write(&head, committed).unwrap();

    let applied = t.apply(b).unwrap();
    assert_eq!(
        applied,
        Applied {
            rev: 2,
            generation: Some(2)
        }
    );
    let new = t.publisher.current().unwrap();
    assert_eq!((new.id, new.rev, new.prepared.0.as_str()), (2, 2, B));
    assert_eq!((flow.id, flow.prepared.0.as_str()), (1, A));
    let old = Arc::downgrade(&flow);
    drop(flow);
    assert!(old.upgrade().is_none());
}

#[test]
fn changes_made_during_prepare_are_rechecked_before_the_rename() {
    let t = setup("publish-cas");
    t.apply(t.draft(A)).unwrap();

    // A direct store apply cannot take the staged slot while the store is unlocked.
    let b = t.draft(B);
    let store = t.store.clone();
    t.inputs.during(move || {
        let mut store = store.lock().unwrap();
        let id = store.draft(None).unwrap();
        store.edit(id, Path::new("config.dae"), C).unwrap();
        let direct = store.apply(id, &Capabilities::linked());
        assert!(matches!(direct, Err(StoreError::ApplyPending)));
    });
    assert_eq!(t.apply(b).unwrap().rev, 2);
    assert_eq!(t.head(), (2, B.to_owned()));

    // The draft is discarded while it is being prepared.
    let b = t.draft(B);
    let store = t.store.clone();
    t.inputs
        .during(move || store.lock().unwrap().discard(b).unwrap());
    assert!(matches!(
        t.apply(b),
        Err(PublishError::Store(StoreError::UnknownDraft))
    ));
    assert_eq!(t.head(), (2, B.to_owned()));

    // The same draft is edited while it is being prepared: the edit survives and commits next.
    let b = t.draft(B);
    let store = t.store.clone();
    let edited = "# edited\nrouting { fallback: block }";
    t.inputs.during(move || {
        let mut store = store.lock().unwrap();
        store.edit(b, Path::new("config.dae"), edited).unwrap();
    });
    assert!(matches!(
        t.apply(b),
        Err(PublishError::Store(StoreError::DraftChanged {
            prepared: 1,
            current: 2
        }))
    ));
    assert_eq!(t.head(), (2, B.to_owned()));
    let text = t.store.lock().unwrap().text(b, Path::new("config.dae"));
    assert_eq!(&*text.unwrap(), edited);
    assert_eq!(t.apply(b).unwrap().rev, 3);
    assert_eq!(t.head(), (3, edited.to_owned()));

    // Another draft's edit runs store cleanup; the staged copy survives it.
    let b = t.draft(B);
    let other = t.draft(A);
    let store = t.store.clone();
    t.inputs.during(move || {
        let mut store = store.lock().unwrap();
        let text = "# other\nrouting { fallback: direct }";
        store.edit(other, Path::new("config.dae"), text).unwrap();
    });
    assert_eq!(
        t.apply(b).unwrap(),
        Applied {
            rev: 4,
            generation: None
        }
    );
    assert_eq!(t.head(), (4, B.to_owned()));
}

#[test]
fn crash_before_the_head_rename_reopens_the_old_head() {
    let t = setup("publish-crash-before");
    t.apply(t.draft(A)).unwrap();
    let b = t.draft(B);
    t.inputs.during(|| panic!("crash before the head rename"));
    assert!(catch_unwind(AssertUnwindSafe(|| t.apply(b))).is_err());
    let (data, store) = t.reopen();
    assert_eq!(store.head(), Some(1));
    assert_eq!(text(&store, 1), A);
    assert_eq!(store.current_draft().unwrap().id, b);
    assert_eq!(revision_dirs(&data), [1]);
}

#[test]
fn crash_after_the_head_rename_reopens_the_new_head() {
    let t = setup("publish-crash-after");
    t.apply(t.draft(A)).unwrap();
    let crash = t.draft(CRASH);
    assert!(catch_unwind(AssertUnwindSafe(|| t.apply(crash))).is_err());
    let (data, store) = t.reopen();
    assert_eq!(store.head(), Some(2));
    assert_eq!(text(&store, 2), CRASH);
    assert!(store.current_draft().is_none());
    assert_eq!(revision_dirs(&data), [1, 2]);
}

#[test]
fn unpreparable_head_refuses_start_and_restores_only_on_request() {
    let t = setup("publish-cold");
    for source in [A, B, C] {
        t.apply(t.draft(source)).unwrap();
    }
    t.inputs.missing.lock().unwrap().push("needs:geo");
    let notice = Notice {
        rejected: 3,
        reason: "missing needs:geo".into(),
        restorable: Some(2),
    };
    assert!(matches!(t.publisher.start(&t.store), Err(StartError::Refused(n)) if n == notice));
    assert!(t.publisher.current().is_none());

    // The notice survives a restart, and a second start still refuses rather than run rev 2.
    let Setup {
        data,
        store,
        publisher,
        ..
    } = t;
    drop(store);
    let store = Mutex::new(HonkStore::open(data.path()).unwrap());
    assert_eq!(store.lock().unwrap().notice(), Some(&notice));
    assert!(matches!(publisher.start(&store), Err(StartError::Refused(n)) if n == notice));
    assert!(publisher.current().is_none());
    assert_eq!(store.lock().unwrap().head(), Some(3));

    let restored = publisher
        .restore(&store, 2, &Capabilities::linked())
        .unwrap();
    assert_eq!(
        restored,
        Applied {
            rev: 4,
            generation: None
        }
    );
    let live = publisher.start(&store).unwrap();
    assert_eq!((live.rev, live.prepared.0.as_str()), (4, B));
    let store = store.lock().unwrap();
    assert_eq!(store.notice(), None);
    assert_eq!(text(&store, 3), C);
}

#[test]
fn start_without_a_preparable_revision_offers_none() {
    let t = setup("publish-none");
    assert!(matches!(
        t.publisher.start(&t.store),
        Err(StartError::NoHead)
    ));
    t.apply(t.draft(C)).unwrap();
    t.inputs.missing.lock().unwrap().push("needs:geo");
    let refused = t.publisher.start(&t.store);
    assert!(matches!(
        refused,
        Err(StartError::Refused(Notice {
            rejected: 1,
            restorable: None,
            ..
        }))
    ));
    assert!(t.publisher.current().is_none());
}

#[test]
fn stopped_apply_returns_no_generation_and_start_prepares_head() {
    let t = setup("publish-stopped");
    assert_eq!(
        t.apply(t.draft(A)).unwrap(),
        Applied {
            rev: 1,
            generation: None
        }
    );
    assert!(t.publisher.current().is_none());
    let first = t.publisher.start(&t.store).unwrap();
    assert_eq!((first.id, first.rev), (1, 1));
    t.publisher.stop();
    assert!(t.publisher.current().is_none());
    assert_eq!(
        t.apply(t.draft(B)).unwrap(),
        Applied {
            rev: 2,
            generation: None
        }
    );
    assert!(t.publisher.current().is_none());
    let second = t.publisher.start(&t.store).unwrap();
    assert_eq!(
        (second.id, second.rev, second.prepared.0.as_str()),
        (2, 2, B)
    );
}

#[test]
fn a_notice_that_cannot_be_written_or_cleared_starts_nothing() {
    let t = setup("publish-notice-io");
    t.apply(t.draft(C)).unwrap();
    t.inputs.missing.lock().unwrap().push("needs:geo");
    // The head rename fails while head is a non-empty directory.
    let head = t.data.path().join("config/head");
    let committed = fs::read(&head).unwrap();
    let block = |blocked: bool| {
        if blocked {
            fs::remove_file(&head).unwrap();
            fs::create_dir_all(head.join("blocker")).unwrap();
        } else {
            fs::remove_dir_all(&head).unwrap();
            fs::write(&head, &committed).unwrap();
        }
    };
    block(true);
    assert!(matches!(
        t.publisher.start(&t.store),
        Err(StartError::Io(_))
    ));
    block(false);
    assert!(matches!(
        t.publisher.start(&t.store),
        Err(StartError::Refused(_))
    ));

    t.inputs.missing.lock().unwrap().clear();
    block(true);
    assert!(matches!(
        t.publisher.start(&t.store),
        Err(StartError::Io(_))
    ));
    assert!(t.publisher.current().is_none());
    block(false);
    assert_eq!(t.publisher.start(&t.store).unwrap().rev, 1);
    assert_eq!(t.store.lock().unwrap().notice(), None);
}
