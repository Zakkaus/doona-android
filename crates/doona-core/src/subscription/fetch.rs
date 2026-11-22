//! The subscription download: one HTTP/1.1 GET at a time, plain or over BoringSSL with the
//! webpki roots, following at most five redirects within the bound its command gives. It goes out directly
//! (doona is outside its own VPN) and resolves the host with the bootstrap resolvers. The redirect
//! rules and the `Subscription-Userinfo` reading follow honk-core's `src/subscription.rs`
//! (`fetch_following`, `subscription_redirect_error`, `parse_subscription_userinfo`) and
//! `src/marked_http.rs` (`basic_auth`), daeuniverse/honk 8919784a. Errors carry no detail: the URL
//! may hold a token.

use crate::dto::ErrorCode;
use crate::tunnel::route::CONNECT_TIMEOUT;
use base64::Engine;
use boring::ssl::{SslConnector, SslMethod, SslVersion};
use boring::x509::X509;
use boring::x509::store::X509StoreBuilder;
use std::io::ErrorKind;
use std::net::{IpAddr, SocketAddr};
use std::sync::LazyLock;
use std::time::Duration;
use tokio::io::BufReader;
use tokio::io::{
    AsyncBufRead, AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt,
};
use tokio::net::TcpStream;
use url::{Host, Url};

/// What a hostile or broken origin can make the core buffer (honk's bound).
const MAX_BODY: usize = 8 << 20;
const MAX_HEAD: usize = 64 << 10;
const MAX_LINE: usize = 1 << 10;
const MAX_REDIRECTS: usize = 5;
const USER_AGENT: &str = concat!("doona/", env!("CARGO_PKG_VERSION"));

#[derive(Debug, Default, PartialEq, Eq)]
pub(crate) struct Fetched {
    pub body: Vec<u8>,
    pub userinfo: Userinfo,
}

/// The quota a provider reports in `Subscription-Userinfo`, when it does. Each value fits Kotlin's
/// signed `Long`.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct Userinfo {
    pub used_bytes: Option<i64>,
    pub total_bytes: Option<i64>,
    pub expires_at_epoch_ms: Option<i64>,
}

enum Reply {
    Body(Fetched),
    Redirect(String),
}

/// A subscription URL as `addSubscription` takes it: http or https with a host.
pub(crate) fn parse_url(text: &str) -> Result<Url, ErrorCode> {
    let url = Url::parse(text.trim()).map_err(|_| ErrorCode::InvalidArgument)?;
    let web = matches!(url.scheme(), "http" | "https");
    if !web || url.host().is_none() {
        return Err(ErrorCode::InvalidArgument);
    }
    Ok(url)
}

/// Downloads `url` within `bound`, redirects included; every failure is `fetch_failed`.
pub(crate) async fn fetch(url: Url, bound: Duration) -> Result<Fetched, ErrorCode> {
    tokio::time::timeout(bound, follow(url))
        .await
        .map_err(|_| ErrorCode::FetchFailed)?
}

async fn follow(mut url: Url) -> Result<Fetched, ErrorCode> {
    // The URL's user information becomes Basic auth, sent only to the origin.
    let auth = basic_auth(&url);
    let _ = url.set_username("");
    let _ = url.set_password(None);
    let origin = url.clone();
    for hop in 0..=MAX_REDIRECTS {
        let same = same_origin(&origin, &url);
        let location = match get(&url, auth.as_deref().filter(|_| same)).await? {
            Reply::Body(fetched) => return Ok(fetched),
            Reply::Redirect(location) => location,
        };
        if hop == MAX_REDIRECTS {
            break;
        }
        url = next_hop(&origin, &url, &location)?;
    }
    Err(ErrorCode::FetchFailed)
}

/// The URL a redirect from `current` to `location` leads to, without credentials, unless the
/// redirect rules refuse it.
fn next_hop(origin: &Url, current: &Url, location: &str) -> Result<Url, ErrorCode> {
    let mut next = current.join(location).map_err(|_| ErrorCode::FetchFailed)?;
    let _ = next.set_username("");
    let _ = next.set_password(None);
    if refused_redirect(origin, current, &next) {
        return Err(ErrorCode::FetchFailed);
    }
    Ok(next)
}

