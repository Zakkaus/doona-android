#[path = "honkconf/capabilities.rs"]
mod capabilities;
mod common;

use std::{
    fs,
    path::{Path, PathBuf},
};

use common::TempDir;
use doonacore::honkconf::{
    Capabilities, Disposition, HonkConfigError, HonkStore, StoreError, Summary, load,
};
use honk_config::Config;

fn write(dir: &TempDir, name: &str, text: &str) -> PathBuf {
    let path = dir.path().join(name);
    fs::write(&path, text).unwrap();
    path
}

const FETCH: &str = "restore the honk checkout with cargo fetch --locked from crates/doona-core";

/// The honk tree honk-config is built from, as cargo resolves it: a pinned Git checkout, or
/// during development a local path. `id` names it in messages and pin reviews.
pub(crate) struct Honk {
    pub root: PathBuf,
    pub id: String,
}

pub(crate) fn honk() -> Honk {
    let output = std::process::Command::new(env!("CARGO"))
        .args(["metadata", "--format-version=1", "--offline", "--locked"])
        .current_dir(env!("CARGO_MANIFEST_DIR"))
        .output()
        .expect("cargo metadata");
    assert!(output.status.success(), "cargo metadata failed: {FETCH}");
    let metadata: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
    let package = metadata["packages"]
        .as_array()
        .unwrap()
        .iter()
        .find(|p| p["name"] == "honk-config")
        .expect("honk-config in the dependency graph");
    let manifest = PathBuf::from(package["manifest_path"].as_str().unwrap());
    let root = manifest.ancestors().nth(3).unwrap().to_path_buf();
    let Some(source) = package["source"].as_str() else {
        return Honk {
            id: format!("path+{}", root.display()),
            root,
        };
    };
    let rev = source.rsplit_once('#').map(|(_, rev)| rev).unwrap();
    let head = std::process::Command::new("git")
        .args(["rev-parse", "--verify", "HEAD"])
        .current_dir(&root)
        .output()
        .expect("git is required to verify the honk corpus");
    assert!(head.status.success(), "unreadable honk HEAD: {FETCH}");
    assert_eq!(
        String::from_utf8(head.stdout).unwrap().trim(),
        rev,
        "wrong honk corpus: {FETCH}"
    );
    Honk {
        root,
        id: source.to_owned(),
    }
}

fn checkout() -> PathBuf {
    let honk = honk();
    assert!(
        honk.root.join("crates/honk-config/Cargo.toml").is_file(),
        "missing honk {}: {FETCH}",
        honk.id
    );
    honk.root
}

#[test]
fn shipped_examples() {
    let root = checkout();
    for name in [
        "config.dae",
        "config.min.dae",
        "example.dae",
        "install/docker.dae",
    ] {
        let staged = TempDir::new("honk-example");
        let file = root.join(name);
        assert!(file.is_file(), "missing {}: {FETCH}", file.display());
        let entry = staged.path().join("main.dae");
        fs::copy(&file, &entry).unwrap();
        let doc = load(entry).unwrap_or_else(|e| panic!("{name}: {e}"));
        let linux =
            Config::from_file_with_detailed_diagnostics(file.to_str().unwrap(), &mut vec![])
                .unwrap();
        linux.validate_detailed().unwrap();
        assert_eq!(doc.config().routing, linux.routing, "{name}");
        assert!(
            doc.config()
                .nodes
                .iter()
                .map(|n| n.id)
                .eq(linux.nodes.iter().map(|n| n.id)),
            "{name}"
        );
        if name == "example.dae" {
            assert_eq!(
                doc.summary(),
                Summary {
                    captured_ip_versions: &[4],
                    subscriptions: 0,
                    nodes: 4,
                    groups: 2,
                    routing_rules: 7,
                    dns_upstreams: 2,
                    dns_request_rules: 1,
                    dns_response_rules: 3,
                }
            );
            assert_eq!(doc.unsupported().len(), 19);
        }
    }
}

