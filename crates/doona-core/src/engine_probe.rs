//! `dev.doona.android.probe.EngineProbe`: the on-device check of the embedded honk engine
//! (`app/src/androidTest`). Built only with the `engine-probe` feature, which only the probe
//! build of the debug app enables (`-Pdoona.engineProbe=true`).

use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::path::Path;

use jni::objects::{JObject, JString};
use jni::{Env, NativeMethod, native_method};

use crate::honkconf::runtime::{Decision, Flow, GlobalTarget, Mode, ModeResult, Network, Runtime};
use crate::honkconf::{Capabilities, HonkStore};
use crate::jni::ThrowInternal;

const FIXTURE: &str = include_str!("../tests/fixtures/honk/runtime.dae");
const SOURCE: SocketAddr = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::new(10, 0, 0, 2), 40000));

#[allow(deprecated)]
const _: NativeMethod = native_method! {
    java_type = "dev.doona.android.probe.EngineProbe",
    fn decide(dir: JString, mode: JString, global: JString, destination: JString, domain: JString) -> JString,
    export = "Java_dev_doona_android_probe_EngineProbe_decide",
    error_policy = ThrowInternal,
};

fn decide<'local>(
    env: &mut Env<'local>,
    _this: JObject<'local>,
    dir: JString<'local>,
    mode: JString<'local>,
    global: JString<'local>,
    destination: JString<'local>,
    domain: JString<'local>,
) -> jni::errors::Result<JString<'local>> {
    let [dir, mode, global, destination, domain] =
        [dir, mode, global, destination, domain].map(|s| s.try_to_string(env));
    let reply = run(&dir?, &mode?, &global?, &destination?, &domain?)
        .unwrap_or_else(|error| format!("error: {error}"));
    env.new_string(reply)
}

/// Commits the fixture to a store under `dir`, builds the engine with `dir/geo` as its data
/// root and decides one TCP flow: `outbound mode chain`.
fn run(
    dir: &str,
    mode: &str,
    global: &str,
    destination: &str,
    domain: &str,
) -> Result<String, String> {
    let dir = Path::new(dir);
    let geo = dir.join("geo");
    std::fs::create_dir_all(&geo).map_err(|e| e.to_string())?;
    let store = commit(&dir.join("config"))?;
    let runtime =
        Runtime::load(&store, &Capabilities::linked(), &geo).map_err(|e| e.to_string())?;
    runtime.set_mode(match mode {
        "global" => Mode::Global,
        "direct" => Mode::Direct,
        _ => Mode::Rule,
    });
    if let Some(target) = target(&store, global)?
        && runtime.set_global_target(target).is_err()
    {
        return Ok("unknown-target".into());
    }
    Ok(describe(
        &runtime.decide(&flow(destination, domain)?).decision,
    ))
}

fn commit(dir: &Path) -> Result<HonkStore, String> {
    let mut store = HonkStore::open(dir).map_err(|e| format!("{e:?}"))?;
    let id = store.draft(None).map_err(|e| format!("{e:?}"))?;
    store
        .edit(id, Path::new("config.dae"), FIXTURE)
        .map_err(|e| format!("{e:?}"))?;
    store
        .apply(id, &Capabilities::linked())
        .map_err(|e| format!("{e:?}"))?;
    Ok(store)
}

/// `group:<name>`, `node:<name>`, or anything else for none.
fn target(store: &HonkStore, global: &str) -> Result<Option<GlobalTarget>, String> {
    Ok(match global.split_once(':') {
        Some(("group", name)) => Some(GlobalTarget::Group(name.to_owned())),
        Some(("node", name)) => {
            let rev = store.head().ok_or("no revision")?;
            let doc = store.read_revision(rev).map_err(|e| format!("{e:?}"))?;
            let node = doc.config().nodes.iter().find(|n| n.name == name);
            Some(GlobalTarget::Node(node.ok_or("no such node")?.id))
        }
        _ => None,
    })
}

fn flow(destination: &str, domain: &str) -> Result<Flow, String> {
    Ok(Flow {
        network: Network::Tcp,
        source: SOURCE,
        destination: destination.parse().map_err(|_| "bad destination")?,
        domain: (!domain.is_empty()).then(|| domain.to_owned()),
        domain_verified: !domain.is_empty(),
        process_name: None,
        mac: None,
        dscp: None,
    })
}

fn describe(decision: &Decision) -> String {
    let mode = match &decision.mode {
        ModeResult::Unchanged => "unchanged".to_owned(),
        ModeResult::Direct => "direct".to_owned(),
        ModeResult::Block => "block".to_owned(),
        ModeResult::Group(name) => format!("group:{name}"),
        ModeResult::Node(_) => format!("node:{}", decision.outbound),
    };
    let chain = decision
        .candidates
        .first()
        .map_or_else(|| "-".to_owned(), |leaf| leaf.chain.join(">"));
    format!("{} {mode} {chain}", decision.outbound)
}
