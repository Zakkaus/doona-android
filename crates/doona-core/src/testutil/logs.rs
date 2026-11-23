//! The unit tests' logger: it counts the records each thread logs and keeps none of them. While a
//! test watches for a text, it also counts the records of any thread whose message holds it.

use std::cell::Cell;
use std::sync::{Mutex, Once};

thread_local! {
    static LOGGED: Cell<usize> = const { Cell::new(0) };
}

/// The texts watched for, each with the records that held it so far.
static WATCHED: Mutex<Vec<(String, usize)>> = Mutex::new(Vec::new());

struct Counting;

impl log::Log for Counting {
    fn enabled(&self, _: &log::Metadata<'_>) -> bool {
        true
    }

    fn log(&self, record: &log::Record<'_>) {
        let _ = LOGGED.try_with(|n| n.set(n.get() + 1));
        let mut watched = WATCHED.lock().unwrap_or_else(|e| e.into_inner());
        if !watched.is_empty() {
            // The logging thread's test may count its allocations: formatting is the logger's.
            super::alloc::uncounted(|| {
                let message = record.args().to_string();
                for (text, held) in watched.iter_mut() {
                    *held += usize::from(message.contains(text.as_str()));
                }
            });
        }
    }

    fn flush(&self) {}
}

fn install() {
    static INSTALL: Once = Once::new();
    INSTALL.call_once(|| {
        if log::set_logger(&Counting).is_ok() {
            log::set_max_level(log::LevelFilter::Trace);
        }
    });
}

/// What `f` returns, and how many records this thread logged while it ran.
pub fn logged<T>(f: impl FnOnce() -> T) -> (T, usize) {
    install();
    let before = LOGGED.with(Cell::get);
    let value = f();
    (value, LOGGED.with(Cell::get) - before)
}

/// What `f` returns, and how many records any thread logged with `text` in its message while it
/// ran. `text` should be unique to the test, as other tests log meanwhile.
pub fn mentioning<T>(text: &str, f: impl FnOnce() -> T) -> (T, usize) {
    install();
    let lock = || WATCHED.lock().unwrap_or_else(|e| e.into_inner());
    lock().push((text.to_owned(), 0));
    let value = f();
    let mut watched = lock();
    let at = watched.iter().position(|(t, _)| t == text);
    let held = at.map_or(0, |at| watched.remove(at).1);
    (value, held)
}
