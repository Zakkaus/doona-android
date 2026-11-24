//! Display locations and the versioned template's subset of the same alias table.

use crate::dto::location::{City, CityNames, Coordinates, Location};
use aho_corasick::AhoCorasick;
use std::borrow::Cow;
use std::cmp::Reverse;
use std::sync::LazyLock;

mod cities;
mod data;
mod flags;
mod relay;
mod unicode;
use data::REGIONS;

struct Site {
    iso: &'static str,
    names: [&'static str; 3],
    id: &'static str,
    point: Coordinates,
    aliases: &'static str,
}

struct Region {
    iso: &'static str,
    center: Coordinates,
    aliases: &'static str,
}

const fn region(iso: &'static str, lat: f64, lon: f64, aliases: &'static str) -> Region {
    Region {
        iso,
        center: Coordinates { lat, lon },
        aliases,
    }
}

impl Site {
    fn keys(&self) -> impl Iterator<Item = &'static str> {
        self.names
            .into_iter()
            .chain([self.id])
            .chain(self.aliases.split('|').filter(|s| !s.is_empty()))
    }

    fn dto(&self) -> City {
        let [en, tw, cn] = self.names;
        City {
            id: self.id.into(),
            coordinates: self.point,
            names: Some(CityNames {
                en: en.into(),
                zh_tw: tw.into(),
                zh_cn: cn.into(),
            }),
        }
    }
}

struct Alias {
    key: &'static str,
    iso: &'static str,
    center: Coordinates,
    city: Option<&'static Site>,
    exact: bool,
    bounded: bool,
    code: CodeContext,
    numbered_code: bool,
}

struct Matcher {
    patterns: AhoCorasick,
    aliases: Vec<Alias>,
    max_key_len: usize,
}

const CAPITAL_ONLY: &str = "IN IT MY NO ID KZ AT BE LU CZ RO IS CL CO SA PK NG EG KH";

static MATCHER: LazyLock<Matcher> = LazyLock::new(|| {
    let mut aliases = Vec::new();
    for r in REGIONS {
        for key in std::iter::once(r.iso).chain(r.aliases.split('|')) {
            if key.starts_with('!') {
                continue;
            }
            let key = key.trim_start_matches(['~', '#']);
            aliases.push(Alias {
                key,
                iso: r.iso,
                center: r.center,
                city: None,
                code: CodeContext::Free,
                numbered_code: false,
                exact: key == r.iso && CAPITAL_ONLY.split_ascii_whitespace().any(|iso| iso == key),
                bounded: key.as_bytes()[0].is_ascii(), // All Han aliases start with Han.
            });
        }
    }
    for city in cities::SITES {
        let (_, center) = country(city.iso.as_bytes()).expect("city ISO code");
        let start = aliases.len();
        for raw in city.keys() {
            let key = raw.trim_start_matches(['?', '^', '+']);
            if aliases[start..]
                .iter()
                .any(|a| a.key.eq_ignore_ascii_case(key))
            {
                continue;
            }
            aliases.push(Alias {
                key,
                iso: city.iso,
                center,
                city: Some(city),
                exact: false,
                bounded: true,
                code: CodeContext::for_key(raw, key),
                numbered_code: raw.starts_with('^')
                    || (key.is_ascii()
                        && key.len() <= 4
                        && !key.eq_ignore_ascii_case(city.names[0])),
            });
        }
    }
    let patterns = AhoCorasick::builder()
        .ascii_case_insensitive(true)
        .build(aliases.iter().map(|a| alias_pattern(a.key)))
        .expect("static region aliases");
    let max_key_len = aliases.iter().map(|a| a.key.len()).max().unwrap();
    Matcher {
        patterns,
        aliases,
        max_key_len,
    }
});

fn han(c: char) -> bool {
    ('\u{3400}'..='\u{9fff}').contains(&c)
}

fn alias_pattern(key: &str) -> &str {
    if let Some(c) = key.chars().next().filter(|&c| han(c)) {
        &key[..c.len_utf8()]
    } else {
        key.split_once(' ').map_or(key, |(head, _)| head)
    }
}

