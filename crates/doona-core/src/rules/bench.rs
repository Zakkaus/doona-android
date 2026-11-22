//! What rule mode costs (the owner asked about power): match time per flow, compile time and peak
//! memory, for a 20,000-line profile, for the template's shape, and for the worst sources the
//! limits let through. Run in release on the desktop:
//! `cargo test --locked --release --lib rules::bench -- --ignored --nocapture --test-threads 1`;
//! the numbers are in `rules-STATE.md`.

use super::tests::{Rng, group, rule};
use super::*;
use crate::testutil::alloc::peak;
use std::collections::HashSet;
use std::hint::black_box;
use std::net::{Ipv4Addr, Ipv6Addr};
use std::time::{Duration, Instant};

const TLDS: [&str; 8] = ["com", "net", "org", "cn", "io", "jp", "tv", "co.uk"];
const GROUPS: [&str; 8] = [
    "Proxy",
    "Auto",
    "Streaming",
    "AI",
    "Telegram",
    "Apple",
    "Microsoft",
    "Games",
];

fn label(rng: &mut Rng, len: usize) -> String {
    (0..len)
        .map(|_| char::from(b'a' + rng.below(26) as u8))
        .collect()
}

fn name(rng: &mut Rng) -> String {
    let tld = TLDS[rng.below(TLDS.len() as u64) as usize];
    let len = 4 + rng.below(9) as usize;
    format!("{}.{tld}", label(rng, len))
}

fn target(rng: &mut Rng) -> &'static str {
    match rng.below(10) {
        0 => "DIRECT",
        1 => "REJECT",
        _ => GROUPS[rng.below(GROUPS.len() as u64) as usize],
    }
}

/// The catalog's nodes, by id.
fn nodes() -> Vec<String> {
    (0..200).map(|i| format!("n-{i:016x}")).collect()
}

fn groups(nodes: &[String]) -> Vec<GroupDef> {
    let nodes: Vec<&str> = nodes.iter().map(String::as_str).collect();
    let mut groups = vec![group("Proxy", &["g-Auto"]), group("Auto", &nodes)];
    for g in &GROUPS[2..] {
        groups.push(group(g, &["g-Proxy", nodes[0], nodes[1]]));
    }
    groups
}

/// A random IPv4 address outside the special ranges' first octets.
fn global_v4(rng: &mut Rng) -> Ipv4Addr {
    Ipv4Addr::from(0x0100_0000 + rng.below(0xdf00_0000) as u32)
}

fn cidr_v4(rng: &mut Rng) -> String {
    format!("{}/{}", global_v4(rng), 8 + rng.below(17))
}

fn cidr_v6(rng: &mut Rng) -> String {
    let addr = Ipv6Addr::from((0x2000u128 << 112) | (rng.u128() >> 4));
    format!("{addr}/{}", 20 + rng.below(45))
}

/// A one-line row of `kind` for each value.
fn lines(rows: &mut Vec<RuleSpec>, rng: &mut Rng, kind: &str, values: Vec<String>) {
    for v in values {
        rows.push(rule(kind, &[&v], target(rng)));
    }
}

/// The names a flow can hit: suffixes, which a host under one hits, and exact names.
struct Hits {
    suffixes: Vec<String>,
    exact: Vec<String>,
}

impl Hits {
    /// A name the rules hold: a host under a suffix, or an exact name as it is.
    fn pick(&self, rng: &mut Rng) -> String {
        let i = rng.below((self.suffixes.len() + self.exact.len()) as u64) as usize;
        match self.suffixes.get(i) {
            Some(suffix) => format!("www.{suffix}"),
            None => self.exact[i - self.suffixes.len()].clone(),
        }
    }
}

