//! What a TCP relay costs through the flow task, its gate included: throughput and CPU time a
//! chunk, with 1,500 B and 32 KiB chunks, from the driver's end of a flow through `splice` to an
//! upstream in memory, and back. The driver's part passes each chunk as the driver does, with the
//! gate readings of a pump (`TcpFlow::verdict` before and after it, `moved` for each chunk the
//! stack takes). The upstream never blocks, so the numbers are the relay's, the channels' and the
//! gate's. Each case runs on one thread (the driver's part and the relay take turns: the steadiest
//! CPU time a chunk), then on two, the relay on a worker as the driver's flow tasks are (each
//! chunk also wakes the other thread). CPU time is the process's, every thread. Then the gate's
//! own operations, alone on one thread. Run in release on the desktop:
//! `cargo test --locked --release --lib tunnel::splice::bench -- --ignored --nocapture --test-threads 1`;
//! the numbers are in `fixa-fix5-STATE.md`.

use super::*;
use crate::tunnel::flow::Ends;
use bytes::Bytes;
use std::hint::black_box;
use std::task::{Context, Waker};
use std::time::Instant;

/// Chunks a run moves, by size: about 600 MB at 1,500 B, 1.3 GB at 32 KiB.
const RUNS: [(usize, usize); 2] = [(1500, 400_000), (CHUNK, 40_000)];
/// Runs a case, of which the median is shown.
const REPEAT: usize = 7;

static DATA: [u8; CHUNK] = [7; CHUNK];

/// The driver's ends of a relay, the relay, and the driver's gate readings, for the tree measured.
mod shim {
    use super::Upstream;
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
    pub fn spawn_relay(mut upstream: Upstream) -> (Ends, JoinHandle<()>) {
        let (events, wakes) = mpsc::unbounded_channel();
        let waker = Waker::new(Key::Udp(0), events);
        let relay = Task::Relay;
        let (ends, mut io) = flow::pair(waker, Tally::new(None), DRAIN_DEADLINE, relay);
        let gate = io.gate();
        let task = tokio::spawn(async move {
            let _ = until_given_up(&gate, splice(&mut io, &mut upstream, &gate)).await;
            drop(wakes);
        });
        (ends, task)
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

/// An upstream in memory: it takes every write at once, and gives `give` chunks of `size` bytes,
/// then its FIN; giving nothing, it sends its FIN once `expect` bytes were written to it.
pub(super) struct Upstream {
    size: usize,
    give: usize,
    expect: usize,
    taken: usize,
    reader: Option<Waker>,
}

impl AsyncRead for Upstream {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
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
        _cx: &mut Context<'_>,
        data: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
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
    let upstream = match way {
        Way::Up => Upstream {
            size,
            give: 0,
            expect: total,
            taken: 0,
            reader: None,
        },
        Way::Down | Way::DownAfterFin => Upstream {
            size,
            give: chunks,
            expect: 0,
            taken: 0,
            reader: None,
        },
    };
    let (mut ends, relay) = shim::spawn_relay(upstream);
    let mut up = ends.up.take();
    if let Way::DownAfterFin = way {
        up = None;
    }
    let (wall, cpu0) = (Instant::now(), cpu());
    match way {
        Way::Up => {
            let tx = up.take().unwrap();
            for _ in 0..chunks {
                shim::reading(&ends);
                tx.send(Bytes::copy_from_slice(&DATA[..size]))
                    .await
                    .unwrap();
                shim::reading(&ends);
            }
            drop(tx);
            while ends.down.recv().await.is_some() {}
        }
        Way::Down | Way::DownAfterFin => {
            let mut got = 0;
            while let Some(chunk) = recv(&mut ends).await {
                shim::reading(&ends);
                got += black_box(chunk).len();
                shim::moved(&ends);
                shim::reading(&ends);
            }
            assert_eq!(got, total);
            drop(up);
        }
    }
    relay.await.unwrap();
    let (wall, cpu) = (wall.elapsed(), cpu() - cpu0);
    let mbps = total as f64 / wall.as_secs_f64() / 1e6;
    (mbps, cpu.as_nanos() as f64 / chunks as f64)
}

async fn recv(ends: &mut Ends) -> Option<Bytes> {
    ends.down.recv().await
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
