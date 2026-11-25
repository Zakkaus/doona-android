//! The geo bundle: verified against the repository's pins before honk may read it.

use std::path::Path;

use anyhow::{Context, Result, ensure};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

const FILES: [&str; 2] = ["geoip.dat", "geosite.dat"];

#[derive(Deserialize)]
struct Pins {
    files: std::collections::BTreeMap<String, Pin>,
}

#[derive(Deserialize)]
struct Pin {
    version: String,
    size_bytes: u64,
    sha256: String,
}

#[derive(Serialize)]
pub struct Asset {
    pub name: &'static str,
    pub version: String,
    pub sha256: String,
}

/// Checks size and SHA-256 of both files against geo-pins.json and returns what was pinned.
pub fn verify(bundle: &Path, pins: &Path) -> Result<Vec<Asset>> {
    let text = std::fs::read_to_string(pins).with_context(|| pins.display().to_string())?;
    let mut pinned: Pins = serde_json::from_str(&text).context("geo-pins.json")?;
    let mut assets = Vec::new();
    for name in FILES {
        let pin = pinned
            .files
            .remove(name)
            .with_context(|| format!("{name}: not pinned"))?;
        let path = bundle.join(name);
        let bytes = std::fs::read(&path).with_context(|| path.display().to_string())?;
        ensure!(
            bytes.len() as u64 == pin.size_bytes,
            "{name}: size {} is not the pinned {}",
            bytes.len(),
            pin.size_bytes
        );
        let digest: String = Sha256::digest(&bytes)
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect();
        ensure!(
            digest == pin.sha256,
            "{name}: SHA-256 {digest} is not the pinned {}",
            pin.sha256
        );
        assets.push(Asset {
            name,
            version: pin.version,
            sha256: digest,
        });
    }
    Ok(assets)
}