/// 20,000 lines, one a row, shaped like a large inline Clash profile: mostly suffixes, then
/// names, keywords, CIDRs, a few ports and lines skipped, GeoIP and MATCH.
fn profile(rng: &mut Rng) -> (Vec<RuleSpec>, Hits) {
    let mut rows = vec![rule("GEOIP", &["private"], "DIRECT")];
    let suffixes: Vec<String> = (0..17_000).map(|_| name(rng)).collect();
    let exact: Vec<String> = (0..1_200).map(|_| format!("api.{}", name(rng))).collect();
    let keywords: Vec<String> = (0..300)
        .map(|_| {
            let len = 5 + rng.below(4) as usize;
            label(rng, len)
        })
        .collect();
    lines(&mut rows, rng, "DOMAIN-SUFFIX", suffixes.clone());
    lines(&mut rows, rng, "DOMAIN", exact.clone());
    lines(&mut rows, rng, "DOMAIN-KEYWORD", keywords);
    let v4 = (0..1_150).map(|_| cidr_v4(rng)).collect();
    lines(&mut rows, rng, "IP-CIDR", v4);
    let v6 = (0..200).map(|_| cidr_v6(rng)).collect();
    lines(&mut rows, rng, "IP-CIDR6", v6);
    lines(
        &mut rows,
        rng,
        "GEOSITE",
        (0..60).map(|i| format!("site{i}")).collect(),
    );
    lines(
        &mut rows,
        rng,
        "PROCESS-NAME",
        (0..20).map(|i| format!("app{i}")).collect(),
    );
    let ports = (0..67)
        .map(|_| (1 + rng.below(65_000)).to_string())
        .collect();
    lines(&mut rows, rng, "DST-PORT", ports);
    rows.push(rule("GEOIP", &["CN"], "DIRECT"));
    rows.push(rule("MATCH", &[], "Proxy"));
    (rows, Hits { suffixes, exact })
}

/// The template's shape in R2 (rule-mode.md, R2): private direct, a streaming list, an AI list,
/// CN direct, the rest through Proxy.
fn template() -> (Vec<RuleSpec>, Hits) {
    let streaming: Vec<String> = (0..40).map(|i| format!("stream{i}.com")).collect();
    let ai: Vec<String> = (0..15).map(|i| format!("ai{i}.com")).collect();
    let list = |values: &[String], to: &str| RuleSpec {
        kind: Kind::DomainSuffix,
        values: values.to_vec(),
        target: Target::Group(crate::ids::group_id(to)),
    };
    let rows = vec![
        rule("GEOIP", &["private"], "DIRECT"),
        list(&streaming, "Streaming"),
        list(&ai, "AI"),
        rule("GEOIP", &["CN"], "DIRECT"),
        rule("MATCH", &[], "Proxy"),
    ];
    let mut suffixes = streaming;
    suffixes.extend(ai);
    let hits = Hits {
        suffixes,
        exact: Vec::new(),
    };
    (rows, hits)
}

/// One flow's fields, owned.
struct Sample {
    domain: Option<String>,
    ip: IpAddr,
    port: u16,
    network: Network,
}

impl Sample {
    fn flow(&self) -> Flow<'_> {
        Flow {
            domain: self.domain.as_deref(),
            ip: self.ip,
            port: self.port,
            network: self.network,
        }
    }
}

fn cn_v4(rng: &mut Rng, cn: &[(u32, u32)]) -> IpAddr {
    let (first, last) = cn[rng.below(cn.len() as u64) as usize];
    IpAddr::V4(Ipv4Addr::from(
        first + rng.below(u64::from(last - first) + 1) as u32,
    ))
}

/// Flows: half named, a third of those after a name the rules hold (a host under a suffix, or
/// an exact name), the rest after none; the other half unnamed, to CN, other IPv4 and IPv6.
fn samples(rng: &mut Rng, hits: &Hits, n: usize) -> Vec<Sample> {
    let cn: Vec<(u32, u32)> = geoip::cn_ranges().collect();
    (0..n)
        .map(|_| {
            let roll = rng.below(100);
            let domain = match roll {
                0..17 => Some(hits.pick(rng)),
                17..50 => Some(format!("{}.{}", label(rng, 6), name(rng))),
                _ => None,
            };
            let ip = match rng.below(10) {
                0..4 => cn_v4(rng, &cn),
                4 => IpAddr::V6(Ipv6Addr::from((0x2400u128 << 112) | (rng.u128() >> 8))),
                _ => IpAddr::V4(global_v4(rng)),
            };
            let network = if roll.is_multiple_of(10) {
                Network::Udp
            } else {
                Network::Tcp
            };
            Sample {
                domain,
                ip,
                port: if roll.is_multiple_of(7) { 80 } else { 443 },
                network,
            }
        })
        .collect()
}

/// The mean time to match one of `samples`, over enough rounds to take about half a second.
fn per_flow(set: &RuleSet, samples: &[Sample]) -> Duration {
    for s in samples.iter().take(10_000) {
        black_box(set.matched(&s.flow()));
    }
    let mut rounds = 0u32;
    let start = Instant::now();
    while start.elapsed() < Duration::from_millis(500) {
        for s in samples {
            black_box(set.matched(black_box(&s.flow())));
        }
        rounds += 1;
    }
    start.elapsed() / (rounds * samples.len() as u32)
}

