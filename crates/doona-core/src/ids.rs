//! Stable ids (core-api.md, Identity). A node's id hashes its subscription id with honk's endpoint
//! identity, `Node::derive_id` (protocol, host, port and the credential and dial-shape
//! fingerprints, never the display name), so a reordered feed keeps its ids and two endpoints
//! that share a name do not share an id. A group's id is `g-` plus its name.

use uuid::Uuid;

pub(crate) fn scoped_uuid(subscription: &str, endpoint: Uuid) -> Uuid {
    Uuid::new_v5(&endpoint, subscription.as_bytes())
}

pub(crate) fn static_uuid(node: &honk_config::node::Node) -> Uuid {
    // Subscription scopes are UTF-8; this namespace cannot name one.
    let base = Uuid::new_v5(&node.id, &[0xff]);
    // These admitted dial options are absent from honk's endpoint fingerprint.
    let tls = node.tls();
    let vmess = node.vmess();
    let tuic = node.tuic();
    let extra = (
        tls.is_some_and(|t| t.skip_cert_verify),
        tls.and_then(|t| t.pin_sha256.as_deref()),
        node.transport()
            .is_some_and(|t| t.grpc_service.as_deref() == Some("")),
        vmess.is_some_and(|v| v.tls.enabled),
        vmess
            .and_then(|v| v.encryption.as_deref())
            .filter(|v| *v != "auto"),
        tuic.and_then(|t| t.alpn.as_deref()),
        tuic.and_then(|t| t.congestion.as_deref()),
        node.hysteria2().and_then(|h| h.port_hopping.as_deref()),
    );
    if extra == Default::default() {
        base
    } else {
        Uuid::new_v5(
            &base,
            &serde_json::to_vec(&extra).expect("string tuple serializes"),
        )
    }
}

/// `n-` and 16 hex digits: a node of `subscription` with honk's endpoint identity `endpoint`.
#[cfg(test)]
pub(crate) fn node_id(subscription: &str, endpoint: Uuid) -> String {
    short_id(scoped_uuid(subscription, endpoint))
}

pub(crate) fn short_id(hash: Uuid) -> String {
    let mut first = [0; 8];
    first.copy_from_slice(&hash.as_bytes()[..8]);
    format!("n-{:016x}", u64::from_be_bytes(first))
}

pub(crate) fn group_id(name: &str) -> String {
    format!("g-{name}")
}

pub(crate) fn is_group(member: &str) -> bool {
    member.starts_with("g-")
}

#[cfg(test)]
mod tests {
    use super::*;
    use honk_config::node::Node;

    fn endpoint(link: &str) -> Uuid {
        Node::from_share_link(link).unwrap().id
    }

    #[test]
    fn a_display_name_shared_by_two_endpoints_gives_two_ids() {
        let a = endpoint("trojan://secret@192.0.2.1:443#hk-01");
        let b = endpoint("trojan://other@192.0.2.1:443#hk-01");
        assert_ne!(node_id("s1", a), node_id("s1", b));
    }

    #[test]
    fn the_name_and_the_position_do_not_change_an_id() {
        let a = endpoint("trojan://secret@192.0.2.1:443#hk-01");
        let renamed = endpoint("trojan://secret@192.0.2.1:443#Hong%20Kong");
        assert_eq!(node_id("s1", a), node_id("s1", renamed));
        let id = node_id("s1", a);
        assert!(id.starts_with("n-") && id.len() == 18, "{id}");
    }

    #[test]
    fn the_same_endpoint_in_two_subscriptions_gives_two_ids() {
        let a = endpoint("trojan://secret@192.0.2.1:443#hk-01");
        assert_ne!(node_id("s1", a), node_id("s2", a));
    }

    #[test]
    fn group_ids_are_the_name_with_a_prefix() {
        assert_eq!(group_id("Proxy"), "g-Proxy");
        assert!(is_group("g-HK") && !is_group("n-0c41be77"));
    }
}
