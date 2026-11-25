mod common;

use std::path::Path;

use common::TempDir;
use doonacore::honkconf::edit::EditError;
use doonacore::honkconf::edit::ads::{AdBlock, AdBlockError, STATEMENT};
use doonacore::honkconf::{GeoCatalog, HonkStore, Label};
use honk_config::dns::DnsRequestAction;

const ENTRY: &str = "config.dae";

struct Geo(Label);

impl GeoCatalog for Geo {
    fn geoip(&self, _: &str) -> Label {
        Label::Present
    }
    fn geoip_nets(&self, _: &str) -> Option<Vec<(std::net::IpAddr, u8)>> {
        None
    }
    fn geosite(&self, label: &str) -> Label {
        assert_eq!(label, "category-ads-all");
        self.0
    }
}

const PRESENT: Geo = Geo(Label::Present);

struct Draft {
    store: HonkStore,
    id: u64,
    _dir: TempDir,
}

impl Draft {
    fn new(files: &[(&str, &str)]) -> Self {
        let dir = TempDir::new("honk-forms-ads");
        let mut store = HonkStore::open(dir.path()).unwrap();
        let id = store.draft(None).unwrap();
        for (path, text) in files {
            store.edit(id, Path::new(path), text).unwrap();
        }
        Self {
            store,
            id,
            _dir: dir,
        }
    }

    fn text(&self, path: &str) -> String {
        self.store
            .text(self.id, Path::new(path))
            .unwrap()
            .to_string()
    }

    fn state(&self) -> AdBlock {
        self.store.ad_block(self.id).unwrap()
    }

    fn set(&mut self, on: bool, geo: &Geo) -> Result<(), AdBlockError> {
        self.store.set_ad_block(self.id, on, geo)
    }

    /// The effective DNS request rules as (is the ad statement, action), and the fallback.
    fn request(&self) -> (Vec<(bool, DnsRequestAction)>, DnsRequestAction) {
        let checked = self.store.check(self.id).unwrap();
        let request = &checked.config().dns.routing.request;
        let rules = request
            .rules
            .iter()
            .map(|r| {
                (
                    format!("{:?}", r.conditions).contains("category-ads-all"),
                    r.action.clone(),
                )
            })
            .collect();
        (rules, request.fallback.clone())
    }
}

fn open_source(result: Result<(), AdBlockError>) -> (String, Option<std::ops::Range<usize>>) {
    match result {
        Err(AdBlockError::Edit(EditError::OpenSource { path, bytes })) => {
            (path.to_string_lossy().into_owned(), bytes)
        }
        other => panic!("expected OpenSource, got {other:?}"),
    }
}

#[test]
fn a_config_without_dns_gets_one_request_rule_first_and_keeps_its_fallback() {
    let text = "group { Proxy { policy: select } }\nrouting { fallback: Proxy }";
    let mut draft = Draft::new(&[(ENTRY, text)]);
    let (rules, fallback) = draft.request();
    assert!(rules.is_empty());
    assert_eq!(draft.state(), AdBlock::Absent);
    draft.set(true, &PRESENT).unwrap();
    let expected = format!(
        "{text}\ndns {{\n    routing {{\n        request {{\n            {STATEMENT}\n        }}\n    }}\n}}\n"
    );
    assert_eq!(draft.text(ENTRY), expected);
    assert_eq!(draft.state(), AdBlock::On);
    assert_eq!(
        draft.request(),
        (vec![(true, DnsRequestAction::Reject)], fallback)
    );
    let checked = draft.store.check(draft.id).unwrap();
    assert_eq!(checked.config().routing.default_outbound, "Proxy");
    // Turning it on again writes nothing.
    draft.set(true, &PRESENT).unwrap();
    assert_eq!(draft.text(ENTRY), expected);
}

