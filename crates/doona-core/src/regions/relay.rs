//! Compact Chinese relay labels name the mainland entry first and the overseas exit second.
use super::{
    Location, MATCHER, Site, candidates, country, han, matches, strip_ascii_case, unicode,
};
use std::cmp::Reverse;

const TRANSIT_TAGS: &[&str] = &[
    "IPLC", "IEPL", "BGP", "CN2", "GIA", "专线", "專線", "中转", "中轉",
];
const NODE_TAGS: &[&str] = &["节点", "節點"];

pub(super) fn tag_start(text: &str) -> bool {
    TRANSIT_TAGS
        .iter()
        .chain(NODE_TAGS)
        .any(|tag| strip_ascii_case(text, tag).is_some())
}

fn left_boundary(prefix: &str) -> bool {
    !prefix
        .chars()
        .next_back()
        .is_some_and(unicode::letter_or_mark)
        || TRANSIT_TAGS.iter().chain(NODE_TAGS).any(|s| {
            prefix
                .get(prefix.len().saturating_sub(s.len())..)
                .is_some_and(|p| p.eq_ignore_ascii_case(s))
        })
}

fn destination(c: char) -> Option<(&'static str, &'static str)> {
    Some(match c {
        '日' => ("JP", "本"),
        '港' => ("HK", ""),
        '德' => ("DE", "國"),
        '美' => ("US", "國"),
        '英' => ("GB", "國"),
        '法' => ("FR", "國"),
        '韩' | '韓' => ("KR", "國"),
        '台' | '臺' => ("TW", "灣"),
        '新' => ("SG", "加坡"),
        '俄' => ("RU", "羅斯"),
        '加' => ("CA", "拿大"),
        _ => return None,
    })
}

fn strip_tag<'a>(text: &'a str, tag: &str) -> Option<&'a str> {
    let rest = strip_ascii_case(text, tag)?;
    let inside_word = tag.is_ascii()
        && rest
            .chars()
            .next()
            .is_some_and(|c| !han(c) && unicode::letter_or_mark(c));
    (!inside_word).then_some(rest)
}

fn route_destination(mut tail: &str) -> &str {
    loop {
        tail = tail.trim_start_matches(|c: char| c.is_whitespace() || "-→>~/|".contains(c));
        let Some(rest) = TRANSIT_TAGS.iter().find_map(|tag| strip_tag(tail, tag)) else {
            return tail;
        };
        tail = rest;
    }
}

pub(super) fn hong_kong_tail(tail: &str) -> bool {
    !tail.chars().next().is_some_and(unicode::letter_or_mark)
        || TRANSIT_TAGS
            .iter()
            .chain(NODE_TAGS)
            .any(|tag| strip_tag(tail, tag).is_some())
}

fn route_after(entry: &str, tail: &str) -> Option<Location> {
    let dest = route_destination(tail);
    if let Some(rest) = dest.strip_prefix('港') {
        // Without a route marker, 港 after a full mainland city name means a port.
        let port = dest.len() == tail.trim_start().len() && entry.chars().nth(1).is_some();
        if port || !hong_kong_tail(rest) {
            return None;
        }
    } else if (dest.len() != tail.len() || dest.starts_with('香'))
        && let Some((_, a)) =
            super::matches_in(dest, dest.len().min(MATCHER.patterns.max_pattern_len()))
                .filter(|(rank, a)| rank.0 == 0 && a.iso != "CN")
                .min_by_key(|(rank, _)| *rank)
    {
        return Some(super::refine(dest, a.iso, a.center, a.city));
    }
    exit_after(dest)
}

fn exit_after(tail: &str) -> Option<Location> {
    let mut chars = tail.chars();
    let (iso, suffix) = destination(chars.next()?)?;
    let rest = chars.as_str();
    let other = match suffix {
        "國" => Some("国"),
        "灣" => Some("湾"),
        "羅斯" => Some("罗斯"),
        _ => None,
    };
    let rest = super::alias_tail(suffix, rest)
        .or_else(|| other.and_then(|key| super::alias_tail(key, rest)))
        .unwrap_or(rest);
    let city = matches(rest)
        .filter(|(_, a)| a.iso == iso && a.city.is_some())
        .min_by_key(|(r, _)| *r);
    if rest.chars().next().is_some_and(unicode::letter_or_mark)
        && !tag_start(rest)
        && !city.is_some_and(|(rank, _)| rank.0 == 0)
    {
        return None;
    }
    let (_, center) = country(iso.as_bytes())?;
    let city = city.and_then(|(_, a)| a.city).or_else(|| match iso {
        "HK" | "SG" => super::cities::SITES.iter().find(|s| s.iso == iso),
        _ => None,
    });
    Some(Location {
        region: Some(iso.into()),
        region_centroid: Some(center),
        city: city.map(Site::dto),
    })
}

pub(super) fn exit(name: &str) -> Option<Location> {
    if !name.contains(han) {
        return None;
    }
    for ((start, Reverse(end), _), a) in candidates(name, 0..name.len()) {
        if a.iso == "CN"
            && a.city.is_some()
            && a.key.starts_with(han)
            && left_boundary(&name[..start])
            && let Some(exit) = route_after(a.key, &name[end..])
        {
            return Some(exit);
        }
    }
    None
}
