//! When feeds are fetched: `subscriptionSchedule`, `setSubscriptionSchedule` and `refreshDue`
//! as functions over the sidecar [`Schedule`] and the in-memory [`Claims`], with the clock
//! passed in as UTC epoch milliseconds. A feed's interval is its own `interval`, then the
//! `assets` default, then the switch's default hours (config-plan.md, subscription rows).
//!
//! Switching off stops scheduled fetches only. A manual update claims the provider the same way,
//! so it never runs next to a scheduled fetch of that provider.

use std::collections::BTreeMap;
use std::collections::btree_map::Entry;

use serde::{Deserialize, Serialize};

use super::feeds::{Authored, IntervalSource, Provider};
use crate::dto::ErrorCode;

/// The default hours the owner chose on the review page (DECISIONS.md, 2026-11-25 20:30).
pub const HOURS: [u32; 4] = [1, 6, 12, 24];
pub const DEFAULT_HOURS: u32 = 12;
/// How long a failed provider waits, at most, before it is due again.
const RETRY_MS: i64 = 3_600_000;

/// The sidecar record: the switch, the default and each provider's last outcomes.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Schedule {
    pub enabled: bool,
    pub default_hours: u32,
    progress: BTreeMap<String, Progress>,
}

/// The outcomes of one provider's fetches with `user_agent`. An id names one tag and URL
/// (feeds.rs), so only the User-Agent can change under it, and then the provider starts again.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Progress {
    user_agent: Option<String>,
    last_success_ms: Option<i64>,
    last_failure_ms: Option<i64>,
}

impl Progress {
    fn new(provider: &Provider) -> Progress {
        Progress {
            user_agent: provider.user_agent.clone(),
            last_success_ms: None,
            last_failure_ms: None,
        }
    }

    fn is_for(&self, provider: &Provider) -> bool {
        self.user_agent == provider.user_agent
    }
}

impl Default for Schedule {
    fn default() -> Self {
        Schedule {
            enabled: true,
            default_hours: DEFAULT_HOURS,
            progress: BTreeMap::new(),
        }
    }
}

/// Providers being fetched now, scheduled or manual, each with the User-Agent of its fetch. Not
/// persisted: a restart ends every fetch.
#[derive(Debug, Default)]
pub struct Claims(BTreeMap<String, Option<String>>);

impl Claims {
    /// Takes `provider` for one fetch; false when it is already being fetched.
    pub fn claim(&mut self, provider: &Provider) -> bool {
        match self.0.entry(provider.id.clone()) {
            Entry::Vacant(slot) => {
                slot.insert(provider.user_agent.clone());
                true
            }
            Entry::Occupied(_) => false,
        }
    }
}

/// `subscriptionSchedule {}`.
#[derive(Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Overview {
    pub enabled: bool,
    pub default_hours: u32,
    /// Earliest deadline, UTC epoch ms; none when off or when no provider has one.
    pub next_refresh_at: Option<i64>,
}

/// `subscriptionSchedule {"id": …}`, beside the [`Overview`].
#[derive(Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FeedSchedule {
    /// Zero is manual.
    pub effective_interval_seconds: u32,
    pub interval_source: IntervalSource,
}

/// `refreshDue {}`.
#[derive(Debug, PartialEq, Eq)]
pub struct Due {
    /// Providers claimed by this call; the caller fetches each and calls [`Schedule::finish`].
    pub claimed: Vec<Provider>,
    pub next_refresh_at: Option<i64>,
}

impl Schedule {
    /// `setSubscriptionSchedule`: `default_hours` must be one of [`HOURS`]; nothing changes
    /// otherwise.
    pub fn set(&mut self, enabled: bool, default_hours: u32) -> Result<(), ErrorCode> {
        if !HOURS.contains(&default_hours) {
            return Err(ErrorCode::InvalidArgument);
        }
        self.enabled = enabled;
        self.default_hours = default_hours;
        Ok(())
    }

    pub fn overview(&self, claims: &Claims, providers: &[Provider], now: i64) -> Overview {
        Overview {
            enabled: self.enabled,
            default_hours: self.default_hours,
            next_refresh_at: self.next_refresh_at(claims, providers, now),
        }
    }

    pub fn feed(&self, provider: &Provider) -> FeedSchedule {
        let (seconds, source) = self.interval(provider);
        FeedSchedule {
            effective_interval_seconds: seconds,
            interval_source: source,
        }
    }

    /// `refreshDue`: claims every provider that is due and not being fetched, once. Off claims
    /// none. A provider never fetched is due at once.
    pub fn refresh_due(&self, claims: &mut Claims, providers: &[Provider], now: i64) -> Due {
        let mut claimed = Vec::new();
        if self.enabled {
            for provider in providers {
                if self.due_at(provider, now).is_some_and(|due| due <= now)
                    && claims.claim(provider)
                {
                    claimed.push(provider.clone());
                }
            }
        }
        Due {
            claimed,
            next_refresh_at: self.next_refresh_at(claims, providers, now),
        }
    }

    /// Ends the fetch of provider `id`, which succeeded when `ok`. Returns false, recording
    /// nothing, when the provider is gone (an edited tag or URL gives another id) or its
    /// User-Agent changed since the claim: such a late result must be dropped, or it would
    /// bring back what the source removed.
    pub fn finish(
        &mut self,
        claims: &mut Claims,
        providers: &[Provider],
        id: &str,
        ok: bool,
        now: i64,
    ) -> bool {
        let claimed = claims.0.remove(id);
        let Some(provider) = providers
            .iter()
            .find(|p| p.id == id && claimed.as_ref() == Some(&p.user_agent))
        else {
            return false;
        };
        let progress = self
            .progress
            .entry(id.to_owned())
            .and_modify(|p| {
                if !p.is_for(provider) {
                    *p = Progress::new(provider);
                }
            })
            .or_insert_with(|| Progress::new(provider));
        *(if ok {
            &mut progress.last_success_ms
        } else {
            &mut progress.last_failure_ms
        }) = Some(now);
        true
    }

    fn interval(&self, provider: &Provider) -> (u32, IntervalSource) {
        match provider.interval {
            Some(Authored { seconds, source }) => (seconds, source),
            None => (self.default_hours * 3600, IntervalSource::Global),
        }
    }

    /// When `provider` is next due, or none for a manual one.
    fn due_at(&self, provider: &Provider, now: i64) -> Option<i64> {
        let interval = i64::from(self.interval(provider).0) * 1000;
        if interval == 0 {
            return None;
        }
        let (success, failure) = self
            .progress
            .get(&provider.id)
            .filter(|p| p.is_for(provider))
            .map_or((None, None), |p| (p.last_success_ms, p.last_failure_ms));
        let by_success = success.map_or(now, |at| at.saturating_add(interval));
        // A failure older than the last success ends no later than `by_success`.
        let retry = failure.map(|failed| failed.saturating_add(interval.min(RETRY_MS)));
        retry.max(Some(by_success))
    }

    fn next_refresh_at(&self, claims: &Claims, providers: &[Provider], now: i64) -> Option<i64> {
        if !self.enabled {
            return None;
        }
        providers
            .iter()
            .filter(|p| !claims.0.contains_key(&p.id))
            .filter_map(|p| self.due_at(p, now))
            .min()
    }
}
