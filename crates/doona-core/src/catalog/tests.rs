//! Stable ids over refreshes, the commit rule of probe results, and what the catalog shows.

use super::*;
use crate::subscription::parse;
use crate::testutil::fixture;

fn stored(id: &str, body: &str) -> StoredSubscription {
    StoredSubscription {
        id: id.to_owned(),
        name: id.to_owned(),
        url: format!("https://{id}.invalid/"),
        updated_at_epoch_ms: None,
        used_bytes: None,
        total_bytes: None,
        expires_at_epoch_ms: None,
        error: None,
        links: parse::parse(&fixture(body)).unwrap().links,
    }
}

fn state(subs: &[(&str, &str)]) -> State {
    State {
        subscriptions: subs.iter().map(|(id, body)| stored(id, body)).collect(),
        ..State::default()
    }
}

fn fresh(s: &StoredSubscription) -> Vec<Arc<NodeEntry>> {
    entries(
        &s.id,
        parse::admit(s.links.iter().map(String::as_str))
            .unwrap()
            .nodes,
    )
}

fn ids(model: &Model) -> Vec<(String, String)> {
    let mut ids: Vec<_> = model
        .nodes
        .iter()
        .map(|n| (n.id.clone(), n.name.clone()))
        .collect();
    ids.sort();
    ids
}

fn latency(model: &Model, state: &State, node: &str) -> (Option<u32>, bool) {
    let catalog = model.catalog(state);
    let m = catalog.groups[1]
        .members
        .iter()
        .find(|m| m.id == node)
        .unwrap();
    (m.latency_ms, m.failed)
}

#[test]
fn a_reordered_feed_keeps_its_ids_and_a_repeat_is_one_node() {
    let first = state(&[("s1", "links.txt")]);
    let model = load(&first);
    assert_eq!(model.nodes.len(), 9);
    let mut next = first.clone();
    next.subscriptions[0].links = parse::parse(&fixture("reordered.txt")).unwrap().links;
    let refreshed = model.with(&next, "s1", fresh(&next.subscriptions[0]));
    // One node went and one was renamed; every other id and name stays.
    let before: HashMap<_, _> = ids(&model).into_iter().collect();
    let after = ids(&refreshed);
    assert_eq!(after.len(), 8);
    let renamed: Vec<_> = after
        .iter()
        .filter(|(id, name)| before.get(id) != Some(name))
        .collect();
    assert_eq!(renamed.len(), 1, "{renamed:?}");
    assert_eq!(renamed[0].1, "hk-01 renamed");
    assert!(before.contains_key(&renamed[0].0));
    // Two endpoints share the name hk-01 and keep two ids.
    let hk: Vec<_> = ids(&model)
        .into_iter()
        .filter(|(_, n)| n == "hk-01")
        .collect();
    assert_eq!(hk.len(), 2);
}

#[test]
fn the_template_follows_the_fixture_names() {
    let s = state(&[("s1", "links.txt")]);
    let model = load(&s);
    let catalog = model.catalog(&s);
    let groups: Vec<(&str, GroupKind, usize)> = catalog
        .groups
        .iter()
        .map(|g| (g.id.as_str(), g.kind, g.members.len()))
        .collect();
    assert_eq!(
        groups,
        [
            ("g-Proxy", GroupKind::Selector, 16),
            ("g-Auto", GroupKind::UrlTest, 9),
            ("g-HK", GroupKind::UrlTest, 3),
            ("g-TW", GroupKind::UrlTest, 1),
            ("g-JP", GroupKind::UrlTest, 1),
            ("g-SG", GroupKind::UrlTest, 1),
            ("g-US", GroupKind::UrlTest, 1),
            ("g-KR", GroupKind::UrlTest, 1),
        ]
    );
    let proxy = &catalog.groups[0];
    assert_eq!(proxy.selected_id.as_deref(), Some("g-Auto"));
    assert!(!proxy.pinned && !proxy.testing);
    assert!(
        proxy.members[..7]
            .iter()
            .all(|m| m.is_group && m.subscription_id.is_none())
    );
    assert_eq!(proxy.members[1].name, "HK");
    let node = &proxy.members[7];
    assert!(!node.is_group && node.subscription_id.as_deref() == Some("s1"));
    assert_eq!(catalog.subscriptions[0].nodes, 9);
    // Before any probe an automatic group carries its first member.
    assert_eq!(
        catalog.groups[1].selected_id.as_deref(),
        Some(node.id.as_str())
    );
}

#[test]
fn the_fastest_node_wins_and_a_failed_round_keeps_the_choice() {
    let s = state(&[("s1", "links.txt")]);
    let mut model = load(&s);
    let plan = model.plan(Some("g-Auto")).unwrap();
    assert_eq!(plan.targets.len(), 9);
    let (a, b) = (&plan.targets[3], &plan.targets[5]);
    assert!(model.record(a, Some(80)));
    assert!(model.record(b, Some(40)));
    let auto = |m: &Model| m.catalog(&s).groups[1].selected_id.clone();
    assert_eq!(auto(&model).as_deref(), Some(b.entry.id.as_str()));
    assert!(model.catalog(&s).groups[1].testing);
    model.finish(&plan.groups);
    assert!(!model.catalog(&s).groups[1].testing);
    // A round where every probe fails keeps the current member.
    let plan = model.plan(None).unwrap();
    for t in &plan.targets {
        assert!(model.record(t, None));
    }
    assert_eq!(auto(&model).as_deref(), Some(b.entry.id.as_str()));
    assert_eq!(latency(&model, &s, &b.entry.id), (None, true));
}

