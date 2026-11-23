use std::collections::{HashMap, HashSet};
use std::fs::{self, File};
use std::io::{self, Read};
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;

use honk_config::Config;
use honk_config::diagnostic::{DiagnosticSources, SettingPath, SourceRef, finish_attempt};
use honk_config::error::{DetailedConfigError, ErrorCategory};
use honk_config::parser::cursor::{Document, Root};
use honk_config::parser::lexer::Source;
use honk_config::parser::source_edit::source_indices;
use honk_config::parser::{LoadedConfig, SourceLimits};

use super::declarations::{self, location, record};
use super::{HonkConfig, HonkConfigError, StoreError, validate};

// honk's bounded editor budget: room for multi-file rule lists without an unlimited VPN heap.
pub(super) const LIMITS: SourceLimits = SourceLimits::DEFAULT;

pub(super) type Files = HashMap<PathBuf, Arc<str>>;

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

pub(super) struct Tree {
    pub entry: PathBuf,
    pub files: Files,
    pub links: HashSet<PathBuf>,
}

impl Tree {
    pub(super) fn read(path: &Path) -> Result<Self, HonkConfigError> {
        if path.extension().is_none_or(|ext| ext != "dae") {
            return Err(HonkConfigError::NonNativeSource);
        }
        let source = DiagnosticSources::new(Some(path.into())).root();
        let root = path
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .unwrap_or(Path::new("."))
            .canonicalize()
            .map_err(|e| io_error(e, &source))?;
        let entry = root.join(path.file_name().unwrap());
        let mut files = Files::new();
        let mut links = HashSet::new();
        let mut bytes = LIMITS.max_bytes;
        let mut dirs = vec![root];
        while let Some(dir) = dirs.pop() {
            for item in fs::read_dir(dir).map_err(|e| io_error(e, &source))? {
                let item = item.map_err(|e| io_error(e, &source))?;
                let path = item.path();
                let kind = item.file_type().map_err(|e| io_error(e, &source))?;
                if path != entry {
                    if kind.is_symlink() {
                        links.insert(path);
                        continue;
                    }
                    if kind.is_dir() {
                        dirs.push(path);
                        continue;
                    }
                }
                if path.extension().is_none_or(|ext| ext != "dae") {
                    continue;
                }
                let text = read_source(&path, &mut bytes, files.len(), kind.is_file())?;
                files.insert(path, text);
            }
        }
        if !files.contains_key(&entry) {
            return Err(io_error(io::ErrorKind::NotFound.into(), &source));
        }
        Ok(Self {
            entry,
            files,
            links,
        })
    }

    fn parse(&self) -> Result<Parsed<'_>, HonkConfigError> {
        let root = self.entry.parent().unwrap();
        let reference = DiagnosticSources::new(Some(self.entry.clone())).root();
        let (overlay, prepared) = prepare(&self.files, &self.entry, root, &self.links, &reference)?;
        let mut diagnostics = Vec::new();
        let loaded =
            Config::from_dae_sources_in_memory(&self.entry, &overlay, LIMITS, &mut diagnostics)
                .map_err(|cause| HonkConfigError::ParseFailed {
                    cause,
                    diagnostics: std::mem::take(&mut diagnostics),
                })?;
        Ok(Parsed {
            loaded,
            diagnostics,
            prepared,
        })
    }

    pub(super) fn form_sources(
        &self,
    ) -> Result<
        (
            LoadedConfig,
            Vec<honk_config::diagnostic::DetailedDiagnostic>,
        ),
        StoreError,
    > {
        if self.files.values().map(|s| s.len()).sum::<usize>() > LIMITS.max_bytes {
            return Err(HonkConfigError::SourceLimit.into());
        }
        let parsed = self.parse()?;
        if parsed.loaded.sources.iter().any(|s| {
            parsed
                .prepared
                .get(s.path.as_path())
                .is_some_and(|p| !p.escapes.is_empty())
        }) {
            return Err(StoreError::Incomplete);
        }
        self.read_companions(&parsed.loaded.config, None)?;
        Ok((parsed.loaded, parsed.diagnostics))
    }

    /// Without a parse, import keeps only `.dae` files. After repair, re-import to pick up hosts files.
    pub(super) fn capture_companions(
        &mut self,
        edit: Option<(&Path, &str)>,
    ) -> Result<(), StoreError> {
        if let Ok(parsed) = self.parse() {
            if !self.links.is_empty() {
                let includes = parsed
                    .loaded
                    .sources
                    .iter()
                    .filter_map(|s| parsed.prepared.get(s.path.as_path()))
                    .flat_map(|p| &p.escapes)
                    .filter_map(|e| e.link.as_deref());
                let hosts = parsed.loaded.config.dns.hosts.iter().filter_map(|p| {
                    confined(self.entry.parent().unwrap(), Path::new(p), &self.links)
                        .err()
                        .flatten()
                });
                if let Some(link) = includes.chain(hosts).next() {
                    return Err(StoreError::ImportSymlink(link.into()));
                }
            }
            let companions = self.read_companions(&parsed.loaded.config, edit)?;
            self.files.extend(companions);
        }
        Ok(())
    }

    pub(super) fn load(&self) -> Result<(HonkConfig, Files), HonkConfigError> {
        let parsed = self.parse()?;
        let companions = self.read_companions(&parsed.loaded.config, None)?;
        let config = admit(self, &companions, parsed)?;
        Ok((config, companions))
    }

    fn read_companions(
        &self,
        config: &Config,
        edit: Option<(&Path, &str)>,
    ) -> Result<Files, HonkConfigError> {
        let root = self.entry.parent().unwrap();
        let mut companions = Files::new();
        let mut remaining = LIMITS.max_bytes - self.files.values().map(|s| s.len()).sum::<usize>();
        let mut count = self.files.len();
        for name in &config.dns.hosts {
            let Ok(path) = confined(root, Path::new(name), &self.links) else {
                continue;
            };
            if let Some(text) = self.files.get(&path) {
                companions.insert(path, text.clone());
                continue;
            }
            if companions.contains_key(&path) {
                continue;
            }
            let replacement = edit.filter(|(p, _)| *p == path);
            if replacement.is_none() && !path.is_file() {
                continue;
            }
            let text = if let Some((_, text)) = replacement {
                let value = bounded_text(text, remaining, count)?;
                remaining -= text.len();
                value
            } else {
                read_source(&path, &mut remaining, count, true)?
            };
            companions.insert(path, text);
            count += 1;
        }
        Ok(companions)
    }
}

