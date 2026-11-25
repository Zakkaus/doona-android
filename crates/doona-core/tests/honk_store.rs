mod common;

use std::fs;
use std::path::Path;

use common::TempDir;
use doonacore::honkconf::{Capabilities, HonkConfigError, HonkStore, StoreError};
use honk_config::parser::SourceLimits;

const DIRECT: &str = "routing { fallback: direct }";
const BLOCK: &str = "routing { fallback: block }";

fn apply(store: &mut HonkStore, source: &str) -> u64 {
    let id = store.draft(None).unwrap();
    store.edit(id, Path::new("config.dae"), source).unwrap();
    store.apply(id, &Capabilities::linked()).unwrap()
}

fn revision_dirs(data: &TempDir) -> Vec<u64> {
    let mut ids: Vec<_> = fs::read_dir(data.path().join("config/revisions"))
        .unwrap()
        .map(|e| e.unwrap().file_name().to_str().unwrap().parse().unwrap())
        .collect();
    ids.sort_unstable();
    ids
}

#[test]
fn stale_draft_cannot_overwrite_the_committed_revision() {
    let data = TempDir::new("honk-race");
    let mut store = HonkStore::open(data.path()).unwrap();
    let base = apply(&mut store, DIRECT);
    let drafts = [DIRECT, BLOCK].map(|source| {
        let id = store.draft(None).unwrap();
        store.edit(id, Path::new("config.dae"), source).unwrap();
        id
    });
    let [winner, stale] = drafts;
    let head = store.apply(winner, &Capabilities::linked()).unwrap();
    let conflict = store.apply(stale, &Capabilities::linked()).unwrap_err();
    assert_eq!(revision_dirs(&data), [base, head]);
    assert!(
        matches!(conflict, StoreError::Conflict { base: Some(b), head: Some(h) } if b == base && h == head)
    );
    assert_eq!(store.current_draft().unwrap().id, stale);
    assert_eq!(
        &*store.read_revision(head).unwrap().sources()[0].content,
        DIRECT
    );
    assert!(
        matches!(HonkStore::open(data.path()), Err(StoreError::Io(e)) if e.kind() == std::io::ErrorKind::WouldBlock)
    );
    drop(store);
    assert_eq!(HonkStore::open(data.path()).unwrap().head(), Some(head));
}

#[test]
fn twelve_applies_keep_latest_ten_including_head() {
    let data = TempDir::new("honk-history");
    let mut store = HonkStore::open(data.path()).unwrap();
    let applied: Vec<_> = (0..12).map(|_| apply(&mut store, DIRECT)).collect();
    assert_eq!(store.head(), applied.last().copied());
    assert_eq!(store.revisions().collect::<Vec<_>>(), applied[2..]);
    assert_eq!(revision_dirs(&data), applied[2..]);
}

#[test]
fn live_draft_pins_its_expired_base_until_discard() {
    let data = TempDir::new("honk-pin");
    let mut store = HonkStore::open(data.path()).unwrap();
    let base = apply(&mut store, DIRECT);
    let draft = store.draft(None).unwrap();
    let revisions: Vec<_> = (0..11).map(|_| apply(&mut store, BLOCK)).collect();
    drop(store);
    let mut store = HonkStore::open(data.path()).unwrap();
    assert_eq!(store.current_draft().unwrap().base, Some(base));
    assert_eq!(
        &*store.read_revision(base).unwrap().sources()[0].content,
        DIRECT
    );
    assert_eq!(revision_dirs(&data)[1..], revisions[1..]);
    store.discard(draft).unwrap();
    assert_eq!(revision_dirs(&data), revisions[1..]);
}

#[test]
fn restart_keeps_invalid_current_draft_and_collects_abandoned_files() {
    let data = TempDir::new("honk-restart");
    let mut store = HonkStore::open(data.path()).unwrap();
    let head = apply(&mut store, DIRECT);
    let current = store.draft(None).unwrap();
    let abandoned = store.draft(None).unwrap();
    store
        .edit(abandoned, Path::new("config.dae"), BLOCK)
        .unwrap();
    store
        .edit(current, Path::new("config.dae"), "global {")
        .unwrap();
    let preview = store.draft(Some(head)).unwrap();
    assert!(matches!(
        store.apply(current, &Capabilities::linked()),
        Err(StoreError::Config(_))
    ));
    let root = data.path().join("config");
    fs::create_dir(root.join("staging/unpublished")).unwrap();
    fs::create_dir(root.join("revisions/999")).unwrap();
    fs::write(root.join("staging/edit.tmp"), "half an edit").unwrap();
    drop(store);
    let store = HonkStore::open(data.path()).unwrap();
    assert_eq!(store.head(), Some(head));
    assert_eq!(store.current_draft().unwrap().id, current);
    assert_eq!(
        &*store.text(current, Path::new("config.dae")).unwrap(),
        "global {"
    );
    for name in [
        "staging/unpublished",
        &format!("staging/{abandoned}"),
        &format!("staging/{preview}"),
        "revisions/999",
        "staging/edit.tmp",
    ] {
        assert!(!root.join(name).try_exists().unwrap(), "{name}");
    }
}

