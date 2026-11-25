//! The corpus: cases, their fixed health and the connections to route.

use std::net::IpAddr;
use std::path::Path;

use anyhow::{Context, Result, ensure};
use serde::Deserialize;

pub const CORPUS_VERSION: u64 = 1;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Corpus {
    pub version: u64,
    pub cases: Vec<Case>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Case {
    pub name: String,
    /// Path of the `.dae` entry file, relative to the corpus directory.
    pub config: String,
    /// Fixed health per node name, applied before any selection.
    #[serde(default)]
    pub health: Vec<Health>,
    /// Selector choices made through the group runtime, in order.
    #[serde(default)]
    pub select: Vec<Choice>,
    pub probes: Vec<Probe>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Health {
    pub node: String,
    /// The health domain the entry applies to; a UDP probe reads the UDP one.
    #[serde(default)]
    pub network: Protocol,
    pub alive: bool,
    /// Probe latency in milliseconds, recorded for an alive node.
    pub latency_ms: Option<u64>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Choice {
    pub group: String,
    pub member: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Probe {
    pub name: String,
    pub domain: Option<String>,
    pub dst_ip: IpAddr,
    pub dst_port: u16,
    pub protocol: Protocol,
}

#[derive(Clone, Copy, Default, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Protocol {
    #[default]
    Tcp,
    Udp,
}

impl Protocol {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Tcp => "tcp",
            Self::Udp => "udp",
        }
    }
}

pub fn load(dir: &Path) -> Result<Corpus> {
    let path = dir.join("corpus.json");
    let text = std::fs::read_to_string(&path).with_context(|| path.display().to_string())?;
    let corpus: Corpus = serde_json::from_str(&text).with_context(|| path.display().to_string())?;
    ensure!(
        corpus.version == CORPUS_VERSION,
        "corpus version {} is not {CORPUS_VERSION}",
        corpus.version
    );
    Ok(corpus)
}
