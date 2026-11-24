//! Compact Chinese relay labels name the mainland entry first and the overseas exit second.
use super::{Location, MATCHER, Site, country, matches, unicode};

const TAGS: &[&str] = &[
    "IPLC", "IEPL", "BGP", "专线", "專線", "节点", "節點", "中转", "中轉",
];

pub(super) fn tag_start(text: &str) -> bool {
    TAGS.iter().any(|s| {
        text.get(..s.len())
            .is_some_and(|p| p.eq_ignore_ascii_case(s))
    })
}

fn left_boundary(prefix: &str) -> bool {
    !prefix
        .chars()
        .next_back()
        .is_some_and(unicode::letter_or_mark)
        || TAGS.iter().any(|s| {
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

fn route_after(entry: &str, tail: &str) -> Option<Location> {
    let dest = tail.trim_start_matches(|c: char| c.is_whitespace() || "-→>~/".contains(c));
    if let Some(rest) = dest.strip_prefix('港') {
        // After a full, adjoining city name this character means port, unless the label
        // supplies explicit route evidence. The compact pair 深港 is unambiguous.
        if dest.len() == tail.len()
            && entry.chars().nth(1).is_some()
            && !tag_start(rest.trim_start())
            && !super::numbered(rest)
        {
            return None;
        }
        let suffix = rest.trim_start_matches(['-', '_', ' ']);
        if !(rest.is_empty() || tag_start(suffix) || super::numbered(suffix)) {
            return None;
        }
    } else if (dest.len() != tail.len() || dest.starts_with("香港"))
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
        "國" => "国",
        "灣" => "湾",
        "羅斯" => "罗斯",
        s => s,
    };
    let rest = rest
        .strip_prefix(suffix)
        .or_else(|| rest.strip_prefix(other))
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
    if !name.chars().any(|c| ('\u{3400}'..='\u{9fff}').contains(&c)) {
        return None;
    }
    for m in MATCHER.patterns.find_overlapping_iter(name) {
        let a = &MATCHER.aliases[m.pattern().as_usize()];
        if a.iso == "CN"
            && a.city.is_some()
            && !a.key.is_ascii()
            && left_boundary(&name[..m.start()])
            && let Some(exit) = route_after(a.key, &name[m.end()..])
        {
            return Some(exit);
        }
    }
    None
}
