//! Hostile bodies against the guard: directives, second documents and deep nesting, after any
//! leading whitespace or control character; a body libyaml cannot scan.

use super::super::parse::parse;
use super::guard::SCAN_CALLS;
use super::tests::{assert_same, nested};
use crate::dto::ErrorCode;
use crate::testutil::alloc::{allocated, peak};
use crate::testutil::fixture;

// A bounded flow scan allocates about 305 KiB, including libyaml's buffers.
const FLOW_SCAN_BYTES: usize = 512 << 10;

/// A `%TAG` directive whose prefix, `prefix` bytes long, libyaml copies into each of the `uses`
/// nodes that name its handle; its lines end with `eol`.
fn tag_directive(prefix: usize, uses: usize, eol: &str) -> String {
    let uses = vec!["!a!b 1"; uses].join(", ");
    let prefix = "x".repeat(prefix);
    format!("%TAG !a! tag:{prefix}{eol}---{eol}proxies: [{uses}]")
}

/// [`tag_directive`] in a second document, after a first that ends at a bare `\r`, a line break
/// to libyaml.
fn second_document(prefix: usize, uses: usize) -> String {
    let uses = vec!["!a!b 1"; uses].join(", ");
    let prefix = "x".repeat(prefix);
    format!("proxies: []\r...\r%TAG !a! tag:{prefix}\r---\r[{uses}]")
}

/// The start of `body`, to name it.
fn head(body: &str) -> String {
    format!("{:?}", body.chars().take(16).collect::<String>())
}

/// Checks that `body` is refused before anything is built from it: the scan that refuses it
/// allocates a few times the body at most, libyaml's buffers and the text of the token it stops
/// at, where serde_yaml's events of these bodies take hundreds of times it.
fn refused_unbuilt(body: &str) {
    refused_allocating_at_most(body, 4 * body.len(), &head(body));
}

fn refused_allocating_at_most(body: &str, limit: usize, case: &str) {
    let (result, bytes) = allocated(|| parse(body.as_bytes()));
    assert_eq!(result.err(), Some(ErrorCode::ParseFailed), "{case}");
    assert!(bytes < limit, "{case}: {bytes} bytes (limit {limit})");
}

fn refused_before_deep_scan(body: &str, case: &str) {
    let before = SCAN_CALLS.get();
    refused_allocating_at_most(body, FLOW_SCAN_BYTES, case);
    let calls = SCAN_CALLS.get() - before;
    // The 129th opening is refused; stream and mapping tokens also count.
    assert!((1..=140).contains(&calls), "{case}: {calls} scanner calls");
}

#[test]
fn a_directive_is_refused_before_anything_is_built() {
    // libyaml copies a %TAG prefix into every node that names its handle: here 256 KiB 1,000
    // times. It ends a line at a bare `\r` too.
    for eol in ["\n", "\r", "\r\n"] {
        refused_unbuilt(&tag_directive(256 << 10, 1000, eol));
    }
    let version = "%YAML 1.1\n---\nproxies: []\n";
    assert_eq!(
        parse(version.as_bytes()).err(),
        Some(ErrorCode::ParseFailed)
    );
}

#[test]
fn a_second_document_is_refused_before_it_is_read() {
    // serde_yaml reads a second document in full before it refuses it.
    refused_unbuilt(&second_document(256 << 10, 1000));
    for body in [
        "proxies: []\n---\nproxies: []\n",
        "proxies: []\n...\n",
        "---\n---\nproxies: []\n",
    ] {
        let result = parse(body.as_bytes());
        assert_eq!(result.err(), Some(ErrorCode::ParseFailed), "{body:?}");
    }
    // A leading `---`, as Clash files may have, begins the one document.
    let clash = String::from_utf8(fixture("clash.yaml")).unwrap();
    let marked = parse(format!("---\n{clash}").as_bytes()).unwrap();
    assert_same(
        &marked.nodes,
        &parse(clash.as_bytes()).unwrap().nodes,
        "---",
    );
}

#[test]
fn deep_flow_nesting_is_refused_before_libyaml_slows_down() {
    // JSON that serde_json refuses at its depth limit goes to serde_yaml next.
    for body in [
        format!("proxies: {}", nested(20_000)),
        format!("{{\"outbounds\": {}}}", nested(20_000)),
    ] {
        refused_before_deep_scan(&body, &head(&body));
    }
    // serde_yaml reads 128 levels; the scan takes as many and refuses one more.
    assert_eq!(
        parse(nested(128).as_bytes()).err(),
        Some(ErrorCode::NoNodes)
    );
    assert_eq!(
        parse(nested(129).as_bytes()).err(),
        Some(ErrorCode::ParseFailed)
    );
    // A bracket in a quoted scalar or a comment does not nest.
    let text = format!(
        "proxies: []\nname: '{}'\n# {}\n",
        "[".repeat(200),
        "{".repeat(200)
    );
    assert_eq!(parse(text.as_bytes()).err(), Some(ErrorCode::NoNodes));
}

