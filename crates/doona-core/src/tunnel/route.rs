//! Where a new flow goes. Direct sends it straight out; Global sends it through the `Proxy`
//! group, which has no node until subscriptions arrive, so a Global flow is refused, never sent
//! direct. doona excludes its own package from the VPN, so these sockets need no `protect()`.

use crate::dto::Mode;
use std::io::{self, ErrorKind};
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::atomic::{AtomicU8, Ordering::Relaxed};
use std::time::Duration;
use tokio::net::{TcpStream, UdpSocket};

/// A TCP dial gives up after this long.
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

/// The committed mode, read by every new flow.
#[derive(Debug)]
pub struct ModeCell(AtomicU8);

impl ModeCell {
    pub fn new(mode: Mode) -> ModeCell {
        let cell = ModeCell(AtomicU8::new(0));
        cell.set(mode);
        cell
    }

    pub fn get(&self) -> Mode {
        match self.0.load(Relaxed) {
            1 => Mode::Direct,
            _ => Mode::Global,
        }
    }

    pub fn set(&self, mode: Mode) {
        self.0.store(
            match mode {
                Mode::Global => 0,
                Mode::Direct => 1,
            },
            Relaxed,
        );
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Route {
    Direct,
    Refuse,
}

impl Route {
    pub fn for_mode(mode: Mode) -> Route {
        match mode {
            Mode::Direct => Route::Direct,
            Mode::Global => Route::Refuse,
        }
    }

    pub async fn connect(self, dst: SocketAddr) -> io::Result<TcpStream> {
        match self {
            Route::Direct => {
                let s = tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(dst))
                    .await
                    .map_err(|_| io::Error::from(ErrorKind::TimedOut))??;
                // The app's own segments arrive already sized; Nagle would only add delay.
                s.set_nodelay(true)?;
                Ok(s)
            }
            Route::Refuse => Err(ErrorKind::PermissionDenied.into()),
        }
    }

    /// A UDP socket that exchanges datagrams with `dst` only.
    pub async fn bind_udp(self, dst: SocketAddr) -> io::Result<UdpSocket> {
        match self {
            Route::Direct => {
                let any: SocketAddr = match dst {
                    SocketAddr::V4(_) => (Ipv4Addr::UNSPECIFIED, 0).into(),
                    SocketAddr::V6(_) => (Ipv6Addr::UNSPECIFIED, 0).into(),
                };
                let s = UdpSocket::bind(any).await?;
                s.connect(dst).await?;
                Ok(s)
            }
            Route::Refuse => Err(ErrorKind::PermissionDenied.into()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mode_cell_round_trips() {
        let cell = ModeCell::new(Mode::Direct);
        assert_eq!(cell.get(), Mode::Direct);
        cell.set(Mode::Global);
        assert_eq!(cell.get(), Mode::Global);
    }

    #[test]
    fn global_without_nodes_never_goes_direct() {
        assert_eq!(Route::for_mode(Mode::Global), Route::Refuse);
        assert_eq!(Route::for_mode(Mode::Direct), Route::Direct);
    }
}
