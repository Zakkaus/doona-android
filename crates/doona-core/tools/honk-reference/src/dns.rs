//! The DNS corpus through honk's embedded engine (`Engine::resolve_dns`): each case's upstreams
//! are local UDP servers with a fixed answer, its `.dae` names them by placeholder, and each query
//! records the reply bytes, the upstream that answered, the requery history and which upstreams
//! it dialed.

use std::collections::BTreeMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use anyhow::{Context, Result, ensure};
use honk_core::embedded::{Engine, Network};
use serde::{Deserialize, Serialize};
use tokio::net::UdpSocket;

pub const CORPUS_VERSION: u64 = 1;

/// The app that sends every query.
const CLIENT: SocketAddr = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 40000);

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Corpus {
    pub version: u64,
    pub cases: Vec<Case>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Case {
    pub name: String,
    /// Path of the `.dae` file, relative to the corpus directory. `{name}` stands for the address
    /// of upstream `name`.
    pub config: String,
    /// Upstream name to the one IPv4 address it answers every query with.
    pub upstreams: BTreeMap<String, Ipv4Addr>,
    pub queries: Vec<Query>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Query {
    pub name: String,
    pub qname: String,
    pub network: Transport,
    /// The upstream whose address the app sent the query to; `None` is the virtual resolver.
    pub server: Option<String>,
    /// The EDNS UDP size the query advertises, when it carries an OPT record.
    pub edns: Option<u16>,
}

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Transport {
    Udp,
    Tcp,
}

#[derive(Serialize)]
pub struct CaseReport {
    pub name: String,
    pub queries: Vec<QueryReport>,
}

#[derive(Serialize)]
pub struct QueryReport {
    pub name: String,
    /// The reply as hex, with the id zeroed.
    pub reply: String,
    pub rcode: u8,
    /// `None` when honk answered SERVFAIL or REFUSED.
    pub final_upstream: Option<String>,
    pub requery_history: Vec<String>,
    /// The upstreams that received a query for this one, in name order.
    pub dialed: Vec<String>,
}

pub fn load(dir: &Path) -> Result<Corpus> {
    let path = dir.join("corpus.json");
    let text = std::fs::read_to_string(&path).with_context(|| path.display().to_string())?;
    let corpus: Corpus = serde_json::from_str(&text).with_context(|| path.display().to_string())?;
    ensure!(
        corpus.version == CORPUS_VERSION,
        "DNS corpus version {} is not {CORPUS_VERSION}",
        corpus.version
    );
    Ok(corpus)
}

/// The query the app sends: id 0, RD, one A question, and an OPT record of `edns` bytes.
pub fn query(name: &str, edns: Option<u16>) -> Vec<u8> {
    let mut m = vec![
        0,
        0,
        0x01,
        0x00,
        0,
        1,
        0,
        0,
        0,
        0,
        0,
        u8::from(edns.is_some()),
    ];
    for label in name.split('.') {
        m.push(label.len() as u8);
        m.extend_from_slice(label.as_bytes());
    }
    m.extend_from_slice(&[0, 0, 1, 0, 1]);
    if let Some(size) = edns {
        m.extend_from_slice(&[0, 0, 41]);
        m.extend_from_slice(&size.to_be_bytes());
        m.extend_from_slice(&[0, 0, 0, 0, 0, 0]);
    }
    m
}

/// What an upstream answers `q` with: its question, and one A record of `address`, TTL 60.
pub fn upstream_answer(q: &[u8], address: Ipv4Addr) -> Option<Vec<u8>> {
    let mut end = 12;
    while *q.get(end)? != 0 {
        end += 1 + usize::from(q[end]);
    }
    let mut a = q.get(..end + 5)?.to_vec();
    a[2..4].copy_from_slice(&0x8180u16.to_be_bytes());
    a[6..12].copy_from_slice(&[0, 1, 0, 0, 0, 0]);
    a.extend_from_slice(&[0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4]);
    a.extend_from_slice(&address.octets());
    Some(a)
}

struct Upstream {
    addr: SocketAddr,
    queries: Arc<AtomicUsize>,
}

async fn upstream(address: Ipv4Addr) -> Result<Upstream> {
    let socket = UdpSocket::bind("127.0.0.1:0").await?;
    let addr = socket.local_addr()?;
    let queries = Arc::new(AtomicUsize::new(0));
    let counted = Arc::clone(&queries);
    tokio::spawn(async move {
        let mut buf = [0; 1500];
        while let Ok((len, peer)) = socket.recv_from(&mut buf).await {
            counted.fetch_add(1, Ordering::SeqCst);
            if let Some(reply) = upstream_answer(&buf[..len], address) {
                let _ = socket.send_to(&reply, peer).await;
            }
        }
    });
    Ok(Upstream { addr, queries })
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// Runs every case of the corpus in `dir`, with `geo` as the engine's data root.
pub fn run(corpus: &Corpus, dir: &Path, geo: &Path) -> Result<Vec<CaseReport>> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    runtime.block_on(async {
        let mut reports = Vec::new();
        for case in &corpus.cases {
            reports.push(run_case(case, dir, geo).await?);
        }
        Ok(reports)
    })
}

async fn run_case(case: &Case, dir: &Path, geo: &Path) -> Result<CaseReport> {
    let mut upstreams = BTreeMap::new();
    for (name, address) in &case.upstreams {
        upstreams.insert(name.as_str(), upstream(*address).await?);
    }
    let path = dir.join(&case.config);
    let mut source = std::fs::read_to_string(&path).with_context(|| path.display().to_string())?;
    for (name, upstream) in &upstreams {
        source = source.replace(&format!("{{{name}}}"), &upstream.addr.to_string());
    }
    let config = honk_config::parser::parse_dae_config(&source)
        .with_context(|| format!("{}: parse", case.name))?;
    let engine = Engine::new(config, geo).with_context(|| format!("{}: engine", case.name))?;
    let mut queries = Vec::new();
    for query in &case.queries {
        let server = match &query.server {
            Some(name) => Some(
                upstreams
                    .get(name.as_str())
                    .with_context(|| format!("{}: no upstream {name}", query.name))?
                    .addr,
            ),
            None => None,
        };
        let network = match query.network {
            Transport::Udp => Network::Udp,
            Transport::Tcp => Network::Tcp,
        };
        let before: Vec<usize> = upstreams
            .values()
            .map(|u| u.queries.load(Ordering::SeqCst))
            .collect();
        let answer = engine
            .resolve_dns(
                &self::query(&query.qname, query.edns),
                network,
                CLIENT,
                server,
            )
            .await
            .with_context(|| format!("{}: not one DNS query", query.name))?;
        let dialed = upstreams
            .iter()
            .zip(before)
            .filter(|((_, u), before)| u.queries.load(Ordering::SeqCst) > *before)
            .map(|((name, _), _)| (*name).to_owned())
            .collect();
        let mut reply = answer.reply().to_vec();
        ensure!(reply.len() >= 12, "{}: short reply", query.name);
        reply[..2].fill(0);
        let outcome = answer.outcome();
        queries.push(QueryReport {
            name: query.name.clone(),
            rcode: reply[3] & 0x0f,
            reply: hex(&reply),
            final_upstream: outcome.and_then(|o| o.final_upstream().map(str::to_owned)),
            requery_history: outcome.map_or_else(Vec::new, |o| o.requery_history().to_vec()),
            dialed,
        });
    }
    Ok(CaseReport {
        name: case.name.clone(),
        queries,
    })
}
