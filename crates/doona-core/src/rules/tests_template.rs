//! The template's rows and the policy over them (rule-mode.md, R2).

use super::policy::{Choice, Policy};
use super::template::{self, AI, Options, STREAMING};
use super::*;
use crate::catalog::MAX_NODES;
use crate::groups;
use std::collections::HashSet;
use std::net::Ipv4Addr;

/// The template's groups over `n` nodes that no region claims, and the policy over them.
fn policy(n: usize, options: Options) -> (Vec<GroupDef>, Policy) {
    let ids: Vec<String> = (0..n).map(|i| format!("n-{i:016x}")).collect();
    let named: Vec<(&str, &str)> = ids.iter().map(|id| (id.as_str(), "node")).collect();
    let groups = groups::template(&named);
    let known: HashSet<&str> = ids.iter().map(String::as_str).collect();
    let policy = Policy::template(&groups, &|id| known.contains(id), options);
    (groups, policy)
}

fn flow(domain: Option<&str>, ip: [u8; 4], port: u16, network: Network) -> Flow<'_> {
    Flow {
        domain,
        ip: IpAddr::V4(Ipv4Addr::from(ip)),
        port,
        network,
    }
}

/// An address outside China and the private ranges.
const ABROAD: [u8; 4] = [104, 16, 0, 1];
/// One of China's (AliDNS).
const CN: [u8; 4] = [223, 5, 5, 5];

#[test]
fn the_template_has_its_five_rows_in_order() {
    let rows = template::rules();
    let shape: Vec<(Kind, usize, Target)> = rows
        .iter()
        .map(|r| (r.kind.clone(), r.values.len(), r.target.clone()))
        .collect();
    let group = |id: &str| Target::Group(id.to_owned());
    assert_eq!(
        shape,
        [
            (Kind::GeoIp, 1, Target::Direct),
            (Kind::DomainSuffix, STREAMING.len(), group("g-Streaming")),
            (Kind::DomainSuffix, AI.len(), group("g-AI")),
            (Kind::GeoIp, 1, Target::Direct),
            (Kind::Match, 0, group("g-Proxy")),
        ]
    );
    assert_eq!(
        (rows[0].values[0].as_str(), rows[3].values[0].as_str()),
        ("private", "CN")
    );
    // Each name is already in the form it is matched in, and none is listed twice.
    let names: Vec<&str> = STREAMING.iter().chain(&AI).copied().collect();
    assert!(names.iter().all(|n| {
        n.bytes()
            .all(|b| b.is_ascii_lowercase() || b"-.".contains(&b) || b.is_ascii_digit())
    }));
    assert_eq!(names.iter().collect::<HashSet<_>>().len(), names.len());
    assert_eq!((template::ID, template::VERSION), ("cn-direct", 2));
}

/// The R1 review's case: a full catalog of nodes no region claims gives `Proxy` one member more
/// than an imported group may have, and the template still compiles.
#[test]
fn the_template_compiles_over_a_full_catalog() {
    let (groups, policy) = policy(MAX_NODES, Options::default());
    assert_eq!(groups[0].members.len(), MAX_NODES + 1);
    assert_eq!(groups[2].members.len(), MAX_NODES + 2);
    assert!(policy.wants_names());
    assert_eq!(policy.main(), "g-Proxy");
    let named = flow(Some("www.netflix.com"), ABROAD, 443, Network::Tcp);
    assert_eq!(policy.choose(&named), Choice::Group("g-Streaming"));
}

#[test]
fn the_first_matching_row_decides_and_the_rest_take_the_main_group() {
    let (_, p) = policy(3, Options::default());
    let tcp = |domain, ip| p.choose(&flow(domain, ip, 443, Network::Tcp));
    assert_eq!(
        tcp(Some("netflix.com"), ABROAD),
        Choice::Group("g-Streaming")
    );
    assert_eq!(
        tcp(Some("ipv4-c001.oca.nflxvideo.net"), ABROAD),
        Choice::Group("g-Streaming")
    );
    assert_eq!(tcp(Some("chatgpt.com"), ABROAD), Choice::Group("g-AI"));
    assert_eq!(
        tcp(Some("api.anthropic.com"), ABROAD),
        Choice::Group("g-AI")
    );
    assert_eq!(
        tcp(Some("notnetflix.com"), ABROAD),
        Choice::Group("g-Proxy")
    );
    assert_eq!(tcp(Some("example.org"), ABROAD), Choice::Group("g-Proxy"));
    // Without a name (strict Private DNS, an app's own DoH), the address decides.
    assert_eq!(tcp(None, ABROAD), Choice::Group("g-Proxy"));
    assert_eq!(tcp(None, CN), Choice::Direct);
    assert_eq!(tcp(None, [192, 168, 1, 2]), Choice::Direct);
    assert_eq!(tcp(None, [100, 64, 0, 1]), Choice::Direct);
    // Private comes first; the lists come before China's addresses.
    assert_eq!(tcp(Some("netflix.com"), [10, 0, 0, 1]), Choice::Direct);
    assert_eq!(tcp(Some("netflix.com"), CN), Choice::Group("g-Streaming"));
    assert_eq!(tcp(Some("baidu.com"), CN), Choice::Direct);
}

