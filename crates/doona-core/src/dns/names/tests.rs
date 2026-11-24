use super::*;
use crate::testutil::alloc::peak;

/// A name in wire form, uncompressed.
fn wire(name: &str) -> Vec<u8> {
    let mut w = Vec::new();
    for label in name.split('.') {
        w.push(label.len() as u8);
        w.extend_from_slice(label.as_bytes());
    }
    w.push(0);
    w
}

/// One record: its owner in wire form (a name, or a pointer), type, TTL and data.
struct Rr<'a> {
    owner: Vec<u8>,
    kind: u16,
    ttl: u32,
    data: &'a [u8],
}

/// The question's name, pointed to.
const AT_QUESTION: [u8; 2] = [0xc0, 12];

fn a(octets: &[u8; 4], ttl: u32) -> Rr<'_> {
    Rr {
        owner: AT_QUESTION.to_vec(),
        kind: TYPE_A,
        ttl,
        data: octets,
    }
}

/// A NOERROR answer to `kind` `name` in `class`, with `records`.
fn answer_with(name: &str, (kind, class): (u16, u16), records: &[Rr<'_>]) -> Vec<u8> {
    let mut m = vec![0x12, 0x34, 0x81, 0x80, 0, 1];
    m.extend_from_slice(&(records.len() as u16).to_be_bytes());
    m.extend_from_slice(&[0, 0, 0, 0]);
    m.extend(wire(name));
    m.extend_from_slice(&kind.to_be_bytes());
    m.extend_from_slice(&class.to_be_bytes());
    for r in records {
        m.extend_from_slice(&r.owner);
        m.extend_from_slice(&r.kind.to_be_bytes());
        m.extend_from_slice(&CLASS_IN.to_be_bytes());
        m.extend_from_slice(&r.ttl.to_be_bytes());
        m.extend_from_slice(&(r.data.len() as u16).to_be_bytes());
        m.extend_from_slice(r.data);
    }
    m
}

fn answer(name: &str, records: &[Rr<'_>]) -> Vec<u8> {
    answer_with(name, (TYPE_A, CLASS_IN), records)
}

fn v4(a: u8, b: u8, c: u8, d: u8) -> IpAddr {
    IpAddr::V4(Ipv4Addr::new(a, b, c, d))
}

fn name_of(names: &Names, ip: IpAddr, now_ms: u64) -> Option<String> {
    names.name_at(ip, now_ms).map(|n| n.to_string())
}

#[test]
fn an_answer_maps_its_addresses_to_the_question_name() {
    let names = Names::new();
    let m = answer(
        "WWW.Example.COM",
        &[a(&[93, 184, 215, 14], 300), a(&[93, 184, 215, 15], 300)],
    );
    names.learn_at(&m, 0);
    assert_eq!(names.len(), 2);
    for ip in [v4(93, 184, 215, 14), v4(93, 184, 215, 15)] {
        assert_eq!(name_of(&names, ip, 1).as_deref(), Some("www.example.com"));
    }
    assert_eq!(name_of(&names, v4(93, 184, 215, 16), 1), None);
    // An IPv4 address in its mapped IPv6 form is the same address; another IPv6 one has none.
    let mapped = IpAddr::V6(Ipv4Addr::new(93, 184, 215, 14).to_ipv6_mapped());
    assert_eq!(
        name_of(&names, mapped, 1).as_deref(),
        Some("www.example.com")
    );
    assert_eq!(name_of(&names, "2001:db8::1".parse().unwrap(), 1), None);
}

#[test]
fn diagnostics_peeks_only_the_ipv6_address_answered() {
    let ip = "2001:db8::1234".parse::<std::net::Ipv6Addr>().unwrap();
    let octets = ip.octets();
    let rr = Rr {
        owner: AT_QUESTION.to_vec(),
        kind: TYPE_AAAA,
        ttl: 300,
        data: &octets,
    };
    let reply = answer_with("IPv6.Example", (TYPE_AAAA, CLASS_IN), &[rr]);
    let names = Names::new();
    names.learn(&reply, &reply);
    assert_eq!(names.peek(ip.into()).as_deref(), Some("ipv6.example"));
    assert_eq!(names.peek("2001:db8::1235".parse().unwrap()), None);
}

#[test]
fn a_cname_chain_maps_to_the_question_name() {
    let names = Names::new();
    let edge = wire("ipv4-c001.1.oca.nflxvideo.net");
    let alias = wire("www.dradis.netflix.com");
    // The question, then CNAME www.netflix.com -> www.dradis.netflix.com -> the edge, then A
    // records the edge owns: the second CNAME's data, pointed to.
    let first_data = 12 + wire("www.netflix.com").len() + 4 + 12;
    let second_data = first_data + alias.len() + 12;
    let to = |at: usize| vec![0xc0 | (at >> 8) as u8, at as u8];
    let records = [
        Rr {
            owner: AT_QUESTION.to_vec(),
            kind: 5,
            ttl: 300,
            data: &alias,
        },
        Rr {
            owner: to(first_data),
            kind: 5,
            ttl: 300,
            data: &edge,
        },
        Rr {
            owner: to(second_data),
            kind: TYPE_A,
            ttl: 60,
            data: &[45, 57, 3, 1],
        },
        Rr {
            owner: to(second_data),
            kind: TYPE_A,
            ttl: 60,
            data: &[45, 57, 3, 2],
        },
    ];
    let m = answer("www.netflix.com", &records);
    assert_eq!(
        read_name(&m, second_data).as_deref(),
        Some("ipv4-c001.1.oca.nflxvideo.net")
    );
    names.learn_at(&m, 0);
    assert_eq!(names.len(), 2);
    for ip in [v4(45, 57, 3, 1), v4(45, 57, 3, 2)] {
        assert_eq!(name_of(&names, ip, 1).as_deref(), Some("www.netflix.com"));
    }
}

/// Only the A records the question's name reaches through its CNAME chain map: an address of
/// another name in the same answer, or one behind an alias the chain does not reach, maps nothing.
/// Owner names match in any case.
#[test]
fn only_what_the_question_name_reaches_maps() {
    let edge = wire("edge.nflxvideo.net");
    let (other, side) = (wire("other.example"), wire("side.example"));
    // The question, then CNAME www.netflix.com -> edge.nflxvideo.net, whose data starts after
    // the record's pointer and fixed fields.
    let edge_at = 12 + wire("www.netflix.com").len() + 4 + 12;
    let to = |at: usize| vec![0xc0 | (at >> 8) as u8, at as u8];
    let records = [
        Rr {
            owner: AT_QUESTION.to_vec(),
            kind: 5,
            ttl: 300,
            data: &edge,
        },
        // An address of a name the chain never reaches.
        Rr {
            owner: other.clone(),
            kind: TYPE_A,
            ttl: 300,
            data: &[203, 0, 113, 9],
        },
        // An alias the chain does not reach, and the address behind it.
        Rr {
            owner: other.clone(),
            kind: 5,
            ttl: 300,
            data: &side,
        },
        Rr {
            owner: side.clone(),
            kind: TYPE_A,
            ttl: 300,
            data: &[203, 0, 113, 10],
        },
        Rr {
            owner: to(edge_at),
            kind: TYPE_A,
            ttl: 60,
            data: &[45, 57, 3, 1],
        },
        Rr {
            owner: wire("EDGE.NflxVideo.NET"),
            kind: TYPE_A,
            ttl: 60,
            data: &[45, 57, 3, 2],
        },
    ];
    let m = answer("www.netflix.com", &records);
    let names = Names::new();
    names.learn_at(&m, 0);
    for ip in [v4(45, 57, 3, 1), v4(45, 57, 3, 2)] {
        assert_eq!(name_of(&names, ip, 1).as_deref(), Some("www.netflix.com"));
    }
    for ip in [v4(203, 0, 113, 9), v4(203, 0, 113, 10)] {
        assert_eq!(name_of(&names, ip, 1), None, "{ip}");
    }
    assert_eq!(names.len(), 2);
}

/// An alias back to a name already in the chain ends it; what the chain reached still maps.
#[test]
fn an_alias_loop_ends_the_chain() {
    let (alias, question) = (wire("alias.example"), wire("loop.example"));
    let records = [
        Rr {
            owner: AT_QUESTION.to_vec(),
            kind: 5,
            ttl: 300,
            data: &alias,
        },
        Rr {
            owner: alias.clone(),
            kind: 5,
            ttl: 300,
            data: &question,
        },
        Rr {
            owner: alias.clone(),
            kind: TYPE_A,
            ttl: 300,
            data: &[192, 0, 2, 7],
        },
    ];
    let names = Names::new();
    names.learn_at(&answer("loop.example", &records), 0);
    assert_eq!(
        name_of(&names, v4(192, 0, 2, 7), 1).as_deref(),
        Some("loop.example")
    );
}

/// A CNAME whose data is not exactly one name, ending where its RDLENGTH does, makes the whole answer
/// map nothing: with no data, or data that stops inside its name, the alias would be read from the
/// next record; trailing bytes are no name either.
#[test]
fn a_cname_whose_data_is_not_one_name_maps_nothing() {
    let edge = wire("edge.example");
    let mut trailing = edge.clone();
    trailing.push(0);
    // Data that stops after the label `edge`, so the next record's owner, `example`, would end it.
    let cut = &edge[..5];
    let cases: [(&str, Vec<Rr<'_>>); 3] = [
        (
            "no data: the next record's owner would be the alias",
            vec![
                Rr {
                    owner: AT_QUESTION.to_vec(),
                    kind: TYPE_CNAME,
                    ttl: 300,
                    data: &[],
                },
                Rr {
                    owner: wire("other.example"),
                    kind: TYPE_A,
                    ttl: 300,
                    data: &[203, 0, 113, 9],
                },
            ],
        ),
        (
            "a byte after the name",
            vec![
                Rr {
                    owner: AT_QUESTION.to_vec(),
                    kind: TYPE_CNAME,
                    ttl: 300,
                    data: &trailing,
                },
                a(&[203, 0, 113, 10], 300),
                Rr {
                    owner: edge.clone(),
                    kind: TYPE_A,
                    ttl: 300,
                    data: &[203, 0, 113, 11],
                },
            ],
        ),
        (
            "a name that runs past its data",
            vec![
                Rr {
                    owner: AT_QUESTION.to_vec(),
                    kind: TYPE_CNAME,
                    ttl: 300,
                    data: cut,
                },
                Rr {
                    owner: wire("example"),
                    kind: TYPE_A,
                    ttl: 300,
                    data: &[203, 0, 113, 12],
                },
                Rr {
                    owner: edge.clone(),
                    kind: TYPE_A,
                    ttl: 300,
                    data: &[203, 0, 113, 13],
                },
            ],
        ),
    ];
    for (case, records) in &cases {
        let names = Names::new();
        names.learn_at(&answer("www.netflix.com", records), 0);
        let named: Vec<_> = (9..=13)
            .filter_map(|host| Some((host, name_of(&names, v4(203, 0, 113, host), 1)?)))
            .collect();
        assert!(named.is_empty(), "{case}: {named:?}");
    }
}

/// The question is compared by the name it reads, not its bytes: a query and an answer whose
/// question is the same pointer, to a name further on in each, match only when the names do.
#[test]
fn questions_match_by_the_names_they_read() {
    // A header, a question whose name points past it, then at 18 the name it points to.
    let message = |flags: [u8; 2], answers: u8, rest: &[u8]| {
        let mut m = vec![0x12, 0x34, flags[0], flags[1], 0, 1, 0, answers, 0, 0, 0, 0];
        m.extend_from_slice(&[0xc0, 18, 0, 1, 0, 1]);
        m.extend_from_slice(rest);
        m
    };
    let query = message([0x01, 0x00], 0, &wire("asked.example"));
    // The answer's record owner sits at 18, so the answer's question reads as that name.
    let answer_for = |owner: &str| {
        let mut rest = wire(owner);
        rest.extend_from_slice(&[0, 1, 0, 1, 0, 0, 1, 44, 0, 4, 203, 0, 113, 9]);
        message([0x81, 0x80], 1, &rest)
    };
    let names = Names::new();
    names.learn(&query, &answer_for("other.example"));
    assert_eq!(names.len(), 0, "a question of another name maps");
    names.learn(&query, &answer_for("Asked.Example"));
    assert_eq!(
        names.name(v4(203, 0, 113, 9)).as_deref(),
        Some("asked.example")
    );
}

#[test]
fn a_ttl_is_held_to_one_minute_and_one_hour() {
    let names = Names::new();
    let ip = v4(203, 0, 113, 1);
    for (ttl, kept_s) in [
        (0, MIN_TTL),
        (5, MIN_TTL),
        (300, 300),
        (86_400, MAX_TTL),
        // A TTL with the top bit set is read as zero (RFC 2181 §8).
        (0x8000_0000, MIN_TTL),
    ] {
        let now = 1_000_000;
        names.learn_at(&answer("ttl.example", &[a(&[203, 0, 113, 1], ttl)]), now);
        let end = now + u64::from(kept_s) * 1000;
        assert_eq!(
            name_of(&names, ip, end - 1).as_deref(),
            Some("ttl.example"),
            "{ttl}"
        );
        assert_eq!(name_of(&names, ip, end), None, "{ttl}");
        // An entry past its time is dropped when it is looked up.
        assert_eq!(names.len(), 0, "{ttl}");
    }
}

#[test]
fn only_a_successful_answer_to_one_a_question_maps() {
    let ip = [198, 51, 100, 7];
    let good = answer("ok.example", &[a(&ip, 60)]);
    let mut nxdomain = good.clone();
    nxdomain[3] |= 3;
    let mut query = good.clone();
    query[2] &= 0x7f;
    let mut status = good.clone();
    status[2] |= 2 << 3;
    let mut two = good.clone();
    two[5] = 2;
    let mut truncated = good.clone();
    truncated[2] |= 0x02;
    let refused = [
        nxdomain,
        query,
        status,
        two,
        truncated,
        answer_with("ok.example", (28, CLASS_IN), &[a(&ip, 60)]),
        answer_with("ok.example", (TYPE_A, 3), &[a(&ip, 60)]),
        answer("bad name.example", &[a(&ip, 60)]),
        answer("bad\u{e9}.example", &[a(&ip, 60)]),
        answer(&format!("{}.example", "a".repeat(250)), &[a(&ip, 60)]),
        // A label with a dot in it would read as another name.
        {
            let mut m = answer("xdot.example", &[a(&ip, 60)]);
            m[13] = b'.';
            m
        },
        good[..HEADER].to_vec(),
        Vec::new(),
    ];
    for (i, m) in refused.iter().enumerate() {
        let names = Names::new();
        names.learn_at(m, 0);
        assert_eq!(names.len(), 0, "case {i}");
    }
    let names = Names::new();
    names.learn_at(&good, 0);
    assert_eq!(names.len(), 1);
}

#[test]
fn records_other_than_a_in_in_map_nothing_and_a_cut_answer_keeps_what_came_before() {
    let names = Names::new();
    let v6 = [0x20, 1, 0xd, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1];
    let records = [
        Rr {
            owner: AT_QUESTION.to_vec(),
            kind: 28,
            ttl: 60,
            data: &v6,
        },
        // Four bytes of data, but not an A record.
        Rr {
            owner: AT_QUESTION.to_vec(),
            kind: 16,
            ttl: 60,
            data: b"txt!",
        },
        a(&[192, 0, 2, 9], 60),
        a(&[192, 0, 2, 10], 60),
    ];
    let m = answer("mixed.example", &records);
    names.learn_at(&m, 0);
    assert_eq!(names.len(), 2);
    let cut = &m[..m.len() - 3];
    let names = Names::new();
    names.learn_at(cut, 0);
    assert_eq!(names.len(), 1);
    assert_eq!(
        name_of(&names, v4(192, 0, 2, 9), 1).as_deref(),
        Some("mixed.example")
    );
}

#[test]
fn a_pointer_loop_in_the_question_maps_nothing() {
    let mut m = answer("loop.example", &[a(&[192, 0, 2, 1], 60)]);
    m[12] = 0xc0;
    m[13] = 12;
    assert_eq!(read_name(&m, HEADER), None);
    let names = Names::new();
    names.learn_at(&m, 0);
    assert_eq!(names.len(), 0);
}

#[test]
fn the_last_answer_for_an_address_wins() {
    let names = Names::new();
    names.learn_at(&answer("first.example", &[a(&[192, 0, 2, 1], 3_600)]), 0);
    names.learn_at(&answer("second.example", &[a(&[192, 0, 2, 1], 60)]), 10);
    assert_eq!(names.len(), 1);
    assert_eq!(
        name_of(&names, v4(192, 0, 2, 1), 11).as_deref(),
        Some("second.example")
    );
    // The later answer's TTL counts.
    assert_eq!(name_of(&names, v4(192, 0, 2, 1), 60_010), None);
}

fn host(i: u32) -> [u8; 4] {
    (0x0a00_0000 + i).to_be_bytes()
}

/// An answer maps every address it gives: EDNS's 1,232 bytes hold 65 compressed A records, and an
/// answer over TCP some 4,000.
#[test]
fn one_answer_maps_every_address_it_gives() {
    let octets: Vec<[u8; 4]> = (0..4_000).map(host).collect();
    let records: Vec<Rr<'_>> = octets.iter().map(|o| a(o, 60)).collect();
    let m = answer("many.example", &records);
    assert!(m.len() <= usize::from(u16::MAX), "{}", m.len());
    let names = Names::new();
    names.learn_at(&m, 0);
    assert_eq!(names.len(), 4_000);
    for i in [0, 64, 3_999] {
        let ip = IpAddr::from(host(i));
        assert_eq!(
            name_of(&names, ip, 1).as_deref(),
            Some("many.example"),
            "{i}"
        );
    }
}

/// A large answer into a full cache pushes out as many of the least recently used.
#[test]
fn a_large_answer_into_a_full_cache_pushes_out_the_least_recently_used() {
    let names = Names::new();
    for i in 0..CAPACITY as u32 {
        names.learn_at(&answer(&format!("h{i}.example"), &[a(&host(i), 3_600)]), 0);
    }
    let octets: Vec<[u8; 4]> = (0..300).map(|i| host(CAPACITY as u32 + i)).collect();
    let records: Vec<Rr<'_>> = octets.iter().map(|o| a(o, 3_600)).collect();
    names.learn_at(&answer("many.example", &records), 1);
    assert_eq!(names.len(), CAPACITY);
    for o in &octets {
        let ip = IpAddr::from(*o);
        assert_eq!(name_of(&names, ip, 2).as_deref(), Some("many.example"));
    }
    assert_eq!(name_of(&names, IpAddr::from(host(299)), 2), None);
    let kept = IpAddr::from(host(300));
    assert_eq!(name_of(&names, kept, 2).as_deref(), Some("h300.example"));
}

#[test]
fn the_cache_keeps_8192_addresses_and_drops_the_least_recently_used() {
    assert_eq!(CAPACITY, 8_192);
    let names = Names::new();
    for i in 0..CAPACITY as u32 {
        names.learn_at(&answer(&format!("h{i}.example"), &[a(&host(i), 3_600)]), 0);
    }
    assert_eq!(names.len(), CAPACITY);
    // Looking the first up makes the second the least recently used.
    let first = IpAddr::from(host(0));
    assert_eq!(name_of(&names, first, 1).as_deref(), Some("h0.example"));
    names.learn_at(
        &answer("new.example", &[a(&host(CAPACITY as u32), 3_600)]),
        2,
    );
    assert_eq!(names.len(), CAPACITY);
    assert_eq!(name_of(&names, IpAddr::from(host(1)), 3), None);
    assert_eq!(name_of(&names, first, 3).as_deref(), Some("h0.example"));
    let new = IpAddr::from(host(CAPACITY as u32));
    assert_eq!(name_of(&names, new, 3).as_deref(), Some("new.example"));
    // An address already held takes its new name without pushing another out.
    names.learn_at(&answer("again.example", &[a(&host(2), 3_600)]), 4);
    assert_eq!(names.len(), CAPACITY);
    assert_eq!(
        name_of(&names, IpAddr::from(host(3)), 5).as_deref(),
        Some("h3.example")
    );
}

/// The heap a full cache holds, for `rules-r2-STATE.md`: names of a typical length, and the
/// longest names, each its own. Run with the release bench (`bench` in the test name).
#[test]
#[ignore = "a release-mode measurement; see rules-r2-STATE.md"]
fn bench_cache_memory() {
    for (what, len) in [("names of 24 bytes", 24), ("names of 253 bytes", 253)] {
        let answers: Vec<Vec<u8>> = (0..CAPACITY as u32)
            .map(|i| {
                // A distinct first label, then labels of up to 63 bytes to the length.
                let mut name = format!("{i:0>8}");
                while name.len() < len {
                    let room = (len - name.len() - 1).min(63);
                    name = format!("{name}.{}", "x".repeat(room));
                }
                answer(&name, &[a(&host(i), 3_600)])
            })
            .collect();
        let (names, peak_b, kept) = peak(|| {
            let names = Names::new();
            for m in &answers {
                names.learn_at(m, 0);
            }
            names
        });
        assert_eq!(names.len(), CAPACITY);
        assert_eq!(names.name_at(IpAddr::from(host(0)), 1).unwrap().len(), len);
        println!(
            "names cache, {what}: {} entries, kept {:.1} KiB, peak {:.1} KiB",
            names.len(),
            kept as f64 / 1024.0,
            peak_b as f64 / 1024.0
        );
    }
}

/// Long CNAME chains, in a file of their own for the size limit.
mod chains;
/// CNAME data that does not decompress.
mod malformed;