/// As honk-core's `marked_http::basic_auth`.
fn basic_auth(url: &Url) -> Option<String> {
    if url.username().is_empty() && url.password().is_none() {
        return None;
    }
    let decode = |text: &str| {
        percent_encoding::percent_decode_str(text)
            .decode_utf8_lossy()
            .into_owned()
    };
    let credentials = format!(
        "{}:{}",
        decode(url.username()),
        decode(url.password().unwrap_or_default())
    );
    let token = base64::engine::general_purpose::STANDARD.encode(credentials);
    Some(format!("Basic {token}"))
}

fn same_origin(a: &Url, b: &Url) -> bool {
    a.scheme() == b.scheme()
        && a.host_str() == b.host_str()
        && a.port_or_known_default() == b.port_or_known_default()
}

/// honk's redirect rules: never from https to plain text, never to a private address literal
/// unless the subscription itself is on one. A downgrade is judged against the hop being left,
/// not the first URL as in honk, so http, then https, then http again is refused too.
fn refused_redirect(origin: &Url, current: &Url, next: &Url) -> bool {
    let downgrade = current.scheme() == "https" && next.scheme() != "https";
    let web = matches!(next.scheme(), "http" | "https");
    downgrade || !web || (private_literal(next) && !private_literal(origin))
}

fn private_literal(url: &Url) -> bool {
    let ip = match url.host() {
        Some(Host::Ipv4(a)) => IpAddr::V4(a),
        Some(Host::Ipv6(a)) => IpAddr::V6(a).to_canonical(),
        _ => return false,
    };
    match ip {
        IpAddr::V4(a) => {
            a.is_loopback() || a.is_private() || a.is_link_local() || a.is_unspecified()
        }
        IpAddr::V6(a) => {
            a.is_loopback()
                || a.is_unique_local()
                || a.is_unicast_link_local()
                || a.is_unspecified()
        }
    }
}

async fn get(url: &Url, auth: Option<&str>) -> Result<Reply, ErrorCode> {
    let port = url.port_or_known_default().ok_or(ErrorCode::FetchFailed)?;
    let stream = connect(url, port).await?;
    let request = request(url, auth);
    if url.scheme() == "https" {
        let host = url.host_str().ok_or(ErrorCode::FetchFailed)?;
        let host = host.trim_start_matches('[').trim_end_matches(']');
        let config = CONNECTOR
            .as_ref()
            .ok_or(ErrorCode::FetchFailed)?
            .configure()
            .map_err(|_| ErrorCode::FetchFailed)?;
        let tls = tokio_boring::connect(config, host, stream)
            .await
            .map_err(|_| ErrorCode::FetchFailed)?;
        exchange(tls, &request).await
    } else {
        exchange(stream, &request).await
    }
}

/// Every address the bootstrap resolvers give, in their order, until one connects.
async fn connect(url: &Url, port: u16) -> Result<TcpStream, ErrorCode> {
    let addrs: Vec<SocketAddr> = match url.host() {
        Some(Host::Ipv4(a)) => vec![(a, port).into()],
        Some(Host::Ipv6(a)) => vec![(a, port).into()],
        Some(Host::Domain(name)) => crate::bootstrap::lookup(name)
            .await
            .map_err(|_| ErrorCode::FetchFailed)?
            .into_iter()
            .map(|ip| SocketAddr::new(ip, port))
            .collect(),
        None => Vec::new(),
    };
    for addr in addrs {
        if let Ok(Ok(stream)) =
            tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(addr)).await
        {
            return Ok(stream);
        }
    }
    Err(ErrorCode::FetchFailed)
}

/// BoringSSL with the webpki roots, as honk-outbound's TLS; built once.
static CONNECTOR: LazyLock<Option<SslConnector>> = LazyLock::new(|| {
    let mut builder = SslConnector::builder(SslMethod::tls()).ok()?;
    builder
        .set_min_proto_version(Some(SslVersion::TLS1_2))
        .ok()?;
    let mut roots = X509StoreBuilder::new().ok()?;
    for der in webpki_root_certs::TLS_SERVER_ROOT_CERTS {
        if let Ok(cert) = X509::from_der(der.as_ref()) {
            roots.add_cert(cert).ok()?;
        }
    }
    builder.set_cert_store_builder(roots);
    Some(builder.build())
});

