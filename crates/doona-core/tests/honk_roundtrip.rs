mod common;

use std::fs;
use std::path::Path;

use common::TempDir;
use doonacore::honkconf::HonkStore;
use doonacore::honkconf::bundle::{ExportError, ExportIssue, ExportMode, HonkBundle};
use honk_config::Config;
use honk_config::parser::{LoadedConfig, SourceLimits};

fn stock(entry: &Path) -> LoadedConfig {
    Config::from_dae_file_with_sources(
        entry,
        &Default::default(),
        SourceLimits::DEFAULT,
        &mut vec![],
    )
    .unwrap()
}

fn write(root: &Path, name: &Path, text: &str) {
    let path = root.join(name);
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    fs::write(path, text).unwrap();
}

fn apply(store: &mut HonkStore, files: &[(&str, &str)]) -> u64 {
    let import = TempDir::new("bundle-import");
    for &(name, text) in files {
        write(import.path(), Path::new(name), text);
    }
    let draft = store.import(&import.path().join(files[0].0)).unwrap();
    store.apply(draft).unwrap()
}

// Exclude only metadata generated on each stock parse of this native fixture.
fn authored(mut config: Config) -> Config {
    for node in &mut config.nodes {
        node.created_at = Default::default();
        node.updated_at = Default::default();
    }
    for group in &mut config.groups {
        group.id = Default::default();
        group.created_at = Default::default();
    }
    for feed in &mut config.subscriptions {
        feed.id = Default::default();
        feed.created_at = Default::default();
    }
    config
}

const FILES: [(&str, &str); 5] = [
    (
        "office.dae",
        "include { z.dae parts/*.dae }\r\ndns {\n upstream { local: 'udp://192.0.2.53:53' -> direct }\n routing {\n request { qname(suffix: example.test) -> local\n fallback: asis }\n response { ip(192.0.2.0/24) -> accept\n fallback: reject }\n }\n}\r\n",
    ),
    (
        "z.dae",
        "# nested first\ninclude { nested/node.dae }\nglobal { log_level: debug }",
    ),
    (
        "nested/node.dae",
        "node {\n 'socks5://192.0.2.1:1080#office'\n 'socks5://192.0.2.2:1080#home'\n}\ngroup {\n Proxy { filter: name(office)\n policy: fixed(0) }\n Home { filter: name(home)\n policy: fallback }\n}\nsubscription { remote: 'https://example.test/feed' }",
    ),
    (
        "parts/b.dae",
        "routing { dport(80) -> direct(mark: 64, must)\n fallback: direct(mark: 256) }",
    ),
    ("parts/a.dae", "routing { dport(443) -> Proxy }"),
];

#[test]
fn moved_tree_preserves_authored_config_include_order_and_bytes() {
    let original = TempDir::new("bundle-original");
    for (name, text) in FILES {
        write(original.path(), Path::new(name), text);
    }
    let entry = original.path().join("office.dae");
    let before = authored(stock(&entry).config);
    assert_eq!(before.nodes.len(), 2);
    assert_eq!(before.groups[0].nodes, [before.nodes[0].id]);
    assert_eq!(before.groups[1].nodes, [before.nodes[1].id]);
    assert!(
        authored(stock(&entry).config) == before,
        "same-file control"
    );
    let data = TempDir::new("bundle-store");
    let mut store = HonkStore::open(data.path()).unwrap();
    let draft = store.import(&entry).unwrap();
    let rev = store.apply(draft).unwrap();
    drop(original);
    apply(&mut store, &[("other.dae", "routing { fallback: block }")]);
    for mode in [ExportMode::Archive, ExportMode::Portable] {
        let HonkBundle::Tree {
            manifest,
            companions,
            issues,
        } = store.export(rev, mode).unwrap()
        else {
            panic!("expected relative tree")
        };
        assert!(issues.is_empty());
        let order = [
            "office.dae",
            "z.dae",
            "nested/node.dae",
            "parts/a.dae",
            "parts/b.dae",
        ]
        .map(Path::new);
        assert!(manifest.iter().map(|f| f.path.as_path()).eq(order));
        assert!(companions.is_empty());
        let destination = TempDir::new("bundle-destination");
        let staged = destination.path().join("staged");
        for file in manifest {
            write(&staged, &file.path, &file.content);
        }
        let moved = destination.path().join("moved");
        fs::rename(staged, &moved).unwrap();
        let after = stock(&moved.join("office.dae"));
        assert!(
            after
                .sources
                .iter()
                .map(|s| s.path.strip_prefix(&moved).unwrap())
                .eq(order)
        );
        assert!(authored(after.config) == before, "authored config changed");
        for (name, text) in FILES {
            assert_eq!(fs::read(moved.join(name)).unwrap(), text.as_bytes());
        }
    }
}

