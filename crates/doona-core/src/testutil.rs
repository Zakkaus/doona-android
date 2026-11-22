//! Helpers for the unit tests.

use crate::sys;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering::Relaxed};

// The integration tests' QUIC node server and the apps behind the tun, shared with the unit
// tests, which use only a part of the apps' client.
#[path = "../tests/common/cert.rs"]
mod cert;
#[allow(dead_code)]
#[path = "../tests/common/client.rs"]
pub mod client;
#[path = "../tests/common/tuic.rs"]
pub mod tuic;

pub mod alloc;

/// A fresh directory under the system temp dir, removed on drop.
pub struct TempDir(PathBuf);

impl TempDir {
    pub fn new(tag: &str) -> TempDir {
        static NEXT: AtomicU32 = AtomicU32::new(0);
        let n = NEXT.fetch_add(1, Relaxed);
        let dir = std::env::temp_dir().join(format!("doona-core-{tag}-{}-{n}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        TempDir(dir)
    }

    pub fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

/// A duplicate's number and what it refers to, and the far end of its socketpair.
pub struct Probe {
    raw: RawFd,
    identity: (u64, u64),
    _peer: OwnedFd,
}

/// A duplicate as jni.rs makes it, and a probe that tells whether it is closed.
pub fn tun() -> (OwnedFd, Probe) {
    let (dup, peer) = tun_and_peer();
    let raw = dup.as_raw_fd();
    let probe = Probe {
        identity: identity(raw).unwrap(),
        raw,
        _peer: peer,
    };
    (dup, probe)
}

/// A duplicate as jni.rs makes it, and the far end of its socketpair, where the apps are.
pub fn tun_and_peer() -> (OwnedFd, OwnedFd) {
    let mut fds = [0; 2];
    // SAFETY: `fds` has room for the two descriptors socketpair writes.
    let r = unsafe {
        libc::socketpair(
            libc::AF_UNIX,
            libc::SOCK_SEQPACKET | libc::SOCK_CLOEXEC,
            0,
            fds.as_mut_ptr(),
        )
    };
    assert_eq!(r, 0);
    // SAFETY: socketpair returned two open descriptors that nothing else owns.
    let (a, b) = unsafe { (OwnedFd::from_raw_fd(fds[0]), OwnedFd::from_raw_fd(fds[1])) };
    (sys::dup_cloexec(a.as_raw_fd()).unwrap(), b)
}

fn identity(fd: RawFd) -> Option<(u64, u64)> {
    // SAFETY: fstat only writes the struct it is given.
    let mut st: libc::stat = unsafe { std::mem::zeroed() };
    // SAFETY: `st` is valid for writes.
    (unsafe { libc::fstat(fd, &mut st) } == 0).then_some((st.st_dev, st.st_ino))
}

/// The duplicate is closed: its number is free, or another test reopened it for something else.
pub fn closed(p: &Probe) -> bool {
    // SAFETY: F_GETFD only reads the descriptor's flags.
    let open = unsafe { libc::fcntl(p.raw, libc::F_GETFD) } != -1;
    !open || identity(p.raw) != Some(p.identity)
}

/// A loopback HTTP server. `respond` gets each request's head and returns the whole response;
/// it may block to hold one back.
pub fn http(respond: impl Fn(&str) -> Vec<u8> + Send + Sync + 'static) -> std::net::SocketAddr {
    use std::io::{Read, Write};
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let respond = std::sync::Arc::new(respond);
    std::thread::spawn(move || {
        for conn in listener.incoming() {
            let Ok(mut conn) = conn else { return };
            let respond = std::sync::Arc::clone(&respond);
            std::thread::spawn(move || {
                let mut head = Vec::new();
                let mut byte = [0u8; 1];
                while !head.ends_with(b"\r\n\r\n") {
                    match conn.read(&mut byte) {
                        Ok(1) => head.push(byte[0]),
                        _ => return,
                    }
                }
                let _ = conn.write_all(&respond(&String::from_utf8_lossy(&head)));
            });
        }
    });
    addr
}

/// The request target of a head `http` received.
pub fn target(head: &str) -> &str {
    head.split(' ').nth(1).unwrap_or_default()
}

/// A response with a length, `headers` and `body`.
pub fn response(status: &str, headers: &[(&str, &str)], body: &[u8]) -> Vec<u8> {
    let mut out = format!("HTTP/1.1 {status}\r\nContent-Length: {}\r\n", body.len());
    for (name, value) in headers {
        out.push_str(&format!("{name}: {value}\r\n"));
    }
    out.push_str("\r\n");
    let mut out = out.into_bytes();
    out.extend_from_slice(body);
    out
}

/// A fixture body from `tests/fixtures`.
pub fn fixture(name: &str) -> Vec<u8> {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures");
    std::fs::read(dir.join(name)).unwrap()
}

/// A loopback SOCKS5 server without authentication that relays each CONNECT; counts the
/// connections it accepted.
pub fn socks5() -> (std::net::SocketAddr, std::sync::Arc<AtomicU32>) {
    socks5_on([127, 0, 0, 1])
}

/// [`socks5`] on `ip`; one on `127.77.0.0/16` is a node on the local network (see `localnet.rs`).
/// A CONNECT whose destination refuses closes the client's connection without a reply.
pub fn socks5_on(ip: [u8; 4]) -> (std::net::SocketAddr, std::sync::Arc<AtomicU32>) {
    socks5_with(ip, |target| std::net::TcpStream::connect(target))
}

/// How a SOCKS5 stand-in reaches a CONNECT's `host:port`.
pub type Connect = fn(&str) -> std::io::Result<std::net::TcpStream>;

/// [`socks5_on`] reaching each destination through `connect`, so a test decides how one answers.
pub fn socks5_with(
    ip: [u8; 4],
    connect: Connect,
) -> (std::net::SocketAddr, std::sync::Arc<AtomicU32>) {
    let listener = std::net::TcpListener::bind(std::net::SocketAddr::from((ip, 0))).unwrap();
    let addr = listener.local_addr().unwrap();
    let count = std::sync::Arc::new(AtomicU32::new(0));
    let c = std::sync::Arc::clone(&count);
    std::thread::spawn(move || {
        for conn in listener.incoming() {
            let Ok(conn) = conn else { return };
            c.fetch_add(1, Relaxed);
            std::thread::spawn(move || {
                let _ = relay_socks(conn, connect);
            });
        }
    });
    (addr, count)
}

fn relay_socks(mut client: std::net::TcpStream, connect: Connect) -> std::io::Result<()> {
    use std::io::{Read, Write};
    let mut greeting = [0u8; 2];
    client.read_exact(&mut greeting)?;
    let mut methods = vec![0u8; usize::from(greeting[1])];
    client.read_exact(&mut methods)?;
    client.write_all(&[5, 0])?;
    let upstream = connect(&socks_target(&mut client)?)?;
    client.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0])?;
    let (mut from_client, mut to_upstream) = (client.try_clone()?, upstream.try_clone()?);
    let up = std::thread::spawn(move || {
        let _ = std::io::copy(&mut from_client, &mut to_upstream);
        let _ = to_upstream.shutdown(std::net::Shutdown::Write);
    });
    let (mut from_upstream, mut to_client) = (upstream, client);
    let _ = std::io::copy(&mut from_upstream, &mut to_client);
    let _ = to_client.shutdown(std::net::Shutdown::Write);
    let _ = up.join();
    Ok(())
}

/// The `host:port` of a SOCKS5 CONNECT request.
fn socks_target(client: &mut std::net::TcpStream) -> std::io::Result<String> {
    use std::io::Read;
    let mut request = [0u8; 4];
    client.read_exact(&mut request)?;
    let host = match request[3] {
        1 => {
            let mut a = [0u8; 4];
            client.read_exact(&mut a)?;
            std::net::Ipv4Addr::from(a).to_string()
        }
        3 => {
            let mut n = [0u8; 1];
            client.read_exact(&mut n)?;
            let mut name = vec![0u8; usize::from(n[0])];
            client.read_exact(&mut name)?;
            String::from_utf8_lossy(&name).into_owned()
        }
        4 => {
            let mut a = [0u8; 16];
            client.read_exact(&mut a)?;
            format!("[{}]", std::net::Ipv6Addr::from(a))
        }
        _ => return Err(std::io::ErrorKind::InvalidData.into()),
    };
    let mut port = [0u8; 2];
    client.read_exact(&mut port)?;
    Ok(format!("{host}:{}", u16::from_be_bytes(port)))
}
