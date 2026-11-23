//! The day's usage: dates, local days, the bounds and the fixes of a damaged file.

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
        rules: vec![("r-match".into(), [up, down, 1])],
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
    assert_eq!(today.rules["r-match"], [11, 102, 2]);
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
    assert_eq!(usage.get(today).unwrap().rules["r-match"], [10, 10, 3]);
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
fn rules_past_the_bound_go_to_other() {
    let mut usage = Usage::default();
    let many = Moved {
        rules: (0..MAX_RULES + 3)
            .map(|i| (format!("r-{i:016x}"), [1, 2, 1]))
            .collect(),
        ..Moved::default()
    };
    usage.add(&at(MIDNIGHT_MS, 0), &many);
    let rules = &usage.get(DAY).unwrap().rules;
    assert_eq!(rules.len(), MAX_RULES + 1);
    assert_eq!(rules[OTHER], [3, 6, 3]);
    // A rule kept apart still counts apart.
    usage.add(&at(MIDNIGHT_MS, 0), &many);
    let rules = &usage.get(DAY).unwrap().rules;
    assert_eq!(rules[&format!("r-{:016x}", 0)], [2, 4, 2]);
    assert_eq!(rules[OTHER], [6, 12, 6]);
}

/// A day at its bounds, with `other`, loads as it was written; one past them from a damaged file
/// moves the rest to `other`.
#[test]
fn a_day_at_its_bounds_loads_as_written() {
    let mut usage = Usage::default();
    let many = Moved {
        outbounds: (0..MAX_OUTBOUNDS + 1)
            .map(|i| (format!("n-{i:03}"), 1, 1))
            .collect(),
        rules: (0..MAX_RULES + 1)
            .map(|i| (format!("r-{i:016x}"), [1, 1, 1]))
            .collect(),
        ..Moved::default()
    };
    usage.add(&at(MIDNIGHT_MS, 0), &many);
    assert_eq!(usage.clone().sane(), usage);
    let mut damaged = usage.full[0].clone();
    damaged.outbounds.insert("n-999".into(), [5, 5]);
    damaged.rules.insert("r-zzz".into(), [5, 5, 5]);
    let read = Usage {
        full: vec![damaged],
        ..Usage::default()
    }
    .sane();
    let day = &read.full[0];
    assert_eq!((day.outbounds.len(), day.rules.len()), (65, 65));
    assert_eq!(day.outbounds[OTHER], [6, 6]);
    assert_eq!(day.rules[OTHER], [6, 6, 6]);
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

/// A day from a file whose counts pass `u64::MAX` once added up, by ids folded into `other`, by its
/// quarters, or with a day or a total of the same date, is dropped: no panic, and no count that
/// wrapped.
#[test]
fn a_day_whose_counts_overflow_when_added_is_dropped() {
    let mut rules = Day::new(3);
    rules.rules.insert("bad".into(), [u64::MAX, 0, 0]);
    rules.rules.insert(OTHER.into(), [1, 0, 0]);
    let mut outbounds = Day::new(4);
    for i in 0..MAX_OUTBOUNDS + 2 {
        outbounds
            .outbounds
            .insert(format!("n-{i:03}"), [u64::MAX / 2 + 1, 0]);
    }
    let mut quarters = Day::new(5);
    quarters.up[0] = u64::MAX;
    quarters.down[1] = 1;
    let mut fine = Day::new(6);
    fine.up[0] = 7;
    fine.rules.insert("bad".into(), [1, 0, 0]);
    fine.rules.insert(OTHER.into(), [1, 0, 0]);
    let mut again = Day::new(6);
    again.down[0] = u64::MAX;
    let t = |up| Total {
        day: 1,
        up,
        down: 0,
    };
    let read = Usage {
        full: vec![rules, outbounds, quarters, fine, again],
        days: vec![t(u64::MAX), t(1)],
    }
    .sane();
    assert_eq!(kept(&read), [6]);
    assert_eq!(read.full[0].rules[OTHER], [2, 0, 0]);
    assert_eq!((read.full[0].up[0], read.full[0].down[0]), (7, 0));
    assert_eq!(read.days, [t(u64::MAX)]);
}
