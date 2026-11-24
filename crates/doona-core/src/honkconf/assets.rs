//! Admission of raw DAT bytes to private staging. A staged pair is not an active policy:
//! activation also requires honk label checks and publication of both routers.

use boring::sha::Sha256;
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::path::Path;

mod metadata;
#[cfg(test)]
mod tests;

const REPO: &str = "MetaCubeX/meta-rules-dat";
const FILES: [&str; 2] = ["geoip.dat", "geosite.dat"];
const MAX_BYTES: u64 = 32 << 20;

/// Expected lengths and hashes come from the signed APK or the GitHub API, never a CDN.
pub struct Release {
    date: String,
    commit: String,
    pins: [Pin; 2],
}

struct Pin {
    size_bytes: u64,
    sha256: [u8; 32],
}

fn invalid(reason: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, reason)
}

impl Release {
    pub fn date(&self) -> &str {
        &self.date
    }

    pub fn commit(&self) -> &str {
        &self.commit
    }

    /// Verify extracted APK files against the manifest read from the APK. Never reuse a stage.
    pub fn stage_bundle(&self, extracted: &Path, stage: &Path) -> io::Result<()> {
        self.stage(stage, |name, pin, target, buf| {
            let source = File::open(extracted.join(name))?;
            copy_verified(source, target, pin, buf).map_err(Attempt::into_io)
        })
    }

    /// Fetch on a blocking worker. `open` must supply a successful HTTPS response body over
    /// the active proxy, with a deadline. Return `Interrupted` on disconnect to stop all retries.
    pub fn stage_update<R: Read>(
        &self,
        stage: &Path,
        mut open: impl FnMut(&str) -> io::Result<R>,
    ) -> io::Result<()> {
        self.stage(stage, |name, pin, target, buf| {
            let mut last = None;
            for source in [Source::Upstream, Source::MultiCdn, Source::Cloudflare] {
                let attempt = open(&source.url(&self.commit, name))
                    .map_err(Attempt::Source)
                    .and_then(|body| copy_verified(body, target, pin, buf));
                match attempt {
                    Ok(()) => return Ok(()),
                    Err(Attempt::Source(e)) if e.kind() != io::ErrorKind::Interrupted => {
                        last = Some(e);
                    }
                    Err(e) => return Err(e.into_io()),
                }
            }
            Err(last.expect("all three sources failed"))
        })
    }

    fn stage(
        &self,
        stage: &Path,
        mut copy: impl FnMut(&str, &Pin, &Path, &mut [u8]) -> io::Result<()>,
    ) -> io::Result<()> {
        fs::create_dir(stage)?;
        let mut buf = [0; 32 * 1024];
        let result = FILES
            .iter()
            .zip(&self.pins)
            .try_for_each(|(name, pin)| copy(name, pin, &stage.join(name), &mut buf));
        match result.and_then(|()| File::open(stage)?.sync_all()) {
            Ok(()) => Ok(()),
            Err(e) => {
                fs::remove_dir_all(stage)?;
                Err(e)
            }
        }
    }
}

enum Source {
    Upstream,
    MultiCdn,
    Cloudflare,
}

impl Source {
    fn url(&self, commit: &str, name: &str) -> String {
        let host = match self {
            Self::Upstream => {
                return format!("https://raw.githubusercontent.com/{REPO}/{commit}/{name}");
            }
            Self::MultiCdn => "cdn.jsdelivr.net",
            Self::Cloudflare => "testingcf.jsdelivr.net",
        };
        format!("https://{host}/gh/{REPO}@{commit}/{name}")
    }
}

enum Attempt {
    Source(io::Error),
    Disk(io::Error),
}

impl Attempt {
    fn into_io(self) -> io::Error {
        match self {
            Self::Source(e) | Self::Disk(e) => e,
        }
    }
}

fn copy_verified(
    source: impl Read,
    target: &Path,
    pin: &Pin,
    buf: &mut [u8],
) -> Result<(), Attempt> {
    let mut source = source.take(pin.size_bytes + 1);
    let mut remaining = pin.size_bytes;
    let mut file = File::create(target).map_err(Attempt::Disk)?;
    let mut hash = Sha256::new();
    loop {
        let n = source.read(buf).map_err(Attempt::Source)?;
        if n == 0 {
            break;
        }
        remaining = remaining
            .checked_sub(n as u64)
            .ok_or_else(|| Attempt::Source(invalid("geo size mismatch")))?;
        file.write_all(&buf[..n]).map_err(Attempt::Disk)?;
        hash.update(&buf[..n]);
    }
    if remaining != 0 {
        return Err(Attempt::Source(invalid("geo size mismatch")));
    }
    if hash.finish() != pin.sha256 {
        return Err(Attempt::Source(invalid("geo SHA-256 mismatch")));
    }
    file.sync_all().map_err(Attempt::Disk)
}
