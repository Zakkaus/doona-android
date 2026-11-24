//! Stable ids over refreshes, the commit rule of probe results, and what the catalog shows.

use super::*;
use crate::subscription::parse;
use crate::testutil::fixture;

/// The catalog `state` gives, its failures recorded nowhere.
fn load(state: &State) -> Model {
    super::load(state, &Diag::default())
}

fn urltest(mut model: Model) -> Model {
    for g in &mut model.groups {
        if g.kind == GroupKind::Score {
            g.kind = GroupKind::UrlTest;
        }
    }
    model
}

fn stored(id: &str, body: &str) -> StoredSubscription {
    let parsed = parse::parse(&fixture(body)).unwrap();
    StoredSubscription {
        id: id.to_owned(),
        name: id.to_owned(),
        url: format!("https://{id}.invalid/"),
        format: Some(parsed.format),
        updated_at_epoch_ms: None,
        used_bytes: None,
        total_bytes: None,
        expires_at_epoch_ms: None,
        error: None,
        links: parsed.links,
        document: parsed.document,
        user_agent: None,
    }
}

fn state(subs: &[(&str, &str)]) -> State {
    State {
        subscriptions: subs.iter().map(|(id, body)| stored(id, body)).collect(),
        ..State::default()
    }
}

fn fresh(s: &StoredSubscription) -> Vec<Arc<NodeEntry>> {
    entries(&s.id, stored_nodes(s).unwrap())
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
            ("g-Auto", GroupKind::Score, 9),
            ("g-Streaming", GroupKind::Selector, 17),
            ("g-AI", GroupKind::Selector, 17),
            ("g-HK", GroupKind::Score, 3),
            ("g-TW", GroupKind::Score, 1),
            ("g-JP", GroupKind::Score, 1),
            ("g-SG", GroupKind::Score, 1),
            ("g-US", GroupKind::Score, 1),
            ("g-KR", GroupKind::Score, 1),
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
    let mut model = urltest(load(&s));
    let plan = model.plan(Some("g-Auto")).unwrap();
    assert_eq!(plan.targets.len(), 9);
    let (a, b) = (&plan.targets[3], &plan.targets[5]);
    assert!(model.record(a, Some(80), None));
    assert!(model.record(b, Some(40), None));
    let auto = |m: &Model| m.catalog(&s).groups[1].selected_id.clone();
    assert_eq!(auto(&model).as_deref(), Some(b.entry.id.as_str()));
    assert!(model.catalog(&s).groups[1].testing);
    model.finish(&plan.round());
    assert!(!model.catalog(&s).groups[1].testing);
    // A round where every probe fails keeps the current member.
    let plan = model.plan(None).unwrap();
    for t in &plan.targets {
        assert!(model.record(t, None, None));
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
    assert!(!model.record(&early.targets[0], Some(10), None));
    assert!(model.record(&late.targets[0], Some(20), None));
    let node = &late.targets[0].entry.id;
    assert_eq!(latency(&model, &s, node), (Some(20), false));
    model.finish(&early.round());
    assert!(hk_of(&model.catalog(&s)).testing);
    model.finish(&late.round());
    assert!(!hk_of(&model.catalog(&s)).testing);
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
        assert!(!gone.record(t, Some(5), None), "{}", t.entry.id);
    }
    // The testing mark survives until the test ends.
    assert!(gone.catalog(&shrunk).groups.iter().all(|g| g.testing));
    gone.finish(&plan.round());
    assert!(gone.catalog(&shrunk).groups.iter().all(|g| !g.testing));
}

