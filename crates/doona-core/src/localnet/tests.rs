//! The classifier, and what a [`Trace`] makes of honk's reports: real connects through honk's
//! connector, and the events honk reports for a lookup and for a node address taken from one.

use super::*;
use honk_outbound::runtime::flow_observation::{self, DnsLookup, ResolutionLocation};
use std::net::SocketAddr;
use std::time::Duration;

fn local(text: &str) -> bool {
    is_local(text.parse().unwrap())
}

#[test]
fn private_link_local_cgnat_and_multicast_ranges_are_local() {
    let v4 = [
        "10.0.0.1",
        "10.255.255.254",
        "172.16.0.1",
        "172.31.255.254",
        "192.168.8.161",
        "169.254.1.1",
        "100.64.0.1",
        "100.127.255.254",
        "224.0.0.251",
        "239.255.255.250",
        "255.255.255.255",
    ];
    let v6 = [
        "fc00::1",
        "fd12:3456:789a::1",
        "fe80::1",
        "febf::1",
        "ff02::fb",
        // An IPv4 local address mapped into IPv6 is still that address.
        "::ffff:192.168.1.1",
    ];
    for text in v4.iter().chain(&v6) {
        assert!(local(text), "{text}");
    }
}

#[test]
fn loopback_unspecified_and_public_addresses_are_not_local() {
    let not = [
        "127.0.0.1",
        "127.1.2.3",
        "::1",
        "0.0.0.0",
        "::",
        // Just outside the private ranges.
        "9.255.255.255",
        "11.0.0.1",
        "172.15.255.255",
        "172.32.0.1",
        "192.167.255.255",
        "192.169.0.1",
        "100.63.255.255",
        "100.128.0.1",
        "169.253.255.255",
        "1.1.1.1",
        "192.0.2.9",
        "203.0.113.7",
        "2001:db8::1",
        "2606:4700:4700::1111",
        "fec0::1",
        "::ffff:8.8.8.8",
    ];
    for text in not {
        assert!(!local(text), "{text}");
    }
}

#[test]
fn literals_are_addresses_and_names_are_not() {
    assert_eq!(
        literal("192.168.8.161"),
        Some("192.168.8.161".parse().unwrap())
    );
    assert_eq!(literal("[fd00::2]"), Some("fd00::2".parse().unwrap()));
    assert_eq!(literal("192-168-8-161.sslip.io"), None);
}

/// A connect through honk's own connector, traced.
async fn connect(trace: &Trace, addr: SocketAddr) -> bool {
    let dial = honk_outbound::connect_marked_addr(addr, None, Duration::from_secs(2));
    trace.scope(dial).await.is_ok()
}

/// Linux refuses TCP to a multicast address at once; Android counts it as local.
#[tokio::test]
async fn a_connect_that_failed_at_a_local_address_was_refused_locally() {
    let trace = Trace::default();
    let lan: SocketAddr = "224.0.0.1:9".parse().unwrap();
    assert!(!connect(&trace, lan).await);
    assert!(trace.refused_locally());
    assert_eq!(trace.peer(), Some(lan.ip()));

    let closed = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = closed.local_addr().unwrap();
    drop(closed);
    let trace = Trace::default();
    assert!(!connect(&trace, addr).await);
    assert!(!trace.refused_locally(), "loopback is not local");
}

/// A transport that came up to the node means the hop to it was not refused, whatever fails
/// after it; one on the local network says the permission is there.
#[tokio::test]
async fn a_connect_that_came_up_was_not_refused() {
    let node = std::net::TcpListener::bind("127.77.0.1:0").unwrap();
    let trace = Trace::default();
    assert!(connect(&trace, node.local_addr().unwrap()).await);
    assert!(trace.reached_locally());
    // A local address tried before it does not change that.
    assert!(!connect(&trace, "224.0.0.1:9".parse().unwrap()).await);
    assert!(!trace.refused_locally());
}

/// A dial that never chose a node address, as when a name did not resolve, was not refused.
#[tokio::test]
async fn a_dial_that_chose_no_address_was_not_refused() {
    let trace = Trace::default();
    let failed: Result<(), ()> = trace.scope(async { Err(()) }).await;
    assert!(failed.is_err());
    assert!(!trace.refused_locally() && !trace.reached_locally());
    assert_eq!(trace.peer(), None);
}

