use super::{FILES, MAX_BYTES, Pin, Release, Source, invalid};
use serde::Deserialize;
use std::io;

#[derive(Deserialize)]
struct Manifest {
    version: u32,
    release_date: String,
    files: Files,
}

#[derive(Deserialize)]
struct Files {
    #[serde(rename = "geoip.dat")]
    ip: File,
    #[serde(rename = "geosite.dat")]
    site: File,
}

#[derive(Deserialize)]
struct File {
    version: String,
    source: String,
    size_bytes: u64,
    sha256: String,
}

#[derive(Deserialize)]
struct GithubRelease {
    published_at: String,
    assets: Vec<Asset>,
}

#[derive(Deserialize)]
struct Asset {
    name: String,
    size: u64,
    digest: Option<String>,
}

#[derive(Deserialize)]
struct Branch {
    commit: Commit,
}

#[derive(Deserialize)]
struct Commit {
    sha: String,
}

impl Release {
    /// Read manifest v1 directly from the APK, not from a writable download directory.
    pub fn from_manifest(json: &[u8]) -> io::Result<Self> {
        let manifest: Manifest = serde_json::from_slice(json)?;
        let Files { ip, site } = manifest.files;
        if manifest.version != 1 || ip.version != site.version {
            return Err(invalid("inconsistent geo manifest"));
        }
        for (file, name) in [(&ip, FILES[0]), (&site, FILES[1])] {
            if file.source != Source::Upstream.url(&ip.version, name) {
                return Err(invalid("unexpected geo origin"));
            }
        }
        let pins = [
            pin(ip.size_bytes, &ip.sha256)?,
            pin(site.size_bytes, &site.sha256)?,
        ];
        Self::new(manifest.release_date, ip.version, pins)
    }

    /// Consume authenticated GitHub `releases/latest` and `branches/release` response bodies.
    pub fn from_github(release: &[u8], branch: &[u8]) -> io::Result<Self> {
        let release: GithubRelease = serde_json::from_slice(release)?;
        let branch: Branch = serde_json::from_slice(branch)?;
        let (date, time) = release.published_at.split_once('T').ok_or_else(bad_date)?;
        if !utc_time(time) {
            return Err(bad_date());
        }
        let [ip, site] = FILES.map(|name| github_pin(&release.assets, name));
        Self::new(date.to_owned(), branch.commit.sha, [ip?, site?])
    }

    fn new(date: String, commit: String, pins: [Pin; 2]) -> io::Result<Self> {
        if !lower_hex(&commit, 40) {
            return Err(invalid("invalid geo commit"));
        }
        if !valid_date(&date) {
            return Err(bad_date());
        }
        Ok(Self { date, commit, pins })
    }
}

fn github_pin(assets: &[Asset], name: &str) -> io::Result<Pin> {
    let mut found = assets.iter().filter(|a| a.name == name);
    let file = found.next().ok_or_else(|| invalid("missing geo asset"))?;
    if found.next().is_some() {
        return Err(invalid("duplicate geo asset"));
    }
    let digest = file
        .digest
        .as_deref()
        .and_then(|s| s.strip_prefix("sha256:"));
    pin(
        file.size,
        digest.ok_or_else(|| invalid("missing geo digest"))?,
    )
}

fn lower_hex(text: &str, len: usize) -> bool {
    text.len() == len
        && text
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn pin(size_bytes: u64, digest: &str) -> io::Result<Pin> {
    if !(1..=MAX_BYTES).contains(&size_bytes) || !lower_hex(digest, 64) {
        return Err(invalid("invalid geo pin"));
    }
    let sha256 =
        std::array::from_fn(|i| u8::from_str_radix(&digest[2 * i..2 * i + 2], 16).unwrap());
    Ok(Pin { size_bytes, sha256 })
}

fn bad_date() -> io::Error {
    invalid("invalid geo release date")
}

fn digits(s: &str, width: usize) -> Option<u16> {
    (s.len() == width && s.bytes().all(|b| b.is_ascii_digit())).then(|| s.parse().unwrap())
}

fn valid_date(s: &str) -> bool {
    let mut fields = s.splitn(3, '-');
    let mut next = |width| fields.next().and_then(|n| digits(n, width));
    let (Some(y), Some(m), Some(d)) = (next(4), next(2), next(2)) else {
        return false;
    };
    let end = match m {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if y.is_multiple_of(4) && (!y.is_multiple_of(100) || y.is_multiple_of(400)) => 29,
        2 => 28,
        _ => return false,
    };
    y != 0 && (1..=end).contains(&d)
}

fn utc_time(s: &str) -> bool {
    let Some(s) = s.strip_suffix('Z') else {
        return false;
    };
    let mut fields = s.split(':');
    [24, 60, 60].into_iter().all(|limit| {
        fields
            .next()
            .and_then(|n| digits(n, 2))
            .is_some_and(|v| v < limit)
    }) && fields.next().is_none()
}
