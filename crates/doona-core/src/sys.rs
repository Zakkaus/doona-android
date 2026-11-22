//! File-descriptor plumbing for the tun.

use std::io;
use std::os::fd::{FromRawFd, OwnedFd, RawFd};

fn cvt(r: libc::c_int) -> io::Result<libc::c_int> {
    if r < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(r)
    }
}

/// Duplicates a borrowed descriptor with close-on-exec; the caller keeps and closes its own.
pub fn dup_cloexec(fd: RawFd) -> io::Result<OwnedFd> {
    // SAFETY: F_DUPFD_CLOEXEC only reads `fd`; an invalid one fails with EBADF.
    let new = cvt(unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 0) })?;
    // SAFETY: `new` is a fresh descriptor that nothing else owns.
    Ok(unsafe { OwnedFd::from_raw_fd(new) })
}

/// Sets O_NONBLOCK. The flag lives on the open file description, which a duplicate shares.
pub fn set_nonblocking(fd: RawFd) -> io::Result<()> {
    // SAFETY: plain fcntl calls on a descriptor the caller owns.
    let flags = cvt(unsafe { libc::fcntl(fd, libc::F_GETFL) })?;
    cvt(unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) })?;
    Ok(())
}

pub fn read(fd: RawFd, buf: &mut [u8]) -> io::Result<usize> {
    // SAFETY: `buf` is valid for `buf.len()` writable bytes.
    let n = unsafe { libc::read(fd, buf.as_mut_ptr().cast(), buf.len()) };
    if n < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(n as usize)
    }
}

pub fn write(fd: RawFd, buf: &[u8]) -> io::Result<usize> {
    // SAFETY: `buf` is valid for `buf.len()` readable bytes.
    let n = unsafe { libc::write(fd, buf.as_ptr().cast(), buf.len()) };
    if n < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(n as usize)
    }
}

/// The index of the network interface `name`, as a scope id; none when there is no such one.
pub fn interface_index(name: &str) -> Option<u32> {
    let name = std::ffi::CString::new(name).ok()?;
    // SAFETY: `name` is a valid NUL-terminated string for the call.
    let index = unsafe { libc::if_nametoindex(name.as_ptr()) };
    (index != 0).then_some(index)
}

/// Milliseconds on `CLOCK_BOOTTIME`: it never goes back, never jumps with the wall clock, and
/// counts the time the phone slept. Zero if the clock cannot be read.
pub fn boot_ms() -> u64 {
    let mut ts = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    // SAFETY: `ts` is a valid timespec to write; Linux and Android have CLOCK_BOOTTIME.
    if unsafe { libc::clock_gettime(libc::CLOCK_BOOTTIME, &mut ts) } != 0 {
        return 0;
    }
    let (s, ns) = (u64::try_from(ts.tv_sec), u64::try_from(ts.tv_nsec));
    s.unwrap_or(0).saturating_mul(1000) + ns.unwrap_or(0) / 1_000_000
}

/// The device's time zone offset from UTC at `epoch_s`, in seconds, summer time included; zero
/// when the zone cannot be read. Bionic reads the zone (`persist.sys.timezone`) again on every
/// `localtime_r`, so a change of zone applies from the next call.
pub fn utc_offset(epoch_s: i64) -> i32 {
    let t = epoch_s as libc::time_t;
    // SAFETY: an all-zero `tm` is a valid value of the plain C struct.
    let mut tm: libc::tm = unsafe { std::mem::zeroed() };
    // SAFETY: both pointers are valid for the call; localtime_r keeps no reference to them.
    if unsafe { libc::localtime_r(&t, &mut tm) }.is_null() {
        return 0;
    }
    i32::try_from(tm.tm_gmtoff).unwrap_or(0)
}

/// 32 random bytes for the netstack's secret.
pub fn random_secret() -> io::Result<[u8; 32]> {
    use std::io::Read;
    let mut secret = [0u8; 32];
    std::fs::File::open("/dev/urandom")?.read_exact(&mut secret)?;
    Ok(secret)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::fd::AsRawFd;

    #[test]
    fn dup_is_independent_and_cloexec() {
        let (a, _b) = std::os::unix::net::UnixDatagram::pair().unwrap();
        let dup = dup_cloexec(a.as_raw_fd()).unwrap();
        assert_ne!(dup.as_raw_fd(), a.as_raw_fd());
        // SAFETY: F_GETFD on a descriptor this test owns.
        let fd_flags = unsafe { libc::fcntl(dup.as_raw_fd(), libc::F_GETFD) };
        assert_eq!(fd_flags & libc::FD_CLOEXEC, libc::FD_CLOEXEC);
        drop(a);
        // The duplicate still works after the original is closed.
        set_nonblocking(dup.as_raw_fd()).unwrap();
    }

    #[test]
    fn dup_of_a_bad_fd_fails() {
        assert_eq!(
            dup_cloexec(-1).unwrap_err().raw_os_error(),
            Some(libc::EBADF)
        );
    }
}
