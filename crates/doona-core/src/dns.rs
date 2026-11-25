//! DNS the core answers itself (core-api.md, "DNS and UDP"): every query an app sends to port 53,
//! over UDP or TCP, to the virtual resolver or to any other address. honk's DNS pipeline answers
//! it under the generation's committed configuration ([`Engine::resolve_dns`]): its request and
//! response rules, upstreams, cache and reply policy, with the geodata its traffic rules read.
//! `asis` forwards to the server the app addressed; a query to the virtual resolver has no such
//! server, so `asis` fails closed there and never dials the virtual address. With no committed
//! configuration the core answers no DNS.
//!
//! What an answer maps ([`Learned`]) goes to the generation's [`Names`] as the driver hands the
//! answer on, before the client can have all of it: over TCP as the driver takes the answer's last
//! chunk (see [`ToClient`]), over UDP as the tun takes the datagram (see the driver). A flow the
//! client opens to an address it got finds its name, however late the task that made the answer
//! runs again; an answer the driver drops, or never takes, maps nothing.
//!
//! [`Engine::resolve_dns`]: honk_core::embedded::Engine::resolve_dns

pub(crate) mod names;
#[cfg(test)]
mod reference;

pub(crate) use crate::honkconf::runtime::Network;
use crate::honkconf::runtime::Runtime;
use names::{Learned, Mapping, Names};
use std::io::{self, ErrorKind};
use std::net::SocketAddr;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// How long one query may take, as honk's daemon bounds its TCP DNS I/O.
pub(crate) const RESOLVE_BOUND: Duration = Duration::from_secs(30);
const HEADER: usize = 12;
fn u16_at(m: &[u8], at: usize) -> Option<u16> {
    Some(u16::from_be_bytes([*m.get(at)?, *m.get(at + 1)?]))
}