fn strip_ascii_case<'a>(text: &'a str, key: &str) -> Option<&'a str> {
    let (head, tail) = text.split_at_checked(key.len())?;
    head.eq_ignore_ascii_case(key).then_some(tail)
}

// Only gaps inside a known alias are flexible. Boundaries use the original text.
fn alias_tail<'a>(key: &str, text: &'a str) -> Option<&'a str> {
    if let Some(tail) = strip_ascii_case(text, key) {
        return Some(tail);
    }
    let mut tail = text;
    if let Some(words) = key.strip_prefix(' ') {
        for word in words.split(' ') {
            tail = strip_ascii_case(tail.trim_start(), word)?;
        }
    } else {
        for c in key.chars() {
            tail = tail.trim_start().strip_prefix(c)?;
        }
    }
    Some(tail)
}

fn candidates(
    name: &str,
    scan: std::ops::Range<usize>,
    wanted: impl Fn(&Alias) -> bool,
) -> impl Iterator<Item = (Rank, &'static Alias)> {
    MATCHER
        .patterns
        .find_overlapping_iter(aho_corasick::Input::new(name).span(scan))
        .filter_map(move |m| {
            let id = m.pattern().as_usize();
            let a = &MATCHER.aliases[id];
            if !wanted(a) {
                return None;
            }
            let mut end = m.end();
            if m.len() != a.key.len() {
                let tail = alias_tail(&a.key[m.len()..], &name[end..])?;
                end = name.len() - tail.len();
            }
            Some(((m.start(), Reverse(end), id), a))
        })
}

// Non-ASCII simple case folds whose result occurs in a Latin alias. Do not lowercase the whole
// name: capital-only codes are case-sensitive, and e.g. dotted/dotless I do not fold to i in /iu.
fn folded(name: &str) -> Cow<'_, str> {
    fn fold(c: char) -> char {
        match c {
            'ſ' => 's',
            'K' => 'k',
            c if !c.is_ascii() && c != 'İ' && c.is_uppercase() => c.to_lowercase().next().unwrap(),
            _ => c,
        }
    }
    if let Some((i, _)) = name.char_indices().find(|&(_, c)| fold(c) != c) {
        let mut text = String::with_capacity(name.len());
        text.push_str(&name[..i]);
        text.extend(name[i..].chars().map(fold));
        Cow::Owned(text)
    } else {
        Cow::Borrowed(name)
    }
}

type Rank = (usize, Reverse<usize>, usize);

// Keep doona's unambiguous existing airport aliases usable in phrases. New short codes are
// labels (SFO, sfo-2), not words to search for in arbitrary prose.
fn code_needs_context(key: &str) -> bool {
    key.len() <= 3
        && key.is_ascii()
        && !matches!(
            key,
            "hkg"
                | "tpe"
                | "nrt"
                | "hnd"
                | "kix"
                | "sjc"
                | "nyc"
                | "icn"
                | "yyz"
                | "lhr"
                | "fra"
                | "cdg"
                | "ams"
                | "syd"
                | "bom"
                | "gru"
                | "kul"
                | "bkk"
                | "dxb"
        )
}

