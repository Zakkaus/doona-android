//! DNS the core answers itself (core-api.md, "DNS and UDP"): a query that reaches the virtual
//! resolver, or any UDP/53, is forwarded as DNS over TCP through the flow's route, to the first
//! upstream that answers. TCP works through any outbound, including those without UDP.
//!
//! An answer too large for the client's UDP buffer goes back truncated (TC set, question only),
//! so the client retries over TCP/53, which the core also serves. A query that no upstream
//! answers, or whose route refuses it, gets SERVFAIL at once instead of a client timeout. What an
//! answer maps goes to the generation's [`Names`] before the client sees it.

pub(crate) mod names;

use crate::tunnel::route::Route;
use names::Names;
use std::io::{self, ErrorKind};
use std::net::{Ipv4Addr, SocketAddr};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// The upstream resolvers, in order.
pub const UPSTREAMS: [SocketAddr; 2] = [
    SocketAddr::new(std::net::IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), 53),
    SocketAddr::new(std::net::IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)), 53),
];

/// One upstream's share of a query, from dial to answer.
const EXCHANGE: Duration = Duration::from_secs(5);
const HEADER: usize = 12;
/// RFC 1035's UDP limit without EDNS.
const UDP_PLAIN: usize = 512;
/// The largest UDP payload an IPv4 packet carries.
const UDP_MAX: usize = 65_507;
const TYPE_OPT: u16 = 41;

fn u16_at(m: &[u8], at: usize) -> Option<u16> {
    Some(u16::from_be_bytes([*m.get(at)?, *m.get(at + 1)?]))
}

fn count(m: &[u8], at: usize) -> usize {
    u16_at(m, at).map_or(0, usize::from)
}

/// A message is a query when it has a header with QR clear.
pub fn is_query(m: &[u8]) -> bool {
    m.len() >= HEADER && m[2] & 0x80 == 0
}

/// The offset just past the (possibly compressed) name at `at`.
fn skip_name(m: &[u8], mut at: usize) -> Option<usize> {
    // A name has at most 127 labels; the bound also stops pointer-free loops on bad input.
    for _ in 0..128 {
        let len = *m.get(at)?;
        match len & 0xc0 {
            0x00 if len == 0 => return Some(at + 1),
            0x00 => at += 1 + usize::from(len),
            0xc0 => return (at + 2 <= m.len()).then_some(at + 2),
            _ => return None,
        }
    }
    None
}

/// The offset just past the question section.
fn question_end(m: &[u8]) -> Option<usize> {
    let mut at = HEADER;
    for _ in 0..count(m, 4) {
        at = skip_name(m, at)? + 4;
    }
    (at <= m.len()).then_some(at)
}

/// The offset just past the resource record at `at`, and its type and class.
fn skip_record(m: &[u8], at: usize) -> Option<(usize, u16, u16)> {
    let at = skip_name(m, at)?;
    let (kind, class, rdlen) = (u16_at(m, at)?, u16_at(m, at + 2)?, u16_at(m, at + 8)?);
    let end = at + 10 + usize::from(rdlen);
    (end <= m.len()).then_some((end, kind, class))
}

/// The largest UDP answer the client takes: its EDNS buffer size, else 512; never more than an
/// IPv4 datagram holds.
pub fn udp_limit(query: &[u8]) -> usize {
    let Some(mut at) = question_end(query) else {
        return UDP_PLAIN;
    };
    let records = count(query, 6) + count(query, 8) + count(query, 10);
    for i in 0..records {
        let Some((end, kind, class)) = skip_record(query, at) else {
            break;
        };
        if kind == TYPE_OPT && i >= count(query, 6) + count(query, 8) {
            return usize::from(class).clamp(UDP_PLAIN, UDP_MAX);
        }
        at = end;
    }
    UDP_PLAIN
}

/// A reply that keeps the header and question of `m`, with no records.
fn bare_reply(m: &[u8]) -> Vec<u8> {
    let end = question_end(m).unwrap_or(HEADER);
    let mut r = m[..end].to_vec();
    if end == HEADER {
        r[4..6].fill(0);
    }
    r[2] |= 0x80;
    r[6..12].fill(0);
    r
}

/// SERVFAIL for `query`: same id, opcode, RD and question; RA set.
pub fn servfail(query: &[u8]) -> Vec<u8> {
    let mut r = bare_reply(query);
    r[2] &= 0xf9; // AA and TC clear
    r[3] = 0x80 | 2;
    r
}

/// `answer` as it may go back over UDP to the sender of `query`: whole, else truncated (TC set)
/// to its question, else to its header when even the question does not fit.
pub fn fit_udp(answer: Vec<u8>, query: &[u8]) -> Vec<u8> {
    let limit = udp_limit(query);
    if answer.len() <= limit {
        return answer;
    }
    let mut r = bare_reply(&answer);
    if r.len() > limit {
        r.truncate(HEADER);
        r[4..6].fill(0);
    }
    r[2] |= 0x02;
    r
}

