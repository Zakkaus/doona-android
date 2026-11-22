//! The registries a node set is built as: honk's registry holds one runtime per id, and nodes of
//! two subscriptions can share an id while they dial differently (see [`super`]).

use honk_config::node::Node;
use honk_outbound::runtime::OutboundRuntimeRegistry;
use std::collections::HashMap;
use std::sync::Arc;

/// The registries of one node set, never none; see [`layers`].
#[derive(Clone)]
pub(crate) struct Registries(pub(super) Vec<Arc<OutboundRuntimeRegistry>>);

impl Registries {
    /// The registry that holds `node`'s runtime, or the first when none does.
    pub(crate) fn of(&self, node: &Node) -> Option<&Arc<OutboundRuntimeRegistry>> {
        let first = self.0.first();
        if self.0.len() == 1 {
            return first;
        }
        let holds = |r: &&Arc<OutboundRuntimeRegistry>| {
            r.get(&node.id)
                .is_some_and(|runtime| same_dial(&runtime.node, node))
        };
        self.0.iter().find(holds).or(first)
    }
}

/// `nodes` in layers, one registry each: a node goes in the first layer that does not hold its
/// id, and is dropped when a layer holds one that dials as it does.
pub(super) fn layers<'a>(nodes: impl IntoIterator<Item = &'a Node>) -> Vec<Vec<Node>> {
    let mut held: Vec<HashMap<uuid::Uuid, &Node>> = Vec::new();
    let mut placed = Vec::new();
    'nodes: for node in nodes {
        for (layer, ids) in held.iter_mut().enumerate() {
            match ids.get(&node.id) {
                Some(other) if same_dial(other, node) => continue 'nodes,
                Some(_) => {}
                None => {
                    ids.insert(node.id, node);
                    placed.push((layer, node));
                    continue 'nodes;
                }
            }
        }
        held.push(HashMap::from([(node.id, node)]));
        placed.push((held.len() - 1, node));
    }
    let mut layers = vec![Vec::new(); held.len()];
    for (layer, node) in placed {
        layers[layer].push(node.clone());
    }
    layers
}

/// Whether two nodes dial alike: equal but for their names, tags, owners and times. Every field is
/// named, so one honk adds does not compile until it is sorted here.
fn same_dial(a: &Node, b: &Node) -> bool {
    let Node {
        id,
        name: _,
        address,
        host,
        port,
        outbound,
        mark,
        tags: _,
        subscription_id: _,
        group_id: _,
        created_at: _,
        updated_at: _,
    } = a;
    (id, address, host, port, outbound, mark)
        == (&b.id, &b.address, &b.host, &b.port, &b.outbound, &b.mark)
}
