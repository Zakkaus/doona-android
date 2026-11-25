//! `init`'s options (`init-options.json`), which last for the process: neither runtime is rebuilt
//! later, so a changed option takes effect when the app process starts next.

use super::{Core, Options};
use crate::dto::{ErrorCode, InitOptions};

/// The worker threads of each runtime; flows are I/O bound and the phone has few big cores.
pub(super) const WORKERS: usize = 2;
/// Performance mode's: four SS2022 flows uploaded 26 Gbps instead of 20 on a desktop, one flow
/// no faster (plans/v1 tput-results).
const PERFORMANCE_WORKERS: usize = 4;

impl Core {
    /// The app's `init`: [`Core::new`] with the workers `options` asks for. Options that do not
    /// parse are the defaults, so a bad value never keeps the core from opening.
    pub fn init(data_dir: &str, options: &str) -> Result<Core, ErrorCode> {
        let options = serde_json::from_str(options).unwrap_or_else(|_| {
            log::warn!("init: options malformed, using the defaults");
            InitOptions::default()
        });
        let workers = if options.performance_mode {
            PERFORMANCE_WORKERS
        } else {
            WORKERS
        };
        Core::with_options(
            data_dir,
            Options {
                workers,
                ..Options::default()
            },
        )
    }
}

#[cfg(test)]
mod tests {
    use super::Core;
    use crate::api::tests_catalog::path;
    use crate::testutil::TempDir;

    /// The worker threads of the core's runtime and of the carriers `init` with `options` makes.
    fn workers(options: &str) -> (usize, Option<usize>) {
        let dir = TempDir::new("init-workers");
        let core = Core::init(path(&dir), options).unwrap();
        let carriers = core.shared.outbounds.carrier_workers();
        (core.runtime.metrics().num_workers(), carriers)
    }

    #[test]
    fn two_workers_per_runtime_by_default() {
        assert_eq!(workers(r#"{"performanceMode":false}"#), (2, Some(2)));
        assert_eq!(workers("{}"), (2, Some(2)));
    }

    #[test]
    fn performance_mode_gives_both_runtimes_four() {
        assert_eq!(workers(r#"{"performanceMode":true}"#), (4, Some(4)));
    }

    #[test]
    fn malformed_options_fall_back_to_two() {
        for options in [
            "",
            "null",
            "[]",
            r#"{"performanceMode":"yes"}"#,
            r#"{"performanceMode":1}"#,
        ] {
            assert_eq!(workers(options), (2, Some(2)), "{options}");
        }
    }
}
