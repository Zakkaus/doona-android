//! A loopback SOCKS5 server without authentication: CONNECT relays a stream, UDP ASSOCIATE relays
//! datagrams for as long as its control connection stays open. It counts the connections it
//! accepted and closed, and the datagrams it relayed from the client.

use super::servers::Seen;
use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, Ipv6Addr, Shutdown, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering::Relaxed};
use std::time::Duration;

pub fn socks5() -> (SocketAddr, Arc<Seen>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let seen = Arc::new(Seen::default());
    let s = Arc::clone(&seen);
    std::thread::spawn(move || {
        for conn in listener.incoming() {
            let Ok(conn) = conn else { return };
            s.connections.fetch_add(1, Relaxed);
            let s = Arc::clone(&s);
            std::thread::spawn(move || {
                let _ = serve(conn, &s);
                s.closed.fetch_add(1, Relaxed);
            });
        }
    });
    (addr, seen)
}

fn serve(mut client: TcpStream, seen: &Arc<Seen>) -> io::Result<()> {
    let mut greeting = [0u8; 2];
    client.read_exact(&mut greeting)?;
    let mut methods = vec![0u8; usize::from(greeting[1])];
    client.read_exact(&mut methods)?;
    client.write_all(&[5, 0])?;
    let mut request = [0u8; 3];
    client.read_exact(&mut request)?;
    let target = read_addr(&mut client)?;
    match request[1] {
        1 => connect(client, target),
        3 => associate(client, seen),
        _ => Err(io::ErrorKind::Unsupported.into()),
    }
}

fn connect(mut client: TcpStream, target: SocketAddr) -> io::Result<()> {
    let upstream = TcpStream::connect(target)?;
    client.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0])?;
    let (mut from_client, mut to_upstream) = (client.try_clone()?, upstream.try_clone()?);
    let up = std::thread::spawn(move || {
        let _ = io::copy(&mut from_client, &mut to_upstream);
        let _ = to_upstream.shutdown(Shutdown::Write);
    });
    let (mut from_upstream, mut to_client) = (upstream, client);
    let _ = io::copy(&mut from_upstream, &mut to_client);
    let _ = to_client.shutdown(Shutdown::Write);
    let _ = up.join();
    Ok(())
}

/// Relays datagrams until the client closes the control connection.
fn associate(mut control: TcpStream, seen: &Arc<Seen>) -> io::Result<()> {
    let relay = UdpSocket::bind("127.0.0.1:0")?;
    relay.set_read_timeout(Some(Duration::from_millis(50)))?;
    let mut reply = vec![5, 0, 0, 1, 127, 0, 0, 1];
    reply.extend_from_slice(&relay.local_addr()?.port().to_be_bytes());
    control.write_all(&reply)?;
    let done = Arc::new(AtomicBool::new(false));
    let thread = {
        let (done, seen) = (Arc::clone(&done), Arc::clone(seen));
        std::thread::spawn(move || relay_datagrams(&relay, &done, &seen))
    };
    let _ = control.read_to_end(&mut Vec::new());
    done.store(true, Relaxed);
    let _ = thread.join();
    Ok(())
}

/// A datagram from the client carries the destination in its header; one from anyone else is a
/// reply, sent back to the client under a header that names its source.
fn relay_datagrams(relay: &UdpSocket, done: &AtomicBool, seen: &Seen) {
    let mut buf = vec![0u8; 65535];
    let mut client = None;
    while !done.load(Relaxed) {
        let Ok((n, from)) = relay.recv_from(&mut buf) else {
            continue;
        };
        if client.is_none_or(|c| c == from) {
            let Some(mut packet) = buf.get(3..n) else {
                continue;
            };
            let Ok(target) = read_addr(&mut packet) else {
                continue;
            };
            client = Some(from);
            seen.datagrams.fetch_add(1, Relaxed);
            let _ = relay.send_to(packet, target);
        } else if let Some(client) = client {
            let mut out = vec![0, 0, 0];
            write_addr(&mut out, from);
            out.extend_from_slice(&buf[..n]);
            let _ = relay.send_to(&out, client);
        }
    }
}

pub fn read_addr(r: &mut impl Read) -> io::Result<SocketAddr> {
    let mut atyp = [0u8; 1];
    r.read_exact(&mut atyp)?;
    let ip = match atyp[0] {
        1 => {
            let mut a = [0u8; 4];
            r.read_exact(&mut a)?;
            Ipv4Addr::from(a).into()
        }
        4 => {
            let mut a = [0u8; 16];
            r.read_exact(&mut a)?;
            Ipv6Addr::from(a).into()
        }
        _ => return Err(io::ErrorKind::InvalidData.into()),
    };
    let mut port = [0u8; 2];
    r.read_exact(&mut port)?;
    Ok(SocketAddr::new(ip, u16::from_be_bytes(port)))
}

pub fn write_addr(out: &mut Vec<u8>, addr: SocketAddr) {
    match addr {
        SocketAddr::V4(a) => {
            out.push(1);
            out.extend_from_slice(&a.ip().octets());
        }
        SocketAddr::V6(a) => {
            out.push(4);
            out.extend_from_slice(&a.ip().octets());
        }
    }
    out.extend_from_slice(&addr.port().to_be_bytes());
}
