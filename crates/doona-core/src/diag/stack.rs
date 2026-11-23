//! A crash file's backtrace (`crash.rs`): the panicking thread's return addresses, each as an
//! offset in the object that holds it. The device never symbolizes: llvm-symbolizer or addr2line
//! turn an offset in `libdoonacore.so` into a function against the unstripped library of the
//! same build.
//!
//! The stack is taken before the crash files are: the unwinder (through `dl_iterate_phdr`) and
//! dladdr take the loader's lock, which a thread that panics inside a library's constructor
//! holds, and that thread then waits for the crash files. Taking the stack first means no thread
//! ever waits for the loader while it holds the crash files.
//!
//! ARCH D21: an object's file name may come from anywhere, as a host program's from the path it
//! was started by. A frame names this library and the system objects that JNI calls pass through
//! ([`OBJECTS`]); any other object is `?`.

use std::ffi::{CStr, c_int, c_void};
use std::fmt;

/// The frames a file lists, innermost first.
const MAX_FRAMES: usize = 64;
/// What a frame in this library is called, whatever the file holding it is called (a unit test's
/// binary on a host).
const OURS: &str = "libdoonacore.so";
/// The other objects a frame may name: bionic's and glibc's C library, the runtime and the app's
/// process image, which JNI calls pass through, and the C++ library the app bundles.
const OBJECTS: [&str; 5] = [
    "libc.so",
    "libc.so.6",
    "libart.so",
    "app_process64",
    "libc++_shared.so",
];

/// The calling thread's frames, innermost first: the name of the object holding each return
/// address and the address's offset in it; `?` and the address when no object holds it.
pub(super) struct Stack {
    frames: [(&'static str, usize); MAX_FRAMES],
    n: usize,
}

impl Stack {
    /// Walks the unwind tables and asks the loader which object holds each address. Nothing is
    /// symbolized and nothing is allocated.
    pub fn capture() -> Stack {
        static ANCHOR: u8 = 0;
        let mut addresses = [0_usize; MAX_FRAMES];
        let n = walk(&mut addresses);
        let ours = loaded_at(std::ptr::from_ref(&ANCHOR) as usize).map(|(_, base)| base);
        let mut frames = [("?", 0); MAX_FRAMES];
        for (frame, &address) in frames.iter_mut().zip(&addresses[..n]) {
            *frame = match loaded_at(address) {
                Some((_, base)) if Some(base) == ours => (OURS, address.wrapping_sub(base)),
                Some((name, base)) => (name.unwrap_or("?"), address.wrapping_sub(base)),
                None => ("?", address),
            };
        }
        Stack { frames, n }
    }
}

/// One frame a line: `3 libdoonacore.so 0x1a2b3c`.
impl fmt::Display for Stack {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for (i, (name, at)) in self.frames[..self.n].iter().enumerate() {
            writeln!(f, "{i} {name} {at:#x}")?;
        }
        Ok(())
    }
}

#[repr(C)]
struct UnwindContext {
    _opaque: [u8; 0],
}

type Step = extern "C" fn(*mut UnwindContext, *mut c_void) -> c_int;

// The unwinder the standard library links: libgcc_s on Linux, LLVM's libunwind on Android.
unsafe extern "C" {
    fn _Unwind_Backtrace(step: Step, arg: *mut c_void) -> c_int;
    fn _Unwind_GetIP(context: *mut UnwindContext) -> usize;
}

/// Fills `stack` with the calling thread's return addresses, innermost first; answers how many.
fn walk(stack: &mut [usize; MAX_FRAMES]) -> usize {
    struct Walk<'a> {
        stack: &'a mut [usize; MAX_FRAMES],
        n: usize,
    }
    extern "C" fn step(context: *mut UnwindContext, arg: *mut c_void) -> c_int {
        const NO_REASON: c_int = 0;
        const END_OF_STACK: c_int = 5;
        // SAFETY: `arg` is the `Walk` that `walk` lends `_Unwind_Backtrace` for this call.
        let walk = unsafe { &mut *arg.cast::<Walk<'_>>() };
        // SAFETY: `context` is the one the unwinder passes to this callback.
        let address = unsafe { _Unwind_GetIP(context) };
        match walk.stack.get_mut(walk.n) {
            Some(slot) if address != 0 => {
                *slot = address;
                walk.n += 1;
                NO_REASON
            }
            _ => END_OF_STACK,
        }
    }
    #[cfg(test)]
    super::crash::note_stack_work();
    let mut walk = Walk { stack, n: 0 };
    // SAFETY: `step` matches the callback type, and `walk` outlives the call that uses it.
    unsafe { _Unwind_Backtrace(step, std::ptr::from_mut(&mut walk).cast()) };
    walk.n
}

/// The object holding `address`: its name when [`OBJECTS`] lists it, and where it is loaded.
fn loaded_at(address: usize) -> Option<(Option<&'static str>, usize)> {
    #[cfg(test)]
    super::crash::note_stack_work();
    let mut info = std::mem::MaybeUninit::<libc::Dl_info>::zeroed();
    // SAFETY: dladdr only reads the address, and `info` has room for what it writes.
    let found = unsafe { libc::dladdr(address as *const c_void, info.as_mut_ptr()) };
    if found == 0 {
        return None;
    }
    // SAFETY: all-zero bytes are a valid `Dl_info`, which dladdr filled when it returned nonzero.
    let info = unsafe { info.assume_init() };
    let name = (!info.dli_fname.is_null()).then(|| {
        // SAFETY: dladdr's file name is a C string the loader keeps while the object stays
        // loaded, which it does while its code is on this thread's stack; it is read here only.
        let path = unsafe { CStr::from_ptr(info.dli_fname) }.to_bytes();
        let file = path.rsplit(|&b| b == b'/').next().unwrap_or_default();
        OBJECTS.into_iter().find(|o| o.as_bytes() == file)
    });
    Some((name.flatten(), info.dli_fbase as usize))
}
