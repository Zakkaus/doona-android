//! The panics on a core's runtime threads and its carriers' (see `outbounds.rs`), wherever they
//! are caught. A generation's own tasks report theirs through `Gen::spawn`; the tasks honk spawns
//! for its carriers (a QUIC connection's driver, a session's reader) never pass through it, so a
//! generation learns of their panics from the count kept here (see `tunnel::watch`).

use std::cell::OnceCell;
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use std::sync::{Arc, Once};

thread_local! {
    static COUNT: OnceCell<Arc<AtomicU64>> = const { OnceCell::new() };
}

/// Counts the calling thread's panics in `count` from now on; for a runtime's `on_thread_start`.
pub(crate) fn count_on_this_thread(count: &Arc<AtomicU64>) {
    COUNT.with(|c| {
        let _ = c.set(Arc::clone(count));
    });
}

/// Installs, once per process, a hook that counts each panic of a counting thread, writes its
/// crash file (`diag/crash.rs`), then runs the hook installed before it (in the app, the one that
/// logs the location only).
pub(crate) fn install() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        let previous = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            // A thread being torn down may have lost its slot; its panic is not counted.
            let _ = COUNT.try_with(|c| c.get().map(|count| count.fetch_add(1, Relaxed)));
            crate::diag::crash::on_panic(info);
            previous(info);
        }));
    });
}