#[test]
fn class_fixtures() {
    let fixtures = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/honk");
    for (file, section, name, expected) in [
        ("ingress.dae", "global", "tproxy_port", "linux_ingress"),
        (
            "mark.dae",
            "global",
            "so_mark_from_dae",
            "linux_socket_mark",
        ),
        (
            "interface.dae",
            "global",
            "lan_interface",
            "linux_interface",
        ),
        (
            "sysctl.dae",
            "global",
            "auto_config_kernel_parameter",
            "linux_sysctl",
        ),
        ("resolver.dae", "dns", "bind", "linux_ingress"),
        ("listener.dae", "global", "pprof_port", "daemon_listener"),
        (
            "lifecycle.dae",
            "global",
            "disable_waiting_network",
            "vpn_lifecycle",
        ),
        ("path.dae", "global", "log_file", "host_path"),
        (
            "profile.dae",
            "global",
            "utls_imitate",
            "unsupported_option",
        ),
        ("plugin.dae", "node", "node[0]", "unavailable_transport"),
        ("mac.dae", "routing", "rule[0]", "l2_mac_unavailable"),
        (
            "fallback.dae",
            "routing",
            "fallback",
            "route_mark_unavailable",
        ),
        ("hosts.dae", "dns", "use_host[0]", "missing_hosts_source"),
    ] {
        let doc = load(fixtures.join(file)).unwrap();
        let records = doc.unsupported();
        assert_eq!(records.len(), 1, "{section}.{name}: {records:?}");
        let r = &records[0];
        // A terminal fallback has no next rule to try: flows reaching it fail.
        let expected_disposition = if name == "fallback" {
            Disposition::Refuse
        } else {
            Disposition::Skip
        };
        assert_eq!(
            (r.section, r.name.as_ref(), r.disposition),
            (section, name, expected_disposition)
        );
        assert_eq!(
            r.reasons.iter().map(|r| r.code).collect::<Vec<_>>(),
            [expected]
        );
    }
}

#[test]
fn includes_count_effective_settings_and_whole_rules() {
    let dir = TempDir::new("honk-includes");
    let entry = write(
        &dir,
        "main.dae",
        "include { 'nested rules.dae' optional/*.dae }\nglobal { pprof_port: 6060 }\nexperimental { udp_nfqueue { enabled: true } }",
    );
    let child = "global {\n pprof_port: 0\n nfqueue_enable: true\n}\nrouting {\n !pname(browser) && dscp(4) -> direct\n dport(443) -> direct\n fallback: direct(mark: 256)\n}";
    write(&dir, "nested rules.dae", child);
    let doc = load(entry).unwrap();
    assert_eq!(doc.sources()[1].content.as_ref(), child);
    assert_eq!(doc.config().routing.default_mark, 256);
    assert_eq!(doc.summary().routing_rules, 2);
    let records = doc.unsupported();
    assert_eq!(records.len(), 3);
    assert_eq!(records[0].name, "nfqueue_enable");
    assert_eq!(
        records[0]
            .locations
            .iter()
            .map(|l| l.source)
            .collect::<Vec<_>>(),
        [0, 1]
    );
    assert_eq!(
        records[1]
            .reasons
            .iter()
            .map(|r| r.code)
            .collect::<Vec<_>>(),
        ["process_name_unavailable", "dscp_unavailable"]
    );
    assert_eq!(records[1].locations[0].line, 6);
    assert_eq!(records[2].name, "fallback");
    assert!(!doc.diagnostics().is_empty()); // legacy NFQUEUE spelling, not another skip
}

