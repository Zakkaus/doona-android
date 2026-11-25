//! Slice 7, offline half: honk groups and the node list over static and fetched inventory.

use std::path::Path;

use doonacore::honkconf::groups::{Fetched, Kind, Member, Origin, Pin, PinError, Projection};
use doonacore::honkconf::{HonkConfig, load};
use honk_config::diagnostic::SettingPath;
use honk_config::node::Node;

fn fixture(name: &str) -> HonkConfig {
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/honk/groups");
    load(path.join(name)).unwrap_or_else(|e| panic!("{name}: {e}"))
}

fn project(name: &str) -> Projection {
    Projection::build(&fixture(name), Vec::new(), &[]).unwrap()
}

fn office(links: &[&str]) -> Vec<Fetched> {
    vec![Fetched {
        subscription: "office".into(),
        scope: "s1".into(),
        nodes: links
            .iter()
            .map(|l| Node::from_share_link(l).unwrap())
            .collect(),
    }]
}

const HK: &str = "trojan://secret@198.51.100.1:443#hk";
const JP: &str = "trojan://secret@198.51.100.2:443#jp";
const US: &str = "trojan://secret@198.51.100.3:443#us";

fn node<'a>(p: &'a Projection, name: &str) -> &'a str {
    &p.nodes.iter().find(|n| n.name == name).unwrap().id
}

fn members(p: &Projection, group: &str) -> Vec<String> {
    let row = p.groups.iter().find(|g| g.id == group).unwrap();
    row.members.iter().map(|m| m.id().to_owned()).collect()
}

#[test]
fn every_policy_maps_to_its_kind_without_a_proxy_group() {
    let p = project("policies.dae");
    let kinds: Vec<_> = p.groups.iter().map(|g| (g.id.as_str(), g.kind)).collect();
    assert_eq!(
        kinds,
        [
            ("g-Pick", Kind::Selector),
            ("g-Fastest", Kind::UrlTest),
            ("g-Turns", Kind::LoadBalance),
            ("g-Spread", Kind::LoadBalance),
            ("g-Backup", Kind::Fallback),
            ("g-Rated", Kind::Score),
            ("g-Timed", Kind::UrlTest),
            ("g-Odd", Kind::Selector),
        ]
    );
    let names: Vec<_> = p.groups.iter().map(|g| g.kind.as_str()).collect();
    assert_eq!(
        names,
        [
            "Selector",
            "UrlTest",
            "LoadBalance",
            "LoadBalance",
            "Fallback",
            "Score",
            "UrlTest",
            "Selector"
        ]
    );
}

#[test]
fn an_unknown_policy_is_a_selector_with_a_diagnostic_at_its_line() {
    let cfg = fixture("policies.dae");
    let p = Projection::build(&cfg, Vec::new(), &[]).unwrap();
    let odd = p.groups.iter().find(|g| g.id == "g-Odd").unwrap();
    assert_eq!(odd.kind, Kind::Selector);
    let line = odd.location.as_ref().unwrap().line;
    let policy = SettingPath::new("groups").index(8).field("policy");
    assert!(
        cfg.diagnostics()
            .iter()
            .any(|d| d.setting == policy && d.line == Some(line)),
        "{:?}",
        cfg.diagnostics()
    );
}

#[test]
fn three_levels_nest_and_select_through_each_level() {
    let p = project("nested.dae");
    let a = node(&p, "a").to_owned();
    assert_eq!(members(&p, "g-Top"), ["g-Mid"]);
    assert_eq!(members(&p, "g-Mid"), ["g-Leaf"]);
    assert_eq!(members(&p, "g-Leaf"), [a.as_str()]);
    assert_eq!(p.selected("g-Top"), Some("g-Mid"));
    assert_eq!(p.selected("g-Leaf"), Some(a.as_str()));
}

#[test]
fn a_cycle_loses_only_its_closing_edge() {
    let p = project("nested.dae");
    let b = node(&p, "b").to_owned();
    assert_eq!(members(&p, "g-A"), ["g-B"]);
    assert_eq!(members(&p, "g-B"), ["g-C"]);
    assert_eq!(members(&p, "g-C"), [b.as_str()]);
}

#[test]
fn zero_groups_with_a_direct_fallback_keep_every_node() {
    let cfg = fixture("zero.dae");
    assert_eq!(
        cfg.config().routing.default_outbound,
        "direct",
        "the fixture falls back to direct"
    );
    let p = Projection::build(&cfg, Vec::new(), &[]).unwrap();
    assert!(p.groups.is_empty());
    let names: Vec<_> = p.nodes.iter().map(|n| n.name.as_str()).collect();
    assert_eq!(names, ["a", "b"]);
}

