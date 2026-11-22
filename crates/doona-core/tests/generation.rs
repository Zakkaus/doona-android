//! A generation with proxied flows open: `traffic` counts every byte through the tun and the
//! open app flows, and `stop` keeps its 1 s bound with 100 proxied flows open, a carrier waiting
//! in its pool and a dial stalled mid-handshake, and leaves no descriptor open that was not open
//! before the first dial. The checks read /proc/self/fd, so these tests run one at a time.

mod common;

use common::client::Client;
use common::global::{Global, WAIT, pattern, ping, socks_link};
use common::servers::{self, Seen};
use common::{anytls, socks, wait_for};
use doonacore::dto::Traffic;
use std::collections::BTreeSet;
use std::sync::atomic::Ordering::Relaxed;
use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, Instant};

fn serial() -> MutexGuard<'static, ()> {
    static SERIAL: Mutex<()> = Mutex::new(());
    SERIAL.lock().unwrap_or_else(|e| e.into_inner())
}

/// Each open descriptor with what it refers to (a socket's inode, say), so a number reused by a
/// new descriptor still counts as new; the listing's own descriptor is left out.
fn open_fds() -> BTreeSet<(String, String)> {
    std::fs::read_dir("/proc/self/fd")
        .unwrap()
        .filter_map(|e| {
            let path = e.ok()?.path();
            let target = std::fs::read_link(&path).ok()?.display().to_string();
            let listing = target.starts_with("/proc/");
            (!listing).then(|| (path.display().to_string(), target))
        })
        .collect()
}

fn traffic(g: &Global) -> Traffic {
    g.snapshot().traffic
}

/// Turns the client until the core's totals equal what crossed the tun, with `flows` open.
fn settles(g: &Global, c: &mut Client, flows: u32) -> bool {
    c.until(WAIT, |c| {
        let t = traffic(g);
        t.active_flows == flows && t.up_total == c.sent && t.down_total == c.received
    })
}

fn all_closed(seen: &Seen) -> bool {
    seen.closed.load(Relaxed) == seen.connections.load(Relaxed)
}

#[test]
fn traffic_counts_every_tun_byte_and_the_open_flows() {
    let _s = serial();
    let (echo, _) = servers::tcp_echo();
    let (udp, _) = servers::udp_echo();
    let (proxy, _) = socks::socks5();
    let g = Global::new(&[socks_link(proxy, "s5")], vec![]);
    let mut c = g.start();
    let ids: Vec<_> = (0..3).map(|_| c.connect(echo)).collect();
    for (i, id) in ids.iter().enumerate() {
        assert!(ping(&mut c, *id, &pattern(50_000 + i)));
    }
    c.send_udp(udp, &[7; 900]);
    assert!(c.recv_udp(udp, WAIT).is_some());
    assert!(
        settles(&g, &mut c, 4),
        "{:?} vs {}/{}",
        traffic(&g),
        c.sent,
        c.received
    );
    assert!(c.sent > 150_000 && c.received > 150_000);
    for id in ids {
        assert_eq!(c.exchange(id, b"").unwrap(), b"");
    }
    // The UDP association stays until it idles out.
    assert!(
        settles(&g, &mut c, 1),
        "{:?} vs {}/{}",
        traffic(&g),
        c.sent,
        c.received
    );
}

#[test]
fn stop_ends_100_proxied_flows_within_a_second_and_leaves_no_descriptor() {
    let _s = serial();
    let (echo, echo_seen) = servers::tcp_echo();
    let (udp, _) = servers::udp_echo();
    let (proxy, proxy_seen) = socks::socks5();
    let g = Global::new(&[socks_link(proxy, "s5")], vec![]);
    let before = open_fds();
    let mut c = g.start();
    let ids: Vec<_> = (0..100).map(|_| c.connect(echo)).collect();
    for id in &ids {
        assert!(ping(&mut c, *id, b"x"));
    }
    c.send_udp(udp, b"x");
    assert!(c.recv_udp(udp, WAIT).is_some());
    assert_eq!(traffic(&g).active_flows, 101);
    assert!(
        open_fds().difference(&before).count() > 100,
        "the flows hold their sockets"
    );
    stops_within_a_second(&g);
    drop(c);
    // 100 flows and the UDP association's control connection.
    assert_eq!(proxy_seen.connections.load(Relaxed), 101);
    assert!(wait_for(WAIT, || all_closed(&proxy_seen) && all_closed(&echo_seen)));
    assert_fds(&before);
}

/// An AnyTLS session outlives the flow that opened it, waiting in its pool for the next stream,
/// and a dial through another node is stuck at the SOCKS5 greeting when `stop` comes: both end.
#[test]
fn stop_closes_a_pooled_carrier_and_a_stalled_handshake() {
    let _s = serial();
    let (at, at_seen) = anytls::anytls();
    let (stall, stall_seen) = servers::stall();
    let g = Global::new(
        &[anytls::link(at, "at"), socks_link(stall, "stall")],
        vec![],
    );
    let before = open_fds();
    let mut c = g.start();
    // The AnyTLS server echoes any stream; the destination is never dialed.
    let id = c.connect(servers::closed_port());
    assert_eq!(c.exchange(id, b"pooled").unwrap(), b"pooled");
    assert!(c.until(WAIT, |_| traffic(&g).active_flows == 0));
    g.select("stall");
    let _stalled = c.connect(servers::closed_port());
    assert!(c.until(WAIT, |_| stall_seen.connections.load(Relaxed) == 1));
    assert_eq!(at_seen.connections.load(Relaxed), 1);
    assert_eq!(
        at_seen.closed.load(Relaxed),
        0,
        "the session waits in its pool"
    );
    stops_within_a_second(&g);
    drop(c);
    assert!(wait_for(WAIT, || all_closed(&at_seen) && all_closed(&stall_seen)));
    assert_fds(&before);
}

fn stops_within_a_second(g: &Global) {
    let begun = Instant::now();
    g.core.stop();
    let took = begun.elapsed();
    assert!(took < Duration::from_secs(1), "{took:?}");
}

/// No descriptor is open that was not open at `before`. Other tests of this binary may close
/// theirs meanwhile, so the count can only fall.
fn assert_fds(before: &BTreeSet<(String, String)>) {
    assert!(
        wait_for(WAIT, || open_fds().is_subset(before)),
        "left open: {:?}",
        open_fds().difference(before).collect::<Vec<_>>()
    );
}