#[test]
fn missing_assets_and_constrained_scope_are_not_parse_failures() {
    let dir = TempDir::new("honk-assets");
    let path = write(
        &dir,
        "main.dae",
        "routing {\n domain(geosite:cn@ads) && !dip(geoip:us) -> direct\n dip(geoip:private) -> direct\n ipversion(6) -> block\n sip(2001:db8::1, 192.0.2.1) -> direct\n}\ndns {\n use_host: hosts\n routing {\n request {\n qname(geosite:ads) -> asis\n fallback: asis\n }\n response { ip(geoip:us) -> accept }\n }\n}",
    );
    let doc = load(&path).unwrap();
    let report = doc.unsupported();
    assert_eq!(doc.summary().dns_request_rules, 1);
    assert_eq!(doc.summary().dns_response_rules, 1);
    assert_eq!(report.len(), 7);
    assert_eq!(report[0].name, "use_host[0]");
    assert_eq!(
        report[1]
            .reasons
            .iter()
            .map(|r| (r.code, r.category.as_deref()))
            .collect::<Vec<_>>(),
        [
            ("missing_geosite", Some("cn@ads")),
            ("missing_geoip", Some("us"))
        ]
    );
    assert_eq!(
        (&*report[2].name, report[2].disposition),
        ("rule[2]", Disposition::Skip)
    );
    assert_eq!(report[3].disposition, Disposition::Limited);
    // A missing geo label blocks the configuration rather than skipping the rule.
    assert_eq!(report[1].disposition, Disposition::Blocked);
    assert_eq!(report[4].disposition, Disposition::Blocked);
    assert_eq!(report[4].reasons.len(), 2);
    assert_eq!(report[5].disposition, Disposition::Limited);
    assert_eq!(report[6].section, "dns.routing.response");
    write(&dir, "hosts", "127.0.0.1 restored.test\n");
    assert_eq!(load(path).unwrap().unsupported().len(), 6);
}

#[test]
fn error_locations_and_literal_missing_include() {
    let dir = TempDir::new("honk-errors");
    let path = write(&dir, "main.dae", "include { child.dae }");
    let doc = load(&path).unwrap();
    assert_eq!(doc.sources().len(), 1);
    assert!(doc.unsupported().is_empty());
    write(
        &dir,
        "child.dae",
        "global {\n log_level: debug\n nfqueue_enable: invalid\n}",
    );
    let HonkConfigError::ParseFailed { cause, diagnostics } = load(&path).unwrap_err() else {
        panic!("expected child diagnostic")
    };
    let d = &cause.diagnostic;
    let linux = Config::from_file_with_detailed_diagnostics(path.to_str().unwrap(), &mut vec![])
        .unwrap_err()
        .diagnostic;
    assert_eq!(
        (d.line, d.byte_column, d.message),
        (Some(3), linux.byte_column, linux.message)
    );
    assert!(d.byte_column.is_some());
    assert_eq!(
        d.source.sources().metadata()[d.source.index()]
            .path
            .as_deref(),
        Some(dir.path().join("child.dae").as_path())
    );
    assert_eq!(diagnostics.last(), Some(d.as_ref()));
}

fn corpus_files(dir: &Path, test: &impl Fn(&Path)) -> usize {
    let mut count = 0;
    for item in fs::read_dir(dir).unwrap_or_else(|e| panic!("{}: {e}; {FETCH}", dir.display())) {
        let path = item.unwrap().path();
        if path.is_dir() {
            count += corpus_files(&path, test);
        } else if path.to_string_lossy().ends_with(".dae") {
            test(&path);
            count += 1;
        }
    }
    count
}

