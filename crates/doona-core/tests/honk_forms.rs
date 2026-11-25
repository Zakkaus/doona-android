mod common;

use std::path::Path;

use common::TempDir;
use doonacore::honkconf::HonkStore;
use doonacore::honkconf::edit::{
    Earlier, Edit, EditError, FormEdit, FormError, Formed, List, Match, RuleForm, RuleRow,
};

const ENTRY: &str =
    "# 主檔\r\ninclude { 'a.dae' 'b.dae' }\r\ngroup { Proxy { policy: select } }\r\n";
const A: &str = "a.dae";
const B: &str = "b.dae";

struct Draft {
    store: HonkStore,
    id: u64,
    _dir: TempDir,
}

impl Draft {
    fn new(a: &str, b: &str) -> Self {
        let dir = TempDir::new("honk-forms");
        let mut store = HonkStore::open(dir.path()).unwrap();
        let id = store.draft(None).unwrap();
        for (path, text) in [("config.dae", ENTRY), (A, a), (B, b)] {
            store.edit(id, Path::new(path), text).unwrap();
        }
        Self {
            store,
            id,
            _dir: dir,
        }
    }

    fn rows(&self, path: &str) -> Vec<RuleRow> {
        self.store.rule_rows(self.id, Path::new(path)).unwrap()
    }

    fn form(&mut self, edit: FormEdit<'_>) -> Result<Formed, EditError> {
        self.store.rule_form(self.id, edit)
    }

    fn add(&mut self, matcher: Match<'_>, target: &str) -> Result<Formed, EditError> {
        self.form(FormEdit::Add {
            before: None,
            form: form(matcher, target),
        })
    }

    fn text(&self, path: &str) -> String {
        self.store
            .text(self.id, Path::new(path))
            .unwrap()
            .to_string()
    }

    fn tree(&self, a: &str, b: &str) {
        for (path, expected) in [("config.dae", ENTRY), (A, a), (B, b)] {
            assert_eq!(self.text(path), expected, "{path}");
        }
    }

    fn rules(&self) -> usize {
        self.store.check(self.id).unwrap().summary().routing_rules
    }
}

fn form<'a>(matcher: Match<'a>, target: &'a str) -> RuleForm<'a> {
    RuleForm {
        matcher,
        target,
        paused: false,
    }
}

fn routing(body: &str) -> String {
    format!("# 前言\r\nrouting {{\r\n{body}  fallback: direct # 保留\r\n}}\r\n")
}

#[test]
fn unsafe_literals_and_targets_are_refused_without_a_write() {
    let a = routing("  domain(x.org) -> block # 旁註\r\n");
    let mut draft = Draft::new(&a, "");
    use FormError::*;
    let cases = [
        (Match::Full("a\nb.org"), Forbidden),
        (Match::Full("a.org\r\n  fallback: block"), Forbidden),
        (Match::Suffix("a\u{2028}b.org"), Forbidden),
        (Match::Keyword("me\u{2029}dia"), Forbidden),
        (Match::Keyword("a\0b"), Forbidden),
        (Match::Keyword("a\u{85}b"), Forbidden),
        (Match::Keyword("a)b"), Forbidden),
        (Match::Suffix("a.org) -> block"), Forbidden),
        (Match::Keyword("a->b"), Forbidden),
        (Match::Keyword("a#b"), Forbidden),
        (Match::Keyword("a'b"), Forbidden),
        (Match::Keyword("a\"b"), Forbidden),
        (Match::Keyword("a&&b"), Forbidden),
        (Match::Keyword("!a"), Forbidden),
        (Match::Keyword("a,b"), Forbidden),
        (Match::Keyword("a b"), Forbidden),
        (Match::Keyword("a\tb"), Forbidden),
        (Match::Full(""), Empty),
        (Match::Keyword("   "), Empty),
        (Match::Network(" "), Empty),
        (Match::Suffix("*.example.org"), Wildcard),
        (Match::Suffix("+.example.org"), Wildcard),
        (Match::Full(" .example.org"), Wildcard),
        (Match::Full("example..org"), Name),
        (Match::Full("example.org.."), Name),
        (Match::Full("-a.org"), Name),
        (Match::Full("a）b.org"), Name),
        (Match::Full("203.0.113.1"), Name),
        (Match::Suffix(&"a".repeat(64)), Name),
        (Match::Network("203.0.113.0"), Network),
        (Match::Network("203.0.113.0/33"), Network),
        (Match::Network("203.0.113.0/+24"), Network),
        (Match::Network("2001:db8::/129"), Network),
        (Match::Network("203.0.113.1/24"), HostBits),
        (Match::Network("2001:db8::1/64"), HostBits),
        (Match::Network("::ffff:203.0.113.0/120"), MappedIpv6),
    ];
    for (matcher, expected) in cases {
        let err = draft.add(matcher, "direct").unwrap_err();
        assert!(
            matches!(&err, EditError::Form(e) if *e == expected),
            "{matcher:?}: {err:?}"
        );
        draft.tree(&a, "");
    }
    for target in ["Missing", "Proxy)", "direct(must)", "Proxy # x", "", "dns"] {
        let err = draft.add(Match::Full("a.org"), target).unwrap_err();
        assert!(
            matches!(err, EditError::Form(Target)),
            "{target:?}: {err:?}"
        );
        draft.tree(&a, "");
    }
}

