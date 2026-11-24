use super::*;
use crate::testutil::TempDir;
use serde_json::{Value, json};

const SHA: &str = "5f22a3b3371d1a489fb3d0df25d00db049a6a603";
const ABC: &str = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
const XYZ: &str = "3608bca1e44ea6c4d268eb6db02260269892c0b42b86bbf1e77a6fa16c3c9282";

fn metadata() -> Value {
    json!({"published_at":"2026-11-24T00:35:07Z", "assets":[
        {"name":"geosite.dat","size":3,"digest":format!("sha256:{XYZ}")},
        {"name":"geoip.dat","size":3,"digest":format!("sha256:{ABC}")}
    ]})
}

fn release(data: Value) -> io::Result<Release> {
    let branch = json!({"commit":{"sha": SHA}});
    Release::from_github(data.to_string().as_bytes(), branch.to_string().as_bytes())
}

#[test]
fn download_retries_only_failed_dat_in_pinned_order() {
    let root = TempDir::new("geo-order");
    let stage = root.path().join("stage");
    let mut requests = Vec::new();
    release(metadata())
        .unwrap()
        .stage_update(&stage, |url| {
            requests.push(url.to_owned());
            match requests.len() {
                1 => Err(io::ErrorKind::TimedOut.into()),
                2 => Ok(&b"bad"[..]),
                3 => Ok(&b"abc"[..]),
                4 => Ok(&b"xyz"[..]),
                _ => panic!("retried a verified DAT"),
            }
        })
        .unwrap();
    assert_eq!(
        requests,
        [
            format!("https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/{SHA}/geoip.dat"),
            format!("https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@{SHA}/geoip.dat"),
            format!("https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@{SHA}/geoip.dat"),
            format!("https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/{SHA}/geosite.dat"),
        ]
    );
    assert_eq!(fs::read(stage.join(FILES[0])).unwrap(), b"abc");
    assert_eq!(fs::read(stage.join(FILES[1])).unwrap(), b"xyz");
}

#[test]
fn interrupted_second_dat_discards_stage_without_retry_or_replacing_active() {
    let root = TempDir::new("geo-interrupt");
    let active = root.path().join("active");
    fs::create_dir(&active).unwrap();
    fs::write(active.join(FILES[0]), b"old").unwrap();
    let pins = release(metadata()).unwrap();
    let stage = root.path().join("pending");
    let mut requests = 0;
    let e = pins
        .stage_update(&stage, |_| {
            requests += 1;
            if requests == 1 {
                Ok(&b"abc"[..])
            } else {
                Err(io::ErrorKind::Interrupted.into())
            }
        })
        .unwrap_err();
    assert_eq!(e.kind(), io::ErrorKind::Interrupted);
    assert_eq!(requests, 2);
    assert!(!stage.exists());
    let e = pins
        .stage_update(&active, |_| -> io::Result<&[u8]> {
            panic!("reused active directory")
        })
        .unwrap_err();
    assert_eq!(e.kind(), io::ErrorKind::AlreadyExists);
    assert_eq!(fs::read(active.join(FILES[0])).unwrap(), b"old");
}

#[test]
fn apk_checks_both_lengths_and_sha_before_returning_a_pair() {
    let data = include_bytes!("../../../tools/geo-pins.json");
    let mut manifest: Value = serde_json::from_slice(data).unwrap();
    for (name, digest) in FILES.into_iter().zip([ABC, XYZ]) {
        manifest["files"][name]["size_bytes"] = json!(3);
        manifest["files"][name]["sha256"] = json!(digest);
    }
    let pins = Release::from_manifest(manifest.to_string().as_bytes()).unwrap();
    let root = TempDir::new("geo-apk");
    let stage = root.path().join("stage");
    fs::write(root.path().join(FILES[0]), b"abc").unwrap();
    for (bad, reason) in [
        ("xy", "geo size mismatch"),
        ("xyz!", "geo size mismatch"),
        ("xYz", "geo SHA-256 mismatch"),
    ] {
        fs::write(root.path().join(FILES[1]), bad).unwrap();
        let error = pins.stage_bundle(root.path(), &stage).unwrap_err();
        assert_eq!(error.to_string(), reason);
        assert!(!stage.exists());
    }
    fs::write(root.path().join(FILES[1]), b"xyz").unwrap();
    pins.stage_bundle(root.path(), &stage).unwrap();
    assert_eq!(fs::read(stage.join(FILES[1])).unwrap(), b"xyz");
}

#[test]
fn metadata_rejects_ambiguous_missing_or_unbounded_pins() {
    let mut duplicate = metadata();
    let site = duplicate["assets"][0].clone();
    duplicate["assets"].as_array_mut().unwrap().push(site);
    assert!(release(duplicate).is_err());
    for (field, value) in [
        ("digest", Value::Null),
        ("size", json!(MAX_BYTES + 1)),
        ("name", json!("other.dat")),
    ] {
        let mut data = metadata();
        data["assets"][0][field] = value;
        assert!(release(data).is_err());
    }
    let mut data = metadata();
    data["published_at"] = json!("2026-02-29T10:00:00Z");
    assert!(release(data).is_err());
}
