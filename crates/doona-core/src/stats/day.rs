//! The day's usage in the device's local time: each way by the quarter hour, the flows opened each
//! hour, the bytes of each outbound and those and the flows of each rule, for the latest days in
//! full and as totals for the month before. A local day is `floor((wall time + zone offset) / 24 h)`, so a change of zone or of
//! summer time moves the boundary with it. Days are keyed by their date: bytes go to the date of
//! the clock that counted them, and today and yesterday are read by the current date, so a clock
//! set back, or a flight west across midnight, counts on the earlier date again and finds the
//! later one where it left it. A day dated after tomorrow can only come from a wrong clock: its
//! bytes move onto today, and such a day's total is dropped, so no future day is ever written.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::mem;

use super::Clock;

/// Quarter hours in a day: yesterday's usage at the same time is exact to a quarter hour.
pub(crate) const SLOTS: usize = 96;
pub(crate) const HOURS: usize = 24;
const SLOT_S: i64 = 900;
const DAY_S: i64 = 86_400;
/// Days kept in full: today and yesterday, and the later day a zone moved west from.
pub(crate) const FULL: usize = 3;
/// The month the totals cover, in days before today; also how many totals are kept, by count and
/// not by distance from today, so a clock that jumps ahead and back again leaves them in place.
pub(crate) const KEPT_DAYS: i64 = 31;
/// Outbounds a day keeps apart; more go to [`OTHER`].
pub(crate) const MAX_OUTBOUNDS: usize = 64;
/// Rules a day keeps apart; more go to [`OTHER`].
pub(crate) const MAX_RULES: usize = 64;
pub(crate) const OTHER: &str = "other";

/// The local day and its quarter hour at `clock`, and how far into that quarter it is (0..900 s).
pub(crate) fn local(clock: &Clock) -> (i64, usize, i64) {
    let local_s = clock.wall_ms.div_euclid(1000) + i64::from(clock.offset_s);
    let into_day = local_s.rem_euclid(DAY_S);
    let slot = usize::try_from(into_day / SLOT_S).unwrap_or(0);
    (local_s.div_euclid(DAY_S), slot, into_day % SLOT_S)
}

/// `days` since 1970-01-01 as an ISO date, `2026-11-22` (proleptic Gregorian, any sign).
pub(crate) fn date(days: i64) -> String {
    // Howard Hinnant's civil_from_days.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02}")
}

/// One local day: bytes each way by the quarter hour, flows opened by the hour, the bytes of each
/// outbound (`[up, down]` by the route's target id), and the bytes and flows of each rule
/// (`[up, down, flows]` by row id, or `unmatched`) in Rule mode. A file written before rules were
/// counted has none.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) struct Day {
    pub day: i64,
    pub up: Vec<u64>,
    pub down: Vec<u64>,
    pub flows: Vec<u64>,
    #[serde(default)]
    pub outbounds: BTreeMap<String, [u64; 2]>,
    #[serde(default)]
    pub rules: BTreeMap<String, [u64; 3]>,
}

impl Day {
    pub fn new(day: i64) -> Day {
        Day {
            day,
            up: vec![0; SLOTS],
            down: vec![0; SLOTS],
            flows: vec![0; HOURS],
            outbounds: BTreeMap::new(),
            rules: BTreeMap::new(),
        }
    }

    /// Both ways together.
    pub fn total(&self) -> (u64, u64) {
        (self.up.iter().sum(), self.down.iter().sum())
    }

    /// Bytes each way by the hour.
    pub fn hours(slots: &[u64]) -> Vec<u64> {
        slots
            .chunks(SLOTS / HOURS)
            .map(|c| c.iter().sum())
            .collect()
    }

    /// Both ways before quarter `slot`, and `into` seconds of it pro rata.
    fn by(&self, slot: usize, into: i64) -> u64 {
        let before: u64 = (self.up[..slot].iter().chain(&self.down[..slot])).sum();
        let part = self.up[slot] + self.down[slot];
        let into = u64::try_from(into).unwrap_or(0);
        before + part.saturating_mul(into) / SLOT_S as u64
    }

    /// Adds an outbound's bytes; past [`MAX_OUTBOUNDS`] targets, a new one counts as [`OTHER`].
    fn count(&mut self, id: &str, up: u64, down: u64) {
        // A count that would pass u64::MAX stays as it was; only a wrong file gets near.
        let _ = bounded(&mut self.outbounds, MAX_OUTBOUNDS, id, [up, down]);
    }

    /// Adds a rule's bytes and flows; past [`MAX_RULES`] rules, a new one counts as [`OTHER`].
    fn count_rule(&mut self, id: &str, moved: [u64; 3]) {
        let _ = bounded(&mut self.rules, MAX_RULES, id, moved);
    }