#[test]
fn overlapping_tests_keep_only_the_latest_result_and_test_until_both_end() {
    let s = state(&[("s1", "links.txt")]);
    let mut model = load(&s);
    let early = model.plan(Some("g-HK")).unwrap();
    let late = model.plan(Some("g-HK")).unwrap();
    assert_eq!(early.targets.len(), 3);
    assert!(!model.record(&early.targets[0], Some(10)));
    assert!(model.record(&late.targets[0], Some(20)));
    let node = &late.targets[0].entry.id;
    assert_eq!(latency(&model, &s, node), (Some(20), false));
    model.finish(&early.groups);
    assert!(model.catalog(&s).groups[2].testing);
    model.finish(&late.groups);
    assert!(!model.catalog(&s).groups[2].testing);
}

#[test]
fn a_probe_of_a_re_created_or_removed_node_is_dropped() {
    let s = state(&[("s1", "links.txt"), ("s2", "reordered.txt")]);
    let mut model = load(&s);
    let plan = model.plan(None).unwrap();
    // s1 is refreshed with the same feed: its nodes are re-created under a new incarnation.
    let mut next = model.with(&s, "s1", fresh(&s.subscriptions[0]));
    next.carry(&model);
    let mut shrunk = s.clone();
    shrunk.subscriptions.remove(1);
    let mut gone = next.with(&shrunk, "s2", Vec::new());
    gone.carry(&next);
    for t in &plan.targets {
        assert!(!gone.record(t, Some(5)), "{}", t.entry.id);
    }
    // The testing mark survives until the test ends.
    assert!(gone.catalog(&shrunk).groups.iter().all(|g| g.testing));
    gone.finish(&plan.groups);
    assert!(gone.catalog(&shrunk).groups.iter().all(|g| !g.testing));
}

#[test]
fn latencies_carry_over_for_the_nodes_that_stay() {
    let s = state(&[("s1", "links.txt"), ("s2", "reordered.txt")]);
    let mut model = load(&s);
    let plan = model.plan(None).unwrap();
    for t in &plan.targets {
        assert!(model.record(t, Some(30)));
    }
    let mut shrunk = s.clone();
    shrunk.subscriptions.remove(0);
    let mut next = model.with(&shrunk, "s1", Vec::new());
    next.carry(&model);
    let kept = &next.nodes[0].id;
    assert_eq!(latency(&next, &shrunk, kept), (Some(30), false));
    assert_eq!(next.catalog(&shrunk).subscriptions.len(), 1);
    assert_eq!(next.nodes.len(), 8);
}

#[test]
fn a_test_needs_a_group_that_exists_and_some_node() {
    let mut empty = load(&State::default());
    assert_eq!(empty.plan(None).err(), Some(ErrorCode::NoNodes));
    assert_eq!(empty.plan(Some("g-Auto")).err(), Some(ErrorCode::NotFound));
    let mut model = load(&state(&[("s1", "links.txt")]));
    assert_eq!(model.plan(Some("g-DE")).err(), Some(ErrorCode::NotFound));
}

#[test]
fn leaves_follow_pins_and_nested_groups() {
    let s = state(&[("s1", "links.txt")]);
    let model = load(&s);
    let first = &model.nodes[0];
    let leaves = model.leaves(&s.selections);
    assert_eq!(leaves["g-Proxy"].id, first.node.id);
    let last = &model.nodes[8];
    let pinned = BTreeMap::from([("g-Proxy".to_owned(), last.id.clone())]);
    assert_eq!(model.leaves(&pinned)["g-Proxy"].id, last.node.id);
    assert!(load(&State::default()).leaves(&s.selections).is_empty());
}

#[test]
fn a_subscription_that_takes_the_catalog_over_its_budget_loads_empty() {
    let links = |from: usize| -> Vec<String> {
        (from..from + parse::MAX_NODES_PER_SUBSCRIPTION)
            .map(|i| format!("trojan://p{i}@192.0.2.1:443#n{i}"))
            .collect()
    };
    let mut s = state(&[
        ("s1", "links.txt"),
        ("s2", "links.txt"),
        ("s3", "links.txt"),
    ]);
    for (i, sub) in s.subscriptions.iter_mut().enumerate() {
        sub.links = links(i * parse::MAX_NODES_PER_SUBSCRIPTION);
    }
    let model = load(&s);
    assert_eq!(model.nodes.len(), 2 * parse::MAX_NODES_PER_SUBSCRIPTION);
    assert_eq!(model.nodes_of("s3"), 0);
    let kept = model.nodes.len();
    assert_eq!(
        within_budget(
            kept,
            &parse::admit(s.subscriptions[2].links.iter().map(String::as_str))
                .unwrap()
                .nodes
        ),
        Err(ErrorCode::ParseFailed)
    );
}
