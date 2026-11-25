//! The group template over the nodes of every subscription (core-api.md, Identity and group
//! template), version 2: `Proxy`, a Selector over `Auto`, the region groups and every node; `Auto`,
//! Score over every node; `Streaming` and `AI`, Selectors over `Proxy` and Proxy's members, for
//! the template's rules (`rules/template.rs`); then one Score group per region found in node names.
//! And the selection rules each group follows.

use crate::dto::GroupKind;
use crate::ids::group_id;
use crate::regions::{TEMPLATE, template_region};
use std::collections::BTreeMap;

pub(crate) const PROXY: &str = "Proxy";
pub(crate) const AUTO: &str = "Auto";
pub(crate) const STREAMING: &str = "Streaming";
pub(crate) const AI: &str = "AI";

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct GroupDef {
    pub id: String,
    pub name: String,
    pub kind: GroupKind,
    /// Member ids: groups (`g-`) and nodes (`n-`), in display order.
    pub members: Vec<String>,
}

/// The groups over `nodes` (id and display name, in feed order); none without nodes.
pub(crate) fn template(nodes: &[(&str, &str)]) -> Vec<GroupDef> {
    if nodes.is_empty() {
        return Vec::new();
    }
    let all: Vec<String> = nodes.iter().map(|(id, _)| (*id).to_owned()).collect();
    let tags: Vec<Option<&str>> = nodes
        .iter()
        .map(|(_, name)| template_region(name))
        .collect();
    let regions: Vec<GroupDef> = TEMPLATE
        .into_iter()
        .filter_map(|region| {
            let members: Vec<String> = all
                .iter()
                .zip(&tags)
                .filter(|(_, tag)| **tag == Some(region))
                .map(|(id, _)| id.clone())
                .collect();
            (!members.is_empty()).then(|| score(region, members))
        })
        .collect();
    let mut proxy = vec![group_id(AUTO)];
    proxy.extend(regions.iter().map(|g| g.id.clone()));
    proxy.extend(all.iter().cloned());
    // The rules' groups start with Proxy, so until the user picks they follow its choice.
    let mut ruled = vec![group_id(PROXY)];
    ruled.extend(proxy.iter().cloned());
    let mut groups = vec![
        selector(PROXY, proxy),
        score(AUTO, all),
        selector(STREAMING, ruled.clone()),
        selector(AI, ruled),
    ];
    groups.extend(regions);
    groups
}

fn selector(name: &str, members: Vec<String>) -> GroupDef {
    GroupDef {
        id: group_id(name),
        name: name.to_owned(),
        kind: GroupKind::Selector,
        members,
    }
}

fn score(name: &str, members: Vec<String>) -> GroupDef {
    GroupDef {
        id: group_id(name),
        name: name.to_owned(),
        kind: GroupKind::Score,
        members,
    }
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
        GroupKind::UrlTest | GroupKind::Score => member(auto),
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
            ("TWN", Some("TW")),
            ("Silicon Valley", Some("US")),
            ("🇯🇵 via 🇭🇰", Some("HK")),
            ("TokyoExpress", Some("JP")),
            ("LAX / NYC", None),
        ] {
            assert_eq!(template_region(name), region, "{name}");
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
        assert_eq!(
            ids,
            ["g-Proxy", "g-Auto", "g-Streaming", "g-AI", "g-HK", "g-JP"]
        );
        assert_eq!(groups[0].kind, GroupKind::Selector);
        assert_eq!(
            groups[0].members,
            ["g-Auto", "g-HK", "g-JP", "n-1", "n-2", "n-3", "n-4"]
        );
        assert_eq!(groups[1].members, ["n-1", "n-2", "n-3", "n-4"]);
        // The rules' groups: Selectors whose first member is Proxy, then Proxy's own.
        for ruled in &groups[2..4] {
            assert_eq!(ruled.kind, GroupKind::Selector);
            assert_eq!(ruled.members[0], "g-Proxy");
            assert_eq!(ruled.members[1..], groups[0].members);
            assert_eq!(selected(ruled, None, None), (Some("g-Proxy"), false));
        }
        assert_eq!(groups[4].members, ["n-2", "n-3"]);
        assert!(groups[4..].iter().all(|g| g.kind == GroupKind::Score));
        assert_eq!(groups[1].kind, GroupKind::Score);
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
