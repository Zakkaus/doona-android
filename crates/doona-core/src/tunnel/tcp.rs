//! TCP flows: the driver's side of each accepted connection, and the task that serves it, either
//! by dialing the original destination through the flow's route or by answering DNS itself.

use super::flow::{CHUNK, Ends, Event, Events, FlowIo, Waker};
use super::route::Route;
use crate::dns;
use bytes::{Buf, Bytes};
use honk_netstack::{FlowId, Stack, StreamError, TcpState};
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

/// Once either side's FIN has arrived, a flow may go this long without a byte read or written in
/// either direction before it is cut. honk's `DRAIN_DEADLINE` (honk 7cd8b13c). A TCP/53
/// connection to the core's DNS may idle as long (see `dns::serve_tcp`).
pub(crate) const DRAIN_DEADLINE: Duration = Duration::from_secs(30);

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
    /// The task ended cleanly: both directions ended, and it closed its side of `down`.
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
        if !self.ends.fin.is_cancelled()
            && matches!(
                stack.tcp_state(id),
                Some(
                    TcpState::CloseWait
                        | TcpState::LastAck
                        | TcpState::Closing
                        | TcpState::TimeWait
                )
            )
        {
            // The client's FIN arrived; data before it may still wait here or in the channel, and
            // the task's drain starts from it all the same.
            self.ends.fin.cancel();
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
/// the splice ends, and a splice cut at the drain time fails, so the driver resets the client.
pub(crate) async fn dial(
    io: FlowIo,
    id: FlowId,
    dst: SocketAddr,
    route: Route,
    drain: Duration,
    events: Events,
    cancel: CancellationToken,
) {
    let work = async {
        let failed = match route.connect(dst).await {
            Ok(mut upstream) => {
                let _ = events.send(Event::Connected(id));
                let fin = io.client_fin();
                let mut io = io;
                splice(&mut io, &mut upstream, &fin, drain).await.is_err()
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
/// other, as `copy_bidirectional` does. From the first FIN on (the client's once `client_fin` is
/// cancelled, even while the data before it waits behind a blocked write), a flow that moves no
/// byte for `deadline` is cut with `TimedOut`: a peer that never closes, never reads or keeps its
/// window shut cannot keep the flow, its buffers and its task. An error in either direction ends
/// both.
async fn splice<C, U>(
    client: &mut C,
    upstream: &mut U,
    client_fin: &CancellationToken,
    deadline: Duration,
) -> io::Result<()>
where
    C: AsyncRead + AsyncWrite + Unpin + ?Sized,
    U: AsyncRead + AsyncWrite + Unpin + ?Sized,
{
    let (mut client_rd, mut client_wr) = tokio::io::split(client);
    let (mut upstream_rd, mut upstream_wr) = tokio::io::split(upstream);
    let moved = Moved::new();
    let eof = Notify::new();
    let copy = async {
        tokio::try_join!(
            copy_way(&mut client_rd, &mut upstream_wr, &moved, &eof),
            copy_way(&mut upstream_rd, &mut client_wr, &moved, &eof),
        )
        .map(drop)
    };
    let cutoff = async {
        tokio::select! {
            () = eof.notified() => {}
            () = client_fin.cancelled() => {}
        }
        drain(&moved, deadline).await;
    };
    tokio::select! {
        biased;
        result = copy => result,
        // A busy direction can spend the task's budget before the timer is polled.
        () = tokio::task::unconstrained(cutoff) => Err(io::ErrorKind::TimedOut.into()),
    }
}

/// When either direction last read or wrote a byte: nanoseconds after `base` on tokio's monotonic
/// clock.
struct Moved {
    base: Instant,
    at: AtomicU64,
}

impl Moved {
    fn new() -> Moved {
        Moved {
            base: Instant::now(),
            at: AtomicU64::new(0),
        }
    }

    /// Records that a byte moved now.
    fn mark(&self) {
        let at = u64::try_from(self.base.elapsed().as_nanos()).unwrap_or(u64::MAX);
        self.at.fetch_max(at, Relaxed);
    }

    fn last(&self) -> Instant {
        self.base + Duration::from_nanos(self.at.load(Relaxed))
    }
}

/// Copies `rd` to `wr` until `rd`'s FIN, then flushes `wr` and shuts its write side down. `eof`
/// is woken at the FIN, before the final flush and shutdown, which may block.
async fn copy_way<R, W>(rd: &mut R, wr: &mut W, moved: &Moved, eof: &Notify) -> io::Result<()>
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
        moved.mark();
        let mut rest = &buf[..n];
        while !rest.is_empty() {
            let written = wr.write(rest).await?;
            if written == 0 {
                return Err(io::ErrorKind::WriteZero.into());
            }
            moved.mark();
            rest = &rest[written..];
        }
        unflushed = true;
    }
    wr.flush().await?;
    wr.shutdown().await
}

/// Returns once no byte moved for `idle`, counted from the later of the drain's start and the last
/// byte moved on the monotonic clock: a late wakeup, as after the process was stopped, does not
/// extend it.
async fn drain(moved: &Moved, idle: Duration) {
    let start = Instant::now();
    loop {
        let deadline = moved.last().max(start) + idle;
        if Instant::now() >= deadline {
            return;
        }
        sleep_until(deadline).await;
    }
}

/// Answers the framed DNS queries of a TCP/53 connection to the virtual resolver; one that sends
/// no query or takes no answer for `idle` fails, so the driver resets the client.
pub(crate) async fn serve_dns(
    mut io: FlowIo,
    id: FlowId,
    route: Route,
    upstreams: Arc<[SocketAddr]>,
    idle: Duration,
    events: Events,
    cancel: CancellationToken,
) {
    let work = async {
        let failed = dns::serve_tcp(&mut io, &upstreams, &route, idle)
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
    use crate::tunnel::flow::{self, DEPTH, Key};
    use honk_netstack::{AcceptOpts, AcceptWhen, Config, ExcludeSet, Mode};
    use tokio::io::{AsyncReadExt, DuplexStream, duplex};
    use tokio::sync::mpsc;
    use tokio::task::JoinHandle;

    /// How late a timer of the paused clock may fire: tokio's timers count whole milliseconds.
    const LATE: Duration = Duration::from_millis(10);

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
        let fin = io.client_fin();
        let relay =
            tokio::spawn(async move { splice(&mut io, &mut upstream, &fin, DRAIN_DEADLINE).await });
        (ends, server, relay)
    }

    /// The upstream answers the client's request and FIN, then never closes: the flow is cut, as a
    /// failure, [`DRAIN_DEADLINE`] after the answer, which reaches the client first.
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
        assert!(
            matches!(ended, Ok(Ok(Err(ref e))) if e.kind() == io::ErrorKind::TimedOut),
            "{ended:?}"
        );
        let idle = answered.elapsed();
        assert!(idle >= DRAIN_DEADLINE, "{idle:?}");
        assert!(idle <= DRAIN_DEADLINE + LATE, "{idle:?}");
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

    /// The client's FIN waits behind data the upstream does not take: the relay is stuck in a
    /// write and never reads that far. The FIN still starts the drain once the driver signals it,
    /// and the flow is cut a deadline after its last byte moved, as a failure.
    #[tokio::test(start_paused = true)]
    async fn a_fin_queued_behind_a_blocked_write_still_starts_the_drain() {
        let (events, _) = mpsc::unbounded_channel();
        let (mut ends, mut io) = flow::pair(Waker::new(Key::Udp(0), events));
        let fin = io.client_fin();
        // The upstream never reads, and one chunk fills its buffer.
        let (mut upstream, _server) = duplex(CHUNK);
        let relay =
            tokio::spawn(async move { splice(&mut io, &mut upstream, &fin, DRAIN_DEADLINE).await });
        // One chunk fills the upstream, the next blocks the relay's write, DEPTH more queue up.
        let up = ends.up.take().unwrap();
        for _ in 0..DEPTH + 2 {
            up.send(Bytes::from(vec![7; CHUNK])).await.unwrap();
        }
        // What the driver does when the client's FIN arrives behind them.
        drop(up);
        ends.fin.cancel();
        let fin_at = Instant::now();
        let ended = tokio::time::timeout(2 * DRAIN_DEADLINE, relay).await;
        assert!(
            matches!(ended, Ok(Ok(Err(ref e))) if e.kind() == io::ErrorKind::TimedOut),
            "{ended:?}"
        );
        let idle = fin_at.elapsed();
        assert!(idle >= DRAIN_DEADLINE, "{idle:?}");
        assert!(idle <= DRAIN_DEADLINE + LATE, "{idle:?}");
        assert_eq!(ends.down.recv().await, None, "the client's side is closed");
    }

    /// The deadline counts from the last byte moved, not from when the drain noticed it: a flow
    /// idle since before the process stopped for twice the deadline is cut when it runs again.
    #[tokio::test(start_paused = true)]
    async fn a_late_wakeup_does_not_extend_the_drain() {
        let (_ends, mut server, relay) = spliced(b"GET");
        let mut request = Vec::new();
        server.read_to_end(&mut request).await.unwrap();
        // Off the 100 ms grid on which a drain that samples counters would look.
        tokio::time::sleep(DRAIN_DEADLINE / 2 + Duration::from_millis(50)).await;
        server.write_all(b"200").await.unwrap();
        // The relay moves the answer; no timer is due, so the drain does not look yet.
        for _ in 0..8 {
            tokio::task::yield_now().await;
        }
        // The process is stopped: the clock jumps, and every timer is late.
        tokio::time::advance(2 * DRAIN_DEADLINE).await;
        let ended = tokio::time::timeout(LATE, relay).await;
        assert!(
            matches!(ended, Ok(Ok(Err(ref e))) if e.kind() == io::ErrorKind::TimedOut),
            "{ended:?}"
        );
    }

    /// An app's stack and the core's, back to back, with one connection between them.
    struct Wire {
        client: Stack,
        core: Stack,
        packet: Vec<u8>,
    }

    impl Wire {
        /// The app's flow and the core's.
        fn connected() -> (Wire, FlowId, FlowId) {
            let now = std::time::Instant::now();
            let mut client = Config::new([7; 32]);
            client.local_v4 = Some(std::net::Ipv4Addr::new(172, 19, 0, 1));
            let mut core = Config::new([9; 32]);
            core.mode = Mode::Transparent {
                exclude: ExcludeSet::default(),
            };
            let mut wire = Wire {
                client: Stack::new(client, now),
                core: Stack::new(core, now),
                packet: vec![0; 2048],
            };
            let app = wire.client.tcp_connect(now, ([10, 9, 9, 9], 80).into());
            wire.settle();
            let syn = wire.core.poll_accept().unwrap();
            let now = AcceptOpts {
                when: AcceptWhen::Now,
                ..AcceptOpts::default()
            };
            wire.core.tcp_accept(syn.id, now).unwrap();
            wire.settle();
            (wire, app.unwrap(), syn.id)
        }

        /// Moves packets both ways until neither stack has one to send.
        fn settle(&mut self) {
            let now = std::time::Instant::now();
            let mut moved = true;
            while moved {
                moved = false;
                while let Some(n) = self.client.poll_transmit(now, &mut self.packet) {
                    let _ = self.core.receive(now, &self.packet[..n]);
                    moved = true;
                }
                while let Some(n) = self.core.poll_transmit(now, &mut self.packet) {
                    let _ = self.client.receive(now, &self.packet[..n]);
                    moved = true;
                }
            }
        }
    }

    /// The task has taken nothing, so the driver holds what it read and leaves the client's FIN
    /// in the stack: the task learns of the FIN all the same (see [`splice`]).
    #[test]
    fn the_clients_fin_reaches_the_task_while_the_data_before_it_waits() {
        let (mut wire, app, id) = Wire::connected();
        let (events, _) = mpsc::unbounded_channel();
        let waker = Waker::new(Key::Tcp(id), events);
        let (ends, io) = flow::pair(Arc::clone(&waker));
        let up = ends.up.clone().unwrap();
        for _ in 0..DEPTH {
            up.try_send(Bytes::from_static(b"x")).unwrap();
        }
        drop(up);
        let mut flow = TcpFlow::new(ends, waker, true);
        wire.client.tcp_write(app, b"GET").unwrap();
        wire.client.tcp_shutdown(app).unwrap();
        wire.settle();
        let mut scratch = vec![0; CHUNK];
        assert!(matches!(
            flow.pump(&mut wire.core, id, &mut scratch),
            Step::Keep
        ));
        assert_eq!(flow.up_pending.as_deref(), Some(&b"GET"[..]));
        assert!(!flow.up_eof, "the FIN is still in the stack");
        assert!(io.client_fin().is_cancelled());
    }
}
