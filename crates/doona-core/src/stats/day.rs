//! The day's usage in the device's local time: each way by the quarter hour, the flows opened each
//! hour, and the bytes of each outbound, for the latest days in full and as totals for the month
//! before. A local day is `floor((wall time + zone offset) / 24 h)`, so a change of zone or of
//! summer time moves the boundary with it. Days are keyed by their date: bytes go to the date of
//! the clock that counted them, and today and yesterday are read by the current date, so a clock
//! set back, or a flight west across midnight, counts on the earlier date again and finds the
//! later one where it left it. A day dated after tomorrow can only come from a wrong clock: its
//! bytes move onto today, and such a day's total is dropped, so no future day is ever written.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

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

/// One local day: bytes each way by the quarter hour, flows opened by the hour, and the bytes of
/// each outbound (`[up, down]` by the route's target id).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) struct Day {
    pub day: i64,
    pub up: Vec<u64>,
    pub down: Vec<u64>,
    pub flows: Vec<u64>,
    #[serde(default)]
    pub outbounds: BTreeMap<String, [u64; 2]>,
}

impl Day {
    pub fn new(day: i64) -> Day {
        Day {
            day,
            up: vec![0; SLOTS],
            down: vec![0; SLOTS],
            flows: vec![0; HOURS],
            outbounds: BTreeMap::new(),
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
        let full = self.outbounds.len() >= MAX_OUTBOUNDS && !self.outbounds.contains_key(id);
        let entry = (self.outbounds)
            .entry(if full { OTHER } else { id }.to_owned())
            .or_default();
        entry[0] += up;
        entry[1] += down;
    }

    /// Adds every count of `other`, quarter by quarter.
    fn merge(&mut self, other: &Day) {
        for (mine, theirs) in [
            (&mut self.up, &other.up),
            (&mut self.down, &other.down),
            (&mut self.flows, &other.flows),
        ] {
            mine.iter_mut().zip(theirs).for_each(|(a, b)| *a += b);
        }
        for (id, [up, down]) in &other.outbounds {
            self.count(id, *up, *down);
        }
    }

    /// A day read from a file: lists of the wrong length are fixed rather than trusted.
    fn sane(mut self) -> Day {
        self.up.resize(SLOTS, 0);
        self.down.resize(SLOTS, 0);
        self.flows.resize(HOURS, 0);
        while self.outbounds.len() > MAX_OUTBOUNDS {
            self.outbounds.pop_last();
        }
        self
    }
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
                today.merge(d);
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

    /// Keeps `day` in full, merged with a kept day of its date; the oldest beyond [`FULL`] keep
    /// only their totals.
    fn keep(&mut self, day: Day) {
        match self.full.iter_mut().find(|d| d.day == day.day) {
            Some(kept) => kept.merge(&day),
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

    /// Keeps a day's totals, added to any kept for its date; past [`KEPT_DAYS`], the oldest go.
    fn total(&mut self, t: Total) {
        if t.up + t.down == 0 {
            return;
        }
        match self.days.binary_search_by_key(&t.day, |k| k.day) {
            Ok(i) => {
                self.days[i].up += t.up;
                self.days[i].down += t.down;
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
        for d in self.full {
            usage.keep(d.sane());
        }
        usage
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 2026-11-22 00:00 UTC, local day 20779 at offset 0.
    const MIDNIGHT_MS: i64 = 20_779 * 86_400_000;
    const DAY: i64 = 20_779;
    const DAY_MS: i64 = 86_400_000;

    fn at(wall_ms: i64, offset_s: i32) -> Clock {
        Clock {
            boot_ms: 0,
            mono_ms: 0,
            wall_ms,
            offset_s,
        }
    }

    fn moved(up: u64, down: u64) -> Moved {
        Moved {
            up,
            down,
            flows: 1,
            outbounds: vec![("g-Proxy".into(), up, down)],
        }
    }

    fn total(usage: &Usage, day: i64) -> Option<(u64, u64)> {
        usage.get(day).map(Day::total)
    }

    fn kept(usage: &Usage) -> Vec<i64> {
        usage.full.iter().map(|d| d.day).collect()
    }

    fn totals(usage: &Usage) -> Vec<i64> {
        usage.days.iter().map(|t| t.day).collect()
    }

    #[test]
    fn dates_and_local_days() {
        assert_eq!(date(0), "1970-01-01");
        assert_eq!(date(20_779), "2026-11-22");
        assert_eq!(date(-1), "1969-12-31");
        assert_eq!(date(11_016), "2000-02-29");
        // 10:24 UTC is 18:24 in Taipei, quarter 73, 9 minutes in.
        let clock = at(MIDNIGHT_MS + 37_440_000, 8 * 3600);
        assert_eq!(local(&clock), (20_779, 73, 540));
        // 23:30 UTC on the 21st is already the 22nd in Taipei.
        assert_eq!(local(&at(MIDNIGHT_MS - 1_800_000, 8 * 3600)).0, 20_779);
        assert_eq!(local(&at(MIDNIGHT_MS - 1_800_000, 0)).0, 20_778);
    }

    #[test]
    fn bytes_land_in_their_quarter_and_hour() {
        let mut usage = Usage::default();
        usage.add(&at(MIDNIGHT_MS + 3_600_000 + 60_000, 0), &moved(10, 100));
        usage.add(&at(MIDNIGHT_MS + 2 * 3_600_000 - 1, 0), &moved(1, 2));
        let today = usage.get(DAY).unwrap();
        assert_eq!((today.up[4], today.down[4], today.up[7]), (10, 100, 1));
        assert_eq!(today.flows[1], 2);
        assert_eq!(Day::hours(&today.down)[1], 102);
        assert_eq!(today.outbounds["g-Proxy"], [11, 102]);
        assert_eq!(today.total(), (11, 102));
    }

    #[test]
    fn midnight_starts_a_day_and_old_days_keep_their_totals() {
        let mut usage = Usage::default();
        usage.add(&at(MIDNIGHT_MS + 23 * 3_600_000, 0), &moved(5, 50));
        // The first byte after midnight starts a new day; the 22nd is yesterday.
        usage.add(&at(MIDNIGHT_MS + DAY_MS, 0), &moved(1, 1));
        assert_eq!(kept(&usage), [DAY, DAY + 1]);
        assert_eq!(total(&usage, DAY), Some((5, 50)));
        let view = usage.view(DAY + 1);
        assert_eq!(view.yesterday.map(|d| d.day), Some(DAY));
        assert_eq!(
            view.days,
            [Total {
                day: DAY,
                up: 5,
                down: 50
            }]
        );
        // Two quiet days later: no yesterday; the 22nd goes over the bound and keeps its total.
        usage.add(&at(MIDNIGHT_MS + 3 * DAY_MS, 0), &moved(2, 2));
        usage.add(&at(MIDNIGHT_MS + 4 * DAY_MS, 0), &moved(0, 0));
        assert_eq!(kept(&usage), [DAY + 1, DAY + 3, DAY + 4]);
        assert_eq!(totals(&usage), [DAY]);
        let view = usage.view(DAY + 5);
        assert!(view.today.is_none());
        assert_eq!(view.yesterday.map(|d| d.day), Some(DAY + 4));
        // Days with no byte are not listed.
        let listed: Vec<i64> = view.days.iter().map(|t| t.day).collect();
        assert_eq!(listed, [DAY, DAY + 1, DAY + 3]);
        // The month before today only.
        let late = usage.view(DAY + 33);
        assert_eq!(late.days.first().map(|t| t.day), Some(DAY + 3));
    }

    #[test]
    fn totals_are_bounded_by_count() {
        let mut usage = Usage::default();
        for d in 0..KEPT_DAYS + 10 {
            usage.add(&at(MIDNIGHT_MS + d * DAY_MS, 0), &moved(1, 0));
        }
        assert_eq!(usage.days.len(), KEPT_DAYS as usize);
        assert_eq!(usage.full.len(), FULL);
        let last = DAY + KEPT_DAYS + 9;
        assert_eq!(usage.days.last().unwrap().day, last - FULL as i64);
    }

    #[test]
    fn a_clock_that_jumps_a_year_ahead_and_back_keeps_the_days() {
        let mut usage = Usage::default();
        for d in 0..5 {
            usage.add(&at(MIDNIGHT_MS + d * DAY_MS + 3_600_000, 0), &moved(1, 1));
        }
        let today = DAY + 4;
        let morning = at(MIDNIGHT_MS + 4 * DAY_MS + 10 * 3_600_000, 0);
        // A year ahead for a moment: a new day, and the history stays.
        let ahead = Clock {
            wall_ms: morning.wall_ms + 365 * DAY_MS,
            ..morning
        };
        usage.add(&ahead, &moved(7, 7));
        assert_eq!(kept(&usage), [today - 1, today, today + 365]);
        assert_eq!(totals(&usage), [DAY, DAY + 1, DAY + 2]);
        // Back to the right date: the day from the wrong clock moves onto today, today and
        // yesterday are the ones kept before, and the totals are all there.
        usage.add(&morning, &moved(2, 2));
        assert_eq!(kept(&usage), [today - 1, today]);
        assert_eq!(total(&usage, today), Some((1 + 7 + 2, 1 + 7 + 2)));
        assert_eq!(usage.today(&morning).2, Some(2));
        let view = usage.view(today);
        let listed: Vec<i64> = view.days.iter().map(|t| t.day).collect();
        assert_eq!(listed, [DAY, DAY + 1, DAY + 2, DAY + 3]);
        // Nothing is ever written ahead of tomorrow.
        assert!(usage.full.iter().all(|d| d.day <= today + 1));
    }

    #[test]
    fn a_zone_that_moves_west_counts_on_the_earlier_date_again() {
        let mut usage = Usage::default();
        // 23:00 UTC on the 21st, in Taipei (+8): 07:00 on the 22nd.
        let taipei = at(MIDNIGHT_MS - 3_600_000, 8 * 3600);
        usage.add(&at(MIDNIGHT_MS - 20 * 3_600_000, 8 * 3600), &moved(3, 3));
        usage.add(&taipei, &moved(4, 4));
        assert_eq!(kept(&usage), [DAY - 1, DAY]);
        // The phone lands in Los Angeles (−8) at the same instant: 15:00 on the 21st.
        let west = Clock {
            offset_s: -8 * 3600,
            ..taipei
        };
        usage.add(&west, &moved(1, 1));
        assert_eq!(total(&usage, DAY - 1), Some((4, 4)));
        assert_eq!(total(&usage, DAY), Some((4, 4)));
        // Today is the 21st again, and the 22nd waits as it was.
        assert_eq!(usage.today(&west), (4, 4, None));
        let view = usage.view(DAY - 1);
        assert_eq!(view.today.map(|d| d.day), Some(DAY - 1));
        assert!(view.days.is_empty());
        // Once the clock reaches the 22nd there, it is today with its morning, after the 21st.
        let next = at(MIDNIGHT_MS + 9 * 3_600_000, -8 * 3600);
        usage.add(&next, &moved(1, 0));
        assert_eq!(usage.today(&next).0, 5);
        assert!(usage.today(&next).2.is_some());
        assert_eq!(kept(&usage), [DAY - 1, DAY]);
    }

    #[test]
    fn a_zone_that_moves_east_starts_the_next_day() {
        let mut usage = Usage::default();
        // 22:00 UTC on the 22nd at offset 0, then the phone lands in Taipei: 06:00 the 23rd.
        let clock = at(MIDNIGHT_MS + 22 * 3_600_000, 0);
        usage.add(&clock, &moved(4, 4));
        let east = Clock {
            offset_s: 8 * 3600,
            ..clock
        };
        usage.add(&east, &moved(1, 1));
        assert_eq!(kept(&usage), [DAY, DAY + 1]);
        assert_eq!(usage.get(DAY + 1).unwrap().up[24], 1);
        // By 06:00 yesterday had moved nothing: its bytes came at 22:00.
        assert_eq!(usage.today(&east), (1, 1, Some(0)));
    }

    #[test]
    fn yesterday_by_the_same_time() {
        let mut usage = Usage::default();
        // Yesterday: 900 bytes in each of 01:00–01:15 and 10:00–10:15, 1 at 23:59.
        for (h, m, b) in [(1, 0, 900), (10, 0, 900), (23, 59, 1)] {
            let ms = MIDNIGHT_MS - DAY_MS + (h * 3600 + m * 60) * 1000;
            usage.add(&at(ms, 0), &moved(b, 0));
        }
        let ten_oh_five = at(MIDNIGHT_MS + 36_300_000, 0);
        // Before today's first byte, today is empty and yesterday is there.
        assert_eq!(usage.today(&ten_oh_five), (0, 0, Some(900 + 300)));
        usage.add(&ten_oh_five, &moved(7, 3));
        // 10:05: all of 01:00 and a third of 10:00's quarter.
        assert_eq!(usage.today(&ten_oh_five), (7, 3, Some(900 + 300)));
        // The next day at 10:10, with no byte since midnight: today is empty, and yesterday counts
        // two thirds of its 10:00 quarter. Nothing changes in the store.
        let next = at(MIDNIGHT_MS + DAY_MS + 36_600_000, 0);
        assert_eq!(usage.today(&next), (0, 0, Some(10 * 600 / 900)));
        assert_eq!(kept(&usage), [DAY - 1, DAY]);
        // Two days on, no yesterday is kept.
        assert_eq!(usage.today(&at(MIDNIGHT_MS + 3 * DAY_MS, 0)), (0, 0, None));
    }

    #[test]
    fn outbounds_past_the_bound_go_to_other() {
        let mut usage = Usage::default();
        let clock = at(MIDNIGHT_MS, 0);
        let many = Moved {
            outbounds: (0..MAX_OUTBOUNDS + 3)
                .map(|i| (format!("n-{i:03}"), 1, 1))
                .collect(),
            ..Moved::default()
        };
        usage.add(&clock, &many);
        let outbounds = &usage.get(DAY).unwrap().outbounds;
        assert_eq!(outbounds.len(), MAX_OUTBOUNDS + 1);
        assert_eq!(outbounds[OTHER], [3, 3]);
    }

    #[test]
    fn a_damaged_file_is_fixed() {
        let mut day = Day::new(1);
        day.up.truncate(3);
        day.flows.extend([1, 2, 3]);
        let t = |day| Total {
            day,
            up: 1,
            down: 0,
        };
        let read = Usage {
            // Out of order, a date twice, more than the bound.
            full: vec![Day::new(9), day.clone(), Day::new(5), day, Day::new(7)],
            days: vec![t(4), t(0), t(4)],
        }
        .sane();
        assert_eq!(kept(&read), [5, 7, 9]);
        assert_eq!(
            read.days,
            [
                t(0),
                Total {
                    day: 4,
                    up: 2,
                    down: 0
                }
            ]
        );
        let fixed = Usage {
            full: vec![Day {
                up: vec![5],
                ..Day::new(2)
            }],
            ..Usage::default()
        }
        .sane();
        let day = &fixed.full[0];
        assert_eq!(
            (day.up.len(), day.down.len(), day.flows.len(), day.up[0]),
            (SLOTS, SLOTS, HOURS, 5)
        );
    }
}
