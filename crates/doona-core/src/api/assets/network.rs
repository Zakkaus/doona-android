//! Each source attempt gets its own clock. The staging verifier pulls a bounded slice at a time.
use crate::{
    subscription::fetch::{CONNECTOR, stream},
    tunnel::route::{Route, Upstream},
};
use std::{
    io::{self, Read},
    sync::Arc,
    time::Duration,
};
use tokio::{
    runtime::Handle,
    time::{Instant, timeout_at},
};
use tokio_util::sync::CancellationToken;
use url::Url;

const META: Duration = Duration::from_secs(20);
const IDLE: Duration = Duration::from_secs(30);
const FILE: Duration = Duration::from_secs(900);

struct Budget {
    end: Instant,
    idle: Duration,
}
impl Budget {
    fn new(file: bool) -> Self {
        Self {
            end: Instant::now() + if file { FILE } else { META },
            idle: IDLE,
        }
    }
    async fn wait<T>(
        &self,
        cancel: &CancellationToken,
        work: impl Future<Output = io::Result<T>>,
    ) -> io::Result<T> {
        let until = self.end.min(Instant::now() + self.idle);
        tokio::select! {biased;
            ()=cancel.cancelled()=>Err(io::ErrorKind::Interrupted.into()),
            result=timeout_at(until,work)=>result.map_err(|_|io::ErrorKind::TimedOut)?,
        }
    }
}
pub(super) struct Remote {
    route: Route,
    rt: Handle,
    pub cancel: CancellationToken,
}
impl Remote {
    pub fn new(route: Route, rt: Handle, cancel: CancellationToken) -> Self {
        Self { route, rt, cancel }
    }
    pub fn open(self: &Arc<Self>, address: &str) -> io::Result<Body> {
        let url = Url::parse(address).map_err(io::Error::other)?;
        let budget = Budget::new(url.path().ends_with(".dat"));
        let reader = self
            .rt
            .block_on(budget.wait(&self.cancel, self.request(&url)))?;
        Ok(Body {
            reader,
            budget,
            remote: Arc::clone(self),
        })
    }
    async fn request(
        &self,
        url: &Url,
    ) -> io::Result<stream::Body<tokio_boring::SslStream<Upstream>>> {
        let host = url.host_str().ok_or(io::ErrorKind::InvalidInput)?;
        let stream = self.route.connect_host(host, 443).await?;
        let config = CONNECTOR
            .as_ref()
            .ok_or(io::ErrorKind::Unsupported)?
            .configure()
            .map_err(io::Error::other)?;
        let tls = tokio_boring::connect(config, host, stream)
            .await
            .map_err(|_| io::Error::other("geo TLS handshake"))?;
        stream::exchange(tls, url).await
    }
}
pub(super) struct Body {
    remote: Arc<Remote>,
    budget: Budget,
    reader: stream::Body<tokio_boring::SslStream<Upstream>>,
}
impl Read for Body {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.remote
            .rt
            .block_on(self.budget.wait(&self.remote.cancel, self.reader.read(buf)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::AsyncWriteExt;
    #[tokio::test(start_paused = true)]
    async fn dat_idle_resets_only_when_the_body_moves() {
        let (reader, mut peer) = tokio::io::duplex(1024);
        peer.write_all(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n")
            .await
            .unwrap();
        let mut body =
            stream::exchange(reader, &Url::parse("https://example.org/file.dat").unwrap())
                .await
                .unwrap();
        let cancel = CancellationToken::new();
        let budget = Budget::new(true);
        let sender = tokio::spawn(async move {
            for byte in b"abc" {
                tokio::time::sleep(IDLE / 2).await;
                peer.write_all(&[*byte]).await.unwrap();
            }
            peer
        });
        let mut buf = [0; 8];
        for byte in b"abc" {
            assert_eq!(budget.wait(&cancel, body.read(&mut buf)).await.unwrap(), 1);
            assert_eq!(buf[0], *byte);
        }
        let _peer = sender.await.unwrap();
        let stalled = Instant::now();
        assert_eq!(
            budget
                .wait(&cancel, body.read(&mut buf))
                .await
                .unwrap_err()
                .kind(),
            io::ErrorKind::TimedOut
        );
        assert_eq!(stalled.elapsed(), IDLE);
        assert!(Budget::new(true).end > budget.end);
    }
}
