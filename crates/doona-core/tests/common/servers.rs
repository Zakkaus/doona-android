//! Loopback upstreams for the data-path tests: a TCP echo, a UDP echo, a DNS-over-TCP resolver,
//! a UDP resolver and a subscription feed, each on its own threads.

use std::io::{Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::sync::atomic::{AtomicUsize, Ordering::Relaxed};
use std::sync::{Arc, mpsc};
use std::time::Duration;

/// Counts what a server saw.
#[derive(Default)]
pub struct Seen {
    pub connections: AtomicUsize,
    pub closed: AtomicUsize,
    pub datagrams: AtomicUsize,
    /// HTTPS-record queries (type 65) a resolver received.
    pub https: AtomicUsize,
}

/// Echoes every byte of each connection, then closes it after the client's FIN.
pub fn tcp_echo() -> (SocketAddr, Arc<Seen>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let seen = Arc::new(Seen::default());
    let s = Arc::clone(&seen);
    std::thread::spawn(move || {
        for conn in listener.incoming() {
            let Ok(mut conn) = conn else { return };
            s.connections.fetch_add(1, Relaxed);
            let s = Arc::clone(&s);
            std::thread::spawn(move || {
                let mut buf = vec![0u8; 64 << 10];
                loop {
                    match conn.read(&mut buf) {
                        Ok(0) | Err(_) => break,
                        Ok(n) => {
                            if conn.write_all(&buf[..n]).is_err() {
                                break;
                            }
                        }
                    }
                }
                s.closed.fetch_add(1, Relaxed);
            });
        }
    });
    (addr, seen)
}

/// Accepts one connection and closes its sending side at once; delivers what the client sent
/// once the client's FIN arrives.
pub fn half_closer() -> (SocketAddr, mpsc::Receiver<Vec<u8>>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        let Ok((mut conn, _)) = listener.accept() else {
            return;
        };
        let _ = conn.shutdown(Shutdown::Write);
        let mut got = Vec::new();
        let _ = conn.read_to_end(&mut got);
        let _ = tx.send(got);
    });
    (addr, rx)
}

/// Accepts connections and reads each until the client closes it, never answering: a dial stalled
/// at its first reply.
pub fn stall() -> (SocketAddr, Arc<Seen>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let seen = Arc::new(Seen::default());
    let s = Arc::clone(&seen);
    std::thread::spawn(move || {
        for conn in listener.incoming() {
            let Ok(mut conn) = conn else { return };
            s.connections.fetch_add(1, Relaxed);
            let s = Arc::clone(&s);
            std::thread::spawn(move || {
                let _ = std::io::copy(&mut conn, &mut std::io::sink());
                s.closed.fetch_add(1, Relaxed);
            });
        }
    });
    (addr, seen)
}

/// Accepts connections and sends each one bytes without end, never reading and never closing: a
/// download its client may stop reading. A connection's thread ends when the core closes it.
pub fn flood() -> SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    std::thread::spawn(move || {
        for conn in listener.incoming() {
            let Ok(mut conn) = conn else { return };
            std::thread::spawn(move || {
                let chunk = vec![7u8; 64 << 10];
                while conn.write_all(&chunk).is_ok() {}
            });
        }
    });
    addr
}

/// Accepts connections and sends each `len` bytes, then its FIN; what the client sends is read and
/// dropped until the client's FIN, so the connection never ends in a reset.
pub fn sender(len: usize) -> SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    std::thread::spawn(move || {
        for conn in listener.incoming() {
            let Ok(mut conn) = conn else { return };
            let mut reader = conn.try_clone().unwrap();
            std::thread::spawn(move || std::io::copy(&mut reader, &mut std::io::sink()));
            std::thread::spawn(move || {
                if conn.write_all(&vec![7u8; len]).is_ok() {
                    let _ = conn.shutdown(Shutdown::Write);
                }
            });
        }
    });
    addr
}

/// A bound, non-listening TCP socket reserves its refused address until the caller drops it.
pub fn closed_port() -> (SocketAddr, tokio::net::TcpSocket) {
    let socket = tokio::net::TcpSocket::new_v4().unwrap();
    socket.bind(([127, 0, 0, 1], 0).into()).unwrap();
    (socket.local_addr().unwrap(), socket)
}

pub fn udp_echo() -> (SocketAddr, Arc<Seen>) {
    let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
    let addr = socket.local_addr().unwrap();
    let seen = Arc::new(Seen::default());
    let s = Arc::clone(&seen);
    std::thread::spawn(move || {
        let mut buf = vec![0u8; 65535];
        while let Ok((n, from)) = socket.recv_from(&mut buf) {
            s.datagrams.fetch_add(1, Relaxed);
            let _ = socket.send_to(&buf[..n], from);
        }
    });
    (addr, seen)
}

