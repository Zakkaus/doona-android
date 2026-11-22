//! The day's usage in the device's local time: today and yesterday each way by the quarter hour,
//! the flows opened each hour, the bytes of each outbound, and the totals of the days before. A
//! local day is `floor((wall time + zone offset) / 24 h)`, so a change of zone or of summer time
//! moves the boundary with it. A later day rolls today over; an earlier one (the clock set back,
//! or a flight west across midnight) keeps adding to the current day, so no day is ever reopened.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

use super::Clock;

/// Quarter hours in a day: yesterday's usage at the same time is exact to a quarter hour.
pub(crate) const SLOTS: usize = 96;
pub(crate) const HOURS: usize = 24;
const SLOT_S: i64 = 900;
const DAY_S: i64 = 86_400;
/// Totals of finished days are kept this many days back, for a month's chart.
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

/// A finished day's totals.
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
    pub today: Option<Day>,
    pub yesterday: Option<Day>,
    /// Finished days with any bytes, oldest first, within [`KEPT_DAYS`] of today.
    #[serde(default)]
    pub days: Vec<Total>,
}

impl Usage {
    /// Today, yesterday and the totals of the days before, as of local day `now`: the stored
    /// today rolls over if `now` is later. Returns whether it rolled.
    pub fn roll(&mut self, now: i64) -> bool {
        let Some(today) = self.today.take_if(|t| t.day < now) else {
            self.today.get_or_insert_with(|| Day::new(now));
            return false;
        };
        let (up, down) = today.total();
        if up + down > 0 {
            self.days.push(Total {
                day: today.day,
                up,
                down,
            });
        }
        self.days
            .retain(|t| t.day >= now - KEPT_DAYS && t.day < now);
        self.yesterday = (today.day == now - 1).then_some(today);
        self.today = Some(Day::new(now));
        true
    }

    /// Adds `moved` at `clock`'s quarter hour. Returns whether the day rolled over first.
    pub fn add(&mut self, clock: &Clock, moved: &Moved) -> bool {
        let (now, slot, _) = local(clock);
        let rolled = self.roll(now);
        let Some(day) = self.today.as_mut() else {
            return rolled;
        };
        day.up[slot] += moved.up;
        day.down[slot] += moved.down;
        day.flows[slot / (SLOTS / HOURS)] += moved.flows;
        for (id, up, down) in &moved.outbounds {
            let full = day.outbounds.len() >= MAX_OUTBOUNDS && !day.outbounds.contains_key(id);
            let key = if full { OTHER } else { id.as_str() };
            let entry = day.outbounds.entry(key.to_owned()).or_default();
            entry[0] += up;
            entry[1] += down;
        }
        rolled
    }

    /// Today's bytes each way, and yesterday's both ways together by the same local time, as of
    /// `clock`: a stored today that is over by then reads as yesterday. Changes nothing.
    pub fn today(&self, clock: &Clock) -> (u64, u64, u64) {
        let (now, slot, into) = local(clock);
        match &self.today {
            Some(t) if t.day >= now => {
                let (up, down) = t.total();
                let yesterday = self.yesterday.as_ref().map_or(0, |y| y.by(slot, into));
                (up, down, yesterday)
            }
            Some(t) if t.day == now - 1 => (0, 0, t.by(slot, into)),
            _ => (0, 0, 0),
        }
    }

    /// The days as of local day `now`, rolled over if it is later; the store is left as it is.
    pub fn view(&self, now: i64) -> Usage {
        let mut view = self.clone();
        view.roll(now);
        view
    }

