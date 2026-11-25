use super::*;
use honk_config::diagnostic::SettingPath;

use crate::testutil::TempDir;

fn load(text: &str) -> Result<HonkConfig, super::super::HonkConfigError> {
    let dir = TempDir::new("honk-groups");
    let path = dir.path().join("main.dae");
    std::fs::write(&path, text).unwrap();
    super::super::load(&path)
}

fn project(text: &str) -> Projection {
    Projection::build(&load(text).unwrap(), Vec::new(), &[]).unwrap()
}

#[test]
fn a_compact_last_definition_is_the_located_one() {
    let p = project(
        "node { a: 'trojan://s@192.0.2.1:443#a' }\n\
         group {\n X { policy: score }\n X {}\n}\n\
         routing { fallback: X }\n",
    );
    assert_eq!(p.groups[0].kind, Kind::Selector);
    assert_eq!(p.groups[0].location.as_ref().unwrap().line, 4);
}

#[test]
fn groups_without_any_node_have_no_members() {
    let p = project("group {\n Lone { policy: select }\n}\nrouting { fallback: direct }\n");
    assert!(p.nodes.is_empty());
    assert!(p.groups[0].members.is_empty());
    assert_eq!(p.selected("g-Lone"), None);
}

#[test]
fn an_unknown_final_or_exit_never_reaches_the_projection() {
    let cases = [
        ("final", "invalid-group-final-target"),
        ("exit", "invalid-group-exit-target"),
    ];
    for (field, code) in cases {
        let text = format!(
            "node {{ a: 'trojan://s@192.0.2.1:443#a' }}\n\
             group {{\n X {{\n  {field}: nowhere\n }}\n}}\nrouting {{ fallback: X }}\n"
        );
        let cfg = load(&text).unwrap_or_else(|e| panic!("{field} is accepted at load: {e}"));
        let Err(refused) = Projection::build(&cfg, Vec::new(), &[]) else {
            panic!("{field} is refused at assembly");
        };
        assert_eq!(refused.diagnostic.code, code);
        let setting = SettingPath::new("groups").index(1).field(field);
        assert_eq!(refused.diagnostic.setting, setting);
    }
}

#[test]
fn a_repeated_name_keeps_the_last_definition_and_its_position() {
    let p = project(
        "node { a: 'trojan://s@192.0.2.1:443#a' }\n\
         group {\n X { policy: select }\n Y { policy: fallback }\n X { policy: score }\n}\n\
         routing { fallback: X }\n",
    );
    let rows: Vec<_> = p.groups.iter().map(|g| (g.id.as_str(), g.kind)).collect();
    assert_eq!(rows, [("g-Y", Kind::Fallback), ("g-X", Kind::Score)]);
    assert_eq!(p.groups[1].location.as_ref().unwrap().line, 5);
}

#[test]
fn a_selected_subgroup_and_an_unknown_group_resolve_by_id() {
    let p = project(
        "node { a: 'trojan://s@192.0.2.1:443#a' }\n\
         group {\n Outer { filter: group(Inner) }\n Inner { policy: select }\n}\n\
         routing { fallback: Outer }\n",
    );
    assert_eq!(p.selected("g-Outer"), Some("g-Inner"));
    assert_eq!(p.selected("g-Missing"), None);
    assert_eq!(p.selected("Outer"), None, "a group ID carries its prefix");
}

#[test]
fn a_label_drops_one_matching_pair_of_quotes() {
    assert_eq!(unquote_once("'香港'"), "香港");
    assert_eq!(unquote_once("\"Two Words\""), "Two Words");
    assert_eq!(unquote_once("''x''"), "'x'");
    assert_eq!(unquote_once("'x\""), "'x\"");
    assert_eq!(unquote_once("'"), "'");
    assert_eq!(unquote_once("Plain"), "Plain");
}
