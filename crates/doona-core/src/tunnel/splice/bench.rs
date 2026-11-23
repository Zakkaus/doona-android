//! What a TCP relay costs through the flow task, its gate included: throughput and CPU time a
//! chunk, with 1,500 B and 32 KiB chunks, from the driver's end of a flow through `splice` to an
//! upstream in memory, and back. The driver's part serves the flow as the driver does: it waits
//! for the flow's wake on the event channel and re-arms it, then moves what it can without waiting,
//! with the gate readings of a pump (`TcpFlow::verdict` before and after it, `moved` for each chunk
//! the stack takes). The upstream never blocks, so the numbers are the relay's, the channels', the
//! wakes' and the gate's; it moves nothing before the clocks start. Each case runs on one thread
//! (the driver's part and the relay take turns: the steadiest CPU time a chunk), then on two, the
//! relay on a worker as the driver's flow tasks are (a wake then crosses threads). CPU time is the
//! process's, every thread. Then the gate's own operations, alone on one thread. Run in release on
//! the desktop:
//! `cargo test --locked --release --lib tunnel::splice::bench -- --ignored --nocapture --test-threads 1`;
//! the numbers are in `fixa-fix5-STATE.md` (fix 6).

use super::*;
use crate::tunnel::flow::{Ends, Event};
use bytes::Bytes;
use std::future::Future;
use std::hint::black_box;
use std::sync::Arc;
use std::task::{Context, Waker};
use std::time::Instant;
use tokio::sync::mpsc::error::TryRecvError;
use tokio::sync::{mpsc, oneshot};

/// Chunks a run moves, by size: about 600 MB at 1,500 B, 1.3 GB at 32 KiB.
const RUNS: [(usize, usize); 2] = [(1500, 400_000), (CHUNK, 40_000)];
/// Runs a case, of which the median is shown.
const REPEAT: usize = 7;

static DATA: [u8; CHUNK] = [7; CHUNK];

/// The driver's ends of a relay, the relay, and the driver's gate readings, for the tree measured.
mod shim {
    use super::{Flow, Upstream};
    use crate::stats::meter::Tally;
    use crate::tunnel::flow::{self, Ends, FlowIo, Key, Waker};
    use crate::tunnel::gate::Task;
    use crate::tunnel::splice::splice;
    use crate::tunnel::tcp::{DRAIN_DEADLINE, until_given_up};
    use std::hint::black_box;
    use std::task::Poll;
    use tokio::sync::mpsc;
    use tokio::task::JoinHandle;

    /// A fresh relay flow whose task splices to `upstream`, spawned on the runtime.
    pub fn spawn_relay(mut upstream: Upstream) -> (Flow, JoinHandle<()>) {
        let (events, wakes) = mpsc::unbounded_channel();
        let waker = Waker::new(Key::Udp(0), events);
        let driver = std::sync::Arc::clone(&waker);
        let relay = Task::Relay;
        let (ends, mut io) = flow::pair(waker, Tally::new(None), DRAIN_DEADLINE, relay);
        let gate = io.gate();
        let task = tokio::spawn(async move {
            let _ = until_given_up(&gate, splice(&mut io, &mut upstream, &gate)).await;
        });
        let flow = Flow {
            ends,
            waker: driver,
            wakes,
        };
        (flow, task)
    }

    /// What the driver reads of the flow's gate for one verdict.
    pub fn reading(ends: &Ends) {
        black_box(ends.gate.read());
    }

    /// What the driver tells the flow's gate as the stack takes a chunk.
    pub fn moved(ends: &Ends) {
        ends.gate.moved();
    }

    /// The driver's ends of a fresh relay flow, and its task's end, for the gate alone.
    pub fn idle_flow() -> (Ends, FlowIo) {
        let (events, _) = mpsc::unbounded_channel();
        let waker = Waker::new(Key::Udp(0), events);
        flow::pair(waker, Tally::new(None), DRAIN_DEADLINE, Task::Relay)
    }