#[test]
fn invisible_characters_are_forbidden_in_every_literal() {
    let mut draft = Draft::new(&routing(""), "");
    let mut wrong = Vec::new();
    for matcher in [
        Match::Keyword("me\u{200b}dia"),
        Match::Full("a\u{200e}.org"),
        Match::Suffix("a.org\u{202e}"),
        Match::Keyword("\u{feff}media"),
        Match::Full("a\u{3164}.org"),
        Match::Suffix("a.org\u{115f}"),
        Match::Full("a\u{ffa0}b.org"),
        Match::Suffix("a\u{34f}b.org"),
    ] {
        if !matches!(
            draft.add(matcher, "direct"),
            Err(EditError::Form(FormError::Forbidden))
        ) {
            wrong.push(matcher);
        }
    }
    assert!(wrong.is_empty(), "{wrong:?}");
    draft.tree(&routing(""), "");
}

#[test]
fn a_format_character_in_a_target_is_refused() {
    let entry = ENTRY.replace("Proxy", "Pro\u{200b}xy");
    let mut draft = Draft::new(&routing(""), "");
    draft
        .store
        .edit(draft.id, Path::new("config.dae"), &entry)
        .unwrap();
    let result = draft.add(Match::Full("a.org"), "Pro\u{200b}xy");
    assert!(
        matches!(result, Err(EditError::Form(FormError::Target))),
        "{result:?}"
    );
    assert_eq!(draft.text(A), routing(""));
}

#[test]
fn keywords_are_ascii_because_honk_sniffs_a_labels() {
    let mut draft = Draft::new(&routing(""), "");
    for keyword in ["中國", "mé", "a_b"] {
        let result = draft.add(Match::Keyword(keyword), "direct");
        assert!(
            matches!(result, Err(EditError::Form(FormError::Keyword))),
            "{keyword}: {result:?}"
        );
    }
    draft.add(Match::Keyword("xn--fiq228c"), "direct").unwrap();
}

#[test]
fn admitted_literals_read_back_as_the_intended_predicate() {
    let a = routing("\tdomain(x.org) -> block # 旁註\r\n");
    let mut draft = Draft::new(&a, "");
    let cases = [
        (Match::Full(" Example.ORG. "), "domain(full: example.org)"),
        (
            Match::Suffix("例子.org"),
            "domain(full: xn--fsqu00a.org, suffix: .xn--fsqu00a.org)",
        ),
        (
            Match::Suffix("例子。org"),
            "domain(full: xn--fsqu00a.org, suffix: .xn--fsqu00a.org)",
        ),
        (Match::Keyword("MeDia"), "domain(keyword: media)"),
        (Match::Network("203.0.113.0/24"), "dip(203.0.113.0/24)"),
        (Match::Network("2001:DB8:0::/32"), "dip(2001:db8::/32)"),
        (Match::China, "dip(geoip:cn)"),
        (Match::Private, "dip(geoip:private)"),
    ];
    let mut expected = String::new();
    for (i, (matcher, predicate)) in cases.into_iter().enumerate() {
        let formed = draft.add(matcher, "Proxy").unwrap();
        expected.push_str(&format!("  {predicate} -> Proxy\r\n"));
        draft.tree(
            &routing(&format!("\tdomain(x.org) -> block # 旁註\r\n{expected}")),
            "",
        );
        assert_eq!(formed.path, Path::new(A));
        assert_eq!(draft.rows(A)[i + 1].id, formed.id);
        assert_eq!(draft.rules(), i + 2);
    }
}