fn numbered(tail: &str) -> bool {
    let digits = tail.trim_start_matches(|c: char| c.is_ascii_digit());
    digits.len() < tail.len() && !digits.chars().next().is_some_and(unicode::letter_or_mark)
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum CodeContext {
    Free,
    Short,
    Risky,
    Numbered,
}

impl CodeContext {
    fn for_key(raw: &str, key: &str) -> Self {
        match raw.as_bytes()[0] {
            b'+' => Self::Numbered,
            b'?' => Self::Risky,
            _ if code_needs_context(key) => Self::Short,
            _ => Self::Free,
        }
    }

    fn accepts(self, name: &str, text: &str, tail: &str) -> bool {
        match self {
            Self::Free => true,
            Self::Numbered => numbered(tail.trim_start_matches(['-', '_'])),
            Self::Risky | Self::Short => {
                let alone = name.trim() == text;
                let upper = text.bytes().all(|b| b.is_ascii_uppercase());
                (if self == Self::Risky {
                    alone && upper
                } else {
                    alone || upper
                }) || numbered(tail.trim_start_matches(['-', '_', ' ']))
            }
        }
    }
}

fn word_before(prefix: &str) -> bool {
    prefix
        .chars()
        .next_back()
        .is_some_and(unicode::letter_or_mark)
}

fn preceding_aliases(
    prefix: &str,
    wanted: impl Fn(&Alias) -> bool,
) -> impl Iterator<Item = (Rank, &'static Alias)> {
    let end = prefix.len();
    let start = end.saturating_sub(MATCHER.max_key_len);
    candidates(prefix, start..end, wanted).filter(move |&((start, Reverse(stop), _), a)| {
        stop == end && end - start == a.key.len() && (!a.exact || &prefix[start..] == a.key)
    })
}

fn mainland_port(prefix: &str) -> Option<(Rank, &'static Alias)> {
    if !prefix.ends_with(han) {
        return None;
    }
    // The suffix 港 identifies a full city even when a provider prefix is glued to it.
    preceding_aliases(prefix, |a| {
        a.iso == "CN" && a.city.is_some() && a.key.starts_with(han) && !a.numbered_code
    })
    .min_by_key(|(rank, _)| *rank)
}

fn spaced_han(a: &Alias, width: usize) -> bool {
    width != a.key.len() && a.key.starts_with(han)
}

fn han_gap_boundaries(prefix: &str, tail: &str) -> bool {
    (!word_before(prefix) || relay::ascii_tag_end(prefix))
        && (!tail.chars().next().is_some_and(unicode::letter_or_mark)
            || relay::ascii_tag_start(tail))
}

fn follows_region(prefix: &str, iso: &str) -> bool {
    preceding_aliases(prefix, |a| a.iso == iso && a.city.is_none())
        .any(|((start, _, _), a)| !a.bounded || !word_before(&prefix[..start]))
}

fn accepts(a: &Alias, name: &str, start: usize, end: usize) -> bool {
    let text = &name[start..end];
    let tail = &name[end..];
    if a.exact && text != a.key {
        return false;
    }
    if !a.code.accepts(name, text, tail) {
        return false;
    }
    if a.key == "港" {
        return relay::hong_kong_tail(tail);
    }
    if spaced_han(a, text.len()) {
        return han_gap_boundaries(&name[..start], tail);
    }
    !a.bounded || accepts_bounded(a, &name[..start], tail)
}

fn accepts_bounded(a: &Alias, prefix: &str, tail: &str) -> bool {
    let han_city = a.city.is_some() && a.key.starts_with(han);
    if word_before(prefix) && !(han_city && follows_region(prefix, a.iso)) {
        return false;
    }
    if a.numbered_code && tail.starts_with(|c: char| c.is_ascii_digit()) {
        return numbered(tail);
    }
    if han_city && (relay::tag_start(tail) || (a.iso == "CN" && tail.starts_with('港'))) {
        return true;
    }
    !tail.chars().next().is_some_and(unicode::letter_or_mark)
}

fn matches(name: &str) -> impl Iterator<Item = (Rank, &'static Alias)> {
    matches_in(name, name.len())
}

fn matches_in(name: &str, end: usize) -> impl Iterator<Item = (Rank, &'static Alias)> {
    candidates(name, 0..end, |_| true).filter_map(move |(rank @ (start, Reverse(stop), _), a)| {
        if !accepts(a, name, start, stop) {
            return None;
        }
        if a.key == "港"
            && let Some(((city_start, _, id), city)) = mainland_port(&name[..start])
        {
            return Some(((city_start, Reverse(stop), id), city));
        }
        Some((rank, a))
    })
}

fn country(code: &[u8]) -> Option<(&'static str, Coordinates)> {
    if let Some(r) = REGIONS.iter().find(|r| r.iso.as_bytes() == code) {
        return Some((r.iso, r.center));
    }
    let i = flags::FLAG_ONLY
        .binary_search_by_key(&code, |r| r.0.as_bytes())
        .ok()?;
    let (iso, lat, lon) = flags::FLAG_ONLY[i];
    Some((iso, Coordinates { lat, lon }))
}

fn flag(name: &str) -> Option<(usize, &'static str, Coordinates)> {
    fn indicator(c: char) -> Option<u8> {
        ('🇦'..='🇿')
            .contains(&c)
            .then(|| b'A' + (c as u32 - '🇦' as u32) as u8)
    }
    let mut chars = name.char_indices().peekable();
    while let Some((start, c)) = chars.next() {
        let Some(first) = indicator(c) else { continue };
        let Some(second) = chars.peek().and_then(|&(_, c)| indicator(c)) else {
            continue;
        };
        chars.next(); // Even an unknown pair consumes both regional indicators.
        let code = [first, second];
        if let Some((iso, center)) = country(&code) {
            return Some((start, iso, center));
        }
    }
    None
}

pub(crate) fn locate(name: &str) -> Location {
    let name = folded(name);
    relay::exit(&name).unwrap_or_else(|| plain_location(&name))
}

fn plain_location(name: &str) -> Location {
    let first = matches(name).min_by_key(|&(rank, _)| rank);
    let flag = flag(name).filter(|(i, _, _)| first.is_none_or(|(rank, _)| *i < rank.0));
    if let Some((_, iso, center)) = flag {
        refine(name, iso, center, None)
    } else if let Some((_, a)) = first {
        refine(name, a.iso, a.center, a.city)
    } else {
        Location::default()
    }
}

fn refine(name: &str, iso: &'static str, center: Coordinates, city: Option<&Site>) -> Location {
    let city = city.or_else(|| {
        matches(name)
            .filter(|(_, a)| a.iso == iso && a.city.is_some())
            .min_by_key(|&(rank, _)| rank)
            .and_then(|(_, a)| a.city)
    });
    Location {
        region: Some(iso.into()),
        region_centroid: Some(center),
        city: city.map(Site::dto),
    }
}

// This is the routing template's order, not the display matcher's leftmost-location rule.
pub(crate) const TEMPLATE: [&str; 6] = ["HK", "TW", "JP", "SG", "US", "KR"];

struct GroupAliases {
    iso: &'static str,
    flag: String,
    words: Vec<&'static str>,
    codes: Vec<&'static str>,
}

static GROUPS: LazyLock<[GroupAliases; 6]> = LazyLock::new(|| {
    TEMPLATE.map(|iso| {
        let r = REGIONS.iter().find(|r| r.iso == iso).unwrap();
        let mut group = GroupAliases {
            iso,
            flag: iso
                .bytes()
                .map(|b| char::from_u32('🇦' as u32 + u32::from(b - b'A')).unwrap())
                .collect(),
            words: Vec::new(),
            codes: vec![iso],
        };
        for key in r.aliases.split('|') {
            let key = key.trim_start_matches('!');
            if let Some(word) = key.strip_prefix('~') {
                group.words.push(word);
            }
            if let Some(code) = key.strip_prefix('#') {
                group.codes.push(code);
            }
        }
        group
    })
});

pub(crate) fn template_region(name: &str) -> Option<&'static str> {
    let lower = name.to_lowercase();
    GROUPS
        .iter()
        .find(|g| {
            lower.contains(&g.flag)
                || g.words.iter().any(|w| lower.contains(w))
                || name.split(|c: char| !c.is_ascii_alphanumeric()).any(|run| {
                    let letters = run.trim_end_matches(|c: char| c.is_ascii_digit());
                    g.codes
                        .iter()
                        .any(|code| letters.eq_ignore_ascii_case(code))
                })
        })
        .map(|g| g.iso)
}

#[cfg(test)]
mod tests;
#[cfg(test)]
mod tests_cities;
