//! Latency tests: each node of a test is probed once through its own outbound with honk's
//! `urltest_node`, at most [`MAX_PARALLEL`] at a time across every test, each within
//! [`PROBE_TIMEOUT`]. A probe waits on the network with no lock held and commits in a short
//! section that checks the node still has the incarnation and probe generation it began with
//! (core-api.md, Commit rule); a test that ends, cancelled or not, clears its groups' `testing`.
//! A probe that failed after it went on to dial its node at an address on the local network
//! records `local_network` (ARCH D26); one that failed before, at the lookup of its own target or
//! of the node's name, records no code (see `localnet::Lookups`).

use crate::api::lock;
use crate::catalog::{Plan, Round, Shared, Target};
use crate::diag::{Count, Event};
use crate::dto::ErrorCode;
use crate::localnet::{Lookups, Trace, literal};
use crate::outbounds::{Carriers, Registries};
use futures_util::FutureExt;
use futures_util::future::BoxFuture;
use honk_config::node::Node;
use honk_outbound::proxy::ProxyRegistry;
use honk_outbound::runtime::OutboundRuntimeRegistry;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering::Relaxed};
use std::time::{Duration, Instant};
use tokio::runtime::Handle;
use tokio::sync::Semaphore;
use tokio_util::sync::CancellationToken;

/// The URL each probe fetches through its node, as clash and sing-box do.
pub const DEFAULT_PROBE_URL: &str = "https://www.gstatic.com/generate_204";
pub(crate) const PROBE_TIMEOUT: Duration = Duration::from_secs(5);
pub(crate) const MAX_PARALLEL: usize = 32;

/// The published registries and the carriers they dial on.
pub(crate) type Published = (Registries, Arc<Carriers>);

/// What one probe goes through.
pub(crate) struct ProbeJob {
    pub node: Arc<Node>,
    pub registry: Option<Arc<OutboundRuntimeRegistry>>,
    pub proxies: Arc<ProxyRegistry>,
    pub url: Arc<str>,
    /// What the core's lookups gave the probe; read when it measured nothing.
    pub lookups: Lookups,
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
    let probe =
        honk_outbound::urltest::urltest_node(&runtime, handler.as_ref(), &job.url, PROBE_TIMEOUT);
    job.lookups.scope(probe).await.ok()
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

    /// Runs the test `plan` on `rt`, each probe through the `published` registry that holds its
    /// node, on its carriers; `cancel` drops it with the tunnel generation it began in.
    pub fn spawn(
        &self,
        rt: &Handle,
        shared: &Arc<Shared>,
        plan: Plan,
        cancel: CancellationToken,
        published: Option<Published>,
    ) {
        let (round, planned) = (plan.round(), plan.targets.len());
        let tally = Arc::new(Tally::default());
        let proxies = shared.outbounds.proxies();
        let probes = plan.targets.into_iter().map(|target| {
            let job = ProbeJob {
                node: Arc::clone(&target.entry.node),
                registry: published
                    .as_ref()
                    .and_then(|(r, _)| r.of(&target.entry.node).cloned()),
                proxies: Arc::clone(&proxies),
                url: Arc::clone(&self.url),
                lookups: Lookups::default(),
            };
            let carriers = published.as_ref().map(|(_, c)| Arc::clone(c));
            let ending = (cancel.clone(), Arc::clone(&tally));
            self.one(Arc::clone(shared), (job, carriers), target, ending)
        });
        let probes = futures_util::future::join_all(probes);
        let ended = Ended {
            shared: Arc::clone(shared),
            round,
            planned,
            tally,
            begun: Instant::now(),
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
        (job, carriers): (ProbeJob, Option<Arc<Carriers>>),
        target: Target,
        (cancel, tally): (CancellationToken, Arc<Tally>),
    ) -> impl Future<Output = ()> + Send + 'static {
        let permits = Arc::clone(&self.permits);
        let prober = Arc::clone(&self.prober);
        async move {
            let Ok(_permit) = permits.acquire_owned().await else {
                return;
            };
            let lookups = job.lookups.clone();
            let trace = Trace::default();
            let probe = trace.scope(prober(job));
            let probe = async {
                match &carriers {
                    Some(carriers) => carriers.run(probe).await.ok().flatten(),
                    None => probe.await,
                }
            };
            let measured = tokio::time::timeout(PROBE_TIMEOUT, probe).await;
            // Carriers that ended under the probe cut it short: it measured nothing.
            if carriers.is_some_and(|c| c.ended()) {
                return;
            }
            let ms = measured
                .ok()
                .flatten()
                .map(|d| u32::try_from(d.as_millis()).unwrap_or(u32::MAX).max(1));
            let error = failure(ms, &lookups, &target);
            tally.count(ms.is_some());
            let mut c = lock(&shared.catalog);
            // `cancel` is cancelled under the catalog lock, so a stop that returned is never undone.
            if !cancel.is_cancelled() && c.model.record(&target, ms, error) {
                if let Some((ip, available)) = trace.availability() {
                    c.model.score.available(target.entry.uuid, ip, available);
                }
                if let Some(ip) = trace.peer().or_else(|| literal(target.entry.node.host())) {
                    c.model.score.probe(target.entry.uuid, ip, ms);
                }
                c.revision += 1;
                shared.publish(&c);
                (shared.stats).record_latency(&target.entry.id, target.began_ms, ms);
            }
        }
    }
}

/// Why a probe measured nothing, when the core can tell: it stopped at an address of its node on
/// the local network. Read also when the probe ran out of time, as a dial Android 17 blocks does.
fn failure(ms: Option<u32>, lookups: &Lookups, target: &Target) -> Option<ErrorCode> {
    let local = ms.is_none() && lookups.refused_locally(target.entry.node.host());
    local.then_some(ErrorCode::LocalNetwork)
}

/// The probes of a test that measured, and those that failed, for its event in the ring.
#[derive(Default)]
struct Tally {
    measured: AtomicU32,
    failed: AtomicU32,
}

impl Tally {
    fn count(&self, measured: bool) {
        let counter = if measured {
            &self.measured
        } else {
            &self.failed
        };
        counter.fetch_add(1, Relaxed);
    }
}

/// Ends a test when its task ends, finished or cancelled.
struct Ended {
    shared: Arc<Shared>,
    round: Round,
    planned: usize,
    tally: Arc<Tally>,
    begun: Instant,
}

impl Drop for Ended {
    fn drop(&mut self) {
        let mut c = lock(&self.shared.catalog);
        c.model.finish(&self.round);
        c.revision += 1;
        self.shared.stats.end_round();
        drop(c);
        let (measured, failed) = (&self.tally.measured, &self.tally.failed);
        let (measured, failed) = (measured.load(Relaxed), failed.load(Relaxed));
        let event = Event::info("probeRound").count(Count::Probes, self.planned);
        let event = event.count(Count::Failed, failed).since(self.begun);
        let finished = usize::try_from(measured + failed).is_ok_and(|n| n >= self.planned);
        let event = if finished {
            event
        } else {
            event.detail("cancelled")
        };
        self.shared.diag.record(event);
    }
}