#[test]
fn default_add_follows_the_last_effective_rule_across_includes() {
    let a = routing("  domain(a.org) -> block\r\n");
    let b = "# 乙\nrouting {\n  domain(b.org) -> Proxy # 末\n}\n";
    let mut draft = Draft::new(&a, b);
    // The textual fallback in the first include is not the effective end.
    draft
        .store
        .form_edit(
            draft.id,
            Path::new(A),
            Edit::Insert {
                list: List::Traffic,
                before: None,
                statement: "domain(full: wrong.org) -> direct",
            },
        )
        .unwrap();
    assert_eq!(
        draft.text(A),
        routing("  domain(a.org) -> block\r\n  domain(full: wrong.org) -> direct\r\n")
    );
    draft.store.edit(draft.id, Path::new(A), &a).unwrap();
    let formed = draft.add(Match::Full("c.org"), "direct").unwrap();
    assert_eq!(formed.path, Path::new(B));
    draft.tree(
        &a,
        "# 乙\nrouting {\n  domain(b.org) -> Proxy # 末\ndomain(full: c.org) -> direct\n}\n",
    );

    let mut draft = Draft::new(&routing(""), "routing {\n}\n");
    let formed = draft.add(Match::China, "direct").unwrap();
    assert_eq!(formed.path, Path::new(A));
    draft.tree(&routing("  dip(geoip:cn) -> direct\r\n"), "routing {\n}\n");

    let mut draft = Draft::new("", "routing {\n}\n");
    draft.add(Match::China, "direct").unwrap();
    draft.tree("", "routing {\ndip(geoip:cn) -> direct\n}\n");

    let b = "routing {\n  fallback: direct\n  domain(b.org) -> block\n}\n";
    let mut draft = Draft::new("", b);
    assert!(matches!(
        draft.add(Match::China, "direct"),
        Err(EditError::Form(FormError::Unplaced))
    ));
    draft.tree("", b);
}

#[test]
fn before_anchor_lands_before_that_rule_in_effective_order() {
    let a = routing("  domain(a.org) -> block\r\n  domain(b.org) -> block\r\n");
    let b = "routing { dport(80) -> block }\n";
    let mut draft = Draft::new(&a, b);
    let rows = draft.rows(A);
    let formed = draft
        .form(FormEdit::Add {
            before: Some((Path::new(A), &rows[1].id)),
            form: form(Match::Full("a.org"), "Proxy"),
        })
        .unwrap();
    assert!(matches!(
        formed.earlier,
        Earlier::Shadowed { position: 0, ref path, ref bytes } if path == Path::new(A) && a[bytes.clone()] == *"domain(a.org) -> block"
    ));
    draft.tree(
        &routing(
            "  domain(a.org) -> block\r\n  domain(full: a.org) -> Proxy\r\n  domain(b.org) -> block\r\n",
        ),
        b,
    );
    // The rule in the later include is not earlier, so nothing may match first.
    let fallback = draft.rows(A).into_iter().find(|r| r.fallback).unwrap().id;
    let formed = draft
        .form(FormEdit::Add {
            before: Some((Path::new(A), &fallback)),
            form: form(Match::Full("c.org"), "Proxy"),
        })
        .unwrap();
    assert_eq!(formed.earlier, Earlier::Clear);
    draft.tree(
        &routing(
            "  domain(a.org) -> block\r\n  domain(full: a.org) -> Proxy\r\n  domain(b.org) -> block\r\n  domain(full: c.org) -> Proxy\r\n",
        ),
        b,
    );
}

#[test]
fn replace_writes_only_the_statement_bytes() {
    let second = "  !domain(\r\n     suffix: '第二.org', # 中間\r\n     suffix: third.org) && l4proto(tcp) -> block # 尾\r\n";
    let a = routing(&format!("\tdomain(x.org) -> direct # 旁註\r\n{second}"));
    let mut draft = Draft::new(&a, "");
    let rows = draft.rows(A);
    let formed = draft
        .form(FormEdit::Replace {
            path: Path::new(A),
            id: &rows[1].id,
            form: form(Match::Keyword("media"), "Proxy"),
        })
        .unwrap();
    let replaced =
        routing("\tdomain(x.org) -> direct # 旁註\r\n  domain(keyword: media) -> Proxy # 尾\r\n");
    draft.tree(&replaced, "");
    assert_eq!(formed.earlier, Earlier::MayMatch);
    let after = draft.rows(A);
    assert_eq!(after[0].id, rows[0].id);
    assert_eq!(after[1].id, formed.id);
    assert_ne!(formed.id, rows[1].id);
    assert!(matches!(
        draft.form(FormEdit::Replace {
            path: Path::new(A),
            id: &after[0].id,
            form: form(Match::Full("y.org"), "Missing"),
        }),
        Err(EditError::Form(FormError::Target))
    ));
    draft.tree(&replaced, "");
}

