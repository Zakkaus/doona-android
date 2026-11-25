//! The publisher protocol with honk's real preparer: the store validates with honk's `Config`,
//! the preparer builds honk's embedded engine on the data root, and an apply commits only what
//! honk accepted. Each case of the stock reference corpus prepares or is refused exactly as
//! stock honk decides.

mod common;

use std::fs;
use std::net::IpAddr;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use common::TempDir;
use doonacore::honkconf::prepare::HonkPreparer;
use doonacore::honkconf::publish::{
    Applied, Generation, Notice, Preparer, PublishError, Publisher, StartError,
};
use doonacore::honkconf::runtime::{Flow, Network, Runtime, RuntimeError};
use doonacore::honkconf::{
    Capabilities, Geo, GeoCatalog, HonkConfig, HonkConfigError, HonkStore, Label, StoreError,
};
use honk_config::diagnostic::DetailedDiagnostic;

const A: &str = "routing { fallback: direct }";
const B: &str = "routing { fallback: block }";
/// Needs `geosite:cn`, which only the full pair holds.
const G: &str = "routing {\n    domain(geosite:cn) -> direct\n    fallback: block\n}";
const G2: &str = "routing {\n    domain(geosite:cn) -> block\n    fallback: direct\n}";
/// Names a category no pair holds.
const MISSING: &str = "routing {\n    domain(geosite:nowhere) -> direct\n    fallback: block\n}";

// ---- geo pairs: honk reads v2ray protobuf DATs ----

fn varint(mut v: u64, out: &mut Vec<u8>) {
    while v >= 0x80 {
        out.push(v as u8 | 0x80);
        v >>= 7;
    }
    out.push(v as u8);
}

fn bytes(tag: u64, body: &[u8], out: &mut Vec<u8>) {
    varint(tag << 3 | 2, out);
    varint(body.len() as u64, out);
    out.extend_from_slice(body);
}

fn number(tag: u64, v: u64, out: &mut Vec<u8>) {
    varint(tag << 3, out);
    varint(v, out);
}

/// `sites` are GEOSITE codes with their domain suffixes; `ips` GeoIP codes with one /8 each.
fn write_pair(dir: &Path, sites: &[(&str, &str)], ips: &[(&str, u8)]) {
    let mut site_list = Vec::new();
    for (code, suffix) in sites {
        let mut domain = Vec::new();
        number(1, 2, &mut domain); // RootDomain: the suffix and its subdomains
        bytes(2, suffix.as_bytes(), &mut domain);
        let mut site = Vec::new();
        bytes(1, code.as_bytes(), &mut site);
        bytes(2, &domain, &mut site);
        bytes(1, &site, &mut site_list);
    }
    let mut ip_list = Vec::new();
    for (code, first) in ips {
        let mut cidr = Vec::new();
        bytes(1, &[*first, 0, 0, 0], &mut cidr);
        number(2, 8, &mut cidr);
        let mut ip = Vec::new();
        bytes(1, code.as_bytes(), &mut ip);
        bytes(2, &cidr, &mut ip);
        bytes(1, &ip, &mut ip_list);
    }
    fs::create_dir_all(dir).unwrap();
    fs::write(dir.join("geosite.dat"), site_list).unwrap();
    fs::write(dir.join("geoip.dat"), ip_list).unwrap();
}

/// Says every label is present, so the store's capability report admits each revision and
/// only honk decides whether it prepares.
struct AllPresent;

impl GeoCatalog for AllPresent {
    fn geoip(&self, _: &str) -> Label {
        Label::Present
    }
    fn geoip_nets(&self, _: &str) -> Option<Vec<(IpAddr, u8)>> {
        Some(Vec::new())
    }
    fn geosite(&self, _: &str) -> Label {
        Label::Present
    }
}

fn caps() -> Capabilities<'static> {
    Capabilities {
        ingress: None,
        geo: Geo::Installed {
            generation: 1,
            catalog: &AllPresent,
        },
    }
}

// ---- a real preparer with a hook between the two store locks ----

type Hook = Box<dyn FnOnce() + Send>;

