//! `stats.json` beside `state.json`: the counters that must outlive the process (the day's
//! usage and the latency history). Not `state.json`, which is the config the user chose and holds every
//! subscription's nodes: rewriting it each minute for counters would wear the flash and take the
//! state writer's lock. Written the same way, to a synced temporary file renamed over the old one;
//! a file this core cannot read is set aside, and the stats start empty.

use super::day::Usage;
use super::latency::Tests;
use crate::api::lock;
use crate::state::{sync_dir, write_synced};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fs;
use std::io::{self, ErrorKind};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

/// The layout version this core writes and reads; 2 keeps days by date.
pub(crate) const STATS_VERSION: u32 = 2;

const FILE: &str = "stats.json";
const TEMP: &str = "stats.json.tmp";
const SET_ASIDE: &str = "stats.json.unreadable";

/// The file as read.
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Saved {
    pub stats_version: u32,
    #[serde(default)]
    pub usage: Usage,
    /// Node id to its last tests, oldest first.
    #[serde(default)]
    pub latency: BTreeMap<String, Tests>,
}

/// The file as written, borrowed from the stores.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct SavedRef<'a> {
    pub stats_version: u32,
    pub usage: &'a Usage,
    pub latency: &'a BTreeMap<String, Tests>,
}

pub(crate) struct StatsFile {
    dir: PathBuf,
    /// One write at a time; each serializes the newest data once it holds this.
    writing: Mutex<()>,
}

impl StatsFile {
    pub fn new(dir: &Path) -> StatsFile {
        StatsFile {
            dir: dir.to_path_buf(),
            writing: Mutex::new(()),
        }
    }

    /// The saved stats; empty when there are none, or when the file was set aside.
    pub fn load(&self) -> Saved {
        let path = self.dir.join(FILE);
        let text = match fs::read(&path) {
            Ok(text) => text,
            Err(e) => {
                if e.kind() != ErrorKind::NotFound {
                    log::warn!("stats: unreadable ({:?}), starting empty", e.kind());
                }
                return Saved::default();
            }
        };
        match serde_json::from_slice::<Saved>(&text) {
            Ok(saved) if saved.stats_version == STATS_VERSION => saved,
            _ => {
                log::warn!("stats: stats.json was unusable; set aside, starting empty");
                if let Err(e) = fs::rename(&path, self.dir.join(SET_ASIDE)) {
                    log::warn!("stats: not set aside ({:?})", e.kind());
                }
                Saved::default()
            }
        }
    }

    /// Writes `bytes` atomically; the caller holds `writing`.
    fn write(&self, bytes: &[u8]) -> io::Result<()> {
        let temp = self.dir.join(TEMP);
        let written =
            write_synced(&temp, bytes).and_then(|()| fs::rename(&temp, self.dir.join(FILE)));
        if written.is_err() {
            let _ = fs::remove_file(&temp);
        }
        written?;
        sync_dir(&self.dir)
    }

    /// Serializes with `data` while holding the write lock, then writes; failures are logged.
    pub fn save(&self, data: impl FnOnce() -> Option<Vec<u8>>) {
        let _writing = lock(&self.writing);
        let written = data().map(|bytes| self.write(&bytes));
        if let Some(Err(e)) = written {
            log::warn!("stats: not written ({:?})", e.kind());
        }
    }
}
