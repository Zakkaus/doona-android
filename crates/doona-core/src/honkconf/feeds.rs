//! Remote feeds as honk declares them: the `subscription` entries, with the interval and
//! User-Agent defaults of `assets { subscription { … } }`, become [`Provider`]s, and a download
//! becomes a [`Feed`] of nodes. The download and the body parser are the ones the live path uses
//! (`subscription::fetch`, `subscription::parse`), called read-only. A feed brings nodes and
//! nothing else: Clash `proxy-groups` and `rules` and sing-box `selector` or `urltest`
//! outbounds never reach [`Feed`], so a provider cannot change a group.

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use honk_config::node::Node;
use honk_config::parser::{SourceLimits, parse_dae_sources};
use uuid::Uuid;

use super::HonkConfig;
use crate::dto::{ErrorCode, SubscriptionFormat};
use crate::ids;
use crate::subscription::fetch::{self, Fetched};
use crate::subscription::parse;

/// Where a provider's interval comes from, in the order honk and doona apply them.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "lowercase")]
pub enum IntervalSource {
    /// The entry's own `interval`.
    Subscription,
    /// `assets { subscription { interval } }`.
    Assets,
    /// Neither is written: the switch's default hours apply.
    Global,
}

/// An interval written in the source, saturated at `u32::MAX` seconds (about 136 years). Zero is
/// honk's "manual": no scheduled fetch.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Authored {
    pub seconds: u32,
    pub source: IntervalSource,
}

/// One `subscription` declaration.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Provider {
    /// `s-` and 16 hex digits of the tag and URL, so another entry never shifts it. Entries
    /// that repeat both get `#2`, `#3`, in source order. Node ids are scoped by it.
    pub id: String,
    pub name: String,
    pub url: String,
    /// The entry's `ua`, else the assets default. Checked when it is sent.
    pub user_agent: Option<String>,
    /// `None` when neither the entry nor `assets` writes an interval.
    pub interval: Option<Authored>,
}

/// The providers a configuration declares, in declaration order.
pub fn providers(config: &HonkConfig) -> Vec<Provider> {
    let cfg = config.config();
    let assets = cfg.assets.subscription.interval.is_some();
    let mut seen = BTreeMap::<String, usize>::new();
    cfg.subscriptions
        .iter()
        .zip(own_intervals(config))
        .map(|(sub, own)| {
            let source = if own {
                Some(IntervalSource::Subscription)
            } else {
                assets.then_some(IntervalSource::Assets)
            };
            let id = provider_id(&sub.name, &sub.url);
            let n = seen.entry(id.clone()).or_default();
            *n += 1;
            Provider {
                id: match n {
                    1 => id,
                    n => format!("{id}#{n}"),
                },
                name: sub.name.clone(),
                url: sub.url.clone(),
                user_agent: sub.user_agent.clone(),
                interval: source.map(|source| Authored {
                    seconds: u32::try_from(sub.update_interval).unwrap_or(u32::MAX),
                    source,
                }),
            }
        })
        .collect()
}

/// A hash, because the URL often carries the account's token and ids are shown and logged.
fn provider_id(tag: &str, url: &str) -> String {
    let url = Uuid::new_v5(&Uuid::NAMESPACE_URL, url.as_bytes());
    let hash = Uuid::new_v5(&url, tag.as_bytes()).simple().to_string();
    format!("s-{}", &hash[..16])
}

/// For each subscription in order, whether its entry writes `interval` itself. honk merges that
/// value with the `assets` default and keeps no trace of which one it read, so honk parses the
/// loaded sources once more with another default after them: an entry that keeps its interval
/// wrote its own.
fn own_intervals(config: &HonkConfig) -> Vec<bool> {
    let cfg = config.config();
    // Flipping a bit gives a value that differs from the default in use.
    let other = cfg.assets.subscription_base().update_interval ^ 1;
    let probe = format!("assets {{ subscription {{ interval: {other}s }} }}");
    let mut inputs: Vec<(PathBuf, Arc<str>)> = config
        .sources()
        .iter()
        .map(|s| (s.path.clone(), s.content.clone()))
        .collect();
    // The empty label cannot repeat a loaded source's absolute path.
    inputs.push((PathBuf::new(), probe.into()));
    // The loader already held the sources to its limits.
    let unlimited = SourceLimits {
        max_bytes: usize::MAX,
        max_sources: usize::MAX,
    };
    // The sources differ from what the loader gave honk only in the include patterns it refused,
    // and this parse expands no include.
    let probed = parse_dae_sources(&inputs, unlimited, &mut Vec::new())
        .expect("honk has parsed these sources");
    cfg.subscriptions
        .iter()
        .zip(probed.config.subscriptions)
        .map(|(sub, probed)| sub.update_interval == probed.update_interval)
        .collect()
}

/// A downloaded feed: its nodes and what the cache needs to read them again.
#[derive(Debug)]
pub struct Feed {
    pub format: SubscriptionFormat,
    pub nodes: Vec<FeedNode>,
    /// Share links of the nodes, for a share-link body.
    pub links: Vec<String>,
    /// Entries of the nodes, for a Clash, sing-box or SIP008 body.
    pub document: Option<String>,
    pub used_bytes: Option<i64>,
    pub total_bytes: Option<i64>,
    pub expires_at_epoch_ms: Option<i64>,
}

/// A node of one provider.
#[derive(Debug)]
pub struct FeedNode {
    /// `n-` and 16 hex digits, scoped by the provider's id (`ids.rs`).
    pub id: String,
    pub node: Node,
}

impl Feed {
    fn new(provider: &str, fetched: Fetched) -> Result<Feed, ErrorCode> {
        let parsed = parse::parse(&fetched.body)?;
        let nodes = parsed
            .nodes
            .into_iter()
            .map(|node| FeedNode {
                id: ids::short_id(ids::scoped_uuid(provider, node.id)),
                node,
            })
            .collect();
        Ok(Feed {
            format: parsed.format,
            nodes,
            links: parsed.links,
            document: parsed.document,
            used_bytes: fetched.userinfo.used_bytes,
            total_bytes: fetched.userinfo.total_bytes,
            expires_at_epoch_ms: fetched.userinfo.expires_at_epoch_ms,
        })
    }

    /// A feed from a body already in hand, such as a cached one.
    pub fn from_body(provider: &str, body: Vec<u8>) -> Result<Feed, ErrorCode> {
        Feed::new(
            provider,
            Fetched {
                body,
                ..Default::default()
            },
        )
    }
}

/// Downloads a provider's feed within `bound`, redirects included. A User-Agent the header
/// cannot carry is `invalid_argument` and nothing is sent; other failures are the fetcher's.
pub async fn fetch(provider: &Provider, bound: Duration) -> Result<Feed, ErrorCode> {
    let agent = provider
        .user_agent
        .as_deref()
        .map(fetch::user_agent)
        .transpose()?;
    let url = fetch::parse_url(&provider.url)?;
    let fetched = fetch::fetch(url, agent.as_deref(), bound).await?;
    Feed::new(&provider.id, fetched)
}
