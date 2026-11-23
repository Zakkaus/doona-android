//! The payloads of `diagnostics` and `setLogLevel` (core-api.md), re-exported by `dto.rs`.

use crate::dto::{ErrorCode, Today, Traffic};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// How severe an event is; the ring keeps those at its level and more severe.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Level {
    Error,
    Warn,
    Info,
    Debug,
}

impl Level {
    /// 1 for `error` to 4 for `debug`: a level keeps the events of its rank and below.
    pub fn rank(self) -> u8 {
        match self {
            Level::Error => 1,
            Level::Warn => 2,
            Level::Info => 3,
            Level::Debug => 4,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Level::Error => "error",
            Level::Warn => "warn",
            Level::Info => "info",
            Level::Debug => "debug",
        }
    }

    pub(crate) fn of_rank(rank: u8) -> Level {
        match rank {
            1 => Level::Error,
            2 => Level::Warn,
            3 => Level::Info,
            _ => Level::Debug,
        }
    }

    pub(crate) fn log(self) -> log::Level {
        match self {
            Level::Error => log::Level::Error,
            Level::Warn => log::Level::Warn,
            Level::Info => log::Level::Info,
            Level::Debug => log::Level::Debug,
        }
    }
}

/// `setLogLevel`'s arguments.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct LevelArgs {
    pub level: Level,
}

/// `diagnostics`' arguments: the least severe level of the events returned; all when absent.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct DiagnosticsArgs {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub level: Option<Level>,
}

/// `diagnostics`' reply.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Diagnostics {
    pub core: CoreBuild,
    /// The ring's level.
    pub level: Level,
    /// Oldest first.
    pub events: Vec<DiagEvent>,
    /// Events the ring let go since `init`.
    pub overwritten: u64,
    /// What the last `snapshot` answered; null before the first.
    pub snapshot: Option<LastSnapshot>,
    pub stats: StatsSummary,
    /// Oldest first.
    pub crashes: Vec<CrashFile>,
}

/// `release` or `debug`.
pub(crate) fn profile() -> &'static str {
    if cfg!(debug_assertions) {
        "debug"
    } else {
        "release"
    }
}

/// The library and how it was built.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct CoreBuild {
    pub version: String,
    pub schema: u32,
    /// `release` or `debug`.
    pub profile: String,
    /// Architecture and OS, as `aarch64-android`.
    pub target: String,
    pub features: Vec<String>,
}

impl CoreBuild {
    pub(crate) fn current() -> CoreBuild {
        let mut features = Vec::new();
        if cfg!(feature = "fault-hook") {
            features.push("fault-hook".to_owned());
        }
        CoreBuild {
            version: env!("CARGO_PKG_VERSION").to_owned(),
            schema: crate::dto::SCHEMA,
            profile: profile().to_owned(),
            target: format!("{}-{}", std::env::consts::ARCH, std::env::consts::OS),
            features,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiagEvent {
    pub at_epoch_ms: i64,
    pub level: Level,
    pub name: String,
    pub code: Option<String>,
    pub detail: Option<String>,
    pub counts: BTreeMap<String, u32>,
    pub ms: Option<u32>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LastSnapshot {
    pub at_epoch_ms: i64,
    pub revision: i64,
    pub running: bool,
    pub generation: i64,
    pub error: Option<ErrorCode>,
    pub warning: Option<ErrorCode>,
}

/// The running generation's traffic (zero while stopped) and the local day's totals, as `snapshot`
/// reports them.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct StatsSummary {
    pub traffic: Traffic,
    pub today: Today,
}

/// `clearCrashes`' arguments: the crash files the app exported.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ClearCrashesArgs {
    pub names: Vec<String>,
}

/// A file in `dataDir/crash/`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct CrashFile {
    pub name: String,
    pub bytes: u64,
}
