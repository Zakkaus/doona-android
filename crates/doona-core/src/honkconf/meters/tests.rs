//! Native rows are not charged by live flows before captured-flow routing; these charges go
//! through the meter API and the usage ledger directly.

use std::path::Path;

use crate::honkconf::HonkStore;
use crate::honkconf::edit::{Edit, RuleRow};
use crate::stats::Clock;
use crate::stats::day::{self, Moved, Usage};
use crate::stats::meter::{DIRECT, Meters as Ledger, Tally};
use crate::testutil::TempDir;

const ENTRY: &str = "config.dae";
const RULE: &str = "  domain(suffix: example.org) -> direct\n";
const EDITED: &str = "  domain(suffix: example.org) -> block\n";
const CLOCK: Clock = Clock {
    boot_ms: 1000,
    mono_ms: 1000,
    wall_ms: 1_700_000_000_000,
    offset_s: 0,
};

fn routing(rules: &str) -> String {
    format!("routing {{\n{rules}  fallback: direct\n}}\n")
}

/// The head's rows in source order, with their meters.
fn head(store: &mut HonkStore) -> Vec<(RuleRow, String)> {
    let draft = store.draft(None).unwrap();
    let rows = store.rule_rows(draft, Path::new(ENTRY)).unwrap();
    store.discard(draft).unwrap();
    let meters = &store.revision_meters(store.head().unwrap()).unwrap()[Path::new(ENTRY)];
    assert_eq!(meters.len(), rows.len());
    rows.into_iter()
        .map(|r| {
            let meter = meters[&r.id].clone();
            (r, meter)
        })
        .collect()
}

fn fold(ledger: &Ledger, usage: &mut Usage) {
    let taken = ledger.take();
    usage.add(
        &CLOCK,
        &Moved {
            outbounds: taken.outbounds,
            rules: taken.rules,
            ..Moved::default()
        },
    );
}

fn up(usage: &Usage, meter: &str) -> u64 {
    let today = usage.get(day::local(&CLOCK).0).unwrap();
    today.rules.get(meter).map_or(0, |r| r[0])
}

#[test]
fn twins_keep_separate_balances_through_edit_move_pause_and_restart() {
    let data = TempDir::new("honk-meters");
    let mut store = HonkStore::open(data.path()).unwrap();
    let draft = store.draft(None).unwrap();
    let twins = routing(&RULE.repeat(2));
    store.edit(draft, Path::new(ENTRY), &twins).unwrap();
    store.apply(draft).unwrap();
    let [(a, a_meter), (_, b_meter), _] = <[_; 3]>::try_from(head(&mut store)).unwrap();
    assert_ne!(a_meter, b_meter);

    let ledger = Ledger::default();
    let mut usage = Usage::default();
    let old_flow = Tally::new(Some(ledger.get_ruled(DIRECT, &a_meter)));
    let b_flow = Tally::new(Some(ledger.get_ruled(DIRECT, &b_meter)));
    old_flow.up(100);
    b_flow.up(7);
    fold(&ledger, &mut usage);

    // Editing the first twin hands its occurrence ID to the second, not its meter.
    let draft = store.draft(None).unwrap();
    let edited = routing(&format!("{EDITED}{RULE}"));
    store.edit(draft, Path::new(ENTRY), &edited).unwrap();
    store.apply(draft).unwrap();
    let [(_, new_meter), (b, b_kept), _] = <[_; 3]>::try_from(head(&mut store)).unwrap();
    assert_eq!(b.id, a.id);
    assert_eq!(b_kept, b_meter);
    assert!(new_meter != a_meter && new_meter != b_meter);

    // A flow opened under the old revision finishes on the old meter.
    old_flow.up(50);
    Tally::new(Some(ledger.get_ruled("block", &new_meter))).up(5);
    b_flow.up(3);
    fold(&ledger, &mut usage);
    assert_eq!(
        [&a_meter, &b_meter, &new_meter].map(|m| up(&usage, m)),
        [150, 10, 5]
    );

    let draft = store.draft(None).unwrap();
    let rows = store.rule_rows(draft, Path::new(ENTRY)).unwrap();
    let path = Path::new(ENTRY);
    store
        .form_edit(
            draft,
            path,
            Edit::Move {
                id: &b.id,
                before: Some(&rows[0].id),
            },
        )
        .unwrap();
    let moved = store.rule_rows(draft, path).unwrap();
    store
        .form_edit(draft, path, Edit::Pause(&moved[0].id))
        .unwrap();
    let paused = store.apply(draft).unwrap();
    let after = head(&mut store);
    assert!(after[0].0.paused);
    assert_eq!(after[0].1, b_meter);
    assert_eq!(after[1].1, new_meter);

    let saved = serde_json::to_vec(&usage).unwrap();
    drop(store);
    let usage: Usage = serde_json::from_slice(&saved).unwrap();
    let mut store = HonkStore::open(data.path()).unwrap();
    assert_eq!(store.head(), Some(paused));
    assert_eq!(head(&mut store), after);
    let draft = store.draft(None).unwrap();
    store
        .form_edit(draft, path, Edit::Resume(&after[0].0.id))
        .unwrap();
    store.apply(draft).unwrap();
    let resumed = head(&mut store);
    assert!(!resumed[0].0.paused);
    assert_eq!(
        resumed
            .iter()
            .map(|(_, m)| up(&usage, m))
            .collect::<Vec<_>>(),
        [10, 5, 0]
    );
}
