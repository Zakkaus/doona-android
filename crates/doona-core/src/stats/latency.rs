//! Each node's last [`KEPT`] latency tests: when the test round began, and the time it measured or
//! none for a probe that failed. A probe result counts once the catalog accepted it (the node and
//! its probe generation still current), so a cancelled or superseded probe leaves no test. The
//! median, the change since the test before and the spread are the screens' arithmetic.

use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, VecDeque};

/// Tests kept per node: the node page's chart shows eight.
pub(crate) const KEPT: usize = 8;

/// One test: the epoch ms its round began, and the milliseconds it measured.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) struct Test(pub i64, pub Option<u32>);

/// A node's tests, oldest first.
pub(crate) type Tests = VecDeque<Test>;

#[derive(Debug, Default)]
pub(crate) struct Latency(BTreeMap<String, Tests>);

impl Latency {
    /// The tests as read from a file, each node's cut to the last [`KEPT`].
    pub fn from_saved(mut saved: BTreeMap<String, Tests>) -> Latency {
        for tests in saved.values_mut() {
            while tests.len() > KEPT {
                tests.pop_front();
            }
        }
        Latency(saved)
    }

    pub fn all(&self) -> &BTreeMap<String, Tests> {
        &self.0
    }

    pub fn get(&self, id: &str) -> Option<&Tests> {
        self.0.get(id)
    }

    /// Adds a test of node `id`, dropping its oldest once it has [`KEPT`].
    pub fn record(&mut self, id: &str, at_ms: i64, ms: Option<u32>) {
        let tests = match self.0.get_mut(id) {
            Some(tests) => tests,
            None => self.0.entry(id.to_owned()).or_default(),
        };
        if tests.len() >= KEPT {
            tests.pop_front();
        }
        tests.push_back(Test(at_ms, ms));
    }

    /// Keeps the nodes `keep` answers yes for; the others left the catalog. Answers whether any
    /// left.
    pub fn retain(&mut self, keep: impl Fn(&str) -> bool) -> bool {
        let before = self.0.len();
        self.0.retain(|id, _| keep(id));
        self.0.len() < before
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_the_last_tests_oldest_first() {
        let mut latency = Latency::default();
        for round in 0..10 {
            let ms = (round % 3 != 2).then_some(30 + round as u32);
            latency.record("n-a", 1000 * i64::from(round), ms);
        }
        let tests: Vec<Test> = latency.get("n-a").unwrap().iter().copied().collect();
        assert_eq!(tests.len(), KEPT);
        assert_eq!(tests[0], Test(2000, None));
        assert_eq!(tests[KEPT - 1], Test(9000, Some(39)));
        assert!(latency.get("n-b").is_none());
    }

    #[test]
    fn nodes_that_left_are_dropped_and_saved_lists_are_cut() {
        let mut latency = Latency::default();
        latency.record("n-a", 1, Some(1));
        latency.record("n-b", 1, None);
        assert!(latency.retain(|id| id == "n-b"));
        assert!(!latency.retain(|id| id == "n-b"));
        assert_eq!(latency.all().keys().collect::<Vec<_>>(), ["n-b"]);
        let long: Tests = (0..20).map(|i| Test(i, Some(5))).collect();
        let saved = Latency::from_saved(BTreeMap::from([("n-c".to_owned(), long)]));
        let kept = saved.get("n-c").unwrap();
        assert_eq!((kept.len(), kept[0].0), (KEPT, 12));
    }
}
