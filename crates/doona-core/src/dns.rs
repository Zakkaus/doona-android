//! DNS the core answers itself (core-api.md, "DNS and UDP"): a query that reaches the virtual
//! resolver, or any UDP/53, is forwarded as DNS over TCP through the flow's route, to the first
//! upstream that answers. TCP works through any outbound, including those without UDP.
//! Score selects for each resolver's TCP socket. Retries of one query share one business start.
//!
//! An answer too large for the client's UDP buffer goes back truncated (TC set, question only),
//! so the client retries over TCP/53, which the core also serves. A query that no upstream
//! answers, or whose route refuses it, gets SERVFAIL at once instead of a client timeout. What an
//! answer maps ([`Learned`]) goes to the generation's [`Names`] as the driver hands the answer on,
//! before the client can have all of it: over TCP as the driver takes the answer's last chunk (see
//! [`ToClient`]), over UDP as the tun takes the datagram (see the driver). A flow the client opens
//! to an address it got finds its name, however late the task that made the answer runs again; an
//! answer the driver drops, or never takes, maps nothing.

pub(crate) mod names;

use crate::rules::Network;
use crate::tunnel::route::Route;
use honk_outbound::group::{ScoreContinuation, ScoreOutcome};
use names::{Learned, Mapping, Names};
use std::io::{self, ErrorKind};
use std::net::{Ipv4Addr, SocketAddr};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::time::{Instant, timeout_at};

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
    let mut original = None;
    for &server in upstreams {
        if let Ok(answer) = exchange_one(server, route, query, &mut original).await {
            return Some(answer);
        }
    }
    None
}

