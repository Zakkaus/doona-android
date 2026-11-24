//! Only the driver opens entries. A handle addresses an insertion, not a slot: a late close
//! or first TCP read cannot change a newer connection after wraparound.
use super::Session;
use crate::api::lock;
use crate::dto::recent::{Protocol, RecentFlow, RecentFlows};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Instant;

const CAPACITY: usize = 128;

type Row = RecentFlow<Arc<str>>;

struct Ring {
    next: u64,
    slots: [Option<Row>; CAPACITY],
    stopped: bool,
}

pub(crate) struct Recent {
    start: Instant,
    ring: Mutex<Ring>,
}

impl Recent {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            start: Instant::now(),
            ring: Mutex::new(Ring {
                next: 0,
                slots: std::array::from_fn(|_| None),
                stopped: false,
            }),
        })
    }

    fn push(self: &Arc<Self>, row: Row) -> Option<Handle> {
        let mut ring = lock(&self.ring);
        if ring.stopped {
            return None;
        }
        let id = ring.next;
        ring.slots[id as usize % CAPACITY] = Some(row);
        ring.next += 1;
        Some(Handle {
            recent: Arc::clone(self),
            id,
        })
    }

    pub fn read(&self) -> RecentFlows {
        let end = lock(&self.ring).next;
        let rows = (end.saturating_sub(CAPACITY as u64)..end)
            .rev()
            .filter_map(|id| {
                // Only clone references under the lock, one row at a time. Formatting and JSON
                // serialization cannot hold up a flow. A concurrent eviction skips that row.
                let row = lock(&self.ring).row(id)?.clone();
                Some(RecentFlow {
                    protocol: row.protocol,
                    local: row.local,
                    remote: row.remote,
                    name: row.name.as_deref().map(str::to_owned),
                    outbound: row.outbound.to_string(),
                    started_ms: row.started_ms,
                    open: row.open,
                })
            });
        RecentFlows {
            flows: rows.collect(),
        }
    }

    pub fn stop(&self) {
        let mut ring = lock(&self.ring);
        ring.stopped = true;
        ring.slots.fill(None);
    }
}

impl Ring {
    fn row(&mut self, id: u64) -> Option<&mut Row> {
        if self.next - id > CAPACITY as u64 {
            return None;
        }
        self.slots[id as usize % CAPACITY].as_mut()
    }
}

impl Session {
    pub(super) fn record_flow(
        &self,
        protocol: Protocol,
        local: SocketAddr,
        remote: SocketAddr,
        outbound: &Arc<str>,
    ) -> Option<Handle> {
        let names = self.names();
        let name = (names.version() != 0)
            .then(|| names.peek(remote.ip()))
            .flatten();
        self.recent.push(Row {
            protocol,
            local,
            remote,
            name,
            outbound: Arc::clone(outbound),
            started_ms: self.recent.start.elapsed().as_millis() as u64,
            open: true,
        })
    }
}

pub(crate) struct Handle {
    recent: Arc<Recent>,
    id: u64,
}

impl Handle {
    pub fn sniff(&self, prefix: &[u8]) {
        if let Some(host) = super::sniff::host(prefix) {
            let name = Arc::from(host);
            if let Some(row) = lock(&self.recent.ring).row(self.id) {
                row.name = Some(name);
            }
        }
    }
}

impl Drop for Handle {
    fn drop(&mut self) {
        if let Some(row) = lock(&self.recent.ring).row(self.id) {
            row.open = false;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_evicted_flow_cannot_rename_or_close_its_replacement() {
        let recent = Recent::new();
        let row = |port| Row {
            protocol: Protocol::Tcp,
            local: ([127, 0, 0, 1], port).into(),
            remote: ([127, 0, 0, 1], 80).into(),
            name: None,
            outbound: "direct".into(),
            started_ms: 0,
            open: true,
        };
        let oldest = recent.push(row(0)).unwrap();
        for port in 1..128 {
            drop(recent.push(row(port)));
        }
        let _newest = recent.push(row(128)).unwrap();
        oldest.sniff(b"GET / HTTP/1.1\r\nHost: old.test\r\n\r\n");
        drop(oldest);
        let flows = recent.read().flows;
        assert!(flows.iter().map(|f| f.local.port()).eq((1..=128).rev()));
        assert!(flows[0].open && flows[0].name.is_none());
        recent.stop();
        assert!(recent.push(row(129)).is_none());
        assert!(recent.read().flows.is_empty());
    }
}
