use std::collections::{HashMap, HashSet};
use std::fs::{self, File};
use std::io::{self, Read};
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;

use honk_config::Config;
use honk_config::diagnostic::{DiagnosticSources, SettingPath, SourceRef, finish_attempt};
use honk_config::error::{DetailedConfigError, ErrorCategory};
use honk_config::parser::SourceLimits;
use honk_config::parser::cursor::{Document, Root};
use honk_config::parser::lexer::Source;
use honk_config::parser::source_edit::source_indices;

use super::declarations::{self, location, record};
use super::{HonkConfig, HonkConfigError, validate};

// honk's bounded editor budget: room for multi-file rule lists without an unlimited VPN heap.
pub(super) const LIMITS: SourceLimits = SourceLimits::DEFAULT;

type Files = HashMap<PathBuf, Arc<str>>;

fn failure(cause: DetailedConfigError) -> HonkConfigError {
    let diagnostics = vec![(*cause.diagnostic).clone()];
    HonkConfigError::ParseFailed { cause, diagnostics }
}

fn io_error(e: io::Error, source: &SourceRef) -> HonkConfigError {
    failure(DetailedConfigError::from_legacy(e.into(), source.clone()))
}

fn invalid(source: &SourceRef, code: &'static str, message: &'static str) -> HonkConfigError {
    failure(DetailedConfigError::new(
        ErrorCategory::Include,
        code,
        source.clone(),
        SettingPath::new("config"),
        message,
    ))
}

struct Tree {
    files: Files,
    links: HashSet<PathBuf>,
}

impl Tree {
    fn read(entry: &Path, root: &Path, source: &SourceRef) -> Result<Self, HonkConfigError> {
        let mut files = Files::new();
        let mut links = HashSet::new();
        let mut bytes = LIMITS.max_bytes;
        let mut dirs = vec![root.to_owned()];
        while let Some(dir) = dirs.pop() {
            for item in fs::read_dir(dir).map_err(|e| io_error(e, source))? {
                let item = item.map_err(|e| io_error(e, source))?;
                let path = item.path();
                let kind = item.file_type().map_err(|e| io_error(e, source))?;
                if kind.is_symlink() && path != entry {
                    links.insert(path);
                } else if kind.is_dir() && path != entry {
                    dirs.push(path);
                } else if path.extension().is_some_and(|ext| ext == "dae") {
                    if files.len() == LIMITS.max_sources {
                        return Err(HonkConfigError::SourceLimit);
                    }
                    let reference = DiagnosticSources::new(Some(path.clone())).root();
                    if !kind.is_file() {
                        return Err(invalid(
                            &reference,
                            "invalid-config-source",
                            "configuration source must be a regular file",
                        ));
                    }
                    let text = read_source(&path, &mut bytes, &reference)?;
                    files.insert(path, text);
                }
            }
        }
        if !files.contains_key(entry) {
            return Err(io_error(io::ErrorKind::NotFound.into(), source));
        }
        Ok(Self { files, links })
    }
}

fn read_source(
    path: &Path,
    remaining: &mut usize,
    source: &SourceRef,
) -> Result<Arc<str>, HonkConfigError> {
    let file = File::open(path).map_err(|e| io_error(e, source))?;
    let size = file.metadata().map_err(|e| io_error(e, source))?.len();
    if size > *remaining as u64 {
        return Err(HonkConfigError::SourceLimit);
    }
    let mut bytes = Vec::with_capacity(size as usize);
    file.take(*remaining as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| io_error(e, source))?;
    *remaining = remaining
        .checked_sub(bytes.len())
        .ok_or(HonkConfigError::SourceLimit)?;
    String::from_utf8(bytes).map(Arc::from).map_err(|_| {
        invalid(
            source,
            "invalid-config-encoding",
            "configuration source must be UTF-8",
        )
    })
}

