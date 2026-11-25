//! Write failures injected through the `FAULTS` seam: call 1 writes the entry, then each file
//! with a filter in edit order, then the restores in reverse, then the draft record.

use std::path::Path;

use super::{AddNodeError, FAULTS};
use crate::honkconf::edit::EditError;
use crate::honkconf::{HonkStore, StoreError};
use crate::testutil::TempDir;

const ENTRY: &str = "config.dae";
const NODES: &str = "node {\n  hk1: 'socks5://192.0.2.1:1080'\n}\n";
const LINK: &str = "socks5://192.0.2.9:1080";

/// A draft of `files`, the store calls of the next add that fail, and the record as JSON.
fn draft(dir: &TempDir, files: &[(&str, &str)], fail: &[u32]) -> (HonkStore, u64, String) {
    let mut store = HonkStore::open(dir.path()).unwrap();
    let id = store.draft(None).unwrap();
    for (path, text) in files {
        store.edit(id, Path::new(path), text).unwrap();
    }
    FAULTS.with_borrow_mut(|faults| *faults = (0, fail.to_vec()));
    let record = serde_json::to_string(&store.manifest).unwrap();
    (store, id, record)
}

fn text(store: &HonkStore, id: u64, path: &str) -> String {
    store.text(id, Path::new(path)).unwrap().to_string()
}

/// Whether `error` is the fault injected at store call `n`.
fn injected(error: &StoreError, n: u32) -> bool {
    matches!(error, StoreError::Io(e) if e.to_string() == format!("injected fault {n}"))
}

fn discarded(store: &HonkStore, id: u64) -> bool {
    matches!(
        store.text(id, Path::new(ENTRY)),
        Err(StoreError::UnknownDraft)
    )
}

fn two_files() -> [(&'static str, String); 2] {
    [
        (ENTRY, format!("include {{ 'b.dae' }}\n{NODES}")),
        ("b.dae", "group { B { filter: name(hk1) } }\n".into()),
    ]
}

#[test]
fn a_failed_second_write_restores_the_entry_and_the_draft_record() {
    let dir = TempDir::new("membership-rollback");
    let files = two_files();
    let files = files.each_ref().map(|(p, t)| (*p, t.as_str()));
    let (mut store, id, record) = draft(&dir, &files, &[2]);
    match store.add_node(id, "tw1", LINK, &["B"]) {
        Err(AddNodeError::Edit(EditError::Store(error))) => assert!(injected(&error, 2)),
        other => panic!("{other:?}"),
    }
    for (path, original) in files {
        assert_eq!(text(&store, id, path), original);
    }
    assert_eq!(serde_json::to_string(&store.manifest).unwrap(), record);
}

#[test]
fn a_failed_file_restore_discards_the_draft_and_keeps_the_original_error() {
    let dir = TempDir::new("membership-unrestored");
    let entry = format!("include {{ 'a.dae', 'b.dae' }}\n{NODES}");
    let a = "group { A { filter: name(hk1) } }\n";
    let b = "group { B { filter: name(hk1) } }\n";
    let files = [(ENTRY, entry.as_str()), ("a.dae", a), ("b.dae", b)];
    // b.dae (call 3) fails, then restoring a.dae (call 4); call 5 discards the draft.
    let (mut store, id, _) = draft(&dir, &files, &[3, 4]);
    match store.add_node(id, "tw1", LINK, &["A", "B"]) {
        Err(AddNodeError::Unrestored {
            error: EditError::Store(error),
            failures,
        }) => {
            assert!(injected(&error, 3));
            assert!(matches!(failures.as_slice(), [f] if injected(f, 4)));
        }
        other => panic!("{other:?}"),
    }
    assert!(discarded(&store, id));
}

#[test]
fn a_failed_record_restore_discards_the_draft_and_reports_a_failed_discard() {
    let files = two_files();
    let files = files.each_ref().map(|(p, t)| (*p, t.as_str()));
    // b.dae (call 2) fails, the entry (call 3) is restored, the record (call 4) fails, and
    // call 5 discards the draft.
    for (fail, reported) in [(&[2, 4][..], &[4][..]), (&[2, 4, 5], &[4, 5])] {
        let dir = TempDir::new("membership-record");
        let (mut store, id, _) = draft(&dir, &files, fail);
        match store.add_node(id, "tw1", LINK, &["B"]) {
            Err(AddNodeError::Unrestored {
                error: EditError::Store(error),
                failures,
            }) => {
                assert!(injected(&error, 2));
                assert_eq!(failures.len(), reported.len());
                assert!(failures.iter().zip(reported).all(|(f, n)| injected(f, *n)));
            }
            other => panic!("{other:?}"),
        }
        assert_eq!(discarded(&store, id), reported.len() == 1, "{fail:?}");
    }
}