#[test]
fn a_partial_dns_block_gains_only_the_missing_levels() {
    let cases = [
        (
            "dns { upstream { default: 'udp://1.1.1.1:53' } }\r\n",
            format!(
                "dns {{ upstream {{ default: 'udp://1.1.1.1:53' }}\r\n    routing {{\r\n        request {{\r\n            {STATEMENT}\r\n        }}\r\n    }}\r\n}}\r\n"
            ),
        ),
        (
            "dns {\n  routing {\n    response { fallback: accept }\n  }\n}\n",
            format!(
                "dns {{\n  routing {{\n    response {{ fallback: accept }}\n      request {{\n          {STATEMENT}\n      }}\n  }}\n}}\n"
            ),
        ),
        (
            "\tdns { routing { request {\n\t} } }\n",
            format!("\tdns {{ routing {{ request {{\n\t    {STATEMENT}\n\t}} }} }}\n"),
        ),
    ];
    for (text, expected) in cases {
        let mut draft = Draft::new(&[(ENTRY, text)]);
        let (_, fallback) = draft.request();
        draft.set(true, &PRESENT).unwrap();
        assert_eq!(draft.text(ENTRY), expected);
        assert_eq!(
            draft.request(),
            (vec![(true, DnsRequestAction::Reject)], fallback)
        );
    }
}

const REQUEST: &str = "dns {\n  upstream { local: 'udp://1.1.1.1:53' }\n  routing {\n    request {\n      qname(geosite:cn) -> local # 國內\n      fallback: local\n    }\n  }\n}\n";

#[test]
fn the_statement_goes_before_every_request_rule_and_off_on_restores_its_bytes() {
    let mut draft = Draft::new(&[(ENTRY, REQUEST)]);
    draft.set(true, &PRESENT).unwrap();
    let on = REQUEST.replace(
        "      qname(geosite:cn)",
        &format!("      {STATEMENT}\n      qname(geosite:cn)"),
    );
    assert_eq!(draft.text(ENTRY), on);
    let local = DnsRequestAction::Upstream("local".into());
    assert_eq!(
        draft.request(),
        (
            vec![(true, DnsRequestAction::Reject), (false, local.clone())],
            local.clone()
        )
    );
    draft.set(false, &PRESENT).unwrap();
    let off = draft.text(ENTRY);
    assert!(
        off.contains("# paused r-") && off.contains(STATEMENT),
        "{off}"
    );
    assert_eq!(draft.state(), AdBlock::Off);
    assert_eq!(draft.request(), (vec![(false, local.clone())], local));
    // Off again writes nothing; on resumes the same bytes, with no second copy.
    draft.set(false, &PRESENT).unwrap();
    assert_eq!(draft.text(ENTRY), off);
    draft.set(true, &PRESENT).unwrap();
    assert_eq!(draft.text(ENTRY), on);
    assert_eq!(draft.state(), AdBlock::On);
}

#[test]
fn a_statement_moved_down_or_repeated_is_elsewhere_and_never_duplicated() {
    let moved = REQUEST.replace(
        "      fallback",
        "      qname(geosite:CATEGORY-ADS-ALL) -> reject\n      fallback",
    );
    let first = REQUEST.replace(
        "      qname(geosite:cn)",
        &format!("      {STATEMENT}\n      qname(geosite:cn)"),
    );
    let twice = first.replace(
        "      fallback",
        &format!("      {STATEMENT}\n      fallback"),
    );
    for text in [moved, twice] {
        let mut draft = Draft::new(&[(ENTRY, &text)]);
        let at = text
            .rfind("qname(geosite:C")
            .or(text.rfind(STATEMENT))
            .unwrap();
        let bytes = at..at + STATEMENT.len();
        let elsewhere = AdBlock::Elsewhere {
            path: ENTRY.into(),
            bytes: bytes.clone(),
        };
        assert_eq!(draft.state(), elsewhere);
        for on in [true, false] {
            assert_eq!(
                open_source(draft.set(on, &PRESENT)),
                (ENTRY.into(), Some(bytes.clone()))
            );
            assert_eq!(draft.text(ENTRY), text);
        }
    }
}