/// Compiles `rows` five times; prints the median time, the peak and what the set keeps, and the
/// match time per flow over `samples`. Members are looked up among the catalog's `nodes`, as the
/// catalog looks them up; the set is built before the clock starts.
fn measure(
    what: &str,
    (groups, nodes): (&[GroupDef], &[String]),
    rows: &[RuleSpec],
    samples: &[Sample],
) {
    let ids: HashSet<&str> = nodes.iter().map(String::as_str).collect();
    let node = |id: &str| ids.contains(id);
    let mut times: Vec<Duration> = (0..5)
        .map(|_| {
            let start = Instant::now();
            let set = black_box(RuleSet::compile(groups, &node, rows).unwrap());
            // Read the clock before the set is dropped: freeing it is no part of compiling.
            let took = start.elapsed();
            drop(set);
            took
        })
        .collect();
    times.sort();
    let (set, peak, kept) = peak(|| RuleSet::compile(groups, &node, rows).unwrap());
    let lines: usize = rows.iter().map(|r| r.values.len().max(1)).sum();
    let kib = |b: usize| b as f64 / 1024.0;
    println!(
        "{what}: {} rows, {lines} lines, {} indexed, {} trie nodes, {} port runs; compile {} µs, \
         peak {:.1} KiB, kept {:.1} KiB; match {} ns/flow",
        rows.len(),
        set.domains.len(),
        set.ips.nodes(),
        set.ports.runs(),
        times[2].as_micros(),
        kib(peak),
        kib(kept),
        per_flow(&set, samples).as_nanos(),
    );
}

#[test]
#[ignore = "a release-mode measurement; see the module comment"]
fn measure_profiles() {
    let mut rng = Rng(0x00b0_0c5e);
    let nodes = nodes();
    let groups = groups(&nodes);
    let catalog = (groups.as_slice(), nodes.as_slice());
    // The data file's size; what the `.so` holds is measured on the library (rules-STATE.md).
    println!("CN data file: {} bytes", geoip::cn_ranges().count() * 8);
    let (rows, hits) = template();
    let flows = samples(&mut rng, &hits, 100_000);
    measure("template", catalog, &rows, &flows);
    let (rows, hits) = profile(&mut rng);
    let flows = samples(&mut rng, &hits, 100_000);
    measure("20,000-line profile", catalog, &rows, &flows);
    let named: Vec<Sample> = flows.into_iter().filter(|s| s.domain.is_some()).collect();
    measure(
        "20,000-line profile, named flows only",
        catalog,
        &rows,
        &named,
    );
}

#[test]
#[ignore = "a release-mode measurement; see the module comment"]
fn measure_worst_sources() {
    let mut rng = Rng(0x0bad_5eed);
    let nodes = nodes();
    let groups = groups(&nodes);
    let catalog = (groups.as_slice(), nodes.as_slice());
    let hits = Hits {
        suffixes: vec!["example.com".to_owned()],
        exact: Vec::new(),
    };
    let flows = samples(&mut rng, &hits, 100_000);
    let mut v6 = Vec::new();
    let hosts = (0..20_000)
        .map(|_| format!("{}/128", Ipv6Addr::from(rng.u128())))
        .collect();
    lines(&mut v6, &mut rng, "IP-CIDR6", hosts);
    measure("20,000 IPv6 /128", catalog, &v6, &flows);
    let mut long = Vec::new();
    let names = (0..20_000)
        .map(|_| {
            // 63 + 63 + 63 + 61 bytes and three dots: MAX_VALUE.
            let labels: Vec<String> = [63, 63, 63, 61].map(|n| label(&mut rng, n)).into();
            labels.join(".")
        })
        .collect();
    lines(&mut long, &mut rng, "DOMAIN-SUFFIX", names);
    measure("20,000 suffixes of 253 bytes", catalog, &long, &flows);
    let mut mixed = Vec::new();
    let words = (0..1_000).map(|_| label(&mut rng, 253)).collect();
    lines(&mut mixed, &mut rng, "DOMAIN-KEYWORD", words);
    let ports = (0..19_000)
        .map(|_| {
            let first = rng.below(65_000);
            format!("{first}-{}", first + rng.below(500))
        })
        .collect();
    lines(&mut mixed, &mut rng, "DST-PORT", ports);
    measure(
        "1,000 keywords of 253 bytes and 19,000 port ranges",
        catalog,
        &mixed,
        &flows,
    );
}
