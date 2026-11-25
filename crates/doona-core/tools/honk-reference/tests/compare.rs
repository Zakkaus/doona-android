//! The comparison must fail on a wrong pin, a missing corpus file, an edited expected leaf or DNS
//! reply and a wrong geo asset. The geo bundle is not in the repository: set HONK_REFERENCE_GEO to a directory
//! that `update_geo.py` staged from geo-pins.json.

use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};

fn manifest() -> &'static Path {
    Path::new(env!("CARGO_MANIFEST_DIR"))
}

fn corpus() -> PathBuf {
    manifest().join("../../tests/fixtures/honk/reference")
}

fn pins() -> PathBuf {
    manifest().join("../geo-pins.json")
}

fn geo() -> PathBuf {
    PathBuf::from(
        std::env::var("HONK_REFERENCE_GEO").expect("set HONK_REFERENCE_GEO to the geo bundle"),
    )
}

fn scratch(name: &str) -> PathBuf {
    let dir = Path::new(env!("CARGO_TARGET_TMPDIR")).join(name);
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    dir
}

fn check(corpus: &Path, geo: &Path, pins: &Path, expected: &Path) -> Output {
    run("check", corpus, geo, pins, expected)
}

fn run(mode: &str, corpus: &Path, geo: &Path, pins: &Path, expected: &Path) -> Output {
    Command::new(env!("CARGO_BIN_EXE_doona-honk-reference"))
        .arg(mode)
        .arg(expected)
        .args(["--corpus"])
        .arg(corpus)
        .arg("--geo")
        .arg(geo)
        .arg("--geo-pins")
        .arg(pins)
        .output()
        .unwrap()
}

fn expected() -> PathBuf {
    corpus().join("expected.json")
}

fn stderr(output: &Output) -> String {
    String::from_utf8_lossy(&output.stderr).into_owned()
}

fn expected_with(name: &str, edit: impl Fn(&str) -> String) -> PathBuf {
    let text = fs::read_to_string(expected()).unwrap();
    let edited = edit(&text);
    assert_ne!(edited, text, "the edit changed nothing");
    let path = scratch(name).join("expected.json");
    fs::write(&path, edited).unwrap();
    path
}

#[test]
fn stored_report_matches() {
    let output = check(&corpus(), &geo(), &pins(), &expected());
    assert!(output.status.success(), "{}", stderr(&output));
}

#[test]
fn wrong_pin_fails() {
    let pin = env!("HONK_PIN");
    let flipped = format!(
        "{}{}",
        if pin.starts_with('0') { '1' } else { '0' },
        &pin[1..]
    );
    let other = expected_with("wrong-pin", |text| text.replace(pin, &flipped));
    let output = check(&corpus(), &geo(), &pins(), &other);
    assert!(!output.status.success());
    assert!(stderr(&output).contains("honk_rev"), "{}", stderr(&output));
}

#[test]
fn missing_corpus_file_fails() {
    let copy = scratch("missing-corpus");
    for entry in fs::read_dir(corpus().join("cases")).unwrap() {
        let entry = entry.unwrap();
        fs::create_dir_all(copy.join("cases")).unwrap();
        fs::copy(entry.path(), copy.join("cases").join(entry.file_name())).unwrap();
    }
    fs::copy(corpus().join("corpus.json"), copy.join("corpus.json")).unwrap();
    fs::remove_file(copy.join("cases/nested.dae")).unwrap();
    let output = check(&copy, &geo(), &pins(), &expected());
    assert!(!output.status.success());
}

#[test]
fn edited_expected_leaf_fails() {
    let other = expected_with("edited-leaf", |text| {
        text.replacen("\"name\": \"hk-2\"", "\"name\": \"hk-1\"", 1)
    });
    let output = check(&corpus(), &geo(), &pins(), &other);
    assert!(!output.status.success());
    assert!(stderr(&output).contains("leaf.name"), "{}", stderr(&output));
}

#[test]
fn wrong_geo_pin_fails() {
    let text = fs::read_to_string(pins()).unwrap();
    let sha = text
        .split("\"sha256\": \"")
        .nth(1)
        .unwrap()
        .split('"')
        .next()
        .unwrap();
    let path = scratch("wrong-geo").join("geo-pins.json");
    fs::write(&path, text.replacen(sha, &"0".repeat(64), 1)).unwrap();
    let output = check(&corpus(), &geo(), &path, &expected());
    assert!(!output.status.success());
    assert!(stderr(&output).contains("SHA-256"), "{}", stderr(&output));
}

#[test]
fn uncovered_geo_code_fails() {
    let dir = scratch("uncovered-geo");
    fs::create_dir_all(dir.join("cases")).unwrap();
    fs::write(
        dir.join("corpus.json"),
        r#"{"version": 1, "cases": [{"name": "c", "config": "cases/c.dae",
            "probes": [{"name": "p", "domain": "a.test", "dst_ip": "203.0.113.9", "dst_port": 443, "protocol": "tcp"}]}]}"#,
    )
    .unwrap();
    fs::write(
        dir.join("cases/c.dae"),
        "routing {\n    domain(geosite:no-such-code-anywhere) -> direct\n    fallback: direct\n}\n",
    )
    .unwrap();
    let out = dir.join("out.json");
    let output = Command::new(env!("CARGO_BIN_EXE_doona-honk-reference"))
        .arg("emit")
        .arg(&out)
        .arg("--corpus")
        .arg(&dir)
        .arg("--geo")
        .arg(geo())
        .arg("--geo-pins")
        .arg(pins())
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert!(
        stderr(&output).contains("failed coverage"),
        "{}",
        stderr(&output)
    );
}

fn dns_corpus() -> PathBuf {
    corpus().join("dns")
}

fn dns_check(corpus: &Path, expected: &Path) -> Output {
    run("dns-check", corpus, &geo(), &pins(), expected)
}

#[test]
fn stored_dns_report_matches() {
    let output = dns_check(&dns_corpus(), &dns_corpus().join("expected.json"));
    assert!(output.status.success(), "{}", stderr(&output));
}

#[test]
fn edited_dns_reply_fails() {
    let text = fs::read_to_string(dns_corpus().join("expected.json")).unwrap();
    // The CN answer's address, 192.0.2.1, as the last bytes of the first reply.
    let edited = text.replacen("c0000201\"", "c0000209\"", 1);
    assert_ne!(edited, text);
    let path = scratch("edited-dns-reply").join("expected.json");
    fs::write(&path, edited).unwrap();
    let output = dns_check(&dns_corpus(), &path);
    assert!(!output.status.success());
    assert!(stderr(&output).contains(".reply"), "{}", stderr(&output));
}

#[test]
fn missing_dns_case_file_fails() {
    let copy = scratch("missing-dns-case");
    fs::create_dir_all(copy.join("cases")).unwrap();
    fs::copy(dns_corpus().join("corpus.json"), copy.join("corpus.json")).unwrap();
    fs::copy(
        dns_corpus().join("cases/geosite-cn.dae"),
        copy.join("cases/geosite-cn.dae"),
    )
    .unwrap();
    let output = dns_check(&copy, &dns_corpus().join("expected.json"));
    assert!(!output.status.success());
    assert!(stderr(&output).contains("split.dae"), "{}", stderr(&output));
}
