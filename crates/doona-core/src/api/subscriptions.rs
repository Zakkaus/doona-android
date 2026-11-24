//! `addSubscription`, `updateSubscription`, `removeSubscription` and `setSubscriptionUserAgent`.
//! The download and the parse run with no lock held; the result commits under the writer lock,
//! and an update commits only if its subscription still has the incarnation it had when the update
//! began (core-api.md, Commit rule). One deadline covers the download, the parse and the wait for
//! the writer; work that misses it is dropped before it commits.

use super::{Core, lock};
use crate::catalog::{self, NodeEntry};
use crate::dto::{self, AddSubscriptionArgs, ErrorCode, SubscriptionUserAgentArgs};
use crate::groups;
use crate::state::{State, StoredSubscription};
use crate::subscription::fetch::{self, Userinfo};
use crate::subscription::parse::{self, Parsed};
use std::sync::{Arc, Mutex, MutexGuard, TryLockError};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use url::Url;

/// `addSubscription` and `updateSubscription` return within this, their commit excepted
/// (core-api.md).
pub(super) const SUBSCRIPTION_BOUND: Duration = Duration::from_secs(30);
/// The share of a command's time the fetch leaves for the parse and the wait for the writer: one
/// part in this many.
const AFTER_FETCH: u32 = 10;

impl Core {
    pub(super) fn add_subscription(
        &self,
        args: AddSubscriptionArgs,
    ) -> Result<dto::Subscription, ErrorCode> {
        let begun = Instant::now();
        let deadline = begun + self.options.subscription_bound;
        let url = fetch::parse_url(&args.url)?;
        let agent = args.user_agent.as_deref().map(fetch::user_agent);
        let agent = agent.transpose()?;
        let name = args
            .name
            .map(|n| n.trim().to_owned())
            .filter(|n| !n.is_empty())
            .unwrap_or_else(|| url.host_str().unwrap_or_default().to_owned());
        let (parsed, userinfo) = self.download(url, agent.clone(), deadline)?;
        let _writer = self.writer_until(deadline)?;
        let mut next = {
            let c = lock(&self.shared.catalog);
            catalog::within_budget(c.model.nodes.len(), &parsed.nodes)?;
            c.state.clone()
        };
        let id = next.take_subscription_id();
        let mut stored = StoredSubscription {
            id: id.clone(),
            name,
            url: args.url.trim().to_owned(),
            format: None,
            updated_at_epoch_ms: None,
            used_bytes: None,
            total_bytes: None,
            expires_at_epoch_ms: None,
            error: None,
            links: Vec::new(),
            document: None,
            user_agent: agent,
        };
        let fresh = refreshed(&mut stored, parsed, userinfo);
        next.subscriptions.push(stored);
        self.commit_nodes(next, &id, fresh, "addSubscription", Some(deadline))
    }

    pub(super) fn update_subscription(&self, id: &str) -> Result<dto::Subscription, ErrorCode> {
        let begun = Instant::now();
        let deadline = begun + self.options.subscription_bound;
        let (url, agent, incarnation) = {
            let c = lock(&self.shared.catalog);
            let s = c.state.subscriptions.iter().find(|s| s.id == id);
            let s = s.ok_or(ErrorCode::NotFound)?;
            let incarnation = c.model.incarnations.get(id).copied();
            (s.url.clone(), s.user_agent.clone(), incarnation)
        };
        let _refreshing = Refreshing::begin(&self.refreshing, id)?;
        let downloaded = fetch::parse_url(&url).and_then(|url| self.download(url, agent, deadline));
        // Out of time: the result, or the failure it would record, is dropped.
        let _writer = match self.writer_until(deadline) {
            Ok(writer) => writer,
            Err(busy) => return Err(downloaded.err().unwrap_or(busy)),
        };
        let (mut next, others) = {
            let c = lock(&self.shared.catalog);
            // Removed meanwhile: the result is dropped. Ids are never reused.
            if c.model.incarnations.get(id).copied() != incarnation {
                return Err(ErrorCode::NotFound);
            }
            (c.state.clone(), c.model.nodes.len() - c.model.nodes_of(id))
        };
        let stored = next.subscriptions.iter_mut().find(|s| s.id == id);
        let stored = stored.ok_or(ErrorCode::NotFound)?;
        let downloaded = downloaded.and_then(|(parsed, userinfo)| {
            catalog::within_budget(others, &parsed.nodes)?;
            Ok((parsed, userinfo))
        });
        let (parsed, userinfo) = match downloaded {
            Ok(downloaded) => downloaded,
            Err(code) => {
                if stored.error != Some(code) {
                    stored.error = Some(code);
                    // Out of time (`busy`): the failure is returned but not recorded.
                    match self.commit_state(next, "updateSubscription", Some(deadline)) {
                        Ok(()) | Err(ErrorCode::Busy) => {}
                        Err(other) => return Err(other),
                    }
                }
                return Err(code);
            }
        };
        let fresh = refreshed(stored, parsed, userinfo);
        self.commit_nodes(next, id, fresh, "updateSubscription", Some(deadline))
    }

    pub(super) fn remove_subscription(&self, id: &str) -> Result<(), ErrorCode> {
        let _writer = lock(&self.writer);
        let mut next = lock(&self.shared.catalog).state.clone();
        let before = next.subscriptions.len();
        next.subscriptions.retain(|s| s.id != id);
        if next.subscriptions.len() == before {
            return Err(ErrorCode::NotFound);
        }
        self.commit_nodes(next, id, Vec::new(), "removeSubscription", None)
            .map(drop)
    }

