//! doona-Android's Rust core: the JNI surface of `dev.doona.android.vpn.NativeCore`
//! (`plans/v1/core-api.md`, schema 3) over a plain Rust API, [`api::Core`], that host tests drive
//! without a JVM.

pub mod api;
mod bootstrap;
mod catalog;
mod diag;
mod dns;
pub mod dto;
mod groups;
pub mod honkconf;
mod ids;
mod jni;
mod localnet;
pub mod outbounds;
mod panics;
mod probe;
mod regions;
mod rules;
mod score;
pub mod state;
mod stats;
mod subscription;
pub mod sys;
pub mod tunnel;

#[cfg(test)]
pub(crate) mod testutil;