#[test]
fn replacing_a_paused_rule_validates_it_active_and_keeps_it_paused() {
    let rule = "  domain(x.org) -> direct # 旁註\r\n";
    let a = routing(&format!("{rule}  domain(y.org) -> block\r\n"));
    let mut draft = Draft::new(&a, "");
    let id = draft.rows(A)[0].id.clone();
    draft
        .store
        .form_edit(draft.id, Path::new(A), Edit::Pause(&id))
        .unwrap();
    let paused_form = RuleForm {
        paused: true,
        ..form(Match::Suffix("z.org"), "Proxy")
    };
    let formed = draft
        .form(FormEdit::Replace {
            path: Path::new(A),
            id: &id,
            form: paused_form,
        })
        .unwrap();
    let new = &formed.id;
    let paused = routing(&format!(
        "# paused {new}: \x20 domain(full: z.org, suffix: .z.org) -> Proxy # 旁註\r\n  domain(y.org) -> block\r\n"
    ));
    draft.tree(&paused, "");
    assert_eq!(formed.earlier, Earlier::Clear);
    assert_eq!(draft.rules(), 1);
    for target in ["Missing", "block)"] {
        assert!(matches!(
            draft.form(FormEdit::Replace {
                path: Path::new(A),
                id: new,
                form: RuleForm {
                    target,
                    ..paused_form
                },
            }),
            Err(EditError::Form(FormError::Target))
        ));
        draft.tree(&paused, "");
    }
    draft
        .form(FormEdit::Replace {
            path: Path::new(A),
            id: new,
            form: form(Match::Suffix("z.org"), "Proxy"),
        })
        .unwrap();
    draft.tree(
        &routing(
            "  domain(full: z.org, suffix: .z.org) -> Proxy # 旁註\r\n  domain(y.org) -> block\r\n",
        ),
        "",
    );
    assert_eq!(draft.rules(), 2);
}

