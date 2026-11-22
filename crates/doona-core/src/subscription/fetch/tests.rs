//! The download against loopback servers: lengths, chunks, bodies that end with the connection,
//! redirects and their credentials, failures, and the quota header.

use super::*;
use crate::testutil::{http, response, target};
use std::sync::Mutex;

fn run<T>(f: impl Future<Output = T>) -> T {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(f)
}

fn get_url(url: &str) -> Result<Fetched, ErrorCode> {
    run(fetch(parse_url(url).unwrap()))
}

#[test]
fn a_body_with_a_length_and_its_quota() {
    let addr = http(|_| {
        response(
            "200 OK",
            &[(
                "Subscription-Userinfo",
                "upload=1024; download=2048; total=107374182400; expire=1798000000",
            )],
            b"trojan://secret@192.0.2.1:443#a\n",
        )
    });
    let fetched = get_url(&format!("http://{addr}/sub?token=x")).unwrap();
    assert_eq!(fetched.body, b"trojan://secret@192.0.2.1:443#a\n");
    assert_eq!(
        fetched.userinfo,
        Userinfo {
            used_bytes: Some(3072),
            total_bytes: Some(107374182400),
            expires_at_epoch_ms: Some(1798000000000),
        }
    );
}

#[test]
fn chunked_bodies_and_bodies_that_end_with_the_connection() {
    let addr = http(|head| match target(head) {
        "/chunked" => b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n\
            5;x=1\r\nhello\r\n6\r\n world\r\n0\r\nTrailer: t\r\n\r\n"
            .to_vec(),
        _ => b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nuntil the end".to_vec(),
    });
    assert_eq!(
        get_url(&format!("http://{addr}/chunked")).unwrap().body,
        b"hello world"
    );
    let closed = get_url(&format!("http://{addr}/close")).unwrap();
    assert_eq!(closed.body, b"until the end");
    assert_eq!(closed.userinfo, Userinfo::default());
}

#[test]
fn redirects_are_followed_and_credentials_stay_with_the_origin() {
    let heads = std::sync::Arc::new(Mutex::new(Vec::new()));
    let seen = std::sync::Arc::clone(&heads);
    let other = http(move |head| {
        seen.lock().unwrap().push(head.to_owned());
        response("200 OK", &[], b"moved")
    });
    let origin = http(move |head| match target(head) {
        "/sub" => response("302 Found", &[("Location", "/next")], b""),
        "/next" if head.contains("Authorization: Basic dXNlcjpwQHNz\r\n") => {
            let location = format!("http://{other}/final");
            response("307 Temporary Redirect", &[("Location", &location)], b"")
        }
        _ => response("401 Unauthorized", &[], b""),
    });
    let fetched = get_url(&format!("http://user:p%40ss@{origin}/sub")).unwrap();
    assert_eq!(fetched.body, b"moved");
    let heads = heads.lock().unwrap();
    assert_eq!(heads.len(), 1);
    assert!(!heads[0].contains("Authorization"), "{}", heads[0]);
    assert!(heads[0].contains(&format!("User-Agent: {USER_AGENT}\r\n")));
}

#[test]
fn failures_are_fetch_failed() {
    let addr = http(|head| match target(head) {
        "/loop" => response("302 Found", &[("Location", "/loop")], b""),
        "/missing" => response("404 Not Found", &[], b""),
        "/short" => b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort".to_vec(),
        "/large" => b"HTTP/1.1 200 OK\r\nContent-Length: 9000000\r\n\r\n".to_vec(),
        _ => b"not http\r\n\r\n".to_vec(),
    });
    for path in ["/loop", "/missing", "/short", "/large", "/garbage"] {
        let result = get_url(&format!("http://{addr}{path}"));
        assert_eq!(result.err(), Some(ErrorCode::FetchFailed), "{path}");
    }
    // Nothing listens on port 1.
    assert_eq!(
        get_url("http://127.0.0.1:1/").err(),
        Some(ErrorCode::FetchFailed)
    );
}

#[test]
fn only_web_urls_with_a_host_are_taken() {
    for url in [
        "ftp://example.org/s",
        "not a url",
        "https://",
        "file:///etc/hosts",
    ] {
        assert_eq!(
            parse_url(url).err(),
            Some(ErrorCode::InvalidArgument),
            "{url}"
        );
    }
    assert!(parse_url(" https://example.org/s?t=1 ").is_ok());
}

#[test]
fn redirects_never_downgrade_or_reach_into_private_addresses() {
    let url = |u: &str| Url::parse(u).unwrap();
    let public = url("https://example.org/s");
    assert!(refused_redirect(&public, &url("http://example.org/s")));
    assert!(refused_redirect(&public, &url("https://192.168.1.1/s")));
    assert!(refused_redirect(
        &public,
        &url("https://[::ffff:127.0.0.1]/s")
    ));
    assert!(refused_redirect(&public, &url("ftp://example.org/s")));
    assert!(!refused_redirect(
        &public,
        &url("https://cdn.example.net/s")
    ));
    assert!(!refused_redirect(
        &url("http://10.0.0.1/s"),
        &url("http://10.0.0.2/s")
    ));
}

#[test]
fn the_quota_header_reads_like_honk() {
    assert_eq!(
        parse_userinfo("upload=5;total=0;expire=0;download=x"),
        Userinfo {
            used_bytes: Some(5),
            total_bytes: None,
            expires_at_epoch_ms: None,
        }
    );
    assert_eq!(parse_userinfo("nonsense"), Userinfo::default());
}

/// Needs the internet: TLS with the webpki roots and the platform resolver.
#[test]
#[ignore = "needs the internet"]
fn an_https_fetch_reaches_a_public_server() {
    let fetched = get_url("https://www.gstatic.com/generate_204").unwrap();
    assert!(fetched.body.is_empty());
}

#[test]
fn quotas_a_signed_long_cannot_hold_are_left_out() {
    let max = i128::from(i64::MAX);
    let beyond_u64 = parse_userinfo("upload=99999999999999999999; download=5");
    assert_eq!(beyond_u64.used_bytes.map(i128::from), None);
    let info = parse_userinfo(
        "upload=9223372036854775807; download=1; total=18446744073709551615; expire=9223372036854776",
    );
    assert_eq!(info.used_bytes.map(i128::from), None);
    assert_eq!(info.total_bytes.map(i128::from), None);
    assert_eq!(info.expires_at_epoch_ms.map(i128::from), None);
    let info = parse_userinfo("upload=9223372036854775806; download=1; total=9223372036854775807");
    assert_eq!(info.used_bytes.map(i128::from), Some(max));
    assert_eq!(info.total_bytes.map(i128::from), Some(max));
}
