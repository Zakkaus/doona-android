//! The generation's tun descriptor, which `stop` can close whatever the driver is doing. The
//! driver reaches it one non-blocking operation at a time under a lock that `revoke` also takes
//! and keeps until the descriptor is closed, so `revoke` waits for at most one syscall or one
//! other revoke; afterwards every operation fails, and no task of the generation can reach the
//! descriptor's number, which the kernel may hand out again.

use crate::sys;
use std::io::{self, ErrorKind};
use std::os::fd::{AsRawFd, OwnedFd, RawFd};
use std::sync::{Arc, Mutex, MutexGuard};
use std::task::Poll;
use tokio::io::Interest;
use tokio::io::unix::AsyncFd;
use tokio::runtime::Handle;

pub(crate) struct Tun {
    fd: Mutex<Option<AsyncFd<OwnedFd>>>,
}

impl Tun {
    /// Makes `fd` non-blocking and registers it with `rt`'s reactor; on failure `fd` is closed.
    pub fn new(fd: OwnedFd, rt: &Handle) -> io::Result<Arc<Tun>> {
        sys::set_nonblocking(fd.as_raw_fd())?;
        let _rt = rt.enter();
        // SAFETY: an OwnedFd is open, and it returns the same descriptor until it is dropped,
        // which only the AsyncFd can do now.
        let fd = unsafe { AsyncFd::register(fd) }?;
        Ok(Arc::new(Tun {
            fd: Mutex::new(Some(fd)),
        }))
    }

    pub fn read(&self, buf: &mut [u8]) -> io::Result<usize> {
        self.op(Interest::READABLE, |fd| sys::read(fd, buf))
    }

    pub fn write(&self, buf: &[u8]) -> io::Result<usize> {
        self.op(Interest::WRITABLE, |fd| sys::write(fd, buf))
    }

    /// One operation, attempted only while the reactor reports the fd ready for `interest`;
    /// otherwise `WouldBlock`, as when the operation itself would block.
    fn op<R>(&self, interest: Interest, f: impl FnOnce(RawFd) -> io::Result<R>) -> io::Result<R> {
        let fd = self.lock();
        let fd = fd.as_ref().ok_or_else(revoked)?;
        fd.try_io(interest, |owned| f(owned.as_raw_fd()))
    }

    /// Waits until an operation for `interest` may succeed. The lock is held only while the
    /// reactor is polled, never across the wait.
    pub async fn ready(&self, interest: Interest) -> io::Result<()> {
        std::future::poll_fn(|cx| {
            let fd = self.lock();
            let Some(fd) = fd.as_ref() else {
                return Poll::Ready(Err(revoked()));
            };
            // The guard is dropped without clearing readiness: the next operation clears it
            // when it would block.
            if interest.is_readable() {
                fd.poll_read_ready(cx).map_ok(drop)
            } else {
                fd.poll_write_ready(cx).map_ok(drop)
            }
        })
        .await
    }

    /// Deregisters and closes the descriptor. The lock is held until the close, so a second
    /// revoker, such as `stop` racing a failed start's cleanup, returns only once the
    /// descriptor is closed rather than as soon as it finds it taken.
    pub fn revoke(&self) {
        self.revoke_pausing(|| {});
    }

    /// [`Tun::revoke`], running `pause` once the descriptor is taken and before it is closed,
    /// where a unit test stands in for a slow deregistration.
    fn revoke_pausing(&self, pause: impl FnOnce()) {
        let mut fd = self.lock();
        let taken = fd.take();
        pause();
        drop(taken);
        drop(fd);
    }

    /// A poisoned lock still holds a valid descriptor or none.
    fn lock(&self) -> MutexGuard<'_, Option<AsyncFd<OwnedFd>>> {
        self.fd.lock().unwrap_or_else(|e| e.into_inner())
    }
}

fn revoked() -> io::Error {
    ErrorKind::NotConnected.into()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::{closed, tun};
    use std::time::Duration;

    #[test]
    fn a_second_revoker_returns_only_once_the_fd_is_closed() {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .enable_all()
            .build()
            .unwrap();
        let (dup, probe) = tun();
        let tun = Tun::new(dup, rt.handle()).unwrap();
        let (taken_tx, taken) = std::sync::mpsc::channel();
        let first = {
            let tun = Arc::clone(&tun);
            std::thread::spawn(move || {
                tun.revoke_pausing(|| {
                    taken_tx.send(()).unwrap();
                    std::thread::sleep(Duration::from_millis(300));
                });
            })
        };
        taken.recv().unwrap();
        tun.revoke();
        assert!(
            closed(&probe),
            "the second revoke returned with the fd open"
        );
        first.join().unwrap();
    }
}
