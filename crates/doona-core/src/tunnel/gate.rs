//! A TCP flow's gate, and the driver's timer for every drain. The gate orders what the flow's task
//! does against what the driver decides, and the driver never waits on it. Every non-blocking I/O
//! poll of the task on its two streams (the client's channels and the upstream) runs between an
//! entry and an exit, each one atomic step on the gate's word, which the driver's give-up changes
//! too: no poll enters once the driver gave the flow up or the drain's deadline has passed, and
//! what a poll did (a byte moved, a FIN read, a side shut) is recorded by its exit.
//!
//! A poll may take long: its outbound may wait for a lock (a Hysteria2 write takes Quinn's
//! connection lock), or its thread may be descheduled. Only its own flow waits for it. The driver
//! reads the gate without a lock, and puts off a decision that a poll in progress could change (the
//! deadline has passed, but the poll may yet move a byte or end the task) until that poll's exit
//! wakes it. So with a flow given up in the middle of a poll: the give-up stops new polls at once,
//! and the driver resets or releases the flow once the poll in progress has ended.
//!
//! From the first FIN either way (for the core's DNS, from accept), a flow may go `idle` without a
//! byte moved before it is cut; before that, nothing reads the clock for it. The driver alone
//! decides: it queues each begun drain's deadline in its one timer ([`Drains`]), and in one place
//! (`TcpFlow::verdict`) releases a flow whose task ended cleanly with its tail handed to the
//! stack, or else resets one whose deadline has passed. The task never reads the deadline: past
//! it, its I/O waits for the driver's reset.

use super::flow::Waker;
use honk_netstack::FlowId;
use std::cmp::Ordering;
use std::collections::BinaryHeap;
use std::io;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::Ordering::{AcqRel, Acquire, Relaxed, Release};
use std::sync::atomic::{AtomicU32, AtomicU64};
use std::task::{Context, Poll};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::time::Instant;
use tokio_util::sync::CancellationToken;

/// A poll of the task is in progress.
const BUSY: u32 = 1;
/// The driver waits for that poll to end: the poll's exit wakes it.
const WANTED: u32 = 1 << 1;
/// The driver gave the flow up: no poll enters again.
const GIVEN_UP: u32 = 1 << 2;
/// One side the task shut cleanly, with its FIN passed on: the bits from here count them.
const SHUT: u32 = 1 << 3;

/// What serves a TCP flow, as its gate needs to know.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Task {
    /// A relay to the dialed destination: the drain begins at the first FIN either way, and the
    /// task ends cleanly once it shut both the client's side and the upstream's.
    Relay,
    /// The core's DNS: the drain begins at accept, so a session that moves nothing for its idle
    /// time is reset whether or not the client sent its FIN, the time spent resolving included;
    /// the task ends cleanly once it shut the client's side.
    Dns,
}

/// One flow's gate, shared by the driver and the task.
pub(crate) struct Gate {
    /// The idle time, in nanoseconds.
    idle: u64,
    /// Shutdowns that make a clean end.
    ways: u32,
    /// Wakes the driver when the drain begins, so that it queues the deadline, and when a poll it
    /// waits for ends.
    waker: Arc<Waker>,
    /// Wakes the task once the driver gave the flow up.
    cancel: CancellationToken,
    /// The flags above and the count of shutdowns, in one word: each change either side makes is
    /// one atomic step, ordered against the other side's.
    word: AtomicU32,
    /// The gate's clock starts here: `begun`, `moved` and `took` are nanoseconds since, plus one;
    /// zero is none.
    epoch: Instant,
    /// When the drain began: the first FIN either way, or accept.
    begun: AtomicU64,
    /// When the task last moved a byte, once the drain had begun; earlier ones never matter. The task alone writes it, so a store keeps it in order.
    moved: AtomicU64,
    /// When the driver's stack last took a byte of the flow. The driver alone writes it.
    took: AtomicU64,
}

/// The gate as the driver reads it at one instant.
#[derive(Clone, Copy, Debug)]
pub(crate) struct Reading {
    /// The task ended cleanly.
    pub ended: bool,
    /// The deadline has passed, and no poll of the task was in progress.
    pub expired: bool,
    /// The deadline has passed while a poll of the task is in progress, which may yet move a byte
    /// or end the task: its exit wakes the driver to read again.
    pub busy: bool,
}

impl Gate {
    pub fn new(idle: Duration, waker: Arc<Waker>, task: Task) -> Gate {
        // The core's DNS begins its drain at accept: the epoch, stamped 1.
        let (ways, begun) = match task {
            Task::Relay => (2, 0),
            Task::Dns => (1, 1),
        };
        Gate {
            idle: nanos(idle),
            ways,
            waker,
            cancel: CancellationToken::new(),
            word: AtomicU32::new(0),
            epoch: Instant::now(),
            begun: AtomicU64::new(begun),
            moved: AtomicU64::new(0),
            took: AtomicU64::new(0),
        }
    }

    /// Now on the gate's clock.
    fn now(&self) -> u64 {
        nanos(self.epoch.elapsed()).saturating_add(1)
    }

    /// The deadline on the gate's clock; `None` before the drain begins.
    fn due(&self) -> Option<u64> {
        let begun = self.begun.load(Acquire);
        let moved = self.moved.load(Acquire).max(self.took.load(Acquire));
        (begun != 0).then(|| begun.max(moved).saturating_add(self.idle))
    }

    /// Whether the deadline has passed at `now`.
    fn past(&self, now: u64) -> bool {
        self.due().is_some_and(|due| now >= due)
    }

    /// The client's FIN, seen now by the driver. The first FIN either way begins the drain and
    /// wakes the driver to queue its deadline; a later one changes nothing, so the deadline never
    /// comes earlier after that.
    pub fn fin(&self) {
        if self.begun.load(Acquire) == 0 && self.begin(self.now()) {
            self.waker.wake();
        }
    }

