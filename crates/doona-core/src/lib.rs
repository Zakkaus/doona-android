//! doona-Android's Rust core: the JNI surface of `dev.doona.android.vpn.NativeCore`
//! (`plans/v1/core-api.md`, schema 1) over a plain Rust API, [`api::Core`], that host tests drive
//! without a JVM.

pub mod api;
mod dns;
pub mod dto;
mod jni;
pub mod state;
pub mod sys;
pub mod tunnel;

#[cfg(test)]
pub(crate) mod testutil;
