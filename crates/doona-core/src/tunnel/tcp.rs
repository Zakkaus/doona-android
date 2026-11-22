//! TCP flows: the driver's side of each accepted connection, and the task that serves it, either
//! by dialing the original destination through the flow's route or by answering DNS itself.

use super::Session;
use super::flow::{Ends, Event, Events, FlowIo, Waker};
use super::route::Route;
use super::splice::splice;
use crate::dns;
use bytes::{Buf, Bytes};
use honk_netstack::{FlowId, Stack, StreamError, TcpState};
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::mpsc::error::{TryRecvError, TrySendError};
use tokio::time::Instant;
use tokio_util::sync::CancellationToken;

/// Once either side's FIN has arrived, a flow may go this long without a byte read or written in
/// either direction before it is cut (see `flow::Drain`). honk's `DRAIN_DEADLINE` (honk
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
    /// The task ended cleanly: both directions ended, and it closed its side of `down`. The tail it
    /// left in `down` must reach the stack by the drain's deadline (see [`TcpFlow::finish`]).
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
            finished: false,
            dns,
        }
    }

    /// Moves what can move in both directions without blocking; once the drain's deadline has
    /// passed, nothing more.
    pub fn pump(&mut self, stack: &mut Stack, id: FlowId, scratch: &mut [u8]) -> Step {
        if self.ends.drain.expired() {
            return Step::Expired;
        }
        if self.pump_up(stack, id, scratch).is_err() || self.pump_down(stack, id).is_err() {
            return Step::Failed;
        }
        if self.finished && self.down_shut && self.down_pending.is_none() {
            Step::Close
        } else {
            Step::Keep
        }
    }

    /// The task ended cleanly. From now on the driver keeps the drain's deadline: by then the tail
    /// must be handed to the stack, or the flow is reset. Returns the deadline as it stands.
    pub fn finish(&mut self) -> Instant {
        self.finished = true;
        // Begun already: the task's directions ended at FINs.
        self.ends.drain.begin(Instant::now())
    }

    pub fn deadline(&self) -> Option<Instant> {
        self.ends.drain.deadline()
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
        if !self.ends.drain.has_begun()
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
            self.ends.drain.begin(Instant::now());
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
                        // `pump` has checked the deadline.
                        let _ = self.ends.drain.moved();
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
    events: Events,
    cancel: CancellationToken,
) {
    let work = async {
        let failed = match route.connect(dst).await {
            Ok(mut upstream) => {
                let _ = events.send(Event::Connected(id));
                let drain = io.drain();
                let mut io = io;
                splice(&mut io, &mut upstream, &drain).await.is_err()
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

/// Answers the framed DNS queries of a TCP/53 connection to the virtual resolver; `session`'s
/// names learn what the answers map. One that sends no query or takes no answer for `idle` fails,
/// so the driver resets the client.
pub(crate) async fn serve_dns(
    mut io: FlowIo,
    id: FlowId,
    (route, session): (Route, Arc<Session>),
    upstreams: Arc<[SocketAddr]>,
    idle: Duration,
    events: Events,
    cancel: CancellationToken,
) {
    let work = async {
        let via = (&route, session.names());
        let failed = dns::serve_tcp(&mut io, &upstreams, via, idle)
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
    use crate::stats::meter::Tally;
    use crate::tunnel::flow::{self, CHUNK, DEPTH, Key};
    use honk_netstack::{AcceptOpts, AcceptWhen, Config, ExcludeSet, Mode};
    use std::io;
    use tokio::io::{AsyncWriteExt, duplex};
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
    /// nothing all that time; the pieces count as progress, and the flow is cut only a deadline
    /// after the client stops.
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
        let relay = tokio::spawn(async move { splice(&mut io, &mut upstream, &drain).await });
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
            assert!(!relay.is_finished(), "a client that reads was cut");
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
        let ended = tokio::time::timeout(2 * DRAIN_DEADLINE, relay).await;
        assert!(
            matches!(ended, Ok(Ok(Err(ref e))) if e.kind() == io::ErrorKind::TimedOut),
            "{ended:?}"
        );
        let idle = stopped.elapsed();
        assert!(
            idle >= DRAIN_DEADLINE && idle <= DRAIN_DEADLINE + LATE,
            "{idle:?}"
        );
        assert!(matches!(
            flow.pump(&mut wire.core, id, &mut scratch),
            Step::Expired
        ));
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
        assert!(io.drain().has_begun());
        let deadline = io.drain().deadline().unwrap();
        assert!(seen + DRAIN_DEADLINE <= deadline && deadline <= pumped + DRAIN_DEADLINE);
    }
}