/// Delegates to [`HonkPreparer`]. The hook runs after the next real prepare, while the
/// store is unlocked; an armed generation panics when it is dropped, after the head rename.
struct Hooked {
    real: Arc<HonkPreparer>,
    during: Mutex<Option<Hook>>,
    arm: Mutex<bool>,
}

struct Armed {
    runtime: Runtime,
    armed: bool,
}

impl Drop for Armed {
    fn drop(&mut self) {
        if self.armed {
            panic!("crash after the head rename");
        }
    }
}

impl Preparer for Hooked {
    type Generation = Armed;
    type Error = RuntimeError;

    fn epoch(&self) -> u64 {
        self.real.epoch()
    }

    fn prepare(&self, config: &HonkConfig) -> Result<Armed, RuntimeError> {
        let runtime = self.real.prepare(config)?;
        let hook = self.during.lock().unwrap().take();
        if let Some(hook) = hook {
            hook();
        }
        Ok(Armed {
            runtime,
            armed: std::mem::take(&mut *self.arm.lock().unwrap()),
        })
    }
}

struct Setup {
    data: TempDir,
    _geo: TempDir,
    /// Holds `geosite:cn`.
    full: PathBuf,
    /// Holds no DAT at all.
    empty: PathBuf,
    store: Arc<Mutex<HonkStore>>,
    real: Arc<HonkPreparer>,
    hooked: Arc<Hooked>,
    publisher: Publisher<HookedRef>,
}

/// The publisher owns its preparer; tests keep a second handle to set hooks.
struct HookedRef(Arc<Hooked>);

impl Preparer for HookedRef {
    type Generation = Armed;
    type Error = RuntimeError;
    fn epoch(&self) -> u64 {
        self.0.epoch()
    }
    fn prepare(&self, config: &HonkConfig) -> Result<Armed, RuntimeError> {
        self.0.prepare(config)
    }
}

fn setup(tag: &str) -> Setup {
    let data = TempDir::new(tag);
    let geo = TempDir::new(&format!("{tag}-geo"));
    let full = geo.path().join("full");
    write_pair(&full, &[("cn", "cn")], &[("cn", 114)]);
    let empty = geo.path().join("empty");
    fs::create_dir_all(&empty).unwrap();
    let store = Arc::new(Mutex::new(HonkStore::open(data.path()).unwrap()));
    let real = Arc::new(HonkPreparer::new(&full));
    let hooked = Arc::new(Hooked {
        real: real.clone(),
        during: Mutex::new(None),
        arm: Mutex::new(false),
    });
    let publisher = Publisher::new(HookedRef(hooked.clone()));
    Setup {
        data,
        _geo: geo,
        full,
        empty,
        store,
        real,
        hooked,
        publisher,
    }
}

fn flow(domain: &str) -> Flow {
    Flow {
        network: Network::Tcp,
        source: "10.0.0.2:40000".parse().unwrap(),
        destination: "203.0.113.9:443".parse().unwrap(),
        domain: Some(domain.to_owned()),
        domain_verified: true,
        process_name: None,
        mac: None,
        dscp: None,
    }
}

impl Setup {
    fn draft(&self, text: &str) -> u64 {
        let mut store = self.store.lock().unwrap();
        let id = store.draft(None).unwrap();
        store.edit(id, Path::new("config.dae"), text).unwrap();
        id
    }

    fn apply(&self, draft: u64) -> Result<Applied, PublishError<RuntimeError>> {
        self.publisher.apply(&self.store, draft, &caps())
    }

    fn during(&self, hook: impl FnOnce() + Send + 'static) {
        *self.hooked.during.lock().unwrap() = Some(Box::new(hook));
    }

    fn head(&self) -> (u64, String) {
        let store = self.store.lock().unwrap();
        let head = store.head().unwrap();
        (head, text(&store, head))
    }

    fn live(&self) -> Arc<Generation<Armed>> {
        self.publisher.current().unwrap()
    }

    fn live_is(&self, generation: &Arc<Generation<Armed>>) -> bool {
        Arc::ptr_eq(&self.live(), generation)
    }