    /// Adds every count of `other`, quarter by quarter. None, with `self` unchanged, when the
    /// two pass `u64::MAX` together (see [`Day::sane`]).
    fn merge(&mut self, other: &Day) -> Option<()> {
        let mut sum = self.clone();
        for (mine, theirs) in [
            (&mut sum.up, &other.up),
            (&mut sum.down, &other.down),
            (&mut sum.flows, &other.flows),
        ] {
            for (a, b) in mine.iter_mut().zip(theirs) {
                *a = a.checked_add(*b)?;
            }
        }
        sum.adds_up()?;
        add_all(&mut sum.outbounds, &other.outbounds, MAX_OUTBOUNDS)?;
        add_all(&mut sum.rules, &other.rules, MAX_RULES)?;
        *self = sum;
        Some(())
    }

    /// Whether the quarters both ways add up within `u64`, as the day's total and yesterday's
    /// by a time of day add them.
    fn adds_up(&self) -> Option<()> {
        (self.up.iter().chain(&self.down)).try_fold(0u64, |sum, n| sum.checked_add(*n))?;
        Some(())
    }

    /// A day read from a file: lists of the wrong length and maps past their bound are fixed
    /// rather than trusted, and a rule id no rule has counts as [`OTHER`]. None when its counts
    /// pass `u64::MAX` once added up, its quarters both ways or the ids folded into one: no day
    /// comes near, so the file is wrong, and the day is dropped rather than wrapped.
    fn sane(mut self) -> Option<Day> {
        self.up.resize(SLOTS, 0);
        self.down.resize(SLOTS, 0);
        self.flows.resize(HOURS, 0);
        self.adds_up()?;
        let (outbounds, read) = (mem::take(&mut self.outbounds), mem::take(&mut self.rules));
        add_all(&mut self.outbounds, &outbounds, MAX_OUTBOUNDS)?;
        let mut rules = BTreeMap::new();
        for (id, moved) in &read {
            let id = if rule_id(id) { id } else { OTHER };
            bounded(&mut rules, usize::MAX, id, *moved)?;
        }
        add_all(&mut self.rules, &rules, MAX_RULES)?;
        Some(self)
    }
}

/// Whether `id` is one a rule counts under: a row id (`r-` and 16 lowercase hex digits),
/// `unmatched` or [`OTHER`].
fn rule_id(id: &str) -> bool {
    let row =
        |hex: &str| hex.len() == 16 && hex.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'));
    id == "unmatched" || id == OTHER || id.strip_prefix("r-").is_some_and(row)
}

/// Adds `moved` to `id` in `counts`; past `max` ids, a new one counts as [`OTHER`]. None, with
/// the id's counts left as they were, when one would pass `u64::MAX`.
fn bounded<const N: usize>(
    counts: &mut BTreeMap<String, [u64; N]>,
    max: usize,
    id: &str,
    moved: [u64; N],
) -> Option<()> {
    let full = counts.len() >= max && !counts.contains_key(id);
    let entry = counts
        .entry(if full { OTHER } else { id }.to_owned())
        .or_insert([0; N]);
    let mut sum = *entry;
    for (a, b) in sum.iter_mut().zip(moved) {
        *a = a.checked_add(b)?;
    }
    *entry = sum;
    Some(())
}

/// Adds every count of `from` to `counts` under `max`. [`OTHER`] comes last, so an id that fits
/// stays apart however it sorts against `other`, and a day written at its bound reads back as it
/// was. None at the first count that would pass `u64::MAX`.
fn add_all<const N: usize>(
    counts: &mut BTreeMap<String, [u64; N]>,
    from: &BTreeMap<String, [u64; N]>,
    max: usize,
) -> Option<()> {
    let ids = from.iter().filter(|(id, _)| *id != OTHER);
    for (id, moved) in ids.chain(from.get_key_value(OTHER)) {
        bounded(counts, max, id, *moved)?;
    }
    Some(())
}

/// A day's totals.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) struct Total {
    pub day: i64,
    pub up: u64,
    pub down: u64,
}

/// What a fold adds to the day.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct Moved {
    pub up: u64,
    pub down: u64,
    pub flows: u64,
    /// `(outbound id, up, down)`.
    pub outbounds: Vec<(String, u64, u64)>,
    /// `(rule id, [up, down, flows])`.
    pub rules: Vec<(String, [u64; 3])>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) struct Usage {
    /// The latest days by date, oldest first, at most [`FULL`].
    #[serde(default)]
    pub full: Vec<Day>,
    /// The totals of days with bytes that are no longer kept in full, oldest first, at most
    /// [`KEPT_DAYS`].
    #[serde(default)]
    pub days: Vec<Total>,
}

/// The usage as of one local day.
pub(crate) struct View<'a> {
    pub today: Option<&'a Day>,
    pub yesterday: Option<&'a Day>,
    /// The days with bytes in the month before today, oldest first.
    pub days: Vec<Total>,
}

