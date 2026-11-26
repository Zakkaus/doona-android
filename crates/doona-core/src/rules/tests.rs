use super::*;
use crate::dto::GroupKind;
use crate::groups::GroupDef;
use crate::ids::group_id;

pub(crate) fn group(name: &str, members: &[&str]) -> GroupDef {
    GroupDef {
        id: group_id(name),
        name: name.to_owned(),
        kind: GroupKind::Selector,
        members: members.iter().map(|m| (*m).to_owned()).collect(),
    }
}

pub(crate) fn rule(kind: &str, values: &[&str], target: &str) -> RuleSpec {
    RuleSpec {
        kind: Kind::clash(kind),
        values: values.iter().map(|v| (*v).to_owned()).collect(),
        target: match target {
            "DIRECT" => Target::Direct,
            "REJECT" => Target::Reject,
            name => Target::Group(group_id(name)),
        },
    }
}

fn groups() -> Vec<GroupDef> {
    vec![group("Proxy", &["n-1"]), group("Streaming", &["g-Proxy"])]
}

/// The nodes the tests' catalog holds.
pub(crate) fn node(id: &str) -> bool {
    matches!(id, "n-1" | "n-2")
}

pub(crate) fn compile(rules: &[RuleSpec]) -> RuleSet {
    RuleSet::compile(&groups(), &node, rules).unwrap()
}

pub(crate) fn refused(rules: &[RuleSpec]) -> bool {
    RuleSet::compile(&groups(), &node, rules) == Err(ErrorCode::ParseFailed)
}

impl PartialEq for RuleSet {
    fn eq(&self, other: &RuleSet) -> bool {
        self.rows == other.rows
    }
}

#[test]
fn another_geoip_code_is_skipped_and_counted() {
    let set = compile(&[
        rule("GEOIP", &["US", "CN"], "Proxy"),
        rule("GEOIP", &["JP"], "DIRECT"),
    ]);
    assert_eq!((set.rows()[0].skipped, set.rows()[1].skipped), (1, 1));
    assert_eq!(set.skipped(), 2);
}

#[test]
fn values_each_kind_reads_compile_with_nothing_skipped() {
    let set = compile(&[
        rule("DOMAIN", &["Netflix.com.", "中国.cn"], "Streaming"),
        rule("DOMAIN-SUFFIX", &["netflix.com"], "Streaming"),
        rule("DOMAIN-KEYWORD", &["Netflix"], "Proxy"),
        rule("IP-CIDR", &["198.51.100.7/24", "2001:db8::/32"], "REJECT"),
        rule("GEOIP", &["CN", "private", "!LAN"], "DIRECT"),
        rule("DST-PORT", &["8000-9000", "25/465/587", "0-65535"], "Proxy"),
        rule("NETWORK", &["UDP", "tcp"], "REJECT"),
        rule("MATCH", &[], "Proxy"),
    ]);
    assert_eq!(set.rows().len(), 8);
    assert_eq!(set.skipped(), 0);
}

#[test]
fn unsupported_kinds_are_skipped_and_counted() {
    let set = compile(&[
        rule("IP-ASN", &["13335"], "Streaming"),
        rule("RULE-SET", &["streaming"], "Streaming"),
        rule(
            "PROCESS-NAME",
            &["com.netflix.mediaclient", "chrome"],
            "Proxy",
        ),
        rule("DOMAIN-REGEX", &["^.*netflix.*$"], "Streaming"),
        rule("AND", &["((NETWORK,UDP),(DST-PORT,443))"], "REJECT"),
        rule("SRC-IP-CIDR", &["192.168.1.0/24"], "DIRECT"),
        rule("NOT-A-KIND", &["x"], "DIRECT"),
        rule("DOMAIN-SUFFIX", &["netflix.com"], "Streaming"),
    ]);
    let reasons: Vec<Option<Skip>> = set
        .rows()
        .iter()
        .map(|r| match &r.kind {
            Kind::Skipped { reason, .. } => Some(*reason),
            _ => None,
        })
        .collect();
    use Skip::*;
    assert_eq!(
        reasons,
        [
            Some(GeodataMissing),
            Some(UnsupportedKind),
            Some(AppRule),
            Some(UnsupportedKind),
            Some(UnsupportedKind),
            Some(UnsupportedKind),
            Some(UnsupportedKind),
            None
        ]
    );
    assert_eq!(set.skipped(), 8);
    assert_eq!(GeodataMissing.as_str(), "geodata_missing");
}

#[test]
fn kinds_are_read_in_any_case() {
    assert_eq!(Kind::clash("domain-suffix"), Kind::DomainSuffix);
    assert_eq!(Kind::clash("IP-CIDR6"), Kind::IpCidr);
    assert_eq!(
        Kind::clash("uid"),
        Kind::Skipped {
            name: "uid".into(),
            reason: Skip::AppRule
        }
    );
}

#[test]
fn values_that_do_not_parse_refuse_the_source() {
    for bad in [
        rule("IP-CIDR", &["192.0.2.1"], "DIRECT"),
        rule("IP-CIDR", &["192.0.2.0/33"], "DIRECT"),
        rule("IP-CIDR6", &["::/129"], "DIRECT"),
        rule("IP-CIDR", &["example.com/8"], "DIRECT"),
        rule("DST-PORT", &["65536"], "DIRECT"),
        rule("NETWORK", &["icmp"], "DIRECT"),
        rule("DOMAIN", &["."], "DIRECT"),
        rule("DOMAIN-KEYWORD", &[""], "DIRECT"),
        rule("DOMAIN", &[], "DIRECT"),
        rule("MATCH", &["x"], "DIRECT"),
    ] {
        assert!(refused(std::slice::from_ref(&bad)), "{bad:?}");
    }
}

#[test]
fn a_missing_target_or_member_refuses_the_source() {
    assert!(refused(&[rule("MATCH", &[], "Nowhere")]));
    // A skipped rule's target is checked too.
    assert!(refused(&[rule("GEOSITE", &["cn"], "Nowhere")]));
    let dangling = [group("Proxy", &["g-Gone"])];
    assert!(RuleSet::compile(&dangling, &node, &[]).is_err());
    let twice = [group("Proxy", &["n-1"]), group("Proxy", &["n-2"])];
    assert!(RuleSet::compile(&twice, &node, &[]).is_err());
}

#[test]
fn group_cycles_refuse_the_source() {
    let cycles: [&[GroupDef]; 3] = [
        &[group("A", &["g-A"])],
        &[group("A", &["g-B"]), group("B", &["n-1", "g-A"])],
        &[
            group("A", &["g-B"]),
            group("B", &["g-C"]),
            group("C", &["g-A"]),
        ],
    ];
    for groups in cycles {
        assert_eq!(
            RuleSet::compile(groups, &node, &[]),
            Err(ErrorCode::ParseFailed)
        );
    }
    // Two paths to one group are no cycle.
    let diamond = [
        group("A", &["g-B", "g-C"]),
        group("B", &["g-D"]),
        group("C", &["g-D"]),
        group("D", &["n-1"]),
    ];
    assert!(RuleSet::compile(&diamond, &node, &[]).is_ok());
}