    /// The gate's part of one poll of the task that moves bytes: `splice` makes one for each read
    /// and each write.
    pub fn task_poll(ends: &Ends) {
        let _ = black_box(ends.gate.poll_write(|| Poll::Ready(Ok(1))));
    }

    /// The client's FIN, as the driver tells it.
    pub fn client_fin(ends: &Ends) {
        ends.gate.fin();
    }
}

/// A relay flow as the driver holds it: its ends, and its wakes on the event channel.
pub(super) struct Flow {
    ends: Ends,
    waker: Arc<crate::tunnel::flow::Waker>,
    wakes: mpsc::UnboundedReceiver<Event>,
}

/// An upstream in memory: it takes every write at once, and gives `give` chunks of `size` bytes,
/// then its FIN; giving nothing, it sends its FIN once `expect` bytes were written to it. It does
/// nothing until `start` fires.
pub(super) struct Upstream {
    size: usize,
    give: usize,
    expect: usize,
    taken: usize,
    reader: Option<Waker>,
    start: Option<oneshot::Receiver<()>>,
}

impl Upstream {
    fn started(&mut self, cx: &mut Context<'_>) -> Poll<()> {
        if let Some(start) = &mut self.start {
            let _ = std::task::ready!(Pin::new(start).poll(cx));
            self.start = None;
        }
        Poll::Ready(())
    }
}

impl AsyncRead for Upstream {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        std::task::ready!(this.started(cx));
        if this.give > 0 {
            assert!(buf.remaining() >= this.size);
            this.give -= 1;
            buf.put_slice(&DATA[..this.size]);
        } else if this.taken < this.expect {
            this.reader = Some(cx.waker().clone());
            return Poll::Pending;
        }
        Poll::Ready(Ok(()))
    }
}

impl AsyncWrite for Upstream {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        data: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        std::task::ready!(this.started(cx));
        this.taken += data.len();
        if this.taken >= this.expect
            && let Some(reader) = this.reader.take()
        {
            reader.wake();
        }
        Poll::Ready(Ok(data.len()))
    }

    fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }
}

/// Which way the chunks go.
#[derive(Clone, Copy, Debug)]
enum Way {
    /// From the client to the upstream, both sides open until the last chunk.
    Up,
    /// From the upstream to the client, both sides open until the last chunk.
    Down,
    /// From the upstream to the client after the client's FIN: the drain has begun.
    DownAfterFin,
}

/// The process's CPU time.
fn cpu() -> std::time::Duration {
    let mut ts = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    // SAFETY: `ts` is a valid timespec for the call to fill.
    let r = unsafe { libc::clock_gettime(libc::CLOCK_PROCESS_CPUTIME_ID, &mut ts) };
    assert_eq!(r, 0);
    std::time::Duration::new(ts.tv_sec as u64, ts.tv_nsec as u32)
}

/// One run: `chunks` of `size` bytes relayed `way`; MB/s and CPU ns a chunk.
async fn run(way: Way, size: usize, chunks: usize) -> (f64, f64) {
    let total = size * chunks;
    let (start, started) = oneshot::channel();
    let give = match way {
        Way::Up => 0,
        Way::Down | Way::DownAfterFin => chunks,
    };
    let upstream = Upstream {
        size,
        give,
        expect: total - give * size,
        taken: 0,
        reader: None,
        start: Some(started),
    };
    let (mut flow, relay) = shim::spawn_relay(upstream);
    let mut up = flow.ends.up.take();
    let send = match way {
        Way::Up => Some(chunks),
        Way::Down => None,
        Way::DownAfterFin => {
            up = None;
            shim::client_fin(&flow.ends);
            None
        }
    };
    let (wall, cpu0) = (Instant::now(), cpu());
    let _ = start.send(());
    let got = drive(&mut flow, &mut up, send, size).await;
    assert_eq!(got, give * size);
    drop(up);
    relay.await.unwrap();
    let (wall, cpu) = (wall.elapsed(), cpu() - cpu0);
    let mbps = total as f64 / wall.as_secs_f64() / 1e6;
    (mbps, cpu.as_nanos() as f64 / chunks as f64)
}

