//! doona-Android's Rust core: the JNI surface of `dev.doona.android.vpn.NativeCore`
//! (`plans/v1/core-api.md`, schema 1) over a plain Rust API, [`api::Core`], that host tests drive
//! without a JVM.

pub mod api;
pub mod dto;
mod jni;
pub mod sys;
