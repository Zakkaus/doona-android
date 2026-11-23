//! TCP flows: the driver's side of each accepted connection, and the task that serves it, either
//! by dialing the original destination through the flow's route or by answering DNS itself.

use super::Session;
use super::flow::{Ends, Event, Events, FlowIo, Waker};
use super::route::Route;
use super::splice::splice;
use crate::dns;
use bytes::{Buf, Bytes};
use honk_netstack::{FlowId, Stack, StreamError, TcpState};
use std::future::Future;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc::error::{TryRecvError, TrySendError};
use tokio::time::Instant;
use tokio_util::sync::CancellationToken;

/// Once either side's FIN has arrived, a flow may go this long without a byte read or written in
/// either direction before it is cut (see `drain.rs`). honk's `DRAIN_DEADLINE` (honk
/// 7cd8b13c). A TCP/53 connection to the core's DNS may idle as long (see `dns::serve_tcp`).
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
    /// The client's FIN was seen and given to the drain.
    client_fin: bool,
    /// The drain deadline queued in the driver's timer for this flow (see [`TcpFlow::due`]).
    timer: Option<Instant>,
    /// Served by the core's DNS, not an app's connection: not counted in `activeFlows`.
    pub dns: bool,
}

pub(crate) enum Step {
    Keep,
    /// The task ended cleanly and the stack has the tail and the FIN: release the handle.
    Close,
    /// The stack side failed: release the handle and end the task.
    Failed,
    /// The drain's deadline passed: reset the client and end the task.
    Expired,
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
            client_fin: false,
            timer: None,
            dns,
        }
    }

    /// Moves what can move in both directions without blocking, unless the flow is settled (see
    /// [`TcpFlow::verdict`]): before any I/O, so that nothing moves after the deadline, and after.
    pub fn pump(&mut self, stack: &mut Stack, id: FlowId, scratch: &mut [u8]) -> Step {
        if let Some(step) = self.verdict() {
            return step;
        }
        if self.pump_up(stack, id, scratch).is_err() || self.pump_down(stack, id).is_err() {
            return Step::Failed;
        }
        self.verdict().unwrap_or(Step::Keep)
    }

    /// What becomes of the flow, decided here alone, release before reset: a flow whose task ended
    /// cleanly, with its tail and FIN handed to the stack, is released, however late the driver
    /// hears that the task ended; otherwise one whose drain deadline has passed is reset, a tail
    /// still in the channel included. `None` while neither holds.
    pub fn verdict(&self) -> Option<Step> {
        let drain = self.ends.drain.read();
        if drain.ended && self.down_shut && self.down_pending.is_none() {
            Some(Step::Close)
        } else if drain.expired {
            Some(Step::Expired)
        } else {
            None
        }
    }

    /// The drain deadline for the driver to queue in its timer: once the drain has begun, unless
    /// the flow waits for an entry no later already.
    pub fn due(&mut self) -> Option<Instant> {
        let at = self.ends.drain.deadline()?;
        if self.timer.is_some_and(|queued| queued <= at) {
            return None;
        }
        self.timer = Some(at);
        Some(at)
    }

    /// Whether the flow waits for its entry for `at` in the driver's timer.
    pub fn waits_for(&self, at: Instant) -> bool {
        self.timer == Some(at)
    }

    /// The driver's timer reached `at` for this flow: false when the flow no longer waits for it.
    pub fn fires(&mut self, at: Instant) -> bool {
        let fires = self.waits_for(at);
        if fires {
            self.timer = None;
        }
        fires
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
        if !self.client_fin
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
            // the drain counts from now all the same, however late the task learns of it.
            self.client_fin = true;
            self.ends.drain.fin();
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
                        // Progress, though the chunk is not done: a slow client keeps the flow.
                        self.ends.drain.moved();
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

/// Runs a flow's task until it ends, or until the driver gives the flow up. Cancellation first:
/// once the flow is given up, none of the task's I/O runs again, though it be ready at the same
/// time (tokio-util's `run_until_cancelled` prefers the task).
pub(super) async fn until_given_up<F: Future>(
    cancel: &CancellationToken,
    task: F,
) -> Option<F::Output> {
    tokio::select! {
        biased;
        () = cancel.cancelled() => None,
        out = task => Some(out),
    }
}

/// Dials `dst` through `route` and splices the two streams (see [`splice`]); both sides close when
/// the splice ends. A clean end goes to the drain before the driver hears of it, so that its timer
/// releases the flow rather than resets it, however late the event comes.
pub(crate) async fn dial(
    io: FlowIo,
    id: FlowId,
    dst: SocketAddr,
    route: Route,
    events: Events,
    cancel: CancellationToken,
) {
    let drain = io.drain();
    let work = async {
        let failed = match route.connect(dst).await {
            Ok(mut upstream) => {
                let _ = events.send(Event::Connected(id));
                let mut io = io;
                splice(&mut io, &mut upstream, &drain).await.is_err()
            }
            Err(_) => {
                drop(io);
                true
            }
        };
        if !failed {
            drain.ended();
        }
        let _ = events.send(Event::Finished { id, failed });
    };
    until_given_up(&cancel, work).await;
}

/// Answers the framed DNS queries of a TCP/53 connection to the virtual resolver; `session`'s
/// names learn what the answers map. One that sends no query or takes no answer for `idle` fails,
/// so the driver resets the client; after the client's FIN the drain bounds it as any flow.
pub(crate) async fn serve_dns(
    mut io: FlowIo,
    id: FlowId,
    (route, session): (Route, Arc<Session>),
    upstreams: Arc<[SocketAddr]>,
    idle: Duration,
    events: Events,
    cancel: CancellationToken,
) {
    let drain = io.drain();
    let work = async {
        let via = (&route, session.names());
        let failed = dns::serve_tcp(&mut io, &upstreams, via, idle)
            .await
            .is_err();
        drop(io);
        if !failed {
            drain.ended();
        }
        let _ = events.send(Event::Finished { id, failed });
    };
    until_given_up(&cancel, work).await;
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::stats::meter::Tally;
    use crate::tunnel::flow::{self, CHUNK, DEPTH, Key};
    use honk_netstack::{AcceptOpts, AcceptWhen, Config, ExcludeSet, Mode};
    use tokio::io::{AsyncReadExt, AsyncWriteExt, duplex};
    use tokio::sync::mpsc;

    /// How late a timer of the paused clock may fire: tokio's timers count whole milliseconds.
    const LATE: Duration = Duration::from_millis(10);

    /// Lets the relay fill the queue to the driver, and the driver move what the stack takes.
    async fn settle_all(wire: &mut Wire, flow: &mut TcpFlow, id: FlowId, scratch: &mut [u8]) {
        for _ in 0..32 {
            for _ in 0..4 {
                tokio::task::yield_now().await;
            }
            assert!(matches!(flow.pump(&mut wire.core, id, scratch), Step::Keep));
            wire.settle();
        }
    }

    /// After its FIN the client reads a little every two thirds of the deadline. The stack takes
    /// each piece from the chunk the driver holds, so the relay, waiting on the full queue, moves
    /// nothing all that time; the pieces count as progress, and the flow is due to be cut only a
    /// deadline after the client stops. The driver's reset then ends the relay.
    #[tokio::test(start_paused = true)]
    async fn a_client_that_reads_slowly_keeps_the_flow_in_the_middle_of_a_chunk() {
        let (mut wire, app, id) = Wire::connected();
        let (events, _) = mpsc::unbounded_channel();
        let waker = Waker::new(Key::Tcp(id), events);
        let (ends, mut io) = flow::pair(Arc::clone(&waker), Tally::new(None), DRAIN_DEADLINE);
        let mut flow = TcpFlow::new(ends, waker, false);
        flow.connected = true;
        let drain = io.drain();
        let (mut upstream, mut server) = duplex(CHUNK);
        let cancel = flow.cancel.clone();
        let relay = tokio::spawn(async move {
            until_given_up(&cancel, splice(&mut io, &mut upstream, &drain)).await
        });
        // The upstream sends more than the client, the stack and the queue hold, and never closes.
        tokio::spawn(async move {
            let _ = server.write_all(&vec![7; 4 << 20]).await;
            std::future::pending::<()>().await
        });
        wire.client.tcp_shutdown(app).unwrap();
        wire.settle();
        let mut scratch = vec![0; CHUNK];
        settle_all(&mut wire, &mut flow, id, &mut scratch).await;
        // Leave most of a chunk with the driver: the reads below never finish it.
        let held = flow.down_pending.as_ref().map_or(0, Bytes::len);
        if held < CHUNK / 2 {
            wire.take(app, held + 4096);
            settle_all(&mut wire, &mut flow, id, &mut scratch).await;
        }
        let mut held = flow.down_pending.as_ref().map_or(0, Bytes::len);
        assert!(held >= CHUNK / 2, "{held}");
        for _ in 0..3 {
            tokio::time::sleep(DRAIN_DEADLINE * 2 / 3).await;
            assert!(flow.verdict().is_none(), "a client that reads was cut");
            wire.take(app, 4096);
            assert!(matches!(
                flow.pump(&mut wire.core, id, &mut scratch),
                Step::Keep
            ));
            wire.settle();
            let now = flow.down_pending.as_ref().map_or(0, Bytes::len);
            assert!(now > 0 && now < held, "{now} of {held}");
            held = now;
        }
        let stopped = Instant::now();
        tokio::time::sleep_until(stopped + DRAIN_DEADLINE - LATE).await;
        assert!(flow.verdict().is_none(), "cut before the deadline");
        tokio::time::sleep_until(stopped + DRAIN_DEADLINE).await;
        assert!(matches!(flow.verdict(), Some(Step::Expired)));
        assert!(matches!(
            flow.pump(&mut wire.core, id, &mut scratch),
            Step::Expired
        ));
        assert!(!relay.is_finished(), "the relay never ends itself");
        flow.cancel.cancel();
        assert!(matches!(relay.await, Ok(None)));
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

        /// The app reads `n` bytes it has received, then the packets move.
        fn take(&mut self, app: FlowId, n: usize) {
            let mut buf = vec![0; n];
            let mut got = 0;
            while got < n {
                got += self.client.tcp_read(app, &mut buf[got..]).unwrap();
            }
            self.settle();
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
    /// in the stack: the drain begins all the same, from when the driver saw the FIN (see
    /// [`splice`]).
    #[test]
    fn the_clients_fin_reaches_the_task_while_the_data_before_it_waits() {
        let (mut wire, app, id) = Wire::connected();
        let (events, _) = mpsc::unbounded_channel();
        let waker = Waker::new(Key::Tcp(id), events);
        let (ends, io) = flow::pair(Arc::clone(&waker), Tally::new(None), DRAIN_DEADLINE);
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
        let seen = Instant::now();
        assert!(matches!(
            flow.pump(&mut wire.core, id, &mut scratch),
            Step::Keep
        ));
        let pumped = Instant::now();
        assert_eq!(flow.up_pending.as_deref(), Some(&b"GET"[..]));
        assert!(!flow.up_eof, "the FIN is still in the stack");
        let deadline = io.drain().deadline().expect("the drain has begun");
        assert!(seen + DRAIN_DEADLINE <= deadline && deadline <= pumped + DRAIN_DEADLINE);
    }

    /// The task passes the upstream's reply and FIN on and ends cleanly; the driver hands both to
    /// the stack, and hears of the end only after the drain's deadline (the process stopped). The
    /// tail is the client's: the flow is released, not reset, whether the driver's timer or the
    /// task's event comes first.
    #[tokio::test(start_paused = true)]
    async fn a_tail_handed_over_is_released_however_late_the_end_is_heard_of() {
        let (mut wire, app, id) = Wire::connected();
        let (events, _) = mpsc::unbounded_channel();
        let waker = Waker::new(Key::Tcp(id), events);
        let (ends, mut io) = flow::pair(Arc::clone(&waker), Tally::new(None), DRAIN_DEADLINE);
        let mut flow = TcpFlow::new(ends, waker, false);
        flow.connected = true;
        let mut scratch = vec![0; CHUNK];
        wire.client.tcp_write(app, b"GET").unwrap();
        wire.client.tcp_shutdown(app).unwrap();
        wire.settle();
        assert!(matches!(
            flow.pump(&mut wire.core, id, &mut scratch),
            Step::Keep
        ));
        let mut request = Vec::new();
        io.read_to_end(&mut request).await.unwrap();
        assert_eq!(request, b"GET");
        io.write_all(b"200").await.unwrap();
        io.shutdown().await.unwrap();
        let drain = io.drain();
        drop(io);
        assert!(matches!(
            flow.pump(&mut wire.core, id, &mut scratch),
            Step::Keep
        ));
        wire.settle();
        assert!(flow.down_shut && flow.down_pending.is_none());
        // What the task does as it ends, before it sends `Finished`.
        drain.ended();
        tokio::time::advance(2 * DRAIN_DEADLINE).await;
        // The driver's timer settles the flow without I/O, the event with a pump: either releases.
        assert!(matches!(flow.verdict(), Some(Step::Close)), "reset");
        let step = flow.pump(&mut wire.core, id, &mut scratch);
        assert!(matches!(step, Step::Close), "reset");
    }

    /// A query arrives as the driver gives the DNS flow up: the task does not answer it, though it
    /// could at once. A select without `biased` picks its first branch at random: 32 rounds.
    #[tokio::test]
    async fn a_flow_given_up_moves_nothing_more_though_its_io_is_ready() {
        let (_wire, _app, id) = Wire::connected();
        let session = Arc::new(Session::new(1, std::sync::Weak::new()));
        let mut answered = 0;
        for _ in 0..32 {
            let (events, _) = mpsc::unbounded_channel();
            let waker = Waker::new(Key::Tcp(id), events.clone());
            let (mut ends, io) = flow::pair(waker, Tally::new(None), DRAIN_DEADLINE);
            let cancel = CancellationToken::new();
            let via = (Route::Direct, Arc::clone(&session));
            let none: Arc<[SocketAddr]> = Arc::from([]);
            let task = tokio::spawn(serve_dns(
                io,
                id,
                via,
                none,
                DRAIN_DEADLINE,
                events,
                cancel.clone(),
            ));
            tokio::task::yield_now().await;
            let q = crate::dns::tests::query(7, "example.com", None);
            let mut framed = u16::try_from(q.len()).unwrap().to_be_bytes().to_vec();
            framed.extend_from_slice(&q);
            let up = ends.up.take().unwrap();
            up.try_send(Bytes::from(framed)).unwrap();
            cancel.cancel();
            task.await.unwrap();
            answered += usize::from(ends.down.try_recv().is_ok());
        }
        assert_eq!(answered, 0, "rounds answered after the cancel");
    }

    /// The task sees the first FIN, the upstream's: the drain wakes the driver, which queues the
    /// deadline once. A later FIN changes nothing and a byte moved puts the deadline off, so neither
    /// queues an entry; the one queued comes, finds the flow alive, and the later deadline is queued
    /// in its place.
    #[tokio::test(start_paused = true)]
    async fn the_first_fin_from_the_task_wakes_the_driver_to_queue_the_drain() {
        let (_wire, _app, id) = Wire::connected();
        let (events, mut woken) = mpsc::unbounded_channel();
        let waker = Waker::new(Key::Tcp(id), events);
        let (ends, io) = flow::pair(Arc::clone(&waker), Tally::new(None), DRAIN_DEADLINE);
        let mut flow = TcpFlow::new(ends, waker, false);
        assert_eq!(flow.due(), None, "no drain before a FIN");
        let drain = io.drain();
        drain.fin();
        assert!(matches!(woken.try_recv(), Ok(Event::Wake(Key::Tcp(k))) if k == id));
        flow.waker.rearm();
        let first = flow.due().unwrap();
        assert_eq!(first, Instant::now() + DRAIN_DEADLINE);
        assert_eq!(flow.due(), None, "queued once");
        tokio::time::sleep(DRAIN_DEADLINE / 2).await;
        drain.fin();
        drain.moved();
        assert!(woken.try_recv().is_err(), "woken for no earlier deadline");
        assert_eq!(flow.due(), None);
        tokio::time::sleep_until(first).await;
        assert!(flow.fires(first));
        assert!(flow.verdict().is_none());
        assert_eq!(flow.due(), Some(first + DRAIN_DEADLINE / 2));
        assert!(!flow.fires(first), "an entry the flow no longer waits for");
    }
}
