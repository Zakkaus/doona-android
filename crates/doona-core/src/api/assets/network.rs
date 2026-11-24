//! The blocking verifier pulls one HTTP body at a time. Every dial uses the generation's
//! selected outbound; neither metadata nor a CDN retry has a direct connector.
use crate::{
    bootstrap,
    subscription::fetch::{CONNECTOR, USER_AGENT},
    tunnel::route::Route,
};
use bytes::Bytes;
use futures_util::TryStreamExt;
use http_body_util::{BodyExt, Empty};
use hyper::{Request, client::conn::http1};
use hyper_util::rt::TokioIo;
use std::{
    io::{self, Read},
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncRead, AsyncReadExt},
    runtime::Handle,
    time::{Instant, timeout_at},
};
use tokio_util::{io::StreamReader, sync::CancellationToken, task::AbortOnDropHandle};
use url::Url;

pub(super) struct Remote {
    route: Route,
    rt: Handle,
    pub cancel: CancellationToken,
    deadline: Instant,
}
impl Remote {
    pub fn new(route: Route, rt: Handle, cancel: CancellationToken) -> Self {
        Self {
            route,
            rt,
            cancel,
            deadline: Instant::now() + Duration::from_secs(120),
        }
    }
    fn wait<T>(&self, work: impl Future<Output = io::Result<T>>) -> io::Result<T> {
        self.rt.block_on(async {
            tokio::select! {biased;
                ()=self.cancel.cancelled()=>Err(io::ErrorKind::Interrupted.into()),
                result=timeout_at(self.deadline,work)=>result.map_err(|_|io::ErrorKind::TimedOut)?,
            }
        })
    }
    pub fn open(self: &Arc<Self>, url: &str) -> io::Result<Body> {
        self.wait(self.request(url))
    }
    async fn request(self: &Arc<Self>, url: &str) -> io::Result<Body> {
        let url = Url::parse(url).map_err(io::Error::other)?;
        let host = url.host_str().ok_or(io::ErrorKind::InvalidInput)?;
        let mut stream = None;
        for ip in bootstrap::lookup(host).await.map_err(io::Error::other)? {
            if let Ok(s) = self.route.connect((ip, 443).into()).await {
                stream = Some(s);
                break;
            }
        }
        let stream = stream.ok_or(io::ErrorKind::NotConnected)?;
        let config = CONNECTOR
            .as_ref()
            .ok_or(io::ErrorKind::Unsupported)?
            .configure()
            .map_err(io::Error::other)?;
        let tls = tokio_boring::connect(config, host, stream)
            .await
            .map_err(|_| io::Error::other("geo TLS handshake"))?;
        let (mut sender, conn) = http1::handshake(TokioIo::new(tls))
            .await
            .map_err(io::Error::other)?;
        let driver = AbortOnDropHandle::new(tokio::spawn(conn));
        let request = Request::get(url.path())
            .header("host", host)
            .header("user-agent", USER_AGENT)
            .header("accept-encoding", "identity")
            .body(Empty::<Bytes>::new())
            .map_err(io::Error::other)?;
        let response = sender
            .send_request(request)
            .await
            .map_err(io::Error::other)?;
        if !response.status().is_success() {
            return Err(io::Error::other("geo HTTP status"));
        }
        let reader = StreamReader::new(
            response
                .into_body()
                .into_data_stream()
                .map_err(io::Error::other),
        );
        Ok(Body {
            remote: Arc::clone(self),
            reader: Box::new(reader),
            _driver: driver,
        })
    }
}
pub(super) struct Body {
    remote: Arc<Remote>,
    reader: Box<dyn AsyncRead + Unpin + Send>,
    _driver: AbortOnDropHandle<Result<(), hyper::Error>>,
}
impl Read for Body {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.remote.wait(self.reader.read(buf))
    }
}
