//! Nodes of one honk id that dial differently, from two subscriptions, and the count of registry
//! retirements that tests wait on.

use super::{Outbounds, Registries, Routing};
use crate::api::lock;
use crate::catalog::{NodeEntry, entries};
use crate::dto::Mode;
use crate::subscription::parse::parse;
use crate::testutil::one_dial_ceiling;
use honk_config::node::Node;
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

/// Counts the retirements of replaced registries under way; each [`Retirement`] counts one until
/// dropped.
#[derive(Default)]
pub(super) struct Retiring(Mutex<usize>, Condvar);

pub(super) struct Retirement(Arc<Retiring>);

impl Retiring {
    pub(super) fn begin(self: &Arc<Self>) -> Retirement {
        *lock(&self.0) += 1;
        Retirement(Arc::clone(self))
    }
}

impl Drop for Retirement {
    fn drop(&mut self) {
        *lock(&self.0.0) -= 1;
        self.0.1.notify_all();
    }
}

impl Outbounds {
    /// Waits until every registry replaced so far has retired and been let go.
    pub(crate) fn wait_retired(&self) {
        let (count, done) = (&self.retiring.0, &self.retiring.1);
        let wait = done.wait_timeout_while(lock(count), Duration::from_secs(10), |n| *n > 0);
        assert!(!wait.unwrap().1.timed_out(), "registries still retiring");
    }
}

/// A Clash TUIC proxy named `name` on `server`, with `extra` fields; it has ALPN h3 unless
/// `extra` names another.
fn clash(name: &str, server: &str, extra: &str) -> Node {
    let body = format!(
        "proxies:\n  - {{name: {name}, type: tuic, server: {server}, port: 443, \
         uuid: b38ad070-88e0-4bcc-9808-924f3c0e1bd5, password: p, sni: tuic.example.net{extra}}}\n"
    );
    parse(body.as_bytes()).unwrap().nodes.remove(0)
}

/// The nodes of three subscriptions, published: the Clash proxy `c`, a sing-box TUIC proxy of
/// the same id and no ALPN, and `c` again as `c2`. honk's id leaves the TUIC ALPN out.
fn published() -> (Outbounds, Vec<Arc<NodeEntry>>) {
    let sing_box = r#"{"outbounds": [{"type": "tuic", "tag": "s", "server": "192.0.2.1",
        "server_port": 443, "uuid": "b38ad070-88e0-4bcc-9808-924f3c0e1bd5", "password": "p",
        "tls": {"enabled": true, "server_name": "tuic.example.net"}}]}"#;
    let first = clash("c", "192.0.2.1", "");
    let second = parse(sing_box.as_bytes()).unwrap().nodes.remove(0);
    assert_eq!(first.id, second.id);
    assert_ne!(first.outbound, second.outbound);
    let mut nodes = entries("s-a", vec![first]);
    nodes.extend(entries("s-b", vec![second]));
    nodes.extend(entries("s-c", vec![clash("c2", "192.0.2.1", "")]));
    let outbounds = Outbounds::new(Arc::default()).unwrap();
    let built = outbounds.build(&nodes);
    let selected = nodes
        .iter()
        .map(|n| {
            (
                Arc::from(n.id.as_str()),
                super::NetworkLeaves {
                    tcp: Some(super::Leaf::Node(Arc::clone(&n.node))),
                    udp: Some(super::Leaf::Node(Arc::clone(&n.node))),
                },
            )
        })
        .collect();
    let (policy, mode) = (Arc::default(), Mode::Global);
    outbounds.replace(
        built,
        Routing {
            score: crate::catalog::Model::new(nodes.clone(), Default::default()).score,
            selected,
            policy,
            mode,
        },
    );
    (outbounds, nodes)
}

#[test]
fn nodes_of_one_id_that_dial_differently_get_runtimes_of_their_own() {
    let (outbounds, nodes) = published();
    let runtime = |id: &str| {
        let dialer = outbounds.dialer(id).unwrap();
        dialer.registry.get(&dialer.node.id).unwrap()
    };
    for n in &nodes {
        assert_eq!(runtime(&n.id).node.outbound, n.node.outbound, "{}", n.name);
    }
    // The same proxy under another name, in a third subscription, dials as the first does.
    assert!(Arc::ptr_eq(&runtime(&nodes[0].id), &runtime(&nodes[2].id)));
    let (registries, _) = outbounds.published().unwrap();
    let runtimes: usize = registries.0.iter().map(|r| r.len()).sum();
    assert_eq!(runtimes, 2);
}

/// A node that no registry holds a runtime of, or none that dials as it does, has no registry;
/// another registry's runtime of its id would dial with another node's settings.
#[test]
fn a_node_no_registry_dials_as_has_none() {
    let (outbounds, nodes) = published();
    let (registries, _) = outbounds.published().unwrap();
    assert_eq!(registries.0.len(), 2);
    let other_alpn = clash("c3", "192.0.2.1", ", alpn: [h3-29]");
    assert_eq!(other_alpn.id, nodes[0].node.id);
    let unknown = clash("u", "192.0.2.9", "");
    for node in [&other_alpn, &unknown] {
        assert!(registries.of(node).is_none(), "{}", node.name);
    }
    let one = Registries(vec![Arc::clone(&registries.0[0])]);
    assert!(Arc::ptr_eq(one.of(&nodes[0].node).unwrap(), &one.0[0]));
    assert!(one.of(&nodes[1].node).is_none());
    assert!(one.of(&unknown).is_none());
}

/// Each registry has a dial ceiling from honk; those of one node set share the first one's, so
/// that ceiling bounds every dial however many registries the set needs.
#[test]
fn the_registries_of_a_node_set_dial_within_one_ceiling() {
    let (outbounds, _) = published();
    let (registries, _) = outbounds.published().unwrap();
    assert_eq!(registries.0.len(), 2);
    assert!(one_dial_ceiling(&registries.0));
}
