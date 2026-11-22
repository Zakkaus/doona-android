//! The stats end to end on the Direct data path (see `datapath.rs` for the harness): what a flow
//! moves shows up in the rate series and the session's peaks with no screen asking, and `stop`
//! folds in the last bytes, so the series adds up to the session's tun totals exactly.

mod common;

use common::client::Client;
use common::servers;
use common::{TempDir, seqpacket_pair, wait_for};
use doonacore::api::Core;
use doonacore::dto::{Reply, Snapshot, Traffic, TrafficSeries};
use doonacore::sys;
use std::os::fd::AsRawFd;
use std::time::Duration;

const CONFIG: &str =
    r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":["192.168.8.1"]}"#;
const WAIT: Duration = Duration::from_secs(5);

struct Setup {
    core: Core,
    client: Client,
    _dir: TempDir,
}

fn setup() -> Setup {
    let dir = TempDir::new("stats");
    let core = Core::new(dir.path().to_str().unwrap()).unwrap();
    assert_eq!(core.call("setMode", r#"{"mode":"Direct"}"#), r#"{"ok":{}}"#);
    let (tun, peer) = seqpacket_pair();
    core.start(sys::dup_cloexec(tun.as_raw_fd()).unwrap(), CONFIG)
        .unwrap();
    drop(tun);
    Setup {
        core,
        client: Client::new(peer),
        _dir: dir,
    }
}

fn snapshot(core: &Core) -> Snapshot {
    serde_json::from_str(&core.snapshot(i64::MAX)).unwrap()
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
    let data: Vec<u8> = (0..2 << 20).map(|i| (i % 251) as u8).collect();
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
fn a_window_outside_one_hour_is_refused() {
    let s = setup();
    for args in [r#"{"seconds":0}"#, r#"{"seconds":3601}"#, r#"{}"#] {
        let reply = s.core.call("trafficSeries", args);
        assert_eq!(reply, r#"{"error":{"code":"invalid_argument"}}"#, "{args}");
    }
    assert_eq!(series(&s.core, 1).up.len(), 1);
}
