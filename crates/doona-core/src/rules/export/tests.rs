//! The Clash lines of a listing, and text capped at a line boundary.

use super::*;
use crate::rules::tests::rule;
use crate::rules::{RuleSet, RuleSpec};

fn listed(rules: &[RuleSpec]) -> Listing {
    let set = RuleSet::compile(&[], &|_| false, rules).unwrap();
    Listing::new("template:test", rules, Some(&set))
}

#[test]
fn the_export_writes_a_line_per_value_and_comments_skipped_ones() {
    let listing = listed(&[
        rule("GEOIP", &["CN", "XX"], "DIRECT"),
        rule("DOMAIN-SUFFIX", &["a.test", "b\ntest"], "REJECT"),
        rule("GEOSITE", &["cn"], "DIRECT"),
        rule("MATCH", &[], "DIRECT"),
    ]);
    let mut out = Capped::new(1 << 20);
    listing.export(&|id| id.to_owned(), &mut out);
    let (text, truncated) = out.finish();
    assert!(!truncated);
    assert_eq!(
        text,
        "GEOIP,CN,DIRECT\n\
         # GEOIP,XX,DIRECT (skipped: geodata_missing)\n\
         DOMAIN-SUFFIX,a.test,REJECT\n\
         DOMAIN-SUFFIX,b test,REJECT\n\
         # GEOSITE,cn,DIRECT (skipped: geodata_missing)\n\
         MATCH,DIRECT\n"
    );
}

#[test]
fn capped_text_ends_at_a_line_boundary() {
    let mut out = Capped::new(12);
    out.line("12345");
    out.line("6789");
    // 5 + 1 + 4 + 1 bytes so far; this line would take it to 14.
    out.line("ab");
    out.line("c");
    assert_eq!(out.finish(), ("12345\n6789\n".to_owned(), true));
    let mut exact = Capped::new(11);
    exact.line("12345");
    exact.line("6789");
    assert_eq!(exact.finish(), ("12345\n6789\n".to_owned(), false));
}
