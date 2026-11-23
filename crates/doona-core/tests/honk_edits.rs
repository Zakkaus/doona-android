mod common;

use std::path::Path;

use common::TempDir;
use doonacore::honkconf::HonkStore;
use doonacore::honkconf::edit::{Edit, EditError, GroupField, List, RuleRow, UpstreamField};

const ENTRY: &str = "# 主檔\r\ninclude { 'a.dae' 'b.dae' }\r\n";
const A: &str = "a.dae";
const B: &str = "b.dae";
const FIRST: &str = "\tdomain(suffix: '例子.org') -> direct\r\n";
const SECOND: &str = "  !domain(\r\n     suffix: '第二.org', # 中間\r\n     suffix: third.org) && l4proto(tcp) -> block\r\n";

struct Draft {
    store: HonkStore,
    id: u64,
    _dir: TempDir,
}

impl Draft {
    fn new(a: &str, b: &str) -> Self {
        let dir = TempDir::new("honk-edits");
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

    fn edit(&mut self, path: &str, op: Edit<'_>) -> Result<(), EditError> {
        self.store.form_edit(self.id, Path::new(path), op)
    }

    fn tree(&self, a: &str, b: &str) {
        for (path, expected) in [("config.dae", ENTRY), (A, a), (B, b)] {
            assert_eq!(
                &*self.store.text(self.id, Path::new(path)).unwrap(),
                expected,
                "{path}"
            );
        }
    }
}

fn routing(body: &str) -> String {
    format!("# 前言\r\nrouting {{\r\n{body}  fallback: direct # 保留\r\n}}\r\n")
}

#[test]
fn duplicate_includes_move_insert_delete_only_the_addressed_bytes() {
    let a = routing(&format!("{FIRST}# 不移動\r\n{SECOND}"));
    let b = routing(FIRST);
    let mut draft = Draft::new(&a, &b);
    let rows = draft.rows(A);
    let other = draft.rows(B);
    assert_ne!(rows[0].id, other[0].id);
    assert_eq!(&a[rows[1].bytes.clone()], SECOND.trim());
    draft.edit(B, Edit::Delete(&other[0].id)).unwrap();
    let empty = routing("");
    draft.tree(&a, &empty);
    draft
        .edit(
            A,
            Edit::Move {
                id: &rows[1].id,
                before: Some(&rows[0].id),
            },
        )
        .unwrap();
    let moved = routing(&format!("{SECOND}{FIRST}# 不移動\r\n"));
    draft.tree(&moved, &empty);
    let after = draft.rows(A);
    assert_eq!(after[0].id, rows[1].id);
    assert_eq!(after[1].id, rows[0].id);
    draft
        .edit(
            A,
            Edit::Insert {
                list: List::Traffic,
                before: None,
                statement: "domain(新.org) -> block",
            },
        )
        .unwrap();
    let added = moved.replace("  fallback:", "  domain(新.org) -> block\r\n  fallback:");
    draft.tree(&added, &empty);
    assert_eq!(
        draft.store.check(draft.id).unwrap().summary().routing_rules,
        3
    );
    draft.edit(A, Edit::Delete(&rows[0].id)).unwrap();
    draft.tree(&added.replace(FIRST, ""), &empty);
}

#[test]
fn multiline_pause_is_inert_and_resume_restores_every_byte() {
    let a = routing(&format!("{FIRST}{SECOND}"));
    let mut draft = Draft::new(&a, &a);
    let id = &draft.rows(A)[1].id;
    draft.edit(A, Edit::Pause(id)).unwrap();
    let commented = SECOND
        .split_inclusive('\n')
        .map(|l| format!("# paused {id}: {l}"))
        .collect::<String>();
    draft.tree(&a.replace(SECOND, &commented), &a);
    assert_eq!(
        draft.store.check(draft.id).unwrap().summary().routing_rules,
        3
    );
    draft.edit(A, Edit::Resume(id)).unwrap();
    draft.tree(&a, &a);
    assert_eq!(draft.rows(A)[1].id, *id);
}

#[test]
fn identity_uses_ordered_parsed_predicates_modifiers_and_occurrence() {
    let rules = "  domain('甲&&乙') && !dport(80) -> direct(mark: 0x40, must)\r\n  !dport(80) && domain('甲&&乙') -> direct(mark: 64, must)\r\n  domain('甲&&乙') && dport(80) -> direct(mark: 64, must)\r\n  domain('甲&&乙') && !dport(80) -> direct(mark: 64)\r\n  domain('甲&&乙') && !dport(80) -> direct(mark: 65, must)\r\n";
    let a = routing(&format!("{rules}{rules}"));
    let mut draft = Draft::new(&a, "");
    let rows = draft.rows(A);
    let ids: std::collections::HashSet<_> = rows.iter().map(|r| &r.id).collect();
    assert_eq!(ids.len(), 11);
    let equivalent = a.replace("'甲&&乙'", "\"甲&&乙\"").replace("0x40", "64");
    draft
        .store
        .edit(draft.id, Path::new(A), &equivalent)
        .unwrap();
    for (before, after) in rows.iter().zip(draft.rows(A)) {
        assert_eq!(before.id, after.id);
    }
    // Each distinct key must get its original occurrence-zero ID even when it is alone.
    for (line, expected) in rules.lines().zip(&rows) {
        draft
            .store
            .edit(draft.id, Path::new(A), &routing(&format!("{line}\r\n")))
            .unwrap();
        assert_eq!(draft.rows(A)[0].id, expected.id);
    }
}

#[test]
fn scoped_fields_and_fallbacks_do_not_change_siblings() {
    let a = "group { Proxy { policy: 'fixed(0)' } }\r\ngroup { Proxy {\r\n policy: 'fixed(0)' # 此處\r\n filter: name(甲)\r\n filter: subtag(old) # 只改這項\r\n} }\r\nrouting { fallback: direct }\r\ndns {\r\n upstream { local: 'udp://1.1.1.1:53' -> direct }\r\n routing {\r\n request { fallback: 'local' }\r\n response { fallback: accept }\r\n }\r\n}\r\n";
    let b = routing(FIRST);
    let mut draft = Draft::new(a, &b);
    draft
        .edit(
            A,
            Edit::Group {
                name: "Proxy",
                field: GroupField::Policy,
                value: "fallback",
            },
        )
        .unwrap();
    let a = a.replace("'fixed(0)' #", "'fallback' #");
    draft.tree(&a, &b);
    draft
        .edit(
            A,
            Edit::GroupFilter {
                name: "Proxy",
                occurrence: 1,
                expression: "subtag(辦公室)",
            },
        )
        .unwrap();
    let a = a.replace("subtag(old)", "subtag(辦公室)");
    draft.tree(&a, &b);
    let id = draft
        .rows(A)
        .into_iter()
        .find(|r| r.list == List::DnsRequest && r.fallback)
        .unwrap()
        .id;
    draft
        .edit(
            A,
            Edit::SetFallback {
                id: &id,
                target: "reject",
            },
        )
        .unwrap();
    let a = a.replace("fallback: 'local'", "fallback: 'reject'");
    draft.tree(&a, &b);
    draft
        .edit(
            A,
            Edit::Upstream {
                name: "local",
                field: UpstreamField::Uri,
                value: "tcp://[::1]:53",
            },
        )
        .unwrap();
    let a = a.replace("udp://1.1.1.1:53", "tcp://[::1]:53");
    draft.tree(&a, &b);
    draft
        .edit(
            A,
            Edit::Upstream {
                name: "local",
                field: UpstreamField::Detour,
                value: "Proxy",
            },
        )
        .unwrap();
    draft.tree(&a.replace("-> direct", "-> Proxy"), &b);
}

#[test]
fn dns_rule_positions_use_the_requested_list() {
    let source = "dns { routing {\n request {\n  qtype(A) -> reject\n  fallback: asis\n }\n response {\n  qtype(A) -> reject\n  fallback: accept\n }\n} }";
    let mut draft = Draft::new(source, "");
    let rows = draft.rows(A);
    assert_ne!(rows[0].id, rows[2].id);
    draft
        .edit(
            A,
            Edit::Insert {
                list: List::DnsResponse,
                before: Some(&rows[2].id),
                statement: "qtype(AAAA) -> accept",
            },
        )
        .unwrap();
    let added = source.replace("response {\n", "response {\n  qtype(AAAA) -> accept\n");
    draft.tree(&added, "");
    draft.edit(A, Edit::Delete(&rows[0].id)).unwrap();
    draft.tree(&added.replacen("  qtype(A) -> reject\n", "", 1), "");
}

#[test]
fn unsafe_form_edits_return_raw_locations_without_a_write() {
    let a = "# 行\nrouting { domain(x) -> direct }\nrouting { fallback: block }\n";
    let b = "routing {\n  domain(x) -> direct\n}\n";
    let mut draft = Draft::new(a, b);
    let rows = draft.rows(A);
    let err = draft.edit(A, Edit::Pause(&rows[0].id)).unwrap_err();
    assert!(
        matches!(err, EditError::OpenSource { path, bytes: Some(span) } if path == Path::new(A) && &a[span.clone()] == "domain(x) -> direct")
    );
    for op in [
        Edit::Insert {
            list: List::Traffic,
            before: None,
            statement: "dport(80) -> block",
        },
        Edit::Insert {
            list: List::Traffic,
            before: Some(&rows[1].id),
            statement: "dport(80) -> block\n fallback: block",
        },
        Edit::Delete(&rows[1].id),
    ] {
        assert!(matches!(
            draft.edit(A, op),
            Err(EditError::OpenSource { .. })
        ));
        draft.tree(a, b);
    }
    let twin = &draft.rows(B)[0].id;
    assert!(matches!(
        draft.edit(A, Edit::Delete(twin)),
        Err(EditError::OpenSource { bytes: None, .. })
    ));
    draft.tree(a, b);
    let decoy = "dns { upstream { \"'local'\": 'tcp://[::1]:53' } }";
    draft.store.edit(draft.id, Path::new(B), decoy).unwrap();
    assert!(matches!(
        draft.edit(
            B,
            Edit::Upstream {
                name: "local",
                field: UpstreamField::Uri,
                value: "udp://[::1]:53"
            }
        ),
        Err(EditError::OpenSource { .. })
    ));
    draft.tree(a, decoy);
}

#[test]
fn template_hunks_point_to_each_change_on_utf8_boundaries() {
    let template = ENTRY.replace("主檔", "主題") + "# 保留\r\n# 尾\r\n";
    let mut draft = Draft::new(&routing(FIRST), "");
    let edited = ENTRY.to_owned() + "# 保留\r\n# 末\r\n";
    draft
        .store
        .edit(draft.id, Path::new("config.dae"), &edited)
        .unwrap();
    let changes = draft.store.template_diff(draft.id, &template).unwrap();
    assert_eq!(changes.len(), 4);
    assert_eq!(
        (
            changes[0].path.as_path(),
            &changes[0].template,
            changes[0].draft.clone()
        ),
        (Path::new(A), &None, 0..routing(FIRST).len())
    );
    assert_eq!(
        (
            changes[1].path.as_path(),
            &changes[1].template,
            changes[1].draft.clone()
        ),
        (Path::new(B), &None, 0..0)
    );
    let spans: Vec<_> = changes[2..]
        .iter()
        .map(|c| {
            (
                &template[c.template.clone().unwrap()],
                &edited[c.draft.clone()],
            )
        })
        .collect();
    assert_eq!(spans, [("題", "檔"), ("尾", "末")]);
}

#[test]
fn resumed_twin_can_have_a_different_occurrence() {
    let a = routing(&FIRST.repeat(2));
    let mut d = Draft::new(&a, "");
    let rows = d.rows(A);
    d.edit(A, Edit::Pause(&rows[1].id)).unwrap();
    d.edit(A, Edit::Delete(&rows[0].id)).unwrap();
    d.edit(A, Edit::Resume(&rows[1].id)).unwrap();
    d.tree(&routing(FIRST), "");
    assert_eq!(d.rows(A)[0].id, rows[0].id);
}

#[test]
fn either_twin_can_pause_first_and_resume_last() {
    let a = routing(&FIRST.repeat(2));
    for (first, second) in [(0, 1), (1, 0)] {
        let mut d = Draft::new(&a, "");
        let rows = d.rows(A);
        d.edit(A, Edit::Pause(&rows[first].id)).unwrap();
        let remaining = &d.rows(A)[second];
        assert!(!remaining.paused);
        d.edit(A, Edit::Pause(&remaining.id)).unwrap();
        let both = d.rows(A);
        assert!(both[..2].iter().all(|r| r.paused));
        assert_ne!(both[0].id, both[1].id);
        d.edit(A, Edit::Resume(&both[first].id)).unwrap();
        d.edit(A, Edit::Resume(&both[second].id)).unwrap();
        d.tree(&a, "");
    }
}

#[test]
fn restart_lists_paused_span_after_an_unrelated_edit() {
    let mut d = Draft::new(&routing(SECOND), "");
    let id = d.rows(A).remove(0).id;
    d.edit(A, Edit::Pause(&id)).unwrap();
    let addition = "dport(80) -> direct";
    d.edit(
        A,
        Edit::Insert {
            list: List::Traffic,
            before: None,
            statement: addition,
        },
    )
    .unwrap();
    drop(d.store);
    d.store = HonkStore::open(d._dir.path()).unwrap();
    let row = d.rows(A).remove(0);
    assert!(row.paused && !row.fallback && row.list == List::Traffic);
    assert_eq!(row.id, id);
    let text = d.store.text(d.id, Path::new(A)).unwrap();
    let marked = SECOND
        .split_inclusive('\n')
        .map(|l| format!("# paused {id}: {l}"))
        .collect::<String>();
    assert_eq!(&text[row.bytes], marked);
    d.edit(A, Edit::Resume(&row.id)).unwrap();
    d.tree(&routing(&format!("{SECOND}  {addition}\r\n")), "");
}

#[test]
fn paused_multiline_run_is_a_position_and_can_be_deleted() {
    let b = routing(SECOND);
    let mut d = Draft::new(&routing(&format!("{SECOND}{FIRST}")), &b);
    let rows = d.rows(A);
    let id = &rows[0].id;
    d.edit(A, Edit::Pause(id)).unwrap();
    let text = d.store.text(d.id, Path::new(A)).unwrap();
    let run = &text[d.rows(A)[0].bytes.clone()];
    let statement = "dport(53) -> block";
    d.edit(
        A,
        Edit::Insert {
            list: List::Traffic,
            before: Some(id),
            statement,
        },
    )
    .unwrap();
    d.tree(&routing(&format!("{statement}\r\n{run}{FIRST}")), &b);
    d.edit(
        A,
        Edit::Move {
            id: &rows[1].id,
            before: Some(id),
        },
    )
    .unwrap();
    d.tree(&routing(&format!("{statement}\r\n{FIRST}{run}")), &b);
    d.edit(A, Edit::Delete(id)).unwrap();
    d.tree(&routing(&format!("{statement}\r\n{FIRST}")), &b);
}

#[test]
fn marker_collision_and_invalid_restoration_point_to_source() {
    let a = routing(FIRST);
    let mut d = Draft::new(&a, "");
    let id = &d.rows(A)[0].id;
    let marker = format!("# paused {id}: not a rule\n");
    let collision = format!("{marker}{a}");
    d.store.edit(d.id, Path::new(A), &collision).unwrap();
    assert!(matches!(
        d.edit(A, Edit::Pause(id)),
        Err(EditError::OpenSource { .. })
    ));
    assert!(
        matches!(d.edit(A, Edit::Resume(id)), Err(EditError::OpenSource {bytes: Some(r), ..}) if collision[r.clone()] == marker)
    );
    d.tree(&collision, "");
    for body in [
        "not a rule",
        "bad(80) -> direct",
        "fallback: direct",
        "dport(80) -> block\ndport(81) -> direct",
    ] {
        let run = body
            .lines()
            .map(|l| format!("# paused {id}: {l}\n"))
            .collect::<String>();
        let source = routing(&run);
        d.store.edit(d.id, Path::new(A), &source).unwrap();
        assert!(
            matches!(d.edit(A, Edit::Resume(id)), Err(EditError::OpenSource {bytes: Some(r), ..}) if source[r.clone()] == run)
        );
        d.tree(&source, "");
    }
}

#[test]
fn only_winning_declarations_accept_field_edits() {
    let a = "group { Proxy { policy: select } }\nrouting { fallback: direct }\ndns { routing { response { fallback: accept } } }";
    let mut d = Draft::new(a, "");
    let rows = d.rows(A);
    d.store.edit(d.id, Path::new(B), a).unwrap();
    for op in [
        Edit::Group {
            name: "Proxy",
            field: GroupField::Policy,
            value: "fallback",
        },
        Edit::SetFallback {
            id: &rows[0].id,
            target: "block",
        },
        Edit::SetFallback {
            id: &rows[1].id,
            target: "reject",
        },
    ] {
        assert!(matches!(d.edit(A, op), Err(EditError::OpenSource { .. })));
        d.tree(a, a);
    }
    let winners = d.rows(B);
    d.edit(
        B,
        Edit::SetFallback {
            id: &winners[0].id,
            target: "block",
        },
    )
    .unwrap();
    let traffic = a.replace("fallback: direct", "fallback: block");
    d.tree(a, &traffic);
    d.edit(
        B,
        Edit::SetFallback {
            id: &winners[1].id,
            target: "reject",
        },
    )
    .unwrap();
    d.tree(a, &traffic.replace("fallback: accept", "fallback: reject"));
}

#[test]
fn trailing_comment_moves_and_deletes_with_its_rule() {
    let first = "  dport(80) -> direct # 綁定\n";
    let second = "  dport(81) -> block\n";
    let build = |rules: &str| format!("routing {{\n{rules}  fallback: direct\n}}\n");
    let mut d = Draft::new(&build(first), "");
    let id = &d.rows(A)[0].id;
    d.edit(
        A,
        Edit::Insert {
            list: List::Traffic,
            before: None,
            statement: second.trim(),
        },
    )
    .unwrap();
    d.tree(&build(&format!("{first}{second}")), "");
    d.edit(A, Edit::Move { id, before: None }).unwrap();
    d.tree(&build(&format!("{second}{first}")), "");
    d.edit(A, Edit::Delete(id)).unwrap();
    d.tree(&build(second), "");
}

#[test]
fn detour_precedes_comment_and_controls_are_never_field_values() {
    let a = "dns { upstream {\n  '辦:公': 'tcp://[::1]:53' # 旁註\n} routing { request { fallback: '辦:公' } } }\ngroup { Proxy { policy: select\n filter: name(a) } }";
    let mut d = Draft::new(a, "");
    let cfg = d.store.check(d.id).unwrap();
    assert_eq!(cfg.config().dns.upstream[0].name, "辦:公");
    for expression in [
        "name(a)\nfilter: name(b)",
        "name(a)\t&& name(b)",
        "name(a\u{7}b)",
    ] {
        assert!(matches!(
            d.edit(
                A,
                Edit::GroupFilter {
                    name: "Proxy",
                    occurrence: 0,
                    expression
                }
            ),
            Err(EditError::OpenSource { .. })
        ));
        d.tree(a, "");
    }
    d.edit(
        A,
        Edit::Upstream {
            name: "辦:公",
            field: UpstreamField::Detour,
            value: "direct",
        },
    )
    .unwrap();
    d.tree(&a.replace(" #", " -> direct #"), "");
    assert_eq!(
        d.store.check(d.id).unwrap().config().dns.upstream[0]
            .outbound
            .as_deref(),
        Some("direct")
    );
}

#[test]
fn field_edits_budget_the_result_not_the_replacement() {
    use doonacore::honkconf::{HonkConfigError, StoreError};
    use honk_config::parser::SourceLimits;
    let limit = SourceLimits::DEFAULT.max_bytes;
    let path = "config.dae";
    let mut source = String::from("routing { fallback: direct }\n#");
    source.extend(std::iter::repeat_n('x', limit - source.len()));
    let mut d = Draft::new("", "");
    d.store.edit(d.id, Path::new(path), &source).unwrap();
    let id = d.rows(path).remove(0).id;
    let mut set = |target| d.edit(path, Edit::SetFallback { id: &id, target });
    assert!(matches!(
        set("toolong"),
        Err(EditError::Store(StoreError::Config(
            HonkConfigError::SourceLimit
        )))
    ));
    set("block").unwrap();
    let text = d.store.text(d.id, Path::new(path)).unwrap();
    assert!(text.starts_with("routing { fallback: block }\n#"));
    assert_eq!(text.len(), limit - 1);
}
