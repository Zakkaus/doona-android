//! The unit tests' logger: it counts the records each thread logs and keeps none of them.

use std::cell::Cell;
use std::sync::Once;

thread_local! {
    static LOGGED: Cell<usize> = const { Cell::new(0) };
}

struct Counting;

impl log::Log for Counting {
    fn enabled(&self, _: &log::Metadata<'_>) -> bool {
        true
    }

    fn log(&self, _: &log::Record<'_>) {
        let _ = LOGGED.try_with(|n| n.set(n.get() + 1));
    }

    fn flush(&self) {}
}

/// What `f` returns, and how many records this thread logged while it ran.
pub fn logged<T>(f: impl FnOnce() -> T) -> (T, usize) {
    static INSTALL: Once = Once::new();
    INSTALL.call_once(|| {
        if log::set_logger(&Counting).is_ok() {
            log::set_max_level(log::LevelFilter::Trace);
        }
    });
    let before = LOGGED.with(Cell::get);
    let value = f();
    (value, LOGGED.with(Cell::get) - before)
}
