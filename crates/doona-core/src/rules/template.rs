//! doona's rule template, `cn-direct` (rule-mode.md, R2): the private ranges go direct, the
//! streaming services' names through `Streaming`, the AI services' names through `AI`, China's
//! addresses direct, and the rest through `Proxy`, the main group. The rows name groups of the
//! catalog's template (`groups::template`). A change to them is a new [`VERSION`].

use super::{Kind, RuleSpec, Target};
use crate::groups;
use crate::ids::group_id;

/// The template's name, as the contract will show it (R3).
#[cfg_attr(not(test), expect(dead_code, reason = "R3's ruleSource shows it"))]
pub(crate) const ID: &str = "cn-direct";
#[cfg_attr(not(test), expect(dead_code, reason = "R3's ruleSource shows it"))]
pub(crate) const VERSION: u32 = 2;

/// Netflix, YouTube, Disney+, Max and Prime Video, by suffix: their sites and the names their
/// video comes from.
pub(crate) const STREAMING: [&str; 14] = [
    "netflix.com",
    "netflix.net",
    "nflxext.com",
    "nflximg.net",
    "nflxso.net",
    "nflxvideo.net",
    "youtube.com",
    "googlevideo.com",
    "ytimg.com",
    "disneyplus.com",
    "bamgrid.com",
    "dssott.com",
    "max.com",
    "primevideo.com",
];

/// ChatGPT and Claude, by suffix.
pub(crate) const AI: [&str; 6] = [
    "openai.com",
    "chatgpt.com",
    "oaistatic.com",
    "oaiusercontent.com",
    "anthropic.com",
    "claude.ai",
];

/// What a user can change in the template.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct Options {
    /// QUIC (UDP to port 443) that a rule sends through a group is rejected, so the app falls back
    /// to TCP; QUIC that goes direct is kept. QUIC over a node is often slower than TCP, or not
    /// carried at all.
    pub block_quic: bool,
}

/// The template's rows, in order.
pub(crate) fn rules() -> Vec<RuleSpec> {
    let row = |kind, values: &[&str], target| RuleSpec {
        kind,
        values: values.iter().map(|v| (*v).to_owned()).collect(),
        target,
    };
    let group = |name| Target::Group(group_id(name));
    vec![
        row(Kind::GeoIp, &["private"], Target::Direct),
        row(Kind::DomainSuffix, &STREAMING, group(groups::STREAMING)),
        row(Kind::DomainSuffix, &AI, group(groups::AI)),
        row(Kind::GeoIp, &["CN"], Target::Direct),
        row(Kind::Match, &[], group(groups::PROXY)),
    ]
}
