//! `state.json` in the app's data directory: the mode, the subscriptions and the selections. The
//! core owns this file (Kotlin's DataStore holds only the per-app policy). Writes replace it
//! atomically: a temp file is written and synced, then renamed over the old one.

use crate::dto::Mode;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fs::{self, File};
use std::io::{self, ErrorKind, Write};
use std::path::{Path, PathBuf};

/// The layout version this core writes and reads.
pub const STATE_VERSION: u32 = 1;

const FILE: &str = "state.json";
const TEMP: &str = "state.json.tmp";
/// Where an unreadable file is moved, so a later version or a person can still recover it.
const SET_ASIDE: &str = "state.json.unreadable";

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct State {
    pub state_version: u32,
    pub mode: Mode,
    #[serde(default)]
    pub subscriptions: Vec<StoredSubscription>,
    /// Group id to the member id chosen by hand.
    #[serde(default)]
    pub selections: BTreeMap<String, String>,
}

impl Default for State {
    fn default() -> State {
        State {
            state_version: STATE_VERSION,
            mode: Mode::Global,
            subscriptions: Vec::new(),
            selections: BTreeMap::new(),
        }
    }
}

/// A subscription as stored; its nodes and status are derived at run time.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StoredSubscription {
    pub id: String,
    pub name: String,
    pub url: String,
    pub updated_at_epoch_ms: Option<i64>,
    pub used_bytes: Option<u64>,
    pub total_bytes: Option<u64>,
    pub expires_at_epoch_ms: Option<i64>,
}

/// How [`Store::load`] found the file.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Loaded {
    Read,
    Missing,
    /// Unparsable, or written by a newer layout: set aside and replaced by defaults.
    SetAside,
}

pub struct Store {
    dir: PathBuf,
}

impl Store {
    pub fn new(dir: &Path) -> Store {
        Store {
            dir: dir.to_path_buf(),
        }
    }

    /// Reads the state. A file this core cannot use is moved aside rather than overwritten
    /// later, and the defaults are returned; only I/O failures other than a missing file fail.
    pub fn load(&self) -> io::Result<(State, Loaded)> {
        let path = self.dir.join(FILE);
        let text = match fs::read(&path) {
            Ok(t) => t,
            Err(e) if e.kind() == ErrorKind::NotFound => {
                return Ok((State::default(), Loaded::Missing));
            }
            Err(e) => return Err(e),
        };
        match serde_json::from_slice::<State>(&text) {
            Ok(s) if s.state_version == STATE_VERSION => Ok((s, Loaded::Read)),
            _ => {
                fs::rename(&path, self.dir.join(SET_ASIDE))?;
                Ok((State::default(), Loaded::SetAside))
            }
        }
    }

    pub fn save(&self, state: &State) -> io::Result<()> {
        let bytes = serde_json::to_vec_pretty(state).map_err(io::Error::other)?;
        let temp = self.dir.join(TEMP);
        let result = write_synced(&temp, &bytes)
            .and_then(|()| fs::rename(&temp, self.dir.join(FILE)))
            .and_then(|()| File::open(&self.dir)?.sync_all());
        if result.is_err() {
            let _ = fs::remove_file(&temp);
        }
        result
    }
}

fn write_synced(path: &Path, bytes: &[u8]) -> io::Result<()> {
    let mut f = File::create(path)?;
    f.write_all(bytes)?;
    f.sync_all()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::TempDir;

    #[test]
    fn missing_file_gives_defaults() {
        let dir = TempDir::new("state-missing");
        let (state, how) = Store::new(dir.path()).load().unwrap();
        assert_eq!(how, Loaded::Missing);
        assert_eq!(state, State::default());
        assert_eq!(state.mode, Mode::Global);
    }

    #[test]
    fn save_then_load_round_trips() {
        let dir = TempDir::new("state-round-trip");
        let store = Store::new(dir.path());
        let mut state = State {
            mode: Mode::Direct,
            ..State::default()
        };
        state
            .selections
            .insert("g-Proxy".into(), "n-0c41be77".into());
        store.save(&state).unwrap();
        assert!(!dir.path().join(TEMP).exists());
        let (loaded, how) = store.load().unwrap();
        assert_eq!(how, Loaded::Read);
        assert_eq!(loaded, state);
    }

    #[test]
    fn unknown_fields_are_ignored_and_missing_lists_default() {
        let dir = TempDir::new("state-lenient");
        fs::write(
            dir.path().join(FILE),
            br#"{"stateVersion":1,"mode":"Direct","later":true}"#,
        )
        .unwrap();
        let (state, how) = Store::new(dir.path()).load().unwrap();
        assert_eq!(how, Loaded::Read);
        assert_eq!(state.mode, Mode::Direct);
        assert!(state.subscriptions.is_empty() && state.selections.is_empty());
    }

    #[test]
    fn unreadable_or_newer_files_are_set_aside() {
        for text in [&b"{not json"[..], br#"{"stateVersion":2,"mode":"Direct"}"#] {
            let dir = TempDir::new("state-aside");
            fs::write(dir.path().join(FILE), text).unwrap();
            let (state, how) = Store::new(dir.path()).load().unwrap();
            assert_eq!(how, Loaded::SetAside);
            assert_eq!(state, State::default());
            assert_eq!(fs::read(dir.path().join(SET_ASIDE)).unwrap(), text);
            assert!(!dir.path().join(FILE).exists());
        }
    }
}
