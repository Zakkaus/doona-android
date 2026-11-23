//! The relay of a dialed TCP flow: the client's stream and the upstream's, spliced until both
//! directions end. Every poll of either stream runs inside the flow's gate (see `gate.rs`), which
//! records what moved; the relay never reads the deadline, and the driver cuts the flow by giving
//! it up.

use super::flow::CHUNK;
use super::gate::{Gate, Gated};
use std::future::poll_fn;
use std::io;
use std::pin::Pin;
use std::task::{Poll, ready};
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt, ReadBuf};

pub(super) mod score;

/// Splices `client`, the flow's end (gated itself), and `upstream` until both directions end, each
/// side's FIN passed on to the other, as `copy_bidirectional` does. Each FIN and each byte read or
/// written goes to the gate: once the flow has moved no byte for its idle time after a FIN, the
/// driver gives it up, so a peer that never closes, never reads or keeps its window shut cannot
/// keep the flow, its buffers and its task. An error in either direction ends both.
pub(super) async fn splice<C, U>(client: &mut C, upstream: &mut U, gate: &Gate) -> io::Result<()>
where
    C: AsyncRead + AsyncWrite + Unpin + ?Sized,
    U: AsyncRead + AsyncWrite + Unpin + ?Sized,
{
    let mut upstream = Gated { gate, io: upstream };
    let (mut client_rd, mut client_wr) = tokio::io::split(client);
    let (mut upstream_rd, mut upstream_wr) = tokio::io::split(&mut upstream);
    tokio::try_join!(
        copy_way(&mut client_rd, &mut upstream_wr),
        copy_way(&mut upstream_rd, &mut client_wr),
    )
    .map(drop)
}

/// Copies `rd` to `wr` until `rd`'s FIN, then flushes `wr` and shuts its write side down.
async fn copy_way<R, W>(rd: &mut R, wr: &mut W) -> io::Result<()>
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
            break;
        }
        wr.write_all(&buf[..n]).await?;
        unflushed = true;
    }
    wr.flush().await?;
    wr.shutdown().await
}

