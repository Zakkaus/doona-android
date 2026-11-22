//! Loopback upstreams for the data-path tests: a TCP echo, a UDP echo, and a DNS-over-TCP
//! resolver, each on its own threads.

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering::Relaxed};

/// Counts what a server saw.
#[derive(Default)]
pub struct Seen {
    pub connections: AtomicUsize,
    pub closed: AtomicUsize,
    pub datagrams: AtomicUsize,
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

/// A loopback port with nothing listening.
pub fn closed_port() -> SocketAddr {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    l.local_addr().unwrap()
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

/// A DNS-over-TCP resolver: a name containing "big" gets 60 records, any other one.
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
        let big = q.windows(3).any(|w| w == b"big");
        let a = answer_to(&q, if big { 60 } else { 1 });
        let mut framed = (a.len() as u16).to_be_bytes().to_vec();
        framed.extend_from_slice(&a);
        if conn.write_all(&framed).is_err() {
            return;
        }
    }
}
