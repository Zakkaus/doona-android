use super::*;
use crate::testutil::TempDir;

fn project(text: &str) -> Projection {
    let dir = TempDir::new("honk-groups");
    let path = dir.path().join("main.dae");
    std::fs::write(&path, text).unwrap();
    let cfg = super::super::load(&path).unwrap();
    Projection::build(&cfg, Vec::new(), &[]).unwrap()
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
