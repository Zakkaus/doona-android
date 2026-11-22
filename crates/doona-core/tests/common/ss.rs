//! A loopback Shadowsocks server, AEAD `aes-128-gcm` over TCP (SIP004), on BoringSSL, which the
//! core links already. It relays each stream to the address in its first bytes and counts the
//! connections it accepted.

use super::servers::Seen;
use super::socks::read_addr;
use base64::Engine;
use boring::hash::{MessageDigest, hash, hmac_sha1};
use boring::symm::{Cipher, decrypt_aead, encrypt_aead};
use std::io::{self, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream};
use std::sync::Arc;
use std::sync::atomic::Ordering::Relaxed;

pub const METHOD: &str = "aes-128-gcm";
pub const PASSWORD: &str = "doona-test";
const KEY: usize = 16;
const TAG: usize = 16;
const MAX_CHUNK: usize = 0x3fff;

/// The share link of a node on this server.
pub fn link(addr: SocketAddr, name: &str) -> String {
    let user =
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(format!("{METHOD}:{PASSWORD}"));
    format!("ss://{user}@{addr}#{name}")
}

pub fn shadowsocks() -> (SocketAddr, Arc<Seen>) {
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
                let _ = serve(conn);
                s.closed.fetch_add(1, Relaxed);
            });
        }
    });
    (addr, seen)
}

fn serve(mut client: TcpStream) -> io::Result<()> {
    let key = master_key();
    let mut salt = [0u8; KEY];
    client.read_exact(&mut salt)?;
    let mut from_client = Opener::new(&key, &salt);
    let mut early = Vec::new();
    let target = loop {
        let Some(chunk) = from_client.chunk(&mut client)? else {
            return Ok(());
        };
        early.extend_from_slice(&chunk);
        let mut rest = early.as_slice();
        if let Ok(target) = read_addr(&mut rest) {
            early = rest.to_vec();
            break target;
        }
    };
    let mut upstream = TcpStream::connect(target)?;
    upstream.write_all(&early)?;
    let (mut to_client, mut from_upstream) = (client.try_clone()?, upstream.try_clone()?);
    let down = std::thread::spawn(move || {
        let _ = seal_stream(&key, &mut from_upstream, &mut to_client);
        let _ = to_client.shutdown(Shutdown::Write);
    });
    while let Some(chunk) = from_client.chunk(&mut client)? {
        upstream.write_all(&chunk)?;
    }
    let _ = upstream.shutdown(Shutdown::Write);
    let _ = down.join();
    Ok(())
}

/// Sends a fresh salt, then everything `from` gives as sealed chunks.
fn seal_stream(key: &[u8; KEY], from: &mut TcpStream, to: &mut TcpStream) -> io::Result<()> {
    let mut salt = [0u8; KEY];
    boring::rand::rand_bytes(&mut salt).map_err(io::Error::other)?;
    to.write_all(&salt)?;
    let subkey = subkey(key, &salt);
    let mut nonce = [0u8; 12];
    let mut buf = vec![0u8; MAX_CHUNK];
    loop {
        let n = from.read(&mut buf)?;
        if n == 0 {
            return Ok(());
        }
        let len = u16::try_from(n).unwrap_or(u16::MAX).to_be_bytes();
        let mut out = seal(&subkey, &mut nonce, &len);
        out.extend(seal(&subkey, &mut nonce, &buf[..n]));
        to.write_all(&out)?;
    }
}

/// Reads a client's chunks.
struct Opener {
    subkey: [u8; KEY],
    nonce: [u8; 12],
}

impl Opener {
    fn new(key: &[u8; KEY], salt: &[u8]) -> Opener {
        Opener {
            subkey: subkey(key, salt),
            nonce: [0; 12],
        }
    }

    /// The next chunk's payload; None at a clean end of the stream.
    fn chunk(&mut self, r: &mut impl Read) -> io::Result<Option<Vec<u8>>> {
        let mut head = [0u8; 2 + TAG];
        match r.read_exact(&mut head) {
            Ok(()) => {}
            Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
            Err(e) => return Err(e),
        }
        let len = open(&self.subkey, &mut self.nonce, &head)?;
        let len = usize::from(u16::from_be_bytes([len[0], len[1]])) & MAX_CHUNK;
        let mut body = vec![0u8; len + TAG];
        r.read_exact(&mut body)?;
        open(&self.subkey, &mut self.nonce, &body).map(Some)
    }
}

/// EVP_BytesToKey with MD5, for a 16-byte key: the first digest.
fn master_key() -> [u8; KEY] {
    let digest = hash(MessageDigest::md5(), PASSWORD.as_bytes()).unwrap();
    digest[..KEY].try_into().unwrap()
}

/// HKDF-SHA1 with `info` "ss-subkey"; one block covers 16 bytes.
fn subkey(key: &[u8; KEY], salt: &[u8]) -> [u8; KEY] {
    let prk = hmac_sha1(salt, key).unwrap();
    let okm = hmac_sha1(&prk, b"ss-subkey\x01").unwrap();
    okm[..KEY].try_into().unwrap()
}

fn seal(key: &[u8], nonce: &mut [u8; 12], data: &[u8]) -> Vec<u8> {
    let mut tag = [0u8; TAG];
    let cipher = Cipher::aes_128_gcm();
    let mut out = encrypt_aead(cipher, key, Some(&nonce[..]), &[], data, &mut tag).unwrap();
    out.extend_from_slice(&tag);
    increment(nonce);
    out
}

fn open(key: &[u8], nonce: &mut [u8; 12], sealed: &[u8]) -> io::Result<Vec<u8>> {
    let (data, tag) = sealed.split_at(sealed.len() - TAG);
    let cipher = Cipher::aes_128_gcm();
    let plain = decrypt_aead(cipher, key, Some(&nonce[..]), &[], data, tag)
        .map_err(|_| io::Error::from(io::ErrorKind::InvalidData))?;
    increment(nonce);
    Ok(plain)
}

/// The little-endian counter nonce of SIP004.
fn increment(nonce: &mut [u8; 12]) {
    for b in nonce.iter_mut() {
        *b = b.wrapping_add(1);
        if *b != 0 {
            break;
        }
    }
}
