//! The rate of the last hour: bytes each way in each second, keyed by the second on the boot clock
//! (`CLOCK_BOOTTIME`), so neither a change of the wall clock nor a stopped tunnel needs any
//! bookkeeping. A bucket remembers its second; one whose second is not the one asked for is from
//! an hour or more ago and reads as zero.

/// One hour of seconds.
pub(crate) const SPAN: u64 = 3600;
/// A series has at most this many points; a longer window takes several seconds per point.
pub(crate) const MAX_POINTS: u64 = 300;

#[derive(Clone, Copy, Debug, Default)]
struct Bucket {
    second: u64,
    up: u64,
    down: u64,
}

pub(crate) struct Ring {
    buckets: Box<[Bucket]>,
    /// The latest second a fold covered.
    latest: Option<u64>,
}

/// The rate over a window: the average bytes per second of each point, oldest first, and the
/// fastest single second of the window each way.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct Series {
    pub step: u64,
    pub up: Vec<u64>,
    pub down: Vec<u64>,
    pub up_peak: u64,
    pub down_peak: u64,
}

impl Ring {
    pub fn new() -> Ring {
        let buckets = vec![Bucket::default(); SPAN as usize].into_boxed_slice();
        Ring {
            buckets,
            latest: None,
        }
    }

    /// The latest second a fold covered, if any.
    pub fn latest(&self) -> Option<u64> {
        self.latest
    }

    /// Adds `up` and `down` bytes moved after second `from` up to second `to`: spread evenly over
    /// those seconds for a fold that comes late; all in `to` when the phone slept in between, as
    /// the counters tell only when the bytes were sampled, not whether they moved before the sleep
    /// or after the wake, so neither the sleep nor the seconds before it get bytes. Seconds more
    /// than an hour before `to` are left out. Returns the most bytes it put in one second, each
    /// way.
    pub fn add(&mut self, from: u64, to: u64, slept: bool, up: u64, down: u64) -> (u64, u64) {
        self.latest = Some(self.latest.map_or(to, |l| l.max(to)));
        if up == 0 && down == 0 {
            return (0, 0);
        }
        let first = if slept {
            to
        } else {
            from.saturating_add(1)
                .clamp(to.saturating_sub(SPAN - 1), to)
        };
        let seconds = to - first + 1;
        let (up_each, up_rest) = (up / seconds, up % seconds);
        let (down_each, down_rest) = (down / seconds, down % seconds);
        for (i, second) in (first..=to).enumerate() {
            // The remainders go to the last seconds, one byte each.
            let late = seconds - i as u64;
            let bucket = self.bucket(second);
            bucket.up += up_each + u64::from(late <= up_rest);
            bucket.down += down_each + u64::from(late <= down_rest);
        }
        (
            up_each + u64::from(up_rest > 0),
            down_each + u64::from(down_rest > 0),
        )
    }

    fn bucket(&mut self, second: u64) -> &mut Bucket {
        let bucket = &mut self.buckets[(second % SPAN) as usize];
        if bucket.second != second {
            *bucket = Bucket {
                second,
                up: 0,
                down: 0,
            };
        }
        bucket
    }

    fn at(&self, second: u64) -> (u64, u64) {
        let b = &self.buckets[(second % SPAN) as usize];
        if b.second == second {
            (b.up, b.down)
        } else {
            (0, 0)
        }
    }