#[test]
fn cleanup_failure_does_not_hide_committed_sources() {
    use std::os::unix::fs::PermissionsExt;
    let data = TempDir::new("honk-deferred-cleanup");
    let mut store = HonkStore::open(data.path()).unwrap();
    let rev = apply(&mut store, DIRECT);
    drop(store);
    let abandoned = data.path().join("config/staging/abandoned");
    fs::create_dir(&abandoned).unwrap();
    fs::write(abandoned.join("partial"), "").unwrap();
    fs::set_permissions(&abandoned, fs::Permissions::from_mode(0o500)).unwrap();
    let reopened = HonkStore::open(data.path());
    assert!(
        abandoned.exists(),
        "sweep unexpectedly removed the protected directory"
    );
    fs::set_permissions(&abandoned, fs::Permissions::from_mode(0o700)).unwrap();
    assert_eq!(
        &*reopened.unwrap().read_revision(rev).unwrap().sources()[0].content,
        DIRECT
    );
}

#[test]
fn failed_recovery_head_write_preserves_committed_tree_and_draft_pins() {
    let data = TempDir::new("honk-recovery-write");
    let mut store = HonkStore::open(data.path()).unwrap();
    let head = apply(&mut store, DIRECT);
    let abandoned = store.draft(None).unwrap();
    let current = store.draft(None).unwrap();
    drop(store);
    let staging = data.path().join("config/staging");
    let blocker = staging.join("head.tmp");
    fs::create_dir(&blocker).unwrap();
    let reopened = HonkStore::open(data.path()).unwrap();
    assert_eq!(
        &*reopened.read_revision(head).unwrap().sources()[0].content,
        DIRECT
    );
    assert_eq!(reopened.current_draft().unwrap().id, current);
    assert!(staging.join(abandoned.to_string()).exists());
    drop(reopened);
    fs::remove_dir(blocker).unwrap();
    let recovered = HonkStore::open(data.path()).unwrap();
    assert_eq!(recovered.current_draft().unwrap().id, current);
    assert!(!staging.join(abandoned.to_string()).exists());
}

#[test]
fn repairing_an_unparsable_import_requires_reimport_to_capture_hosts() {
    let data = TempDir::new("honk-repair");
    let bundle = TempDir::new("honk-broken-import");
    let entry = bundle.path().join("main.dae");
    let repaired = "include { child.dae }";
    fs::write(&entry, "include {").unwrap();
    fs::write(
        bundle.path().join("child.dae"),
        "dns { use_host: names.txt }",
    )
    .unwrap();
    fs::write(bundle.path().join("names.txt"), "127.0.0.1 restored.test\n").unwrap();
    let mut store = HonkStore::open(data.path()).unwrap();
    let draft = store.import(&entry).unwrap();
    assert!(store.check(draft).is_err());
    store.edit(draft, Path::new("main.dae"), repaired).unwrap();
    let report = store.check(draft).unwrap().unsupported();
    assert_eq!(report[0].reasons[0].code, "missing_hosts_source");
    fs::write(&entry, repaired).unwrap();
    let imported = store.import(&entry).unwrap();
    assert!(store.check(imported).unwrap().unsupported().is_empty());
    assert_eq!(
        &*store.text(imported, Path::new("names.txt")).unwrap(),
        "127.0.0.1 restored.test\n"
    );
}

#[test]
fn restore_publishes_a_new_revision_with_original_companions_and_entry_name() {
    let data = TempDir::new("honk-restore");
    let bundle = TempDir::new("honk-import");
    let entry = bundle.path().join("office.dae");
    fs::create_dir(bundle.path().join("sub")).unwrap();
    fs::write(
        &entry,
        "include { sub/policy.dae }\ndns { use_host: sub/hosts }",
    )
    .unwrap();
    fs::write(bundle.path().join("sub/policy.dae"), DIRECT).unwrap();
    fs::write(bundle.path().join("sub/hosts"), "# original\r\n").unwrap();
    let mut store = HonkStore::open(data.path()).unwrap();
    let imported = store.import(&entry).unwrap();
    let original = store.apply(imported, &Capabilities::linked()).unwrap();
    let edit = store.draft(None).unwrap();
    store
        .edit(edit, Path::new("sub/policy.dae"), BLOCK)
        .unwrap();
    store
        .edit(edit, Path::new("sub/hosts"), "# changed")
        .unwrap();
    let changed = store.apply(edit, &Capabilities::linked()).unwrap();
    let restore = store.draft(Some(original)).unwrap();
    assert_eq!(store.head(), Some(changed));
    let restored = store.apply(restore, &Capabilities::linked()).unwrap();
    assert!(original < changed && changed < restored);
    assert_eq!(store.head(), Some(restored));
    for (rev, policy, hosts) in [
        (original, DIRECT, "# original\r\n"),
        (changed, BLOCK, "# changed"),
        (restored, DIRECT, "# original\r\n"),
    ] {
        let sources = store.read_revision(rev).unwrap();
        assert!(sources.sources()[0].path.ends_with("office.dae"));
        assert_eq!(&*sources.sources()[1].content, policy);
        assert_eq!(
            fs::read(
                data.path()
                    .join(format!("config/revisions/{rev}/sub/hosts"))
            )
            .unwrap(),
            hosts.as_bytes()
        );
    }
}