    /// Drop the process state as a crash would and reopen the store from disk.
    fn reopen(self) -> (TempDir, HonkStore) {
        let Setup { data, store, .. } = self;
        drop(store);
        let store = HonkStore::open(data.path()).unwrap();
        (data, store)
    }
}

fn outbound(generation: &Generation<Armed>, domain: &str) -> String {
    generation.prepared.runtime.decide(&flow(domain)).outbound
}

fn text(store: &HonkStore, rev: u64) -> String {
    store.read_revision(rev).unwrap().sources()[0]
        .content
        .to_string()
}

fn revision_dirs(data: &TempDir) -> Vec<u64> {
    let mut ids: Vec<u64> = fs::read_dir(data.path().join("config/revisions"))
        .unwrap()
        .map(|e| e.unwrap().file_name().to_str().unwrap().parse().unwrap())
        .collect();
    ids.sort_unstable();
    ids
}

fn asset_error(e: &PublishError<RuntimeError>, kind: std::io::ErrorKind) -> bool {
    matches!(e, PublishError::Prepare(RuntimeError::Asset(io)) if io.kind() == kind)
}

// ---- lane H's list with the real preparer ----

#[test]
fn failed_prepare_or_stale_epoch_leaves_head_and_live_generation() {
    let t = setup("prepare-refused");
    t.apply(t.draft(A)).unwrap();
    let live = t.publisher.start(&t.store).unwrap();

    // honk refuses: a category the pair lacks, then a regex honk cannot compile.
    let missing = t.draft(MISSING);
    let e = t.apply(missing).unwrap_err();
    assert!(asset_error(&e, std::io::ErrorKind::InvalidData), "{e}");
    let regex = t.draft("routing {\n    domain(regex:'(') -> direct\n    fallback: block\n}");
    let e = t.apply(regex).unwrap_err();
    assert!(
        matches!(e, PublishError::Prepare(RuntimeError::Config(_))),
        "{e}"
    );
    assert_eq!(t.head(), (1, A.to_owned()));
    assert!(t.live_is(&live));

    // The asset generation changes while the draft is prepared against the old one.
    let g = t.draft(G);
    let (real, store, empty) = (t.real.clone(), t.store.clone(), t.empty.clone());
    t.during(move || real.set_data_root(&mut store.lock().unwrap(), empty));
    assert!(matches!(
        t.apply(g),
        Err(PublishError::StaleInputs {
            prepared: 0,
            current: 1
        })
    ));
    assert_eq!(t.head(), (1, A.to_owned()));
    assert!(t.live_is(&live));

    // Every refused draft stays editable; the next attempt prepares against today's inputs.
    let e = t.apply(g).unwrap_err();
    assert!(asset_error(&e, std::io::ErrorKind::NotFound), "{e}");
    t.real
        .set_data_root(&mut t.store.lock().unwrap(), t.full.clone());
    assert_eq!(
        t.apply(g).unwrap(),
        Applied {
            rev: 2,
            generation: Some(2)
        }
    );
    assert!(t.store.lock().unwrap().check(missing).is_ok());
}

#[test]
fn swap_follows_the_head_rename_and_open_flows_keep_their_generation() {
    let t = setup("prepare-swap");
    t.apply(t.draft(A)).unwrap();
    let flow = t.publisher.start(&t.store).unwrap();
    let b = t.draft(B);

    // The rename itself fails: head is a non-empty directory. Nothing is swapped.
    let head = t.data.path().join("config/head");
    let committed = fs::read(&head).unwrap();
    fs::remove_file(&head).unwrap();
    fs::create_dir_all(head.join("blocker")).unwrap();
    assert!(matches!(
        t.apply(b),
        Err(PublishError::Store(StoreError::Io(_)))
    ));
    assert!(t.live_is(&flow));
    fs::remove_dir_all(&head).unwrap();
    fs::write(&head, committed).unwrap();

    assert_eq!(
        t.apply(b).unwrap(),
        Applied {
            rev: 2,
            generation: Some(2)
        }
    );
    let new = t.live();
    assert_eq!((new.id, new.rev), (2, 2));
    assert_eq!(outbound(&new, "example.org"), "block");
    // The open flow still decides with the generation it started with.
    assert_eq!((flow.id, flow.rev), (1, 1));
    assert_eq!(outbound(&flow, "example.org"), "direct");
    let old = Arc::downgrade(&flow);
    drop(flow);
    assert!(old.upgrade().is_none());
}

