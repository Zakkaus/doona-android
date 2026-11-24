//! JNI schema 3 (`plans/v1/core-api.md`); Kotlin's `vpn/CoreDto.kt` shares `contracts/doona-core/*.json`.
//! Unknown fields are ignored; missing required fields yield `invalid_argument`.

pub use crate::diag::report::{
    ClearCrashesArgs, CoreBuild, CrashFile, DiagEvent, Diagnostics, DiagnosticsArgs, LastSnapshot,
    Level, LevelArgs, StatsSummary,
};
use serde::{Deserialize, Serialize};

pub mod location;
pub mod recent;
pub mod score;
mod selection;
pub use selection::{Network, SelectArgs};
mod start;
pub use start::StartConfig;

/// Schema 3 admits catalog leaves without a subscription and adds the static editor commands.
pub const SCHEMA: u32 = 3;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorCode {
    InvalidArgument,
    NotFound,
    FetchFailed,
    TooLarge,
    ParseFailed,
    NoNodes,
    Busy,
    Internal,
    /// A local-network dial or fetch failed (Android 17 `ACCESS_LOCAL_NETWORK`, ARCH D26).
    LocalNetwork,
    DnsFailed,
}

impl ErrorCode {
    pub fn as_str(self) -> &'static str {
        match self {
            ErrorCode::InvalidArgument => "invalid_argument",
            ErrorCode::NotFound => "not_found",
            ErrorCode::FetchFailed => "fetch_failed",
            ErrorCode::TooLarge => "too_large",
            ErrorCode::ParseFailed => "parse_failed",
            ErrorCode::NoNodes => "no_nodes",
            ErrorCode::Busy => "busy",
            ErrorCode::Internal => "internal",
            ErrorCode::LocalNetwork => "local_network",
            ErrorCode::DnsFailed => "dns_failed",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
pub enum Mode {
    #[default]
    Global,
    Direct,
    /// Each new flow takes its first matching rule's target (rule-mode.md); new in schema 2.
    Rule,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum GroupKind {
    Selector,
    UrlTest,
    Score,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Init {
    pub schema: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct BootstrapDnsArgs {
    pub servers: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    pub revision: i64,
    pub running: bool,
    pub generation: i64,
    pub since_epoch_ms: Option<i64>,
    pub error: Option<ErrorCode>,
    /// A problem that leaves the data path up: `local_network` once a dial through a node was
    /// refused on its way to an address on the local network, until a dial reaches a node there.
    /// Null while stopped; each change raises `revision`.
    pub warning: Option<ErrorCode>,
    pub traffic: Traffic,
    /// The local day's bytes so far, kept across restarts; also while stopped.
    pub today: Today,
    pub catalog: Option<Catalog>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Traffic {
    pub up_total: u64,
    pub down_total: u64,
    pub active_flows: u32,
    /// The session's fastest second each way, in bytes, whether or not a screen was open.
    pub up_peak: u64,
    pub down_peak: u64,
}

/// Today's bytes each way in the device's local time zone, and yesterday's both ways together by
/// the same local time (exact to the quarter hour, the current quarter pro rata; null when the
/// core kept no yesterday, so a screen can leave the comparison out).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Today {
    pub up_total: u64,
    pub down_total: u64,
    pub yesterday_by_now: Option<u64>,
}

/// `usage`'s reply: today and yesterday by the hour of the local day, and the totals of the
/// finished days of the last 31 that had any bytes, oldest first (today not among them).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Usage {
    pub today: DayUsage,
    pub yesterday: Option<DayUsage>,
    pub days: Vec<DayTotal>,
}

/// One local day: 24 hours of bytes each way and of flows the apps opened, the bytes of each
/// outbound the day's flows went through, and those and the flows of each rule that routed them.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct DayUsage {
    /// `2026-11-22`.
    pub date: String,
    pub up: Vec<u64>,
    pub down: Vec<u64>,
    pub flows: Vec<u64>,
    pub outbounds: Vec<OutboundBytes>,
    /// Empty outside Rule mode.
    #[serde(rename = "byRule")]
    pub by_rule: Vec<RuleBytes>,
}

/// The payload one outbound carried: `id` is the route's target, the group a flow went through
/// (`g-Proxy` in Global) or `direct`; a rule mode adds group and node ids; `other` holds what
/// went past 64 targets in a day. Payload only, so the sum is under the tun's day total by the
/// packet headers, and DNS the core answers itself is not in it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct OutboundBytes {
    pub id: String,
    pub up: u64,
    pub down: u64,
}

/// The payload and the flows of one rule in Rule mode, in id order: `id` is the row's id as
/// `rules` lists it, `unmatched` for flows no row matched (they went through the main group), and
/// `other` holds what went past 64 rules in a day. A day's rules carry the same bytes as its
/// outbounds for the flows Rule mode routed.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RuleBytes {
    pub id: String,
    pub up: u64,
    pub down: u64,
    pub flows: u64,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct DayTotal {
    pub date: String,
    pub up: u64,
    pub down: u64,
}

/// `trafficSeries`' reply: the rate over a window that ends at most a second ago, oldest point
/// first. Each point is the average bytes per second over `stepSeconds`; the peaks are the
/// fastest single second of the window. `endEpochMs` is the wall time at the end of the last
/// point; point `i` of `n` ends `(n - 1 - i) × stepSeconds` seconds before it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TrafficSeries {
    pub step_seconds: u64,
    pub end_epoch_ms: i64,
    pub up: Vec<u64>,
    pub down: Vec<u64>,
    pub up_peak: u64,
    pub down_peak: u64,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Catalog {
    pub mode: Mode,
    pub groups: Vec<Group>,
    pub subscriptions: Vec<Subscription>,
    /// The group Global sends every flow through and Rule the flows no rule matched.
    pub main_group_id: String,
    pub rule_source: RuleSource,
}

/// Where Rule mode's rules come from, and what compiled of them. `rules` counts rows, `lines`
/// values (a row without values, `MATCH`, counts one) and `skipped` the lines not compiled.
/// `rulesRevision` is derived from the row ids: equal rows give an equal number across rebuilds
/// and restarts, so it is compared for equality only. `error` is `parse_failed` when the source
/// did not compile; the template always does.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuleSource {
    pub kind: SourceKind,
    /// The template's name and version; null for a source that is no template.
    pub template: Option<String>,
    pub version: Option<u32>,
    /// The subscription a source came from; null for the template.
    pub subscription_id: Option<String>,
    pub options: RuleOptions,
    pub rules: u32,
    pub lines: u32,
    pub skipped: u32,
    pub rules_revision: u64,
    pub error: Option<ErrorCode>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SourceKind {
    Template,
}

/// What a user can change in a template.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuleOptions {
    /// QUIC (UDP to port 443) a rule sends through a group is refused, so apps fall back to TCP.
    pub block_quic: bool,
}

/// `setRuleSource`'s argument, and the source `state.json` keeps. Schema 2 knows the template
/// only; another kind does not parse (`invalid_argument`).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum RuleSourceSpec {
    Template {
        template: String,
        options: RuleOptions,
    },
}

