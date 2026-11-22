//! Row ids, the page and the counts of the template.

use super::*;
use crate::dto::RuleSourceSpec;
use crate::rules::policy::Policy;
use crate::rules::template;
use crate::rules::tests::rule;

fn listed(rules: &[RuleSpec]) -> Listing {
    let set = RuleSet::compile(&[], &|_| false, rules).unwrap();
    Listing::new("template:test", rules, Some(&set))
}

fn ids(listing: &Listing) -> Vec<&str> {
    listing.rows.iter().map(|r| r.id.as_str()).collect()
}

#[test]
fn identical_rows_get_distinct_ids_by_occurrence() {
    let a = rule("DOMAIN", &["a.test"], "DIRECT");
    let b = rule("DOMAIN", &["b.test"], "DIRECT");
    let listing = listed(&[a.clone(), b.clone(), a.clone()]);
    let [first, other, again] = ids(&listing)[..] else {
        panic!("three rows")
    };
    assert!(first != again && first != other && other != again);
    assert!(first.starts_with("r-") && first.len() == 18, "{first}");
    // A row's id follows its content, not its position: inserting a row before keeps it.
    let shifted = listed(&[b, a.clone(), a]);
    assert_eq!(ids(&shifted), [other, first, again]);
    assert_ne!(shifted.revision, listing.revision);
    assert!(listing.revision < 1 << 48);
}

#[test]
fn the_source_the_kind_the_values_and_the_target_each_change_an_id() {
    let base = rule("DOMAIN", &["a.test", "b.test"], "DIRECT");
    let id = |source: &str, rule: &RuleSpec| {
        let rules = std::slice::from_ref(rule);
        let set = RuleSet::compile(&[], &|_| false, rules).unwrap();
        Listing::new(source, rules, Some(&set)).rows[0].id.clone()
    };
    let first = id("template:test", &base);
    assert_eq!(first, id("template:test", &base));
    assert_ne!(first, id("template:other", &base));
    for changed in [
        rule("DOMAIN-SUFFIX", &["a.test", "b.test"], "DIRECT"),
        rule("DOMAIN", &["a.test,b.test"], "DIRECT"),
        rule("DOMAIN", &["b.test", "a.test"], "DIRECT"),
        rule("DOMAIN", &["a.test", "b.test"], "REJECT"),
    ] {
        assert_ne!(first, id("template:test", &changed), "{changed:?}");
    }
}

#[test]
fn skipped_lines_carry_their_reason() {
    let listing = listed(&[
        rule("GEOIP", &["CN", "XX", "YY"], "DIRECT"),
        rule("GEOSITE", &["cn", "google"], "DIRECT"),
        rule("PROCESS-NAME", &["curl"], "DIRECT"),
        rule("MATCH", &[], "DIRECT"),
    ]);
    let page = listing.page(0, 10);
    let shown: Vec<_> = page
        .rows
        .iter()
        .map(|r| (r.kind.as_str(), r.skipped.as_deref(), r.skipped_lines))
        .collect();
    assert_eq!(
        shown,
        [
            ("GeoIp", Some("geodata_missing"), 2),
            ("GEOSITE", Some("geodata_missing"), 2),
            ("PROCESS-NAME", Some("app_rule"), 1),
            ("Match", None, 0),
        ]
    );
    assert_eq!(page.rows[3].sample, None);
    assert_eq!(page.rows[0].sample.as_deref(), Some("CN"));
    assert_eq!((page.rows[0].count, page.rows[3].count), (3, 0));
    assert_eq!(listing.lines(), 7);
}

#[test]
fn a_page_is_bounded_by_the_offset_the_limit_and_the_end() {
    let rules: Vec<RuleSpec> = (0..7)
        .map(|i| rule("DOMAIN", &[format!("{i}.test").as_str()], "DIRECT"))
        .collect();
    let listing = listed(&rules);
    let page = listing.page(5, 3);
    assert_eq!((page.total, page.rows.len()), (7, 2));
    assert_eq!((page.rows[0].index, page.rows[1].index), (5, 6));
    assert_eq!(page.rows[0].id, listing.rows[5].id);
    assert!(listing.page(7, 3).rows.is_empty());
    assert!(listing.page(u32::MAX, 500).rows.is_empty());
    assert_eq!(listing.page(0, 3).rules_revision, listing.revision);
}

#[test]
fn the_template_lists_five_rows_of_twenty_three_lines() {
    let policy = Policy::template(&[], &|_| false, template::Options::default());
    let source = policy.source();
    assert_eq!(
        (source.rules, source.lines, source.skipped, source.error),
        (5, 23, 0, None)
    );
    assert_eq!(source.template.as_deref(), Some(template::ID));
    assert_eq!(source.version, Some(template::VERSION));
    let rows = policy.listing().page(0, 5).rows;
    let targets: Vec<_> = rows
        .iter()
        .map(|r| (r.target.as_str(), r.target_kind))
        .collect();
    assert_eq!(
        targets,
        [
            ("direct", TargetKind::Direct),
            ("g-Streaming", TargetKind::Group),
            ("g-AI", TargetKind::Group),
            ("direct", TargetKind::Direct),
            ("g-Proxy", TargetKind::Group),
        ]
    );
    let RuleSourceSpec::Template { template, .. } = RuleSourceSpec::default();
    assert_eq!(template, template::ID);
}
