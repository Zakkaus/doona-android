//! TCP flows: the driver's side of each accepted connection, and the task that serves it, either
//! by dialing the original destination through the flow's route or by answering DNS itself.

use super::flow::{CHUNK, Ends, Event, Events, FlowIo, Waker};
use super::route::Route;
use crate::dns;
use bytes::{Buf, Bytes};
use honk_netstack::{FlowId, Stack, StreamError};
use std::future::poll_fn;
use std::io;
use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use std::task::{Poll, ready};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt, ReadBuf};
use tokio::sync::Notify;
use tokio::sync::mpsc::error::{TryRecvError, TrySendError};
use tokio::time::{Instant, sleep_until};
use tokio_util::sync::CancellationToken;

/// Once either side's FIN is read, the other direction may go this long without a byte read or
/// written in either direction before both sides close. honk's `DRAIN_DEADLINE` (honk 7cd8b13c).
/// A TCP/53 connection to the core's DNS may idle as long (see `dns::serve_tcp`).
const DRAIN_DEADLINE: Duration = Duration::from_secs(30);
/// How often an idle drain looks at the counters.
const DRAIN_CHECK: Duration = Duration::from_millis(100);

/// The driver's state for one accepted connection.
pub(crate) struct TcpFlow {
    ends: Ends,
    pub waker: Arc<Waker>,
    /// Ends the task when the driver gives the flow up.
    pub cancel: CancellationToken,
    /// Read from the stack, not yet taken by the task.
    up_pending: Option<Bytes>,
    /// The client's FIN was read; pass it on once `up_pending` is delivered.
    up_eof: bool,
    /// From the task, not yet taken by the stack.
    down_pending: Option<Bytes>,
    /// The upstream's FIN was passed to the client.
    down_shut: bool,
    /// The SYN was answered: the stack has data for us.
    pub connected: bool,
    /// The task ended cleanly: both directions ended, or the drain after the first FIN cut them,
    /// and it closed its side of `down`.
    pub finished: bool,
    /// Served by the core's DNS, not an app's connection: not counted in `activeFlows`.
    pub dns: bool,
}

pub(crate) enum Step {
    Keep,
    /// Both directions are done: release the handle.
    Close,
    /// The stack side failed: release the handle and end the task.
    Failed,
}

impl TcpFlow {
    /// A flow whose task dials out, or with `dns` one the core's DNS serves; the DNS flow's
    /// SYN is answered at once.
    pub fn new(ends: Ends, waker: Arc<Waker>, dns: bool) -> TcpFlow {
        TcpFlow {
            ends,
            waker,
            cancel: CancellationToken::new(),
            up_pending: None,
            up_eof: false,
            down_pending: None,
            down_shut: false,
            connected: dns,
            finished: false,
            dns,
        }
    }

    /// Moves what can move in both directions without blocking.
    pub fn pump(&mut self, stack: &mut Stack, id: FlowId, scratch: &mut [u8]) -> Step {
        if self.pump_up(stack, id, scratch).is_err() || self.pump_down(stack, id).is_err() {
            return Step::Failed;
        }
        if self.finished && self.down_shut && self.down_pending.is_none() {
            Step::Close
        } else {
            Step::Keep
        }
    }

    fn pump_up(
        &mut self,
        stack: &mut Stack,
        id: FlowId,
        scratch: &mut [u8],
    ) -> Result<(), StreamError> {
        if !self.connected {
            return Ok(());
        }
        while let Some(tx) = &self.ends.up {
            if let Some(chunk) = self.up_pending.take() {
                match tx.try_send(chunk) {
                    Ok(()) => continue,
                    Err(TrySendError::Full(chunk)) => {
                        self.up_pending = Some(chunk);
                        return Ok(());
                    }
                    // The task is gone; it reports how it ended.
                    Err(TrySendError::Closed(_)) => {
                        self.ends.up = None;
                        return Ok(());
                    }
                }
            }
            if self.up_eof {
                self.ends.up = None;
                return Ok(());
            }
            match stack.tcp_read(id, scratch) {
                Ok(0) => self.up_eof = true,
                Ok(n) => self.up_pending = Some(Bytes::copy_from_slice(&scratch[..n])),
                Err(StreamError::WouldBlock) => return Ok(()),
                Err(e) => return Err(e),
            }
        }
        Ok(())
    }

