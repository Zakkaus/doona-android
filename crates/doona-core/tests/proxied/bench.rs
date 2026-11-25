//! Run alone on Linux with sing-box in PATH (or set SING_BOX). BENCH_PROTOCOLS accepts a
//! comma-separated subset. Do not use --include-ignored alongside the other proxied tests.

#[path = "cpu.rs"]
mod cpu;
#[path = "peer.rs"]
mod peer;

use crate::common::{client::Client, global::Global, seqpacket_pair};
use honk_netstack::{FlowId, Mode, StreamError};
use std::io::{ErrorKind, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::{
    Arc,
    atomic::{AtomicBool, AtomicU64, Ordering::Relaxed},
};
use std::thread;
use std::time::{Duration, Instant};

const BLOCK: &[u8] = &[0x5a; 64 * 1024];
const WINDOW: Duration = Duration::from_secs(20);
type Counts = Arc<[AtomicU64]>;

fn received(data: &[u8], count: &AtomicU64) {
    assert_eq!((data[0], data[data.len() - 1]), (0x5a, 0x5a));
    count.fetch_add(data.len() as u64, Relaxed);
}

fn transfer(mut socket: TcpStream, send: bool, count: &AtomicU64) {
    let mut buf = [0; BLOCK.len()];
    socket.read_exact(&mut [0]).unwrap();
    loop {
        let io = if send {
            socket.write(BLOCK)
        } else {
            socket.read(&mut buf)
        };
        match io {
            Ok(0) => break,
            Ok(n) if !send => received(&buf[..n], count),
            Ok(_) => (),
            Err(e)
                if matches!(
                    e.kind(),
                    ErrorKind::ConnectionReset
                        | ErrorKind::BrokenPipe
                        | ErrorKind::ConnectionAborted
                ) =>
            {
                break;
            }
            Err(e) => panic!("bulk transfer: {e}"),
        }
    }
}

fn pump(c: &mut Client, ids: &[FlowId], counts: &Counts, send: bool) {
    c.pump();
    for (&id, count) in ids.iter().zip(counts.iter()) {
        if send {
            c.write_now(id, BLOCK);
        } else {
            match c.read_bulk(id) {
                Ok([]) => panic!("unexpected EOF"),
                Ok(data) => received(data, count),
                Err(StreamError::WouldBlock) => (),
                Err(e) => panic!("bulk read: {e:?}"),
            }
        }
    }
}

struct Case<'a> {
    protocol: &'a str,
    flows: usize,
    download: bool,
    counts: Counts,
    idle: f64,
}

impl Case<'_> {
    fn measure(&self, c: &mut Client, dst: SocketAddr, peer: Option<u32>) {
        let ids: Vec<_> = (0..self.flows)
            .map(|_| {
                let id = c.connect(dst);
                assert!(
                    c.until(Duration::from_secs(5), |c| c.write_now(id, &BLOCK[..1])
                        == 1)
                );
                id
            })
            .collect();
        let drive = |c: &mut Client, duration| {
            let start = Instant::now();
            while start.elapsed() < duration {
                pump(c, &ids, &self.counts, !self.download);
            }
            start.elapsed().as_secs_f64()
        };
        drive(c, Duration::from_secs(1));
        for window in 1..=3 {
            let cpu_start = cpu::sample(peer);
            let wall = Instant::now();
            let before: Vec<_> = self.counts.iter().map(|c| c.load(Relaxed)).collect();
            let elapsed = drive(c, WINDOW);
            let bytes: Vec<_> = self
                .counts
                .iter()
                .zip(before)
                .map(|(c, b)| c.load(Relaxed) - b)
                .collect();
            let cpu = cpu::percent(&cpu_start, cpu::sample(peer), wall.elapsed().as_secs_f64());
            assert!(bytes.iter().all(|&n| n > 0), "stalled receiver");
            let direction = if self.download { "download" } else { "upload" };
            println!(
                "GOODPUT {}",
                serde_json::json!({"protocol":self.protocol,"flows":self.flows,"direction":direction,"window":window,"gbps":8e-9*bytes.iter().sum::<u64>() as f64/elapsed,"bytes":bytes,"cpu":cpu,"idle":self.idle})
            );
        }
    }

    fn generator(&self) {
        let (a, b) = seqpacket_pair();
        let mut client = Client::new(a);
        let done = Arc::new(AtomicBool::new(false));
        let (stop, counts, send) = (done.clone(), self.counts.clone(), self.download);
        let worker = thread::Builder::new()
            .name("endpoint".into())
            .spawn(move || {
                // honk Endpoint has no passive open; Transparent accepts TCP on the isolated pair.
                let mut endpoint = Client::with_mode(
                    b,
                    Mode::Transparent {
                        exclude: Default::default(),
                    },
                );
                let mut ids = Vec::with_capacity(counts.len());
                while !stop.load(Relaxed) {
                    while let Some(id) = endpoint.accept() {
                        ids.push(id);
                    }
                    pump(&mut endpoint, &ids, &counts, send);
                }
            })
            .unwrap();
        self.measure(&mut client, SocketAddr::from(([203, 0, 113, 10], 80)), None);
        done.store(true, Relaxed);
        while !worker.is_finished() {
            client.receive(Instant::now());
        }
        worker.join().unwrap();
    }

    fn core(&self) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let mut dst = listener.local_addr().unwrap();
        let (peer, link) = if self.protocol == "direct" {
            // Direct mode never dials this subscription member.
            (None, "socks5://127.0.0.1:1#bench".into())
        } else {
            let (p, link) = peer::start(self.protocol, dst.port());
            dst.set_ip([203, 0, 113, 10].into());
            (Some(p), link)
        };
        let global = Global::new(&[link]);
        if peer.is_some() {
            global.select("bench");
        } else {
            global.call_ok("setMode", r#"{"mode":"Direct"}"#);
        }
        let mut workers = Vec::new();
        for i in 0..self.flows {
            let (listener, counts, send) = (
                listener.try_clone().unwrap(),
                self.counts.clone(),
                self.download,
            );
            workers.push(
                thread::Builder::new()
                    .name("payload".into())
                    .spawn(move || transfer(listener.accept().unwrap().0, send, &counts[i]))
                    .unwrap(),
            );
        }
        self.measure(&mut global.start(), dst, peer.as_ref().map(peer::Peer::pid));
        global.core.stop();
        drop(peer);
        for worker in workers {
            worker.join().unwrap();
        }
    }
}

#[test]
#[ignore = "desktop throughput; requires a loopback sing-box peer and an idle host"]
fn bench_proxy() {
    if !peer::available() {
        eprintln!("SKIP: sing-box unavailable");
        return;
    }
    let protocols = "generator,direct,socks,shadowsocks,vless,trojan,hysteria2";
    let selected = std::env::var("BENCH_PROTOCOLS").unwrap_or_else(|_| protocols.into());
    for protocol in selected.split(',') {
        assert!(
            protocols.split(',').any(|p| p == protocol),
            "unknown protocol: {protocol}"
        );
        for flows in [1, 4] {
            for download in [true, false] {
                let case = Case {
                    protocol,
                    flows,
                    download,
                    counts: (0..flows).map(|_| AtomicU64::new(0)).collect(),
                    idle: cpu::wait_idle(),
                };
                if protocol == "generator" {
                    case.generator();
                } else {
                    case.core();
                }
            }
        }
    }
}