/// Sends `query` to each upstream in turn through `route`; the first answer with the query's
/// id wins.
async fn exchange(upstreams: &[SocketAddr], route: &Route, query: &[u8]) -> Option<Vec<u8>> {
    for &server in upstreams {
        match tokio::time::timeout(EXCHANGE, exchange_one(server, route, query)).await {
            Ok(Ok(a)) if a.len() >= HEADER && a[..2] == query[..2] => return Some(a),
            _ => {}
        }
    }
    None
}

async fn exchange_one(server: SocketAddr, route: &Route, query: &[u8]) -> io::Result<Vec<u8>> {
    let mut s = route.connect(server).await?;
    write_framed(&mut s, query).await?;
    read_framed(&mut s).await
}

async fn write_framed<S: AsyncWrite + Unpin>(s: &mut S, m: &[u8]) -> io::Result<()> {
    let len = u16::try_from(m.len()).map_err(|_| io::Error::from(ErrorKind::InvalidInput))?;
    let mut framed = Vec::with_capacity(m.len() + 2);
    framed.extend_from_slice(&len.to_be_bytes());
    framed.extend_from_slice(m);
    s.write_all(&framed).await
}

async fn read_framed<S: AsyncRead + Unpin>(s: &mut S) -> io::Result<Vec<u8>> {
    let len = s.read_u16().await?;
    let mut m = vec![0; usize::from(len)];
    s.read_exact(&mut m).await?;
    Ok(m)
}

/// The answer to `query`, or SERVFAIL; None when `query` is not a query. The addresses an answer
/// gives are mapped in `names` before it is returned, so a flow the client opens to one finds its
/// name.
pub(crate) async fn answer(
    upstreams: &[SocketAddr],
    (route, names): (&Route, &Names),
    query: &[u8],
) -> Option<Vec<u8>> {
    if !is_query(query) {
        return None;
    }
    let answer = if route.refused() {
        None
    } else {
        exchange(upstreams, route, query).await
    };
    if let Some(a) = &answer {
        names.learn(a);
    }
    Some(answer.unwrap_or_else(|| servfail(query)))
}

