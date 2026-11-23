//! A TCP flow's drain, and the driver's timer for every drain. From the first FIN either way, a
//! flow may go `idle` without a byte moved before it is cut. The driver alone decides: it queues
//! each begun drain's deadline in its one timer ([`Drains`]), and in one place
//! (`TcpFlow::verdict`) releases a flow whose task ended cleanly with its tail handed to the
//! stack, or else resets one whose deadline has passed. The flow's task only reports what it sees
//! (a FIN, a byte moved, its clean end) and never reads the deadline; the driver's reset cancels
//! it.

use super::flow::Waker;
use crate::api::lock;
use honk_netstack::FlowId;
use std::cmp::Ordering;
use std::collections::BinaryHeap;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::time::Instant;

/// One flow's drain, shared by the driver and the task. Every report and every reading takes the
/// clock under the one lock, so they are ordered: a byte counted before a reading moves the
/// deadline that reading sees, and a byte after a passed deadline counts for nothing.
pub(crate) struct Drain {
    idle: Duration,
    /// Wakes the driver when the drain begins, so that it queues the deadline.
    waker: Arc<Waker>,
    state: Mutex<State>,
}

#[derive(Default)]
struct State {
    /// When the first FIN either way was seen.
    fin: Option<Instant>,
    /// When a byte last moved.
    moved: Option<Instant>,
    /// The task ended cleanly: both directions ended, each FIN passed on.
    ended: bool,
}

impl State {
    fn deadline(&self, idle: Duration) -> Option<Instant> {
        let fin = self.fin?;
        Some(self.moved.map_or(fin, |moved| moved.max(fin)) + idle)
    }
}

/// A drain as the driver reads it at one instant.
#[derive(Clone, Copy, Debug)]
pub(crate) struct Reading {
    /// The task ended cleanly.
    pub ended: bool,
    /// The deadline has passed.
    pub expired: bool,
}

impl Drain {
    pub fn new(idle: Duration, waker: Arc<Waker>) -> Drain {
        Drain {
            idle,
            waker,
            state: Mutex::default(),
        }
    }

    /// A FIN, either way, seen now. The first begins the drain and wakes the driver to queue its
    /// deadline; a later one changes nothing, so the deadline never comes earlier after that.
    pub fn fin(&self) {
        let begun = {
            let mut state = lock(&self.state);
            let first = state.fin.is_none();
            if first {
                state.fin = Some(Instant::now());
            }
            first
        };
        if begun {
            self.waker.wake();
        }
    }

    /// A byte moved now. Past the deadline it counts for nothing: a read or write after a stop of
    /// the process cannot revive a flow whose time ran out during it.
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

    /// The task ended cleanly.
    pub fn ended(&self) {
        lock(&self.state).ended = true;
    }

    /// When the flow is cut unless a byte moves first; `None` before a FIN.
    pub fn deadline(&self) -> Option<Instant> {
        lock(&self.state).deadline(self.idle)
    }

    pub fn read(&self) -> Reading {
        let state = lock(&self.state);
        let deadline = state.deadline(self.idle);
        Reading {
            ended: state.ended,
            expired: deadline.is_some_and(|deadline| Instant::now() >= deadline),
        }
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
