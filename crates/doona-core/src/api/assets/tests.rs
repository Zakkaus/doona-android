use super::*;
use crate::rules::{Flow, Network, policy::Choice};
use crate::testutil::{TempDir, tun_and_peer};
use serde_json::{Value, json};
use std::io::Cursor;

struct Fixture {
    ip: Vec<u8>,
    site: Vec<u8>,
    latest: Vec<u8>,
    branch: Vec<u8>,
}
impl Fixture {
    fn new(day: u8, addr: [u8; 4]) -> Self {
        let mut ip = b"\x0a\x0e\x0a\x02CN\x12\x08\x0a\x04".to_vec();
        ip.extend(addr);
        ip.extend([16, 32]);
        let site = b"\x0a\x04\x0a\x02CN".to_vec();
        Self::from_bytes(day, ip, site)
    }
    fn from_bytes(day: u8, ip: Vec<u8>, site: Vec<u8>) -> Self {
        let asset = |name: &str, body: &[u8]| {
            let sha: String = boring::sha::sha256(body)
                .iter()
                .map(|v| format!("{v:02x}"))
                .collect();
            json!({"name":name,"size":body.len(),"digest":format!("sha256:{sha}")})
        };
        let latest=json!({"published_at":format!("2026-11-{day:02}T00:00:00Z"),"assets":[asset("geoip.dat",&ip),asset("geosite.dat",&site)]}).to_string().into_bytes();
        let branch = json!({"commit":{"sha":format!("{day:040x}")}})
            .to_string()
            .into_bytes();
        Self {
            ip,
            site,
            latest,
            branch,
        }
    }
    fn pins(&self) -> Release {
        Release::from_github(&self.latest, &self.branch).unwrap()
    }
    fn copy(&self, path: &Path) {
        fs::write(path.join("geoip.dat"), &self.ip).unwrap();
        fs::write(path.join("geosite.dat"), &self.site).unwrap();
    }
    fn open(&self, url: &str) -> io::Result<Cursor<&[u8]>> {
        let bytes = match url {
            LATEST => &self.latest,
            BRANCH => &self.branch,
            url if url.ends_with("/geoip.dat") => &self.ip,
            url if url.ends_with("/geosite.dat") => &self.site,
            _ => panic!("unexpected geo URL"),
        };
        Ok(Cursor::new(bytes))
    }
}

