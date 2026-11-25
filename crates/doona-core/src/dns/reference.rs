//! Slice 9: captured DNS against Lane A's honk reference (`tools/honk-reference`,
//! `fixtures/honk/reference/dns`). Each case is committed through the honk store and answered
//! through [`answer`] with the generation's runtime; its reply bytes (id zeroed) and the
//! upstreams it dialed must equal the reference's. The cases that read geosite need the pinned
//! DATs (`DOONA_GEO_DIR`).

use super::tests::runtime;
use super::{Network, answer};
use serde_json::Value;
use std::collections::BTreeMap;
use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

const REFERENCE: &str = concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/tests/fixtures/honk/reference/dns"
);

const CLIENT: SocketAddr = SocketAddr::new(
    std::net::IpAddr::V4(std::net::Ipv4Addr::new(10, 0, 0, 2)),
    40000,
);

/// The reference's query: id 0, RD, one A question, and an OPT record of `edns` bytes.
fn query(name: &str, edns: Option<u16>) -> Vec<u8> {
    super::tests::query(0, name, edns)
}

/// The reference's upstream answer to `q`: its question and one A record of `address`, TTL 60.
fn upstream_answer(q: &[u8], address: Ipv4Addr) -> Option<Vec<u8>> {
    let end = super::question_end(q)?;
    let mut a = q[..end].to_vec();
    a[2..4].copy_from_slice(&0x8180u16.to_be_bytes());
    a[6..12].copy_from_slice(&[0, 1, 0, 0, 0, 0]);
    a.extend_from_slice(&[0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4]);
    a.extend_from_slice(&address.octets());
    Some(a)
}

/// A loopback UDP upstream answering every query with `address`, and the count of queries it got.
fn upstream(address: Ipv4Addr) -> (SocketAddr, Arc<AtomicUsize>) {
    let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
    let addr = socket.local_addr().unwrap();
    let queries = Arc::new(AtomicUsize::new(0));
    let counted = Arc::clone(&queries);
    std::thread::spawn(move || {
        let mut buf = [0; 1500];
        while let Ok((len, peer)) = socket.recv_from(&mut buf) {
            counted.fetch_add(1, Ordering::SeqCst);
            if let Some(reply) = upstream_answer(&buf[..len], address) {
                let _ = socket.send_to(&reply, peer);
            }
        }
    });
    (addr, queries)
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

async fn compare(case: &Value, expected: &Value, geo: &Path) {
    let upstreams: BTreeMap<&str, (SocketAddr, Arc<AtomicUsize>)> = case["upstreams"]
        .as_object()
        .unwrap()
        .iter()
        .map(|(name, address)| {
            let address = address.as_str().unwrap().parse().unwrap();
            (name.as_str(), upstream(address))
        })
        .collect();
    let path = Path::new(REFERENCE).join(case["config"].as_str().unwrap());
    let mut source = std::fs::read_to_string(path).unwrap();
    for (name, (addr, _)) in &upstreams {
        source = source.replace(&format!("{{{name}}}"), &addr.to_string());
    }
    let runtime = runtime(&source, geo);
    let queries = case["queries"].as_array().unwrap();
    let want = expected["queries"].as_array().unwrap();
    assert_eq!(queries.len(), want.len());
    for (q, want) in queries.iter().zip(want) {
        assert_eq!(q["name"], want["name"]);
        let server = q["server"].as_str().map(|name| upstreams[name].0);
        let network = match q["network"].as_str().unwrap() {
            "udp" => Network::Udp,
            _ => Network::Tcp,
        };
        let edns = q["edns"].as_u64().map(|size| u16::try_from(size).unwrap());
        let before: Vec<usize> = upstreams
            .values()
            .map(|(_, n)| n.load(Ordering::SeqCst))
            .collect();
        let query = query(q["qname"].as_str().unwrap(), edns);
        let mut reply = answer(Some(&runtime), &query, network, (CLIENT, server))
            .await
            .unwrap();
        let dialed: Vec<&str> = upstreams
            .iter()
            .zip(before)
            .filter(|((_, (_, n)), before)| n.load(Ordering::SeqCst) > *before)
            .map(|((name, _), _)| *name)
            .collect();
        reply[..2].fill(0);
        let name = &q["name"];
        assert_eq!(hex(&reply), want["reply"], "{} {name}: reply", case["name"]);
        assert_eq!(
            Value::from(dialed),
            want["dialed"],
            "{} {name}: dialed",
            case["name"]
        );
    }
}

async fn corpus(geo: Option<&Path>) {
    let read = |file: &str| -> Value {
        serde_json::from_slice(&std::fs::read(Path::new(REFERENCE).join(file)).unwrap()).unwrap()
    };
    let (corpus, expected) = (read("corpus.json"), read("expected.json"));
    let empty = crate::testutil::TempDir::new("dns-reference-nogeo");
    let mut ran = 0;
    for (case, want) in corpus["cases"]
        .as_array()
        .unwrap()
        .iter()
        .zip(expected["cases"].as_array().unwrap())
    {
        assert_eq!(case["name"], want["name"]);
        let path = Path::new(REFERENCE).join(case["config"].as_str().unwrap());
        let needs_geo = std::fs::read_to_string(path).unwrap().contains("geosite:");
        if needs_geo != geo.is_some() {
            continue;
        }
        compare(case, want, geo.unwrap_or(empty.path())).await;
        ran += 1;
    }
    assert!(ran > 0);
}

#[tokio::test]
async fn captured_dns_matches_the_honk_reference() {
    corpus(None).await;
}

#[tokio::test]
#[ignore = "requires DOONA_GEO_DIR containing the pinned DATs"]
async fn captured_geosite_dns_matches_the_honk_reference() {
    let geo = std::path::PathBuf::from(std::env::var_os("DOONA_GEO_DIR").unwrap());
    corpus(Some(&geo)).await;
}
