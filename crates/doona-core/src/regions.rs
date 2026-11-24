//! Display locations and the versioned template's subset of the same alias table.

use crate::dto::location::{City, Coordinates, Location};
use aho_corasick::AhoCorasick;
use std::borrow::Cow;
use std::cmp::Reverse;
use std::sync::LazyLock;

mod data;
mod flags;
mod unicode;
use data::REGIONS;

struct Site {
    id: &'static str,
    point: Coordinates,
    aliases: &'static str,
}

struct Region {
    iso: &'static str,
    center: Coordinates,
    aliases: &'static str,
    cities: &'static [Site],
}

const fn city(id: &'static str, lat: f64, lon: f64, aliases: &'static str) -> Site {
    Site {
        id,
        point: Coordinates { lat, lon },
        aliases,
    }
}

const fn region(
    iso: &'static str,
    lat: f64,
    lon: f64,
    aliases: &'static str,
    cities: &'static [Site],
) -> Region {
    Region {
        iso,
        center: Coordinates { lat, lon },
        aliases,
        cities,
    }
}

impl Region {
    fn aliases(&self) -> impl Iterator<Item = (&'static str, Option<&'static Site>)> {
        self.aliases.split('|').map(|key| (key, None)).chain(
            self.cities
                .iter()
                .flat_map(|city| city.aliases.split('|').map(move |key| (key, Some(city)))),
        )
    }
}

struct Alias {
    key: &'static str,
    region: &'static Region,
    city: Option<&'static Site>,
    exact: bool,
    bounded: bool,
}

struct Matcher {
    patterns: AhoCorasick,
    aliases: Vec<Alias>,
}

const CAPITAL_ONLY: &str = "IN IT MY NO ID KZ AT BE LU CZ RO IS CL CO SA PK NG EG KH";

static MATCHER: LazyLock<Matcher> = LazyLock::new(|| {
    let mut aliases = Vec::new();
    for r in REGIONS {
        for (key, city) in std::iter::once((r.iso, None)).chain(r.aliases()) {
            if key.starts_with('!') {
                continue;
            }
            let key = key.trim_start_matches(['~', '#']);
            aliases.push(Alias {
                key,
                region: r,
                city,
                exact: key == r.iso && CAPITAL_ONLY.split_ascii_whitespace().any(|iso| iso == key),
                bounded: key.as_bytes()[0].is_ascii(), // All Han aliases start with Han.
            });
        }
    }
    let patterns = AhoCorasick::builder()
        .ascii_case_insensitive(true)
        .build(aliases.iter().map(|a| a.key))
        .expect("static region aliases");
    Matcher { patterns, aliases }
});

// Non-ASCII simple case folds whose result occurs in a Latin alias. Do not lowercase the whole
// name: capital-only codes are case-sensitive, and e.g. dotted/dotless I do not fold to i in /iu.
fn folded(name: &str) -> Cow<'_, str> {
    fn fold(c: char) -> char {
        match c {
            'ſ' => 's',
            'K' => 'k',
            'Ü' => 'ü',
            'Ã' => 'ã',
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

fn matches(name: &str) -> impl Iterator<Item = (Rank, &'static Alias)> {
    MATCHER
        .patterns
        .find_overlapping_iter(name)
        .filter_map(move |m| {
            let id = m.pattern().as_usize();
            let a = &MATCHER.aliases[id];
            let (start, end) = (m.start(), m.end());
            if a.exact && &name[start..end] != a.key {
                return None;
            }
            if a.bounded
                && (name[..start]
                    .chars()
                    .next_back()
                    .is_some_and(unicode::letter_or_mark)
                    || name[end..]
                        .chars()
                        .next()
                        .is_some_and(unicode::letter_or_mark))
            {
                return None;
            }
            Some(((start, Reverse(end), id), a))
        })
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
        if let Some(r) = REGIONS.iter().find(|r| r.iso.as_bytes() == code) {
            return Some((start, r.iso, r.center));
        }
        if let Ok(i) = flags::FLAG_ONLY.binary_search_by_key(&code.as_slice(), |r| r.0.as_bytes()) {
            let (iso, lat, lon) = flags::FLAG_ONLY[i];
            return Some((start, iso, Coordinates { lat, lon }));
        }
    }
    None
}

pub(crate) fn locate(name: &str) -> Location {
    let name = folded(name);
    let first = matches(&name).min_by_key(|&(rank, _)| rank);
    let flag = flag(&name).filter(|(i, _, _)| first.is_none_or(|(rank, _)| *i < rank.0));
    let (iso, center, mut city) = if let Some((_, iso, center)) = flag {
        (iso, center, None)
    } else if let Some((_, a)) = first {
        (a.region.iso, a.region.center, a.city)
    } else {
        return Location::default();
    };
    // A country code or flag may precede its city; never borrow a city from another region.
    if city.is_none() {
        city = matches(&name)
            .filter(|(_, a)| a.region.iso == iso && a.city.is_some())
            .min_by_key(|&(rank, _)| rank)
            .and_then(|(_, a)| a.city);
    }
    Location {
        region: Some(iso.into()),
        region_centroid: Some(center),
        city: city.map(|s| City {
            id: s.id.into(),
            coordinates: s.point,
        }),
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
        for (key, _) in r.aliases() {
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
