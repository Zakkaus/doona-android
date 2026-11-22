//! `snapshot` and the reads of the stats: cheap, and served while stopped too.

use super::{Core, Life, lock, parse, to_json};
use crate::catalog::NodeEntry;
use crate::dto::{
    Empty, ErrorCode, LatencyTest, NodeInfo, Nodes, NodesArgs, Reply, SeriesArgs, Snapshot, Today,
    Traffic, TrafficSeries,
};
use crate::stats::Clock;
use crate::stats::ring::SPAN;
use crate::tunnel::Session;
use honk_config::types::NodeProtocol;
use std::sync::Arc;

impl Core {
    pub fn snapshot(&self, since_revision: i64) -> String {
        // One moment of the lifecycle, the catalog and the warning: the locks in the order `stop`
        // takes them, and the warning read under the catalog lock its changes take.
        let (revision, catalog, session, warning) = {
            let life = lock(&self.life);
            let c = lock(&self.shared.catalog);
            let catalog = (c.revision > since_revision).then(|| c.model.catalog(&c.state));
            let session = match &*life {
                Life::Running(tunnel) => Some(Arc::clone(tunnel.session())),
                _ => None,
            };
            let warning = session.as_ref().and_then(|s| s.warning());
            (c.revision, catalog, session, warning)
        };
        let today = self.stats.today(&Clock::now());
        let snapshot = match session {
            Some(s) => Snapshot {
                revision,
                running: !s.failed(),
                generation: s.generation,
                since_epoch_ms: Some(s.since_epoch_ms),
                error: s.failed().then_some(ErrorCode::Internal),
                warning,
                traffic: s.traffic(),
                today,
                catalog,
            },
            None => Snapshot {
                revision,
                running: false,
                generation: self.generation.load(std::sync::atomic::Ordering::Relaxed),
                since_epoch_ms: None,
                error: None,
                warning: None,
                traffic: Traffic::default(),
                today,
                catalog,
            },
        };
        to_json(&snapshot)
    }

    /// `trafficSeries`: the rate of the last `seconds` (1 to 3600), whether or not a screen was
    /// open; zero while stopped.
    pub(super) fn traffic_series(&self, args: &str) -> Result<String, ErrorCode> {
        let SeriesArgs { seconds } = parse(args)?;
        if !(1..=SPAN).contains(&seconds) {
            return Err(ErrorCode::InvalidArgument);
        }
        let running = matches!(*lock(&self.life), Life::Running(_));
        let (series, end_epoch_ms) = self.stats.series(seconds, running, &Clock::now());
        Ok(to_json(&Reply::Ok(TrafficSeries {
            step_seconds: series.step,
            end_epoch_ms,
            up: series.up,
            down: series.down,
            up_peak: series.up_peak,
            down_peak: series.down_peak,
        })))
    }

    /// `usage`: today and yesterday by the hour, and the days before.
    pub(super) fn usage(&self, args: &str) -> Result<String, ErrorCode> {
        let Empty {} = parse(args)?;
        Ok(to_json(&Reply::Ok(self.stats.usage(&Clock::now()))))
    }

    /// `nodes`: what each node's share link says and its last latency tests, for every node in
    /// catalog order, or for those of `ids` that exist.
    pub(super) fn nodes(&self, args: &str) -> Result<String, ErrorCode> {
        let NodesArgs { ids } = parse(args)?;
        let entries: Vec<Arc<NodeEntry>> = {
            let c = lock(&self.shared.catalog);
            match ids {
                None => c.model.nodes.clone(),
                Some(ids) => ids.iter().filter_map(|id| c.model.entry(id)).collect(),
            }
        };
        let tests = self.stats.latency(entries.iter().map(|e| e.id.as_str()));
        let nodes = entries.iter().zip(tests).map(node_info).collect();
        Ok(to_json(&Reply::Ok(Nodes { nodes })))
    }

    /// Folds the last bytes of a generation that has ended into the stats, and writes them.
    pub(super) fn retire(&self, session: &Session) {
        self.stats.finish(session, &Clock::now());
    }
}

/// What `snapshot` answers before `init`: stopped, nothing to show.
pub fn snapshot_before_init() -> String {
    to_json(&Snapshot {
        revision: 0,
        running: false,
        generation: 0,
        since_epoch_ms: None,
        error: None,
        warning: None,
        traffic: Traffic::default(),
        today: Today::default(),
        catalog: None,
    })
}

/// A node's details from its share link, with its `tests`.
fn node_info((entry, tests): (&Arc<NodeEntry>, Vec<LatencyTest>)) -> NodeInfo {
    let node = &entry.node;
    let protocol = node.protocol();
    let quic = matches!(
        protocol,
        NodeProtocol::Hysteria2 | NodeProtocol::Tuic | NodeProtocol::Juicity
    );
    let transport = match node.transport() {
        _ if quic => "quic".to_owned(),
        Some(t) => t.transport.clone(),
        None => "tcp".to_owned(),
    };
    NodeInfo {
        id: entry.id.clone(),
        protocol: protocol.as_str().to_owned(),
        server: node.host.clone(),
        port: node.port,
        transport,
        tls: node.tls().is_some_and(|t| t.enabled),
        tests,
    }
}
