//! Compact Chinese relay labels name the mainland entry first and the overseas exit second.
use super::{
    Location, MATCHER, Site, candidates, country, han, matches, matches_in, strip_ascii_case,
    unicode,
};
use std::cmp::Reverse;

const TRANSIT_TAGS: &[&str] = &[
    "IPLC", "IEPL", "BGP", "CN2", "GIA", "专线", "專線", "中转", "中轉",
];
const NODE_TAGS: &[&str] = &["节点", "節點"];

fn ends_with_tag(text: &str, tag: &str) -> bool {
    text.get(text.len().saturating_sub(tag.len())..)
        .is_some_and(|tail| tail.eq_ignore_ascii_case(tag))
}

pub(super) fn tag_start(text: &str) -> bool {
    TRANSIT_TAGS
        .iter()
        .chain(NODE_TAGS)
        .any(|tag| strip_ascii_case(text, tag).is_some())
}

pub(super) fn left_boundary(prefix: &str) -> bool {
    !prefix
        .chars()
        .next_back()
        .is_some_and(unicode::letter_or_mark)
        || TRANSIT_TAGS
            .iter()
            .chain(NODE_TAGS)
            .any(|tag| ends_with_tag(prefix, tag))
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

pub(super) fn right_boundary(tail: &str) -> bool {
    !tail.chars().next().is_some_and(unicode::letter_or_mark)
        || TRANSIT_TAGS
            .iter()
            .chain(NODE_TAGS)
            .any(|tag| strip_tag(tail, tag).is_some())
}

fn route_after(entry: &str, tail: &str) -> Option<Location> {
    let dest = route_destination(tail);
    if let Some(rest) = dest.strip_prefix('港') {
        // A separator distinguishes a Hong Kong exit from a mainland port.
        let port = dest.len() == tail.len() && entry.chars().nth(1).is_some();
        if port || !right_boundary(rest) {
            return None;
        }
    } else if (dest.len() != tail.len() || dest.starts_with('香'))
        && let Some((_, a)) = matches_in(dest, dest.len().min(MATCHER.patterns.max_pattern_len()))
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
    // Reject invalid compact exits before scanning the suffix for a city.
    if rest.chars().next().is_some_and(unicode::letter_or_mark)
        && !tag_start(rest)
        && !matches_in(rest, rest.len().min(MATCHER.patterns.max_pattern_len()))
            .any(|(rank, a)| rank.0 == 0 && a.iso == iso && a.city.is_some())
    {
        return None;
    }
    let city = matches(rest)
        .filter(|(_, a)| a.iso == iso && a.city.is_some())
        .min_by_key(|(r, _)| *r);
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
    let entries = candidates(name, 0..name.len(), |a| {
        a.iso == "CN" && a.city.is_some() && a.key.starts_with(han)
    });
    for ((start, Reverse(end), _), a) in entries {
        if left_boundary(&name[..start])
            && let Some(exit) = route_after(a.key, &name[end..])
        {
            return Some(exit);
        }
    }
    None
}