    /// Sets the User-Agent the subscription's next fetches send, null for the default, and fetches
    /// nothing. An update already under way sends the agent it began with and keeps this one.
    pub(super) fn set_subscription_user_agent(
        &self,
        args: SubscriptionUserAgentArgs,
    ) -> Result<dto::Subscription, ErrorCode> {
        let agent = args.user_agent.as_deref().map(fetch::user_agent);
        let agent = agent.transpose()?;
        let _writer = lock(&self.writer);
        let mut next = lock(&self.shared.catalog).state.clone();
        let stored = next.subscriptions.iter_mut().find(|s| s.id == args.id);
        let stored = stored.ok_or(ErrorCode::NotFound)?;
        if stored.user_agent != agent {
            stored.user_agent = agent;
            self.commit_state(next, "setSubscriptionUserAgent", None)?;
        }
        let c = lock(&self.shared.catalog);
        let s = c.state.subscriptions.iter().find(|s| s.id == args.id);
        s.map(|s| c.model.subscription(s))
            .ok_or(ErrorCode::NotFound)
    }

    /// Fetches `url` with `agent` (the default when none) on the runtime, leaving a share of the
    /// time to `deadline` for the parse, which runs on a blocking thread, and the writer. This
    /// thread waits for both with its own timer, so the deadline holds even while every worker is
    /// busy; a parse still running at it is `fetch_failed`, and its result is dropped.
    fn download(
        &self,
        url: Url,
        agent: Option<String>,
        deadline: Instant,
    ) -> Result<(Parsed, Userinfo), ErrorCode> {
        let left = deadline.saturating_duration_since(Instant::now());
        let fetching = left - left / AFTER_FETCH;
        let (tx, rx) = std::sync::mpsc::channel();
        self.runtime.spawn(async move {
            let downloaded = match fetch::fetch(url, agent.as_deref(), fetching).await {
                Ok(fetched) => tokio::task::spawn_blocking(move || {
                    parse::parse(&fetched.body).map(|parsed| (parsed, fetched.userinfo))
                })
                .await
                .unwrap_or(Err(ErrorCode::Internal)),
                Err(code) => Err(code),
            };
            let _ = tx.send(downloaded);
        });
        rx.recv_timeout(deadline.saturating_duration_since(Instant::now()))
            .map_err(|_| ErrorCode::FetchFailed)?
    }

    /// The writer lock if it comes free before `deadline`, else `busy`. std's `Mutex` has no
    /// timed lock, so this polls; it waits for another command's commit, which is short.
    fn writer_until(&self, deadline: Instant) -> Result<MutexGuard<'_, ()>, ErrorCode> {
        loop {
            if Instant::now() >= deadline {
                return Err(ErrorCode::Busy);
            }
            match self.writer.try_lock() {
                Ok(writer) => return Ok(writer),
                Err(TryLockError::Poisoned(e)) => return Ok(e.into_inner()),
                Err(TryLockError::WouldBlock) => std::thread::sleep(Duration::from_millis(2)),
            }
        }
    }

    /// Commits `next`, in which `changed` has `fresh` nodes or is gone: choices whose member
    /// went are dropped, the data path gets a registry for the new node set, and runtime state
    /// (latencies, automatic choices) carries over for the nodes that stay. Returns `changed`
    /// as the catalog shows it, or an empty one when it was removed. Past `deadline`, which the
    /// registry rebuild and the serialization may use up, nothing is written and the command is
    /// `busy` ([`Core::write`] checks it right before the first write). A fresh node may be one
    /// whose tests an earlier commit forgot: that reaches the disk first, or nothing is
    /// committed, so a crash after this commit never reads the old tests back.
    fn commit_nodes(
        &self,
        mut next: State,
        changed: &str,
        fresh: Vec<Arc<NodeEntry>>,
        command: &'static str,
        deadline: Option<Instant>,
    ) -> Result<dto::Subscription, ErrorCode> {
        if !fresh.is_empty() && !self.shared.stats.forgetting_written() {
            log::error!("{command}: forgotten latency tests not written");
            return Err(ErrorCode::Internal);
        }
        let mut model = lock(&self.shared.catalog).model.with(&next, changed, fresh);
        groups::prune(&mut next.selections, &model.groups);
        let built = self.shared.outbounds.build(&model.nodes);
        self.write(&next, command, deadline)?;
        let shown = {
            let mut c = lock(&self.shared.catalog);
            model.carry(&c.model);
            c.model = model;
            c.state = next;
            c.revision += 1;
            self.shared.publish_nodes(&c, built);
            // The tests of the nodes that left go with them, so a node that comes back has none.
            self.shared.stats.keep_nodes(|id| c.model.has(id));
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
    stored.format = Some(parsed.format);
    stored.updated_at_epoch_ms = Some(now_ms());
    stored.used_bytes = info.used_bytes;
    stored.total_bytes = info.total_bytes;
    stored.expires_at_epoch_ms = info.expires_at_epoch_ms;
    stored.error = None;
    stored.links = parsed.links;
    stored.document = parsed.document;
    catalog::entries(&stored.id, parsed.nodes)
}

fn removed(id: &str) -> dto::Subscription {
    dto::Subscription {
        id: id.to_owned(),
        name: String::new(),
        url: String::new(),
        nodes: 0,
        format: None,
        updated_at_epoch_ms: None,
        error: None,
        used_bytes: None,
        total_bytes: None,
        expires_at_epoch_ms: None,
        user_agent: None,
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
