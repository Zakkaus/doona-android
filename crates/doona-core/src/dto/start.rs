use serde::{Deserialize, Serialize};

/// The tunnel parameters and, optionally, the private directory of extracted APK assets.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartConfig {
    pub mtu: u16,
    pub ipv4: std::net::Ipv4Addr,
    pub dns: std::net::Ipv4Addr,
    /// Android resolver addresses, including scoped IPv6 (e.g. `fe80::1%wlan0`).
    pub bootstrap_dns: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub geo_asset_dir: Option<std::path::PathBuf>,
}
