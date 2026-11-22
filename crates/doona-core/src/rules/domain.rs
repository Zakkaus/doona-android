//! `DOMAIN`, `DOMAIN-SUFFIX` and `DOMAIN-KEYWORD`, one index each: exact names and suffixes in
//! hash maps, a suffix found by walking the name's parents at label boundaries (after honk-core's
//! `routing/domain.rs`), and keywords in one Aho-Corasick automaton. Each maps a value to the
//! first row that holds it, so a lookup costs the same for 20 rules as for 20,000.

use super::NONE;
use crate::dto::ErrorCode;
use aho_corasick::AhoCorasick;
use std::borrow::Cow;
use std::collections::HashMap;

#[derive(Clone, Debug, Default)]
pub(super) struct Index {
    full: HashMap<Box<str>, u32>,
    suffix: HashMap<Box<str>, u32>,
    /// The keywords added, until [`Index::finish`].
    words: Vec<(String, u32)>,
    keywords: Option<Keywords>,
}

#[derive(Clone, Debug)]
struct Keywords {
    automaton: AhoCorasick,
    /// Each pattern's first row.
    rows: Vec<u32>,
    /// The smallest of them: a name is searched only when it could win.
    first: u32,
}

impl Index {
    pub(super) fn add_full(&mut self, value: &str, row: u32) -> Result<(), ErrorCode> {
        let name = rule_name(value)?;
        self.full.entry(name.into()).or_insert(row);
        Ok(())
    }

    pub(super) fn add_suffix(&mut self, value: &str, row: u32) -> Result<(), ErrorCode> {
        let name = rule_name(value)?;
        self.suffix.entry(name.into()).or_insert(row);
        Ok(())
    }

    /// Keywords match in lower case, as names do.
    pub(super) fn add_keyword(&mut self, value: &str, row: u32) -> Result<(), ErrorCode> {
        if value.is_empty() {
            return Err(ErrorCode::ParseFailed);
        }
        self.words.push((value.to_lowercase(), row));
        Ok(())
    }

    /// Builds the keyword automaton, one pattern per distinct keyword.
    pub(super) fn finish(&mut self) -> Result<(), ErrorCode> {
        let mut words = std::mem::take(&mut self.words);
        self.full.shrink_to_fit();
        self.suffix.shrink_to_fit();
        if words.is_empty() {
            return Ok(());
        }
        // Rows ascend within a keyword after a stable sort, so `dedup` keeps the first.
        words.sort_by(|a, b| a.0.cmp(&b.0));
        words.dedup_by(|later, kept| later.0 == kept.0);
        let automaton =
            AhoCorasick::new(words.iter().map(|w| &w.0)).map_err(|_| ErrorCode::ParseFailed)?;
        let rows: Vec<u32> = words.iter().map(|w| w.1).collect();
        let first = rows.iter().copied().min().unwrap_or(NONE);
        self.keywords = Some(Keywords {
            automaton,
            rows,
            first,
        });
        Ok(())
    }

    /// The first row whose value matches `name`, if it comes before `below`; else `below`.
    pub(super) fn first(&self, name: &str, below: u32) -> u32 {
        let name = flow_name(name);
        let name = name.as_ref();
        let mut best = below;
        if let Some(&row) = self.full.get(name) {
            best = best.min(row);
        }
        if !self.suffix.is_empty() {
            let mut parent = name;
            loop {
                if let Some(&row) = self.suffix.get(parent) {
                    best = best.min(row);
                }
                match parent.split_once('.') {
                    Some((_, rest)) => parent = rest,
                    None => break,
                }
            }
        }
        if let Some(k) = self.keywords.as_ref().filter(|k| k.first < best) {
            for m in k.automaton.find_overlapping_iter(name) {
                best = best.min(k.rows[m.pattern().as_usize()]);
            }
        }
        best
    }

    /// The exact names, suffixes and keywords held.
    pub(super) fn len(&self) -> usize {
        self.full.len() + self.suffix.len() + self.keywords.as_ref().map_or(0, |k| k.rows.len())
    }
}

/// A `DOMAIN` or `DOMAIN-SUFFIX` value as it is matched: lower case, without a final dot, and a
/// name outside ASCII in its IDNA form (`中国.cn` is `xn--fiqs8s.cn`), the form DNS asks for. A
/// value that is no name is kept and matches nothing; so does a suffix with a leading dot
/// (`.cn`), since a parent of a name never starts with one.
fn rule_name(value: &str) -> Result<String, ErrorCode> {
    let value = value.strip_suffix('.').unwrap_or(value);
    if value.is_empty() {
        return Err(ErrorCode::ParseFailed);
    }
    if value.is_ascii() {
        return Ok(value.to_ascii_lowercase());
    }
    Ok(match url::Host::parse(value) {
        Ok(url::Host::Domain(name)) => name,
        _ => value.to_lowercase(),
    })
}

/// A flow's name, from a DNS question: ASCII; compared in lower case without a final dot.
fn flow_name(name: &str) -> Cow<'_, str> {
    let name = name.strip_suffix('.').unwrap_or(name);
    if name.bytes().any(|b| b.is_ascii_uppercase()) {
        Cow::Owned(name.to_ascii_lowercase())
    } else {
        Cow::Borrowed(name)
    }
}