#[test]
fn upstream_syntax_and_conformance_admission() {
    let root = checkout();
    let tmp = TempDir::new("honk-corpus");
    for (dir, expected) in [("tests/fixtures", 81), ("conformance/cases", 105)] {
        let cases = corpus_files(&root.join("crates/honk-config").join(dir), &|fixture| {
            let entry = tmp.path().join("case.dae");
            fs::copy(fixture, &entry).unwrap();
            use honk_config::diagnostic::{DetailedDiagnostic, finish_attempt};
            let mut notices = Vec::new();
            let linux = Config::from_dae_file_with_sources(
                &entry,
                &Default::default(),
                Default::default(),
                &mut notices,
            )
            .and_then(|loaded| {
                let source = loaded.sources[0].source.clone();
                let cfg = loaded.config;
                cfg.append_diagnostics(source.clone(), &mut notices);
                finish_attempt(
                    cfg.validate_detailed().map_err(|mut e| {
                        e.diagnostic.source = source;
                        e
                    }),
                    &mut notices,
                )
            });
            let android = load(&entry);
            // Only the daemon retries native failures as structured documents.
            if let Ok(doc) = &android {
                let daemon = Config::from_file_with_detailed_diagnostics(
                    entry.to_str().unwrap(),
                    &mut Vec::new(),
                )
                .unwrap();
                daemon.validate_detailed().unwrap();
                assert_eq!(
                    doc.config().routing,
                    daemon.routing,
                    "{}",
                    fixture.display()
                );
            }
            assert_eq!(
                android.is_ok(),
                linux.is_ok(),
                "{}: linux={linux:?}, android={:?}",
                fixture.display(),
                android.as_ref().err()
            );
            let actual = match &android {
                Ok(doc) => doc.diagnostics(),
                Err(HonkConfigError::ParseFailed { diagnostics, .. }) => diagnostics.as_slice(),
                Err(other) => panic!("{}: {other:?}", fixture.display()),
            };
            let key = |d: &DetailedDiagnostic| {
                (
                    d.code,
                    d.severity,
                    d.source.index(),
                    d.span.clone(),
                    d.line,
                    d.byte_column,
                    d.message,
                    d.terminal,
                )
            };
            assert_eq!(
                actual.iter().map(key).collect::<Vec<_>>(),
                notices.iter().map(key).collect::<Vec<_>>(),
                "{}",
                fixture.display()
            );
        });
        assert_eq!(
            cases,
            expected,
            "incomplete {dir} at {}: {FETCH}",
            honk().id
        );
    }
}

#[test]
fn confined_includes_and_hosts() {
    use std::os::unix::fs::symlink;
    let dir = TempDir::new("honk-confined");
    let stage = dir.path().join("stage");
    fs::create_dir(&stage).unwrap();
    let outside = write(&dir, "outside.dae", "global { nfqueue_enable: invalid }");
    symlink(&outside, stage.join("link.dae")).unwrap();
    fs::write(stage.join("rules-a.dae"), "global { pprof_port: 1234 }").unwrap();
    let text = format!(
        "include {{\n ../outside.dae\n '{0}/*.dae'\n link.dae\n '{1}/missing/*.dae'\n rules-[ab].dae\n optional/*.dae\n ../stage/rules-a.dae\n}}\ndns {{\n use_host: '../outside.dae'\n use_host: '{0}/outside.dae'\n use_host: link.dae\n use_host: absent\n use_host: rules-a.dae\n}}",
        dir.path().display(),
        stage.display()
    );
    let entry = stage.join("main.dae");
    fs::write(&entry, &text).unwrap();
    let config = load(entry).unwrap();
    assert!(!config.complete());
    assert_eq!(config.sources()[0].content.as_ref(), text);
    assert_eq!(config.sources().len(), 2);
    assert_eq!(config.config().global.pprof_port, 1234);
    let records = config.unsupported();
    assert!(
        records[..5]
            .iter()
            .all(|r| r.disposition == Disposition::Incomplete)
    );
    assert!(
        records[5..]
            .iter()
            .all(|r| r.disposition == Disposition::Skip)
    );
    let codes: Vec<_> = records
        .iter()
        .map(|r| (r.reasons[0].code, r.locations[0].line))
        .collect();
    assert_eq!(
        codes,
        [
            ("include_escape", 2),
            ("include_escape", 3),
            ("include_escape", 4),
            ("include_escape", 5),
            ("include_escape", 8),
            ("use_host_escape", 11),
            ("use_host_escape", 12),
            ("use_host_escape", 13),
            ("missing_hosts_source", 14),
            ("daemon_listener", 1)
        ]
    );
}

