use serde::{Deserialize, Serialize};

/// The tunnel parameters and, optionally, the private directory of extracted APK assets.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartConfig {
    /// MTU configured on the tun.
    pub mtu: u16,
    /// IPv4 address assigned to the tun.
    pub ipv4: std::net::Ipv4Addr,
    /// The virtual resolver the tun advertises; DNS sent to it is answered by the core.
    pub dns: std::net::Ipv4Addr,
    /// Android resolver addresses, including scoped IPv6 (e.g. `fe80::1%wlan0`).
    pub bootstrap_dns: Vec<String>,
    /// Private directory containing the APK's extracted, pinned DAT files.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub geo_asset_dir: Option<std::path::PathBuf>,
}

#[derive(Serialize, Deserialize)]
pub struct DnsUpstreamsArgs {
    pub servers: Vec<String>,
}
