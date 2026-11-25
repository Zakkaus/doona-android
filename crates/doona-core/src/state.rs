//! `state.json` in the app's data directory: the mode, the rule source, the subscriptions and the
//! selections. The core owns this file (Kotlin's DataStore holds only the per-app policy).
//! Writes replace it atomically: a temp file is written and synced, then renamed over the old one.

use crate::dto::{ErrorCode, Mode, RuleSourceSpec, SubscriptionFormat};
use crate::rules::template;
use crate::subscription::fetch;
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
    #[serde(
        default,
        deserialize_with = "dns_upstreams",
        skip_serializing_if = "Option::is_none"
    )]
    pub dns_upstreams: Option<Vec<std::net::SocketAddr>>,
    /// Where Rule mode's rules come from; the template when absent or unreadable.
    #[serde(default, deserialize_with = "rule_source")]
    pub rule_source: RuleSourceSpec,
    #[serde(default)]
    pub subscriptions: Vec<StoredSubscription>,
    #[serde(default)]
    pub static_nodes: Vec<crate::dto::StaticNode>,
    /// Group id to the member id chosen by hand.
    #[serde(default)]
    pub selections: BTreeMap<String, String>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub tcp_selections: BTreeMap<String, String>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub udp_selections: BTreeMap<String, String>,
    /// The number in the next subscription's id; an id is never given twice.
    #[serde(default)]
    pub next_subscription: u64,
}

impl State {
    pub(crate) fn pin(&self, group: &str, network: crate::dto::Network) -> Option<&String> {
        let overrides = match network {
            crate::dto::Network::Tcp => &self.tcp_selections,
            crate::dto::Network::Udp => &self.udp_selections,
        };
        overrides.get(group).or_else(|| self.selections.get(group))
    }

    /// A new subscription id: `s` and a number above every id given before.
    pub fn take_subscription_id(&mut self) -> String {
        let used = self
            .subscriptions
            .iter()
            .filter_map(|s| s.id.strip_prefix('s')?.parse::<u64>().ok())
            .max()
            .unwrap_or(0);
        let n = self.next_subscription.max(used + 1);
        self.next_subscription = n + 1;
        format!("s{n}")
    }
}

fn dns_upstreams<'de, D: serde::Deserializer<'de>>(
    d: D,
) -> Result<Option<Vec<std::net::SocketAddr>>, D::Error> {
    let value = serde_json::Value::deserialize(d)?;
    // An empty list cannot be valid. Keep it distinct from a missing field until Store::load reports the reset.
    let servers = serde_json::from_value::<Vec<String>>(value)
        .ok()
        .and_then(|servers| crate::dns::parse_upstreams(&servers));
    Ok(Some(servers.unwrap_or_default()))
}

/// A stored User-Agent. One the commands would refuse, which only a hand-edited file holds, reads
/// as none, and the rest of the file stays usable.
fn user_agent<'de, D: serde::Deserializer<'de>>(d: D) -> Result<Option<String>, D::Error> {
    let value = serde_json::Value::deserialize(d)?;
    Ok(value.as_str().and_then(|a| fetch::user_agent(a).ok()))
}

/// A stored rule source. One this core cannot use, such as a later core's kind or template, reads
/// as the default, and the rest of the file stays usable.
fn rule_source<'de, D: serde::Deserializer<'de>>(d: D) -> Result<RuleSourceSpec, D::Error> {
    let value = serde_json::Value::deserialize(d)?;
    let spec = serde_json::from_value(value).ok();
    Ok(spec
        .filter(|RuleSourceSpec::Template { template, .. }| template == template::ID)
        .unwrap_or_default())
}

impl Default for State {
    fn default() -> State {
        State {
            state_version: STATE_VERSION,
            mode: Mode::Global,
            dns_upstreams: None,
            rule_source: RuleSourceSpec::default(),
            subscriptions: Vec::new(),
            static_nodes: Vec::new(),
            selections: BTreeMap::new(),
            tcp_selections: BTreeMap::new(),
            udp_selections: BTreeMap::new(),
            next_subscription: 1,
        }
    }
}

/// A subscription as stored. It is stored only after a successful fetch, so it always has a format
/// and a fetch time. Its nodes are parsed at load from `document` when it has one, else from
/// `links`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StoredSubscription {
    pub id: String,
    pub name: String,
    pub url: String,
    /// The format of the last successful fetch.
    pub format: SubscriptionFormat,
    /// When the last successful fetch ended.
    pub updated_at_epoch_ms: i64,
    pub used_bytes: Option<i64>,
    pub total_bytes: Option<i64>,
    pub expires_at_epoch_ms: Option<i64>,
    /// The code of the last failed update; a successful one clears it.
    pub error: Option<ErrorCode>,
    /// The share links of its nodes as last fetched, one per node, in feed order.
    pub links: Vec<String>,
    /// For a structured body (Clash, sing-box or SIP008), the entries of its nodes as last
    /// fetched: a YAML document of the entries alone, under the body's own key.
    pub document: Option<String>,
    /// The User-Agent its fetches send instead of the default, valid as `fetch::user_agent` takes
    /// it.
    #[serde(deserialize_with = "user_agent")]
    pub user_agent: Option<String>,
}

