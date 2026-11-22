//! How a flow task and the driver talk. The driver owns the netstack and never waits on a flow:
//! data crosses in bounded channels of [`Bytes`] chunks, and a flow task that moves a chunk wakes
//! the driver through one unbounded event channel. Each flow has at most one wake queued (its
//! [`Waker`] remembers), so that channel stays as short as the number of flows.

use crate::stats::meter::Tally;
use bytes::{Buf, Bytes};
use honk_netstack::FlowId;
use std::io::{self, ErrorKind};
use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering::AcqRel};
use std::task::{Context, Poll, ready};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::{OwnedSemaphorePermit, mpsc};
use tokio_util::sync::PollSender;

/// The largest chunk either side puts in a channel.
pub(crate) const CHUNK: usize = 32 * 1024;
/// Chunks in flight per direction and flow: with [`CHUNK`], 128 KiB.
pub(crate) const DEPTH: usize = 4;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) enum Key {
    Tcp(FlowId),
    Udp(u64),
}

pub(crate) enum Event {
    /// A flow moved a chunk or closed a channel.
    Wake(Key),
    /// A TCP flow's upstream is connected: answer the client's SYN.
    Connected(FlowId),
    /// A TCP flow's task ended; `failed` resets the client side.
    Finished { id: FlowId, failed: bool },
    /// A UDP association ended (idle, or its socket failed).
    UdpEnded(u64),
    /// A DNS answer to send back from `from` (the address the query went to) to `to`. It keeps
    /// its query's slot until the driver writes it, so queued answers stay bounded too.
    DnsAnswer {
        from: SocketAddr,
        to: SocketAddr,
        payload: Vec<u8>,
        _slot: OwnedSemaphorePermit,
    },
}

pub(crate) type Events = mpsc::UnboundedSender<Event>;

pub(crate) struct Waker {
    key: Key,
    queued: AtomicBool,
    events: Events,
}

impl Waker {
    pub fn new(key: Key, events: Events) -> Arc<Waker> {
        Arc::new(Waker {
            key,
            queued: AtomicBool::new(false),
            events,
        })
    }

    /// Queues a wake unless one is queued already.
    pub fn wake(&self) {
        if !self.queued.swap(true, AcqRel) {
            let _ = self.events.send(Event::Wake(self.key));
        }
    }

    /// The driver calls this before it looks at the flow's channels. A swap, not a store: it
    /// acquires the flow's last wake, so the chunk moved before it is visible.
    pub fn rearm(&self) {
        self.queued.swap(false, AcqRel);
    }
}

/// The driver's ends of a TCP flow's channels.
pub(crate) struct Ends {
    /// Client to upstream; dropped to pass on the client's FIN.
    pub up: Option<mpsc::Sender<Bytes>>,
    /// Upstream to client; closed by the task to pass on the upstream's FIN.
    pub down: mpsc::Receiver<Bytes>,
}

/// The task's end of a TCP flow: the client, as an async stream.
pub(crate) struct FlowIo {
    up: mpsc::Receiver<Bytes>,
    unread: Bytes,
    down: PollSender<Bytes>,
    waker: Arc<Waker>,
    /// The payload the task took and gave, for its outbound's bytes of the day.
    tally: Tally,
}

/// The driver's ends and the task's [`FlowIo`] of a new flow, whose payload counts in `tally`.
pub(crate) fn pair(waker: Arc<Waker>, tally: Tally) -> (Ends, FlowIo) {
    let (up_tx, up_rx) = mpsc::channel(DEPTH);
    let (down_tx, down_rx) = mpsc::channel(DEPTH);
    let ends = Ends {
        up: Some(up_tx),
        down: down_rx,
    };
    let io = FlowIo {
        up: up_rx,
        unread: Bytes::new(),
        down: PollSender::new(down_tx),
        waker,
        tally,
    };
    (ends, io)
}

impl AsyncRead for FlowIo {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if this.unread.is_empty() {
            match ready!(this.up.poll_recv(cx)) {
                Some(chunk) => {
                    this.unread = chunk;
                    // Room in the channel: the driver may have a chunk waiting for it.
                    this.waker.wake();
                }
                None => return Poll::Ready(Ok(())),
            }
        }
        let n = buf.remaining().min(this.unread.len());
        buf.put_slice(&this.unread[..n]);
        this.unread.advance(n);
        this.tally.up(n);
        Poll::Ready(Ok(()))
    }
}

impl AsyncWrite for FlowIo {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        data: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        let gone = |_| io::Error::from(ErrorKind::BrokenPipe);
        ready!(this.down.poll_reserve(cx)).map_err(gone)?;
        let n = data.len().min(CHUNK);
        this.down
            .send_item(Bytes::copy_from_slice(&data[..n]))
            .map_err(gone)?;
        this.waker.wake();
        this.tally.down(n);
        Poll::Ready(Ok(n))
    }

    fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    /// Passes the upstream's FIN on: the driver sees the channel close after its last chunk.
    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        this.down.close();
        this.waker.wake();
        Poll::Ready(Ok(()))
    }
}