#[test]
fn crash_before_the_head_rename_reopens_the_old_head() {
    let t = setup("prepare-crash-before");
    t.apply(t.draft(A)).unwrap();
    let b = t.draft(B);
    t.during(|| panic!("crash before the head rename"));
    assert!(catch_unwind(AssertUnwindSafe(|| t.apply(b))).is_err());
    let (data, store) = t.reopen();
    assert_eq!(store.head(), Some(1));
    assert_eq!(text(&store, 1), A);
    assert_eq!(store.current_draft().unwrap().id, b);
    assert_eq!(revision_dirs(&data), [1]);
}

#[test]
fn crash_after_the_head_rename_reopens_the_new_head() {
    let t = setup("prepare-crash-after");
    t.apply(t.draft(A)).unwrap();
    let b = t.draft(B);
    *t.hooked.arm.lock().unwrap() = true;
    assert!(catch_unwind(AssertUnwindSafe(|| t.apply(b))).is_err());
    let (data, store) = t.reopen();
    assert_eq!(store.head(), Some(2));
    assert_eq!(text(&store, 2), B);
    assert!(store.current_draft().is_none());
    assert_eq!(revision_dirs(&data), [1, 2]);
}

#[test]
fn unpreparable_head_refuses_start_and_restores_only_on_request() {
    let t = setup("prepare-cold");
    for source in [A, G, G2] {
        t.apply(t.draft(source)).unwrap();
    }
    // The pair is gone at the next cold start: revisions 3 and 2 need it, 1 does not.
    t.real
        .set_data_root(&mut t.store.lock().unwrap(), t.empty.clone());
    let refused = match t.publisher.start(&t.store) {
        Err(StartError::Refused(notice)) => notice,
        other => panic!("{:?}", other.map(|g| g.id)),
    };
    assert_eq!((refused.rejected, refused.restorable), (3, Some(1)));
    assert!(
        refused.reason.starts_with("honk geo asset:"),
        "{}",
        refused.reason
    );
    assert!(t.publisher.current().is_none());
    assert_eq!(t.head(), (3, G2.to_owned()));

    // The notice survives the process; nothing older ran or was committed meanwhile.
    let (data, store) = t.reopen();
    assert_eq!(store.notice(), Some(&refused));
    assert_eq!(store.head(), Some(3));
    let store = Mutex::new(store);
    let publisher = Publisher::new(HonkPreparer::new(data.path().join("no-assets")));
    assert!(matches!(
        publisher.start(&store),
        Err(StartError::Refused(n)) if (n.rejected, n.restorable) == (3, Some(1))
    ));

    // The owner taps restore: revision 1 is committed again, freshly prepared, still stopped.
    let applied = publisher.restore(&store, 1, &caps()).unwrap();
    assert_eq!(
        applied,
        Applied {
            rev: 4,
            generation: None
        }
    );
    let live = publisher.start(&store).unwrap();
    assert_eq!((live.id, live.rev), (1, 4));
    assert_eq!(
        live.prepared.decide(&flow("example.org")).outbound,
        "direct"
    );
    assert!(store.lock().unwrap().notice().is_none());
}

#[test]
fn start_without_a_preparable_revision_offers_none() {
    let t = setup("prepare-cold-none");
    t.apply(t.draft(G)).unwrap();
    t.real
        .set_data_root(&mut t.store.lock().unwrap(), t.empty.clone());
    let expected = Notice {
        rejected: 1,
        reason: match t.publisher.start(&t.store) {
            Err(StartError::Refused(n)) => n.reason,
            _ => panic!("started"),
        },
        restorable: None,
    };
    assert_eq!(t.store.lock().unwrap().notice(), Some(&expected));
    assert!(t.publisher.current().is_none());
}

