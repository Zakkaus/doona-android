//! TCP flows: the driver's side of each accepted connection, and the task that serves it, either
//! by dialing the original destination through the flow's route or by answering DNS itself.

use super::flow::{CHUNK, Ends, Event, Events, FlowIo, Waker};
use super::route::Route;
use crate::dns;
use bytes::{Buf, Bytes};
use honk_netstack::{FlowId, Stack, StreamError};
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::sync::mpsc::error::{TryRecvError, TrySendError};
use tokio_util::sync::CancellationToken;

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
    /// The task ended cleanly: it read the client's FIN and closed its side of `down`.
    pub finished: bool,
}

pub(crate) enum Step {
    Keep,
    /// Both directions are done: release the handle.
    Close,
    /// The stack side failed: release the handle and end the task.
    Failed,
}

impl TcpFlow {
    pub fn new(ends: Ends, waker: Arc<Waker>, connected: bool) -> TcpFlow {
        TcpFlow {
            ends,
            waker,
            cancel: CancellationToken::new(),
            up_pending: None,
            up_eof: false,
            down_pending: None,
            down_shut: false,
            connected,
            finished: false,
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
                Err(TryRecvError::Disconnected) => {
                    stack.tcp_shutdown(id)?;
                    self.down_shut = true;
                }
            }
        }
    }
}

/// Dials `dst` through `route` and splices the two streams until both sides close.
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
                tokio::io::copy_bidirectional_with_sizes(&mut io, &mut upstream, CHUNK, CHUNK)
                    .await
                    .is_err()
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

/// Answers the framed DNS queries of a TCP/53 connection to the virtual resolver.
pub(crate) async fn serve_dns(
    mut io: FlowIo,
    id: FlowId,
    route: Route,
    upstreams: Arc<[SocketAddr]>,
    events: Events,
    cancel: CancellationToken,
) {
    let work = async {
        let failed = dns::serve_tcp(&mut io, &upstreams, route).await.is_err();
        drop(io);
        let _ = events.send(Event::Finished { id, failed });
    };
    tokio::select! {
        () = cancel.cancelled() => {}
        () = work => {}
    }
}