fn transport(addr: &str, status: TransportStatus) -> FlowEvent {
    FlowEvent::Transport {
        attempt_id: uuid::Uuid::nil(),
        server_addr: Some(addr.parse().unwrap()),
        status,
        resolution_location: ResolutionLocation::Unknown,
        error: None,
    }
}

/// A lookup of a node's name as honk reports it: the addresses, and the one honk took, if any.
fn lookup(addresses: &[&str], selected: Option<&str>) -> FlowEvent {
    FlowEvent::Dns(DnsLookup {
        lookup_id: uuid::Uuid::nil(),
        parent_lookup_id: None,
        attempt_id: None,
        purpose: "node",
        name: "nas.localnet.test".to_owned(),
        qtype: "A".to_owned(),
        source: "upstream",
        upstream_transport: None,
        carrier_transport: None,
        cache: "bypass",
        cache_entry_id: None,
        upstream: None,
        route_evaluation_ids: Vec::new(),
        status: "succeeded",
        addresses: addresses.iter().map(|a| a.parse().unwrap()).collect(),
        selected_ip: selected.map(|a| a.parse().unwrap()),
        error: None,
    })
}

/// Only the address honk took from a lookup counts: a name that resolved to a public and a local
/// address, of which a UDP transport took the public one, was not refused on the local network.
#[test]
fn only_the_address_taken_from_a_lookup_counts() {
    let both = ["203.0.113.7", "fd00::2"];
    let trace = Trace::default();
    trace.record(&lookup(&both, None));
    assert!(!trace.refused_locally() && trace.peer().is_none());
    trace.record(&lookup(&both, Some("203.0.113.7")));
    assert!(!trace.refused_locally());
    assert_eq!(trace.peer(), Some("203.0.113.7".parse().unwrap()));

    let trace = Trace::default();
    trace.record(&lookup(&both, Some("fd00::2")));
    assert!(trace.refused_locally());
}

/// The resolver's own TCP connection, inside a lookup, is not a transport to the node: it neither
/// counts as a choice nor as one that came up.
#[tokio::test]
async fn a_lookup_s_own_connection_is_not_the_node_s() {
    let trace = Trace::default();
    trace
        .scope(async {
            let observer = flow_observation::current().unwrap();
            let mut context = observer.context();
            context.lookup_id = Some(uuid::Uuid::nil());
            let resolver = observer.with_context(context);
            resolver.publish(transport("192.168.8.1:53", TransportStatus::Succeeded));
            observer.publish(transport("224.0.0.1:443", TransportStatus::Failed));
        })
        .await;
    assert!(trace.refused_locally());
    assert_eq!(trace.peer(), Some("224.0.0.1".parse().unwrap()));
}

/// A probe went on to dial its node only once its target resolved; then its node counts by the
/// literal or by what the node's name resolved to for this probe, and a lookup outside any probe
/// counts for none.
#[tokio::test]
async fn a_probe_counts_its_node_only_once_its_target_resolved() {
    let lan: IpAddr = "224.0.0.1".parse().unwrap();
    let public: IpAddr = "203.0.113.7".parse().unwrap();
    looked_up(false, &[lan]);

    let probe = Lookups::default();
    probe.scope(async { looked_up(false, &[lan]) }).await;
    assert!(
        !probe.refused_locally("nas.localnet.test"),
        "the target never resolved"
    );
    assert!(!probe.refused_locally("192.168.8.2"));

    let probe = Lookups::default();
    probe
        .scope(async {
            looked_up(true, &[public]);
            looked_up(false, &[lan]);
        })
        .await;
    assert!(probe.refused_locally("nas.localnet.test"));

    let probe = Lookups::default();
    probe.scope(async { looked_up(true, &[public]) }).await;
    assert!(probe.refused_locally("192.168.8.2"));
    assert!(probe.refused_locally("[fd00::2]"));
    assert!(!probe.refused_locally("127.0.0.1"));
    assert!(
        !probe.refused_locally("nas.localnet.test"),
        "its name never resolved"
    );
}

/// A node's name that resolved to a public and a local address may have been dialed at the public
/// one, so a probe that failed through it says nothing about the local network.
#[tokio::test]
async fn a_probe_through_a_name_with_a_public_answer_was_not_refused_locally() {
    let probe = Lookups::default();
    probe
        .scope(async {
            looked_up(true, &["203.0.113.9".parse().unwrap()]);
            looked_up(false, &["203.0.113.7".parse().unwrap()]);
            looked_up(false, &["fd00::2".parse().unwrap()]);
        })
        .await;
    assert!(!probe.refused_locally("nas.localnet.test"));
}
