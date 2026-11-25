//! `init`'s options (`init-options.json`), which last for the process: neither runtime is rebuilt
//! later, so a changed option takes effect when the app process starts next.

use super::{Core, Options};
use crate::dto::{ErrorCode, InitOptions};

/// The worker threads of each runtime; flows are I/O bound and the phone has few big cores.
pub(super) const WORKERS: usize = 2;

/// Performance mode's: one per CPU core the process may run on, never fewer than [`WORKERS`]. More
/// workers help only several flows at once: four SS2022 flows uploaded 26 Gbps with 4 instead of 20
/// with 2 on a desktop, one flow no faster (plans/v1 tput-results).
fn performance_workers() -> usize {
    std::thread::available_parallelism()
        .map_or(WORKERS, std::num::NonZeroUsize::get)
        .max(WORKERS)
}

impl Core {
    /// The app's `init`: [`Core::new`] with the workers `options` asks for. Options that do not
    /// parse are the defaults, so a bad value never keeps the core from opening.
    pub fn init(data_dir: &str, options: &str) -> Result<Core, ErrorCode> {
        let options = serde_json::from_str(options).unwrap_or_else(|_| {
            log::warn!("init: options malformed, using the defaults");
            InitOptions::default()
        });
        let workers = if options.performance_mode {
            performance_workers()
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
    use super::{Core, WORKERS, performance_workers};
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
    fn performance_mode_gives_both_runtimes_one_worker_per_core() {
        let cores = std::thread::available_parallelism().map_or(1, |n| n.get());
        let expected = cores.max(WORKERS);
        assert_eq!(performance_workers(), expected);
        assert_eq!(
            workers(r#"{"performanceMode":true}"#),
            (expected, Some(expected))
        );
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