#[test]
fn verified_hash_of_malformed_unreferenced_record_does_not_activate() {
    let dir = TempDir::new("geo-bad-proto");
    let bundle = TempDir::new("geo-bad-proto-apk");
    let good = Fixture::new(1, [8, 8, 8, 8]);
    let bad = Fixture::from_bytes(2, good.ip, b"\x0a\x09\x0a\x02XX\x12\x03\x12\x06x".to_vec());
    bad.copy(bundle.path());
    let core = core(&dir, &bad);
    let _peer = start(&core, bundle.path());
    assert_eq!(status(&core)["source"], "builtin");
    assert_eq!(status(&core)["reason"], "invalid_generation");
    assert!(!direct(&core, "8.8.8.8"));
    assert!(direct(&core, "223.5.5.5"));
}
fn status(core: &Core) -> Value {
    serde_json::from_str::<Value>(&core.call("configAssets", "{}")).unwrap()["ok"].clone()
}
fn direct(core: &Core, ip: &str) -> bool {
    let c = lock(&core.shared.catalog);
    c.model.policy.choose(&Flow {
        domain: None,
        ip: ip.parse().unwrap(),
        network: Network::Tcp,
        port: 443,
    }) == Choice::Direct
}
fn start(core: &Core, dir: &Path) -> std::os::fd::OwnedFd {
    let args = json!({"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":[],"geoAssetDir":dir});
    let (tun, peer) = tun_and_peer();
    core.start(tun, &args.to_string()).unwrap();
    peer
}
fn core(dir: &TempDir, apk: &Fixture) -> Core {
    let mut core = Core::new(dir.path().to_str().unwrap()).unwrap();
    core.assets = Arc::new(Assets::with_pins(dir.path(), apk.pins()));
    assert_eq!(core.call("setMode", r#"{"mode":"Rule"}"#), r#"{"ok":{}}"#);
    core
}
#[test]
fn apk_first_start_then_verified_update_changes_routes_only_after_restart() {
    let dir = TempDir::new("geo-lifecycle");
    let bundle = TempDir::new("geo-bundle");
    let apk = Fixture::new(1, [8, 8, 8, 8]);
    apk.copy(bundle.path());
    let next = Fixture::new(2, [9, 9, 9, 9]);
    let core = core(&dir, &apk);
    let _peer = start(&core, bundle.path());
    assert!(direct(&core, "8.8.8.8"));
    assert!(!direct(&core, "9.9.9.9"));
    assert_eq!(status(&core)["source"], "apk");
    let active = dir.path().join("geo/a");
    let old = dir.path().join("geo/b");
    assert!(!active.exists() && old.is_dir());
    core.assets
        .update(|url, _| next.open(url), &CancellationToken::new())
        .unwrap();
    assert_eq!(status(&core)["lastResult"], "ready_on_next_start");
    assert!(active.is_dir() && old.is_dir());
    assert_eq!(status(&core)["release"], "2026-11-01");
    assert!(direct(&core, "8.8.8.8"));
    assert_eq!(
        core.call("configAssets", r#"{"action":"update"}"#),
        r#"{"error":{"code":"busy"}}"#
    );
    core.stop();
    let _second = start(&core, bundle.path());
    assert!(direct(&core, "9.9.9.9"));
    assert!(!direct(&core, "8.8.8.8"));
    assert_eq!(status(&core)["source"], "download");
    assert_eq!(status(&core)["updateWaitingForNextStart"], false);
    assert!(
        active.is_dir() && !old.exists(),
        "obsolete APK slot survived activation"
    );
    core.assets
        .update(
            |url, kind| {
                assert!(matches!(kind, RequestKind::Metadata));
                assert!(url == LATEST || url == BRANCH);
                next.open(url)
            },
            &CancellationToken::new(),
        )
        .unwrap();
    assert_eq!(status(&core)["lastResult"], "up_to_date");
    drop(core);
    // Recreate the disk state after the journal rename but before deletion of the old slot.
    fs::create_dir(&old).unwrap();
    apk.copy(&old);
    let reopened = self::core(&dir, &apk);
    let _peer = start(&reopened, bundle.path());
    assert!(direct(&reopened, "9.9.9.9") && !old.exists());
    reopened.stop();
    fs::write(active.join("geoip.dat"), b"corrupt").unwrap();
    let _peer = start(&reopened, bundle.path());
    assert_eq!(status(&reopened)["source"], "apk");
    assert!(direct(&reopened, "8.8.8.8") && old.is_dir() && !active.exists());
}
#[test]
fn same_size_corruption_after_staging_keeps_active_pair_across_process_restart() {
    let dir = TempDir::new("geo-corrupt");
    let bundle = TempDir::new("geo-bundle");
    let apk = Fixture::new(1, [8, 8, 8, 8]);
    apk.copy(bundle.path());
    let next = Fixture::new(2, [9, 9, 9, 9]);
    {
        let core = core(&dir, &apk);
        let _peer = start(&core, bundle.path());
        core.assets
            .update(|u, _| next.open(u), &CancellationToken::new())
            .unwrap();
    }
    let path = dir.path().join("geo/a/geoip.dat");
    let mut bad = fs::read(&path).unwrap();
    bad[10] ^= 1;
    fs::write(path, bad).unwrap();
    let core = core(&dir, &apk);
    assert_eq!(status(&core)["updateWaitingForNextStart"], true);
    let _peer = start(&core, bundle.path());
    assert!(direct(&core, "8.8.8.8"));
    assert!(!direct(&core, "9.9.9.9"));
    assert_eq!(status(&core)["reason"], "invalid_generation");
    assert_eq!(status(&core)["updateWaitingForNextStart"], false);
}
#[test]
fn offline_command_returns_reason_without_starting_download() {
    let dir = TempDir::new("geo-status");
    let core = Core::new(dir.path().to_str().unwrap()).unwrap();
    let fixture: Value = serde_json::from_str(include_str!(
        "../../../../../contracts/doona-core/call-ok-config-assets.json"
    ))
    .unwrap();
    assert_eq!(status(&core), fixture["ok"]);
    assert_eq!(
        core.call(
            "configAssets",
            include_str!("../../../../../contracts/doona-core/call-config-assets-update-args.json")
        ),
        r#"{"error":{"code":"fetch_failed"}}"#
    );
    assert_eq!(status(&core)["reason"], "not_connected");
    assert!(!dir.path().join("geo").exists());
}