/// Check components before normalization so `link/../name` cannot bypass link refusal.
pub(super) fn confined(root: &Path, path: &Path, links: &HashSet<PathBuf>) -> Option<PathBuf> {
    let relative = if path.is_absolute() {
        path.strip_prefix(root).ok()?
    } else {
        path
    };
    let mut current = root.to_owned();
    for part in relative.components() {
        match part {
            Component::ParentDir if current != root => {
                current.pop();
            }
            Component::CurDir => (),
            Component::Normal(name) => {
                current.push(name);
                if links.contains(&current) {
                    return None;
                }
            }
            _ => return None,
        }
    }
    Some(current)
}

struct Prepared<'a> {
    doc: Document<'a>,
    escapes: Vec<Escape>,
}

struct Escape {
    span: std::ops::Range<usize>,
    blank: bool,
}

// A glob can reach a link that isn't literally named by a component. Never follow
// it or implement another matcher. Retain honk's safe matches, but don't certify
// coverage if an excluded link shares the pattern's literal prefix.
fn may_reach_link(path: &Path, links: &HashSet<PathBuf>) -> bool {
    let bytes = path.as_os_str().as_encoded_bytes();
    bytes
        .iter()
        .position(|b| matches!(b, b'*' | b'?' | b'['))
        .is_some_and(|end| {
            links.iter().any(|link| {
                link.as_os_str()
                    .as_encoded_bytes()
                    .starts_with(&bytes[..end])
            })
        })
}

// Honk exposes include expansion but not its pattern spans. Match the byte boundaries
// of parse_include_body: quoting matters only at a pattern start, not after `,` or `:`.
fn escapes(doc: &Document<'_>, root: &Path, links: &HashSet<PathBuf>) -> Vec<Escape> {
    let mut escaped = Vec::new();
    for section in doc.sections().filter(|s| s.header() == "include") {
        for entry in section
            .body()
            .into_iter()
            .flatten()
            .filter(|s| s.body().is_none())
        {
            let mut text = entry.header();
            while !text.is_empty() {
                text = text.trim_ascii_start();
                let Some((pattern, len)) = pattern_prefix(text) else {
                    break;
                };
                let path = confined(root, Path::new(pattern), links);
                if path.as_ref().is_none_or(|p| may_reach_link(p, links)) {
                    let start = entry.header_span().end - text.len();
                    escaped.push(Escape {
                        span: start..start + len,
                        blank: path.is_none(),
                    });
                }
                text = &text[len..];
            }
        }
    }
    escaped
}

fn pattern_prefix(text: &str) -> Option<(&str, usize)> {
    let quote = *text.as_bytes().first()?;
    if matches!(quote, b'\'' | b'"') {
        let mut bytes = text.bytes().enumerate().skip(1);
        while let Some((i, b)) = bytes.next() {
            if b == quote {
                return Some((&text[1..i], i + 1));
            }
            if b == b'\\' {
                bytes.next();
            }
        }
        None
    } else {
        let end = text
            .bytes()
            .position(|b| b.is_ascii_whitespace() || matches!(b, b'{' | b'}'))
            .unwrap_or(text.len());
        (end != 0).then(|| (&text[..end], end))
    }
}

pub(super) fn load(path: &Path) -> Result<HonkConfig, HonkConfigError> {
    let Some(name) = path
        .file_name()
        .filter(|_| path.extension().is_some_and(|ext| ext == "dae"))
    else {
        return Err(HonkConfigError::NonNativeSource);
    };
    let reference = DiagnosticSources::new(Some(path.into())).root();
    let root = path
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."))
        .canonicalize()
        .map_err(|e| io_error(e, &reference))?;
    let entry = root.join(name);
    let tree = Tree::read(&entry, &root, &reference)?;
    let (overlay, prepared) = prepare(&tree.files, &entry, &root, &tree.links, &reference)?;
    admit(&entry, &root, &tree, &overlay, prepared)
}

type Documents<'a> = HashMap<&'a Path, Prepared<'a>>;

