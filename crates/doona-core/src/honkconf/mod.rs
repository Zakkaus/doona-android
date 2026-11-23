//! Import honk sources and keep their private drafts and applied revisions.
//! Capability reporting never edits `Config`.
//!
//! The capture family is IPv4. A requested non-Chrome `global.utls_imitate` is an
//! `unsupported_option`: honk's uTLS implementation can only use the Chrome profile.
//! Hosts companions are resolved under the imported entry directory, not the Linux
//! `global.data_dir`. No full honk GeoIP/GEOSITE snapshot is linked into this runtime.

pub mod bundle;
mod declarations;
mod draft;
pub mod edit;
mod rules;
mod staged;
mod store;

pub use draft::Draft;
pub use store::{HonkStore, StoreError};

use std::borrow::Cow;
use std::collections::HashSet;
use std::ops::Range;
use std::path::Path;

use honk_config::Config;
use honk_config::diagnostic::{DetailedDiagnostic, SettingPath, SourceRef};
use honk_config::error::{DetailedConfigError, ErrorCategory};
use honk_config::parser::{LoadedConfig, SourceSnapshot};

/// Import failures. Capability findings on an admitted document are not parse errors.
#[derive(Debug)]
pub enum HonkConfigError {
    /// The staged tree exceeds honk's source count or byte budget.
    SourceLimit,
    /// The entry extension or document format is not native `.dae`.
    NonNativeSource,
    /// Source I/O, parsing, indexing or validation failed.
    ParseFailed {
        /// Terminal diagnostic, with honk's source coordinates when available.
        cause: DetailedConfigError,
        /// All diagnostics in emission order, including the terminal cause.
        diagnostics: Vec<DetailedDiagnostic>,
    },
}

impl std::fmt::Display for HonkConfigError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::SourceLimit => f.write_str("configuration exceeds the source limit"),
            Self::NonNativeSource => f.write_str("expected native .dae configuration"),
            Self::ParseFailed { cause, .. } => cause.fmt(f),
        }
    }
}

impl std::error::Error for HonkConfigError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::ParseFailed { cause, .. } => Some(cause),
            _ => None,
        }
    }
}

/// Coordinates in an original UTF-8 source, suitable for opening the declaration.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Location {
    /// Index into [`HonkConfig::sources`] (entry first, then includes in honk merge order).
    pub source: usize,
    /// Half-open byte range.
    pub bytes: Range<usize>,
    /// One-based physical line.
    pub line: usize,
    /// One-based byte column.
    pub byte_column: usize,
}

/// A capability or asset required by an effective declaration.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Reason {
    /// Stable machine-readable reason, independent of the UI language.
    pub code: &'static str,
    /// For geodata, the category including any attribute; never a URI, secret or file path.
    pub category: Option<String>,
}

impl Reason {
    fn new(code: &'static str) -> Self {
        Self {
            code,
            category: None,
        }
    }
}

/// What activation can do with this declaration.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Disposition {
    /// Omit this declaration without changing the imported text.
    Skip,
    /// Still usable on IPv4 or queries with a real original DNS destination.
    Limited,
    /// An include was refused: its declarations are unknown and Apply must be blocked.
    Incomplete,
}

/// One affected declaration, regardless of how many capabilities it needs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Unsupported {
    /// Native section (including nested sections).
    pub section: &'static str,
    /// Setting name or ordinal; contains no user-supplied path or credentials.
    pub name: Cow<'static, str>,
    /// Independent reasons affecting this declaration.
    pub reasons: Vec<Reason>,
    /// Required activation behaviour.
    pub disposition: Disposition,
    /// Original contributions, including aliases that affect the same setting.
    pub locations: Vec<Location>,
}

/// Rule-family scope and counts at import, not runtime packet counters.
#[derive(Debug, PartialEq, Eq)]
pub struct Summary {
    /// IP families admitted by the current Android ingress.
    pub captured_ip_versions: &'static [u8],
    /// Configured subscription sources, not fetched nodes.
    pub subscriptions: usize,
    /// Nodes declared in the imported tree.
    pub nodes: usize,
    /// Effective groups after includes are merged.
    pub groups: usize,
    /// Traffic rules, excluding the fallback.
    pub routing_rules: usize,
    /// Effective DNS upstreams, including honk defaults.
    pub dns_upstreams: usize,
    /// Request rules, excluding the fallback.
    pub dns_request_rules: usize,
    /// Response rules, excluding the fallback.
    pub dns_response_rules: usize,
    /// Number of affected declarations, not the sum of their reasons.
    pub unsupported: usize,
}

/// Validated native configuration and its read-only Android capability report.
#[derive(Debug)]
pub struct HonkConfig {
    loaded: LoadedConfig,
    diagnostics: Vec<DetailedDiagnostic>,
    unsupported: Vec<Unsupported>,
}

impl HonkConfig {
    /// Effective configuration produced by the pinned honk parser.
    pub fn config(&self) -> &Config {
        &self.loaded.config
    }
    /// Nonterminal diagnostics retained from admission.
    pub fn diagnostics(&self) -> &[DetailedDiagnostic] {
        &self.diagnostics
    }
    /// Exact `.dae` source bytes; never log these.
    pub fn sources(&self) -> &[SourceSnapshot] {
        &self.loaded.sources
    }
    /// One record per affected declaration, not per reason or packet. The TUN admits IPv4 only.
    pub fn unsupported(&self) -> Vec<Unsupported> {
        self.unsupported.clone()
    }
    /// False for an incomplete include tree. Skipping a hosts source does not hide declarations.
    pub fn complete(&self) -> bool {
        !self
            .unsupported
            .iter()
            .any(|r| r.disposition == Disposition::Incomplete)
    }
    /// Effective inventory, including honk's default DNS upstream; fallbacks are not rules.
    pub fn summary(&self) -> Summary {
        let cfg = self.config();
        let dns = &cfg.dns.routing;
        Summary {
            captured_ip_versions: &[4],
            subscriptions: cfg.subscriptions.len(),
            nodes: cfg.nodes.len(),
            groups: cfg.groups.len(),
            routing_rules: cfg.routing.rules.len(),
            dns_upstreams: cfg.dns.upstream.len(),
            dns_request_rules: dns.request.rules.len(),
            dns_response_rules: dns.response.rules.len(),
            unsupported: self.unsupported.len(),
        }
    }
}

/// Load native honk configuration, retaining include order, node IDs and diagnostics.
/// The caller owns the private staged directory and must not modify it during import.
pub fn load(path: impl AsRef<Path>) -> Result<HonkConfig, HonkConfigError> {
    staged::load(path.as_ref())
}

fn validate(config: &Config, root: &SourceRef) -> Result<(), DetailedConfigError> {
    config.validate_detailed().map_err(|mut error| {
        error.diagnostic.source = root.clone();
        error
    })?;
    // honk-core's operator admission adds this check; its helper is crate-private.
    let mut ids = HashSet::with_capacity(config.subscriptions.len());
    if config
        .subscriptions
        .iter()
        .any(|sub| sub.id.is_nil() || !ids.insert(sub.id))
    {
        return Err(DetailedConfigError::new(
            ErrorCategory::Validation,
            "invalid-subscription-id",
            root.clone(),
            SettingPath::new("subscriptions").field("id"),
            "subscription IDs must be non-nil and unique",
        ));
    }
    Ok(())
}
