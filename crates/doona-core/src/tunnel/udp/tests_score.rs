use super::*;
use crate::dto::Mode;
use crate::outbounds::Outbounds;
use crate::testutil::socks;
use crate::tunnel::route::{Inbound, tests::published_links};
use honk_outbound::group::{ScoreBudgetCounters, SelectionNetwork};

#[derive(Clone, Copy)]
enum Reply {
    Echo,
    Foreign,
    Silent,
    OverBudget,
    Stalled,
}

async fn association(outbounds: &Outbounds, peer: &UdpSocket, reply: Reply, packets: u8) -> usize {
    let remote = peer.local_addr().unwrap();
    let pair = (([127, 0, 0, 1], 1234).into(), remote);
    let mut nat = Nat::new(Budget::new(64), Duration::from_millis(500));
    let (events, wakes) = mpsc::unbounded_channel();
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
    if matches!(reply, Reply::Stalled) {
        let (upstream, reporter) = route.bind_udp(remote).await.unwrap();
        let UdpUpstream::Proxy(t, ..) = upstream else {
            panic!("expected proxy transport")
        };
        let blocked = UdpUpstream::Proxy(Arc::new(Stalled(t)), remote, None);
        let fill = fill_after_reply(peer, wakes, &mut nat, pair, &events);
        let (_, filled) = tokio::join!(
            exchange(opened, &blocked, Tally::new(None), reporter.as_ref()),
            tokio::time::timeout(Duration::from_secs(5), fill)
        );
        filled.expect("first reply did not reach the driver");
        return nat.flows[&id].down.len();
    }
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
                Reply::Stalled => unreachable!(),
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

#[test]
fn a_stalled_send_times_out_even_after_a_reply_and_queue_overflow() {
    let (b, queued) = trial(Reply::Stalled, 1);
    assert_eq!((b.trial_failure, b.trial_success, queued), (1, 0, 1));
}

async fn fill_after_reply(
    peer: &UdpSocket,
    mut wakes: mpsc::UnboundedReceiver<Event>,
    nat: &mut Nat,
    pair: (SocketAddr, SocketAddr),
    events: &Events,
) {
    let mut buf = [0];
    let (_, from) = peer.recv_from(&mut buf).await.unwrap();
    peer.send_to(&buf, from).await.unwrap();
    assert!(matches!(wakes.recv().await, Some(Event::Wake(Key::Udp(_)))));
    tokio::time::pause();
    tokio::time::advance(nat.idle / 2).await;
    for _ in 0..=DEPTH * 8 {
        nat.send(pair, &[1], events, || unreachable!());
    }
}

/// The first datagram is forwarded; the next send blocks on the carrier.
#[derive(Debug)]
struct Stalled(Arc<dyn PacketTransport>);

impl PacketTransport for Stalled {
    fn relay_addr(&self) -> SocketAddr {
        self.0.relay_addr()
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
            self.0.send_packet(data)
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
        self.0.recv_packet(buf)
    }
}
