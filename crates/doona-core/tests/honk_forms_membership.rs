mod common;

use std::collections::BTreeSet;
use std::path::Path;

use common::TempDir;
use doonacore::honkconf::HonkStore;
use doonacore::honkconf::edit::EditError;
use doonacore::honkconf::edit::membership::AddNodeError;
use honk_config::Config;
use honk_config::parser::resolve_group_filters;

const ENTRY: &str = "config.dae";
const LINK: &str = "socks5://192.0.2.9:1080";

struct Draft {
    store: HonkStore,
    id: u64,
    _dir: TempDir,
}

impl Draft {
    fn new(files: &[(&str, &str)]) -> Self {
        let dir = TempDir::new("honk-forms-membership");
        let mut store = HonkStore::open(dir.path()).unwrap();
        let id = store.draft(None).unwrap();
        for (path, text) in files {
            store.edit(id, Path::new(path), text).unwrap();
        }
        Self {
            store,
            id,
            _dir: dir,
        }
    }

    fn text(&self, path: &str) -> String {
        self.store
            .text(self.id, Path::new(path))
            .unwrap()
            .to_string()
    }

    fn add(&mut self, name: &str, groups: &[&str]) -> Result<Vec<String>, AddNodeError> {
        self.store.add_node(self.id, name, LINK, groups)
    }

    /// The parsed configuration after honk assembles it with every node present.
    fn assembled(&self) -> Config {
        let checked = self.store.check(self.id).unwrap();
        checked
            .config()
            .clone()
            .assemble(resolve_group_filters)
            .unwrap()
    }
}

/// Node names of the winning declaration of `group`.
fn members(config: &Config, group: &str) -> BTreeSet<String> {
    let group = config.groups.iter().rfind(|g| g.name == group).unwrap();
    let nodes = config.nodes.iter().filter(|n| group.nodes.contains(&n.id));
    nodes.map(|n| n.name.clone()).collect()
}

fn set(names: &[&str]) -> BTreeSet<String> {
    names.iter().map(|n| n.to_string()).collect()
}

const NODES: &str =
    "node {\n  hk1: 'socks5://192.0.2.1:1080'\n  jp1: 'socks5://192.0.2.2:1080'\n}\n";

#[test]
fn an_implicit_all_group_already_holds_the_node_and_is_never_edited() {
    let text = format!(
        "{NODES}group {{\n  All {{ policy: select }}\n  Hk {{\n    filter: name(keyword: hk)\n  }}\n}}\n"
    );
    let mut draft = Draft::new(&[(ENTRY, &text)]);
    let holding = draft.add("新節點", &["All", "Hk"]).unwrap();
    assert_eq!(holding, ["All", "Hk"]);
    let out = draft.text(ENTRY);
    assert!(out.contains("  All { policy: select }\n"), "{out}");
    assert!(
        out.contains("    filter: name(keyword: hk)\n    filter: name('新節點')\n  }"),
        "{out}"
    );
    assert!(
        out.contains(&format!("    '新節點': '{LINK}'\n}}\ngroup")),
        "{out}"
    );
    let config = draft.assembled();
    assert_eq!(members(&config, "All"), set(&["hk1", "jp1", "新節點"]));
    assert_eq!(members(&config, "Hk"), set(&["hk1", "新節點"]));
}

#[test]
fn filtered_and_subgroup_only_groups_gain_the_node_and_keep_their_members() {
    let text = format!(
        "{NODES}group {{\n  Hk {{ filter: name(keyword: hk) && !name(jp1)\n    policy: urltest }}\n  Pick {{ filter: name(jp1) }}\n  Outer {{\n    filter: group(Hk, Pick)\n    policy: fallback\n  }}\n}}\nrouting {{ fallback: Outer }}\n"
    );
    let mut draft = Draft::new(&[(ENTRY, &text)]);
    let holding = draft.add("tw1", &["Hk", "Pick", "Outer"]).unwrap();
    assert_eq!(holding, ["Hk", "Pick", "Outer"]);
    let out = draft.text(ENTRY);
    assert!(
        out.contains("    policy: urltest\n    filter: name('tw1')\n  }"),
        "{out}"
    );
    assert!(
        out.contains("  Pick { filter: name(jp1)\n      filter: name('tw1')\n  }"),
        "{out}"
    );
    assert!(
        out.contains(
            "    filter: group(Hk, Pick)\n    policy: fallback\n    filter: name('tw1')\n  }"
        ),
        "{out}"
    );
    let config = draft.assembled();
    assert_eq!(members(&config, "Hk"), set(&["hk1", "tw1"]));
    assert_eq!(members(&config, "Pick"), set(&["jp1", "tw1"]));
    assert_eq!(members(&config, "Outer"), set(&["tw1"]));
    let outer = config.groups.iter().find(|g| g.name == "Outer").unwrap();
    assert_eq!(outer.groups, ["Hk", "Pick"]);
}