fn request(url: &Url, auth: Option<&str>) -> Vec<u8> {
    let mut target = url.path().to_owned();
    if let Some(query) = url.query() {
        target.push('?');
        target.push_str(query);
    }
    let host = match url.port() {
        Some(port) => format!("{}:{port}", url.host_str().unwrap_or_default()),
        None => url.host_str().unwrap_or_default().to_owned(),
    };
    let mut head = format!(
        "GET {target} HTTP/1.1\r\nHost: {host}\r\nUser-Agent: {USER_AGENT}\r\nAccept: */*\r\n\
         Accept-Encoding: identity\r\nConnection: close\r\n"
    );
    if let Some(auth) = auth {
        head.push_str(&format!("Authorization: {auth}\r\n"));
    }
    head.push_str("\r\n");
    head.into_bytes()
}

/// The parts of a response head the fetch uses.
struct Head {
    status: u16,
    location: Option<String>,
    chunked: bool,
    length: Option<usize>,
    userinfo: Userinfo,
}

async fn exchange<S: AsyncRead + AsyncWrite + Unpin>(
    mut stream: S,
    request: &[u8],
) -> Result<Reply, ErrorCode> {
    stream
        .write_all(request)
        .await
        .map_err(|_| ErrorCode::FetchFailed)?;
    stream.flush().await.map_err(|_| ErrorCode::FetchFailed)?;
    let mut reader = BufReader::new(stream);
    let head = parse_head(&read_head(&mut reader).await?)?;
    if matches!(head.status, 301 | 302 | 303 | 307 | 308) {
        return head
            .location
            .map(Reply::Redirect)
            .ok_or(ErrorCode::FetchFailed);
    }
    if !(200..300).contains(&head.status) {
        return Err(ErrorCode::FetchFailed);
    }
    let body = if head.status == 204 {
        Vec::new()
    } else if head.chunked {
        read_chunked(&mut reader).await?
    } else if let Some(length) = head.length {
        read_exact(&mut reader, length).await?
    } else {
        read_to_end(&mut reader).await?
    };
    Ok(Reply::Body(Fetched {
        body,
        userinfo: head.userinfo,
    }))
}

async fn read_head<R: AsyncBufRead + Unpin>(reader: &mut R) -> Result<Vec<u8>, ErrorCode> {
    let mut head = Vec::new();
    loop {
        let n = (&mut *reader)
            .take((MAX_HEAD - head.len()) as u64 + 1)
            .read_until(b'\n', &mut head)
            .await
            .map_err(|_| ErrorCode::FetchFailed)?;
        if n == 0 || head.len() > MAX_HEAD {
            return Err(ErrorCode::FetchFailed);
        }
        if head.ends_with(b"\r\n\r\n") || head.ends_with(b"\n\n") {
            return Ok(head);
        }
    }
}

fn parse_head(bytes: &[u8]) -> Result<Head, ErrorCode> {
    let mut headers = [httparse::EMPTY_HEADER; 64];
    let mut response = httparse::Response::new(&mut headers);
    match response.parse(bytes) {
        Ok(httparse::Status::Complete(_)) => {}
        _ => return Err(ErrorCode::FetchFailed),
    }
    let header = |name: &str| {
        response
            .headers
            .iter()
            .find(|h| h.name.eq_ignore_ascii_case(name))
            .and_then(|h| std::str::from_utf8(h.value).ok())
    };
    let chunked =
        header("transfer-encoding").is_some_and(|v| v.to_ascii_lowercase().contains("chunked"));
    Ok(Head {
        status: response.code.ok_or(ErrorCode::FetchFailed)?,
        location: header("location").map(str::to_owned),
        chunked,
        length: header("content-length").and_then(|v| v.trim().parse().ok()),
        userinfo: header("subscription-userinfo").map_or_else(Userinfo::default, parse_userinfo),
    })
}

