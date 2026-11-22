//! The relay of a dialed TCP flow: the client's stream and the upstream's, spliced until both
//! directions end or the flow's drain runs out (see `flow::Drain`).

use super::flow::{CHUNK, Drain};
use std::future::poll_fn;
use std::io;
use std::pin::Pin;
use std::task::{Poll, ready};
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt, ReadBuf};
use tokio::time::{Instant, sleep_until};

/// Splices `client` and `upstream` until both directions end, each side's FIN passed on to the
/// other, as `copy_bidirectional` does. From the first FIN on (the client's as the driver saw it,
/// even while the data before it waits behind a blocked write), a flow that moves no byte for the
/// drain's idle time is cut with `TimedOut`: a peer that never closes, never reads or keeps its
/// window shut cannot keep the flow, its buffers and its task. Bytes the driver hands the stack
/// count, so a client that reads slowly keeps the flow in the middle of a chunk. An error in either
/// direction ends both.
pub(super) async fn splice<C, U>(client: &mut C, upstream: &mut U, drain: &Drain) -> io::Result<()>
where
    C: AsyncRead + AsyncWrite + Unpin + ?Sized,
    U: AsyncRead + AsyncWrite + Unpin + ?Sized,
{
    let (mut client_rd, mut client_wr) = tokio::io::split(client);
    let (mut upstream_rd, mut upstream_wr) = tokio::io::split(upstream);
    let copy = async {
        tokio::try_join!(
            copy_way(&mut client_rd, &mut upstream_wr, drain),
            copy_way(&mut upstream_rd, &mut client_wr, drain),
        )
        .map(drop)
    };
    let cutoff = async {
        drain.begun().await;
        // Counted on the monotonic clock from the later of the drain's start and the last byte
        // moved: a late wakeup, as after the process was stopped, does not extend it.
        while let Some(deadline) = drain.deadline()
            && Instant::now() < deadline
        {
            sleep_until(deadline).await;
        }
    };
    tokio::select! {
        biased;
        result = copy => result,
        // A busy direction can spend the task's budget before the timer is polled.
        () = tokio::task::unconstrained(cutoff) => Err(io::ErrorKind::TimedOut.into()),
    }
}

/// Copies `rd` to `wr` until `rd`'s FIN, then flushes `wr` and shuts its write side down. The FIN
/// begins the drain before the final flush and shutdown, which may block. Each byte read or
/// written is progress, unless the drain's deadline has passed: then it fails the copy first.
async fn copy_way<R, W>(rd: &mut R, wr: &mut W, drain: &Drain) -> io::Result<()>
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
            drain.begin(Instant::now());
            break;
        }
        drain.moved()?;
        let mut rest = &buf[..n];
        while !rest.is_empty() {
            let written = wr.write(rest).await?;
            if written == 0 {
                return Err(io::ErrorKind::WriteZero.into());
            }
            drain.moved()?;
            rest = &rest[written..];
        }
        unflushed = true;
    }
    wr.flush().await?;
    wr.shutdown().await
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tunnel::flow::{self, DEPTH, Ends, Key, Waker};
    use crate::tunnel::tcp::DRAIN_DEADLINE;
    use bytes::Bytes;
    use std::time::Duration;
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
        let (mut ends, mut io) = flow::pair(Waker::new(Key::Udp(0), events), DRAIN_DEADLINE);
        let up = ends.up.take().unwrap();
        up.try_send(Bytes::from_static(request)).unwrap();
        drop(up);
        let (mut upstream, server) = duplex(CHUNK);
        let drain = io.drain();
        let relay = tokio::spawn(async move { splice(&mut io, &mut upstream, &drain).await });
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
        let (mut ends, mut io) = flow::pair(Waker::new(Key::Udp(0), events), DRAIN_DEADLINE);
        let drain = io.drain();
        // The upstream never reads, and one chunk fills its buffer.
        let (mut upstream, _server) = duplex(CHUNK);
        let relay = tokio::spawn(async move { splice(&mut io, &mut upstream, &drain).await });
        // One chunk fills the upstream, the next blocks the relay's write, DEPTH more queue up.
        let up = ends.up.take().unwrap();
        for _ in 0..DEPTH + 2 {
            up.send(Bytes::from(vec![7; CHUNK])).await.unwrap();
        }
        // What the driver does when the client's FIN arrives behind them.
        drop(up);
        let fin_at = Instant::now();
        ends.drain.begin(fin_at);
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

    /// The driver records the client's FIN; the process stops before the relay runs again. The
    /// drain counts from the FIN the driver saw, so the relay cuts the flow as soon as it runs.
    #[tokio::test(start_paused = true)]
    async fn a_stop_between_the_fin_and_the_relay_does_not_extend_the_drain() {
        let (events, _) = mpsc::unbounded_channel();
        let (ends, mut io) = flow::pair(Waker::new(Key::Udp(0), events), DRAIN_DEADLINE);
        let drain = io.drain();
        let (mut upstream, _server) = duplex(CHUNK);
        let relay = tokio::spawn(async move { splice(&mut io, &mut upstream, &drain).await });
        tokio::task::yield_now().await;
        // What the driver does when it sees the client's FIN.
        ends.drain.begin(Instant::now());
        // The process is stopped: the clock jumps before the relay runs.
        tokio::time::advance(2 * DRAIN_DEADLINE).await;
        let ended = tokio::time::timeout(LATE, relay).await;
        assert!(
            matches!(ended, Ok(Ok(Err(ref e))) if e.kind() == io::ErrorKind::TimedOut),
            "{ended:?}"
        );
    }

    /// Data that arrives while the process is stopped past the deadline does not revive the flow:
    /// the deadline is checked before the read counts as progress.
    #[tokio::test(start_paused = true)]
    async fn data_that_arrives_during_a_stop_does_not_revive_an_expired_flow() {
        let (_ends, mut server, relay) = spliced(b"GET");
        let mut request = Vec::new();
        server.read_to_end(&mut request).await.unwrap();
        // The process is stopped: the upstream sends, and the clock passes the deadline.
        server.write_all(b"late").await.unwrap();
        tokio::time::advance(2 * DRAIN_DEADLINE).await;
        let ended = tokio::time::timeout(LATE, relay).await;
        assert!(
            matches!(ended, Ok(Ok(Err(ref e))) if e.kind() == io::ErrorKind::TimedOut),
            "{ended:?}"
        );
    }
}
