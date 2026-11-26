//! The template's rows and the source compiled from them (rule-mode.md, R2).

use super::policy::Policy;
use super::template::{self, AI, Options, STREAMING};
use super::*;
use crate::catalog::MAX_NODES;
use crate::groups;
use std::collections::HashSet;

/// The template's groups over `n` nodes that no region claims, and the policy over them.
fn policy(n: usize, options: Options) -> (Vec<GroupDef>, Policy) {
    let ids: Vec<String> = (0..n).map(|i| format!("n-{i:016x}")).collect();
    let named: Vec<(&str, &str)> = ids.iter().map(|id| (id.as_str(), "node")).collect();
    let groups = groups::template(&named);
    let known: HashSet<&str> = ids.iter().map(String::as_str).collect();
    let policy = Policy::template(&groups, &|id| known.contains(id), options);
    (groups, policy)
}

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
    assert_eq!(policy.main(), "g-Proxy");
    assert_eq!(policy.source().error, None);
    assert_eq!(policy.source().skipped, 0);
}

#[test]
fn without_nodes_the_template_still_compiles() {
    let (groups, p) = policy(0, Options::default());
    assert!(groups.is_empty());
    assert_eq!(p.source().error, None);
    assert_eq!(p.listing().rows.len(), 5);
}