#[test]
fn stopped_apply_returns_no_generation_and_start_prepares_head() {
    let t = setup("prepare-stopped");
    assert!(matches!(
        t.publisher.start(&t.store),
        Err(StartError::NoHead)
    ));
    let a = t.apply(t.draft(A)).unwrap();
    assert_eq!(
        a,
        Applied {
            rev: 1,
            generation: None
        }
    );
    // A stopped apply still prepares: honk's refusal commits nothing.
    let e = t.apply(t.draft(MISSING)).unwrap_err();
    assert!(asset_error(&e, std::io::ErrorKind::InvalidData), "{e}");
    assert_eq!(t.head(), (1, A.to_owned()));

    let live = t.publisher.start(&t.store).unwrap();
    assert_eq!((live.id, live.rev), (1, 1));
    t.publisher.stop();
    assert!(t.publisher.current().is_none());
    let b = t.apply(t.draft(B)).unwrap();
    assert_eq!(
        b,
        Applied {
            rev: 2,
            generation: None
        }
    );
}

// ---- generation numbers ----

#[test]
fn generations_rise_by_one_per_committed_apply_and_a_no_op_keeps_them() {
    let t = setup("prepare-generations");
    t.apply(t.draft(A)).unwrap();
    assert_eq!(t.publisher.start(&t.store).unwrap().id, 1);
    let mut expected = 1;
    for source in [B, G, A] {
        expected += 1;
        let applied = t.apply(t.draft(source)).unwrap();
        assert_eq!(applied.generation, Some(expected));
        assert_eq!(t.live().id, expected);
    }
    // A refused apply leaves the count alone.
    assert!(t.apply(t.draft(MISSING)).is_err());
    assert_eq!(t.live().id, expected);

    // Head's exact bytes again: no revision, no generation, and the draft is gone.
    let live = t.live();
    let same = t.draft(A);
    assert_eq!(
        t.apply(same).unwrap(),
        Applied {
            rev: 4,
            generation: Some(expected)
        }
    );
    assert!(t.live_is(&live));
    assert_eq!(revision_dirs(&t.data), [1, 2, 3, 4]);
    assert!(matches!(
        t.store.lock().unwrap().check(same),
        Err(StoreError::UnknownDraft)
    ));

    // An edit that is undone before Apply is a no-op too; a real change is the next generation.
    let undone = t.draft(A);
    t.store
        .lock()
        .unwrap()
        .edit(undone, Path::new("config.dae"), B)
        .unwrap();
    t.store
        .lock()
        .unwrap()
        .edit(undone, Path::new("config.dae"), A)
        .unwrap();
    assert_eq!(t.apply(undone).unwrap().generation, Some(expected));
    assert_eq!(
        t.apply(t.draft(B)).unwrap(),
        Applied {
            rev: 5,
            generation: Some(expected + 1)
        }
    );

    // Stopped, a no-op returns head with no generation.
    t.publisher.stop();
    assert_eq!(
        t.apply(t.draft(B)).unwrap(),
        Applied {
            rev: 5,
            generation: None
        }
    );
}

// ---- the stock reference corpus ----

/// What stock honk decides for one `.dae` file: its loader (which validates) and its embedded
/// engine build on `root`. `Err` carries the loader's terminal diagnostic when there is one.
fn stock(path: &Path, root: &Path) -> (Vec<DetailedDiagnostic>, Result<(), Refusal>) {
    let mut diagnostics = Vec::new();
    let loaded = honk_config::parser::parse_dae_config_file_with_detailed_diagnostics(
        path,
        &mut diagnostics,
    );
    let result = match loaded {
        Err(e) => Err(Refusal::Load(Span::of(&e.diagnostic))),
        Ok(config) => match config.validate_detailed() {
            Err(e) => Err(Refusal::Load(Span::of(&e.diagnostic))),
            Ok(()) => honk_core::embedded::Engine::new(config, root)
                .map(drop)
                .map_err(|e| Refusal::Build(format!("{e:#}"))),
        },
    };
    (diagnostics, result)
}

