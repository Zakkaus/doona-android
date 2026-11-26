//! Behaviour pinned to Mihomo's source, MetaCubeX/mihomo `Alpha` at `100ff73a` (2026-11-20),
//! after astra's review of R1. Each test names the lines it follows.

use super::tests::{compile, group, node, refused, rule};
use super::*;

/// A group whose member is not a known node refuses the profile: `getProxies` fails on a name
/// it does not hold (`adapter/outboundgroup/parser.go:156,223-228`), and so does the whole
/// configuration (`config/config.go:956-958`).
#[test]
fn a_member_that_is_no_known_node_refuses_the_source() {
    let missing = [group("Proxy", &["n-1", "n-missing"])];
    assert_eq!(
        RuleSet::compile(&missing, &node, &[]),
        Err(ErrorCode::ParseFailed)
    );
    let known = [group("Proxy", &["n-1", "n-2"])];
    assert!(RuleSet::compile(&known, &node, &[]).is_ok());
}

/// `DST-PORT` reads its value as `utils.NewUnsignedRanges` (`common/utils/ranges.go:16-48`,
/// `common/utils/range.go:16-28,65-95`): the value trimmed, `,` read as `/`, empty pieces
/// skipped, each piece trimmed, `[`, ` ` and `]` trimmed around each port, and a reversed range
/// turned around.
#[test]
fn dst_port_reads_a_value_as_mihomo_does() {
    for value in [
        "80/",
        "/443",
        "25//465",
        " 53 , 853 ",
        "9000-8000",
        "[1080]",
        "80/ /443",
    ] {
        assert!(
            !refused(&[rule("DST-PORT", &[value], "DIRECT")]),
            "{value:?}"
        );
    }
    // 28 ranges are more than `MAX_VALUE` bytes, and still one value.
    let ranges: Vec<String> = (0..28)
        .map(|i| format!("{}-{}", 10_000 + 2 * i, 10_001 + 2 * i))
        .collect();
    let long = ranges.join("/");
    assert!(long.len() > limits::MAX_VALUE);
    assert!(!refused(&[rule("DST-PORT", &[&long], "DIRECT")]));
}

/// What Mihomo refuses: a value with no range (`rules/common/port.go:42-49`, and `*` reads as
/// none at `ranges.go:19-20`), more than 28 pieces (`ranges.go:25-28`), a sign
/// (`strconv.ParseUint`, `range.go:90`), and three ends (`range.go:84-85`).
#[test]
fn dst_port_refuses_what_mihomo_refuses() {
    let pieces = |n: u16| (1..=n).map(|p| p.to_string()).collect::<Vec<_>>().join("/");
    assert!(!refused(&[rule("DST-PORT", &[&pieces(28)], "DIRECT")]));
    let many = pieces(29);
    for bad in [
        "",
        " ",
        "/",
        "//",
        "*",
        "+80",
        "80-90-100",
        "-80",
        "80-",
        "8o",
        &many,
    ] {
        assert!(refused(&[rule("DST-PORT", &[bad], "DIRECT")]), "{bad:?}");
    }
    // Mihomo keeps the low 16 bits of a larger number (`range.go:91`, `T(val)`); doona refuses
    // it rather than match a port the profile never named.
    assert!(refused(&[rule("DST-PORT", &["65616"], "DIRECT")]));
}

/// A kind without data and without values is one line skipped, as the limits count it; only a
/// compiled kind needs its values.
#[test]
fn a_skipped_kind_without_values_is_one_line_skipped() {
    let unsupported = RuleSpec {
        kind: Kind::clash("RULE-SET"),
        values: Vec::new(),
        target: Target::Direct,
    };
    let set = compile(&[unsupported]);
    assert_eq!((set.rows()[0].skipped, set.skipped()), (1, 1));
}

/// A name of 253 bytes with a final dot is within the bound: the dot is dropped first.
#[test]
fn a_final_dot_does_not_count_toward_a_names_length() {
    let label = "a".repeat(63);
    let name = format!("{label}.{label}.{label}.{}", "b".repeat(61));
    assert_eq!(name.len(), limits::MAX_VALUE);
    let dotted = format!("{name}.");
    for kind in ["DOMAIN", "DOMAIN-SUFFIX"] {
        assert!(!refused(&[rule(kind, &[&dotted], "DIRECT")]), "{kind}");
        let longer = format!("x{dotted}");
        assert!(refused(&[rule(kind, &[&longer], "DIRECT")]), "{kind}");
    }
}