impl Default for RuleSourceSpec {
    /// doona's template, `cn-direct`, with no option on.
    fn default() -> RuleSourceSpec {
        RuleSourceSpec::Template {
            template: "cn-direct".to_owned(),
            options: RuleOptions::default(),
        }
    }
}

/// `rules`' page: `limit` is 1 to 500, and an `offset` past the end gives no rows.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RulesArgs {
    pub offset: u32,
    pub limit: u32,
}

/// `rules`' reply: `total` rows in the source, `rows` the page asked for.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Rules {
    pub rules_revision: u64,
    pub total: u32,
    pub rows: Vec<RuleRow>,
}

/// One row of rules. `id` is `r-` and 16 hex digits, stable across node changes and restarts;
/// `kind` is `Domain`, `DomainSuffix`, `DomainKeyword`, `IpCidr`, `GeoIp`, `DstPort`, `Network`,
/// `Match`, or for a kind not compiled the source's own name (`GEOSITE`). `sample` is the first
/// value, null for `Match`; `count` the values. `target` is `direct`, `reject` or a group id.
/// `skipped` says why lines of the row were not compiled (`unsupported_kind`,
/// `geodata_missing`, `app_rule`), `skippedLines` how many.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuleRow {
    pub id: String,
    pub index: u32,
    pub kind: String,
    pub sample: Option<String>,
    pub count: u32,
    pub target: String,
    pub target_kind: TargetKind,
    pub skipped: Option<String>,
    pub skipped_lines: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TargetKind {
    Direct,
    Reject,
    Group,
}