#[test]
fn block_quic_rejects_quic_bound_for_a_group_and_keeps_direct_quic() {
    let (_, on) = policy(3, Options { block_quic: true });
    let (_, off) = policy(3, Options::default());
    let quic = |domain, ip| flow(domain, ip, 443, Network::Udp);
    for (domain, ip) in [
        (Some("youtube.com"), ABROAD),
        (Some("openai.com"), ABROAD),
        (None, ABROAD),
    ] {
        assert_eq!(on.choose(&quic(domain, ip)), Choice::Blocked, "{domain:?}");
        assert!(
            matches!(off.choose(&quic(domain, ip)), Choice::Group(_)),
            "{domain:?}"
        );
    }
    assert_eq!(on.choose(&quic(None, CN)), Choice::Direct);
    assert_eq!(on.choose(&quic(None, [192, 168, 1, 2])), Choice::Direct);
    // Only UDP to 443.
    let tcp = flow(Some("youtube.com"), ABROAD, 443, Network::Tcp);
    assert_eq!(on.choose(&tcp), Choice::Group("g-Streaming"));
    let dns = flow(None, ABROAD, 53, Network::Udp);
    assert_eq!(on.choose(&dns), Choice::Group("g-Proxy"));
}

/// With no node the catalog has no group, and the rules still apply: private and China's addresses
/// go direct, and the rest names the group a rule gives, which then has no node to take it.
#[test]
fn without_nodes_the_rules_still_apply() {
    let (groups, p) = policy(0, Options::default());
    assert!(groups.is_empty());
    assert!(p.wants_names());
    let tcp = |domain, ip| p.choose(&flow(domain, ip, 443, Network::Tcp));
    assert_eq!(tcp(None, [192, 168, 1, 1]), Choice::Direct);
    assert_eq!(tcp(None, CN), Choice::Direct);
    assert_eq!(
        tcp(Some("www.netflix.com"), ABROAD),
        Choice::Group("g-Streaming")
    );
    assert_eq!(tcp(Some("claude.ai"), ABROAD), Choice::Group("g-AI"));
    assert_eq!(tcp(None, ABROAD), Choice::Group("g-Proxy"));
}

#[test]
fn a_rule_may_reject_or_name_a_group_and_the_policy_says_so() {
    let groups = [tests::group("Proxy", &["n-1"]), tests::group("Empty", &[])];
    let rules = [
        tests::rule("DOMAIN-SUFFIX", &["ads.example"], "REJECT"),
        tests::rule("DOMAIN-SUFFIX", &["empty.example"], "Empty"),
    ];
    let set = RuleSet::compile(&groups, &|id| id == "n-1", &rules).unwrap();
    let p = Policy::new("g-Proxy", Some(set), Options::default());
    let tcp = |domain| p.choose(&flow(Some(domain), ABROAD, 443, Network::Tcp));
    assert_eq!(tcp("x.ads.example"), Choice::Reject);
    assert_eq!(tcp("empty.example"), Choice::Group("g-Empty"));
    // No final rule: the main group.
    assert_eq!(tcp("other.example"), Choice::Group("g-Proxy"));
}

/// The rule `byRule` counts a flow under: the matched row's id as `rules` lists it, with the same
/// choice `choose` makes.
#[test]
fn decide_names_the_row_that_chose() {
    let (_, p) = policy(3, Options::default());
    let ids: Vec<&str> = p.listing().rows.iter().map(|r| r.id.as_str()).collect();
    let cases = [
        (
            Some("www.netflix.com"),
            ABROAD,
            ids[1],
            Choice::Group("g-Streaming"),
        ),
        (None, CN, ids[3], Choice::Direct),
        (Some("baidu.com"), CN, ids[3], Choice::Direct),
        (
            Some("example.org"),
            ABROAD,
            ids[4],
            Choice::Group("g-Proxy"),
        ),
        (None, ABROAD, ids[4], Choice::Group("g-Proxy")),
    ];
    for (domain, ip, id, choice) in cases {
        let f = flow(domain, ip, 443, Network::Tcp);
        assert_eq!(p.decide(&f), (choice, id), "{domain:?}");
        assert_eq!(p.choose(&f), choice, "{domain:?}");
    }
    // QUIC that blockQuic refuses still names its row.
    let (_, on) = policy(3, Options { block_quic: true });
    let quic = flow(None, ABROAD, 443, Network::Udp);
    assert_eq!(on.decide(&quic), (Choice::Blocked, ids[4]));
}

#[test]
fn a_flow_no_row_matches_is_unmatched() {
    let groups = [tests::group("Proxy", &["n-1"])];
    let rules = [tests::rule("DOMAIN-SUFFIX", &["ads.example"], "REJECT")];
    let p = Policy::listed("g-Proxy", &groups, &|id| id == "n-1", &rules);
    let row = p.listing().rows[0].id.as_str();
    let tcp = |domain| flow(Some(domain), ABROAD, 443, Network::Tcp);
    assert_eq!(p.decide(&tcp("x.ads.example")), (Choice::Reject, row));
    let other = tcp("other.example");
    assert_eq!(p.decide(&other), (Choice::Group("g-Proxy"), "unmatched"));
    assert_eq!(p.choose(&other), Choice::Group("g-Proxy"));
}
