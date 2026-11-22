//! The unit tests' allocator: the system's, counting what each thread allocates, so a test can
//! show that a refusal comes before anything is built, and what a thread holds at most, so a
//! bench can show a build's peak. libyaml allocates through it too.

use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;

thread_local! {
    static ALLOCATED: Cell<usize> = const { Cell::new(0) };
    /// What this thread allocated less what it freed: a block freed by another thread comes off
    /// that thread's count, so this is exact only for work kept on one thread.
    static LIVE: Cell<usize> = const { Cell::new(0) };
    static PEAK: Cell<usize> = const { Cell::new(0) };
}

struct Counting;

#[global_allocator]
static COUNTING: Counting = Counting;

fn count(bytes: usize) {
    // Not counted once the thread's locals are gone.
    let _ = ALLOCATED.try_with(|a| a.set(a.get().saturating_add(bytes)));
    let _ = LIVE.try_with(|live| {
        let now = live.get().saturating_add(bytes);
        live.set(now);
        let _ = PEAK.try_with(|peak| peak.set(peak.get().max(now)));
    });
}

fn uncount(bytes: usize) {
    let _ = LIVE.try_with(|live| live.set(live.get().saturating_sub(bytes)));
}

// SAFETY: every call goes to the system allocator unchanged, and counting allocates nothing.
unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        count(layout.size());
        // SAFETY: the caller's contract, passed on.
        unsafe { System.alloc(layout) }
    }

    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        count(layout.size());
        // SAFETY: as for `alloc`.
        unsafe { System.alloc_zeroed(layout) }
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        count(new_size.saturating_sub(layout.size()));
        uncount(layout.size().saturating_sub(new_size));
        // SAFETY: as for `alloc`.
        unsafe { System.realloc(ptr, layout, new_size) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        uncount(layout.size());
        // SAFETY: as for `alloc`.
        unsafe { System.dealloc(ptr, layout) }
    }
}

/// What `f` returns, and the bytes this thread allocated while it ran, freed or not.
pub fn allocated<T>(f: impl FnOnce() -> T) -> (T, usize) {
    let before = ALLOCATED.with(Cell::get);
    let value = f();
    (value, ALLOCATED.with(Cell::get) - before)
}

/// What `f` returns, the most bytes this thread held at once while it ran beyond what it held
/// before, and what it still holds of them after.
pub fn peak<T>(f: impl FnOnce() -> T) -> (T, usize, usize) {
    let before = LIVE.with(Cell::get);
    PEAK.with(|p| p.set(before));
    let value = f();
    let peak = PEAK.with(Cell::get) - before;
    (value, peak, LIVE.with(Cell::get).saturating_sub(before))
}