/// `effectiveConfig`'s reply: where flows and DNS go, the rules' provenance, and the rules as
/// Clash-style text, capped at 1 MiB at a line boundary (`truncated`). Nodes appear in it only
/// by display name, never with a server, a port or a credential.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EffectiveConfig {
    pub mode: Mode,
    pub main_group_id: String,
    /// `direct` in Direct, else the main group's id.
    pub dns_via: String,
    pub dns_upstreams: Vec<String>,
    pub rule_source: RuleSource,
    pub text: String,
    pub truncated: bool,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Group {
    pub id: String,
    pub name: String,
    pub kind: GroupKind,
    pub selected_id: Option<String>,
    pub tcp_selected_id: Option<String>,
    pub udp_selected_id: Option<String>,
    #[serde(default)]
    pub networks_differ: bool,
    pub pinned: bool,
    #[serde(default)]
    pub tcp_pinned: bool,
    #[serde(default)]
    pub udp_pinned: bool,
    pub testing: bool,
    pub members: Vec<Member>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Member {
    pub id: String,
    pub name: String,
    pub latency_ms: Option<u32>,
    pub failed: bool,
    /// Known cause of the last probe failure, e.g. `local_network` or `dns_failed`; null for groups.
    pub error: Option<ErrorCode>,
    pub is_group: bool,
    pub subscription_id: Option<String>,
    #[serde(flatten)]
    pub location: location::Location,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SubscriptionFormat {
    ShareLinks,
    Base64,
    Clash,
    SingBox,
    Sip008,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Subscription {
    pub id: String,
    pub name: String,
    pub url: String,
    pub nodes: u32,
    /// Format of the last successful fetch; null for subscriptions saved before it was recorded.
    pub format: Option<SubscriptionFormat>,
    pub updated_at_epoch_ms: Option<i64>,
    pub error: Option<ErrorCode>,
    pub used_bytes: Option<i64>,
    pub total_bytes: Option<i64>,
    pub expires_at_epoch_ms: Option<i64>,
    /// The User-Agent its fetches send; null sends the default.
    pub user_agent: Option<String>,
}

/// A `call` reply: `{"ok":<result>}` or `{"error":{"code":"…"}}`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Reply<T> {
    Ok(T),
    Error(CallError),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct CallError {
    pub code: ErrorCode,
}

/// The empty object most commands return.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Empty {}

// Nullable argument keys are required (core-api.md writes `"group":null`): serde reads a
// missing `Option` field as `None` unless it is deserialized as a plain value.

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct GroupArgs {
    #[serde(deserialize_with = "Option::deserialize")]
    pub group: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AddSubscriptionArgs {
    pub url: String,
    #[serde(deserialize_with = "Option::deserialize")]
    pub name: Option<String>,
    /// Optional, unlike the other nullable keys: a caller from before it sends the default.
    #[serde(default, rename = "userAgent")]
    pub user_agent: Option<String>,
}

/// `setSubscriptionUserAgent`: null returns the subscription to the default.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SubscriptionUserAgentArgs {
    pub id: String,
    #[serde(deserialize_with = "Option::deserialize")]
    pub user_agent: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct IdArgs {
    pub id: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModeArgs {
    pub mode: Mode,
}

/// `nodes`' choice: every node in catalog order, or these ids (unknown ones are left out).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct NodesArgs {
    #[serde(deserialize_with = "Option::deserialize")]
    pub ids: Option<Vec<String>>,
}

/// `nodes`' reply.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Nodes {
    pub nodes: Vec<NodeInfo>,
}

/// What a node's share link says, and its last latency tests (at most 8, oldest first). The
/// protocol is honk's name (`trojan`, `vless`, `ss`, `hysteria2`, …); the transport is `quic` for
/// the QUIC protocols, else the stream transport (`tcp`, `ws`, `grpc`, …).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct NodeInfo {
    pub id: String,
    pub protocol: String,
    pub server: String,
    pub port: u16,
    pub transport: String,
    pub tls: bool,
    pub tests: Vec<LatencyTest>,
}

/// One latency test: when its round began, and the time it measured; none when the probe failed.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LatencyTest {
    pub at_epoch_ms: i64,
    pub ms: Option<u32>,
}

/// `trafficSeries`' window: 1 to 3600 seconds.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SeriesArgs {
    pub seconds: u64,
}