fn prepare<'a>(
    inputs: &'a Files,
    entry: &Path,
    root: &Path,
    links: &HashSet<PathBuf>,
    reference: &SourceRef,
) -> Result<(Files, Documents<'a>), HonkConfigError> {
    let mut overlay = Files::new();
    let mut prepared = HashMap::new();
    for (label, text) in inputs {
        let parsed = Document::parse(Source::new(text, reference.clone()), &mut Vec::new());
        if label == entry
            && structured(text)
            && !parsed
                .as_ref()
                .is_ok_and(|doc| doc.sections().any(|s| Root::parse(s.header()).is_some()))
        {
            return Err(HonkConfigError::NonNativeSource);
        }
        let mut input = text.clone();
        if let Ok(doc) = parsed {
            let escapes = escapes(&doc, root, links);
            if escapes.iter().any(|e| e.blank) {
                let mut masked = String::with_capacity(text.len());
                let mut end = 0;
                for escape in escapes.iter().filter(|e| e.blank) {
                    masked.push_str(&text[end..escape.span.start]);
                    masked.extend(std::iter::repeat_n(' ', escape.span.len()));
                    end = escape.span.end;
                }
                masked.push_str(&text[end..]);
                input = masked.into();
            }
            prepared.insert(label.as_path(), Prepared { doc, escapes });
        }
        overlay.insert(label.clone(), input);
    }
    Ok((overlay, prepared))
}

fn admit(
    entry: &Path,
    root: &Path,
    tree: &Tree,
    overlay: &Files,
    mut prepared: Documents<'_>,
) -> Result<HonkConfig, HonkConfigError> {
    let mut diagnostics = Vec::new();
    let mut loaded = Config::from_dae_sources_in_memory(entry, overlay, LIMITS, &mut diagnostics)
        .map_err(|cause| HonkConfigError::ParseFailed {
        cause,
        diagnostics: std::mem::take(&mut diagnostics),
    })?;
    let source = loaded.sources[0].source.clone();
    loaded
        .config
        .append_diagnostics(source.clone(), &mut diagnostics);
    let report = validate(&loaded.config, &source).and_then(|()| {
        let (_, index) = source_indices(&loaded.sources).map_err(|_| {
            DetailedConfigError::new(
                ErrorCategory::Parse,
                "source-index-failed",
                source.clone(),
                SettingPath::new("config"),
                "cannot locate accepted rules",
            )
        })?;
        let mut declarations = declarations::Declarations::default();
        let mut skipped = Vec::new();
        for (i, file) in loaded.sources.iter_mut().enumerate() {
            if let Some(Prepared { doc, escapes }) = prepared.remove(file.path.as_path()) {
                for Escape { span, .. } in escapes {
                    let loc = location(&doc, doc.source().span(span.start, span.end), i);
                    skipped.push(record(
                        "include",
                        format!("pattern@{}", span.start),
                        "include_escape",
                        vec![loc],
                    ));
                }
                declarations.collect(
                    &doc,
                    i,
                    &declarations::Context {
                        config: &loaded.config,
                        root,
                        links: &tree.links,
                        diagnostics: &diagnostics,
                    },
                );
            }
            if let Some(original) = tree.files.get(&file.path) {
                file.content = original.clone();
            }
        }
        declarations.finish(&loaded.config, &index, &mut skipped);
        Ok(skipped)
    });
    let unsupported =
        finish_attempt(report, &mut diagnostics).map_err(|cause| HonkConfigError::ParseFailed {
            cause,
            diagnostics: std::mem::take(&mut diagnostics),
        })?;
    Ok(HonkConfig {
        loaded,
        diagnostics,
        unsupported,
    })
}

fn structured(text: &str) -> bool {
    let first = text
        .strip_prefix('\u{feff}')
        .unwrap_or(text)
        .lines()
        .map(str::trim)
        .find(|s| !s.is_empty() && !s.starts_with('#'))
        .unwrap_or("");
    if first.starts_with(['{', '[', '%', '!', '&']) || first.starts_with("---") {
        return true;
    }
    let head = first.split_once('{').map_or(first, |(head, _)| head);
    head.contains('=')
        || head.trim_end().ends_with(':')
        || head
            .split_once(':')
            .is_some_and(|(_, value)| value.starts_with(char::is_whitespace))
}
