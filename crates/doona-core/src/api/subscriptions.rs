//! `addSubscription`, `updateSubscription` and `removeSubscription`. The download and the parse
//! run with no lock held; the result commits under the writer lock, and an update commits only
//! if its subscription still has the incarnation it had when the update began (core-api.md,
//! Commit rule).

use super::{Core, lock};
use crate::catalog::{self, NodeEntry};
use crate::dto::{self, AddSubscriptionArgs, ErrorCode};
use crate::groups;
use crate::state::{State, StoredSubscription};
use crate::subscription::fetch::{self, FETCH_BOUND, Userinfo};
use crate::subscription::parse::{self, Parsed};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use url::Url;

/// How long the calling thread waits beyond the fetch's own bound before it gives up.
const WAIT_SLACK: Duration = Duration::from_secs(1);

impl Core {
    pub(super) fn add_subscription(
        &self,
        args: AddSubscriptionArgs,
    ) -> Result<dto::Subscription, ErrorCode> {
        let begun = Instant::now();
        let url = fetch::parse_url(&args.url)?;
        let name = args
            .name
            .map(|n| n.trim().to_owned())
            .filter(|n| !n.is_empty())
            .unwrap_or_else(|| url.host_str().unwrap_or_default().to_owned());
        let (parsed, userinfo) = self.download(url)?;
        let _writer = lock(&self.writer);
        let mut next = lock(&self.shared.catalog).state.clone();
        let id = next.take_subscription_id();
        let mut stored = StoredSubscription {
            id: id.clone(),
            name,
            url: args.url.trim().to_owned(),
            updated_at_epoch_ms: None,
            used_bytes: None,
            total_bytes: None,
            expires_at_epoch_ms: None,
            error: None,
            links: Vec::new(),
        };
        let fresh = refreshed(&mut stored, parsed, userinfo);
        next.subscriptions.push(stored);
        let added = self.commit_nodes(next, &id, fresh, "addSubscription")?;
        log::info!(
            "addSubscription: {} nodes in {} ms",
            added.nodes,
            begun.elapsed().as_millis()
        );
        Ok(added)
    }

    pub(super) fn update_subscription(&self, id: &str) -> Result<dto::Subscription, ErrorCode> {
        let begun = Instant::now();
        let (url, incarnation) = {
            let c = lock(&self.shared.catalog);
            let s = c.state.subscriptions.iter().find(|s| s.id == id);
            let s = s.ok_or(ErrorCode::NotFound)?;
            (s.url.clone(), c.model.incarnations.get(id).copied())
        };
        let _refreshing = Refreshing::begin(&self.refreshing, id)?;
        let downloaded = fetch::parse_url(&url).and_then(|url| self.download(url));
        let _writer = lock(&self.writer);
        let mut next = {
            let c = lock(&self.shared.catalog);
            // Removed meanwhile: the result is dropped. Ids are never reused.
            if c.model.incarnations.get(id).copied() != incarnation {
                return Err(ErrorCode::NotFound);
            }
            c.state.clone()
        };
        let stored = next.subscriptions.iter_mut().find(|s| s.id == id);
        let stored = stored.ok_or(ErrorCode::NotFound)?;
        let (parsed, userinfo) = match downloaded {
            Ok(downloaded) => downloaded,
            Err(code) => {
                if stored.error != Some(code) {
                    stored.error = Some(code);
                    self.commit_state(next, "updateSubscription")?;
                }
                return Err(code);
            }
        };
        let fresh = refreshed(stored, parsed, userinfo);
        let updated = self.commit_nodes(next, id, fresh, "updateSubscription")?;
        log::info!(
            "updateSubscription: {} nodes in {} ms",
            updated.nodes,
            begun.elapsed().as_millis()
        );
        Ok(updated)
    }

    pub(super) fn remove_subscription(&self, id: &str) -> Result<(), ErrorCode> {
        let _writer = lock(&self.writer);
        let mut next = lock(&self.shared.catalog).state.clone();
        let before = next.subscriptions.len();
        next.subscriptions.retain(|s| s.id != id);
        if next.subscriptions.len() == before {
            return Err(ErrorCode::NotFound);
        }
        self.commit_nodes(next, id, Vec::new(), "removeSubscription")
            .map(drop)
    }

    /// Fetches and parses `url` on the runtime; this thread waits with its own timer, so the
    /// bound holds even while every worker is busy.
    fn download(&self, url: Url) -> Result<(Parsed, Userinfo), ErrorCode> {
        let (tx, rx) = std::sync::mpsc::channel();
        self.runtime.spawn(async move {
            let _ = tx.send(fetch::fetch(url).await);
        });
        let fetched = rx
            .recv_timeout(FETCH_BOUND + WAIT_SLACK)
            .map_err(|_| ErrorCode::FetchFailed)??;
        Ok((parse::parse(&fetched.body)?, fetched.userinfo))
    }

    /// Commits `next`, in which `changed` has `fresh` nodes or is gone: choices whose member
    /// went are dropped, the data path gets a registry for the new node set, and runtime state
    /// (latencies, automatic choices) carries over for the nodes that stay. Returns `changed`
    /// as the catalog shows it, or an empty one when it was removed.
    fn commit_nodes(
        &self,
        mut next: State,
        changed: &str,
        fresh: Vec<Arc<NodeEntry>>,
        command: &str,
    ) -> Result<dto::Subscription, ErrorCode> {
        let mut model = lock(&self.shared.catalog).model.with(&next, changed, fresh);
        groups::prune(&mut next.selections, &model.groups);
        let built = self.shared.outbounds.build(&model.nodes);
        self.write(&next, command)?;
        let shown = {
            let mut c = lock(&self.shared.catalog);
            model.carry(&c.model);
            c.model = model;
            c.state = next;
            c.revision += 1;
            self.shared.outbounds.replace(built);
            let s = c.state.subscriptions.iter().find(|s| s.id == changed);
            s.map(|s| c.model.subscription(s))
        };
        self.store.sync();
        Ok(shown.unwrap_or_else(|| removed(changed)))
    }
}

/// Fills `stored` from a successful download and returns its nodes.
fn refreshed(
    stored: &mut StoredSubscription,
    parsed: Parsed,
    info: Userinfo,
) -> Vec<Arc<NodeEntry>> {
    stored.updated_at_epoch_ms = Some(now_ms());
    stored.used_bytes = info.used_bytes;
    stored.total_bytes = info.total_bytes;
    stored.expires_at_epoch_ms = info.expires_at_epoch_ms;
    stored.error = None;
    stored.links = parsed.links;
    catalog::entries(&stored.id, parsed.nodes)
}

fn removed(id: &str) -> dto::Subscription {
    dto::Subscription {
        id: id.to_owned(),
        name: String::new(),
        url: String::new(),
        nodes: 0,
        updated_at_epoch_ms: None,
        error: None,
        used_bytes: None,
        total_bytes: None,
        expires_at_epoch_ms: None,
    }
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

/// Marks an update of one subscription under way until dropped.
struct Refreshing<'a> {
    set: &'a Mutex<std::collections::HashSet<String>>,
    id: String,
}

impl<'a> Refreshing<'a> {
    fn begin(
        set: &'a Mutex<std::collections::HashSet<String>>,
        id: &str,
    ) -> Result<Refreshing<'a>, ErrorCode> {
        if !lock(set).insert(id.to_owned()) {
            return Err(ErrorCode::Busy);
        }
        Ok(Refreshing {
            set,
            id: id.to_owned(),
        })
    }
}

impl Drop for Refreshing<'_> {
    fn drop(&mut self) {
        lock(self.set).remove(&self.id);
    }
}