#[test]
fn source_budget_covers_entry_and_includes() {
    let dir = TempDir::new("honk-budget");
    let entry = write(&dir, "main.dae", "include { part*.dae }");
    let limits = honk_config::parser::SourceLimits::DEFAULT;
    let part = write(&dir, "part.dae", "");
    fs::File::options()
        .write(true)
        .open(&part)
        .unwrap()
        .set_len(limits.max_bytes as u64)
        .unwrap();
    assert!(matches!(load(&entry), Err(HonkConfigError::SourceLimit)));
    fs::write(part, "").unwrap();
    for i in 2..limits.max_sources {
        write(&dir, &format!("part{i}.dae"), "");
    }
    assert_eq!(load(&entry).unwrap().sources().len(), limits.max_sources);
    write(&dir, "part-extra.dae", "");
    assert!(matches!(load(entry), Err(HonkConfigError::SourceLimit)));
}

#[test]
fn structured_documents_are_not_native_sources() {
    let dir = TempDir::new("honk-format");
    let data = TempDir::new("honk-format-store");
    let mut store = HonkStore::open(data.path()).unwrap();
    for text in [
        "\u{feff}{\"global\":{\"log_level\":\"debug\"}}",
        "[global]\nlog_level = 'debug'",
        "global:\n  log_level: debug",
        r#"{"global":{"log_level":"debug"}}"#,
    ] {
        let entry = write(&dir, "config.dae", text);
        let loaded = load(&entry);
        assert!(matches!(loaded, Err(HonkConfigError::NonNativeSource)));
        assert!(matches!(
            store.import(&entry),
            Err(StoreError::Config(HonkConfigError::NonNativeSource))
        ));
        assert!(store.current_draft().is_none());
    }
    let path = write(&dir, "config.yaml", "global { log_level: debug }");
    assert!(matches!(load(path), Err(HonkConfigError::NonNativeSource)));
}

#[test]
fn staged_source_errors() {
    let dir = TempDir::new("honk-invalid-source");
    let entry = write(&dir, "main.dae", "include { child.dae }");
    let child = dir.path().join("child.dae");
    fs::write(&child, [0xff]).unwrap();
    assert_error(&entry, "invalid-config-encoding", &child);
    fs::remove_file(&child).unwrap();
    fs::create_dir(&child).unwrap();
    assert_error(&child, "invalid-config-source", &child);
    fs::write(&entry, "include { 'a**/*.dae' }").unwrap();
    assert_error(&entry, "invalid-config-include", &entry);
}

fn assert_error(entry: &Path, code: &str, culprit: &Path) {
    let HonkConfigError::ParseFailed { cause, .. } = load(entry).unwrap_err() else {
        panic!("expected {code}")
    };
    let d = cause.diagnostic;
    assert_eq!(d.code, code);
    assert_eq!(
        d.source.sources().metadata()[d.source.index()]
            .path
            .as_deref(),
        Some(culprit)
    );
}

#[test]
fn refused_link_before_parent_component_never_loads_either_target() {
    let dir = TempDir::new("honk-link-parent");
    fs::create_dir_all(dir.path().join("sub/deep")).unwrap();
    std::os::unix::fs::symlink("sub/deep", dir.path().join("link")).unwrap();
    write(&dir, "sub/b.dae", "global { pprof_port: 1111 }");
    write(&dir, "b.dae", "global { pprof_port: 2222 }");
    let doc = load(write(&dir, "main.dae", "include { link/../b.dae }")).unwrap();
    assert!(!doc.complete());
    assert_eq!(doc.sources().len(), 1);
    assert_eq!(doc.unsupported()[0].reasons[0].code, "include_escape");
}

#[test]
fn punctuation_inside_include_uses_honks_own_pattern_boundaries() {
    let dir = TempDir::new("honk-pattern");
    let child = "y.dae";
    write(&dir, child, "global { pprof_port: 4321 }");
    // After the comma this quote is part of an unquoted pattern. The next
    // whitespace starts another pattern; adjacent quoted paths split at the quote.
    let entry = write(
        &dir,
        "main.dae",
        "include { rules,'x y.dae' 'missing.dae''y.dae' }",
    );
    let native = Config::from_dae_file_with_sources(
        &entry,
        &Default::default(),
        Default::default(),
        &mut Vec::new(),
    )
    .unwrap();
    let doc = load(&entry).unwrap();
    assert!(doc.complete());
    assert_eq!(
        doc.sources().iter().map(|s| &s.path).collect::<Vec<_>>(),
        native.sources.iter().map(|s| &s.path).collect::<Vec<_>>()
    );
    assert_eq!(doc.config().global.pprof_port, 4321);
}

