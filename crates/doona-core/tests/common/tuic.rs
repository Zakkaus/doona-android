//! A loopback TUIC v5 server on Quinn, with a certificate made at start. It checks no
//! credentials and echoes each stream once it has read its CONNECT header, instead of relaying
//! it: all a test of the carrier's lifetime needs, since the client keeps the QUIC connection
//! for its next stream. It records the address each client connected from, whose port is the
//! client's QUIC socket.

use super::cert;
use quinn::rustls;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

/// The share link of a node on `addr`; the certificate is not checked.
pub fn link(addr: SocketAddr, name: &str) -> String {
    let user = "b831381d-6324-4d53-ad4f-8cda48b30811:doona-test";
    format!("tuic://{user}@{addr}?sni=localhost&insecure=1#{name}")
}

/// The server's address, and the address of each client that connected so far.
pub fn tuic() -> (SocketAddr, Arc<Mutex<Vec<SocketAddr>>>) {
    let clients = Arc::new(Mutex::new(Vec::new()));
    let seen = Arc::clone(&clients);
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        rt.block_on(async move {
            let endpoint = quinn::Endpoint::server(config(), ([127, 0, 0, 1], 0).into()).unwrap();
            tx.send(endpoint.local_addr().unwrap()).unwrap();
            while let Some(incoming) = endpoint.accept().await {
                let seen = Arc::clone(&seen);
                tokio::spawn(async move {
                    let Ok(conn) = incoming.await else { return };
                    seen.lock().unwrap().push(conn.remote_address());
                    serve(&conn).await;
                });
            }
        });
    });
    (rx.recv().unwrap(), clients)
}

fn config() -> quinn::ServerConfig {
    let (cert, key) = cert::localhost();
    let cert = rustls::pki_types::CertificateDer::from(cert.to_der().unwrap());
    let key = rustls::pki_types::PrivatePkcs8KeyDer::from(key.private_key_to_der_pkcs8().unwrap());
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut tls = rustls::ServerConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])
        .unwrap()
        .with_no_client_auth()
        .with_single_cert(vec![cert], key.into())
        .unwrap();
    tls.alpn_protocols = vec![b"tuic".to_vec()];
    let crypto = quinn::crypto::rustls::QuicServerConfig::try_from(tls).unwrap();
    quinn::ServerConfig::with_crypto(Arc::new(crypto))
}

/// Drains the authentication stream and echoes every stream the client opens, until the
/// connection ends.
async fn serve(conn: &quinn::Connection) {
    loop {
        tokio::select! {
            uni = conn.accept_uni() => {
                let Ok(mut auth) = uni else { return };
                tokio::spawn(async move { auth.read_to_end(1024).await });
            }
            bi = conn.accept_bi() => {
                let Ok((send, recv)) = bi else { return };
                tokio::spawn(echo(send, recv));
            }
        }
    }
}

/// Reads the CONNECT header (version, command, an address in sing's numbering, the port), then
/// sends back everything that follows.
async fn echo(mut send: quinn::SendStream, mut recv: quinn::RecvStream) -> Option<()> {
    let mut head = [0u8; 3];
    recv.read_exact(&mut head).await.ok()?;
    let len = match head[2] {
        0x01 => 4,
        0x02 => 16,
        _ => {
            let mut n = [0u8; 1];
            recv.read_exact(&mut n).await.ok()?;
            usize::from(n[0])
        }
    };
    let mut rest = vec![0u8; len + 2];
    recv.read_exact(&mut rest).await.ok()?;
    tokio::io::copy(&mut recv, &mut send).await.ok()?;
    send.finish().ok()
}
