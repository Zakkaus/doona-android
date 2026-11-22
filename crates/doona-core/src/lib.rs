//! doona-Android's Rust core. This first commit only proves that honk-config, honk-outbound
//! (with `rprx`) and honk-netstack build and link into one arm64 `cdylib`.

use std::time::Instant;

/// Touches one entry point of each honk crate so the linker keeps them in the library.
/// Returns the number of crates that answered.
#[doc(hidden)]
#[unsafe(no_mangle)]
pub extern "C" fn doona_core_link_probe() -> u32 {
    let node = honk_config::node::Node::from_share_link("ss://invalid").is_ok();
    let registry = honk_outbound::ProxyRegistry::default_resolver().is_ok();
    let stack = honk_netstack::Stack::new(honk_netstack::Config::new([0; 32]), Instant::now());
    u32::from(node) + u32::from(registry) + u32::from(stack.is_quiescent())
}