/// Counts the datagrams it receives and never answers: a QUIC handshake stalls there.
pub fn udp_sink() -> (SocketAddr, Arc<Seen>) {
    let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
    let addr = socket.local_addr().unwrap();
    let seen = Arc::new(Seen::default());
    let s = Arc::clone(&seen);
    std::thread::spawn(move || {
        let mut buf = vec![0u8; 65535];
        while socket.recv_from(&mut buf).is_ok() {
            s.datagrams.fetch_add(1, Relaxed);
        }
    });
    (addr, seen)
}

fn u16_at(m: &[u8], at: usize) -> usize {
    usize::from(u16::from_be_bytes([m[at], m[at + 1]]))
}

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

/// The answer to `q` (one question, no compression in it): `n` A records.
pub fn answer_to(q: &[u8], n: u16) -> Vec<u8> {
    let mut end = 12;
    while q[end] != 0 {
        end += 1 + usize::from(q[end]);
    }
    end += 5;
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

/// Answer count and response code of a DNS message.
pub fn answers_and_rcode(m: &[u8]) -> (usize, u8) {
    (u16_at(m, 6), m[3] & 0x0f)
}

pub fn truncated(m: &[u8]) -> bool {
    m[2] & 0x02 != 0
}

/// How long [`dns_server`] takes to answer a name containing "slow", within the core's 5 s for
/// one upstream.
pub const SLOW: Duration = Duration::from_secs(3);

/// A DNS-over-TCP resolver: a name containing "big" gets 60 records, one containing "huge" 4000
/// (about 64 KB), any other one; a name containing "slow" is answered after [`SLOW`].
pub fn dns_server() -> (SocketAddr, Arc<Seen>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let seen = Arc::new(Seen::default());
    let s = Arc::clone(&seen);
    std::thread::spawn(move || {
        for conn in listener.incoming() {
            let Ok(conn) = conn else { return };
            let s = Arc::clone(&s);
            std::thread::spawn(move || serve_dns(conn, &s));
        }
    });
    (addr, seen)
}

fn serve_dns(mut conn: TcpStream, seen: &Seen) {
    let mut len = [0u8; 2];
    while conn.read_exact(&mut len).is_ok() {
        let mut q = vec![0u8; usize::from(u16::from_be_bytes(len))];
        if conn.read_exact(&mut q).is_err() {
            return;
        }
        seen.datagrams.fetch_add(1, Relaxed);
        if q.windows(4).any(|w| w == b"slow") {
            std::thread::sleep(SLOW);
        }
        let records = if q.windows(4).any(|w| w == b"huge") {
            4000
        } else if q.windows(3).any(|w| w == b"big") {
            60
        } else {
            1
        };
        let a = answer_to(&q, records);
        let mut framed = (a.len() as u16).to_be_bytes().to_vec();
        framed.extend_from_slice(&a);
        if conn.write_all(&framed).is_err() {
            return;
        }
    }
}

/// An HTTP server that answers every request with `body`, as a subscription feed.
pub fn feed(body: String) -> SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    std::thread::spawn(move || {
        for conn in listener.incoming() {
            let Ok(mut conn) = conn else { return };
            let mut head = Vec::new();
            let mut byte = [0u8; 1];
            while !head.ends_with(b"\r\n\r\n") && conn.read_exact(&mut byte).is_ok() {
                head.push(byte[0]);
            }
            let reply = format!(
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
            let _ = conn.write_all(reply.as_bytes());
        }
    });
    addr
}

/// A UDP resolver for one-question queries without other records: A gets 127.0.0.1, any other
/// type no record. Counts the queries in `datagrams`, those for an HTTPS record also in `https`.
pub fn udp_resolver() -> (SocketAddr, Arc<Seen>) {
    udp_resolver_to(std::net::Ipv4Addr::LOCALHOST)
}

/// [`udp_resolver`] answering A queries with `answer`.
pub fn udp_resolver_to(answer: std::net::Ipv4Addr) -> (SocketAddr, Arc<Seen>) {
    let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
    let addr = socket.local_addr().unwrap();
    let seen = Arc::new(Seen::default());
    let s = Arc::clone(&seen);
    std::thread::spawn(move || {
        let mut buf = vec![0u8; 512];
        while let Ok((n, from)) = socket.recv_from(&mut buf) {
            if n < 17 {
                continue;
            }
            s.datagrams.fetch_add(1, Relaxed);
            let mut a = buf[..n].to_vec();
            a[2] |= 0x80;
            a[3] = 0x80;
            match u16_at(&a, n - 4) {
                1 => {
                    a[7] = 1;
                    a.extend_from_slice(&[0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4]);
                    a.extend_from_slice(&answer.octets());
                }
                65 => {
                    s.https.fetch_add(1, Relaxed);
                }
                _ => {}
            }
            let _ = socket.send_to(&a, from);
        }
    });
    (addr, seen)
}
