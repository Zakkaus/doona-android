//! The group template over the nodes of every subscription (core-api.md, Identity and group
//! template): `Proxy`, a Selector over `Auto`, the region groups and every node; `Auto`, a UrlTest
//! over every node; then one UrlTest per region found in node names. And the selection rules each
//! group follows.

use crate::dto::GroupKind;
use crate::ids::group_id;
use std::collections::BTreeMap;

pub(crate) const PROXY: &str = "Proxy";
pub(crate) const AUTO: &str = "Auto";

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct GroupDef {
    pub id: String,
    pub name: String,
    pub kind: GroupKind,
    /// Member ids: groups (`g-`) and nodes (`n-`), in display order.
    pub members: Vec<String>,
}

/// How a region shows in node names: its codes as words (`HK`, `hk01`), its flag, and its
/// names in Chinese and English (compared in lower case).
struct Region {
    name: &'static str,
    codes: &'static [&'static str],
    flag: &'static str,
    words: &'static [&'static str],
}

/// The regions with a group, in template order. Names matching none get no region group.
const REGIONS: [Region; 6] = [
    Region {
        name: "HK",
        codes: &["HK", "HKG"],
        flag: "🇭🇰",
        words: &["香港", "hong kong", "hongkong"],
    },
    Region {
        name: "TW",
        codes: &["TW", "TWN"],
        flag: "🇹🇼",
        words: &["台湾", "臺灣", "台灣", "台北", "taiwan", "taipei"],
    },
    Region {
        name: "JP",
        codes: &["JP", "JPN"],
        flag: "🇯🇵",
        words: &["日本", "东京", "東京", "大阪", "japan", "tokyo", "osaka"],
    },
    Region {
        name: "SG",
        codes: &["SG", "SGP"],
        flag: "🇸🇬",
        words: &["新加坡", "狮城", "獅城", "singapore"],
    },
    Region {
        name: "US",
        codes: &["US", "USA"],
        flag: "🇺🇸",
        words: &[
            "美国",
            "美國",
            "洛杉矶",
            "洛杉磯",
            "硅谷",
            "西雅图",
            "united states",
            "los angeles",
            "seattle",
            "silicon valley",
        ],
    },
    Region {
        name: "KR",
        codes: &["KR", "KOR"],
        flag: "🇰🇷",
        words: &["韩国", "韓國", "首尔", "首爾", "korea", "seoul"],
    },
];

/// The groups over `nodes` (id and display name, in feed order); none without nodes.
pub(crate) fn template(nodes: &[(&str, &str)]) -> Vec<GroupDef> {
    if nodes.is_empty() {
        return Vec::new();
    }
    let all: Vec<String> = nodes.iter().map(|(id, _)| (*id).to_owned()).collect();
    let tags: Vec<Option<&str>> = nodes.iter().map(|(_, name)| region_of(name)).collect();
    let regions: Vec<GroupDef> = REGIONS
        .iter()
        .filter_map(|region| {
            let members: Vec<String> = all
                .iter()
                .zip(&tags)
                .filter(|(_, tag)| **tag == Some(region.name))
                .map(|(id, _)| id.clone())
                .collect();
            (!members.is_empty()).then(|| url_test(region.name, members))
        })
        .collect();
    let mut proxy = vec![group_id(AUTO)];
    proxy.extend(regions.iter().map(|g| g.id.clone()));
    proxy.extend(all.iter().cloned());
    let mut groups = vec![
        GroupDef {
            id: group_id(PROXY),
            name: PROXY.to_owned(),
            kind: GroupKind::Selector,
            members: proxy,
        },
        url_test(AUTO, all),
    ];
    groups.extend(regions);
    groups
}

fn url_test(name: &str, members: Vec<String>) -> GroupDef {
    GroupDef {
        id: group_id(name),
        name: name.to_owned(),
        kind: GroupKind::UrlTest,
        members,
    }
}

/// The first region, in template order, that `name` mentions.
fn region_of(name: &str) -> Option<&'static str> {
    let lower = name.to_lowercase();
    let codes = codes(name);
    REGIONS
        .iter()
        .find(|r| {
            lower.contains(r.flag)
                || r.words.iter().any(|w| lower.contains(w))
                || codes.iter().any(|c| r.codes.contains(&c.as_str()))
        })
        .map(|r| r.name)
}

/// The letters of each ASCII alphanumeric run made of letters then digits (`HK`, `hk01`, `JP2`),
/// upper-cased. Runs that mix them otherwise (`4K`, `v2ray`) are not codes, and a code inside a
/// longer word (`Plus`) is not a run of its own.
fn codes(name: &str) -> Vec<String> {
    name.split(|c: char| !c.is_ascii_alphanumeric())
        .filter_map(|run| {
            let letters = run.trim_end_matches(|c: char| c.is_ascii_digit());
            (!letters.is_empty() && letters.bytes().all(|b| b.is_ascii_alphabetic()))
                .then(|| letters.to_ascii_uppercase())
        })
        .collect()
}

