use serde::{Deserialize, Serialize};

/// `nodes`' choice: every node in catalog order, or these ids (unknown ones are left out).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct NodesArgs {
    #[serde(deserialize_with = "Option::deserialize")]
    pub ids: Option<Vec<String>>,
}

/// `nodes`' reply.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Nodes {
    pub nodes: Vec<NodeInfo>,
}

/// What a node's share link says, and its last latency tests (at most 8, oldest first). The
/// protocol is honk's name (`trojan`, `vless`, `ss`, `hysteria2`, …); the transport is `quic` for
/// the QUIC protocols, else the stream transport (`tcp`, `ws`, `grpc`, …).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NodeInfo {
    pub id: String,
    /// The subscription the node came from; null for a static node.
    pub subscription_id: Option<String>,
    pub protocol: String,
    pub server: String,
    pub port: u16,
    pub transport: String,
    pub tls: bool,
    pub tests: Vec<LatencyTest>,
}

/// One latency test: when its round began, and the time it measured; none when the probe failed.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LatencyTest {
    pub at_epoch_ms: i64,
    pub ms: Option<u32>,
}