/// How [`Store::load`] found the file.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Loaded {
    Read,
    DnsReset,
    Missing,
    /// Unparsable, or written by a newer layout: set aside and replaced by defaults.
    SetAside,
}

pub struct Store {
    dir: PathBuf,
    /// Syncs the directory after a rename; a unit test stands in for a slow disk.
    sync_dir: fn(&Path) -> io::Result<()>,
}

impl Store {
    pub fn new(dir: &Path) -> Store {
        Store {
            dir: dir.to_path_buf(),
            sync_dir,
        }
    }

    /// The app's data directory, which holds the file.
    pub fn dir(&self) -> &Path {
        &self.dir
    }

    #[cfg(test)]
    pub(crate) fn with_dir_sync(dir: &Path, sync_dir: fn(&Path) -> io::Result<()>) -> Store {
        Store {
            dir: dir.to_path_buf(),
            sync_dir,
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
            Ok(mut s) if s.state_version == STATE_VERSION => {
                let loaded = if s.dns_upstreams.take_if(|v| v.is_empty()).is_some() {
                    Loaded::DnsReset
                } else {
                    Loaded::Read
                };
                Ok((s, loaded))
            }
            _ => {
                fs::rename(&path, self.dir.join(SET_ASIDE))?;
                Ok((State::default(), Loaded::SetAside))
            }
        }
    }

    /// Writes `state` to a synced temporary file and renames it over the state file. The
    /// rename is the commit: once it succeeds, a restart reads the new state. [`Store::sync`]
    /// then makes the rename durable. `proceed` runs once `state` is serialized, right before
    /// the first write to the file system; when it answers false nothing is written and the
    /// error is `TimedOut`.
    pub fn commit(&self, state: &State, proceed: impl FnOnce() -> bool) -> io::Result<()> {
        let bytes = serde_json::to_vec_pretty(state).map_err(io::Error::other)?;
        if !proceed() {
            return Err(ErrorKind::TimedOut.into());
        }
        let temp = self.dir.join(TEMP);
        let committed =
            write_synced(&temp, &bytes).and_then(|()| fs::rename(&temp, self.dir.join(FILE)));
        if committed.is_err() {
            let _ = fs::remove_file(&temp);
        }
        committed
    }

    /// Syncs the directory after a [`Store::commit`]. A failure is only logged: the state is
    /// committed already.
    pub fn sync(&self) {
        if let Err(e) = (self.sync_dir)(&self.dir) {
            log::warn!("state: directory not synced ({:?})", e.kind());
        }
    }
}

pub(crate) fn sync_dir(dir: &Path) -> io::Result<()> {
    File::open(dir)?.sync_all()
}

