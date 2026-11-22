//! A generation with proxied flows open: `traffic` counts every byte through the tun and the
//! open app flows, and `stop` keeps its 1 s bound with 100 proxied flows open and leaves no
//! descriptor behind. The fd counts read /proc/self/fd, so these tests run one at a time.

mod common;

use common::client::Client;
use common::global::{Global, WAIT, pattern, ping, socks_link};
use common::servers::{self, Seen};
use common::{socks, wait_for};
use doonacore::dto::Traffic;
use std::sync::atomic::Ordering::Relaxed;
use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, Instant};

fn serial() -> MutexGuard<'static, ()> {
    static SERIAL: Mutex<()> = Mutex::new(());
    SERIAL.lock().unwrap_or_else(|e| e.into_inner())
}

fn open_fds() -> usize {
    std::fs::read_dir("/proc/self/fd").unwrap().count()
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
    // Warm up whatever the runtime, honk and the servers open lazily.
    let mut c = g.start();
    let id = c.connect(echo);
    assert!(ping(&mut c, id, b"warm"));
    c.send_udp(udp, b"warm");
    assert!(c.recv_udp(udp, WAIT).is_some());
    g.core.stop();
    drop(c);
    assert!(wait_for(WAIT, || all_closed(&proxy_seen) && all_closed(&echo_seen)));
    let before = open_fds();

    let mut c = g.start();
    let ids: Vec<_> = (0..100).map(|_| c.connect(echo)).collect();
    for id in &ids {
        assert!(ping(&mut c, *id, b"x"));
    }
    c.send_udp(udp, b"x");
    assert!(c.recv_udp(udp, WAIT).is_some());
    assert_eq!(traffic(&g).active_flows, 101);
    assert!(open_fds() > before + 100, "the flows hold their sockets");
    let begun = Instant::now();
    g.core.stop();
    let took = begun.elapsed();
    assert!(took < Duration::from_secs(1), "{took:?}");
    drop(c);
    // The warm-up's flow and UDP control connection, then 100 flows and one control connection.
    assert_eq!(proxy_seen.connections.load(Relaxed), 103);
    assert!(wait_for(WAIT, || all_closed(&proxy_seen) && all_closed(&echo_seen)));
    assert!(
        wait_for(WAIT, || open_fds() == before),
        "{} open, {before} before",
        open_fds()
    );
}