/// The re-review's bodies, and two more: a leading space, form feed, no-break space or byte
/// order mark that the trees' trim takes away, but at which libyaml stops (`\x0c`), or which it
/// reads as the start of one plain scalar (` %`, U+00A0, a byte order mark after a space).
#[test]
fn a_leading_space_or_control_character_hides_nothing_from_the_guard() {
    refused_unbuilt(&format!(" {}", tag_directive(256 << 10, 1000, "\n")));
    refused_unbuilt(&second_document(256 << 10, 1000));
    for body in [
        format!("\x0cproxies: {}", nested(20_000)),
        format!("\x0c{{\"outbounds\": {}}}", nested(20_000)),
        format!("\u{a0}{}", nested(20_000)),
        format!(" \u{feff}{}", nested(20_000)),
    ] {
        refused_before_deep_scan(&body, &head(&body));
    }
}

/// A body libyaml's scanner fails on is not read as YAML, which would fail at the same place
/// after building every event before it. serde_json still reads it, within its recursion limit.
#[test]
fn a_body_libyaml_cannot_scan_is_read_only_as_json() {
    // 200,000 events, then a control character, which libyaml refuses.
    refused_unbuilt(&format!("proxies: [{}0]\x01", "[],".repeat(100_000)));
    // JSON writes a character outside the BMP as a surrogate pair, which libyaml refuses.
    let flag = r"🇯🇵";
    let deep = format!(
        "{{\"name\": \"{flag}\", \"outbounds\": {}}}",
        nested(20_000)
    );
    refused_before_deep_scan(&deep, "JSON-only nesting");
    let plain = String::from_utf8(fixture("singbox.json")).unwrap();
    let flagged = plain.replacen(r#""tag": "ss-01""#, &format!(r#""tag": "{flag} ss-01""#), 1);
    assert_ne!(flagged, plain);
    let parsed = parse(flagged.as_bytes()).unwrap();
    assert!(parsed.nodes.iter().any(|n| n.name == "🇯🇵 ss-01"));
    let again = parse(parsed.document.as_deref().unwrap().as_bytes()).unwrap();
    assert_same(&again.nodes, &parsed.nodes, "flagged");
}

enum Limit {
    DeepScan,
    Bytes(usize),
    Peak(usize),
}

/// Budgets allow for libyaml's token buffers and, for block nesting, bare keys and aliases,
/// serde_yaml's event list. They do not allow expanding aliases or scanning deep flow stacks.
fn hostile() -> Vec<(&'static str, String, Limit)> {
    use Limit::{Bytes, DeepScan, Peak};
    let mut laughs = String::from("a0: &a0 [x,x,x,x,x,x,x,x,x,x]\n");
    for i in 1..12 {
        let uses = vec![format!("*a{}", i - 1); 10].join(",");
        laughs += &format!("a{i}: &a{i} [{uses}]\n");
    }
    laughs += "proxies: [*a11]\n";
    vec![
        ("%TAG", tag_directive(256 << 10, 1000, "\n"), Bytes(1 << 20)),
        (
            "%TAG, \\r",
            tag_directive(256 << 10, 1000, "\r"),
            Bytes(1 << 20),
        ),
        (
            "second document",
            second_document(256 << 10, 1000),
            Bytes(1 << 20),
        ),
        (
            "flow nesting",
            format!("proxies: {}", nested(399_000)),
            DeepScan,
        ),
        ("bare flow nesting", nested(399_000), DeepScan),
        (
            "JSON nesting",
            format!("{{\"outbounds\": {}}}", nested(399_000)),
            DeepScan,
        ),
        ("billion laughs", laughs, Bytes(512 << 10)),
        (
            "string aliases",
            format!(
                "s: &s {}\nproxies: [{}]",
                "y".repeat(64 << 10),
                "*s,".repeat(1000)
            ),
            Bytes(1 << 20),
        ),
        (
            "block nesting",
            format!("proxies:\n{}x", "- ".repeat(10_000)),
            // Peak across all prefixes: 5,047,831 bytes.
            Peak(10_100_000),
        ),
        (
            "bare keys",
            format!("proxies: {{{}}}", "a,".repeat(10_000)),
            // Peak across all prefixes: 3,225,152 bytes.
            Peak(6_500_000),
        ),
    ]
}

/// What a body may start with that the trees' trim takes away: the review's list, and a
/// no-break space, which libyaml reads as text.
const PREFIXES: [&str; 12] = [
    "",
    " ",
    "\t",
    "\r",
    "\n",
    "\r\n",
    "\x0b",
    "\x0c",
    "\u{feff}",
    "\u{feff} ",
    " \u{feff}",
    "\u{a0}",
];

#[test]
fn no_prefix_lets_a_hostile_body_through() {
    for (what, body, budget) in hostile() {
        for prefix in PREFIXES {
            let prefixed = format!("{prefix}{body}");
            let case = format!("{what} after {prefix:?}");
            match budget {
                Limit::DeepScan => refused_before_deep_scan(&prefixed, &case),
                Limit::Bytes(bytes) => refused_allocating_at_most(&prefixed, bytes, &case),
                Limit::Peak(limit) => {
                    // Keep the cumulative cap as well: peak alone misses allocation churn.
                    let ((), bytes, _) =
                        peak(|| refused_allocating_at_most(&prefixed, 8 << 20, &case));
                    assert!(bytes < limit, "{case}: peak {bytes} bytes (limit {limit})");
                }
            }
        }
    }
}