    /// Begins the drain at `now` unless it has begun: true for the first FIN.
    fn begin(&self, now: u64) -> bool {
        self.begun.compare_exchange(0, now, AcqRel, Acquire).is_ok()
    }

    /// The driver's stack took a byte of the flow now. Before the drain begins it changes nothing,
    /// and past the deadline it counts for nothing: a write after a stop of the process cannot
    /// revive a flow whose time ran out during it.
    pub fn moved(&self) {
        if self.begun.load(Acquire) == 0 {
            return;
        }
        let now = self.now();
        if !self.past(now) {
            self.took.store(now, Release);
        }
    }

    /// When the flow is cut unless a byte moves first, for the driver's timer: `None` before the
    /// drain begins.
    pub fn deadline(&self) -> Option<Instant> {
        let due = self.due()?;
        Some(self.epoch + Duration::from_nanos(due - 1))
    }

    /// The gate as the driver reads it now, without waiting (see [`Reading`]). The clock is read
    /// only once the drain has begun.
    pub fn read(&self) -> Reading {
        let mut word = self.word.load(Acquire);
        loop {
            let ended = word / SHUT >= self.ways;
            let past = self.begun.load(Acquire) != 0 && self.past(self.now());
            if !past || word & BUSY == 0 {
                return Reading {
                    ended,
                    expired: past,
                    busy: false,
                };
            }
            // The poll in progress wakes the driver as it exits; one that has just exited is read
            // again.
            match self
                .word
                .compare_exchange(word, word | WANTED, AcqRel, Acquire)
            {
                Ok(_) => {
                    return Reading {
                        ended,
                        expired: false,
                        busy: true,
                    };
                }
                Err(now) => word = now,
            }
        }
    }

    /// The driver gives the flow up: no poll of the task enters from now on, and the task is woken
    /// to end. True when no poll is in progress either, so that the driver may reset or release
    /// the flow now; otherwise the poll in progress wakes the driver as it exits, and the driver
    /// gives the flow up again then.
    pub fn give_up(&self) -> bool {
        let word = self.word.fetch_or(GIVEN_UP | WANTED, AcqRel);
        self.cancel.cancel();
        word & BUSY == 0
    }

    /// Completes once the driver gave the flow up.
    pub async fn given_up(&self) {
        self.cancel.cancelled().await;
    }

    /// The gate for one I/O poll of the task: `None`, so no I/O, once the flow is given up or past
    /// its deadline. A refused entry changes nothing the driver reads.
    fn enter(&self) -> Option<Entry<'_>> {
        let mut word = self.word.load(Relaxed);
        loop {
            debug_assert_eq!(word & BUSY, 0, "a poll of the task inside another");
            if word & GIVEN_UP != 0 {
                return None;
            }
            match self
                .word
                .compare_exchange_weak(word, word | BUSY, Acquire, Relaxed)
            {
                Ok(_) => break,
                Err(now) => word = now,
            }
        }
        let mut entry = Entry {
            gate: self,
            now: None,
            shut: false,
            began: false,
        };
        // Before the drain begins nothing is cut, and a byte moved changes no deadline.
        if self.begun.load(Acquire) != 0 {
            let now = self.now();
            if self.past(now) {
                return None;
            }
            entry.now = Some(now);
        }
        Some(entry)
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
        let Some(entry) = self.enter() else {
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

    /// Shuts a side down with `poll`, inside the gate: the last side shut ends the task cleanly in
    /// the step that ends the poll, so the driver never sees the task's end before the poll's.
    pub fn poll_shutdown(
        &self,
        poll: impl FnOnce() -> Poll<io::Result<()>>,
    ) -> Poll<io::Result<()>> {
        let Some(mut entry) = self.enter() else {
            return Poll::Pending;
        };
        let polled = poll();
        if let Poll::Ready(Ok(())) = polled {
            entry.shut = true;
        }
        polled
    }
}

/// `d` in nanoseconds, held far below the end of the gate's clock.
fn nanos(d: Duration) -> u64 {
    u64::try_from(d.as_nanos()).unwrap_or(u64::MAX >> 2)
}

/// One I/O poll of the task, inside the gate; dropped, it exits.
struct Entry<'a> {
    gate: &'a Gate,
    /// The clock at entry, read once the drain has begun.
    now: Option<u64>,
    /// The poll shut a side cleanly.
    shut: bool,
    /// The poll began the drain: the driver is woken to queue the deadline.
    began: bool,
}

impl Entry<'_> {
    /// A byte moved: once the drain has begun, the deadline counts from this poll's entry.
    fn moved(&self) {
        if let Some(now) = self.now {
            self.gate.moved.store(now, Release);
        }
    }

    /// The end of a stream: the first FIN either way begins the drain.
    fn fin(&mut self) {
        let now = self.now.unwrap_or_else(|| self.gate.now());
        self.began = self.gate.begin(now);
    }
}

impl Drop for Entry<'_> {
    fn drop(&mut self) {
        let word = &self.gate.word;
        // One step clears BUSY, which is set, and adds a side shut: the last one ends the task
        // cleanly in the step that ends the poll.
        let shut = if self.shut { SHUT } else { 0 };
        let before = word.fetch_add(shut.wrapping_sub(BUSY), AcqRel);
        if before & WANTED != 0 {
            // Only the driver waiting for this poll sets it, and only while the poll is in
            // progress (a give-up sets it any time, but then no poll enters again).
            word.fetch_and(!WANTED, AcqRel);
        }
        if before & WANTED != 0 || self.began {
            self.gate.waker.wake();
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
