//! A TCP flow's gate, and the driver's timer for every drain. The gate orders what the flow's task
//! does against what the driver decides: every non-blocking I/O poll of the task on its two streams
//! (the client's channels and the upstream) runs inside the gate's lock, and so do the driver's
//! readings and its give-up. Under the lock, with the clock read there, a poll is refused once the
//! driver gave the flow up or the drain's deadline has passed; otherwise it runs, and what it did
//! (a byte moved, a FIN read, a side shut) is recorded before anyone else looks.
//!
//! From the first FIN either way, a flow may go `idle` without a byte moved before it is cut. The
//! driver alone decides: it queues each begun drain's deadline in its one timer ([`Drains`]), and
//! in one place (`TcpFlow::verdict`) releases a flow whose task ended cleanly with its tail handed
//! to the stack, or else resets one whose deadline has passed. The task never reads the deadline:
//! past it, its I/O waits for the driver's reset.

use super::flow::Waker;
use crate::api::lock;
use honk_netstack::FlowId;
use std::cmp::Ordering;
use std::collections::BinaryHeap;
use std::io;
use std::pin::Pin;
use std::sync::{Arc, Mutex, MutexGuard};
use std::task::{Context, Poll};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::time::Instant;
use tokio_util::sync::CancellationToken;

/// What serves a TCP flow, as its gate needs to know.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Task {
    /// A relay to the dialed destination: it ends cleanly once it shut both the client's side and
    /// the upstream's.
    Relay,
    /// The core's DNS: it ends cleanly once it shut the client's side.
    Dns,
}

/// One flow's gate, shared by the driver and the task.
pub(crate) struct Gate {
    idle: Duration,
    /// Shutdowns that make a clean end.
    ways: u8,
    /// Wakes the driver when the drain begins, so that it queues the deadline.
    waker: Arc<Waker>,
    /// Wakes the task once the driver gave the flow up.
    cancel: CancellationToken,
    state: Mutex<State>,
}

#[derive(Default)]
struct State {
    /// When the drain began: the first FIN either way.
    begun: Option<Instant>,
    /// When a byte last moved.
    moved: Option<Instant>,
    /// Sides the task shut cleanly, each with its FIN passed on.
    shut: u8,
    /// The driver gave the flow up: no I/O of the task runs again.
    given_up: bool,
}

impl State {
    fn deadline(&self, idle: Duration) -> Option<Instant> {
        let begun = self.begun?;
        Some(self.moved.map_or(begun, |moved| moved.max(begun)) + idle)
    }
}

/// The gate as the driver reads it at one instant.
#[derive(Clone, Copy, Debug)]
pub(crate) struct Reading {
    /// The task ended cleanly.
    pub ended: bool,
    /// The deadline has passed.
    pub expired: bool,
}

impl Gate {
    pub fn new(idle: Duration, waker: Arc<Waker>, task: Task) -> Gate {
        let ways = match task {
            Task::Relay => 2,
            Task::Dns => 1,
        };
        Gate {
            idle,
            ways,
            waker,
            cancel: CancellationToken::new(),
            state: Mutex::default(),
        }
    }

    /// The client's FIN, seen now by the driver. The first FIN either way begins the drain and
    /// wakes the driver to queue its deadline; a later one changes nothing, so the deadline never
    /// comes earlier after that.
    pub fn fin(&self) {
        let begun = {
            let mut state = lock(&self.state);
            let now = Instant::now();
            begin(&mut state, now)
        };
        if begun {
            self.waker.wake();
        }
    }

    /// The driver's stack took a byte of the flow now. Past the deadline it counts for nothing: a
    /// write after a stop of the process cannot revive a flow whose time ran out during it.
    pub fn moved(&self) {
        let mut state = lock(&self.state);
        let now = Instant::now();
        if state
            .deadline(self.idle)
            .is_none_or(|deadline| now < deadline)
        {
            state.moved = Some(now);
        }
    }

    /// When the flow is cut unless a byte moves first; `None` before the drain begins.
    pub fn deadline(&self) -> Option<Instant> {
        lock(&self.state).deadline(self.idle)
    }

    pub fn read(&self) -> Reading {
        let state = lock(&self.state);
        let deadline = state.deadline(self.idle);
        Reading {
            ended: state.shut >= self.ways,
            expired: deadline.is_some_and(|deadline| Instant::now() >= deadline),
        }
    }

    /// The driver gives the flow up: it waits for an I/O poll of the task in progress, and none
    /// begins after this returns. Then the task is woken to end.
    pub fn give_up(&self) {
        lock(&self.state).given_up = true;
        self.cancel.cancel();
    }

    /// Completes once the driver gave the flow up.
    pub async fn given_up(&self) {
        self.cancel.cancelled().await;
    }

