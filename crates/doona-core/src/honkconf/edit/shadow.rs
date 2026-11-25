//! Which earlier active rule can take a form rule's traffic first. Only an earlier rule with a
//! single positive name, keyword, network or geo matcher can be proven to cover or to miss it;
//! any other predicate may match. A raw `suffix:` is honk's plain `ends_with`
//! (honk-core `routing/domain.rs:164` at the pinned revision), so it also takes `notexample.org`.

use honk_config::routing::RoutingCondition;

use super::literal::{Literal, Net};

#[derive(Clone, Copy, PartialEq)]
pub(super) enum Relation {
    /// The earlier rule matches all of the form rule's traffic.
    Covers,
    Disjoint,
    May,
}

#[derive(Clone, Copy, PartialEq)]
enum Kind {
    Full,
    Suffix,
    Keyword,
    Ip,
    Geo,
}

/// The values of a rule that is one positive name, keyword, network or geo predicate. The name
/// kinds OR together in one `domain()`; any other predicate beside them is an AND.
fn simple(c: &RoutingCondition) -> Option<Vec<(Kind, &str)>> {
    if c.not != Default::default() {
        return None;
    }
    let fields = [
        (Some(Kind::Full), &c.domain),
        (Some(Kind::Suffix), &c.domain_suffix),
        (Some(Kind::Keyword), &c.domain_keyword),
        (Some(Kind::Ip), &c.ip),
        (Some(Kind::Geo), &c.geo_ip),
        (None, &c.domain_regex),
        (None, &c.geosite),
        (None, &c.source_ip),
        (None, &c.port),
        (None, &c.source_port),
        (None, &c.protocol),
        (None, &c.process_name),
        (None, &c.mac),
        (None, &c.ip_version),
        (None, &c.dscp),
    ];
    let set: Vec<_> = fields.into_iter().filter(|(_, v)| !v.is_empty()).collect();
    let names = set
        .iter()
        .all(|(k, _)| matches!(k, Some(Kind::Full | Kind::Suffix | Kind::Keyword)));
    if set.is_empty() || (set.len() > 1 && !names) {
        return None;
    }
    set.into_iter()
        .flat_map(|(kind, values)| values.iter().map(move |v| Some((kind?, v.as_str()))))
        .collect()
}

/// A name or keyword in the form the admission writes, so that comparing bytes is comparing
/// what honk matches: it matches case-sensitively and reads `full:` as a glob, so a `*` or `?`
/// leaves the earlier value at `May`.
fn canonical(value: &str) -> bool {
    !value.is_empty()
        && !value.ends_with('.')
        && value.bytes().all(|b| {
            b.is_ascii_lowercase() || b.is_ascii_digit() || matches!(b, b'.' | b'-' | b'_')
        })
}

/// Whether `name` is `suffix` or lies under it at a label boundary.
fn under(name: &str, suffix: &str) -> bool {
    name.strip_suffix(suffix)
        .is_some_and(|head| head.is_empty() || head.ends_with('.'))
}

fn names(new: &Literal, kind: Kind, v: &str) -> Relation {
    use Relation::{Covers, Disjoint, May};
    let given = |covers: bool, disjoint: bool| match (covers, disjoint) {
        (true, _) => Covers,
        (false, true) => Disjoint,
        _ => May,
    };
    match (new, kind) {
        (Literal::Full(n), Kind::Full) => given(n == v, true),
        (Literal::Full(n), Kind::Suffix) => given(n.ends_with(v), true),
        (Literal::Full(n), Kind::Keyword) => given(n.contains(v), true),
        (Literal::Suffix(n), Kind::Full) => given(false, !under(v, n)),
        (Literal::Suffix(n), Kind::Suffix) => {
            // The new rule's names are `n` and everything ending in `.n`.
            let dotted = format!(".{n}");
            given(
                n.ends_with(v),
                !(dotted.ends_with(v) || v.ends_with(&dotted)),
            )
        }
        (Literal::Suffix(n), Kind::Keyword) | (Literal::Keyword(n), Kind::Keyword) => {
            given(n.contains(v), false)
        }
        (Literal::Keyword(k), Kind::Full) => given(false, !v.contains(k.as_str())),
        _ => May,
    }
}

fn value(new: &Literal, kind: Kind, v: &str) -> Relation {
    match (new, kind) {
        (Literal::Network(n), Kind::Ip) => Net::exact(v).map_or(Relation::May, |e| {
            if e.contains(*n) && e.same_family(*n) {
                Relation::Covers
            } else if e.contains(*n) || n.contains(e) {
                Relation::May
            } else {
                Relation::Disjoint
            }
        }),
        (Literal::Geo(label), Kind::Geo) if *label == v => Relation::Covers,
        // honk lowercases the queried name (sniffing.rs:381/418, dns/query.rs:262) but never a rule
        // value, so a value with an uppercase letter matches nothing. That holds for TLS/QUIC SNI,
        // HTTP Host and DNS, not for hosts that come from config or the API; a miss there only
        // loses a warning and never gives a false Shadowed.
        (Literal::Full(_) | Literal::Suffix(_) | Literal::Keyword(_), _)
            if matches!(kind, Kind::Full | Kind::Suffix | Kind::Keyword)
                && v.bytes().any(|b| b.is_ascii_uppercase()) =>
        {
            Relation::Disjoint
        }
        (Literal::Full(_) | Literal::Suffix(_) | Literal::Keyword(_), _)
            if matches!(kind, Kind::Full | Kind::Suffix | Kind::Keyword) && canonical(v) =>
        {
            names(new, kind, v)
        }
        _ => Relation::May,
    }
}

/// An earlier rule matches when any of its values does.
pub(super) fn relation(new: &Literal, earlier: &RoutingCondition) -> Relation {
    let Some(values) = simple(earlier) else {
        return Relation::May;
    };
    // An earlier form suffix rule is `full: n` plus `suffix: .n`, which together cover `n`.
    if let Literal::Suffix(n) = new
        && values.contains(&(Kind::Full, n))
        && values.contains(&(Kind::Suffix, &format!(".{n}")))
    {
        return Relation::Covers;
    }
    values
        .iter()
        .map(|&(kind, v)| value(new, kind, v))
        .fold(Relation::Disjoint, |seen, r| match (seen, r) {
            (Relation::Covers, _) | (_, Relation::Covers) => Relation::Covers,
            (Relation::May, _) | (_, Relation::May) => Relation::May,
            _ => Relation::Disjoint,
        })
}