fn count(m: &[u8], at: usize) -> usize {
    u16_at(m, at).map_or(0, usize::from)
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

/// The ends of a query: the app that sent it, and the server it addressed (`None` for the virtual
/// resolver).
pub(crate) type Ends = (SocketAddr, Option<SocketAddr>);

/// The reply `runtime` gives `query`, held to the client's UDP buffer when it came over UDP; none
/// when no configuration is committed, or when a UDP payload is not exactly one query.
pub(crate) async fn answer(
    runtime: Option<&Runtime>,
    query: &[u8],
    network: Network,
    (client, server): Ends,
) -> Option<Vec<u8>> {
    let answer = runtime?
        .engine()
        .resolve_dns(query, network, client, server)
        .await?;
    Some(answer.reply().to_vec())
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
/// before the client's FIN the wait for honk is not idle up to [`RESOLVE_BOUND`]. A query with no
/// reply (no configuration committed) ends the connection. What an answer maps goes to `names`
/// with the answer's last chunk.
pub(crate) async fn serve_tcp<S: ToClient>(
    io: &mut S,
    (runtime, names): (Option<&Runtime>, &Names),
    ends: Ends,
) -> io::Result<()> {
    loop {
        let query = match read_framed(io).await {
            Ok(q) => q,
            Err(e) if e.kind() == ErrorKind::UnexpectedEof => break,
            Err(e) => return Err(e),
        };
        io.resolving(Some(RESOLVE_BOUND));
        #[cfg(test)]
        crate::tunnel::pause::at(crate::tunnel::pause::Point::Resolving).await;
        let reply = answer(runtime, &query, Network::Tcp, ends).await;
        io.resolving(None);
        let Some(a) = reply else { break };
        #[cfg(test)]
        crate::tunnel::pause::at(crate::tunnel::pause::Point::Answered).await;
        if let Some(learned) = Learned::read(&query, &a) {
            // The frame is one write, so its last chunk ends the answer.
            io.map_with_last_chunk(names.bind(learned));
        }
        write_framed(io, &a).await?;
    }
    io.shutdown().await
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::honkconf::runtime::Runtime;
    use crate::honkconf::{Capabilities, Geo, GeoCatalog, HonkStore, Label};
    use crate::testutil::TempDir;
    use std::path::Path;
    use std::sync::Arc;

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

    /// A DNS-over-TCP server on loopback that answers each query `q` with `answer(q)`.
    pub fn upstream_with(answer: impl Fn(&[u8]) -> Vec<u8> + Send + 'static) -> SocketAddr {
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

    fn field(tag: u8, bytes: &[u8]) -> Vec<u8> {
        let mut out = vec![tag, u8::try_from(bytes.len()).unwrap()];
        out.extend(bytes);
        out
    }

    /// A generation's geodata: `geosite.dat` with one category, `cn`, the domain `baidu.com`.
    pub fn geo() -> TempDir {
        let dir = TempDir::new("dns-geo");
        let mut domain = vec![8, 2];
        domain.extend(field(18, b"baidu.com"));
        let mut site = field(10, b"CN");
        site.extend(field(18, &domain));
        std::fs::write(dir.path().join("geosite.dat"), field(10, &site)).unwrap();
        dir
    }

    /// The labels [`geo`] holds: the geosite category `cn`, no GeoIP code.
    struct Cn;

    impl GeoCatalog for Cn {
        fn geoip(&self, _: &str) -> Label {
            Label::MissingCategory
        }
        fn geoip_nets(&self, _: &str) -> Option<Vec<(std::net::IpAddr, u8)>> {
            None
        }
        fn geosite(&self, label: &str) -> Label {
            match label {
                "cn" => Label::Present,
                _ => Label::MissingCategory,
            }
        }
    }

    /// Commits `source` as the head of the honk store in `dir`, checked against [`geo`]'s labels.
    pub fn commit(dir: &Path, source: &str) -> HonkStore {
        let mut store = HonkStore::open(dir).unwrap();
        let id = store.draft(None).unwrap();
        store.edit(id, Path::new("config.dae"), source).unwrap();
        let geo = Geo::Installed {
            generation: 1,
            catalog: &Cn,
        };
        store
            .apply(id, &Capabilities { ingress: None, geo })
            .unwrap();
        store
    }

    /// The runtime of `source` committed as a store head, with `geo` as its geodata.
    pub fn runtime(source: &str, geo: &Path) -> Arc<Runtime> {
        let data = TempDir::new("dns-store");
        Arc::new(Runtime::load(&commit(data.path(), source), geo).unwrap())
    }

    /// A configuration that sends every query to `upstream`.
    pub fn to(upstream: SocketAddr) -> String {
        format!(
            "dns {{\n upstream {{\n  u: 'tcp://{upstream}' -> direct\n }}\n routing {{\n  request {{\n   fallback: u\n  }}\n }}\n}}\nrouting {{\n fallback: direct\n}}\n"
        )
    }

    /// The runtime of a configuration that sends every query to `upstream`.
    pub fn through(upstream: SocketAddr) -> Arc<Runtime> {
        let geo = geo();
        runtime(&to(upstream), geo.path())
    }

    /// The runtime of a configuration that rejects `example.com` and its subdomains, answered at
    /// once without a dial.
    pub fn rejecting() -> Arc<Runtime> {
        let geo = geo();
        let source = "dns {\n upstream {\n  u: 'tcp://127.0.0.1:9' -> direct\n }\n routing {\n  request {\n   qname(suffix: example.com) -> reject\n   fallback: u\n  }\n }\n}\nrouting {\n fallback: direct\n}\n";
        runtime(source, geo.path())
    }

    /// `cn` names to `alidns`, others to `google`, a requery of `google`'s 10.0.0.0/8 answers at
    /// `alidns`, `ads.test` rejected and `asis.test` sent where the app addressed it; the
    /// traffic rules read the same `cn`.
    pub fn split(alidns: SocketAddr, google: SocketAddr) -> String {
        format!(
            "dns {{\n upstream {{\n  alidns: 'tcp://{alidns}' -> direct\n  google: 'tcp://{google}' -> direct\n }}\n routing {{\n  request {{\n   qname(suffix: ads.test) -> reject\n   qname(suffix: asis.test) -> asis\n   qname(geosite: cn) -> alidns\n   fallback: google\n  }}\n  response {{\n   upstream(google) && ip(10.0.0.0/8) -> alidns\n   fallback: accept\n  }}\n }}\n}}\nrouting {{\n domain(geosite: cn) -> direct\n fallback: direct\n}}\n"
        )
    }

    /// An upstream answering one A record, `address`.
    pub fn answering(address: [u8; 4]) -> SocketAddr {
        upstream_with(move |q| with_address(q, address))
    }

    fn with_address(q: &[u8], address: [u8; 4]) -> Vec<u8> {
        let mut a = answer_to(q, 1);
        let at = a.len() - 4;
        a[at..].copy_from_slice(&address);
        a
    }

    const CLIENT: SocketAddr = SocketAddr::new(
        std::net::IpAddr::V4(std::net::Ipv4Addr::new(10, 0, 0, 2)),
        40000,
    );

    fn first_address(reply: &[u8]) -> Option<[u8; 4]> {
        (u16_at(reply, 6)? > 0).then(|| reply[reply.len() - 4..].try_into().unwrap())
    }

    async fn ask(
        runtime: &Runtime,
        name: &str,
        network: Network,
        server: Option<SocketAddr>,
    ) -> Vec<u8> {
        let q = query(9, name, Some(1232));
        let reply = answer(Some(runtime), &q, network, (CLIENT, server))
            .await
            .unwrap();
        assert_eq!(reply[..2], q[..2]);
        reply
    }

    #[tokio::test]
    async fn queries_follow_the_committed_dns_rules_with_the_generation_geodata() {
        let geo = geo();
        let (alidns, google) = (answering([192, 0, 2, 1]), answering([192, 0, 2, 2]));
        let runtime = runtime(&split(alidns, google), geo.path());

        let cn = ask(&runtime, "www.baidu.com", Network::Udp, None).await;
        assert_eq!(
            first_address(&cn),
            Some([192, 0, 2, 1]),
            "cn goes to alidns"
        );
        let other = ask(&runtime, "example.org", Network::Tcp, None).await;
        assert_eq!(
            first_address(&other),
            Some([192, 0, 2, 2]),
            "the rest to google"
        );
        let ad = ask(&runtime, "x.ads.test", Network::Udp, None).await;
        assert_eq!(
            (ad[3] & 0x0f, first_address(&ad)),
            (0, None),
            "reject: empty NOERROR"
        );
    }

    #[tokio::test]
    async fn a_requeried_answer_comes_from_the_second_upstream() {
        let geo = geo();
        let (alidns, google) = (answering([192, 0, 2, 1]), answering([10, 0, 0, 7]));
        let runtime = runtime(&split(alidns, google), geo.path());

        let reply = ask(&runtime, "example.org", Network::Udp, None).await;

        assert_eq!(first_address(&reply), Some([192, 0, 2, 1]));
    }

    #[tokio::test]
    async fn asis_goes_to_the_addressed_server_and_fails_closed_without_one() {
        let geo = geo();
        let (alidns, google) = (answering([192, 0, 2, 1]), answering([192, 0, 2, 2]));
        let runtime = runtime(&split(alidns, google), geo.path());
        let addressed = answering([203, 0, 113, 9]);

        let real = ask(&runtime, "www.asis.test", Network::Tcp, Some(addressed)).await;
        assert_eq!(first_address(&real), Some([203, 0, 113, 9]));
        let synthetic = ask(&runtime, "www.asis.test", Network::Tcp, None).await;
        assert_eq!(
            (synthetic[3] & 0x0f, first_address(&synthetic)),
            (2, None),
            "SERVFAIL"
        );
    }

    #[tokio::test]
    async fn no_committed_configuration_answers_nothing() {
        let q = query(3, "example.org", None);
        assert_eq!(answer(None, &q, Network::Udp, (CLIENT, None)).await, None);
    }

    #[test]
    fn names_with_pointers_and_bad_lengths() {
        let q = query(1, "x.y.example", None);
        assert_eq!(skip_name(&q, HEADER), Some(q.len() - 4));
        assert_eq!(skip_name(&[0xc0, 12], 0), Some(2));
        assert_eq!(skip_name(&[0xc0], 0), None);
        assert_eq!(skip_name(&[0x40, 0], 0), None);
        assert_eq!(skip_name(&[3, b'a'], 0), None);
    }
}
