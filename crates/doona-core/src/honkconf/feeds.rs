//! Remote feeds as honk declares them: the `subscription` entries, with the interval and
//! User-Agent defaults of `assets { subscription { … } }`, become [`Provider`]s, and a download
//! becomes a [`Feed`] of nodes. The download and the body parser are the ones the live path uses
//! (`subscription::fetch`, `subscription::parse`), called read-only. A feed brings nodes and
//! nothing else: Clash `proxy-groups` and `rules` and sing-box `selector` or `urltest`
//! outbounds never reach [`Feed`], so a provider cannot change a group.

use std::collections::{BTreeMap, BTreeSet};
use std::time::Duration;

use honk_config::node::Node;
use honk_config::parser::SourceSnapshot;
use honk_config::parser::cursor::{Document, Segment};
use honk_config::parser::lexer::Source;

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

/// An interval written in the source. Zero is honk's "manual": no scheduled fetch.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Authored {
    pub seconds: u64,
    pub source: IntervalSource,
}

/// One `subscription` declaration.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Provider {
    /// `s-` and the tag; a second entry with the same tag gets `#2`, a third `#3`, in source
    /// order. Node ids are scoped by it.
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
    let own = authored_intervals(config.sources());
    let mut seen = BTreeMap::<&str, usize>::new();
    cfg.subscriptions
        .iter()
        .map(|sub| {
            let n = seen.entry(&sub.name).or_default();
            *n += 1;
            let source = if own.contains(sub.name.as_str()) {
                Some(IntervalSource::Subscription)
            } else {
                assets.then_some(IntervalSource::Assets)
            };
            Provider {
                id: match n {
                    1 => format!("s-{}", sub.name),
                    n => format!("s-{}#{n}", sub.name),
                },
                name: sub.name.clone(),
                url: sub.url.clone(),
                user_agent: sub.user_agent.clone(),
                interval: source.map(|source| Authored {
                    seconds: sub.update_interval,
                    source,
                }),
            }
        })
        .collect()
}

/// Tags of the entries that write `interval` themselves. honk merges that value with the assets
/// default and keeps no trace of which one it read, so the entry's own lines are read again. Only
/// the entry form `tag: 'url' { interval: … }` and the block form `tag { url: …; interval: … }`
/// directly under `subscription` are read; a legacy wrapper around entries is not.
fn authored_intervals(sources: &[SourceSnapshot]) -> BTreeSet<String> {
    let mut tags = BTreeSet::new();
    for snapshot in sources {
        let source = Source::new(&snapshot.content, snapshot.source.clone());
        let Ok(doc) = Document::parse(source, &mut Vec::new()) else {
            continue;
        };
        for section in doc.sections().filter(|s| s.header() == "subscription") {
            for entry in section.body().into_iter().flatten() {
                if writes_interval(&entry) {
                    tags.insert(tag(entry.header()).to_owned());
                }
            }
        }
    }
    tags
}

fn writes_interval(entry: &Segment<'_, '_>) -> bool {
    entry
        .body()
        .into_iter()
        .flatten()
        .filter(|line| line.body().is_none())
        .any(|line| {
            line.header()
                .split_once(':')
                .is_some_and(|(key, _)| key.trim() == "interval")
        })
}

/// The tag of an entry header: `office` of `office: 'https://…'`, quotes removed.
fn tag(header: &str) -> &str {
    let header = header.trim();
    let quote = header.chars().next().filter(|c| matches!(c, '\'' | '"'));
    match quote {
        Some(q) => header[1..]
            .split_once(q)
            .map_or(&header[1..], |(tag, _)| tag),
        None => header.split_once(':').map_or(header, |(tag, _)| tag.trim()),
    }
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