/// The driver's part of a run, as it serves the flow: it moves what it can without waiting, the
/// gate read before and after as a pump does, then waits for the flow's wake and re-arms it. With
/// `send`, that many chunks go up, then the client's FIN. The bytes the client got, once the relay
/// closed the client's side.
async fn drive(
    flow: &mut Flow,
    up: &mut Option<mpsc::Sender<Bytes>>,
    mut send: Option<usize>,
    size: usize,
) -> usize {
    let mut got = 0;
    loop {
        shim::reading(&flow.ends);
        if let (Some(left), Some(tx)) = (send.as_mut(), up.as_ref()) {
            while *left > 0 && tx.try_send(Bytes::copy_from_slice(&DATA[..size])).is_ok() {
                *left -= 1;
            }
            if *left == 0 {
                (*up, send) = (None, None);
                shim::client_fin(&flow.ends);
            }
        }
        let closed = loop {
            match flow.ends.down.try_recv() {
                Ok(chunk) => {
                    got += black_box(chunk).len();
                    shim::moved(&flow.ends);
                }
                Err(TryRecvError::Empty) => break false,
                Err(TryRecvError::Disconnected) => break true,
            }
        };
        shim::reading(&flow.ends);
        if closed {
            return got;
        }
        flow.wakes.recv().await;
        flow.waker.rearm();
    }
}

fn median(mut v: Vec<f64>) -> f64 {
    v.sort_by(f64::total_cmp);
    v[v.len() / 2]
}

#[test]
#[ignore = "a release-mode measurement; see the module comment"]
fn measure_relay() {
    println!();
    for threads in [1, 2] {
        let runtime = match threads {
            1 => tokio::runtime::Builder::new_current_thread(),
            _ => {
                let mut two = tokio::runtime::Builder::new_multi_thread();
                two.worker_threads(1);
                two
            }
        }
        .enable_all()
        .build()
        .unwrap();
        for way in [Way::Up, Way::Down, Way::DownAfterFin] {
            for (size, chunks) in RUNS {
                // One run to warm up, then the measured ones.
                runtime.block_on(run(way, size, chunks / 10));
                let runs: Vec<(f64, f64)> = (0..REPEAT)
                    .map(|_| runtime.block_on(run(way, size, chunks)))
                    .collect();
                let mbps = median(runs.iter().map(|r| r.0).collect());
                let ns = median(runs.iter().map(|r| r.1).collect());
                let case = format!("{threads} thread(s), {way:?} {size} B");
                println!("relay {case}: {mbps:.0} MB/s, {ns:.0} ns CPU a chunk");
            }
        }
    }
}

/// Gate operations one measurement times.
const OPS: u32 = 10_000_000;

/// The time of one `op` alone on one thread, in ns: the median of [`REPEAT`] measurements.
fn per_op(op: impl Fn()) -> f64 {
    let runs = (0..REPEAT).map(|_| {
        let start = Instant::now();
        for _ in 0..OPS {
            op();
        }
        start.elapsed().as_nanos() as f64 / f64::from(OPS)
    });
    median(runs.collect())
}

/// What one task poll adds through the gate, and one reading of the driver costs, uncontended,
/// before the client's FIN and after it.
#[test]
#[ignore = "a release-mode measurement; see the module comment"]
fn measure_gate() {
    println!();
    for fin in [false, true] {
        let (ends, _io) = shim::idle_flow();
        if fin {
            shim::client_fin(&ends);
        }
        let poll = per_op(|| shim::task_poll(&ends));
        let reading = per_op(|| shim::reading(&ends));
        let when = if fin {
            "after the FIN"
        } else {
            "before the FIN"
        };
        println!("gate {when}: {poll:.1} ns a task poll, {reading:.1} ns a driver reading");
    }
}