async fn read_exact<R: AsyncRead + Unpin>(
    reader: &mut R,
    length: usize,
) -> Result<Vec<u8>, ErrorCode> {
    if length > MAX_BODY {
        return Err(ErrorCode::FetchFailed);
    }
    let mut body = vec![0; length];
    reader
        .read_exact(&mut body)
        .await
        .map_err(|_| ErrorCode::FetchFailed)?;
    Ok(body)
}

/// A body that ends with the connection. A TLS peer that closes without close_notify still
/// ends it; the parser refuses a truncated list as a whole only when no line survives.
async fn read_to_end<R: AsyncRead + Unpin>(reader: &mut R) -> Result<Vec<u8>, ErrorCode> {
    let mut body = Vec::new();
    match reader
        .take(MAX_BODY as u64 + 1)
        .read_to_end(&mut body)
        .await
    {
        Ok(_) => {}
        Err(e) if e.kind() == ErrorKind::UnexpectedEof && !body.is_empty() => {}
        Err(_) => return Err(ErrorCode::FetchFailed),
    }
    if body.len() > MAX_BODY {
        return Err(ErrorCode::FetchFailed);
    }
    Ok(body)
}

async fn read_chunked<R: AsyncBufRead + Unpin>(reader: &mut R) -> Result<Vec<u8>, ErrorCode> {
    let mut body = Vec::new();
    loop {
        let line = read_line(reader).await?;
        let size = line.split(';').next().unwrap_or_default().trim();
        let size = usize::from_str_radix(size, 16).map_err(|_| ErrorCode::FetchFailed)?;
        if size == 0 {
            // Trailers, if any, end with an empty line; the connection closes after them.
            while !read_line(reader).await?.trim().is_empty() {}
            return Ok(body);
        }
        if body.len() + size > MAX_BODY {
            return Err(ErrorCode::FetchFailed);
        }
        let start = body.len();
        body.resize(start + size, 0);
        reader
            .read_exact(&mut body[start..])
            .await
            .map_err(|_| ErrorCode::FetchFailed)?;
        read_line(reader).await?;
    }
}

/// One line of at most [`MAX_LINE`] bytes; the end of the stream ends it too.
async fn read_line<R: AsyncBufRead + Unpin>(reader: &mut R) -> Result<String, ErrorCode> {
    let mut line = Vec::new();
    (&mut *reader)
        .take(MAX_LINE as u64)
        .read_until(b'\n', &mut line)
        .await
        .map_err(|_| ErrorCode::FetchFailed)?;
    String::from_utf8(line).map_err(|_| ErrorCode::FetchFailed)
}

/// `upload=…; download=…; total=…; expire=…`: used is upload plus download, a zero total or
/// expiry means none, and the expiry is in seconds. A value a signed 64-bit integer cannot hold,
/// or a sum or millisecond time that overflows one, leaves its field out.
fn parse_userinfo(header: &str) -> Userinfo {
    let (mut upload, mut download, mut info) = (None, None, Userinfo::default());
    for pair in header.split(';') {
        let Some((key, value)) = pair.split_once('=') else {
            continue;
        };
        // Digits only, as honk reads them; a number too long for an i64 is present but unusable.
        let value = value.trim();
        if value.is_empty() || !value.bytes().all(|b| b.is_ascii_digit()) {
            continue;
        }
        let value = value.parse::<i64>().ok();
        match key.trim() {
            "upload" => upload = Some(value),
            "download" => download = Some(value),
            "total" => info.total_bytes = value.filter(|v| *v != 0),
            "expire" => {
                info.expires_at_epoch_ms =
                    value.filter(|v| *v != 0).and_then(|v| v.checked_mul(1000));
            }
            _ => {}
        }
    }
    if upload.is_some() || download.is_some() {
        let part = |p: Option<Option<i64>>| p.unwrap_or(Some(0));
        info.used_bytes = part(upload)
            .zip(part(download))
            .and_then(|(u, d)| u.checked_add(d));
    }
    info
}

#[cfg(test)]
mod tests;
