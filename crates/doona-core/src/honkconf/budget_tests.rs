use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::Path;

use super::{HonkConfigError, HonkStore, StoreError};
use crate::testutil::TempDir;

const BYTES: usize = 8 * 1024 * 1024;

fn replace_first(path: &Path) {
    let mut file = OpenOptions::new().write(true).open(path).unwrap();
    file.write_all(&[0xff]).unwrap();
}

fn append_byte(path: &Path) {
    let mut file = OpenOptions::new().append(true).open(path).unwrap();
    file.write_all(b"\n").unwrap();
}

fn byte_boundary(entry_text: &str, names: &[&str]) {
    let bundle = TempDir::new("byte-boundary");
    let data = TempDir::new("byte-boundary-store");
    let entry = bundle.path().join("main.dae");
    let capacity = BYTES - entry_text.len();
    let mut payload = vec![b'x'; capacity / names.len()];
    payload[..11].copy_from_slice(b"global {}\n#");
    fs::write(&entry, entry_text).unwrap();
    for (i, name) in names.iter().enumerate() {
        if i == names.len() - 1 {
            payload.resize(payload.len() + capacity % names.len(), b'x');
        }
        fs::write(bundle.path().join(name), &payload).unwrap();
    }
    let mut store = HonkStore::open(data.path()).unwrap();
    let id = store.import(&entry).unwrap();
    let head = store.apply(id).unwrap();
    // Over budget takes precedence over decoding any of the supplied text.
    for name in names {
        replace_first(&bundle.path().join(name));
    }
    append_byte(&bundle.path().join(names[0]));
    let error = store.import(&entry).unwrap_err();
    assert!(
        matches!(error, StoreError::Config(HonkConfigError::SourceLimit)),
        "{error}"
    );
    assert_eq!(store.head(), Some(head));
    assert!(store.current_draft().is_none());
    let staging = data.path().join("config/staging");
    assert_eq!(fs::read_dir(staging).unwrap().count(), 0);
}

#[test]
fn entry_byte_boundary() {
    byte_boundary("", &["main.dae"]);
}

#[test]
fn include_byte_boundary() {
    byte_boundary("include { part-*.dae }", &["part-a.dae", "part-b.dae"]);
}

#[test]
fn hosts_byte_boundary() {
    byte_boundary(
        "dns {\n use_host: a.txt\n use_host: b.txt\n}",
        &["a.txt", "b.txt"],
    );
}

#[test]
fn thirty_third_file_refused_before_decoding() {
    let bundle = TempDir::new("source-count");
    let data = TempDir::new("source-count-store");
    let entry = bundle.path().join("main.dae");
    fs::write(&entry, "include { part-*.dae }").unwrap();
    for i in 1..32 {
        fs::write(bundle.path().join(format!("part-{i}.dae")), "").unwrap();
    }
    let mut store = HonkStore::open(data.path()).unwrap();
    let draft = store.import(&entry).unwrap();
    assert_eq!(store.check(draft).unwrap().sources().len(), 32);
    let head = store.apply(draft).unwrap();
    for i in 1..=32 {
        fs::write(bundle.path().join(format!("part-{i}.dae")), [0xff]).unwrap();
    }
    assert!(matches!(
        store.import(&entry),
        Err(StoreError::Config(HonkConfigError::SourceLimit))
    ));
    assert_eq!(store.head(), Some(head));
    assert!(store.current_draft().is_none());
}