    fn pump_down(&mut self, stack: &mut Stack, id: FlowId) -> Result<(), StreamError> {
        loop {
            if let Some(mut chunk) = self.down_pending.take() {
                // tcp_write takes at least one byte or blocks; a blocked write asks the stack
                // for a WRITABLE event, so a remainder is retried at once.
                match stack.tcp_write(id, &chunk) {
                    Ok(n) => {
                        chunk.advance(n);
                        if !chunk.is_empty() {
                            self.down_pending = Some(chunk);
                        }
                        continue;
                    }
                    Err(StreamError::WouldBlock) => {
                        self.down_pending = Some(chunk);
                        return Ok(());
                    }
                    Err(e) => return Err(e),
                }
            }
            if self.down_shut {
                return Ok(());
            }
            match self.ends.down.try_recv() {
                Ok(chunk) => self.down_pending = Some(chunk),
                Err(TryRecvError::Empty) => return Ok(()),
                Err(TryRecvError::Disconnected) => match stack.tcp_shutdown(id) {
                    Ok(()) => self.down_shut = true,
                    // The upstream closed before the client acknowledged the SYN-ACK: honk
                    // queues no FIN in SynReceived. Its CONNECTED event pumps this flow again.
                    Err(StreamError::WouldBlock) => return Ok(()),
                    Err(e) => return Err(e),
                },
            }
        }
    }
}

/// Dials `dst` through `route` and splices the two streams (see [`splice`]); both sides close when
/// the splice ends.
pub(crate) async fn dial(
    io: FlowIo,
    id: FlowId,
    dst: SocketAddr,
    route: Route,
    events: Events,
    cancel: CancellationToken,
) {
    let work = async {
        let failed = match route.connect(dst).await {
            Ok(mut upstream) => {
                let _ = events.send(Event::Connected(id));
                let mut io = io;
                splice(&mut io, &mut upstream).await.is_err()
            }
            Err(_) => {
                drop(io);
                true
            }
        };
        let _ = events.send(Event::Finished { id, failed });
    };
    tokio::select! {
        () = cancel.cancelled() => {}
        () = work => {}
    }
}

/// Splices `client` and `upstream` until both directions end, each side's FIN passed on to the
/// other, as `copy_bidirectional` does. From the first FIN on, a direction that stays open is cut
/// after [`DRAIN_DEADLINE`] without progress, so a peer that never closes cannot keep the flow, its
/// buffers and its task. An error in either direction ends both.
async fn splice<C, U>(client: &mut C, upstream: &mut U) -> io::Result<()>
where
    C: AsyncRead + AsyncWrite + Unpin + ?Sized,
    U: AsyncRead + AsyncWrite + Unpin + ?Sized,
{
    let (mut client_rd, mut client_wr) = tokio::io::split(client);
    let (mut upstream_rd, mut upstream_wr) = tokio::io::split(upstream);
    let (up, down) = (Progress::default(), Progress::default());
    let eof = Notify::new();
    let copy = async {
        tokio::try_join!(
            copy_way(&mut client_rd, &mut upstream_wr, &up, &eof),
            copy_way(&mut upstream_rd, &mut client_wr, &down, &eof),
        )
        .map(drop)
    };
    let cutoff = async {
        eof.notified().await;
        drain(&up, &down).await;
    };
    tokio::select! {
        biased;
        result = copy => result,
        // A busy direction can spend the task's budget before the timer is polled.
        () = tokio::task::unconstrained(cutoff) => Ok(()),
    }
}

/// What one direction moved: the bytes read from its source and the bytes its destination took.
#[derive(Default)]
struct Progress {
    read: AtomicU64,
    written: AtomicU64,
}

impl Progress {
    fn snapshot(&self) -> (u64, u64) {
        (self.read.load(Relaxed), self.written.load(Relaxed))
    }
}

/// Copies `rd` to `wr` until `rd`'s FIN, then flushes `wr` and shuts its write side down. `eof`
/// is woken at the FIN, before the final flush and shutdown, which may block.
async fn copy_way<R, W>(rd: &mut R, wr: &mut W, progress: &Progress, eof: &Notify) -> io::Result<()>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let mut buf = vec![0; CHUNK];
    let mut unflushed = false;
    loop {
        let n = poll_fn(|cx| -> Poll<io::Result<usize>> {
            let mut chunk = ReadBuf::new(&mut buf);
            match Pin::new(&mut *rd).poll_read(cx, &mut chunk) {
                Poll::Ready(result) => Poll::Ready(result.map(|()| chunk.filled().len())),
                Poll::Pending => {
                    // Nothing to read for now: a node's protocol may hold what it was given until
                    // it is flushed.
                    if unflushed {
                        ready!(Pin::new(&mut *wr).poll_flush(cx))?;
                        unflushed = false;
                    }
                    Poll::Pending
                }
            }
        })
        .await?;
        if n == 0 {
            eof.notify_one();
            break;
        }
        progress.read.fetch_add(n as u64, Relaxed);
        let mut rest = &buf[..n];
        while !rest.is_empty() {
            let written = wr.write(rest).await?;
            if written == 0 {
                return Err(io::ErrorKind::WriteZero.into());
            }
            progress.written.fetch_add(written as u64, Relaxed);
            rest = &rest[written..];
        }
        unflushed = true;
    }
    wr.flush().await?;
    wr.shutdown().await
}

