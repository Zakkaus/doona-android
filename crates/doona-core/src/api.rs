//! The plain Rust API behind the five JNI functions. Host tests drive it without a JVM; `jni.rs`
//! only converts arguments, holds the process's one [`Core`] and throws.

use crate::dto::{
    self, AddSubscriptionArgs, Catalog, Empty, ErrorCode, GroupArgs, IdArgs, Mode, ModeArgs, Reply,
    SelectArgs, Snapshot, StartConfig, Traffic,
};
use crate::state::{Loaded, State, Store};
use serde::Serialize;
use serde::de::DeserializeOwned;
use std::os::fd::OwnedFd;
use std::path::Path;
use std::sync::{Mutex, MutexGuard};
use tokio::runtime::Runtime;

/// The tokio runtime's worker threads; flows are I/O bound and the phone has few big cores.
const WORKERS: usize = 2;

pub struct Core {
    runtime: Runtime,
    store: Store,
    catalog: Mutex<CatalogState>,
}

struct CatalogState {
    /// What `state.json` holds; changed only after the file is written.
    state: State,
    /// Rises with every change Kotlin must re-read; starts above Kotlin's initial -1 and 0.
    revision: i64,
}

impl Core {
    /// Reads `data_dir/state.json` and builds the runtime.
    pub fn new(data_dir: &str) -> Result<Core, ErrorCode> {
        let store = Store::new(Path::new(data_dir));
        let (state, loaded) = store.load().map_err(|e| {
            log::error!("init: state unreadable ({:?})", e.kind());
            ErrorCode::Internal
        })?;
        if loaded == Loaded::SetAside {
            log::warn!("init: state.json was unusable; set aside, defaults in use");
        }
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(WORKERS)
            .thread_name("doona-core")
            .enable_all()
            .build()
            .map_err(|_| ErrorCode::Internal)?;
        Ok(Core {
            runtime,
            store,
            catalog: Mutex::new(CatalogState { state, revision: 1 }),
        })
    }

    /// `init`'s reply.
    pub fn schema() -> String {
        to_json(&dto::Init {
            schema: dto::SCHEMA,
        })
    }

    /// Takes ownership of `tun`, the duplicate of Kotlin's descriptor.
    pub fn start(&self, tun: OwnedFd, config: &str) -> Result<(), ErrorCode> {
        let _config: StartConfig =
            serde_json::from_str(config).map_err(|_| ErrorCode::InvalidArgument)?;
        let _ = (&self.runtime, tun);
        Err(ErrorCode::Internal)
    }

    pub fn stop(&self) {}

    pub fn snapshot(&self, since_revision: i64) -> String {
        let catalog = lock(&self.catalog);
        let snapshot = Snapshot {
            revision: catalog.revision,
            running: false,
            generation: 0,
            since_epoch_ms: None,
            error: None,
            traffic: Traffic::default(),
            catalog: (catalog.revision > since_revision).then(|| Catalog {
                mode: catalog.state.mode,
                groups: Vec::new(),
                subscriptions: Vec::new(),
            }),
        };
        to_json(&snapshot)
    }

    pub fn call(&self, command: &str, args: &str) -> String {
        let reply = match command {
            "setMode" => parse(args).and_then(|a: ModeArgs| self.set_mode(a.mode)),
            // Groups, members and subscriptions arrive with the subscription lane; until then
            // nothing they address exists.
            "select" => parse::<SelectArgs>(args).and(Err(ErrorCode::NotFound)),
            "testLatency" => parse(args).and_then(|a: GroupArgs| match a.group {
                Some(_) => Err(ErrorCode::NotFound),
                None => Err(ErrorCode::NoNodes),
            }),
            "addSubscription" => parse::<AddSubscriptionArgs>(args).and(Err(ErrorCode::NoNodes)),
            "updateSubscription" | "removeSubscription" => {
                parse::<IdArgs>(args).and(Err(ErrorCode::NotFound))
            }
            _ => Err(ErrorCode::InvalidArgument),
        };
        match reply {
            Ok(()) => to_json(&Reply::Ok(Empty {})),
            Err(code) => {
                log::info!("call {}: {}", command_name(command), code.as_str());
                error_reply(code)
            }
        }
    }

    /// Commits the mode to `state.json` first; new flows read it from then on.
    fn set_mode(&self, mode: Mode) -> Result<(), ErrorCode> {
        let mut catalog = lock(&self.catalog);
        if catalog.state.mode == mode {
            return Ok(());
        }
        let next = State {
            mode,
            ..catalog.state.clone()
        };
        self.store.save(&next).map_err(|e| {
            log::error!("setMode: state not written ({:?})", e.kind());
            ErrorCode::Internal
        })?;
        catalog.state = next;
        catalog.revision += 1;
        Ok(())
    }
}

/// `{"error":{"code":…}}`.
pub fn error_reply(code: ErrorCode) -> String {
    to_json(&Reply::<Empty>::Error(dto::CallError { code }))
}

/// What `snapshot` answers before `init`: stopped, nothing to show.
pub fn snapshot_before_init() -> String {
    to_json(&Snapshot {
        revision: 0,
        running: false,
        generation: 0,
        since_epoch_ms: None,
        error: None,
        traffic: Traffic::default(),
        catalog: None,
    })
}

/// A command name fit for the log: the closed list, never caller text.
fn command_name(command: &str) -> &'static str {
    const KNOWN: [&str; 6] = [
        "select",
        "testLatency",
        "addSubscription",
        "updateSubscription",
        "removeSubscription",
        "setMode",
    ];
    KNOWN
        .into_iter()
        .find(|k| *k == command)
        .unwrap_or("unknown")
}

fn parse<T: DeserializeOwned>(args: &str) -> Result<T, ErrorCode> {
    serde_json::from_str(args).map_err(|_| ErrorCode::InvalidArgument)
}

fn to_json<T: Serialize>(value: &T) -> String {
    // The DTOs hold only strings, numbers and maps with string keys, which always serialize.
    serde_json::to_string(value).unwrap_or_default()
}

/// A poisoned lock still holds consistent data here: every critical section only assigns.
fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}