/// Answers the framed queries of one TCP/53 connection in turn until the client closes it.
pub(crate) async fn serve_tcp<S: AsyncRead + AsyncWrite + Unpin>(
    io: &mut S,
    upstreams: &[SocketAddr],
    route: (&Route, &Names),
) -> io::Result<()> {
    loop {
        let query = match read_framed(io).await {
            Ok(q) => q,
            Err(e) if e.kind() == ErrorKind::UnexpectedEof => break,
            Err(e) => return Err(e),
        };
        if let Some(a) = answer(upstreams, route, &query).await {
            write_framed(io, &a).await?;
        }
    }
    io.shutdown().await
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// A query for `name` A, with an EDNS OPT record of `edns` bytes when given.
    pub fn query(id: u16, name: &str, edns: Option<u16>) -> Vec<u8> {
        let mut m = id.to_be_bytes().to_vec();
        m.extend_from_slice(&[0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, u8::from(edns.is_some())]);
        for label in name.split('.') {
            m.push(label.len() as u8);
            m.extend_from_slice(label.as_bytes());
        }
        m.extend_from_slice(&[0, 0, 1, 0, 1]);
        if let Some(size) = edns {
            m.extend_from_slice(&[0, 0, 41]);
            m.extend_from_slice(&size.to_be_bytes());
            m.extend_from_slice(&[0, 0, 0, 0, 0, 0]);
        }
        m
    }

    /// An answer to `q` with `n` A records, each a compressed pointer to the question name.
    pub fn answer_to(q: &[u8], n: u16) -> Vec<u8> {
        let end = question_end(q).unwrap();
        let mut a = q[..end].to_vec();
        a[2] |= 0x80;
        a[3] = 0x80;
        a[6..8].copy_from_slice(&n.to_be_bytes());
        a[8..12].fill(0);
        for i in 0..n {
            a.extend_from_slice(&[0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 10, 0]);
            a.extend_from_slice(&i.to_be_bytes());
        }
        a
    }

    #[test]
    fn servfail_keeps_id_and_question() {
        let q = query(0xbeef, "example.org", Some(1232));
        let r = servfail(&q);
        assert_eq!(&r[..2], &[0xbe, 0xef]);
        assert_eq!(r[2], 0x81, "QR and RD");
        assert_eq!(r[3] & 0x0f, 2, "SERVFAIL");
        assert_eq!(count(&r, 4), 1);
        assert_eq!(count(&r, 10), 0, "the OPT record is gone");
        assert_eq!(r.len(), question_end(&q).unwrap());
        // A query whose question cannot be parsed still gets a header-only SERVFAIL.
        let bad = [0x12, 0x34, 0x01, 0, 0, 1, 0, 0, 0, 0, 0, 0, 5, b'a'];
        let r = servfail(&bad);
        assert_eq!(r.len(), HEADER);
        assert_eq!(count(&r, 4), 0);
    }

    #[test]
    fn udp_limit_reads_edns() {
        assert_eq!(udp_limit(&query(1, "a.example", None)), 512);
        assert_eq!(udp_limit(&query(1, "a.example", Some(1232))), 1232);
        assert_eq!(udp_limit(&query(1, "a.example", Some(100))), 512);
        assert_eq!(udp_limit(&[0u8; 5]), 512);
    }

    #[test]
    fn large_answers_are_truncated_for_udp() {
        let q = query(7, "big.example", None);
        let small = answer_to(&q, 2);
        assert_eq!(fit_udp(small.clone(), &q), small);
        let big = answer_to(&q, 40);
        assert!(big.len() > 512);
        let t = fit_udp(big, &q);
        assert_eq!(t[2] & 0x02, 0x02, "TC");
        assert_eq!(count(&t, 6), 0);
        assert_eq!(t.len(), question_end(&q).unwrap());
        let q = query(7, "big.example", Some(4096));
        let big = answer_to(&q, 40);
        assert_eq!(fit_udp(big.clone(), &q), big);
    }

    /// A query with one A question per name.
    fn questions(id: u16, names: &[&str]) -> Vec<u8> {
        let mut m = query(id, names[0], None);
        m[5] = names.len() as u8;
        for name in &names[1..] {
            for label in name.split('.') {
                m.push(label.len() as u8);
                m.extend_from_slice(label.as_bytes());
            }
            m.extend_from_slice(&[0, 0, 1, 0, 1]);
        }
        m
    }

    #[test]
    fn a_question_too_large_for_udp_is_dropped_from_the_truncated_reply() {
        let long = ["a", "b", "c"].map(|c| [c.repeat(63), c.repeat(63), c.repeat(63)].join("."));
        let q = questions(9, &long.each_ref().map(String::as_str));
        assert!(question_end(&q).unwrap() > UDP_PLAIN);
        let t = fit_udp(answer_to(&q, 1), &q);
        assert_eq!(t.len(), HEADER);
        assert_eq!(t[2] & 0x82, 0x82, "QR and TC");
        assert_eq!(count(&t, 4), 0);
        assert_eq!(&t[..2], &q[..2]);
    }

    #[test]
    fn the_udp_limit_stops_at_the_largest_ipv4_payload() {
        assert_eq!(udp_limit(&query(1, "a.example", Some(65535))), 65507);
        assert_eq!(udp_limit(&query(1, "a.example", Some(65507))), 65507);
    }

    /// A DNS-over-TCP server on loopback that answers each query with two A records.
    fn upstream() -> SocketAddr {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        std::thread::spawn(move || {
            use std::io::{Read, Write};
            for mut s in listener.incoming().flatten() {
                let mut len = [0; 2];
                while s.read_exact(&mut len).is_ok() {
                    let mut q = vec![0; usize::from(u16::from_be_bytes(len))];
                    s.read_exact(&mut q).unwrap();
                    let a = answer_to(&q, 2);
                    s.write_all(&(a.len() as u16).to_be_bytes()).unwrap();
                    s.write_all(&a).unwrap();
                }
            }
        });
        addr
    }

    #[tokio::test]
    async fn an_answer_maps_its_addresses_before_the_client_gets_it() {
        let names = Names::new();
        let upstreams = [upstream()];
        let q = query(3, "Learn.Example", Some(1232));
        let a = answer(&upstreams, (&Route::Direct, &names), &q)
            .await
            .unwrap();
        assert_eq!(count(&a, 6), 2);
        for host in [0, 1] {
            let ip = std::net::IpAddr::from([10, 0, 0, host]);
            assert_eq!(names.name(ip).as_deref(), Some("learn.example"));
        }
        // A refused route answers SERVFAIL and maps nothing.
        let names = Names::new();
        let a = answer(&upstreams, (&Route::Refuse, &names), &q)
            .await
            .unwrap();
        assert_eq!(a[3] & 0x0f, 2);
        assert_eq!(names.len(), 0);
    }

    #[test]
    fn names_with_pointers_and_bad_lengths() {
        let q = query(1, "x.y.example", None);
        assert_eq!(skip_name(&q, HEADER), Some(q.len() - 4));
        assert_eq!(skip_name(&[0xc0, 12], 0), Some(2));
        assert_eq!(skip_name(&[0xc0], 0), None);
        assert_eq!(skip_name(&[0x40, 0], 0), None);
        assert_eq!(skip_name(&[3, b'a'], 0), None);
        assert!(!is_query(&answer_to(&q, 1)));
        assert!(is_query(&q));
    }
}