/// Returns once neither direction read or wrote a byte for [`DRAIN_DEADLINE`]. Idle time is
/// measured on the monotonic clock, so a late wakeup counts as idle, not as a pause in the count.
async fn drain(up: &Progress, down: &Progress) {
    let progress = || (up.snapshot(), down.snapshot());
    let mut last = progress();
    let mut now = Instant::now();
    let mut deadline = now + DRAIN_DEADLINE;
    loop {
        sleep_until(deadline.min(now + DRAIN_CHECK)).await;
        now = Instant::now();
        let current = progress();
        if current != last {
            last = current;
            deadline = now + DRAIN_DEADLINE;
        } else if now >= deadline {
            return;
        }
    }
}

/// Answers the framed DNS queries of a TCP/53 connection to the virtual resolver; an idle one is
/// closed after [`DRAIN_DEADLINE`].
pub(crate) async fn serve_dns(
    mut io: FlowIo,
    id: FlowId,
    route: Route,
    upstreams: Arc<[SocketAddr]>,
    events: Events,
    cancel: CancellationToken,
) {
    let work = async {
        let failed = dns::serve_tcp(&mut io, &upstreams, &route, DRAIN_DEADLINE)
            .await
            .is_err();
        drop(io);
        let _ = events.send(Event::Finished { id, failed });
    };
    tokio::select! {
        () = cancel.cancelled() => {}
        () = work => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tunnel::flow::{self, Key};
    use tokio::io::{AsyncReadExt, DuplexStream, duplex};
    use tokio::sync::mpsc;
    use tokio::task::JoinHandle;

    /// A splice between a flow's client end and an in-memory upstream, after the client sent
    /// `request` and its FIN; the server end of the upstream is returned with the driver's ends.
    fn spliced(request: &'static [u8]) -> (Ends, DuplexStream, JoinHandle<io::Result<()>>) {
        let (events, _) = mpsc::unbounded_channel();
        // The key only names the flow in its wakes, which nothing reads here.
        let (mut ends, mut io) = flow::pair(Waker::new(Key::Udp(0), events));
        let up = ends.up.take().unwrap();
        up.try_send(Bytes::from_static(request)).unwrap();
        drop(up);
        let (mut upstream, server) = duplex(CHUNK);
        let relay = tokio::spawn(async move { splice(&mut io, &mut upstream).await });
        (ends, server, relay)
    }

    /// The upstream answers the client's request and FIN, then never closes: both sides close
    /// [`DRAIN_DEADLINE`] after the answer, which reaches the client first.
    #[tokio::test(start_paused = true)]
    async fn an_upstream_that_never_closes_after_the_clients_fin_is_cut_at_the_deadline() {
        let (mut ends, mut server, relay) = spliced(b"GET");
        let mut request = Vec::new();
        server.read_to_end(&mut request).await.unwrap();
        assert_eq!(request, b"GET");
        server.write_all(b"200").await.unwrap();
        let answered = Instant::now();
        // Without the bound the splice never ends; the timeout turns that into a failure.
        let ended = tokio::time::timeout(2 * DRAIN_DEADLINE, relay).await;
        assert!(matches!(ended, Ok(Ok(Ok(())))), "{ended:?}");
        let idle = answered.elapsed();
        assert!(idle >= DRAIN_DEADLINE, "{idle:?}");
        assert!(idle <= DRAIN_DEADLINE + DRAIN_CHECK, "{idle:?}");
        assert_eq!(ends.down.recv().await.as_deref(), Some(&b"200"[..]));
        assert_eq!(ends.down.recv().await, None, "the client's side is closed");
        assert_eq!(
            server.read(&mut [0; 1]).await.unwrap(),
            0,
            "so is the upstream"
        );
    }

    /// An upstream that keeps sending after the client's FIN, each pause shorter than the
    /// deadline, runs past it and ends when the upstream closes.
    #[tokio::test(start_paused = true)]
    async fn a_slow_upstream_that_keeps_sending_is_not_cut() {
        let (mut ends, mut server, relay) = spliced(b"GET");
        let mut request = Vec::new();
        server.read_to_end(&mut request).await.unwrap();
        let begun = Instant::now();
        for part in [b"a", b"b", b"c"] {
            tokio::time::sleep(DRAIN_DEADLINE * 2 / 3).await;
            server.write_all(part).await.unwrap();
        }
        drop(server);
        relay.await.unwrap().unwrap();
        assert!(begun.elapsed() >= 2 * DRAIN_DEADLINE);
        let mut got = Vec::new();
        while let Some(chunk) = ends.down.recv().await {
            got.extend_from_slice(&chunk);
        }
        assert_eq!(got, b"abc");
    }
}
