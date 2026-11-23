use super::{Dialer, Session, Trace, Upstream};
use crate::api::lock;
use honk_outbound::group::{ScoreBusinessGuard, ScoreContinuation, ScoreOutcome, ScoreReporter};
use honk_outbound::runtime::DialScope;
use std::io::{self, ErrorKind};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use tokio::time::{Instant, timeout_at};

pub(crate) type Connected<T = Upstream> = (T, Option<ScoreReporter>);

struct Setup {
    scope: Arc<DialScope>,
    progress: Arc<Mutex<Progress>>,
}

struct Progress {
    waiting: Option<ScoreBusinessGuard>,
    started: Option<ScoreReporter>,
}

impl Setup {
    fn new(d: &Dialer, guard: ScoreBusinessGuard) -> Self {
        let progress = Arc::new(Mutex::new(Progress {
            waiting: Some(guard),
            started: None,
        }));
        let start = Arc::clone(&progress);
        let scope = d.registry.dial_scope(move || {
            let mut p = lock(&start);
            p.started = p.waiting.take().map(ScoreBusinessGuard::start);
        });
        Self { scope, progress }
    }

    fn failed(self, outcome: ScoreOutcome) {
        let mut p = lock(&self.progress);
        if let Some(guard) = p.waiting.take() {
            guard.finish(outcome);
        }
        if let Some(reporter) = p.started.take() {
            reporter.setup_failed(outcome);
        }
    }
}

pub(super) async fn connect(
    first: &Dialer,
    session: &Session,
    dst: SocketAddr,
    deadline: Instant,
    original: &mut Option<ScoreContinuation>,
) -> io::Result<Connected> {
    match dial(first, session, dst, deadline, original).await {
        Ok(stream) => Ok(stream),
        Err((outcome, error)) => {
            if Instant::now() >= deadline
                || matches!(
                    outcome,
                    ScoreOutcome::Rejected | ScoreOutcome::Cancelled | ScoreOutcome::Shutdown
                )
            {
                return Err(error);
            }
            let Some(retry) = original.as_ref().and_then(|c| first.retry(c)) else {
                return Err(error);
            };
            dial(&retry, session, dst, deadline, original)
                .await
                .map_err(|(_, e)| e)
        }
    }
}

async fn dial(
    d: &Dialer,
    session: &Session,
    dst: SocketAddr,
    deadline: Instant,
    original: &mut Option<ScoreContinuation>,
) -> Result<Connected, (ScoreOutcome, io::Error)> {
    let (registry, proxies, id) = (Arc::clone(&d.registry), Arc::clone(&d.proxies), d.node.id);
    let dial = async move {
        proxies
            .dial_runtime(
                registry,
                id,
                dst,
                None,
                deadline.saturating_duration_since(Instant::now()),
            )
            .await
            .map(|s| s.stream)
    };
    let admitted = |g: &ScoreBusinessGuard| {
        original.get_or_insert_with(|| g.continuation());
    };
    setup(d, session, &Trace::default(), deadline, admitted, dial).await
}

pub(super) async fn setup<T: Send + 'static>(
    d: &Dialer,
    session: &Session,
    trace: &Trace,
    deadline: Instant,
    admitted: impl FnOnce(&ScoreBusinessGuard),
    dial: impl Future<Output = anyhow::Result<T>> + Send + 'static,
) -> Result<Connected<T>, (ScoreOutcome, io::Error)> {
    let guard = d
        .score
        .as_ref()
        .map(|s| s.feedback.begin())
        .transpose()
        .map_err(|_| (ScoreOutcome::Rejected, ErrorKind::PermissionDenied.into()))?;
    let setup = guard.map(|g| {
        admitted(&g);
        Setup::new(d, g)
    });
    let scope = setup.as_ref().map(|s| Arc::clone(&s.scope));
    let began = session.begin_dial();
    let work = trace.scope(async move {
        match scope {
            Some(scope) => scope.scope(dial).await,
            None => dial.await,
        }
    });
    let result = match admitted_until(
        deadline,
        d.on_carriers(work),
        setup.as_ref().map(|s| &*s.scope),
    )
    .await
    {
        Ok(Ok(Err(_))) if d.registry.is_shutdown() => {
            Err((ScoreOutcome::Shutdown, ErrorKind::NotConnected.into()))
        }
        Ok(Ok(result)) => result.map_err(|e| {
            (
                ScoreOutcome::from_error(&e),
                ErrorKind::ConnectionRefused.into(),
            )
        }),
        Ok(Err(e)) => Err((ScoreOutcome::Shutdown, e)),
        Err(outcome) => Err((outcome, ErrorKind::TimedOut.into())),
    };
    session.dialed(began, trace, result.is_ok());
    match result {
        Ok(stream) => {
            let reporter = setup.and_then(|s| lock(&s.progress).started.take());
            if let Some(r) = &reporter {
                r.setup_succeeded();
            }
            Ok((stream, reporter))
        }
        Err((outcome, error)) => {
            if let Some(setup) = setup {
                setup.failed(outcome);
            }
            Err((outcome, error))
        }
    }
}

async fn admitted_until<F: Future>(
    deadline: Instant,
    dial: F,
    scope: Option<&DialScope>,
) -> Result<F::Output, ScoreOutcome> {
    let mut dial = std::pin::pin!(dial);
    timeout_at(deadline, &mut dial).await.map_err(|_| {
        if scope.is_some_and(DialScope::is_waiting_for_admission) {
            ScoreOutcome::Rejected
        } else {
            ScoreOutcome::Timeout
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::subscription::parse::parse;
    use honk_outbound::{proxy::ProxyRegistry, runtime::OutboundRuntimeRegistry};
    use std::time::Duration;

    #[tokio::test(start_paused = true)]
    async fn a_capacity_wait_is_rejected_not_a_node_timeout() {
        let nodes = parse(b"socks5://127.0.0.1:1#a").unwrap().nodes;
        let (registry, _) =
            OutboundRuntimeRegistry::build_reusing_with_dial_ceiling(&nodes, 1, 1, 1, false, None)
                .unwrap();
        let registry = Arc::new(registry);
        let _held = registry.acquire_dial_permit().await;
        let scope = registry.dial_scope(|| panic!("started without admission"));
        let proxies = ProxyRegistry::default_resolver().unwrap();
        let work = proxies.dial_runtime(
            registry,
            nodes[0].id,
            ([127, 0, 0, 1], 80).into(),
            None,
            Duration::from_secs(10),
        );
        assert!(matches!(
            admitted_until(
                Instant::now() + Duration::from_millis(50),
                scope.scope(work),
                Some(&scope)
            )
            .await,
            Err(ScoreOutcome::Rejected)
        ));
    }
}
