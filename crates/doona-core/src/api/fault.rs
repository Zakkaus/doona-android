//! A fault for debug builds of the app only: with the `fault-hook` feature, which the app's Gradle
//! build turns on for its debug variant, `call("debugFault", "{}")` panics a task of the running
//! generation. Its data path then ends as a crash would end it, so the app's health check can be
//! shown to catch it (PLAN-v1 section 7, core failure). Without the feature the command is
//! unknown, as any other is.

use super::{Core, Life, lock};
use crate::dto::ErrorCode;

impl Core {
    /// `not_found` when no generation runs.
    pub(super) fn fault(&self) -> Result<(), ErrorCode> {
        match &*lock(&self.life) {
            Life::Running(tunnel) => {
                log::warn!("debugFault: failing the running generation");
                tunnel.fault();
                Ok(())
            }
            _ => Err(ErrorCode::NotFound),
        }
    }
}
