//! How a flow task and the driver talk. The driver owns the netstack and never waits on a flow:
//! data crosses in bounded channels of [`Bytes`] chunks, and a flow task that moves a chunk wakes
//! the driver through one unbounded event channel. Each flow has at most one wake queued (its
//! [`Waker`] remembers), so that channel stays as short as the number of flows.

use crate::dns::names::Learned;
use crate::stats::meter::Tally;
use bytes::{Buf, Bytes};
use honk_netstack::FlowId;
use std::io::{self, ErrorKind};
use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering::AcqRel, Ordering::Relaxed};
use std::task::{Context, Poll, ready};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::{OwnedSemaphorePermit, mpsc};
use tokio::time::Instant;
use tokio_util::sync::{CancellationToken, PollSender};

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
    /// A DNS answer to send back from `from` (the address the query went to) to `to`, and what it
    /// maps once the tun takes it. It keeps its query's slot until the driver writes it, so queued
    /// answers stay bounded too.
    DnsAnswer {
        from: SocketAddr,
        to: SocketAddr,
        payload: Vec<u8>,
        learned: Option<Learned>,
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

/// A TCP flow's drain: from the first FIN either way, the flow may go `idle` without a byte moved
/// before it is cut. The task and the driver share it: either begins it, the driver when it sees
/// the client's FIN (while the data before it may still wait in the channel or the stack), both
/// count progress, and once the task has ended the driver keeps its deadline. Times are on
/// tokio's monotonic clock, as nanoseconds after `base`.
pub(crate) struct Drain {
    idle: Duration,
    base: Instant,
    /// When the drain began; `u64::MAX` before.
    began: AtomicU64,
    /// When a byte last moved.
    moved: AtomicU64,
    /// Cancelled once the drain has begun.
    begun: CancellationToken,
}

impl Drain {
    fn new(idle: Duration) -> Drain {
        Drain {
            idle,
            base: Instant::now(),
            began: AtomicU64::new(u64::MAX),
            moved: AtomicU64::new(0),
            begun: CancellationToken::new(),
        }
    }

    fn nanos(&self, at: Instant) -> u64 {
        let since = at.saturating_duration_since(self.base).as_nanos();
        u64::try_from(since).unwrap_or(u64::MAX - 1)
    }

    fn due(&self, began: u64) -> Instant {
        let from = began.max(self.moved.load(Relaxed));
        self.base + Duration::from_nanos(from) + self.idle
    }

    /// Begins the drain at `at`, when a FIN was first seen, unless it began earlier; returns the
    /// deadline.
    pub fn begin(&self, at: Instant) -> Instant {
        let at = self.nanos(at);
        let began = self.began.fetch_min(at, Relaxed).min(at);
        self.begun.cancel();
        self.due(began)
    }

    pub fn has_begun(&self) -> bool {
        self.begun.is_cancelled()
    }

    /// Returns once the drain has begun.
    pub async fn begun(&self) {
        self.begun.cancelled().await;
    }

    /// When the flow is cut unless a byte moves first; `None` before the drain begins.
    pub fn deadline(&self) -> Option<Instant> {
        let began = self.began.load(Relaxed);
        (began != u64::MAX).then(|| self.due(began))
    }

    pub fn expired(&self) -> bool {
        self.deadline().is_some_and(|d| Instant::now() >= d)
    }

    /// Counts a byte moved now. Past the deadline nothing is counted and the flow is to be cut: a
    /// read or write after a stop of the process cannot revive a flow whose time ran out during it.
    pub fn moved(&self) -> io::Result<()> {
        let now = Instant::now();
        if self.deadline().is_some_and(|d| now >= d) {
            return Err(ErrorKind::TimedOut.into());
        }
        self.moved.fetch_max(self.nanos(now), Relaxed);
        Ok(())
    }
}

/// The driver's ends of a TCP flow's channels.
pub(crate) struct Ends {
    /// Client to upstream; dropped to pass on the client's FIN.
    pub up: Option<mpsc::Sender<Bytes>>,
    /// Upstream to client; closed by the task to pass on the upstream's FIN.
    pub down: mpsc::Receiver<Bytes>,
    pub drain: Arc<Drain>,
}

/// The task's end of a TCP flow: the client, as an async stream.
pub(crate) struct FlowIo {
    up: mpsc::Receiver<Bytes>,
    unread: Bytes,
    down: PollSender<Bytes>,
    waker: Arc<Waker>,
    /// The payload the task took and gave, for its outbound's bytes of the day.
    tally: Tally,
    drain: Arc<Drain>,
}

/// A TCP flow's channels: the payload counts in `tally`, and the drain cuts the flow after `idle`
/// without progress.
pub(crate) fn pair(waker: Arc<Waker>, tally: Tally, idle: Duration) -> (Ends, FlowIo) {
    let (up_tx, up_rx) = mpsc::channel(DEPTH);
    let (down_tx, down_rx) = mpsc::channel(DEPTH);
    let drain = Arc::new(Drain::new(idle));
    let ends = Ends {
        up: Some(up_tx),
        down: down_rx,
        drain: Arc::clone(&drain),
    };
    let io = FlowIo {
        up: up_rx,
        unread: Bytes::new(),
        down: PollSender::new(down_tx),
        waker,
        tally,
        drain,
    };
    (ends, io)
}

impl FlowIo {
    pub fn drain(&self) -> Arc<Drain> {
        Arc::clone(&self.drain)
    }
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