#[test]
fn latencies_carry_over_for_the_nodes_that_stay() {
    let s = state(&[("s1", "links.txt"), ("s2", "reordered.txt")]);
    let mut model = load(&s);
    let plan = model.plan(None).unwrap();
    for t in &plan.targets {
        assert!(model.record(t, Some(30), None));
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
    let mut s = state(&[("s1", "links.txt")]);
    let model = load(&s);
    let first = &model.nodes[0];
    let leaves = model.leaves(&s);
    assert_eq!(
        leaves["g-Proxy"]
            .tcp
            .as_ref()
            .unwrap()
            .peek(&model.score)
            .unwrap()
            .id,
        first.node.id
    );
    let last = &model.nodes[8];
    s.selections.insert("g-Proxy".into(), last.id.clone());
    assert_eq!(
        model.leaves(&s)["g-Proxy"]
            .tcp
            .as_ref()
            .unwrap()
            .peek(&model.score)
            .unwrap()
            .id,
        last.node.id
    );
    assert!(load(&State::default()).leaves(&s).is_empty());
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

#[test]
fn only_a_success_of_the_current_round_replaces_the_choice() {
    let s = state(&[("s1", "links.txt")]);
    let mut model = urltest(load(&s));
    let auto = |m: &Model| m.catalog(&s).groups[1].selected_id.clone().unwrap();
    let plan = model.plan(Some("g-Auto")).unwrap();
    let id = |i: usize| plan.targets[i].entry.id.clone();
    for (i, t) in plan.targets.iter().enumerate() {
        let ms = match i {
            3 => 10,
            6 => 20,
            5 => 50,
            _ => 90,
        };
        assert!(model.record(t, Some(ms), None));
    }
    assert_eq!(auto(&model), id(3));
    // The fastest fails first; the next fastest still has its earlier timing, then fails too.
    let round = model.plan(Some("g-Auto")).unwrap();
    for i in [3, 6, 5, 0, 1, 2, 4, 7, 8] {
        assert!(model.record(&round.targets[i], None, None));
        assert_eq!(auto(&model), id(3), "after target {i} failed");
    }
    // A success of this round replaces it; an earlier timing of a node still probing does not.
    let round = model.plan(Some("g-Auto")).unwrap();
    assert!(model.record(&round.targets[3], None, None));
    assert!(model.record(&round.targets[5], Some(60), None));
    assert_eq!(auto(&model), id(5));
    assert!(model.record(&round.targets[6], Some(70), None));
    assert_eq!(auto(&model), id(5));
}

#[test]
fn a_refresh_that_drops_the_choice_picks_the_fastest_survivor() {
    let s = state(&[("s1", "links.txt")]);
    let mut model = urltest(load(&s));
    let hk = |m: &Model, s: &State| hk_of(&m.catalog(s)).selected_id.clone().unwrap();
    let plan = model.plan(Some("g-HK")).unwrap();
    let ids: Vec<_> = plan.targets.iter().map(|t| t.entry.id.clone()).collect();
    assert!(model.record(&plan.targets[0], None, None));
    assert!(model.record(&plan.targets[1], Some(20), None));
    assert!(model.record(&plan.targets[2], Some(10), None));
    assert_eq!(hk(&model, &s), ids[2]);
    // HK-02 leaves the feed: the measured hk-01 takes over, not the failed first member.
    let mut next = s.clone();
    next.subscriptions[0]
        .links
        .retain(|l| !l.ends_with("#HK-02"));
    let mut refreshed = urltest(model.with(&next, "s1", fresh(&next.subscriptions[0])));
    refreshed.carry(&model);
    assert_eq!(hk(&refreshed, &next), ids[1]);
    // With nothing measured, a choice that is still a member stays.
    let round = refreshed.plan(Some("g-HK")).unwrap();
    for t in &round.targets {
        assert!(refreshed.record(t, None, None));
    }
    let mut again = urltest(refreshed.with(&next, "s1", fresh(&next.subscriptions[0])));
    again.carry(&refreshed);
    assert_eq!(hk(&again, &next), ids[1]);
}

#[test]
fn a_refresh_during_a_round_ranks_survivors_by_their_kept_times() {
    let s = state(&[("s1", "links.txt")]);
    let mut model = urltest(load(&s));
    let hk = |m: &Model, s: &State| hk_of(&m.catalog(s)).selected_id.clone().unwrap();
    let plan = model.plan(Some("g-HK")).unwrap();
    let ids: Vec<_> = plan.targets.iter().map(|t| t.entry.id.clone()).collect();
    assert!(model.record(&plan.targets[0], None, None));
    assert!(model.record(&plan.targets[1], Some(20), None));
    assert!(model.record(&plan.targets[2], Some(10), None));
    model.finish(&plan.round());
    assert_eq!(hk(&model, &s), ids[2]);
    // Another round starts, and HK-02 leaves the feed before its probes return.
    let round = model.plan(Some("g-HK")).unwrap();
    let mut next = s.clone();
    next.subscriptions[0]
        .links
        .retain(|l| !l.ends_with("#HK-02"));
    let mut refreshed = urltest(model.with(&next, "s1", fresh(&next.subscriptions[0])));
    refreshed.carry(&model);
    assert_eq!(hk(&refreshed, &next), ids[1]);
    // The round's results are dropped, and its end changes nothing.
    for t in &round.targets {
        assert!(!refreshed.record(t, Some(1), None));
    }
    refreshed.finish(&round.round());
    assert_eq!(hk(&refreshed, &next), ids[1]);
}

#[test]
fn a_structured_subscription_keeps_its_ids_across_a_refetch_and_a_restart() {
    for body in ["clash.yaml", "singbox.json", "sip008.json"] {
        let fetch = || entries("s1", parse::parse(&fixture(body)).unwrap().nodes);
        let ids = |nodes: &[Arc<NodeEntry>]| -> Vec<String> {
            nodes.iter().map(|n| n.id.clone()).collect()
        };
        let fetched = ids(&fetch());
        assert_eq!(ids(&fetch()), fetched, "{body}");
        let restarted = load(&state(&[("s1", body)]));
        assert_eq!(ids(&restarted.nodes), fetched, "{body}");
    }
}

/// The catalog's HK group.
fn hk_of(c: &crate::dto::Catalog) -> &crate::dto::Group {
    c.groups.iter().find(|g| g.id == "g-HK").unwrap()
}
