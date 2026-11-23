//! Points where a test holds a flow's task, as if the thread running it were descheduled there,
//! and meanwhile does what the driver would. Compiled into tests only. A hold covers the tasks of
//! the thread that set it, so a test on a current-thread runtime holds only its own.

use std::cell::RefCell;
use std::sync::Arc;
use tokio::sync::Notify;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub(crate) enum Point {
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
