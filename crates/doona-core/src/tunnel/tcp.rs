//! TCP flows: the driver's side of each accepted connection, and the task that serves it, either
//! by dialing the original destination through the flow's route or by answering DNS itself.

use super::Session;
use super::flow::{Ends, Event, Events, FlowIo, Waker};
use super::gate::Gate;
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

/// Once either side's FIN has arrived, a flow may go this long without a byte read or written in
/// either direction before it is cut (see `gate.rs`). honk's `DRAIN_DEADLINE` (honk
/// 7cd8b13c). A TCP/53 connection to the core's DNS may idle as long, from accept on (see
/// [`Task::Dns`](super::gate::Task::Dns)).
pub(crate) const DRAIN_DEADLINE: Duration = Duration::from_secs(30);

/// The driver's state for one accepted connection.
pub(crate) struct TcpFlow {
    ends: Ends,
    pub waker: Arc<Waker>,
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
    /// The client's FIN was seen and given to the gate.
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
        let gate = self.ends.gate.read();
        if gate.ended && self.down_shut && self.down_pending.is_none() {
            Some(Step::Close)
        } else if gate.expired {
            Some(Step::Expired)
        } else {
            None
        }
    }

    /// The drain deadline for the driver to queue in its timer: once the drain has begun, unless
    /// the flow waits for an entry no later already.
    pub fn due(&mut self) -> Option<Instant> {
        let at = self.ends.gate.deadline()?;
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

    /// Gives the flow up: no I/O of its task runs once this returns (see `Gate::give_up`), and the
    /// task ends.
    pub fn give_up(&self) {
        self.ends.gate.give_up();
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
            self.ends.gate.fin();
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
                        self.ends.gate.moved();
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

/// Runs a flow's task until it ends, or until the driver gives the flow up. The gate refuses the
/// task's I/O from the give-up on; the task itself is dropped at its next poll, the give-up checked
/// first (tokio-util's `run_until_cancelled` prefers the task).
pub(super) async fn until_given_up<F: Future>(gate: &Gate, task: F) -> Option<F::Output> {
    tokio::select! {
        biased;
        () = gate.given_up() => None,
        out = task => Some(out),
    }
}

/// Dials `dst` through `route` and splices the two streams (see [`splice`]); both sides close when
/// the splice ends. The relay's last shutdown publishes its clean end in the gate, so the driver
/// releases the flow rather than resets it, however late this task runs again or its event comes.
pub(crate) async fn dial(io: FlowIo, id: FlowId, dst: SocketAddr, route: Route, events: Events) {
    let gate = io.gate();
    let work = async {
        let failed = match route.connect(dst).await {
            Ok(mut upstream) => {
                let _ = events.send(Event::Connected(id));
                let mut io = io;
                splice(&mut io, &mut upstream, &gate).await.is_err()
            }
            Err(_) => {
                drop(io);
                true
            }
        };
        let _ = events.send(Event::Finished { id, failed });
    };
    until_given_up(&gate, work).await;
}

/// Answers the framed DNS queries of a TCP/53 connection to the virtual resolver; `session`'s
/// names learn what the answers map. The driver bounds the session from accept: it is reset once
/// it has moved nothing for its idle time (see `dns::serve_tcp`). The shutdown that passes the FIN
/// on publishes the clean end in the gate, as the relay's does.
pub(crate) async fn serve_dns(
    mut io: FlowIo,
    id: FlowId,
    (route, session): (Route, Arc<Session>),
    upstreams: Arc<[SocketAddr]>,
    events: Events,
) {
    let gate = io.gate();
    let work = async {
        let via = (&route, session.names());
        let failed = dns::serve_tcp(&mut io, &upstreams, via).await.is_err();
        drop(io);
        #[cfg(test)]
        super::pause::at(super::pause::Point::Served).await;
        let _ = events.send(Event::Finished { id, failed });
    };
    until_given_up(&gate, work).await;
}

#[cfg(test)]
mod tests;
