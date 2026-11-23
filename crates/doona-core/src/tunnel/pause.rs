//! Points where a test holds a flow's task, as if the thread running it were descheduled there,
//! and meanwhile does what the driver would. Compiled into tests only. A hold covers the tasks of
//! the thread that set it, so a test on a current-thread runtime holds only its own. And an
//! upstream whose write holds the thread polling it (see [`stuck`]).

use super::flow::FlowIo;
use super::splice::splice;
use super::tcp::until_given_up;
use std::cell::RefCell;
use std::io;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::mpsc::{Receiver, Sender, channel};
use std::task::{Context, Poll};
use std::thread::JoinHandle;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::Notify;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub(crate) enum Point {
    /// The core's DNS waits for its resolver.
    Resolving,
    /// The core's DNS has an answer for the client, before it writes it.
    Answered,
    /// The core's DNS has served its session to the end, before its task reports how it ended.
    Served,
}

#[derive(Default)]
struct Hold {
    reached: Notify,
    released: Notify,
}

thread_local! {
    static HOLDS: RefCell<Vec<(Point, Arc<Hold>)>> = const { RefCell::new(Vec::new()) };
}

/// Holds each task of this thread that comes to a point, until the test lets it go; dropped, it
/// holds no more.
pub(crate) struct Held {
    point: Point,
    hold: Arc<Hold>,
}

/// Holds the tasks of this thread at `point`.
pub(crate) fn hold(point: Point) -> Held {
    let hold = Arc::new(Hold::default());
    HOLDS.with(|holds| holds.borrow_mut().push((point, Arc::clone(&hold))));
    Held { point, hold }
}

impl Held {
    /// Waits until a task comes to the point.
    pub async fn reached(&self) {
        self.hold.reached.notified().await;
    }

    /// Lets the task held there go on.
    pub fn release(&self) {
        self.hold.released.notify_one();
    }
}

impl Drop for Held {
    fn drop(&mut self) {
        let point = self.point;
        HOLDS.with(|holds| holds.borrow_mut().retain(|(p, _)| *p != point));
        self.hold.released.notify_one();
    }
}

/// A task comes to `point`: it waits there while a test holds the point.
pub(crate) async fn at(point: Point) {
    let hold = HOLDS.with(|holds| {
        let holds = holds.borrow();
        let found = holds.iter().find(|(p, _)| *p == point);
        found.map(|(_, hold)| Arc::clone(hold))
    });
    if let Some(hold) = hold {
        hold.reached.notify_one();
        hold.released.notified().await;
    }
}

/// An upstream that holds the thread polling it in the middle of a poll, until the test lets it go:
/// as if the poll waited for a lock of its outbound (a Hysteria2 write takes Quinn's connection
/// lock) or its thread were descheduled there. Every write takes all it is given.
pub(crate) struct Stuck {
    stall: Stall,
    entered: Option<Sender<()>>,
    release: Receiver<()>,
}

/// Where a [`Stuck`] upstream holds.
#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum Stall {
    /// In its first write; it never sends a byte or its FIN.
    Write,
    /// In its shutdown; it sends its FIN at once.
    Shutdown,
}

/// The test's end of a [`Stuck`] upstream.
pub(crate) struct Unstick {
    entered: Receiver<()>,
    release: Sender<()>,
}

pub(crate) fn stuck(stall: Stall) -> (Stuck, Unstick) {
    let (entered_tx, entered) = channel();
    let (release, release_rx) = channel();
    let stuck = Stuck {
        stall,
        entered: Some(entered_tx),
        release: release_rx,
    };
    (stuck, Unstick { entered, release })
}

impl Stuck {
    /// Holds the thread here the first time, if `stall` is where it holds.
    fn holds(&mut self, stall: Stall) {
        if self.stall == stall
            && let Some(entered) = self.entered.take()
        {
            let _ = entered.send(());
            let _ = self.release.recv();
        }
    }
}

impl Unstick {
    /// Waits until the write holds its thread; false if none does within 5 s.
    pub fn entered(&self) -> bool {
        self.entered.recv_timeout(Duration::from_secs(5)).is_ok()
    }

    /// Lets the held write go on.
    pub fn release(&self) {
        let _ = self.release.send(());
    }
}

impl AsyncWrite for Stuck {
    fn poll_write(
        self: Pin<&mut Self>,
        _cx: &mut Context<'_>,
        data: &[u8],
    ) -> Poll<io::Result<usize>> {
        self.get_mut().holds(Stall::Write);
        Poll::Ready(Ok(data.len()))
    }

    fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        self.get_mut().holds(Stall::Shutdown);
        Poll::Ready(Ok(()))
    }
}

impl AsyncRead for Stuck {
    fn poll_read(
        self: Pin<&mut Self>,
        _cx: &mut Context<'_>,
        _buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        match self.stall {
            Stall::Write => Poll::Pending,
            Stall::Shutdown => Poll::Ready(Ok(())),
        }
    }
}

/// Relays `io` to `upstream` on a thread of its own, as a worker of the runtime would, until the
/// splice ends or the driver gives the flow up.
pub(crate) fn relay_apart(mut io: FlowIo, mut upstream: Stuck) -> JoinHandle<()> {
    std::thread::spawn(move || {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let gate = io.gate();
        let relay = splice(&mut io, &mut upstream, &gate);
        runtime.block_on(until_given_up(&gate, relay));
    })
}