#[cfg(test)]
mod bench;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::stats::meter::Tally;
    use crate::tunnel::flow::{self, DEPTH, Ends, Event, FlowIo, Key, Waker};
    use crate::tunnel::gate::Task;
    use crate::tunnel::tcp::{DRAIN_DEADLINE, until_given_up};
    use bytes::Bytes;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering::SeqCst};
    use std::task::Context;
    use std::time::Duration;
    use tokio::io::{AsyncReadExt, DuplexStream, duplex};
    use tokio::sync::mpsc;
    use tokio::task::JoinHandle;
    use tokio::time::{Instant, sleep_until};

    /// How late a timer of the paused clock may fire: tokio's timers count whole milliseconds.
    const LATE: Duration = Duration::from_millis(10);

    /// What a relay's task came to: `None` once the driver gave the flow up.
    type Relay = JoinHandle<Option<io::Result<()>>>;

    /// A relay between a fresh flow's client end and an in-memory upstream that holds one chunk,
    /// with the driver's part for the flow (see [`keep`]) in a task of its own. With a `request`,
    /// the client sent it and its FIN first. The server end of the upstream is returned with the
    /// driver's ends.
    fn relay(request: Option<&'static [u8]>) -> (Ends, DuplexStream, Relay) {
        let (events, wakes) = mpsc::unbounded_channel();
        let waker = Waker::new(Key::Udp(0), events);
        let (mut ends, mut io) = flow::pair(
            Arc::clone(&waker),
            Tally::new(None),
            DRAIN_DEADLINE,
            Task::Relay,
        );
        if let Some(request) = request {
            let up = ends.up.take().unwrap();
            up.try_send(Bytes::from_static(request)).unwrap();
        }
        let (mut upstream, server) = duplex(CHUNK);
        let gate = io.gate();
        tokio::spawn(keep(Arc::clone(&gate), waker, wakes));
        let relay = tokio::spawn(async move {
            until_given_up(&gate, splice(&mut io, &mut upstream, &gate)).await
        });
        (ends, server, relay)
    }

    /// The driver's part for one flow, without a stack: woken as the drain begins, it waits for
    /// the deadline, and gives the flow up once that has passed, as `Driver::expire_drains` does
    /// on `TcpFlow::verdict`.
    async fn keep(gate: Arc<Gate>, waker: Arc<Waker>, mut wakes: mpsc::UnboundedReceiver<Event>) {
        loop {
            waker.rearm();
            if gate.read().expired {
                gate.give_up();
                return;
            }
            match gate.deadline() {
                Some(at) => tokio::select! {
                    () = sleep_until(at) => {}
                    _ = wakes.recv() => {}
                },
                None => drop(wakes.recv().await),
            }
        }
    }

    /// The upstream answers the client's request and FIN, then never closes: the flow is given
    /// up [`DRAIN_DEADLINE`] after the answer, which reaches the client first.
    #[tokio::test(start_paused = true)]
    async fn an_upstream_that_never_closes_after_the_clients_fin_is_cut_at_the_deadline() {
        let (mut ends, mut server, relay) = relay(Some(b"GET"));
        let mut request = Vec::new();
        server.read_to_end(&mut request).await.unwrap();
        assert_eq!(request, b"GET");
        server.write_all(b"200").await.unwrap();
        let answered = Instant::now();
        // Without the bound the relay never ends; the timeout turns that into a failure.
        let ended = tokio::time::timeout(2 * DRAIN_DEADLINE, relay).await;
        assert!(matches!(ended, Ok(Ok(None))), "{ended:?}");
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
        let (mut ends, mut server, relay) = relay(Some(b"GET"));
        let mut request = Vec::new();
        server.read_to_end(&mut request).await.unwrap();
        let begun = Instant::now();
        for part in [b"a", b"b", b"c"] {
            tokio::time::sleep(DRAIN_DEADLINE * 2 / 3).await;
            server.write_all(part).await.unwrap();
        }
        drop(server);
        relay.await.unwrap().expect("given up").unwrap();
        assert!(begun.elapsed() >= 2 * DRAIN_DEADLINE);
        let mut got = Vec::new();
        while let Some(chunk) = ends.down.recv().await {
            got.extend_from_slice(&chunk);
        }
        assert_eq!(got, b"abc");
    }

    /// The client's FIN waits behind data the upstream does not take: the relay is stuck in a
    /// write and never reads that far. The FIN the driver saw still starts the drain, and the flow
    /// is given up a deadline after its last byte moved.
    #[tokio::test(start_paused = true)]
    async fn a_fin_queued_behind_a_blocked_write_still_starts_the_drain() {
        // The upstream never reads, and one chunk fills its buffer.
        let (mut ends, _server, relay) = relay(None);
        // One chunk fills the upstream, the next blocks the relay's write, DEPTH more queue up.
        let up = ends.up.take().unwrap();
        for _ in 0..DEPTH + 2 {
            up.send(Bytes::from(vec![7; CHUNK])).await.unwrap();
        }
        // What the driver does when the client's FIN arrives behind them.
        drop(up);
        let fin_at = Instant::now();
        ends.gate.fin();
        let ended = tokio::time::timeout(2 * DRAIN_DEADLINE, relay).await;
        assert!(matches!(ended, Ok(Ok(None))), "{ended:?}");
        let idle = fin_at.elapsed();
        assert!(idle >= DRAIN_DEADLINE, "{idle:?}");
        assert!(idle <= DRAIN_DEADLINE + LATE, "{idle:?}");
        assert_eq!(ends.down.recv().await, None, "the client's side is closed");
    }

    /// The deadline counts from the last byte moved, not from when the driver looks: a flow idle
    /// since before the process stopped for twice the deadline is given up when it runs again.
    #[tokio::test(start_paused = true)]
    async fn a_late_wakeup_does_not_extend_the_drain() {
        let (_ends, mut server, relay) = relay(Some(b"GET"));
        let mut request = Vec::new();
        server.read_to_end(&mut request).await.unwrap();
        // Off the 100 ms grid on which a drain that samples counters would look.
        tokio::time::sleep(DRAIN_DEADLINE / 2 + Duration::from_millis(50)).await;
        server.write_all(b"200").await.unwrap();
        // The relay moves the answer; no timer is due, so the driver does not look yet.
        for _ in 0..8 {
            tokio::task::yield_now().await;
        }
        // The process is stopped: the clock jumps, and every timer is late.
        tokio::time::advance(2 * DRAIN_DEADLINE).await;
        let ended = tokio::time::timeout(LATE, relay).await;
        assert!(matches!(ended, Ok(Ok(None))), "{ended:?}");
    }

    /// The driver records the client's FIN; the process stops before the relay runs again. The
    /// drain counts from the FIN the driver saw, so the flow is given up as soon as it runs.
    #[tokio::test(start_paused = true)]
    async fn a_stop_between_the_fin_and_the_relay_does_not_extend_the_drain() {
        let (ends, _server, relay) = relay(None);
        tokio::task::yield_now().await;
        // What the driver does when it sees the client's FIN.
        ends.gate.fin();
        // The process is stopped: the clock jumps before the relay runs.
        tokio::time::advance(2 * DRAIN_DEADLINE).await;
        let ended = tokio::time::timeout(LATE, relay).await;
        assert!(matches!(ended, Ok(Ok(None))), "{ended:?}");
    }

    /// Data that arrives while the process is stopped past the deadline does not revive the flow:
    /// the relay, which runs before the driver looks, does not move it.
    #[tokio::test(start_paused = true)]
    async fn data_that_arrives_during_a_stop_does_not_revive_an_expired_flow() {
        let (mut ends, mut server, relay) = relay(Some(b"GET"));
        let mut request = Vec::new();
        server.read_to_end(&mut request).await.unwrap();
        // The process is stopped: the upstream sends, and the clock passes the deadline.
        server.write_all(b"late").await.unwrap();
        tokio::time::advance(2 * DRAIN_DEADLINE).await;
        let ended = tokio::time::timeout(LATE, relay).await;
        assert!(matches!(ended, Ok(Ok(None))), "{ended:?}");
        assert_eq!(
            ends.down.recv().await,
            None,
            "the late data reached the client"
        );
    }

    /// The driver gives the flow up as the upstream, which had stopped reading, takes what it
    /// holds: the relay's write could go on at once, and does not. A select without `biased` picks
    /// its first branch at random: 32 rounds.
    #[tokio::test(start_paused = true)]
    async fn a_relay_given_up_writes_no_more_though_its_upstream_is_ready() {
        let mut wrote = 0;
        for _ in 0..32 {
            let (events, _) = mpsc::unbounded_channel();
            let (mut ends, mut io) = flow::pair(
                Waker::new(Key::Udp(0), events),
                Tally::new(None),
                DRAIN_DEADLINE,
                Task::Relay,
            );
            let gate = io.gate();
            let given_up = Arc::clone(&gate);
            let (mut upstream, mut server) = duplex(CHUNK);
            let relay = tokio::spawn(async move {
                until_given_up(&gate, splice(&mut io, &mut upstream, &gate)).await
            });
            let up = ends.up.take().unwrap();
            for _ in 0..3 {
                up.send(Bytes::from(vec![7; CHUNK])).await.unwrap();
            }
            for _ in 0..8 {
                tokio::task::yield_now().await;
            }
            // The upstream holds one chunk, and the relay waits to write the next.
            let mut held = vec![0; CHUNK];
            server.read_exact(&mut held).await.unwrap();
            given_up.give_up();
            assert!(matches!(relay.await, Ok(None)));
            let mut more = Vec::new();
            server.read_to_end(&mut more).await.unwrap();
            wrote += usize::from(!more.is_empty());
        }
        assert_eq!(wrote, 0, "rounds that wrote after the cancel");
    }

    /// A stream that runs `hook` as its first read or write starts, in the middle of the relay's
    /// poll: as if the relay's thread were descheduled there. Once `given_up` is set, it counts
    /// what it writes in `after`.
    struct Hooked<T> {
        io: T,
        on_read: Option<Box<dyn FnOnce() + Send>>,
        on_write: Option<Box<dyn FnOnce() + Send>>,
        given_up: Arc<AtomicBool>,
        after: Arc<AtomicUsize>,
    }

    impl<T> Hooked<T> {
        fn new(io: T) -> Hooked<T> {
            Hooked {
                io,
                on_read: None,
                on_write: None,
                given_up: Arc::default(),
                after: Arc::default(),
            }
        }
    }

    impl<T: AsyncRead + Unpin> AsyncRead for Hooked<T> {
        fn poll_read(
            self: Pin<&mut Self>,
            cx: &mut Context<'_>,
            buf: &mut ReadBuf<'_>,
        ) -> Poll<io::Result<()>> {
            let this = self.get_mut();
            if let Some(hook) = this.on_read.take() {
                hook();
            }
            Pin::new(&mut this.io).poll_read(cx, buf)
        }
    }

    impl<T: AsyncWrite + Unpin> AsyncWrite for Hooked<T> {
        fn poll_write(
            self: Pin<&mut Self>,
            cx: &mut Context<'_>,
            data: &[u8],
        ) -> Poll<io::Result<usize>> {
            let this = self.get_mut();
            if let Some(hook) = this.on_write.take() {
                hook();
            }
            let n = ready!(Pin::new(&mut this.io).poll_write(cx, data))?;
            if this.given_up.load(SeqCst) {
                this.after.fetch_add(n, SeqCst);
            }
            Poll::Ready(Ok(n))
        }

        fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Pin::new(&mut self.get_mut().io).poll_flush(cx)
        }

        fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Pin::new(&mut self.get_mut().io).poll_shutdown(cx)
        }
    }

    /// The flow's waker, and the driver's end of the wakes it sends.
    type Wakes = (Arc<Waker>, mpsc::UnboundedReceiver<Event>);

    /// A fresh flow whose client has sent `GET` and keeps its side open, its gate, and its wakes.
    fn asked() -> (Ends, FlowIo, Arc<Gate>, Wakes) {
        let (events, woken) = mpsc::unbounded_channel();
        let waker = Waker::new(Key::Udp(0), events);
        let pair = flow::pair(
            Arc::clone(&waker),
            Tally::new(None),
            DRAIN_DEADLINE,
            Task::Relay,
        );
        let (ends, io) = pair;
        let up = ends.up.as_ref().unwrap();
        up.try_send(Bytes::from_static(b"GET")).unwrap();
        let gate = io.gate();
        (ends, io, gate, (waker, woken))
    }

    /// The relay has passed its cancel check and starts to read the client's request when its
    /// thread is descheduled; the driver gives the flow up meanwhile. The relay writes nothing of
    /// the request upstream when it runs again.
    #[tokio::test(start_paused = true)]
    async fn a_relay_given_up_as_it_reads_writes_nothing_upstream() {
        let (_ends, io, gate, _) = asked();
        let driver = Arc::clone(&gate);
        let mut client = Hooked::new(io);
        client.on_read = Some(Box::new(move || {
            driver.give_up();
        }));
        let (mut upstream, mut server) = duplex(CHUNK);
        let relay = tokio::spawn(async move {
            until_given_up(&gate, splice(&mut client, &mut upstream, &gate)).await
        });
        assert!(matches!(relay.await, Ok(None)));
        let mut got = Vec::new();
        server.read_to_end(&mut got).await.unwrap();
        assert!(
            got.is_empty(),
            "wrote {got:?} upstream after the flow was given up"
        );
    }

    /// The relay is in the middle of its write upstream when its thread is descheduled; the driver,
    /// on a thread of its own, gives the flow up meanwhile, and ends it once no poll is in progress
    /// (as `Driver::stop_tcp`): it waits for nothing, and is woken by the write's end. Nothing
    /// reaches the upstream once the driver ends the flow.
    #[tokio::test(start_paused = true)]
    async fn a_relay_given_up_in_the_middle_of_a_write_writes_nothing_after() {
        let (_ends, mut io, gate, (waker, mut woken)) = asked();
        let (near, _server) = duplex(CHUNK);
        let mut upstream = Hooked::new(near);
        let (given_up, after) = (Arc::clone(&upstream.given_up), Arc::clone(&upstream.after));
        let driver = Arc::clone(&gate);
        upstream.on_write = Some(Box::new(move || {
            let (done, finished) = std::sync::mpsc::channel();
            std::thread::spawn(move || {
                loop {
                    waker.rearm();
                    if driver.give_up() {
                        break;
                    }
                    woken.blocking_recv();
                }
                given_up.store(true, SeqCst);
                let _ = done.send(());
            });
            // Time for the driver to end the flow, unless it waits for the write's end.
            let _ = finished.recv_timeout(Duration::from_millis(200));
        }));
        let relay = tokio::spawn(async move {
            until_given_up(&gate, splice(&mut io, &mut upstream, &gate)).await
        });
        assert!(matches!(relay.await, Ok(None)));
        assert_eq!(
            after.load(SeqCst),
            0,
            "bytes written upstream after the give-up"
        );
    }

    /// The deadline passes while the driver is descheduled, so nothing gives the flow up: the
    /// relay, waiting to write upstream, writes nothing when the upstream takes data again.
    #[tokio::test(start_paused = true)]
    async fn a_relay_writes_nothing_upstream_after_the_deadline_though_not_given_up() {
        let (events, _) = mpsc::unbounded_channel();
        let waker = Waker::new(Key::Udp(0), events);
        let (mut ends, mut io) = flow::pair(waker, Tally::new(None), DRAIN_DEADLINE, Task::Relay);
        let gate = io.gate();
        let (mut upstream, mut server) = duplex(CHUNK);
        let relay = tokio::spawn(async move {
            until_given_up(&gate, splice(&mut io, &mut upstream, &gate)).await
        });
        let up = ends.up.take().unwrap();
        for _ in 0..3 {
            up.send(Bytes::from(vec![7; CHUNK])).await.unwrap();
        }
        drop(up);
        ends.gate.fin();
        for _ in 0..8 {
            tokio::task::yield_now().await;
        }
        tokio::time::advance(2 * DRAIN_DEADLINE).await;
        // The upstream takes the chunk it held: the relay could write the next.
        let mut held = vec![0; CHUNK];
        server.read_exact(&mut held).await.unwrap();
        let late = tokio::time::timeout(LATE, server.read(&mut [0; 1])).await;
        assert!(
            late.is_err(),
            "the relay wrote upstream after the deadline: {late:?}"
        );
        relay.abort();
    }
}