#[test]
fn self_contained_revision_is_one_unmodified_file() {
    let data = TempDir::new("bundle-single");
    let mut store = HonkStore::open(data.path()).unwrap();
    let text = "# original\r\nrouting { fallback: direct }\r\n";
    let rev = apply(&mut store, &[("single.dae", text)]);
    for mode in [ExportMode::Archive, ExportMode::Portable] {
        let HonkBundle::Single(file) = store.export(rev, mode).unwrap() else {
            panic!("unexpected manifest")
        };
        assert_eq!(file.path, Path::new("single.dae"));
        assert_eq!(file.content.as_ref(), text);
    }
}

#[test]
fn include_section_requires_a_tree_even_without_children() {
    let data = TempDir::new("bundle-unmatched");
    let mut store = HonkStore::open(data.path()).unwrap();
    for entry in ["include { parts/*.dae }", "include {}"] {
        let rev = apply(&mut store, &[("main.dae", entry)]);
        for mode in [ExportMode::Archive, ExportMode::Portable] {
            let HonkBundle::Tree {
                manifest,
                companions,
                issues,
            } = store.export(rev, mode).unwrap()
            else {
                panic!("lost the include tree")
            };
            assert!(issues.is_empty() && companions.is_empty());
            assert_eq!(manifest.len(), 1);
            assert_eq!(manifest[0].content.as_ref(), entry);
        }
    }
}

#[test]
fn dot_glob_reports_only_the_escaping_include_at_its_byte_offset() {
    let data = TempDir::new("bundle-dot-glob");
    let mut store = HonkStore::open(data.path()).unwrap();
    let entry = "# café\ninclude { .*/x.dae }";
    let rev = apply(
        &mut store,
        &[
            ("main.dae", entry),
            (".hidden/x.dae", "routing { fallback: direct }"),
        ],
    );
    assert_eq!(
        store.export(rev, ExportMode::Portable).err(),
        Some(ExportError::NotPortable(vec![ExportIssue::IncludeEscape {
            source: 0,
            offset: entry.find(".*").unwrap()
        }]))
    );
}

#[test]
fn staged_relative_hosts_are_archived_but_not_portable() {
    let data = TempDir::new("bundle-relative-hosts");
    let mut store = HonkStore::open(data.path()).unwrap();
    let text = "dns { use_host: assets/hosts }\r\n";
    let hosts = "# local\r\n192.0.2.1 office.test\r\n";
    let rev = apply(&mut store, &[("main.dae", text), ("assets/hosts", hosts)]);
    let HonkBundle::Tree {
        manifest,
        companions,
        issues,
    } = store.export(rev, ExportMode::Archive).unwrap()
    else {
        panic!("expected hosts companion")
    };
    assert_eq!(issues, [ExportIssue::RelativeHosts { index: 0 }]);
    assert_eq!(manifest[0].content.as_ref(), text);
    let [file] = companions.as_slice() else {
        panic!("expected one companion")
    };
    assert_eq!(file.path, Path::new("assets/hosts"));
    assert_eq!(file.content.as_bytes(), hosts.as_bytes());
    assert_eq!(
        store.export(rev, ExportMode::Portable).err(),
        Some(ExportError::NotPortable(issues))
    );
}
#[test]
fn archive_discloses_hosts_omissions_without_rewriting_or_certifying_them() {
    let data = TempDir::new("bundle-external");
    let mut store = HonkStore::open(data.path()).unwrap();
    let text = "dns {\n use_host: '../private-hosts'\n use_host: missing\n}\n";
    let rev = apply(&mut store, &[("private.dae", text)]);
    let expected = [
        ExportIssue::HostsEscape { index: 0 },
        ExportIssue::MissingHosts { index: 1 },
    ];
    let HonkBundle::Tree {
        manifest,
        companions,
        issues,
    } = store.export(rev, ExportMode::Archive).unwrap()
    else {
        panic!("archive omitted the disclosure")
    };
    assert_eq!(issues, expected);
    assert!(companions.is_empty());
    assert_eq!(manifest.len(), 1);
    assert_eq!(manifest[0].content.as_ref(), text);
    assert_eq!(
        store.export(rev, ExportMode::Portable).err(),
        Some(ExportError::NotPortable(expected.into()))
    );
}

#[test]
fn lexical_include_that_stock_cannot_expand_is_not_portable() {
    let data = TempDir::new("bundle-expansion");
    let mut store = HonkStore::open(data.path()).unwrap();
    let rev = apply(
        &mut store,
        &[
            ("main.dae", "include { absent/../child.dae }"),
            ("child.dae", "routing { fallback: block }"),
        ],
    );
    assert_eq!(
        store.export(rev, ExportMode::Portable).err(),
        Some(ExportError::NotPortable(vec![
            ExportIssue::IncludeExpansion { source: 0 }
        ]))
    );
}
