//! The JSON payloads of the JNI contract (`plans/v1/core-api.md`, schema 1). Kotlin's twin is
//! `vpn/CoreDto.kt`; `contracts/doona-core/*.json` holds the examples both sides test against.
//! Unknown fields are ignored; a missing required field fails to parse (`invalid_argument`).

use serde::{Deserialize, Serialize};

/// The contract schema this core speaks; `init` returns it.
pub const SCHEMA: u32 = 1;

/// The closed set of error codes, as `ErrorCode` in Kotlin's `Engine.kt`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorCode {
    InvalidArgument,
    NotFound,
    FetchFailed,
    ParseFailed,
    NoNodes,
    Busy,
    Internal,
}

impl ErrorCode {
    pub fn as_str(self) -> &'static str {
        match self {
            ErrorCode::InvalidArgument => "invalid_argument",
            ErrorCode::NotFound => "not_found",
            ErrorCode::FetchFailed => "fetch_failed",
            ErrorCode::ParseFailed => "parse_failed",
            ErrorCode::NoNodes => "no_nodes",
            ErrorCode::Busy => "busy",
            ErrorCode::Internal => "internal",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
pub enum Mode {
    #[default]
    Global,
    Direct,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum GroupKind {
    Selector,
    UrlTest,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Init {
    pub schema: u32,
}

/// `start`'s config. The tun was built with the same values.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartConfig {
    pub mtu: u16,
    pub ipv4: std::net::Ipv4Addr,
    /// The virtual resolver the tun advertises; DNS sent to it is answered by the core.
    pub dns: std::net::Ipv4Addr,
    /// The underlying network's resolvers, as Android reports them; they may carry a scope
    /// (`fe80::1%wlan0`), so they stay text until a dialer needs them.
    pub bootstrap_dns: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    pub revision: i64,
    pub running: bool,
    pub generation: i64,
    pub since_epoch_ms: Option<i64>,
    pub error: Option<ErrorCode>,
    pub traffic: Traffic,
    pub catalog: Option<Catalog>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Traffic {
    pub up_total: u64,
    pub down_total: u64,
    pub active_flows: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Catalog {
    pub mode: Mode,
    pub groups: Vec<Group>,
    pub subscriptions: Vec<Subscription>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Group {
    pub id: String,
    pub name: String,
    pub kind: GroupKind,
    pub selected_id: Option<String>,
    pub pinned: bool,
    pub testing: bool,
    pub members: Vec<Member>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Member {
    pub id: String,
    pub name: String,
    pub latency_ms: Option<u32>,
    pub failed: bool,
    pub is_group: bool,
    pub subscription_id: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Subscription {
    pub id: String,
    pub name: String,
    pub url: String,
    pub nodes: u32,
    pub updated_at_epoch_ms: Option<i64>,
    pub error: Option<ErrorCode>,
    pub used_bytes: Option<u64>,
    pub total_bytes: Option<u64>,
    pub expires_at_epoch_ms: Option<i64>,
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
#[serde(rename_all = "camelCase")]
pub struct SelectArgs {
    pub group: String,
    #[serde(deserialize_with = "Option::deserialize")]
    pub member_id: Option<String>,
}

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
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct IdArgs {
    pub id: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModeArgs {
    pub mode: Mode,
}
