//! The comparison must fail on a wrong pin, a missing corpus file, an edited expected leaf and a
//! wrong geo asset. The geo bundle is not in the repository: set HONK_REFERENCE_GEO to a directory
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
    Command::new(env!("CARGO_BIN_EXE_doona-honk-reference"))
        .arg("check")
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
    let pin = fs::read_to_string(manifest().join("Cargo.toml")).unwrap();
    let rev = pin
        .split("rev = \"")
        .nth(1)
        .unwrap()
        .split('"')
        .next()
        .unwrap()
        .to_owned();
    let other = expected_with("wrong-pin", |text| {
        text.replace(&rev, "63de98ba32f871ac6a779e7462a0a79f8a595153")
    });
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
