//! The counters that must outlive the process, beside `state.json`: the day's usage in
//! `stats.json`, written at most once a minute while it changes, and each node's latency tests in
//! `latency.json`, written when a test round ends, so a minute of traffic does not rewrite every
//! node's tests. Not `state.json`, which is the config the user chose and holds every
//! subscription's nodes: rewriting it each minute for counters would wear the flash and take the
//! state writer's lock. Written the same way, to a synced temporary file renamed over the old one;
//! a file this core cannot read is set aside, and its store starts empty.

use super::day::Usage;
use super::latency::Tests;
use crate::api::lock;
use crate::diag::{Diag, Event};
use crate::state::{sync_dir, write_synced};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fs;
use std::io::{self, ErrorKind};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

/// The layout version of both files: 2 keeps days by date, and the latency tests apart.
pub(crate) const STATS_VERSION: u32 = 2;

pub(crate) const USAGE: &str = "stats.json";
pub(crate) const LATENCY: &str = "latency.json";

/// `stats.json`.
#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct UsageFile {
    pub stats_version: u32,
    #[serde(default)]
    pub usage: Usage,
}

/// `latency.json`: node id to its last tests, oldest first.
#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct LatencyFile {
    pub stats_version: u32,
    #[serde(default)]
    pub latency: BTreeMap<String, Tests>,
}

/// A file's layout version, read before the rest.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Head {
    stats_version: u32,
}

pub(crate) struct StatsFile {
    dir: PathBuf,
    name: &'static str,
    /// One write at a time; each takes the newest data once it holds this.
    writing: Mutex<()>,
    /// Where a file that cannot be read or written is recorded.
    diag: Arc<Diag>,
}

impl StatsFile {
    pub fn new(dir: &Path, name: &'static str, diag: Arc<Diag>) -> StatsFile {
        StatsFile {
            dir: dir.to_path_buf(),
            name,
            writing: Mutex::new(()),
            diag,
        }
    }

    /// The saved data; the default when there is none, or when the file was set aside.
    pub fn load<T: DeserializeOwned + Default>(&self) -> T {
        let path = self.dir.join(self.name);
        let text = match fs::read(&path) {
            Ok(text) => text,
            Err(e) => {
                if e.kind() != ErrorKind::NotFound {
                    let event = Event::warn("statsRead").io(e.kind()).detail(self.name);
                    self.diag.record(event);
                }
                return T::default();
            }
        };
        let ours =
            serde_json::from_slice::<Head>(&text).is_ok_and(|h| h.stats_version == STATS_VERSION);
        match ours
            .then(|| serde_json::from_slice::<T>(&text).ok())
            .flatten()
        {
            Some(data) => data,
            None => {
                let aside = self.dir.join(format!("{}.unreadable", self.name));
                let mut event = Event::warn("statsSetAside").detail(self.name);
                if let Err(e) = fs::rename(&path, aside) {
                    event = event.io(e.kind());
                }
                self.diag.record(event);
                T::default()
            }
        }
    }

    /// Writes `bytes` atomically; the caller holds `writing`.
    fn write(&self, bytes: &[u8]) -> io::Result<()> {
        let temp = self.dir.join(format!("{}.tmp", self.name));
        let written =
            write_synced(&temp, bytes).and_then(|()| fs::rename(&temp, self.dir.join(self.name)));
        if written.is_err() {
            let _ = fs::remove_file(&temp);
        }
        written?;
        sync_dir(&self.dir)
    }

    /// Writes what `data` gives once this file's earlier writes are done, encoded and written
    /// outside any lock `data` takes; failures are logged. Answers the kind of a failed write.
    pub fn save<T: Serialize>(&self, data: impl FnOnce() -> T) -> Result<(), ErrorKind> {
        let _writing = lock(&self.writing);
        let written = serde_json::to_vec(&data())
            .map_err(io::Error::from)
            .and_then(|bytes| self.write(&bytes));
        if let Err(e) = &written {
            let event = Event::warn("statsWrite").io(e.kind()).detail(self.name);
            self.diag.record(event);
        }
        written.map_err(|e| e.kind())
    }
}
