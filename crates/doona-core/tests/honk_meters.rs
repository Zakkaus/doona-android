mod common;

use std::fs;
use std::path::Path;

use common::TempDir;
use doonacore::honkconf::HonkStore;
use doonacore::honkconf::edit::{Edit, FormEdit, Match, RuleForm, RuleRow};

const ENTRY: &str = "config.dae";
const RULE: &str = "  domain(suffix: example.org) -> direct\n";
const OTHER: &str = "  domain(suffix: example.net) -> block\n";

fn routing(rules: &str) -> String {
    format!("routing {{\n{rules}  fallback: direct\n}}\n")
}

struct Store {
    store: HonkStore,
    data: TempDir,
}

impl Store {
    fn with(rules: &str) -> Self {
        let data = TempDir::new("honk-meters");
        let mut store = HonkStore::open(data.path()).unwrap();
        let draft = store.draft(None).unwrap();
        store
            .edit(draft, Path::new(ENTRY), &routing(rules))
            .unwrap();
        store.apply(draft).unwrap();
        Self { store, data }
    }

    fn rows(&self, draft: u64) -> Vec<RuleRow> {
        self.store.rule_rows(draft, Path::new(ENTRY)).unwrap()
    }

    fn edit(&mut self, draft: u64, edit: Edit<'_>) {
        self.store.form_edit(draft, Path::new(ENTRY), edit).unwrap();
    }

    /// The applied rows' meters in source order.
    fn apply(&mut self, draft: u64) -> Vec<(RuleRow, String)> {
        let rows = self.rows(draft);
        let rev = self.store.apply(draft).unwrap();
        let meters = &self.store.revision_meters(rev).unwrap()[Path::new(ENTRY)];
        assert_eq!(meters.len(), rows.len());
        rows.into_iter()
            .map(|r| {
                let meter = meters[&r.id].clone();
                (r, meter)
            })
            .collect()
    }

    fn form(&mut self, draft: u64, edit: FormEdit<'_>) -> String {
        self.store.rule_form(draft, edit).unwrap().id
    }

    fn head(&mut self) -> Vec<(RuleRow, String)> {
        let draft = self.store.draft(None).unwrap();
        self.apply(draft)
    }
}

fn meters(rows: &[(RuleRow, String)]) -> Vec<&str> {
    rows.iter().map(|(_, m)| m.as_str()).collect()
}

#[test]
fn an_inserted_twin_starts_fresh_although_it_takes_the_first_occurrence_id() {
    let mut s = Store::with(&RULE.repeat(2));
    let before = s.head();
    let draft = s.store.draft(None).unwrap();
    s.edit(
        draft,
        Edit::Insert {
            list: doonacore::honkconf::edit::List::Traffic,
            before: Some(&before[0].0.id),
            statement: RULE.trim(),
        },
    );
    let after = s.apply(draft);
    assert_eq!(after[0].0.id, before[0].0.id);
    assert_eq!(after[1].0.id, before[1].0.id);
    assert_eq!(&meters(&after)[1..], meters(&before));
    assert!(!meters(&before).contains(&meters(&after)[0]));
}

#[test]
fn deleting_a_twin_leaves_the_survivor_its_own_meter() {
    let mut s = Store::with(&RULE.repeat(2));
    let before = s.head();
    let draft = s.store.draft(None).unwrap();
    s.edit(draft, Edit::Delete(&before[0].0.id));
    let after = s.apply(draft);
    assert_eq!(after[0].0.id, before[0].0.id);
    assert_eq!(meters(&after), meters(&before)[1..]);
}

#[test]
fn a_changed_fallback_starts_fresh_and_rules_keep_theirs() {
    let mut s = Store::with(RULE);
    let before = s.head();
    let draft = s.store.draft(None).unwrap();
    s.edit(
        draft,
        Edit::SetFallback {
            id: &before[1].0.id,
            target: "block",
        },
    );
    let after = s.apply(draft);
    assert_eq!(after[0].1, before[0].1);
    assert_ne!(after[1].1, before[1].1);
}

// The draft records the new bytes' meters before it replaces the file. A crash in between
// leaves the old bytes, which must still find their own meters.
#[test]
fn old_bytes_left_by_an_interrupted_edit_keep_their_meters() {
    let mut s = Store::with(&RULE.repeat(2));
    let before = s.head();
    let draft = s.store.draft(None).unwrap();
    let file = s
        .data
        .path()
        .join(format!("config/staging/{draft}/{ENTRY}"));
    let old = fs::read(&file).unwrap();
    s.edit(draft, Edit::Delete(&before[0].0.id));
    drop(s.store);
    fs::write(&file, old).unwrap();
    s.store = HonkStore::open(s.data.path()).unwrap();
    assert_eq!(meters(&s.apply(draft)), meters(&before));
}

#[test]
fn restoring_a_revision_brings_back_its_meters() {
    let mut s = Store::with(RULE);
    let first = s.head();
    let old = s.store.head().unwrap();
    let draft = s.store.draft(None).unwrap();
    s.edit(draft, Edit::Delete(&first[0].0.id));
    s.apply(draft);
    let restore = s.store.draft(Some(old)).unwrap();
    assert_eq!(meters(&s.apply(restore)), meters(&first));
}

fn rule(paused: bool) -> RuleForm<'static> {
    RuleForm {
        matcher: Match::Suffix("a.org"),
        target: "block",
        paused,
    }
}

/// Rows other than `id` keep their meters and `id` holds one no earlier row held.
fn only_fresh(before: &[(RuleRow, String)], after: &[(RuleRow, String)], id: &str) {
    for (row, meter) in after.iter().filter(|(r, _)| r.id != id) {
        assert_eq!(
            before.iter().find(|(r, _)| r.id == row.id).unwrap().1,
            *meter
        );
    }
    let (_, fresh) = after.iter().find(|(r, _)| r.id == id).unwrap();
    assert!(!meters(before).contains(&fresh.as_str()));
}

#[test]
fn a_form_added_rule_starts_fresh_and_its_neighbours_keep_theirs() {
    for paused in [false, true] {
        let mut s = Store::with(&format!("{RULE}{OTHER}"));
        let before = s.head();
        let draft = s.store.draft(None).unwrap();
        let id = s.form(
            draft,
            FormEdit::Add {
                before: Some((Path::new(ENTRY), &before[1].0.id)),
                form: rule(paused),
            },
        );
        let after = s.apply(draft);
        assert_eq!(after.len(), before.len() + 1);
        assert_eq!(after[1].0.paused, paused);
        only_fresh(&before, &after, &id);
    }
}

#[test]
fn a_form_replaced_rule_starts_fresh_active_or_paused() {
    for paused in [false, true] {
        let mut s = Store::with(&format!("{RULE}{OTHER}"));
        let draft = s.store.draft(None).unwrap();
        if paused {
            let id = s.rows(draft)[1].id.clone();
            s.edit(draft, Edit::Pause(&id));
        }
        let before = s.apply(draft);
        assert_eq!(before[1].0.paused, paused);
        let draft = s.store.draft(None).unwrap();
        let id = s.form(
            draft,
            FormEdit::Replace {
                path: Path::new(ENTRY),
                id: &before[1].0.id,
                form: rule(paused),
            },
        );
        let after = s.apply(draft);
        assert_eq!(after.len(), before.len());
        assert_eq!(after[1].0.paused, paused);
        assert_ne!(after[1].0.id, before[1].0.id);
        only_fresh(&before, &after, &id);
    }
}
