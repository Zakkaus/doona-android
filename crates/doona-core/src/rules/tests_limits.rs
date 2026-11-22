//! Each limit at its bound and one past it, as C1d's guard tests: past it the source is
//! `parse_failed`, refused before anything is built.

use super::limits::{MAX_DEPTH, MAX_GROUPS, MAX_KEYWORDS, MAX_LINES, MAX_MEMBERS, MAX_VALUE};
use super::tests::{group, rule};
use super::*;
use crate::groups::GroupDef;
use crate::testutil::alloc::allocated;

fn proxy() -> Vec<GroupDef> {
    vec![group("Proxy", &["n-1"])]
}

fn compiles(groups: &[GroupDef], rules: &[RuleSpec]) -> Result<(), ErrorCode> {
    RuleSet::compile(groups, rules).map(|_| ())
}

/// `n` suffix lines, one row each.
fn suffixes(n: usize) -> Vec<RuleSpec> {
    let names: Vec<String> = (0..n).map(|i| format!("d{i}.example")).collect();
    names
        .iter()
        .map(|d| rule("DOMAIN-SUFFIX", &[d.as_str()], "Proxy"))
        .collect()
}

#[test]
fn lines_at_the_bound_compile_and_one_more_is_refused_before_anything_is_built() {
    let mut rules = suffixes(MAX_LINES);
    assert_eq!(compiles(&proxy(), &rules), Ok(()));
    rules.push(rule("MATCH", &[], "Proxy"));
    let groups = proxy();
    let (result, bytes) = allocated(|| compiles(&groups, &rules));
    assert_eq!(result, Err(ErrorCode::ParseFailed));
    assert_eq!(bytes, 0);
}

#[test]
fn values_ports_and_skipped_lines_count_toward_the_bound() {
    // One row of MAX_LINES - 3 values, three ports in one value: at the bound.
    let names: Vec<String> = (0..MAX_LINES - 3)
        .map(|i| format!("d{i}.example"))
        .collect();
    let names: Vec<&str> = names.iter().map(String::as_str).collect();
    let mut rules = vec![
        rule("DOMAIN", &names, "Proxy"),
        rule("DST-PORT", &["25/465/587"], "REJECT"),
    ];
    assert_eq!(compiles(&proxy(), &rules), Ok(()));
    rules.push(rule("GEOSITE", &["cn"], "DIRECT"));
    assert_eq!(compiles(&proxy(), &rules), Err(ErrorCode::ParseFailed));
    rules.pop();
    rules[1] = rule("DST-PORT", &["25/465/587/2525"], "REJECT");
    assert_eq!(compiles(&proxy(), &rules), Err(ErrorCode::ParseFailed));
}

#[test]
fn keywords_at_the_bound_compile_and_one_more_is_refused() {
    let words: Vec<String> = (0..=MAX_KEYWORDS).map(|i| format!("w{i}")).collect();
    let words: Vec<&str> = words.iter().map(String::as_str).collect();
    let at = [rule("DOMAIN-KEYWORD", &words[..MAX_KEYWORDS], "Proxy")];
    assert_eq!(compiles(&proxy(), &at), Ok(()));
    let past = [
        rule("DOMAIN-KEYWORD", &words[..MAX_KEYWORDS], "Proxy"),
        rule("DOMAIN-KEYWORD", &words[MAX_KEYWORDS..], "DIRECT"),
    ];
    assert_eq!(compiles(&proxy(), &past), Err(ErrorCode::ParseFailed));
}

#[test]
fn a_value_at_the_bound_compiles_and_a_longer_one_is_refused() {
    let label = "a".repeat(60);
    let name = format!("{label}.{label}.{label}.{}", "b".repeat(MAX_VALUE - 183));
    assert_eq!(name.len(), MAX_VALUE);
    let at = [rule("DOMAIN-SUFFIX", &[&name], "Proxy")];
    assert_eq!(compiles(&proxy(), &at), Ok(()));
    let longer = format!("x{name}");
    let past = [rule("DOMAIN-SUFFIX", &[&longer], "Proxy")];
    assert_eq!(compiles(&proxy(), &past), Err(ErrorCode::ParseFailed));
    // A skipped kind's value is not kept, so it has no bound of its own.
    let skipped = [rule("DOMAIN-REGEX", &[&"a".repeat(4_096)], "Proxy")];
    assert_eq!(compiles(&proxy(), &skipped), Ok(()));
}

#[test]
fn groups_at_the_bound_compile_and_one_more_is_refused() {
    let names: Vec<String> = (0..=MAX_GROUPS).map(|i| format!("G{i}")).collect();
    let mut groups: Vec<GroupDef> = names.iter().map(|n| group(n, &["n-1"])).collect();
    let extra = groups.pop();
    assert_eq!(compiles(&groups, &[]), Ok(()));
    groups.extend(extra);
    assert_eq!(compiles(&groups, &[]), Err(ErrorCode::ParseFailed));
}

#[test]
fn members_at_the_bound_compile_and_one_more_is_refused() {
    let members: Vec<String> = (0..=MAX_MEMBERS).map(|i| format!("n-{i}")).collect();
    let members: Vec<&str> = members.iter().map(String::as_str).collect();
    let at = [group("Proxy", &members[..MAX_MEMBERS])];
    assert_eq!(compiles(&at, &[]), Ok(()));
    let past = [group("Proxy", &members)];
    assert_eq!(compiles(&past, &[]), Err(ErrorCode::ParseFailed));
}

/// A chain G0 -> G1 -> ... -> G`edges`, the last over a node.
fn chain(edges: usize) -> Vec<GroupDef> {
    (0..=edges)
        .map(|i| {
            let below = format!("g-G{}", i + 1);
            let member = if i == edges { "n-1" } else { below.as_str() };
            group(&format!("G{i}"), &[member])
        })
        .collect()
}

#[test]
fn nesting_at_the_bound_compiles_and_one_level_more_is_refused() {
    assert_eq!(usize::from(MAX_DEPTH), 4);
    assert_eq!(compiles(&chain(MAX_DEPTH.into()), &[]), Ok(()));
    let past = chain(usize::from(MAX_DEPTH) + 1);
    assert_eq!(compiles(&past, &[]), Err(ErrorCode::ParseFailed));
}
