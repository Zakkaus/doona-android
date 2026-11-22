//! Points where a host test runs code on the core's own thread, to order it against a race
//! without sleeping. Compiled into tests only.

use super::{Core, lock};
use std::sync::Mutex;

type Teardown = Box<dyn FnOnce(&Core) + Send>;

#[derive(Default)]
pub(super) struct Hooks {
    teardown: Mutex<Option<Teardown>>,
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
}