#[test]
fn a_node_in_no_group_is_still_listed() {
    let p = Projection::build(&fixture("feeds.dae"), office(&[HK, JP]), &[]).unwrap();
    let lonely = node(&p, "lonely");
    assert!(
        p.groups
            .iter()
            .all(|g| g.members.iter().all(|m| m.id() != lonely))
    );
    let listed: Vec<_> = p
        .nodes
        .iter()
        .map(|n| (n.name.as_str(), &n.origin))
        .collect();
    let fetched = Origin::Subscription("s1".into());
    assert_eq!(
        listed,
        [
            ("lonely", &Origin::Static),
            ("hk", &fetched),
            ("jp", &fetched)
        ]
    );
}

#[test]
fn an_unfiltered_group_skips_built_ins_and_a_literal_name_admits_one() {
    let p = project("builtins.dae");
    let a = node(&p, "a").to_owned();
    let all = p.groups.iter().find(|g| g.id == "g-Everything").unwrap();
    assert!(all.implicit_all);
    assert_eq!(all.members, [Member::Node(a.clone())]);
    let home = p.groups.iter().find(|g| g.id == "g-Home").unwrap();
    assert!(!home.implicit_all);
    assert_eq!(home.members, [Member::Node(a), Member::Builtin("direct")]);
    assert!(
        p.nodes
            .iter()
            .all(|n| n.name != "direct" && n.name != "block")
    );
}

#[test]
fn defaults_finals_exits_and_locations_name_catalog_ids() {
    let p = Projection::build(&fixture("feeds.dae"), office(&[HK, JP]), &[]).unwrap();
    let (hk, lonely) = (node(&p, "hk"), node(&p, "lonely"));
    let row = |id: &str| p.groups.iter().find(|g| g.id == id).unwrap();
    assert_eq!(row("g-Office").default.as_deref(), Some(hk));
    assert_eq!(row("g-Office").final_target.as_deref(), Some("direct"));
    assert_eq!(row("g-Quick").final_target.as_deref(), Some("g-Office"));
    assert_eq!(row("g-Spare").final_target.as_deref(), Some(lonely));
    assert_eq!(row("g-Spare").exit.as_deref(), Some(lonely));
    let office = row("g-Office").location.clone().unwrap();
    assert_eq!((office.source, office.line), (0, 9));
    let spare = row("g-Spare").location.clone().unwrap();
    assert_eq!((spare.source, spare.line), (1, 2));
}

#[test]
fn a_pin_on_a_removed_member_is_pruned_and_default_and_final_stay() {
    let cfg = fixture("feeds.dae");
    let before = Projection::build(&cfg, office(&[HK, JP, US]), &[]).unwrap();
    let pins = [
        Pin {
            group: "g-Office".into(),
            member: node(&before, "us").into(),
        },
        Pin {
            group: "g-Quick".into(),
            member: node(&before, "jp").into(),
        },
    ];
    let after = Projection::build(&cfg, office(&[HK, JP]), &pins).unwrap();
    assert_eq!(after.pruned, pins[..1]);
    let office = after.groups.iter().find(|g| g.id == "g-Office").unwrap();
    let hk = node(&after, "hk");
    assert_eq!(office.default.as_deref(), Some(hk));
    assert_eq!(office.final_target.as_deref(), Some("direct"));
    assert_eq!(after.selected("g-Office"), Some(hk));
    assert_eq!(after.selected("g-Quick"), Some(node(&after, "jp")));
}

#[test]
fn reordering_a_provider_keeps_member_ids() {
    let cfg = fixture("feeds.dae");
    let first = Projection::build(&cfg, office(&[HK, JP, US]), &[]).unwrap();
    let again = Projection::build(&cfg, office(&[US, HK, JP]), &[]).unwrap();
    let mut ids = members(&first, "g-Office");
    let mut moved = members(&again, "g-Office");
    assert_ne!(ids, moved, "feed order is member order");
    ids.sort();
    moved.sort();
    assert_eq!(ids, moved);
    assert_eq!(node(&first, "us"), node(&again, "us"));
}

#[test]
fn a_provider_the_configuration_no_longer_declares_adds_no_node() {
    let mut feeds = office(&[HK]);
    feeds[0].subscription = "removed".into();
    let p = Projection::build(&fixture("feeds.dae"), feeds, &[]).unwrap();
    let names: Vec<_> = p.nodes.iter().map(|n| n.name.as_str()).collect();
    assert_eq!(names, ["lonely"]);
    assert!(members(&p, "g-Office").is_empty());
}