#[test]
fn edits_and_imports_enforce_the_shared_companion_budget_and_boundary() {
    let data = TempDir::new("honk-edit-boundary");
    let mut store = HonkStore::open(data.path()).unwrap();
    let head = apply(&mut store, DIRECT);
    let edit = store.draft(None).unwrap();
    assert!(matches!(
        store.edit(edit, Path::new("../escape"), "no"),
        Err(StoreError::InvalidEditPath)
    ));
    assert!(matches!(
        store.text(edit, Path::new("absent.dae")),
        Err(StoreError::MissingFile)
    ));
    let limit = SourceLimits::DEFAULT;
    store
        .edit(edit, Path::new("config.dae"), "dns { use_host: hosts }")
        .unwrap();
    assert!(matches!(
        store.edit(edit, Path::new("hosts"), &"x".repeat(limit.max_bytes)),
        Err(StoreError::Config(HonkConfigError::SourceLimit))
    ));
    store
        .edit(
            edit,
            Path::new("config.dae"),
            "include { ../external/*.dae }",
        )
        .unwrap();
    assert!(!store.check(edit).unwrap().complete());
    assert!(matches!(
        store.apply(edit, &Capabilities::linked()),
        Err(StoreError::Incomplete)
    ));
    let bundle = TempDir::new("honk-companions");
    let entry = bundle.path().join("main.dae");
    let mut config = String::from("dns {\n");
    for i in 0..limit.max_sources {
        config.push_str(&format!("use_host: '{i}.txt'\n"));
        fs::write(bundle.path().join(format!("{i}.txt")), "").unwrap();
    }
    config.push('}');
    fs::write(&entry, config).unwrap();
    assert!(matches!(
        store.import(&entry),
        Err(StoreError::Config(HonkConfigError::SourceLimit))
    ));
    assert_eq!(store.current_draft().unwrap().id, edit);
    assert_eq!(store.head(), Some(head));
}

fn assert_escape(store: &HonkStore, draft: u64, code: &str, declaration: &str) {
    let config = store.check(draft).unwrap();
    let record = &config.unsupported()[0];
    assert_eq!(record.reasons[0].code, code);
    let at = &record.locations[0];
    assert_eq!(
        &config.sources()[at.source].content[at.bytes.clone()],
        declaration
    );
}

// Symlinks are located escapes in an editable draft, never a refused import or a read.
#[test]
fn symlinked_dependencies_are_located_escapes_in_editable_drafts() {
    let data = TempDir::new("honk-link-store");
    let bundle = TempDir::new("honk-link-bundle");
    let outside = TempDir::new("honk-link-sentinel");
    let sentinel = outside.path().join("sentinel.dae");
    fs::write(&sentinel, [0xff]).unwrap();
    std::os::unix::fs::symlink(&sentinel, bundle.path().join("alias.dae")).unwrap();
    let entry = bundle.path().join("main.dae");
    let mut store = HonkStore::open(data.path()).unwrap();
    fs::write(&entry, "include { a*.dae }").unwrap();
    let draft = store.import(&entry).unwrap();
    assert_escape(&store, draft, "include_escape", "a*.dae");
    assert!(matches!(
        store.apply(draft, &Capabilities::linked()),
        Err(StoreError::Incomplete)
    ));
    store.edit(draft, Path::new("main.dae"), DIRECT).unwrap();
    let rev = store.apply(draft, &Capabilities::linked()).unwrap();
    let tree = data.path().join(format!("config/revisions/{rev}"));
    assert!(!tree.join("alias.dae").exists());
    fs::write(&entry, "dns { use_host: alias.dae }").unwrap();
    let draft = store.import(&entry).unwrap();
    assert_escape(&store, draft, "use_host_escape", "use_host: alias.dae");
    let rev = store.apply(draft, &Capabilities::linked()).unwrap();
    let tree = data.path().join(format!("config/revisions/{rev}"));
    assert_eq!(fs::read_link(tree.join("alias.dae")).unwrap(), sentinel);
    let restored = store.draft(Some(rev)).unwrap();
    assert_escape(&store, restored, "use_host_escape", "use_host: alias.dae");
    assert_eq!(fs::read(&sentinel).unwrap(), [0xff]);
}

#[test]
fn a_draft_edited_into_another_format_stays_editable() {
    let data = TempDir::new("honk-format-edit");
    let mut store = HonkStore::open(data.path()).unwrap();
    let draft = store.draft(None).unwrap();
    let yaml = "global:\n  log_level: debug";
    store.edit(draft, Path::new("config.dae"), yaml).unwrap();
    assert_eq!(&*store.text(draft, Path::new("config.dae")).unwrap(), yaml);
    assert!(matches!(
        store.check(draft),
        Err(StoreError::Config(HonkConfigError::NonNativeSource))
    ));
}
