//! The generation's driver: the one task that owns the tun fd. It reads packets until the
//! generation is cancelled, and fails the generation when the tun does.

use super::{Gen, Settings};
use crate::sys;
use std::io::{self, ErrorKind};
use std::os::fd::{AsRawFd, OwnedFd};
use tokio::io::unix::AsyncFd;
use tokio::sync::oneshot;

pub(crate) async fn run(
    tun: AsyncFd<OwnedFd>,
    settings: Settings,
    gen_: Gen,
    ready: oneshot::Sender<()>,
) {
    let _ = ready.send(());
    // Cancellation drops this future, and `tun` with it: the duplicate fd is closed by the task
    // that used it.
    if let Err(e) = pump(&tun, &settings, &gen_).await {
        log::error!(
            "generation {}: tun failed ({:?})",
            gen_.session.generation,
            e.kind()
        );
        gen_.die();
    }
}

async fn pump(tun: &AsyncFd<OwnedFd>, settings: &Settings, gen_: &Gen) -> io::Result<()> {
    let mut buf = vec![0u8; usize::from(settings.mtu) + 64];
    let mut up = 0u64;
    loop {
        let mut guard = tun.readable().await?;
        match guard.try_io(|fd| sys::read(fd.as_raw_fd(), &mut buf)) {
            Ok(Ok(0)) => return Err(ErrorKind::UnexpectedEof.into()),
            Ok(Ok(n)) => {
                up += n as u64;
                gen_.session.publish(up, 0, 0);
            }
            Ok(Err(e)) if e.kind() == ErrorKind::Interrupted => {}
            Ok(Err(e)) => return Err(e),
            Err(_would_block) => {}
        }
    }
}