impl Usage {
    pub fn get(&self, day: i64) -> Option<&Day> {
        self.full.iter().find(|d| d.day == day)
    }

    /// Local day `now`, made ready to count on, and its place in `full`: a day dated after
    /// tomorrow was counted under a wrong clock, so its bytes move onto `now` and such a total is
    /// dropped; `now` is added if it is not kept.
    fn settle(&mut self, now: i64) -> usize {
        let ahead = now + 1;
        let mut today = match self.full.iter().position(|d| d.day == now) {
            Some(at) => self.full.remove(at),
            None => Day::new(now),
        };
        self.full.retain(|d| {
            let wrong = d.day > ahead;
            if wrong {
                // Past u64::MAX only from a wrong file: today stays as it was.
                let _ = today.merge(d);
            }
            !wrong
        });
        self.days.retain(|t| t.day <= ahead);
        let at = self.full.partition_point(|d| d.day < now);
        self.full.insert(at, today);
        // At most one kept day is after `now`, so only days before it go over the bound.
        let over = self.full.len().saturating_sub(FULL);
        self.retire(over);
        at.saturating_sub(over)
    }

    /// Keeps `day` in full, merged with a kept day of its date, or dropped when the two pass
    /// `u64::MAX` together; the oldest beyond [`FULL`] keep only their totals.
    fn keep(&mut self, day: Day) {
        match self.full.iter_mut().find(|d| d.day == day.day) {
            Some(kept) => {
                let _ = kept.merge(&day);
            }
            None => {
                let at = self.full.partition_point(|d| d.day < day.day);
                self.full.insert(at, day);
            }
        }
        self.retire(self.full.len().saturating_sub(FULL));
    }

    /// Keeps only the totals of the `n` oldest days kept in full.
    fn retire(&mut self, n: usize) {
        let old: Vec<Day> = self.full.drain(..n).collect();
        for d in old {
            let (up, down) = d.total();
            self.total(Total {
                day: d.day,
                up,
                down,
            });
        }
    }

    /// Keeps a day's totals, added to any kept for its date, unless the two pass `u64::MAX`
    /// together (a wrong file); past [`KEPT_DAYS`], the oldest go.
    fn total(&mut self, t: Total) {
        if (t.up, t.down) == (0, 0) {
            return;
        }
        match self.days.binary_search_by_key(&t.day, |k| k.day) {
            Ok(i) => {
                let kept = &mut self.days[i];
                if let (Some(up), Some(down)) =
                    (kept.up.checked_add(t.up), kept.down.checked_add(t.down))
                {
                    (kept.up, kept.down) = (up, down);
                }
            }
            Err(i) => self.days.insert(i, t),
        }
        let over = self.days.len().saturating_sub(KEPT_DAYS as usize);
        self.days.drain(..over);
    }

    /// Adds `moved` at `clock`'s quarter hour of its local day.
    pub fn add(&mut self, clock: &Clock, moved: &Moved) {
        let (now, slot, _) = local(clock);
        let at = self.settle(now);
        let day = &mut self.full[at];
        day.up[slot] += moved.up;
        day.down[slot] += moved.down;
        day.flows[slot / (SLOTS / HOURS)] += moved.flows;
        for (id, up, down) in &moved.outbounds {
            day.count(id, *up, *down);
        }
        for (id, rule) in &moved.rules {
            day.count_rule(id, *rule);
        }
    }

    /// Today's bytes each way, and yesterday's both ways together by the same local time when
    /// yesterday is kept, as of `clock`. Changes nothing.
    pub fn today(&self, clock: &Clock) -> (u64, u64, Option<u64>) {
        let (now, slot, into) = local(clock);
        let (up, down) = self.get(now).map_or((0, 0), Day::total);
        (up, down, self.get(now - 1).map(|y| y.by(slot, into)))
    }

    /// Today, yesterday and the month before as of local day `now`; a later day is left out.
    pub fn view(&self, now: i64) -> View<'_> {
        let month = |day: i64| day >= now - KEPT_DAYS && day < now;
        let mut days = Usage::default();
        for d in self.full.iter().filter(|d| month(d.day)) {
            let (up, down) = d.total();
            days.total(Total {
                day: d.day,
                up,
                down,
            });
        }
        for t in self.days.iter().filter(|t| month(t.day)) {
            days.total(*t);
        }
        View {
            today: self.get(now),
            yesterday: self.get(now - 1),
            days: days.days,
        }
    }

    /// The days as read from a file, each fixed where it does not fit.
    pub fn sane(self) -> Usage {
        let mut usage = Usage::default();
        for t in self.days {
            usage.total(t);
        }
        for d in self.full.into_iter().filter_map(Day::sane) {
            usage.keep(d);
        }
        usage
    }
}

#[cfg(test)]
mod tests;
