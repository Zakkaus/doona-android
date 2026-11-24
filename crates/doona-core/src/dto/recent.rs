use serde::{Deserialize, Serialize};
use std::net::SocketAddr;

#[derive(Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Protocol {
    Tcp,
    Udp,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RecentFlow<T = String> {
    pub protocol: Protocol,
    pub local: SocketAddr,
    pub remote: SocketAddr,
    /// Sniffed hostname, or the name from a DNS A answer: the tunnel carries IPv4 only.
    pub name: Option<T>,
    pub outbound: T,
    /// Milliseconds since tunnel start on CLOCK_BOOTTIME, including suspend (Android elapsedRealtime).
    pub started_ms: u64,
    pub open: bool,
}

#[derive(Default, Serialize, Deserialize)]
pub struct RecentFlows {
    pub flows: Vec<RecentFlow>,
}