    /// The `seconds` up to and including second `end` (at most an hour), in points of `step`
    /// seconds: one second each up to [`MAX_POINTS`] seconds, more beyond, so a window has at most
    /// [`MAX_POINTS`] points and covers `points × step` seconds ending at `end`.
    pub fn series(&self, end: u64, seconds: u64) -> Series {
        let seconds = seconds.clamp(1, SPAN);
        let step = seconds.div_ceil(MAX_POINTS);
        let points = seconds.div_ceil(step);
        let mut series = Series {
            step,
            up: Vec::with_capacity(points as usize),
            down: Vec::with_capacity(points as usize),
            ..Series::default()
        };
        for p in (0..points).rev() {
            let last = end.checked_sub(p * step);
            let (mut up, mut down) = (0, 0);
            for k in 0..step {
                let Some(second) = last.and_then(|l| l.checked_sub(k)) else {
                    break;
                };
                let (u, d) = self.at(second);
                series.up_peak = series.up_peak.max(u);
                series.down_peak = series.down_peak.max(d);
                up += u;
                down += d;
            }
            series.up.push(up / step);
            series.down.push(down / step);
        }
        series
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_fold_lands_in_its_second_and_reads_back() {
        let mut ring = Ring::new();
        assert_eq!(ring.add(99, 100, false, 7, 900), (7, 900));
        assert_eq!(ring.latest(), Some(100));
        let s = ring.series(100, 60);
        assert_eq!((s.step, s.up.len(), s.down.len()), (1, 60, 60));
        assert_eq!((s.up[59], s.down[59]), (7, 900));
        assert!(s.up[..59].iter().chain(&s.down[..59]).all(|&b| b == 0));
        assert_eq!((s.up_peak, s.down_peak), (7, 900));
    }

    #[test]
    fn a_late_fold_spreads_over_the_seconds_it_covers() {
        let mut ring = Ring::new();
        // Three seconds, 10 bytes: 3, 3, 4 (the remainder to the last).
        assert_eq!(ring.add(10, 13, false, 10, 0), (4, 0));
        let s = ring.series(13, 4);
        assert_eq!(s.up, vec![0, 3, 3, 4]);
        // A fold whose `to` is not after `from` goes to `to`.
        ring.add(13, 13, false, 5, 0);
        assert_eq!(ring.series(13, 1).up, vec![9]);
        // A fold that covers more than an hour keeps only the last hour.
        ring.add(0, 2 * SPAN, false, SPAN * 2, 0);
        let hour = ring.series(2 * SPAN, SPAN);
        assert_eq!(hour.up.iter().sum::<u64>(), SPAN * 2 / hour.step);
    }

    #[test]
    fn a_fold_across_a_suspend_lands_in_its_last_second() {
        let mut ring = Ring::new();
        // The boot clock counted 600 s, the phone slept through most: all in second 700.
        assert_eq!(ring.add(100, 700, true, 0, 1000), (0, 1000));
        assert_eq!(ring.series(700, 1).down, [1000]);
        assert!(ring.series(699, 599).down.iter().all(|&b| b == 0));
        // Awake throughout: the whole span.
        ring.add(700, 703, false, 0, 30);
        assert_eq!(ring.series(703, 3).down, [10, 10, 10]);
    }

    #[test]
    fn seconds_an_hour_old_read_as_zero() {
        let mut ring = Ring::new();
        ring.add(4, 5, false, 1, 1);
        // The same bucket an hour later holds a new second.
        ring.add(SPAN + 4, SPAN + 5, false, 2, 2);
        assert_eq!(ring.series(5, 1).up, vec![0]);
        assert_eq!(ring.series(SPAN + 5, 1).up, vec![2]);
        // Seconds before the boot clock began are zero, not an underflow.
        let early = ring.series(3, 60);
        assert_eq!(early.up.len(), 60);
        assert!(early.up.iter().all(|&b| b == 0));
    }

    #[test]
    fn long_windows_average_several_seconds_per_point() {
        let mut ring = Ring::new();
        let end = 10 * SPAN;
        for s in end - SPAN + 1..=end {
            ring.add(s - 1, s, false, 12, s % 12);
        }
        for (seconds, step, points) in [(60, 1, 60), (300, 1, 300), (900, 3, 300), (3600, 12, 300)]
        {
            let s = ring.series(end, seconds);
            assert_eq!((s.step, s.up.len()), (step, points), "{seconds} s");
            assert!(s.up.iter().all(|&r| r == 12), "{seconds} s");
            assert_eq!((s.up_peak, s.down_peak), (12, 11), "{seconds} s");
        }
        // A window that is not a multiple of its step covers whole points.
        let odd = ring.series(end, 901);
        assert_eq!((odd.step, odd.up.len()), (4, 226));
        assert_eq!(ring.series(end, 0).up.len(), 1);
        assert_eq!(ring.series(end, 99_999).up.len(), 300);
    }
}
