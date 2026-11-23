//! A DNS answer over TCP through a flow's channels, to a driver that takes and sends each chunk
//! before the task that wrote it runs again.

use super::*;
use crate::dns::names::{Learned, Names};
use crate::dns::serve_tcp;
use crate::dns::tests::{answer_to, query, upstream_with};
use crate::tunnel::gate::Task;
use crate::tunnel::route::Route;
use std::net::IpAddr;
use tokio::io::AsyncWriteExt;

/// The task's end of a flow, wrapped so the driver takes each chunk as soon as the task writes it:
/// the task is held there, between handing the chunk over and anything it does next. Once the
/// whole answer is taken the driver sends it, and the client's next SYN looks the first address
/// up; `seen` is what that lookup found.
struct Driven<'a> {
    io: FlowIo,
    ends: Ends,
    names: &'a Names,
    /// The bytes taken so far, and the framed answer's length.
    taken: usize,
    frame: usize,
    seen: Option<Option<Arc<str>>>,
}

impl ToClient for Driven<'_> {
    fn map_with_last_chunk(&mut self, mapping: Mapping) {
        self.io.map_with_last_chunk(mapping);
    }

    fn resolving(&mut self, on: bool) {
        self.io.resolving(on);
    }
}

impl AsyncRead for Driven<'_> {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().io).poll_read(cx, buf)
    }
}

impl AsyncWrite for Driven<'_> {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        data: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        let n = ready!(Pin::new(&mut this.io).poll_write(cx, data))?;
        while let Ok(chunk) = this.ends.down.try_recv() {
            this.taken += chunk.len();
        }
        if this.taken == this.frame && this.seen.is_none() {
            this.seen = Some(this.names.name(IpAddr::from([10, 0, 0, 0])));
        }
        Poll::Ready(Ok(n))
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().io).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().io).poll_shutdown(cx)
    }
}

/// A client that gets a whole answer over TCP and opens a flow to an address in it finds the
/// address named, however late the task that wrote the answer runs again.
#[tokio::test]
async fn an_answer_over_tcp_is_mapped_before_the_driver_takes_its_last_byte() {
    let idle = Duration::from_secs(30);
    let (events, _) = mpsc::unbounded_channel();
    // The key only names the flow in its wakes, which nothing reads here.
    let (mut ends, io) = pair(
        Waker::new(Key::Udp(0), events),
        Tally::new(None),
        idle,
        Task::Dns,
    );
    let q = query(5, "race.example", None);
    let mut framed = (q.len() as u16).to_be_bytes().to_vec();
    framed.extend_from_slice(&q);
    // The query, then the client's FIN, which ends the task after the answer.
    let up = ends.up.take().unwrap();
    up.try_send(Bytes::from(framed)).unwrap();
    drop(up);
    let names = Names::new();
    let frame = answer_to(&q, 2).len() + 2;
    let mut driven = Driven {
        io,
        ends,
        names: &names,
        taken: 0,
        frame,
        seen: None,
    };
    let upstreams = [upstream_with(|q| answer_to(q, 2))];
    let via = (&Route::Direct, &names);
    serve_tcp(&mut driven, &upstreams, via).await.unwrap();
    let seen = driven.seen.expect("the driver took the whole answer");
    assert_eq!(
        seen.as_deref(),
        Some("race.example"),
        "the client's SYN after the answer found no name"
    );
}

/// What an answer maps is put as the driver takes the last chunk of the write that carries it, not
/// before; an answer still in the channel when the flow ends maps nothing.
#[tokio::test]
async fn what_an_answer_maps_is_put_as_the_driver_takes_its_last_chunk() {
    let q = query(6, "chunks.example", None);
    let answered = answer_to(&q, 2);
    let names = Names::new();
    let (events, _) = mpsc::unbounded_channel();
    let waker = Waker::new(Key::Udp(0), events);
    let (mut ends, mut io) = pair(
        Arc::clone(&waker),
        Tally::new(None),
        Duration::from_secs(30),
        Task::Dns,
    );
    io.map_with_last_chunk(names.bind(Learned::read(&q, &answered).unwrap()));
    io.write_all(&vec![0; 2 * CHUNK + 1]).await.unwrap();
    for _ in 0..2 {
        assert_eq!(ends.down.try_recv().unwrap().len(), CHUNK);
        assert_eq!(names.len(), 0, "put before the last chunk");
    }
    assert_eq!(ends.down.try_recv().unwrap().len(), 1);
    assert_eq!(names.len(), 2);
    // Written, never taken.
    let names = Names::new();
    let (ends, mut io) = pair(waker, Tally::new(None), Duration::from_secs(30), Task::Dns);
    io.map_with_last_chunk(names.bind(Learned::read(&q, &answered).unwrap()));
    io.write_all(b"x").await.unwrap();
    drop(ends);
    assert_eq!(names.len(), 0);
}