#[test]
fn a_paused_statement_after_an_active_rule_is_elsewhere() {
    let mut draft = Draft::new(&[(ENTRY, REQUEST)]);
    draft.set(true, &PRESENT).unwrap();
    draft.set(false, &PRESENT).unwrap();
    let off = draft.text(ENTRY);
    let start = off.find("# paused").unwrap();
    let end = start + off[start..].find('\n').unwrap() + 1;
    let run = &off[start..end];
    let moved = off
        .replacen(run, "", 1)
        .replace("      fallback", &format!("{run}      fallback"));
    draft
        .store
        .edit(draft.id, Path::new(ENTRY), &moved)
        .unwrap();
    let at = moved.find("# paused").unwrap();
    assert_eq!(
        draft.state(),
        AdBlock::Elsewhere {
            path: ENTRY.into(),
            bytes: at..at + run.len()
        }
    );
}

#[test]
fn a_paused_copy_before_an_active_first_one_is_elsewhere() {
    let mut draft = Draft::new(&[(ENTRY, REQUEST)]);
    draft.set(true, &PRESENT).unwrap();
    draft.set(false, &PRESENT).unwrap();
    let off = draft.text(ENTRY);
    let twice = off.replace(
        "      qname(geosite:cn)",
        &format!("      {STATEMENT}\n      qname(geosite:cn)"),
    );
    draft
        .store
        .edit(draft.id, Path::new(ENTRY), &twice)
        .unwrap();
    let start = twice.find("# paused").unwrap();
    let bytes = start..start + twice[start..].find('\n').unwrap() + 1;
    let elsewhere = AdBlock::Elsewhere {
        path: ENTRY.into(),
        bytes: bytes.clone(),
    };
    assert_eq!(draft.state(), elsewhere);
    for on in [true, false] {
        assert_eq!(
            open_source(draft.set(on, &PRESENT)),
            (ENTRY.into(), Some(bytes.clone()))
        );
        assert_eq!(draft.text(ENTRY), twice);
    }
}

#[test]
fn a_statement_in_an_include_is_read_only() {
    let entry = "include { 'ads.dae' }\ndns { routing { request { fallback: asis } } }\n";
    let ads = format!("dns {{ routing {{ request {{\n  {STATEMENT}\n}} }} }}\n");
    let mut draft = Draft::new(&[(ENTRY, entry), ("ads.dae", &ads)]);
    let at = ads.find(STATEMENT).unwrap();
    let bytes = at..at + STATEMENT.len();
    assert_eq!(
        draft.state(),
        AdBlock::Elsewhere {
            path: "ads.dae".into(),
            bytes: bytes.clone()
        }
    );
    for on in [true, false] {
        assert_eq!(
            open_source(draft.set(on, &PRESENT)),
            ("ads.dae".into(), Some(bytes.clone()))
        );
    }
    assert_eq!(draft.text(ENTRY), entry);
    assert_eq!(draft.text("ads.dae"), ads);
}

#[test]
fn a_missing_label_refuses_turning_it_on_but_not_off() {
    let mut draft = Draft::new(&[(ENTRY, REQUEST)]);
    for label in [Label::MissingCategory, Label::MissingAttribute] {
        match draft.set(true, &Geo(label)) {
            Err(AdBlockError::Label(refused)) => assert_eq!(refused, label),
            other => panic!("{other:?}"),
        }
        assert_eq!(draft.text(ENTRY), REQUEST);
    }
    draft.set(true, &PRESENT).unwrap();
    draft.set(false, &Geo(Label::MissingCategory)).unwrap();
    assert_eq!(draft.state(), AdBlock::Off);
    assert!(matches!(
        draft.set(true, &Geo(Label::MissingCategory)),
        Err(AdBlockError::Label(Label::MissingCategory))
    ));
    assert_eq!(draft.state(), AdBlock::Off);
}
