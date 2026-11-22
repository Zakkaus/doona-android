//! A loopback AnyTLS server on BoringSSL, with a certificate made at start. It checks no password
//! and echoes each stream instead of relaying it, which is all a test of the carrier's lifetime
//! needs: the client keeps an idle session (one TLS connection) in its pool for the next stream.
//! It counts the sessions it accepted and the ones that ended.

use super::servers::Seen;
use boring::asn1::Asn1Time;
use boring::bn::BigNum;
use boring::ec::{EcGroup, EcKey};
use boring::hash::MessageDigest;
use boring::nid::Nid;
use boring::pkey::PKey;
use boring::ssl::{SslAcceptor, SslMethod};
use boring::x509::{X509, X509NameBuilder};
use std::collections::HashSet;
use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpListener};
use std::sync::Arc;
use std::sync::atomic::Ordering::Relaxed;

const PSH: u8 = 2;
const FIN: u8 = 3;
const HEART_REQUEST: u8 = 8;
const HEART_RESPONSE: u8 = 9;

/// The share link of a node on this server; the certificate is not checked.
pub fn link(addr: SocketAddr, name: &str) -> String {
    format!("anytls://doona-test@{addr}/?sni=localhost&insecure=1#{name}")
}

pub fn anytls() -> (SocketAddr, Arc<Seen>) {
    let acceptor = Arc::new(acceptor());
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let seen = Arc::new(Seen::default());
    let s = Arc::clone(&seen);
    std::thread::spawn(move || {
        for conn in listener.incoming() {
            let Ok(conn) = conn else { return };
            s.connections.fetch_add(1, Relaxed);
            let (s, acceptor) = (Arc::clone(&s), Arc::clone(&acceptor));
            std::thread::spawn(move || {
                if let Ok(tls) = acceptor.accept(conn) {
                    let _ = serve(tls);
                }
                s.closed.fetch_add(1, Relaxed);
            });
        }
    });
    (addr, seen)
}

fn acceptor() -> SslAcceptor {
    let group = EcGroup::from_curve_name(Nid::X9_62_PRIME256V1).unwrap();
    let key = PKey::from_ec_key(EcKey::generate(&group).unwrap()).unwrap();
    let mut name = X509NameBuilder::new().unwrap();
    name.append_entry_by_nid(Nid::COMMONNAME, "localhost")
        .unwrap();
    let name = name.build();
    let mut cert = X509::builder().unwrap();
    cert.set_version(2).unwrap();
    let serial = BigNum::from_u32(1).unwrap().to_asn1_integer().unwrap();
    cert.set_serial_number(&serial).unwrap();
    cert.set_subject_name(&name).unwrap();
    cert.set_issuer_name(&name).unwrap();
    cert.set_pubkey(&key).unwrap();
    cert.set_not_before(&Asn1Time::days_from_now(0).unwrap())
        .unwrap();
    cert.set_not_after(&Asn1Time::days_from_now(1).unwrap())
        .unwrap();
    cert.sign(&key, MessageDigest::sha256()).unwrap();
    let mut b = SslAcceptor::mozilla_intermediate_v5(SslMethod::tls()).unwrap();
    b.set_private_key(&key).unwrap();
    b.set_certificate(&cert.build()).unwrap();
    b.build()
}

/// Reads the authentication, then frames: a stream's first data frame names its destination and
/// is dropped, and the next one comes back followed by a FIN, which ends the stream. A client's
/// half-close sends no frame, so the server ends each stream after one exchange.
fn serve(mut tls: impl Read + Write) -> io::Result<()> {
    let mut auth = [0u8; 34];
    tls.read_exact(&mut auth)?;
    let padding = u64::from(u16::from_be_bytes([auth[32], auth[33]]));
    io::copy(&mut (&mut tls).take(padding), &mut io::sink())?;
    let mut addressed = HashSet::new();
    loop {
        let mut head = [0u8; 7];
        tls.read_exact(&mut head)?;
        let sid = u32::from_be_bytes([head[1], head[2], head[3], head[4]]);
        let mut data = vec![0u8; usize::from(u16::from_be_bytes([head[5], head[6]]))];
        tls.read_exact(&mut data)?;
        match head[0] {
            PSH if addressed.insert(sid) => {}
            PSH => {
                frame(&mut tls, PSH, sid, &data)?;
                frame(&mut tls, FIN, sid, &[])?;
            }
            HEART_REQUEST => frame(&mut tls, HEART_RESPONSE, sid, &[])?,
            _ => {}
        }
    }
}

fn frame(out: &mut impl Write, cmd: u8, sid: u32, data: &[u8]) -> io::Result<()> {
    let mut f = vec![cmd];
    f.extend_from_slice(&sid.to_be_bytes());
    f.extend_from_slice(&(data.len() as u16).to_be_bytes());
    f.extend_from_slice(data);
    out.write_all(&f)?;
    out.flush()
}
