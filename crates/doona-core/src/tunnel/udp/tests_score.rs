use super::*;
use crate::dto::Mode;
use crate::outbounds::Outbounds;
use crate::testutil::socks;
use crate::tunnel::route::{Inbound, tests::published_links};
use futures_util::{FutureExt, poll};
use honk_outbound::group::{ScoreBudgetCounters, ScoreOutcome, SelectionNetwork};
use std::net::{Ipv4Addr, SocketAddrV4};
use tokio::time::advance;

#[derive(Clone, Copy)]
enum Reply {
    Echo,
    Foreign,
    Silent,
    OverBudget,
}

async fn association(outbounds: &Outbounds, peer: &UdpSocket, reply: Reply, packets: u8) -> usize {
    let remote = peer.local_addr().unwrap();
    let pair = (([127, 0, 0, 1], 1234).into(), remote);
    let mut nat = Nat::new(Budget::new(64), Duration::from_millis(500));
    let (events, _wakes) = mpsc::unbounded_channel();
    let session = Arc::new(Session::new(0, Default::default()));
    let open = || {
        Some(Route::of(
            Inbound::Tun,
            (remote, Network::Udp),
            Mode::Global,
            outbounds,
            &session,
        ))
    };
    let (opened, route) = nat.send(pair, &[0], &events, open).unwrap();
    let id = opened.id;
    for i in 1..packets {
        nat.send(pair, &[i], &events, open);
    }
    let serve = async {
        let mut buf = [0; 1];
        for i in 0..packets {
            let (_, from) = peer.recv_from(&mut buf).await.unwrap();
            assert_eq!(buf, [i]);
            match reply {
                Reply::Echo => peer.send_to(&buf, from).await.unwrap(),
                Reply::Foreign => UdpSocket::bind("127.0.0.1:0")
                    .await
                    .unwrap()
                    .send_to(&buf, from)
                    .await
                    .unwrap(),
                Reply::OverBudget => peer.send_to(&[1; 65], from).await.unwrap(),
                Reply::Silent => 0,
            };
        }
    };
    let (_, served) = tokio::join!(
        relay(opened, remote, (route, None), events),
        tokio::time::timeout(Duration::from_secs(5), serve)
    );
    served.expect("proxy did not forward the requests");
    nat.flows[&id].down.len()
}

fn trial(reply: Reply, packets: u8) -> (ScoreBudgetCounters, usize) {
    let (a, _) = socks::socks5();
    let (b, _) = socks::socks5();
    let links = format!("socks5://{a}#a\nsocks5://{b}#b");
    let (outbounds, model) = published_links(links.as_bytes(), Default::default(), Mode::Global);
    let manager = &model.score.manager;
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    let queued = runtime.block_on(async {
        let peer = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        assert_eq!(association(&outbounds, &peer, Reply::Echo, 1).await, 1);
        association(&outbounds, &peer, reply, packets).await
    });
    let counts = manager.score_budget_counters("g-Auto", SelectionNetwork::Udp);
    assert_eq!(
        manager.score_state().root_business_starts(),
        2,
        "two associations"
    );
    assert_eq!(counts.trial_starts, 1);
    (counts, queued)
}

#[test]
fn many_datagrams_earn_one_udp_start() {
    let (b, queued) = trial(Reply::Echo, 8);
    assert_eq!((b.business_starts, queued), (2, 8));
}

#[test]
fn foreign_reply_cannot_qualify_the_association() {
    let (b, queued) = trial(Reply::Foreign, 1);
    assert_eq!((b.trial_failure, b.trial_success, queued), (1, 0, 0));
}

#[test]
fn reply_idle_succeeds_but_unanswered_idle_fails() {
    let (b, _) = trial(Reply::Echo, 1);
    assert_eq!((b.trial_success, b.trial_failure), (1, 0));
    let (b, _) = trial(Reply::Silent, 1);
    assert_eq!((b.trial_success, b.trial_failure), (0, 1));
}

#[test]
fn local_reply_loss_does_not_charge_the_node() {
    let (b, queued) = trial(Reply::OverBudget, 1);
    assert_eq!((b.trial_cancelled, b.trial_failure, queued), (1, 0, 0));
}

const REMOTE: SocketAddr = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::LOCALHOST, 443));

fn pair(port: u16) -> (SocketAddr, SocketAddr) {
    (([127, 0, 0, 1], port).into(), REMOTE)
}

fn open(nat: &mut Nat, port: u16, byte: u8, events: &Events) -> Opened {
    nat.send(pair(port), &[byte], events, || Some(Route::Direct))
        .unwrap()
        .0
}