/// The member `group` sends traffic to, and whether the user chose it: a pin that is still a
/// member wins; otherwise a UrlTest carries its automatic choice, and a Selector, or a UrlTest
/// before any probe, its first member.
pub(crate) fn selected<'a>(
    group: &'a GroupDef,
    pin: Option<&'a String>,
    auto: Option<&'a String>,
) -> (Option<&'a str>, bool) {
    let member =
        |m: Option<&'a String>| m.filter(|m| group.members.contains(m)).map(String::as_str);
    if let Some(pinned) = member(pin) {
        return (Some(pinned), true);
    }
    let own = match group.kind {
        GroupKind::UrlTest => member(auto),
        GroupKind::Selector => None,
    };
    (own.or(group.members.first().map(String::as_str)), false)
}

/// The member with the lowest latency, the first of equals; `None` when no member has one, so
/// the group keeps its current choice.
pub(crate) fn fastest(group: &GroupDef, latency: impl Fn(&str) -> Option<u32>) -> Option<&String> {
    group
        .members
        .iter()
        .filter_map(|m| latency(m).map(|ms| (ms, m)))
        .min_by_key(|(ms, _)| *ms)
        .map(|(_, m)| m)
}

/// Drops the choices whose group or member is gone, so those groups return to their own choice.
pub(crate) fn prune(selections: &mut BTreeMap<String, String>, groups: &[GroupDef]) {
    selections.retain(|group, member| {
        groups
            .iter()
            .any(|g| &g.id == group && g.members.contains(member))
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn regions_are_found_by_code_flag_and_name() {
        for (name, region) in [
            ("🇭🇰 香港 01", Some("HK")),
            ("HK-02 IPLC", Some("HK")),
            ("hk03", Some("HK")),
            ("Hong Kong 4", Some("HK")),
            ("台湾 TW01", Some("TW")),
            ("🇯🇵 Tokyo", Some("JP")),
            ("JP2", Some("JP")),
            ("SGP 新加坡", Some("SG")),
            ("美国 洛杉矶", Some("US")),
            ("US-West", Some("US")),
            ("🇰🇷 Seoul", Some("KR")),
            ("Plus 4K", None),
            ("v2ray relay", None),
            ("Remaining: 10 GB", None),
            ("🇩🇪 Frankfurt", None),
        ] {
            assert_eq!(region_of(name), region, "{name}");
        }
    }

    #[test]
    fn the_template_puts_proxy_auto_then_regions_in_order() {
        let nodes = [
            ("n-1", "JP 01"),
            ("n-2", "hk-01"),
            ("n-3", "hk-01"),
            ("n-4", "Other"),
        ];
        let groups = template(&nodes);
        let ids: Vec<&str> = groups.iter().map(|g| g.id.as_str()).collect();
        assert_eq!(ids, ["g-Proxy", "g-Auto", "g-HK", "g-JP"]);
        assert_eq!(groups[0].kind, GroupKind::Selector);
        assert_eq!(
            groups[0].members,
            ["g-Auto", "g-HK", "g-JP", "n-1", "n-2", "n-3", "n-4"]
        );
        assert_eq!(groups[1].members, ["n-1", "n-2", "n-3", "n-4"]);
        assert_eq!(groups[2].members, ["n-2", "n-3"]);
        assert!(groups[1..].iter().all(|g| g.kind == GroupKind::UrlTest));
        assert!(template(&[]).is_empty());
    }

    #[test]
    fn selection_follows_pins_then_the_group_s_own_choice() {
        let groups = template(&[("n-1", "a"), ("n-2", "b")]);
        let (proxy, auto) = (&groups[0], &groups[1]);
        let n2 = "n-2".to_owned();
        let gone = "n-9".to_owned();
        assert_eq!(selected(proxy, None, None), (Some("g-Auto"), false));
        assert_eq!(selected(proxy, Some(&n2), None), (Some("n-2"), true));
        assert_eq!(selected(proxy, Some(&gone), None), (Some("g-Auto"), false));
        assert_eq!(selected(auto, None, None), (Some("n-1"), false));
        assert_eq!(selected(auto, None, Some(&n2)), (Some("n-2"), false));
        assert_eq!(selected(auto, None, Some(&gone)), (Some("n-1"), false));
    }

    #[test]
    fn the_fastest_member_wins_and_none_without_results() {
        let groups = template(&[("n-1", "a"), ("n-2", "b"), ("n-3", "c")]);
        let auto = &groups[1];
        let ms = |m: &str| match m {
            "n-2" | "n-3" => Some(40),
            _ => None,
        };
        assert_eq!(fastest(auto, ms).map(String::as_str), Some("n-2"));
        assert_eq!(fastest(auto, |_| None), None);
    }

    #[test]
    fn prune_drops_choices_whose_group_or_member_is_gone() {
        let groups = template(&[("n-1", "HK 1")]);
        let mut selections = BTreeMap::from([
            ("g-Proxy".to_owned(), "n-1".to_owned()),
            ("g-Auto".to_owned(), "n-2".to_owned()),
            ("g-JP".to_owned(), "n-1".to_owned()),
            ("g-HK".to_owned(), "n-1".to_owned()),
        ]);
        prune(&mut selections, &groups);
        let kept: Vec<&str> = selections.keys().map(String::as_str).collect();
        assert_eq!(kept, ["g-HK", "g-Proxy"]);
    }
}
