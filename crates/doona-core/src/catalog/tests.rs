//! Stable ids over refreshes and what the catalog shows.

use super::*;
use crate::dto::GroupKind;
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
        parse::admit(s.links.iter().map(String::as_str)).nodes,
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
