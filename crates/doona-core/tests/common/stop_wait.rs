//! The core's stop budget, which the integration tests cannot import.

use std::time::Duration;

/// `STOP_WAIT` in `src/tunnel/mod.rs:49`, which is `pub(crate)`. A unit test in
/// `src/api/tests.rs` asserts that the two agree.
pub const STOP_WAIT: Duration = Duration::from_millis(900);
