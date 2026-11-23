//! Where a panic happened, as a crash file states it (`crash.rs`, ARCH D21): a file of this
//! crate, or a dependency's name and version, and the module, never a path of the machine that
//! built it.

use std::fmt::{self, Write as _};

/// Where a panic happened, by its source file.
#[derive(Debug, PartialEq, Eq)]
pub(super) enum Place<'a> {
    /// This crate: its file from the crate's directory on (`src/api/rules.rs`) and the module
    /// (`api/rules`, empty for the crate's root).
    Ours {
        file: &'a str,
        module: &'a str,
    },
    /// A dependency or the standard library: its source directory (`tokio-1.47.1`,
    /// `honk-outbound`, `core`) and the module.
    Theirs {
        dir: &'a str,
        module: &'a str,
    },
    Unknown,
}

/// `src/…` for this crate built in its own directory, else the directory before the last
/// `/src/`: the crate's, whether under cargo's registry or git checkouts, rustc's `library`, or a
/// workspace. Anything else, as a file generated in a build directory, is unknown.
pub(super) fn place(file: &str) -> Place<'_> {
    let (dir, file, rest) = match file.strip_prefix("src/") {
        Some(rest) => (env!("CARGO_PKG_NAME"), file, rest),
        None => match file.rfind("/src/") {
            Some(i) => {
                let start = file[..i].rfind('/').map_or(0, |j| j + 1);
                (&file[start..i], &file[i + 1..], &file[i + 5..])
            }
            None => return Place::Unknown,
        },
    };
    let module = rest.strip_suffix(".rs").map(|m| {
        let m = m.strip_suffix("/mod").unwrap_or(m);
        if matches!(m, "lib" | "main" | "mod") {
            ""
        } else {
            m
        }
    });
    let plain = |text: &str, more: &str| {
        text.chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || more.contains(c))
    };
    match module {
        Some(module) if !dir.is_empty() && plain(dir, "-.+") && plain(module, "/") => {
            if dir == env!("CARGO_PKG_NAME") {
                Place::Ours { file, module }
            } else {
                Place::Theirs { dir, module }
            }
        }
        _ => Place::Unknown,
    }
}

/// A source directory's crate name and version: `tokio-1.47.1` is `tokio` and `1.47.1`,
/// `sha-1-0.10.6` is `sha-1` and `0.10.6`; a git checkout's or the standard library's has no
/// version.
pub(super) fn version_of(dir: &str) -> (&str, Option<&str>) {
    let numeric = |p: &str| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit());
    for (i, _) in dir.match_indices('-') {
        let version = &dir[i + 1..];
        let mut parts = version.splitn(3, '.');
        let (major, minor) = (parts.next().unwrap_or(""), parts.next().unwrap_or(""));
        let patch = parts.next().unwrap_or("");
        if numeric(major) && numeric(minor) && patch.starts_with(|c: char| c.is_ascii_digit()) {
            return (&dir[..i], Some(version));
        }
    }
    (dir, None)
}

/// A place's module as Rust names it: `doonacore::api::rules`, `tokio::runtime::park`.
pub(super) struct Module<'a, 'p>(pub &'a Place<'p>);

impl fmt::Display for Module<'_, '_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let (krate, module) = match self.0 {
            Place::Ours { module, .. } => (env!("CARGO_CRATE_NAME"), *module),
            Place::Theirs { dir, module } => (version_of(dir).0, *module),
            Place::Unknown => return f.write_str("unknown"),
        };
        for c in krate.chars() {
            f.write_char(if c == '-' { '_' } else { c })?;
        }
        for part in module.split('/').filter(|p| !p.is_empty()) {
            write!(f, "::{part}")?;
        }
        Ok(())
    }
}