pub(super) fn bounded_text(
    text: &str,
    remaining: usize,
    count: usize,
) -> Result<Arc<str>, HonkConfigError> {
    if count == LIMITS.max_sources || text.len() > remaining {
        return Err(HonkConfigError::SourceLimit);
    }
    Ok(Arc::from(text))
}

fn read_source(
    path: &Path,
    remaining: &mut usize,
    count: usize,
    regular: bool,
) -> Result<Arc<str>, HonkConfigError> {
    if count == LIMITS.max_sources {
        return Err(HonkConfigError::SourceLimit);
    }
    let source = &DiagnosticSources::new(Some(path.into())).root();
    if !regular {
        return Err(invalid(
            source,
            "invalid-config-source",
            "configuration source must be a regular file",
        ));
    }
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
pub(super) fn confined<'a>(
    root: &Path,
    path: &Path,
    links: &'a HashSet<PathBuf>,
) -> Result<PathBuf, Option<&'a Path>> {
    if path.is_absolute() {
        return Err(None);
    }
    let mut current = root.to_owned();
    for part in path.components() {
        match part {
            Component::ParentDir if current != root => {
                current.pop();
            }
            Component::CurDir => (),
            Component::Normal(name) => {
                current.push(name);
                if let Some(link) = links.get(&current) {
                    return Err(Some(link));
                }
            }
            _ => return Err(None),
        }
    }
    Ok(current)
}

struct Prepared<'a> {
    doc: Document<'a>,
    escapes: Vec<Escape>,
}

struct Escape {
    span: std::ops::Range<usize>,
    blank: bool,
    link: Option<PathBuf>,
}

// A symlink matching a directory component is a boundary even if no leaf is visible through it.
fn glob_link<'a>(path: &Path, root: &Path, links: &'a HashSet<PathBuf>) -> Option<&'a Path> {
    if links.is_empty() {
        return None;
    }
    let options = glob::MatchOptions {
        require_literal_separator: true,
        ..glob::MatchOptions::new()
    };
    for prefix in path.ancestors().take_while(|p| *p != root) {
        let text = prefix.to_str()?;
        if !text.contains(['*', '?', '[']) {
            break;
        }
        let pattern = glob::Pattern::new(text).ok()?;
        if let Some(link) = links.iter().find(|link| {
            (prefix != path || link.extension().is_some_and(|ext| ext == "dae"))
                && pattern.matches_path_with(link, options)
        }) {
            return Some(link);
        }
    }
    None
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
                let link = match &path {
                    Err(link) => *link,
                    Ok(path) => glob_link(path, root, links),
                };
                if path.is_err() || link.is_some() {
                    let start = entry.header_span().end - text.len();
                    escaped.push(Escape {
                        span: start..start + len,
                        blank: path.is_err(),
                        link: link.map(Path::to_owned),
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
    Tree::read(path)?.load().map(|(config, _)| config)
}

type Documents<'a> = HashMap<&'a Path, Prepared<'a>>;

struct Parsed<'a> {
    loaded: LoadedConfig,
    diagnostics: Vec<honk_config::diagnostic::DetailedDiagnostic>,
    prepared: Documents<'a>,
}

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
    tree: &Tree,
    companions: &Files,
    Parsed {
        mut loaded,
        mut diagnostics,
        mut prepared,
    }: Parsed<'_>,
) -> Result<HonkConfig, HonkConfigError> {
    let root = tree.entry.parent().unwrap();
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
                        files: &tree.files,
                        companions,
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