#[test]
fn hosts_shorthand_skips_system_hosts_without_blocking_apply() {
    let dir = TempDir::new("honk-system-hosts");
    let text = "dns { use_host: true }";
    let doc = load(write(&dir, "main.dae", text)).unwrap();
    assert_eq!(doc.config().dns.hosts, ["/etc/hosts"]);
    assert!(doc.complete());
    let record = &doc.unsupported()[0];
    assert_eq!(record.reasons[0].code, "use_host_escape");
    assert_eq!(record.disposition, Disposition::Skip);
    assert_eq!(
        &doc.sources()[0].content[record.locations[0].bytes.clone()],
        "use_host: true"
    );
}

#[test]
fn native_bom_is_parsed_as_written_by_honk() {
    let dir = TempDir::new("honk-native-bom");
    let text = "\u{feff}routing { dport(1) -> block }";
    let entry = write(&dir, "main.dae", text);
    let linux =
        Config::from_file_with_detailed_diagnostics(entry.to_str().unwrap(), &mut Vec::new())
            .unwrap();
    let doc = load(entry).unwrap();
    assert_eq!(doc.sources()[0].content.as_ref(), text);
    assert_eq!(doc.summary().routing_rules, linux.routing.rules.len());
}

#[test]
fn wildcard_retains_regular_matches_without_certifying_excluded_links() {
    let dir = TempDir::new("honk-glob-link");
    let outside = TempDir::new("honk-glob-outside");
    let target = write(&outside, "external.dae", "global { log_level: invalid }");
    std::os::unix::fs::symlink(target, dir.path().join("part-link.dae")).unwrap();
    write(&dir, "part-ok.dae", "global { pprof_port: 1234 }");
    let config = load(write(&dir, "main.dae", "include { part-*.dae }")).unwrap();
    assert_eq!(config.config().global.pprof_port, 1234);
    assert!(!config.complete());
    assert_eq!(config.unsupported()[0].reasons[0].code, "include_escape");
}

#[test]
fn imported_sources_exclude_unreferenced_assets() {
    let dir = TempDir::new("honk-assets");
    let entry = write(&dir, "main.dae", "include { rules-*.dae }");
    write(&dir, "rules-local.dae", "dns { use_host: names.txt }");
    write(&dir, "example.dae", "dns { use_host: unrelated.txt }");
    std::os::unix::fs::symlink("/absent", dir.path().join("rules-geoip.dat")).unwrap();
    write(&dir, "names.txt", "127.0.0.1 example.test\n");
    fs::write(dir.path().join("geoip.dat"), [0xff]).unwrap();
    for i in 0..honk_config::parser::SourceLimits::DEFAULT.max_sources {
        write(&dir, &format!("unrelated-{i}"), "not a source");
    }
    assert!(load(&entry).unwrap().unsupported().is_empty());
    let data = TempDir::new("honk-assets-store");
    let mut store = HonkStore::open(data.path()).unwrap();
    let draft = store.import(&entry).unwrap();
    let rev = store.apply(draft, &Capabilities::linked()).unwrap();
    let tree = data.path().join(format!("config/revisions/{rev}"));
    let mut names: Vec<_> = fs::read_dir(&tree)
        .unwrap()
        .map(|e| e.unwrap().file_name())
        .collect();
    names.sort_unstable();
    assert_eq!(names, ["main.dae", "names.txt", "rules-local.dae"]);
    assert_eq!(
        fs::read(tree.join("names.txt")).unwrap(),
        b"127.0.0.1 example.test\n"
    );
}
