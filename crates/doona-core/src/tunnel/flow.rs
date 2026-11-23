//! How a flow task and the driver talk. The driver owns the netstack and never waits on a flow:
//! data crosses in bounded channels of [`Bytes`] chunks, and a flow task that moves a chunk wakes
//! the driver through one unbounded event channel. Each flow has at most one wake queued (its
//! [`Waker`] remembers), so that channel stays as short as the number of flows.

use super::drain::Drain;
use crate::dns::ToClient;
use crate::dns::names::{Learned, Mapping};
use crate::stats::meter::Tally;
use bytes::{Buf, Bytes};
use honk_netstack::FlowId;
use std::io::{self, ErrorKind};
use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering::AcqRel};
use std::task::{Context, Poll, ready};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::mpsc::error::TryRecvError;
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

/// The driver's ends of a TCP flow's channels.
pub(crate) struct Ends {
    /// Client to upstream; dropped to pass on the client's FIN.
    pub up: Option<mpsc::Sender<Bytes>>,
    /// Upstream to client; closed by the task to pass on the upstream's FIN.
    pub down: Down,
    pub drain: Arc<Drain>,
}

/// A chunk for the client; the last chunk of a DNS answer carries what the answer maps.
struct Chunk(Bytes, Option<Mapping>);

impl Chunk {
    /// Its bytes, once what it maps is put: the driver takes a chunk before any of it can leave.
    fn take(self) -> Bytes {
        if let Some(mapping) = self.1 {
            mapping.put();
        }
        self.0
    }
}

/// The driver's end of the channel to the client.
pub(crate) struct Down(mpsc::Receiver<Chunk>);

impl Down {
    pub fn try_recv(&mut self) -> Result<Bytes, TryRecvError> {
        self.0.try_recv().map(Chunk::take)
    }

    #[cfg(test)]
    pub async fn recv(&mut self) -> Option<Bytes> {
        self.0.recv().await.map(Chunk::take)
    }
}

/// The task's end of a TCP flow: the client, as an async stream.
pub(crate) struct FlowIo {
    up: mpsc::Receiver<Bytes>,
    unread: Bytes,
    down: PollSender<Chunk>,
    /// What the last chunk of the next write maps, when it ends a DNS answer.
    mapping: Option<Mapping>,
    waker: Arc<Waker>,
    /// The payload the task took and gave, for its outbound's bytes of the day.
    tally: Tally,
    drain: Arc<Drain>,
}

/// A TCP flow's channels: the payload counts in `tally`, and after a FIN the driver cuts the flow
/// once it has gone `idle` without progress (see `drain.rs`).
pub(crate) fn pair(waker: Arc<Waker>, tally: Tally, idle: Duration) -> (Ends, FlowIo) {
    let (up_tx, up_rx) = mpsc::channel(DEPTH);
    let (down_tx, down_rx) = mpsc::channel(DEPTH);
    let drain = Arc::new(Drain::new(idle, Arc::clone(&waker)));
    let ends = Ends {
        up: Some(up_tx),
        down: Down(down_rx),
        drain: Arc::clone(&drain),
    };
    let io = FlowIo {
        up: up_rx,
        unread: Bytes::new(),
        down: PollSender::new(down_tx),
        mapping: None,
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

impl ToClient for FlowIo {
    fn map_with_last_chunk(&mut self, mapping: Mapping) {
        self.mapping = Some(mapping);
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
        let mapping = if n == data.len() {
            this.mapping.take()
        } else {
            None
        };
        let chunk = Chunk(Bytes::copy_from_slice(&data[..n]), mapping);
        this.down.send_item(chunk).map_err(gone)?;
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

#[cfg(test)]
mod tests;
