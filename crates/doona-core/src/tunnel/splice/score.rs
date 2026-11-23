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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::stats::meter::Tally;
    use crate::subscription::parse::parse;
    use crate::tunnel::flow::{self, Ends, Key, Waker};
    use crate::tunnel::gate::Task;
    use crate::tunnel::tcp::DRAIN_DEADLINE;
    use honk_config::group::{Group, GroupPolicy};
    use honk_outbound::alive::{IpVersion, ProbeDomain};
    use honk_outbound::group::{GroupManager, ScoreSelectionContext, SelectionNetwork};
    use tokio::io::{AsyncWriteExt, duplex};

    fn trial() -> (GroupManager, ScoreReporter) {
        let nodes = parse(b"socks5://127.0.0.1:1#a\nsocks5://127.0.0.1:2#b")
            .unwrap()
            .nodes;
        let group = Group {
            name: "auto".into(),
            policy: GroupPolicy::Score,
            nodes: nodes.iter().map(|n| n.id).collect(),
            ..Group::default()
        };
        let manager = GroupManager::new(&[group], &nodes);
        let dst = std::net::SocketAddr::from(([127, 0, 0, 1], 443));
        let context = ScoreSelectionContext {
            network: SelectionNetwork::Tcp,
            probe_domain: ProbeDomain::Tcp,
            target_family: Some(IpVersion::V4),
            health_family: IpVersion::V4,
            target: Some(dst.into()),
        };
        let start = || {
            let plan = manager.selection_plan_for_target("auto", &context);
            let reporter = plan.entries[0]
                .feedback
                .as_ref()
                .unwrap()
                .begin()
                .unwrap()
                .start();
            reporter.setup_succeeded();
            reporter
        };
        let incumbent = start();
        incumbent.first_response();
        incumbent.finish(ScoreOutcome::Success);
        let challenger = start();
        assert_eq!(
            manager
                .score_budget_counters("auto", SelectionNetwork::Tcp)
                .trial_starts,
            1
        );
        (manager, challenger)
    }

    fn client() -> (Ends, FlowIo) {
        let waker = Waker::new(Key::Udp(0), tokio::sync::mpsc::unbounded_channel().0);
        flow::pair(waker, Tally::new(None), DRAIN_DEADLINE, Task::Relay)
    }

    fn outcomes(manager: &GroupManager) -> (u64, u64, u64) {
        let b = manager.score_budget_counters("auto", SelectionNetwork::Tcp);
        (b.trial_success, b.trial_failure, b.trial_cancelled)
    }

    #[tokio::test]
    async fn clean_close_reports_success() {
        let (manager, reporter) = trial();
        let (mut ends, mut client) = client();
        drop(ends.up.take());
        let (upstream, mut server) = duplex(16);
        server.write_all(b"reply").await.unwrap();
        server.shutdown().await.unwrap();
        let mut stream: Upstream = Box::new(upstream);
        relay(&mut client, &mut stream, &ends.gate, Some(reporter))
            .await
            .unwrap();
        assert_eq!(&ends.down.try_recv().unwrap()[..], b"reply");
        assert_eq!(outcomes(&manager), (1, 0, 0));
    }

    #[tokio::test]
    async fn upstream_broken_pipe_reports_failure() {
        let (manager, reporter) = trial();
        let (ends, mut client) = client();
        ends.up
            .as_ref()
            .unwrap()
            .try_send(bytes::Bytes::from_static(b"request"))
            .unwrap();
        let (upstream, server) = duplex(16);
        drop(server);
        let mut stream: Upstream = Box::new(upstream);
        let error = relay(&mut client, &mut stream, &ends.gate, Some(reporter))
            .await
            .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::BrokenPipe);
        assert_eq!(outcomes(&manager), (0, 1, 0));
    }

    #[tokio::test]
    async fn client_abort_cancels_the_live_trial() {
        let (manager, reporter) = trial();
        let (ends, mut client) = client();
        let (upstream, mut server) = duplex(16);
        let mut stream: Upstream = Box::new(upstream);
        let mut relay = std::pin::pin!(relay(&mut client, &mut stream, &ends.gate, Some(reporter)));
        assert!(futures_util::poll!(&mut relay).is_pending());
        assert_eq!(outcomes(&manager), (0, 0, 0));
        drop(ends.down);
        server.write_all(b"reply").await.unwrap();
        assert_eq!(relay.await.unwrap_err().kind(), io::ErrorKind::BrokenPipe);
        assert_eq!(outcomes(&manager), (0, 0, 1));
    }
}