#[derive(Debug, PartialEq, Eq)]
struct Span {
    code: &'static str,
    message: &'static str,
    setting: String,
    bytes: Option<std::ops::Range<usize>>,
    line: Option<usize>,
    column: Option<usize>,
}

impl Span {
    fn of(d: &DetailedDiagnostic) -> Self {
        Self {
            code: d.code,
            message: d.message,
            setting: d.setting.to_string(),
            bytes: d.span.clone(),
            line: d.line,
            column: d.byte_column,
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
enum Refusal {
    /// The loader or `Config::validate` refused, with honk's diagnostic and span.
    Load(Span),
    /// The engine build refused; honk gives no span here.
    Build(String),
}

/// What doona decides for the same text: a running Apply through the store and publisher.
fn doona(source: &str, root: &Path) -> (Vec<DetailedDiagnostic>, Result<(), Refusal>) {
    let data = TempDir::new("prepare-corpus");
    let store = Mutex::new(HonkStore::open(data.path()).unwrap());
    let publisher = Publisher::new(HonkPreparer::new(root));
    let seed = store.lock().unwrap().draft(None).unwrap();
    store
        .lock()
        .unwrap()
        .edit(seed, Path::new("config.dae"), A)
        .unwrap();
    publisher.apply(&store, seed, &caps()).unwrap();
    publisher.start(&store).unwrap();
    let id = store.lock().unwrap().draft(None).unwrap();
    // The store keeps text that does not load; the apply then reports honk's refusal.
    store
        .lock()
        .unwrap()
        .edit(id, Path::new("config.dae"), source)
        .unwrap();
    let result = match publisher.apply(&store, id, &caps()) {
        Ok(applied) => {
            assert_eq!(
                applied,
                Applied {
                    rev: 2,
                    generation: Some(2)
                }
            );
            let head = store.lock().unwrap().read_revision(2).unwrap();
            return (head.diagnostics().to_vec(), Ok(()));
        }
        Err(PublishError::Store(StoreError::Config(HonkConfigError::ParseFailed {
            cause,
            ..
        }))) => Err(Refusal::Load(Span::of(&cause.diagnostic))),
        Err(PublishError::Prepare(e)) => Err(Refusal::Build(match e {
            RuntimeError::Config(e) => format!("{e:#}"),
            RuntimeError::Asset(e) => format!("{:#}", anyhow::Error::from(e)),
            other => panic!("{other}"),
        })),
        Err(other) => panic!("doona refused for its own reason: {other}"),
    };
    // A refusal leaves head and the live generation as they were.
    assert_eq!(store.lock().unwrap().head(), Some(1));
    assert_eq!(publisher.current().unwrap().rev, 1);
    (Vec::new(), result)
}

fn codes(diagnostics: &[DetailedDiagnostic]) -> Vec<(&'static str, Option<usize>)> {
    diagnostics.iter().map(|d| (d.code, d.line)).collect()
}

/// doona's verdict and warnings for `path`, after checking both against stock honk's.
fn check(name: &str, path: &Path, root: &Path) -> (Vec<DetailedDiagnostic>, Result<(), Refusal>) {
    let source = fs::read_to_string(path).unwrap();
    let (stock_diagnostics, stock_result) = stock(path, root);
    let (diagnostics, result) = doona(&source, root);
    assert_eq!(result, stock_result, "{name}");
    if result.is_ok() {
        assert_eq!(codes(&diagnostics), codes(&stock_diagnostics), "{name}");
    }
    (diagnostics, result)
}

/// Corpus cases whose routing names a node or an undeclared target. honk's loader reads them,
/// so the reference report routes them, but `Config::validate` refuses each one with
/// `unknown-routing-target`, and so does the daemon's admission.
const STOCK_REFUSES: [&str; 3] = ["zero-groups", "node-in-no-group", "unknown-outbound"];

const REFERENCE: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/honk/reference");

/// Every case of cuta's corpus against `root`, returning the cases stock honk refuses. The
/// reference report records the loader's verdict and warnings only (its harness never calls
/// `Config::validate`), so a loader refusal there must be a refusal here, and an accepted case
/// carries the report's warnings.
fn reference_corpus(root: &Path) -> Vec<String> {
    let reference = Path::new(REFERENCE);
    let read = |name| -> serde_json::Value {
        serde_json::from_slice(&fs::read(reference.join(name)).unwrap()).unwrap()
    };
    let (corpus, expected) = (read("corpus.json"), read("expected.json"));
    let cases = corpus["cases"].as_array().unwrap();
    assert!(!cases.is_empty());
    let mut refused = Vec::new();
    for case in cases {
        let name = case["name"].as_str().unwrap();
        let report = expected["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|c| c["name"] == name)
            .unwrap();
        let path = reference.join(case["config"].as_str().unwrap());
        let (diagnostics, result) = check(name, &path, root);
        if result.is_err() {
            refused.push(name.to_owned());
            continue;
        }
        assert!(report["error"].is_null(), "{name}");
        let warnings: Vec<_> = report["diagnostics"]
            .as_array()
            .unwrap()
            .iter()
            .map(|d| d["code"].as_str().unwrap().to_owned())
            .collect();
        let ours: Vec<_> = diagnostics.iter().map(|d| d.code.to_owned()).collect();
        assert_eq!(ours, warnings, "{name}");
    }
    refused
}

#[test]
fn reference_corpus_prepares_as_stock_honk_decides() {
    // Every label the corpus names; the pinned pair is in the ignored test below.
    let geo = TempDir::new("prepare-corpus-geo");
    write_pair(
        geo.path(),
        &[("cn", "cn"), ("google", "google.com")],
        &[("cn", 114)],
    );
    assert_eq!(reference_corpus(geo.path()), STOCK_REFUSES);
}

#[test]
#[ignore = "requires DOONA_GEO_DIR containing the pinned DATs"]
fn reference_corpus_on_the_pinned_pair() {
    let source = std::env::var_os("DOONA_GEO_DIR").unwrap();
    let stage = TempDir::new("prepare-corpus-pinned");
    let dir = stage.path().join("bundle");
    doonacore::honkconf::assets::Release::from_manifest(include_bytes!("../tools/geo-pins.json"))
        .unwrap()
        .stage_bundle(Path::new(&source), &dir)
        .unwrap();
    assert_eq!(reference_corpus(&dir), STOCK_REFUSES);
}

/// Configurations stock honk refuses at each stage, beside the corpus (which it accepts).
#[test]
fn refusals_carry_honks_error_and_span() {
    let geo = TempDir::new("prepare-refusal-geo");
    write_pair(geo.path(), &[("cn", "cn")], &[("cn", 114)]);
    let dir = TempDir::new("prepare-refusal-cases");
    let cases = [
        ("syntax", "routing {\n    fallback: direct\n"),
        (
            "reserved-node-name",
            "node {\n    direct: 'socks5://10.0.0.1:1080'\n}\nrouting { fallback: direct }",
        ),
        (
            "unknown-group",
            "routing {\n    domain(suffix: a.test) -> direct\n    fallback: nowhere-group\n}",
        ),
        (
            "regex",
            "routing {\n    domain(regex:'(') -> direct\n    fallback: block\n}",
        ),
        ("missing-category", MISSING),
        (
            "dial-mode",
            "global { dial_mode: sideways }\nrouting { fallback: direct }",
        ),
    ];
    let (mut spans, mut builds) = (0, 0);
    for (name, source) in cases {
        let path = dir.path().join(format!("{name}.dae"));
        fs::write(&path, source).unwrap();
        match check(name, &path, geo.path()).1 {
            Ok(()) => panic!("{name}: stock honk accepted it"),
            Err(Refusal::Load(span)) => {
                spans += 1;
                // Parse errors carry a byte span; validation errors carry the setting path.
                assert!(
                    span.line.is_some() || !span.setting.is_empty(),
                    "{name}: {span:?}"
                );
            }
            Err(Refusal::Build(message)) => {
                builds += 1;
                assert!(!message.is_empty(), "{name}");
            }
        }
    }
    assert!(spans >= 1 && builds >= 1, "{spans} {builds}");
}
