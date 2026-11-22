//! `DST-PORT`: the first row whose ports hold a flow's, by binary search over disjoint runs of
//! ports, each labelled with the smallest row whose ranges cover it. One sweep over the ranges
//! builds the runs, so 20,000 ranges cost no more per flow than one does beyond the search.
//! A value is read as Mihomo reads it ([`parse`]).

use super::NONE;
use crate::dto::ErrorCode;
use std::collections::BTreeMap;

/// The most pieces one value may have, as Mihomo counts them, empty ones included
/// (`common/utils/ranges.go:25-28`, Mihomo `100ff73a`).
pub(super) const MAX_PIECES: usize = 28;

#[derive(Clone, Debug, Default)]
pub(super) struct Index {
    /// The ranges added, until [`Index::finish`].
    ranges: Vec<(u16, u16, u32)>,
    /// From each start port to the next run's, the first row there, [`NONE`] for none; by start.
    runs: Vec<(u32, u32)>,
}

impl Index {
    /// Adds the ports `first..=last` for `row`.
    pub(super) fn add(&mut self, first: u16, last: u16, row: u32) {
        self.ranges.push((first, last, row));
    }

    /// Builds the runs from the ranges added.
    pub(super) fn finish(&mut self) {
        let mut edges: Vec<(u32, bool, u32)> = Vec::with_capacity(self.ranges.len() * 2);
        for &(first, last, row) in &self.ranges {
            edges.push((first.into(), true, row));
            edges.push((u32::from(last) + 1, false, row));
        }
        self.ranges = Vec::new();
        edges.sort_unstable();
        // The rows whose ranges cover the sweep's position, with how many of their ranges do.
        let mut open: BTreeMap<u32, u32> = BTreeMap::new();
        let mut runs: Vec<(u32, u32)> = Vec::new();
        let mut i = 0;
        while i < edges.len() {
            let at = edges[i].0;
            while i < edges.len() && edges[i].0 == at {
                let (_, opens, row) = edges[i];
                if opens {
                    *open.entry(row).or_default() += 1;
                } else if let Some(n) = open.get_mut(&row) {
                    *n -= 1;
                    if *n == 0 {
                        open.remove(&row);
                    }
                }
                i += 1;
            }
            let first = open.keys().next().copied().unwrap_or(NONE);
            if runs.last().map(|r| r.1) != Some(first) {
                runs.push((at, first));
            }
        }
        runs.shrink_to_fit();
        self.runs = runs;
    }

    /// The first row whose ranges hold `port`, [`NONE`] if none does.
    pub(super) fn first(&self, port: u16) -> u32 {
        let after = self.runs.partition_point(|r| r.0 <= u32::from(port));
        match after {
            0 => NONE,
            _ => self.runs[after - 1].1,
        }
    }

    pub(super) fn runs(&self) -> usize {
        self.runs.len()
    }
}

/// The pieces of a `DST-PORT` value that are ranges, as `utils.NewUnsignedRanges` splits it
/// (`common/utils/ranges.go:18-24,36-38`): the value trimmed, cut at `/` and at `,`, and the empty
/// pieces dropped. The limits count these, and [`parse`] reads them.
pub(super) fn pieces(value: &str) -> impl Iterator<Item = &str> {
    value.trim().split(['/', ',']).filter(|p| !p.is_empty())
}

/// Calls `each` with every range of a `DST-PORT` value; `parse_failed` where Mihomo refuses the
/// value: no range in it (`rules/common/port.go:42-49`, `*` reads as none at `ranges.go:19-20`),
/// more than [`MAX_PIECES`], or a piece that does not parse.
pub(super) fn parse(value: &str, mut each: impl FnMut(u16, u16)) -> Result<(), ErrorCode> {
    let refused = Err(ErrorCode::ParseFailed);
    let value = value.trim();
    if value == "*" || value.split(['/', ',']).count() > MAX_PIECES {
        return refused;
    }
    let mut any = false;
    for piece in pieces(value) {
        let (first, last) = range(piece)?;
        each(first, last);
        any = true;
    }
    if any { Ok(()) } else { refused }
}

/// One piece (`common/utils/range.go:16-28,65-87`): spaces only are the range 0-0; else one port
/// or two joined by `-`, a reversed pair turned around.
fn range(piece: &str) -> Result<(u16, u16), ErrorCode> {
    let piece = piece.trim();
    if piece.is_empty() {
        return Ok((0, 0));
    }
    let (a, b) = match piece.split_once('-') {
        None => (piece, piece),
        Some((_, b)) if b.contains('-') => return Err(ErrorCode::ParseFailed),
        Some(ends) => ends,
    };
    let (a, b) = (port(a)?, port(b)?);
    Ok((a.min(b), a.max(b)))
}

/// A port as `strconv.ParseUint` reads it once `[`, ` ` and `]` are trimmed from its ends
/// (`range.go:71,79,89-95`): decimal digits, no sign. A number past 65,535 is refused, where
/// Mihomo keeps its low 16 bits (`T(val)`, `range.go:91`) and so matches a port the profile never
/// named.
fn port(s: &str) -> Result<u16, ErrorCode> {
    let s = s.trim_matches(['[', ' ', ']']);
    if s.is_empty() || !s.bytes().all(|b| b.is_ascii_digit()) {
        return Err(ErrorCode::ParseFailed);
    }
    s.parse().map_err(|_| ErrorCode::ParseFailed)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rules::tests::Rng;

    #[test]
    fn runs_match_a_brute_force_oracle() {
        let mut rng = Rng(0x5eed_9047);
        for _ in 0..30 {
            let count = 1 + rng.below(60) as usize;
            let ranges: Vec<(u16, u16, u32)> = (0..count)
                .map(|row| {
                    let a = rng.below(2_000) as u16;
                    let b = a.saturating_add(rng.below(400) as u16);
                    (a, b, row as u32)
                })
                .collect();
            let mut index = Index::default();
            for &(a, b, row) in &ranges {
                index.add(a, b, row);
            }
            index.finish();
            for port in 0..2_500u16 {
                let want = ranges
                    .iter()
                    .filter(|r| r.0 <= port && port <= r.1)
                    .map(|r| r.2)
                    .min()
                    .unwrap_or(NONE);
                assert_eq!(index.first(port), want, "port {port}");
            }
        }
    }

    #[test]
    fn the_last_port_and_an_empty_index() {
        let mut index = Index::default();
        index.finish();
        assert_eq!(index.first(443), NONE);
        index.add(65_535, 65_535, 4);
        index.add(0, 65_535, 9);
        index.finish();
        assert_eq!((index.first(0), index.first(65_535)), (9, 4));
    }
}