    /// The days as read from a file, each fixed where it does not fit.
    pub fn sane(self) -> Usage {
        Usage {
            today: self.today.map(Day::sane),
            yesterday: self.yesterday.map(Day::sane),
            days: self.days,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 2026-11-22 00:00 UTC, local day 20779 at offset 0.
    const MIDNIGHT_MS: i64 = 20_779 * 86_400_000;

    fn at(wall_ms: i64, offset_s: i32) -> Clock {
        Clock {
            boot_ms: 0,
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
        assert!(!usage.add(&at(MIDNIGHT_MS + 3_600_000 + 60_000, 0), &moved(10, 100)));
        usage.add(&at(MIDNIGHT_MS + 2 * 3_600_000 - 1, 0), &moved(1, 2));
        let today = usage.today.as_ref().unwrap();
        assert_eq!((today.up[4], today.down[4], today.up[7]), (10, 100, 1));
        assert_eq!(today.flows[1], 2);
        assert_eq!(Day::hours(&today.down)[1], 102);
        assert_eq!(today.outbounds["g-Proxy"], [11, 102]);
        assert_eq!(today.total(), (11, 102));
    }

    #[test]
    fn midnight_rolls_today_into_yesterday_and_the_totals() {
        let mut usage = Usage::default();
        usage.add(&at(MIDNIGHT_MS + 23 * 3_600_000, 0), &moved(5, 50));
        // The first byte after midnight starts a new day.
        assert!(usage.add(&at(MIDNIGHT_MS + 86_400_000, 0), &moved(1, 1)));
        let yesterday = usage.yesterday.as_ref().unwrap();
        assert_eq!((yesterday.day, yesterday.total()), (20_779, (5, 50)));
        assert_eq!(usage.today.as_ref().unwrap().total(), (1, 1));
        assert_eq!(
            usage.days,
            vec![Total {
                day: 20_779,
                up: 5,
                down: 50
            }]
        );
        // Two quiet days later: no yesterday, and the totals keep only days with bytes.
        usage.add(&at(MIDNIGHT_MS + 3 * 86_400_000, 0), &moved(2, 2));
        assert!(usage.yesterday.is_none());
        assert_eq!(
            usage.days.iter().map(|t| t.day).collect::<Vec<_>>(),
            [20_779, 20_780]
        );
        // 31 days after the 22nd's last total, the totals before it are gone.
        usage.add(&at(MIDNIGHT_MS + 34 * 86_400_000, 0), &moved(1, 0));
        assert_eq!(
            usage.days.iter().map(|t| t.day).collect::<Vec<_>>(),
            [20_782]
        );
    }

    #[test]
    fn a_clock_set_back_keeps_the_current_day() {
        let mut usage = Usage::default();
        usage.add(&at(MIDNIGHT_MS + 3_600_000, 0), &moved(1, 1));
        // A day back: nothing rolls, and the bytes stay on today.
        assert!(!usage.add(&at(MIDNIGHT_MS - 3_600_000, 0), &moved(2, 2)));
        assert_eq!(usage.today.as_ref().unwrap().day, 20_779);
        assert_eq!(usage.today.as_ref().unwrap().total(), (3, 3));
        assert!(usage.yesterday.is_none() && usage.days.is_empty());
        // Forward again past midnight, it rolls once.
        assert!(usage.add(&at(MIDNIGHT_MS + 86_400_000 + 1, 0), &moved(0, 0)));
        assert_eq!(usage.yesterday.as_ref().unwrap().total(), (3, 3));
    }

    #[test]
    fn a_time_zone_change_moves_the_boundary() {
        let mut usage = Usage::default();
        // 22:00 UTC on the 22nd at offset 0, then the phone lands in Taipei (+8): 06:00 the 23rd.
        let clock = at(MIDNIGHT_MS + 22 * 3_600_000, 0);
        usage.add(&clock, &moved(4, 4));
        let east = Clock {
            offset_s: 8 * 3600,
            ..clock
        };
        assert!(usage.add(&east, &moved(1, 1)));
        assert_eq!(usage.today.as_ref().unwrap().day, 20_780);
        assert_eq!(usage.today.as_ref().unwrap().up[24], 1);
        // Back west to UTC-5 the same instant (17:00 the 22nd): the 23rd stays today.
        let west = Clock {
            offset_s: -5 * 3600,
            ..clock
        };
        assert!(!usage.add(&west, &moved(1, 1)));
        assert_eq!(usage.today.as_ref().unwrap().total(), (2, 2));
    }

    #[test]
    fn yesterday_by_the_same_time() {
        let mut usage = Usage::default();
        // Yesterday: 900 bytes in each of 01:00–01:15 and 10:00–10:15, 1 at 23:59.
        for (h, m, b) in [(1, 0, 900), (10, 0, 900), (23, 59, 1)] {
            let ms = MIDNIGHT_MS - 86_400_000 + (h * 3600 + m * 60) * 1000;
            usage.add(&at(ms, 0), &moved(b, 0));
        }
        let ten_oh_five = at(MIDNIGHT_MS + 36_300_000, 0);
        usage.add(&ten_oh_five, &moved(7, 3));
        // 10:05: all of 01:00 and a third of 10:00's quarter.
        assert_eq!(usage.today(&ten_oh_five), (7, 3, 900 + 300));
        // The next day at 10:10, with no byte since midnight: today is empty, and yesterday (the
        // stored today) counts two thirds of its 10:00 quarter. The store does not change.
        let next = at(MIDNIGHT_MS + 86_400_000 + 36_600_000, 0);
        assert_eq!(usage.today(&next), (0, 0, 10 * 600 / 900));
        assert_eq!(usage.today.as_ref().unwrap().day, 20_779);
        let view = usage.view(20_780);
        assert_eq!(view.yesterday.unwrap().total(), (7, 3));
        assert_eq!(
            view.days.last().unwrap(),
            &Total {
                day: 20_779,
                up: 7,
                down: 3
            }
        );
        assert_eq!(usage.today(&at(MIDNIGHT_MS + 3 * 86_400_000, 0)), (0, 0, 0));
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
        let outbounds = &usage.today.as_ref().unwrap().outbounds;
        assert_eq!(outbounds.len(), MAX_OUTBOUNDS + 1);
        assert_eq!(outbounds[OTHER], [3, 3]);
    }

    #[test]
    fn a_damaged_day_is_fixed() {
        let mut day = Day::new(1);
        day.up.truncate(3);
        day.flows.extend([1, 2, 3]);
        let fixed = Usage {
            today: Some(day),
            ..Usage::default()
        }
        .sane();
        let day = fixed.today.unwrap();
        assert_eq!(
            (day.up.len(), day.down.len(), day.flows.len()),
            (SLOTS, SLOTS, HOURS)
        );
    }
}
