//! The stats end to end on the Direct data path (see `datapath.rs` for the harness): what a flow
//! moves shows up in the rate series, the session's peaks and the day's usage with no screen
//! asking; `stop` folds in the last bytes, so they add up to the session's tun totals exactly, and
//! a new process reads the day back.

mod common;

use common::client::Client;
use common::servers;
use common::{TempDir, seqpacket_pair, wait_for};
use doonacore::api::{Core, Options};
use doonacore::dto::{OutboundBytes, Reply, Snapshot, Traffic, TrafficSeries, Usage};
use doonacore::sys;
use std::os::fd::AsRawFd;
use std::time::Duration;

const CONFIG: &str =
    r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":["192.168.8.1"]}"#;
const WAIT: Duration = Duration::from_secs(5);
const ECHOED: usize = 2 << 20;

struct Setup {
    core: Core,
    client: Client,
    dir: TempDir,
}

fn setup() -> Setup {
    setup_in(TempDir::new("stats"))
}

fn setup_in(dir: TempDir) -> Setup {
    setup_with(dir, Options::default())
}

fn setup_with(dir: TempDir, options: Options) -> Setup {
    let core = Core::with_options(common::seeded(&dir), options).unwrap();
    assert_eq!(core.call("setMode", r#"{"mode":"Direct"}"#), r#"{"ok":{}}"#);
    let (tun, peer) = seqpacket_pair();
    core.start(sys::dup_cloexec(tun.as_raw_fd()).unwrap(), CONFIG)
        .unwrap();
    drop(tun);
    Setup {
        core,
        client: Client::new(peer),
        dir,
    }
}

fn snapshot(core: &Core) -> Snapshot {
    serde_json::from_str(&core.snapshot(i64::MAX)).unwrap()
}

fn usage(core: &Core) -> Usage {
    match serde_json::from_str(&core.call("usage", "{}")).unwrap() {
        Reply::Ok(usage) => usage,
        Reply::Error(e) => panic!("usage: {e:?}"),
    }
}

fn series(core: &Core, seconds: u64) -> TrafficSeries {
    let reply = core.call("trafficSeries", &format!(r#"{{"seconds":{seconds}}}"#));
    match serde_json::from_str(&reply).unwrap() {
        Reply::Ok(series) => series,
        Reply::Error(e) => panic!("trafficSeries: {e:?}"),
    }
}

/// Echoes 2 MiB through a direct flow, then waits until the flow is gone and the totals have
/// not moved for a second (the client's last ACKs included).
fn echo_and_settle(s: &mut Setup) -> Traffic {
    let (echo, _) = servers::tcp_echo();
    let data: Vec<u8> = (0..ECHOED).map(|i| (i % 251) as u8).collect();
    let id = s.client.connect(echo);
    assert_eq!(s.client.exchange(id, &data).unwrap().len(), data.len());
    let mut last = Traffic::default();
    assert!(wait_for(WAIT * 2, || {
        s.client.until(Duration::from_secs(1), |_| false);
        let now = snapshot(&s.core).traffic;
        let moved = (now.up_total, now.down_total) != (last.up_total, last.down_total);
        let settled = now.active_flows == 0 && !moved;
        last = now;
        settled
    }));
    last
}

#[test]
fn the_series_and_peaks_fill_with_no_screen_and_add_up_after_stop() {
    let mut s = setup();
    let totals = echo_and_settle(&mut s);
    assert!(totals.down_total > 2 << 20, "{totals:?}");
    // The generation's watch folds the counters in once a second.
    assert!(wait_for(WAIT, || {
        let t = snapshot(&s.core).traffic;
        t.up_peak > 0 && t.down_peak > 0
    }));
    let peaks = snapshot(&s.core).traffic;
    assert!(peaks.down_peak <= peaks.down_total && peaks.up_peak <= peaks.up_total);
    s.core.stop();
    let minute = series(&s.core, 60);
    assert_eq!((minute.step_seconds, minute.up.len()), (1, 60));
    assert_eq!(minute.up.iter().sum::<u64>(), totals.up_total);
    assert_eq!(minute.down.iter().sum::<u64>(), totals.down_total);
    assert_eq!(minute.down_peak, *minute.down.iter().max().unwrap());
    let hour = series(&s.core, 3600);
    assert_eq!((hour.step_seconds, hour.down.len()), (12, 300));
    // Stopped, the snapshot shows no session and no peaks.
    assert_eq!(snapshot(&s.core).traffic, Traffic::default());
}

#[test]
fn the_day_counts_the_session_its_outbound_and_flows_and_outlives_the_process() {
    let mut s = setup();
    let date = usage(&s.core).today.date;
    let totals = echo_and_settle(&mut s);
    s.core.stop();
    let day = usage(&s.core);
    if day.today.date != date {
        return; // Local midnight passed during the test: the day split the bytes.
    }
    let today = snapshot(&s.core).today;
    assert_eq!(
        (today.up_total, today.down_total),
        (totals.up_total, totals.down_total)
    );
    assert_eq!(day.today.up.iter().sum::<u64>(), totals.up_total);
    // One flow, in Direct: its payload exactly, under the tun's totals by the headers.
    assert_eq!(day.today.flows.iter().sum::<u64>(), 1);
    let direct = &day.today.outbounds;
    assert_eq!(direct.len(), 1, "{direct:?}");
    assert_eq!(direct[0].id, "direct");
    assert_eq!(
        (direct[0].up, direct[0].down),
        (ECHOED as u64, ECHOED as u64)
    );
    // No rule routes a flow in Direct.
    assert!(day.today.by_rule.is_empty());
    assert!(totals.down_total > ECHOED as u64);
    // A new process on the same directory reads the day back.
    let Setup { core, dir, .. } = s;
    drop(core);
    let again = setup_in(dir);
    assert_eq!(usage(&again.core).today, day.today);
}

/// The bytes `direct` carried today, as the last fold left them.
fn direct(core: &Core) -> Option<OutboundBytes> {
    let today = usage(core).today.outbounds;
    today.into_iter().find(|o| o.id == "direct")
}

/// A flow draining after the client's FIN goes through the stats folds: the client half-closes and
/// reads nothing while the upstream's reply fills the core's queue, a fold takes the bytes the
/// relay has given while the core holds the flow, and the client then reads the rest and the
/// upstream's FIN within the drain time. The day holds the flow's payload exactly.
#[test]
fn a_draining_flow_counts_its_bytes_through_the_folds() {
    // More than the client's window, the stack and the queue hold: the relay waits on the client.
    const REPLY: usize = 2 << 20;
    let options = Options {
        tcp_drain: Duration::from_secs(10),
        ..Options::default()
    };
    let mut s = setup_with(TempDir::new("stats"), options);
    let date = usage(&s.core).today.date;
    let id = s.client.connect(servers::sender(REPLY));
    assert!(s.client.until(WAIT, |c| c.write_now(id, b"GET") == 3));
    s.client.shutdown(id);
    // The client turns without reading until a fold has taken part of the reply.
    let core = &s.core;
    let taken = s
        .client
        .until(WAIT, |_| direct(core).is_some_and(|d| d.down > 0));
    assert!(taken, "no fold took the draining flow's bytes");
    let held = direct(core).unwrap();
    assert!(held.down < REPLY as u64, "{held:?}");
    assert_eq!(snapshot(core).traffic.active_flows, 1);
    // The drain holds the flow for the client, which now reads the reply to its end.
    assert_eq!(s.client.read_to_end(id).unwrap().len(), REPLY);
    assert!(wait_for(WAIT, || snapshot(&s.core).traffic.active_flows == 0));
    s.core.stop();
    let day = usage(&s.core);
    if day.today.date != date {
        return; // Local midnight passed during the test: the day split the bytes.
    }
    assert_eq!(day.today.flows.iter().sum::<u64>(), 1);
    let carried = direct(&s.core).unwrap();
    assert_eq!((carried.up, carried.down), (3, REPLY as u64));
}

#[test]
fn a_window_outside_one_hour_is_refused() {
    let s = setup();
    for args in [r#"{"seconds":0}"#, r#"{"seconds":3601}"#, r#"{}"#] {
        let reply = s.core.call("trafficSeries", args);
        assert_eq!(reply, r#"{"error":{"code":"invalid_argument"}}"#, "{args}");
    }
    assert_eq!(series(&s.core, 1).up.len(), 1);
}
