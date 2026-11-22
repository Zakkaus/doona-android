//! DNS over UDP through the core's answer path: the generation's names learn only from the
//! answer the client gets, and only when it answers the question the client asked.

use super::*;
use crate::dns::tests::{answer_to, query, upstream_with};
use std::net::IpAddr;
use std::sync::Weak;
use tokio::sync::Semaphore;

/// What a client that sent `q` gets back through an upstream that answers with `answer`, and the
/// session whose names learned from it.
async fn exchange(answer: fn(&[u8]) -> Vec<u8>, q: &[u8]) -> (Vec<u8>, Arc<Session>) {
    let session = Arc::new(Session::new(1, Weak::new()));
    let (events, mut got) = mpsc::unbounded_channel();
    let slot = Arc::new(Semaphore::new(1)).try_acquire_owned().unwrap();
    let client = SocketAddr::from(([10, 0, 0, 2], 40_000));
    let server = SocketAddr::from(([10, 0, 0, 1], 53));
    let upstreams = Arc::from([upstream_with(answer)]);
    let via = (Route::Direct, Arc::clone(&session));
    answer_dns(q.to_vec(), (client, server), via, upstreams, events, slot).await;
    match got.recv().await {
        Some(Event::DnsAnswer { payload, .. }) => (payload, session),
        _ => panic!("no answer"),
    }
}

/// How many of the addresses `answer_to` gives, 10.0.0.0 on, have a name.
fn named(session: &Session, hosts: u16) -> usize {
    let ip = |i: u16| IpAddr::from([10, 0, (i >> 8) as u8, i as u8]);
    (0..hosts)
        .filter(|&i| session.names().name(ip(i)).is_some())
        .count()
}

/// An answer with the query's id but another question maps nothing, though the client gets it;
/// the question asked, in another case (DNS 0x20), maps.
#[tokio::test]
async fn an_answer_to_another_question_maps_nothing() {
    let q = query(7, "asked.example", Some(1232));
    let other = |q: &[u8]| {
        let id = u16::from_be_bytes([q[0], q[1]]);
        answer_to(&query(id, "other.example", None), 2)
    };
    let (sent, session) = exchange(other, &q).await;
    assert_eq!(sent[..2], q[..2]);
    assert_eq!(named(&session, 2), 0);
    let shouted = |q: &[u8]| {
        let mut a = answer_to(q, 2);
        a[13..18].make_ascii_uppercase();
        a
    };
    let (_, session) = exchange(shouted, &q).await;
    assert_eq!(named(&session, 2), 2);
}