fn shadows(rules: &[&str], cases: &[(Match<'_>, Option<usize>)]) {
    let a = routing(
        &rules
            .iter()
            .map(|r| format!("  {r} -> direct\r\n"))
            .collect::<String>(),
    );
    for &(matcher, shadow) in cases {
        let mut draft = Draft::new(&a, "");
        let earlier = draft.add(matcher, "Proxy").unwrap().earlier;
        match shadow {
            Some(n) => assert!(
                matches!(earlier, Earlier::Shadowed { position, .. } if position == n),
                "{matcher:?}: {earlier:?}"
            ),
            None => assert_eq!(earlier, Earlier::Clear, "{matcher:?}"),
        }
    }
}

#[test]
fn earlier_rules_shadow_only_when_provable() {
    shadows(
        &["domain(suffix: example.org)", "domain(keyword: media)"],
        &[
            (Match::Full("api.example.org"), Some(0)),
            (Match::Suffix("example.org"), Some(0)),
            (Match::Full("ample.org"), None),
            (Match::Full("notexample.org"), Some(0)),
            (Match::Full("media.net"), Some(1)),
            (Match::Suffix("social.media.net"), Some(1)),
            (Match::Keyword("multimedia"), Some(1)),
        ],
    );
    shadows(
        &["dip(10.0.0.0/8)", "dip(2001:db8:1::/48)", "dip(geoip:cn)"],
        &[
            (Match::Network("10.1.0.0/16"), Some(0)),
            (Match::Network("2001:db8:1:5::/64"), Some(1)),
            (Match::China, Some(2)),
        ],
    );
    shadows(
        &["dip(10.0.0.0/8)", "dip(2001:db8:1::/48)"],
        &[
            (Match::Network("11.0.0.0/8"), None),
            (Match::Network("2001:db8:2::/48"), None),
        ],
    );
    for (earlier_rule, matcher) in [
        ("domain(keyword: media)", Match::Suffix("a.org")),
        ("domain(suffix: a.org)", Match::Keyword("b")),
        ("domain(full: a.b.org)", Match::Suffix("b.org")),
        ("dip(geoip:private)", Match::Full("a.org")),
        ("dip(geoip:cn)", Match::Network("10.0.0.0/8")),
        ("dip(10.0.0.0/8)", Match::Private),
        (
            "domain(suffix: a.org) && l4proto(tcp)",
            Match::Full("a.org"),
        ),
        ("!domain(suffix: b.org)", Match::Full("a.org")),
        (
            "domain(suffix: a.org) && !domain(full: x.a.org)",
            Match::Full("x.a.org"),
        ),
        // honk reads `full:` as a glob, and matches case-sensitively.
        (
            "domain(full: *.example.org)",
            Match::Full("api.example.org"),
        ),
        ("domain(full: 'a?.org')", Match::Full("ab.org")),
        (
            "domain(full: '*.example.org')",
            Match::Suffix("example.org"),
        ),
        ("dip(10.0.0.0/8)", Match::Network("0.0.0.0/0")),
        ("dip(::/0)", Match::Network("10.0.0.0/8")),
    ] {
        let mut draft = Draft::new(&routing(&format!("  {earlier_rule} -> direct\r\n")), "");
        assert_eq!(
            draft.add(matcher, "Proxy").unwrap().earlier,
            Earlier::MayMatch,
            "{earlier_rule}"
        );
    }
}

#[test]
fn form_suffix_writes_the_label_bounded_pair() {
    let mut draft = Draft::new(&routing(""), "");
    draft.add(Match::Suffix("Example.org"), "Proxy").unwrap();
    draft.tree(
        &routing("  domain(full: example.org, suffix: .example.org) -> Proxy\r\n"),
        "",
    );
}

#[test]
fn replacing_a_form_suffix_rule_round_trips() {
    let mut draft = Draft::new(&routing(""), "");
    let first = draft.add(Match::Suffix("a.org"), "Proxy").unwrap();
    let formed = draft
        .form(FormEdit::Replace {
            path: Path::new(A),
            id: &first.id,
            form: form(Match::Suffix("b.org"), "Proxy"),
        })
        .unwrap();
    draft.tree(
        &routing("  domain(full: b.org, suffix: .b.org) -> Proxy\r\n"),
        "",
    );
    assert_eq!(draft.rows(A)[0].id, formed.id);
}

#[test]
fn raw_suffix_shadows_by_plain_ends_with() {
    shadows(
        &["domain(suffix: example.org)"],
        &[(Match::Full("notexample.org"), Some(0))],
    );
}

#[test]
fn raw_suffix_shadows_a_longer_name_that_ends_with_it() {
    shadows(
        &["domain(suffix: a.org)"],
        &[(Match::Suffix("xa.org"), Some(0))],
    );
    shadows(
        &["domain(suffix: a.org)"],
        &[(Match::Full("xa.org"), Some(0))],
    );
}

#[test]
fn raw_name_leaves_a_form_suffix_clear_when_no_name_can_match() {
    shadows(&["domain(full: xa.org)"], &[(Match::Suffix("a.org"), None)]);
}

#[test]
fn form_suffix_shadows_on_label_boundaries() {
    shadows(
        &["domain(full: example.org, suffix: .example.org)"],
        &[
            (Match::Full("api.example.org"), Some(0)),
            (Match::Full("example.org"), Some(0)),
            (Match::Full("notexample.org"), None),
            (Match::Suffix("example.org"), Some(0)),
            (Match::Suffix("api.example.org"), Some(0)),
            (Match::Suffix("notexample.org"), None),
        ],
    );
}

#[test]
fn keyword_values_are_lowercased_like_names() {
    let mut draft = Draft::new(&routing(""), "");
    draft.add(Match::Keyword("MEDIA"), "Proxy").unwrap();
    draft.tree(&routing("  domain(keyword: media) -> Proxy\r\n"), "");
}

#[test]
fn an_earlier_value_with_uppercase_matches_nothing() {
    // honk lowercases network names before matching and never lowercases rule values.
    shadows(
        &[
            "domain(suffix: A.org)",
            "domain(full: Example.org)",
            "domain(keyword: Media)",
            "domain(keyword: media)",
        ],
        &[
            (Match::Full("a.org"), None),
            (Match::Full("example.org"), None),
            (Match::Keyword("media"), Some(3)),
        ],
    );
}