pub(crate) fn write_synced(path: &Path, bytes: &[u8]) -> io::Result<()> {
    use std::os::unix::fs::OpenOptionsExt;
    let mut f = File::options()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(path)?;
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
        store.commit(&state, || true).unwrap();
        store.sync();
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
    fn subscription_ids_are_never_given_twice() {
        let mut state = State::default();
        let first = state.take_subscription_id();
        assert_eq!(first, "s1");
        assert_eq!(state.take_subscription_id(), "s2");
        // A counter behind the ids in the file, as a hand-edited one may be, still avoids them.
        let mut edited: State = serde_json::from_str(
            r#"{"stateVersion":1,"mode":"Global","nextSubscription":1,"subscriptions":[{"id":"s7","name":"a","url":"https://a.invalid/","format":"clash","updatedAtEpochMs":5,"usedBytes":null,"totalBytes":null,"expiresAtEpochMs":null,"error":null,"links":[],"document":null,"userAgent":null}]}"#,
        )
        .unwrap();
        assert_eq!(edited.take_subscription_id(), "s8");
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

    #[test]
    fn a_rule_source_this_core_cannot_use_reads_as_the_template_and_keeps_the_rest() {
        let sub = r#"{"id":"s1","name":"a","url":"https://a.invalid/","format":"clash",
            "updatedAtEpochMs":5,"usedBytes":null,"totalBytes":null,"expiresAtEpochMs":null,
            "error":null,"links":[],"document":null,"userAgent":null}"#;
        for source in [
            "",
            r#""ruleSource":{"kind":"subscription","subscriptionId":"s1","options":{}},"#,
            r#""ruleSource":{"kind":"template","template":"later","options":{"blockQuic":true}},"#,
            r#""ruleSource":7,"#,
        ] {
            let dir = TempDir::new("state-rule-source");
            let text =
                format!(r#"{{"stateVersion":1,"mode":"Rule",{source}"subscriptions":[{sub}]}}"#);
            fs::write(dir.path().join(FILE), text).unwrap();
            let (state, how) = Store::new(dir.path()).load().unwrap();
            assert_eq!(how, Loaded::Read, "{source}");
            assert_eq!(state.rule_source, RuleSourceSpec::default(), "{source}");
            assert_eq!((state.mode, state.subscriptions.len()), (Mode::Rule, 1));
        }
    }

    #[test]
    fn a_rule_source_round_trips() {
        let dir = TempDir::new("state-rule-source-kept");
        let store = Store::new(dir.path());
        let state = State {
            rule_source: RuleSourceSpec::Template {
                template: template::ID.to_owned(),
                options: crate::dto::RuleOptions { block_quic: true },
            },
            ..State::default()
        };
        store.commit(&state, || true).unwrap();
        assert_eq!(store.load().unwrap(), (state, Loaded::Read));
    }

    #[test]
    fn a_user_agent_round_trips_and_is_null_without_one() {
        let dir = TempDir::new("state-user-agent");
        let store = Store::new(dir.path());
        let sub = |id: &str, user_agent: Option<&str>| StoredSubscription {
            id: id.to_owned(),
            name: id.to_owned(),
            url: format!("https://{id}.invalid/"),
            format: SubscriptionFormat::Clash,
            updated_at_epoch_ms: 5,
            used_bytes: None,
            total_bytes: None,
            expires_at_epoch_ms: None,
            error: None,
            links: Vec::new(),
            document: None,
            user_agent: user_agent.map(str::to_owned),
        };
        let state = State {
            subscriptions: vec![sub("s1", Some("mihomo/1.19.16")), sub("s2", None)],
            ..State::default()
        };
        store.commit(&state, || true).unwrap();
        assert_eq!(store.load().unwrap(), (state, Loaded::Read));
        let file: serde_json::Value =
            serde_json::from_slice(&fs::read(dir.path().join(FILE)).unwrap()).unwrap();
        assert_eq!(file["stateVersion"], STATE_VERSION);
        assert_eq!(file["subscriptions"][0]["userAgent"], "mihomo/1.19.16");
        assert_eq!(
            file["subscriptions"][1]["userAgent"],
            serde_json::Value::Null
        );
    }

    #[test]
    fn a_user_agent_the_commands_would_refuse_reads_as_none_and_keeps_the_rest() {
        let long = format!(r#""{}""#, "x".repeat(257));
        for agent in [
            r#""a\r\nX-Injected: 1""#,
            r#""""#,
            r#""   ""#,
            r#""caf\u00e9""#,
            long.as_str(),
            "7",
            "null",
        ] {
            let dir = TempDir::new("state-user-agent-unusable");
            let text = format!(
                r#"{{"stateVersion":1,"mode":"Direct","subscriptions":[{{"id":"s1","name":"a",
                    "url":"https://a.invalid/","format":"clash","updatedAtEpochMs":5,
                    "usedBytes":null,"totalBytes":null,"expiresAtEpochMs":null,"error":null,
                    "links":[],"document":null,"userAgent":{agent}}}]}}"#
            );
            fs::write(dir.path().join(FILE), text).unwrap();
            let (state, how) = Store::new(dir.path()).load().unwrap();
            assert_eq!(how, Loaded::Read, "{agent}");
            assert_eq!(state.mode, Mode::Direct);
            let s1 = &state.subscriptions[0];
            assert_eq!(
                (s1.user_agent.as_deref(), s1.updated_at_epoch_ms),
                (None, 5)
            );
        }
    }

    #[test]
    fn a_subscription_without_a_fetch_is_not_a_stored_one_and_the_file_is_set_aside() {
        let dir = TempDir::new("state-unfetched");
        let text = br#"{"stateVersion":1,"mode":"Direct","subscriptions":[{"id":"s1","name":"a",
            "url":"https://a.invalid/","format":null,"updatedAtEpochMs":null,"usedBytes":null,
            "totalBytes":null,"expiresAtEpochMs":null,"error":null,"links":[],"document":null,
            "userAgent":null}]}"#;
        fs::write(dir.path().join(FILE), text).unwrap();
        let (state, how) = Store::new(dir.path()).load().unwrap();
        assert_eq!((how, state), (Loaded::SetAside, State::default()));
    }
}
