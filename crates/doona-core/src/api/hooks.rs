//! Points where a host test runs code on the core's own thread, to order it against a race
//! without sleeping. Compiled into tests only.

use super::{Core, lock};
use std::sync::Mutex;
use std::time::Instant;

type Teardown = Box<dyn FnOnce(&Core) + Send>;
type BeforeWrite = Box<dyn FnOnce(Instant) + Send>;

#[derive(Default)]
pub(super) struct Hooks {
    teardown: Mutex<Option<Teardown>>,
    before_write: Mutex<Option<BeforeWrite>>,
}

impl Hooks {
    /// Runs `f` once in `stop`, after the probe token is replaced and before the lifecycle
    /// changes.
    pub fn on_teardown(&self, f: impl FnOnce(&Core) + Send + 'static) {
        *lock(&self.teardown) = Some(Box::new(f));
    }

    pub fn teardown(&self, core: &Core) {
        let f = lock(&self.teardown).take();
        if let Some(f) = f {
            f(core);
        }
    }

    /// Runs `f` once with a subscription command's deadline, once its state is serialized and
    /// before the first write to the file system.
    pub fn on_before_write(&self, f: impl FnOnce(Instant) + Send + 'static) {
        *lock(&self.before_write) = Some(Box::new(f));
    }

    pub fn before_write(&self, deadline: Instant) {
        let f = lock(&self.before_write).take();
        if let Some(f) = f {
            f(deadline);
        }
    }
}
