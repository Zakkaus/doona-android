//! Latency tests: each node of a test is probed once through its own outbound with honk's
//! `urltest_node`, at most [`MAX_PARALLEL`] at a time across every test, each within
//! [`PROBE_TIMEOUT`]. A probe waits on the network with no lock held and commits in a short
//! section that checks the node still has the incarnation and probe generation it began with
//! (core-api.md, Commit rule); a test that ends, cancelled or not, clears its groups' `testing`.

use crate::api::lock;
use crate::catalog::{Plan, Round, Shared, Target};
use futures_util::FutureExt;
use futures_util::future::BoxFuture;
use honk_config::node::Node;
use honk_outbound::proxy::ProxyRegistry;
use honk_outbound::runtime::OutboundRuntimeRegistry;
use std::sync::Arc;
use std::time::Duration;
use tokio::runtime::Handle;
use tokio::sync::Semaphore;
use tokio_util::sync::CancellationToken;

/// The URL each probe fetches through its node, as clash and sing-box do.
pub const DEFAULT_PROBE_URL: &str = "https://www.gstatic.com/generate_204";
pub(crate) const PROBE_TIMEOUT: Duration = Duration::from_secs(5);
pub(crate) const MAX_PARALLEL: usize = 32;

/// What one probe goes through.
pub(crate) struct ProbeJob {
    pub node: Arc<Node>,
    pub registry: Option<Arc<OutboundRuntimeRegistry>>,
    pub proxies: Arc<ProxyRegistry>,
    pub url: Arc<str>,
}

/// Measures one node: the round trip, or `None` when the probe failed. The app uses
/// [`urltest`]; unit tests stand in for the network.
pub(crate) type Prober =
    Arc<dyn Fn(ProbeJob) -> BoxFuture<'static, Option<Duration>> + Send + Sync>;

pub(crate) fn urltest() -> Prober {
    Arc::new(|job| urltest_job(job).boxed())
}

async fn urltest_job(job: ProbeJob) -> Option<Duration> {
    let runtime = job.registry?.get(&job.node.id)?;
    let entry = job.proxies.find(job.node.protocol())?;
    let handler = Arc::clone(&entry.tcp);
    honk_outbound::urltest::urltest_node(&runtime, handler.as_ref(), &job.url, PROBE_TIMEOUT)
        .await
        .ok()
}

/// The probing the core does: who measures, against what, and how many at once.
pub(crate) struct Probes {
    prober: Prober,
    url: Arc<str>,
    permits: Arc<Semaphore>,
}

impl Probes {
    pub fn new(prober: Prober, url: &str) -> Probes {
        Probes {
            prober,
            url: url.into(),
            permits: Arc::new(Semaphore::new(MAX_PARALLEL)),
        }
    }

    /// Runs the test `plan` on `rt`; `cancel` drops it with the tunnel generation it began in.
    pub fn spawn(&self, rt: &Handle, shared: &Arc<Shared>, plan: Plan, cancel: CancellationToken) {
        let round = plan.round();
        let registry = shared.outbounds.registry();
        let proxies = shared.outbounds.proxies();
        let probes = plan.targets.into_iter().map(|target| {
            let job = ProbeJob {
                node: Arc::clone(&target.entry.node),
                registry: registry.clone(),
                proxies: Arc::clone(&proxies),
                url: Arc::clone(&self.url),
            };
            self.one(Arc::clone(shared), job, target, cancel.clone())
        });
        let probes = futures_util::future::join_all(probes);
        let ended = Ended {
            shared: Arc::clone(shared),
            round,
        };
        rt.spawn(async move {
            let _ended = ended;
            tokio::select! {
                () = cancel.cancelled() => {}
                _ = probes => {}
            }
        });
    }

    fn one(
        &self,
        shared: Arc<Shared>,
        job: ProbeJob,
        target: Target,
        cancel: CancellationToken,
    ) -> impl Future<Output = ()> + Send + 'static {
        let permits = Arc::clone(&self.permits);
        let prober = Arc::clone(&self.prober);
        async move {
            let Ok(_permit) = permits.acquire_owned().await else {
                return;
            };
            let measured = tokio::time::timeout(PROBE_TIMEOUT, prober(job)).await;
            let ms = measured
                .ok()
                .flatten()
                .map(|d| u32::try_from(d.as_millis()).unwrap_or(u32::MAX).max(1));
            let mut c = lock(&shared.catalog);
            // `cancel` is cancelled under the catalog lock, so a stop that returned is never undone.
            if !cancel.is_cancelled() && c.model.record(&target, ms) {
                c.revision += 1;
                shared.publish(&c);
            }
        }
    }
}

/// Ends a test when its task ends, finished or cancelled.
struct Ended {
    shared: Arc<Shared>,
    round: Round,
}

impl Drop for Ended {
    fn drop(&mut self) {
        let mut c = lock(&self.shared.catalog);
        c.model.finish(&self.round);
        c.revision += 1;
    }
}