#[test]
fn exact_names_with_quotes_commas_parentheses_dots_and_bars_match_only_themselves() {
    let decoys = "node {\n  a: 'socks5://192.0.2.1:1080'\n  b: 'socks5://192.0.2.2:1080'\n  v1x2: 'socks5://192.0.2.3:1080'\n  p: 'socks5://192.0.2.4:1080'\n  x: 'socks5://192.0.2.5:1080'\n}\n";
    for name in [
        "it's",
        "say \"hi\"",
        "a,b",
        "x(1)",
        "v1.2",
        "p|q",
        "regex:a",
        "keyword: x",
    ] {
        let text = format!("{decoys}group {{ Pick {{ filter: name(b) }} }}\n");
        let mut draft = Draft::new(&[(ENTRY, &text)]);
        assert_eq!(draft.add(name, &["Pick"]).unwrap(), ["Pick"], "{name}");
        assert_eq!(
            members(&draft.assembled(), "Pick"),
            set(&["b", name]),
            "{name}"
        );
    }
}

#[test]
fn the_filter_lands_in_the_winning_declaration_of_the_winning_file() {
    let entry = format!("include {{ 'b.dae' }}\n{NODES}group {{ Hk {{ filter: name(hk1) }} }}\n");
    let b = "group {\r\n\tHk { filter: name(jp1) }\r\n\tHk {\r\n\t\tfilter: name(hk1) # 生效\r\n\t}\r\n}\r\n";
    let mut draft = Draft::new(&[(ENTRY, &entry), ("b.dae", b)]);
    assert_eq!(draft.add("tw1", &["Hk"]).unwrap(), ["Hk"]);
    let expected = b.replace("# 生效\r\n\t}", "# 生效\r\n\t\tfilter: name('tw1')\r\n\t}");
    assert_eq!(draft.text("b.dae"), expected);
    assert!(
        draft
            .text(ENTRY)
            .contains("group { Hk { filter: name(hk1) } }")
    );
    assert_eq!(members(&draft.assembled(), "Hk"), set(&["hk1", "tw1"]));
}

#[test]
fn a_declaration_that_does_not_read_back_opens_its_source() {
    // An unbound chain hop defers every filter at parse time, so no added filter reads back.
    let text = format!(
        "{NODES}node {{ relay: 'socks5://192.0.2.7:1080' -> later }}\ngroup {{ Hk {{ filter: name(hk1) }} }}\n"
    );
    let mut draft = Draft::new(&[(ENTRY, &text)]);
    let start = text.find("Hk {").unwrap();
    let end = start + text[start..].find('}').unwrap() + 1;
    match draft.add("tw1", &["Hk"]) {
        Err(AddNodeError::Edit(EditError::OpenSource { path, bytes })) => {
            assert_eq!((path.to_str().unwrap(), bytes), (ENTRY, Some(start..end)));
        }
        other => panic!("{other:?}"),
    }
    assert_eq!(draft.text(ENTRY), text);
}

#[test]
fn refused_nodes_and_unknown_groups_write_nothing() {
    let text = format!("{NODES}group {{ Hk {{ filter: name(hk1) }} }}\n");
    let mut draft = Draft::new(&[(ENTRY, &text)]);
    for name in ["hk1", "Hk"] {
        assert!(
            matches!(draft.add(name, &[]), Err(AddNodeError::Node)),
            "{name}"
        );
    }
    assert!(matches!(
        draft.store.add_node(draft.id, "tw1", "not a link", &[]),
        Err(AddNodeError::Node)
    ));
    match draft.add("tw1", &["Hk", "Proxy"]) {
        Err(AddNodeError::Group(name)) => assert_eq!(name, "Proxy"),
        other => panic!("{other:?}"),
    }
    assert_eq!(draft.text(ENTRY), text);
}

#[test]
fn a_group_chosen_twice_gains_one_filter() {
    let text = format!("{NODES}group {{ Hk {{ filter: name(hk1) }} }}\n");
    let mut draft = Draft::new(&[(ENTRY, &text)]);
    assert_eq!(draft.add("tw1", &["Hk", "Hk"]).unwrap(), ["Hk"]);
    assert_eq!(draft.text(ENTRY).matches("filter: name('tw1')").count(), 1);
    assert_eq!(members(&draft.assembled(), "Hk"), set(&["hk1", "tw1"]));
}

#[test]
fn a_node_can_join_no_group_with_or_without_groups_in_the_config() {
    // DEC 2026-11-26: any hand-written config, including zero groups and a node in no group.
    for (groups, holding) in [
        ("", vec![]),
        ("group { Hk { filter: name(hk1) } }\n", vec![]),
        ("group { All { policy: select } }\n", vec!["All"]),
    ] {
        let text = format!("{NODES}{groups}");
        let mut draft = Draft::new(&[(ENTRY, &text)]);
        assert_eq!(draft.add("tw1", &[]).unwrap(), holding, "{groups}");
        let config = draft.assembled();
        assert!(config.nodes.iter().any(|n| n.name == "tw1"), "{groups}");
        assert_eq!(config.groups.len(), usize::from(!groups.is_empty()));
    }
}