async fn exchange_one(
    server: SocketAddr,
    route: &Route,
    query: &[u8],
    original: &mut Option<ScoreContinuation>,
) -> io::Result<Vec<u8>> {
    let deadline = Instant::now() + EXCHANGE;
    let (mut stream, reporter) = route.connect_until(server, deadline, original).await?;
    let exchange = async {
        let timed = reporter.as_ref().map(|r| (r, std::time::Instant::now()));
        write_framed(&mut stream, query).await?;
        if let Some((r, started)) = timed {
            r.tx_completed(query.len() as u64 + 2, started);
        }
        let answer = read_framed(&mut stream).await?;
        if answer.len() < HEADER || answer[..2] != query[..2] {
            return Err(ErrorKind::InvalidData.into());
        }
        if let Some(r) = &reporter {
            r.first_response();
            r.rx(answer.len() as u64 + 2);
        }
        Ok(answer)
    };
    let result = timeout_at(deadline, exchange)
        .await
        .map_err(io::Error::from)
        .and_then(|r| r);
    if let Some(r) = reporter {
        r.finish(match &result {
            Ok(_) => ScoreOutcome::Success,
            Err(e) => ScoreOutcome::from_io_error(e),
        });
    }
    result
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

/// The answer to `query`, or SERVFAIL, as the client gets it over `network`: over UDP, held to the
/// client's buffer (see [`fit_udp`]). None when `query` is not a query. What it maps is the
/// caller's to read and put once the answer is on its way; a truncated answer maps nothing, and the
/// client's retry over TCP maps the addresses.
pub(crate) async fn answer(
    upstreams: &[SocketAddr],
    route: &Route,
    query: &[u8],
    network: Network,
) -> Option<Vec<u8>> {
    if !is_query(query) {
        return None;
    }
    let answer = if route.refused() {
        None
    } else {
        exchange(upstreams, route, query).await
    };
    let Some(mut answer) = answer else {
        return Some(servfail(query));
    };
    if network == Network::Udp {
        answer = fit_udp(answer, query);
    }
    Some(answer)
}

/// The stream DNS over TCP answers a client on: the task's end of the flow.
pub(crate) trait ToClient: AsyncRead + AsyncWrite + Unpin {
    /// Has `mapping` put as the driver takes the last chunk of the next write, before that chunk
    /// can leave; dropped with the chunk if the driver never takes it.
    fn map_with_last_chunk(&mut self, mapping: Mapping);

    /// The session waits for the resolver, which gives up within `bound` (`Some`), or no longer.
    /// Before the client's FIN that wait is not idle up to `bound`, however short the drain.
    fn resolving(&mut self, bound: Option<Duration>);
}

/// Answers the framed queries of one TCP/53 connection in turn until the client closes it. It has
/// no timer of its own: the driver resets a connection that moves nothing for its idle time, before
/// or after the client's FIN, and drops what it still holds for the client (see `tunnel/gate.rs`),
/// so a client that sends neither a query nor its FIN, or never reads, cannot hold the connection
/// or its buffers. Each byte of a query read or of an answer written counts as progress there, and
/// before the client's FIN the wait for the resolver is not idle up to the resolver's own bound,
/// [`EXCHANGE`] an upstream: the driver cuts a resolver that never returns, as one whose outbound
/// holds its thread, without it. What an answer maps goes to `names` with the answer's last chunk.
pub(crate) async fn serve_tcp<S: ToClient>(
    io: &mut S,
    upstreams: &[SocketAddr],
    (route, names): (&Route, &Names),
) -> io::Result<()> {
    let tries = u32::try_from(upstreams.len()).unwrap_or(u32::MAX);
    let bound = EXCHANGE.saturating_mul(tries);
    loop {
        let query = match read_framed(io).await {
            Ok(q) => q,
            Err(e) if e.kind() == ErrorKind::UnexpectedEof => break,
            Err(e) => return Err(e),
        };
        io.resolving(Some(bound));
        #[cfg(test)]
        crate::tunnel::pause::at(crate::tunnel::pause::Point::Resolving).await;
        let reply = answer(upstreams, route, &query, Network::Tcp).await;
        io.resolving(None);
        if let Some(a) = reply {
            #[cfg(test)]
            crate::tunnel::pause::at(crate::tunnel::pause::Point::Answered).await;
            if let Some(learned) = Learned::read(&query, &a) {
                // The frame is one write, so its last chunk ends the answer.
                io.map_with_last_chunk(names.bind(learned));
            }
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
        upstream_with(|q| answer_to(q, 2))
    }

    /// A DNS-over-TCP server on loopback that answers each query `q` with `answer(q)`.
    pub fn upstream_with(answer: fn(&[u8]) -> Vec<u8>) -> SocketAddr {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        std::thread::spawn(move || {
            use std::io::{Read, Write};
            for mut s in listener.incoming().flatten() {
                let mut len = [0; 2];
                while s.read_exact(&mut len).is_ok() {
                    let mut q = vec![0; usize::from(u16::from_be_bytes(len))];
                    s.read_exact(&mut q).unwrap();
                    let a = answer(&q);
                    s.write_all(&(a.len() as u16).to_be_bytes()).unwrap();
                    s.write_all(&a).unwrap();
                }
            }
        });
        addr
    }

    #[test]
    fn resolver_retry_counts_one_tcp_query_across_two_targets() {
        use crate::dto::Mode;
        use crate::tunnel::Session;
        use crate::tunnel::route::{Inbound, tests::published_links};
        use honk_outbound::group::SelectionNetwork;
        use std::sync::Arc;

        let (proxy, connections) = crate::testutil::socks5();
        let links = format!("socks5://{proxy}#dns");
        let (outbounds, model) =
            published_links(links.as_bytes(), Default::default(), Mode::Global);
        let session = Arc::new(Session::new(0, Default::default()));
        let route = Route::dns(Inbound::Tun, Mode::Global, &outbounds, &session);
        let wrong_id = upstream_with(|q| {
            let mut a = answer_to(q, 1);
            a[0] ^= 1;
            a
        });
        let q = query(71, "dns.example", None);
        let servers = [wrong_id, upstream()];
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let reply = runtime
            .block_on(answer(&servers, &route, &q, Network::Udp))
            .unwrap();
        assert_eq!(reply, answer_to(&q, 2));
        assert_eq!(connections.load(std::sync::atomic::Ordering::Relaxed), 2);
        let manager = &model.score.manager;
        assert_eq!(manager.score_cache_snapshot().exact_cells, 2);
        assert_eq!(manager.score_state().root_business_starts(), 1);
        assert_eq!(
            manager
                .score_budget_counters("g-Auto", SelectionNetwork::Tcp)
                .business_starts,
            1
        );
    }

    /// A refused route answers SERVFAIL, which maps nothing.
    #[tokio::test]
    async fn a_refused_route_answers_servfail_which_maps_nothing() {
        let q = query(3, "learn.example", Some(1232));
        let a = answer(&[upstream()], &Route::Refuse, &q, Network::Tcp)
            .await
            .unwrap();
        assert_eq!(a[3] & 0x0f, 2);
        assert!(Learned::read(&q, &a).is_none());
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