/// The private editor view; catalog members never include the credential-bearing link.
#[derive(Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct StaticNode {
    pub id: String,
    pub name: String,
    pub link: String,
}

impl std::fmt::Debug for StaticNode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("StaticNode")
            .field("id", &self.id)
            .finish_non_exhaustive()
    }
}

#[derive(Deserialize, Serialize)]
#[serde(tag = "kind", rename_all = "lowercase", deny_unknown_fields)]
pub enum StaticDraft {
    Link { link: String, name: Option<String> },
    Manual(ManualNode),
}

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct ManualNode {
    pub protocol: String,
    pub server: String,
    pub port: u16,
    pub name: Option<String>,
    pub username: Option<String>,
    pub password: Option<String>,
    pub uuid: Option<String>,
    pub cipher: Option<String>,
    #[serde(default)]
    pub options: std::collections::BTreeMap<String, String>,
}

/// Deserialize each draft independently: one malformed field must not discard valid siblings.
#[derive(Deserialize)]
#[serde(untagged)]
pub enum StaticInput {
    Draft(StaticDraft),
    Malformed(serde::de::IgnoredAny),
}

#[derive(Deserialize)]
pub struct StaticBatch {
    pub rows: Vec<StaticInput>,
}

#[derive(Deserialize)]
pub struct StaticUpdate {
    pub expected: StaticNode,
    pub node: StaticInput,
}

#[derive(Deserialize)]
pub struct StaticRemove {
    pub expected: StaticNode,
    pub confirmed: bool,
}

#[derive(Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StaticReason {
    CatalogFull,
    Chain,
    Cipher,
    Credentials,
    DuplicateOption,
    Fields,
    Link,
    LinkLength,
    Name,
    Option,
    Password,
    Path,
    Port,
    Protocol,
    Server,
    Username,
    Uuid,
}

#[derive(Serialize, Deserialize)]
#[serde(tag = "status", rename_all = "lowercase")]
pub enum StaticResult {
    Ready {
        id: String,
        name: String,
        protocol: String,
    },
    Added {
        id: String,
        name: String,
        protocol: String,
    },
    Updated {
        id: String,
        name: String,
        protocol: String,
    },
    Duplicate {
        id: String,
    },
    Invalid {
        reason: StaticReason,
    },
    Unsupported {
        reason: StaticReason,
    },
}

#[derive(Serialize, Deserialize)]
pub struct StaticResults {
    pub rows: Vec<StaticResult>,
}