#[test]
fn pins_follow_the_kind() {
    let p = Projection::build(&fixture("feeds.dae"), office(&[HK, JP]), &[]).unwrap();
    let (hk, jp) = (node(&p, "hk").to_owned(), node(&p, "jp").to_owned());
    p.pin("g-Office", Some(&jp)).unwrap();
    assert_eq!(p.selected("g-Office"), Some(jp.as_str()));
    assert_eq!(p.pin("g-Office", None), Err(PinError::SelectorClear));
    assert_eq!(
        p.selected("g-Spare"),
        None,
        "load balancing has no single choice"
    );
    p.pin("g-Spare", Some(&jp)).unwrap();
    assert_eq!(p.selected("g-Spare"), Some(jp.as_str()));
    p.pin("g-Spare", None).unwrap();
    assert_eq!(p.selected("g-Spare"), None);
    assert_eq!(p.pin("g-Spare", Some(&hk)), Err(PinError::NotMember));
    assert_eq!(p.pin("g-Gone", Some(&hk)), Err(PinError::UnknownGroup));
}

#[test]
fn an_endpoint_already_listed_is_kept_once_first_wins() {
    let mut feeds = office(&[HK, "trojan://secret@192.0.2.9:443#lonely-again"]);
    feeds.push(Fetched {
        subscription: "office".into(),
        scope: "s2".into(),
        nodes: vec![Node::from_share_link(HK).unwrap()],
    });
    let p = Projection::build(&fixture("feeds.dae"), feeds, &[]).unwrap();
    let listed: Vec<_> = p
        .nodes
        .iter()
        .map(|n| (n.name.as_str(), &n.origin))
        .collect();
    let s1 = Origin::Subscription("s1".into());
    assert_eq!(listed, [("lonely", &Origin::Static), ("hk", &s1)]);
    let kept = |name| node(&p, name).to_owned();
    let shadowed: Vec<_> = p
        .shadowed
        .iter()
        .map(|s| (s.scope.as_str(), s.name.as_str(), s.kept.clone()))
        .collect();
    assert_eq!(
        shadowed,
        [
            ("s1", "lonely-again", kept("lonely")),
            ("s2", "hk", kept("hk"))
        ]
    );
}

#[test]
fn a_group_named_proxy_is_an_ordinary_group() {
    let p = project("proxy.dae");
    let rows: Vec<_> = p.groups.iter().map(|g| (g.id.as_str(), g.kind)).collect();
    assert_eq!(
        rows,
        [("g-Auto", Kind::UrlTest), ("g-Proxy", Kind::Fallback)]
    );
    assert_eq!(members(&p, "g-Proxy"), [node(&p, "b")]);
    assert!(!p.groups[1].implicit_all);
}

#[test]
fn block_members_empty_groups_and_subgroup_pins() {
    let p = project("edges.dae");
    let row = |id: &str| p.groups.iter().find(|g| g.id == id).unwrap();
    assert_eq!(row("g-Stop").members, [Member::Builtin("block")]);
    assert_eq!(row("g-Stop").final_target.as_deref(), Some("block"));
    assert!(row("g-Empty").members.is_empty());
    assert!(!row("g-Empty").implicit_all);
    assert_eq!(p.selected("g-Empty"), None);
    assert_eq!(p.selected("g-Outer"), Some("g-Empty"));
    p.pin("g-Outer", Some("g-Stop")).unwrap();
    assert_eq!(p.selected("g-Outer"), Some("g-Stop"));
}

#[test]
fn a_compact_block_is_located_and_an_empty_include_declares_nothing() {
    let cfg = fixture("edges.dae");
    assert_eq!(cfg.sources().len(), 2, "blank.dae is admitted");
    let p = Projection::build(&cfg, Vec::new(), &[]).unwrap();
    let bare = p.groups.iter().find(|g| g.id == "g-Bare").unwrap();
    let at = bare.location.as_ref().map(|l| (l.source, l.line));
    assert_eq!(at, Some((0, 12)));
    assert_eq!(bare.members, [Member::Node(node(&p, "a").into())]);
}

#[test]
fn a_quoted_name_keeps_its_quotes_in_the_id_and_drops_them_in_the_label() {
    let p = project("quoted.dae");
    let rows: Vec<_> = p
        .groups
        .iter()
        .map(|g| (g.id.as_str(), g.label()))
        .collect();
    assert_eq!(
        rows,
        [
            ("g-'香港'", "香港"),
            ("g-\"東京 1\"", "東京 1"),
            ("g-Plain", "Plain"),
        ]
    );
    assert_eq!(members(&p, "g-Plain"), ["g-\"東京 1\""]);
    assert_eq!(members(&p, "g-\"東京 1\""), ["g-'香港'"]);
    let plain = p.groups.iter().find(|g| g.id == "g-Plain").unwrap();
    assert_eq!(plain.final_target.as_deref(), Some("g-'香港'"));
}