#[derive(Debug, PartialEq)]
enum Carrier {
    Stream,
    Datagram,
    StalledQuic,
}

/// Accepts [0], blocks [1], and optionally delivers one peer reply.
#[derive(Debug)]
struct Stalled(Carrier, AtomicBool);

fn upstream(carrier: Carrier, reply: bool) -> UdpUpstream {
    let node = Stalled(carrier, AtomicBool::new(reply));
    UdpUpstream::Proxy(Arc::new(node), REMOTE, None)
}

impl PacketTransport for Stalled {
    fn relay_addr(&self) -> SocketAddr {
        REMOTE
    }

    fn send_timeout_is_congestion(&self) -> bool {
        self.0 != Carrier::Stream
    }

    fn quic_path_stalled(&self) -> bool {
        self.0 == Carrier::StalledQuic
    }

    fn send_packet<'a, 'b, 't>(
        &'a self,
        data: &'b [u8],
    ) -> std::pin::Pin<Box<dyn Future<Output = io::Result<()>> + Send + 't>>
    where
        'a: 't,
        'b: 't,
        Self: 't,
    {
        if data == [0] {
            Box::pin(std::future::ready(Ok(())))
        } else {
            Box::pin(std::future::pending())
        }
    }

    fn recv_packet<'a, 'b, 't>(
        &'a self,
        buf: &'b mut [u8],
    ) -> std::pin::Pin<Box<dyn Future<Output = io::Result<(usize, SocketAddr)>> + Send + 't>>
    where
        'a: 't,
        'b: 't,
        Self: 't,
    {
        Box::pin(async move {
            if !self.1.swap(false, Relaxed) {
                return std::future::pending().await;
            }
            buf[0] = 7;
            Ok((1, REMOTE))
        })
    }
}

async fn after_reply(carrier: Carrier, delay: Duration) -> ScoreOutcome {
    let upstream = upstream(carrier, true);
    let mut nat = Nat::new(Budget::new(64), IDLE);
    let (events, _) = mpsc::unbounded_channel();
    let opened = open(&mut nat, 1, 0, &events);
    let id = opened.id;
    let mut run = std::pin::pin!(exchange(opened, &upstream, Tally::new(None), None));
    assert!(poll!(&mut run).is_pending());
    assert_eq!(nat.flows[&id].down.len(), 1);
    advance(delay).await;
    for _ in 0..=DEPTH * 8 {
        nat.send(pair(1), &[1], &events, || unreachable!());
    }
    assert!(poll!(&mut run).is_pending());
    advance(IDLE - delay).await;
    run.now_or_never().expect("association idle deadline")
}

#[tokio::test(start_paused = true)]
async fn reply_idle_before_the_send_deadline_keeps_success() {
    let outcome = after_reply(Carrier::Stream, IDLE / 2).await;
    assert_eq!(outcome, ScoreOutcome::Success);
}

#[tokio::test(start_paused = true)]
async fn a_stalled_send_times_out_even_after_a_reply_and_queue_overflow() {
    let outcome = after_reply(Carrier::Stream, Duration::ZERO).await;
    assert_eq!(outcome, ScoreOutcome::Timeout);
}

#[tokio::test(start_paused = true)]
async fn a_congested_send_deadline_is_cancelled() {
    let outcome = after_reply(Carrier::Datagram, Duration::ZERO).await;
    assert_eq!(outcome, ScoreOutcome::Cancelled);
}

#[tokio::test(start_paused = true)]
async fn a_stalled_quic_path_overrides_packet_congestion() {
    let outcome = after_reply(Carrier::StalledQuic, Duration::ZERO).await;
    assert_eq!(outcome, ScoreOutcome::NodeFailure);
}

#[tokio::test(start_paused = true)]
async fn shared_budget_loss_does_not_charge_an_unanswered_node() {
    let healthy_node = upstream(Carrier::Stream, false);
    let stalled_node = upstream(Carrier::Stream, false);
    let mut nat = Nat::new(Budget::new(2), IDLE);
    let (events, _) = mpsc::unbounded_channel();
    let healthy = open(&mut nat, 1, 0, &events);
    let mut run = std::pin::pin!(exchange(healthy, &healthy_node, Tally::new(None), None));
    assert!(poll!(&mut run).is_pending());
    let [a, b] = [2, 3].map(|port| {
        let opened = open(&mut nat, port, 1, &events);
        exchange(opened, &stalled_node, Tally::new(None), None)
    });
    let mut stalled = std::pin::pin!(futures_util::future::join(a, b));
    assert!(poll!(&mut stalled).is_pending());
    nat.send(pair(1), &[0], &events, || unreachable!());
    assert_eq!(run.await, ScoreOutcome::Cancelled);
}
