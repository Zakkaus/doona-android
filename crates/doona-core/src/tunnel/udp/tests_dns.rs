//! DNS over UDP through the core's answer path: what the driver puts in the generation's names
//! comes only from the answer the client gets, and only when it answers the question the client
//! asked.

use super::*;
use crate::dns::names::Names;
use crate::dns::tests::{answer_to, query, through, upstream_with};
use std::net::IpAddr;
use tokio::sync::Semaphore;

/// What a client that sent `q` gets back through an upstream that answers with `answer`, and the
/// names once the driver has put what the answer maps, as it does when the tun takes it.
async fn exchange(answer: fn(&[u8]) -> Vec<u8>, q: &[u8]) -> (Vec<u8>, Names) {
    let (events, mut got) = mpsc::unbounded_channel();
    let slot = Arc::new(Semaphore::new(1)).try_acquire_owned().unwrap();
    let client = SocketAddr::from(([10, 0, 0, 2], 40_000));
    let server = SocketAddr::from(([10, 0, 0, 1], 53));
    let honk = (Some(through(upstream_with(answer))), (client, Some(server)));
    answer_dns(q.to_vec(), server, honk, events, slot).await;
    let Some(Event::DnsAnswer {
        payload, learned, ..
    }) = got.recv().await
    else {
        panic!("no answer");
    };
    let names = Names::new();
    if let Some(learned) = learned {
        names.put(learned);
    }
    (payload, names)
}

/// How many of the addresses `answer_to` gives, 10.0.0.0 on, have a name.
fn named(names: &Names, hosts: u16) -> usize {
    let ip = |i: u16| IpAddr::from([10, 0, (i >> 8) as u8, i as u8]);
    (0..hosts).filter(|&i| names.name(ip(i)).is_some()).count()
}

/// An answer too large for the client's buffer goes back truncated, without an address, so the
/// names learn none of it; the client asks again over TCP, and that answer names them. The same
/// answer sent whole, to a client with room for it, names every address.
#[tokio::test]
async fn a_truncated_answer_maps_nothing() {
    let plain = query(5, "big.example", None);
    let (sent, names) = exchange(|q| answer_to(q, 40), &plain).await;
    assert_eq!(sent[2] & 0x02, 0x02, "TC");
    assert_eq!(named(&names, 40), 0);
    let edns = query(6, "big.example", Some(4096));
    let (sent, names) = exchange(|q| answer_to(q, 40), &edns).await;
    assert_eq!(sent[2] & 0x02, 0, "whole");
    assert_eq!(named(&names, 40), 40);
}

/// An upstream answer with the query's id but another question maps nothing: honk answers the
/// client SERVFAIL. So does one that asks the question in another case (DNS 0x20).
#[tokio::test]
async fn an_answer_to_another_question_maps_nothing() {
    let q = query(7, "asked.example", Some(1232));
    let other = |q: &[u8]| {
        let id = u16::from_be_bytes([q[0], q[1]]);
        answer_to(&query(id, "other.example", None), 2)
    };
    let shouted = |q: &[u8]| {
        let mut a = answer_to(q, 2);
        a[13..18].make_ascii_uppercase();
        a
    };
    for upstream in [other as fn(&[u8]) -> Vec<u8>, shouted] {
        let (sent, names) = exchange(upstream, &q).await;
        assert_eq!((sent[..2] == q[..2], sent[3] & 0x0f), (true, 2), "SERVFAIL");
        assert_eq!(named(&names, 2), 0);
    }
}