    /// The gate for one I/O poll of the task: `None`, so no I/O, once the flow is given up or past
    /// its deadline.
    fn enter(&self) -> Option<Entry<'_>> {
        let state = lock(&self.state);
        let now = Instant::now();
        let expired = state.deadline(self.idle).is_some_and(|at| now >= at);
        if state.given_up || expired {
            return None;
        }
        Some(Entry {
            state,
            now,
            begun: false,
            waker: &self.waker,
        })
    }

    /// Reads into `buf` with `poll`, inside the gate: a byte read is progress, the end of the
    /// stream a FIN.
    pub fn poll_read(
        &self,
        buf: &mut ReadBuf<'_>,
        poll: impl FnOnce(&mut ReadBuf<'_>) -> Poll<io::Result<()>>,
    ) -> Poll<io::Result<()>> {
        let Some(mut entry) = self.enter() else {
            return Poll::Pending;
        };
        let (before, room) = (buf.filled().len(), buf.remaining() > 0);
        let polled = poll(buf);
        if let Poll::Ready(Ok(())) = polled {
            if buf.filled().len() > before {
                entry.moved();
            } else if room {
                entry.fin();
            }
        }
        polled
    }

    /// Writes with `poll`, inside the gate: a byte written is progress.
    pub fn poll_write(
        &self,
        poll: impl FnOnce() -> Poll<io::Result<usize>>,
    ) -> Poll<io::Result<usize>> {
        let Some(mut entry) = self.enter() else {
            return Poll::Pending;
        };
        let polled = poll();
        if let Poll::Ready(Ok(1..)) = polled {
            entry.moved();
        }
        polled
    }

    /// Flushes with `poll`, inside the gate.
    pub fn poll_flush(&self, poll: impl FnOnce() -> Poll<io::Result<()>>) -> Poll<io::Result<()>> {
        match self.enter() {
            Some(_entry) => poll(),
            None => Poll::Pending,
        }
    }

    /// Shuts a side down with `poll`, inside the gate: the last side shut ends the task cleanly
    /// in the same critical section, so the driver never sees that FIN passed on before the end.
    pub fn poll_shutdown(
        &self,
        poll: impl FnOnce() -> Poll<io::Result<()>>,
    ) -> Poll<io::Result<()>> {
        let Some(mut entry) = self.enter() else {
            return Poll::Pending;
        };
        let polled = poll();
        if let Poll::Ready(Ok(())) = polled {
            entry.state.shut += 1;
        }
        polled
    }
}

/// The first FIN either way begins the drain.
fn begin(state: &mut State, now: Instant) -> bool {
    let first = state.begun.is_none();
    if first {
        state.begun = Some(now);
    }
    first
}

/// The gate held for one I/O poll of the task, with the clock read inside it.
struct Entry<'a> {
    state: MutexGuard<'a, State>,
    now: Instant,
    /// The poll began the drain: the driver is woken to queue the deadline.
    begun: bool,
    waker: &'a Waker,
}

impl Entry<'_> {
    fn moved(&mut self) {
        self.state.moved = Some(self.now);
    }

    fn fin(&mut self) {
        self.begun = begin(&mut self.state, self.now);
    }
}

impl Drop for Entry<'_> {
    fn drop(&mut self) {
        if self.begun {
            self.waker.wake();
        }
    }
}

/// A stream of the task's other than the client's, its upstream, through the flow's gate.
pub(super) struct Gated<'a, T: ?Sized> {
    pub gate: &'a Gate,
    pub io: &'a mut T,
}

impl<T: AsyncRead + Unpin + ?Sized> AsyncRead for Gated<'_, T> {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        let io = &mut *this.io;
        this.gate
            .poll_read(buf, |buf| Pin::new(io).poll_read(cx, buf))
    }
}

impl<T: AsyncWrite + Unpin + ?Sized> AsyncWrite for Gated<'_, T> {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        data: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        let io = &mut *this.io;
        this.gate.poll_write(|| Pin::new(io).poll_write(cx, data))
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        let io = &mut *this.io;
        this.gate.poll_flush(|| Pin::new(io).poll_flush(cx))
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        let io = &mut *this.io;
        this.gate.poll_shutdown(|| Pin::new(io).poll_shutdown(cx))
    }
}

/// The driver's one timer for the drains of its flows: each deadline a flow queued (see
/// `TcpFlow::due`), earliest first. The driver drops an entry its flow no longer waits for, as the
/// flow is gone or queued another, once it is the earliest (see `Driver::expire_drains`).
#[derive(Default)]
pub(crate) struct Drains(BinaryHeap<Due>);

impl Drains {
    pub fn push(&mut self, at: Instant, id: FlowId) {
        self.0.push(Due(at, id));
    }

    /// The earliest entry.
    pub fn peek(&self) -> Option<(Instant, FlowId)> {
        self.0.peek().map(|&Due(at, id)| (at, id))
    }

    pub fn pop(&mut self) {
        self.0.pop();
    }

    /// The earliest deadline queued.
    pub fn next(&self) -> Option<Instant> {
        self.0.peek().map(|due| due.0)
    }
}

/// A queued deadline; the earliest is the heap's greatest.
#[derive(Clone, Copy)]
struct Due(Instant, FlowId);

impl Ord for Due {
    fn cmp(&self, other: &Due) -> Ordering {
        other.0.cmp(&self.0)
    }
}

impl PartialOrd for Due {
    fn partial_cmp(&self, other: &Due) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl PartialEq for Due {
    fn eq(&self, other: &Due) -> bool {
        self.0 == other.0
    }
}

impl Eq for Due {}
