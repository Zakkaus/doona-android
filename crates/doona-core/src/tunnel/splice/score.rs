use super::{Gate, splice};
use crate::tunnel::flow::FlowIo;
use crate::tunnel::route::Upstream;
use honk_outbound::group::{ScoreOutcome, ScoreReporter};
use std::io;
use std::pin::Pin;
use std::task::{Context, Poll, ready};
use std::time::Instant;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

pub(crate) async fn relay(
    client: &mut FlowIo,
    stream: &mut Upstream,
    gate: &Gate,
    reporter: Option<ScoreReporter>,
) -> io::Result<()> {
    let Some(reporter) = reporter else {
        return splice(client, stream, gate).await;
    };
    let mut upstream = Observed {
        stream,
        reporter: &reporter,
        writing: None,
        responded: false,
    };
    splice(client, &mut upstream, gate).await?;
    reporter.finish(ScoreOutcome::Success);
    Ok(())
}

// Observe inside the upstream gate: a client or gate error drops the reporter as cancelled.
struct Observed<'a> {
    stream: &'a mut Upstream,
    reporter: &'a ScoreReporter,
    writing: Option<Instant>,
    responded: bool,
}

impl Observed<'_> {
    fn failed(&self, error: &io::Error) {
        self.reporter.finish(ScoreOutcome::from_io_error(error));
    }
}

impl AsyncRead for Observed<'_> {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let before = buf.filled().len();
        ready!(Pin::new(&mut self.stream).poll_read(cx, buf)).inspect_err(|e| self.failed(e))?;
        let received = buf.filled().len() - before;
        if received != 0 {
            if !self.responded {
                self.reporter.first_response();
                self.responded = true;
            }
            self.reporter.rx(received as u64);
        }
        Poll::Ready(Ok(()))
    }
}

impl AsyncWrite for Observed<'_> {
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        let start = *self.writing.get_or_insert_with(Instant::now);
        let n = ready!(Pin::new(&mut self.stream).poll_write(cx, buf))
            .inspect_err(|e| self.failed(e))?;
        self.writing = None;
        if n == 0 && !buf.is_empty() {
            let error = io::Error::from(io::ErrorKind::WriteZero);
            self.failed(&error);
            return Poll::Ready(Err(error));
        }
        self.reporter.tx_completed(n as u64, start);
        Poll::Ready(Ok(n))
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.stream).poll_flush(cx).map_err(|e| {
            self.failed(&e);
            e
        })
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.stream).poll_shutdown(cx).map_err(|e| {
            self.failed(&e);
            e
        })
    }
}
