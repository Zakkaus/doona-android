use std::{collections::BTreeMap, fs, thread, time::Duration};

fn idle_ticks() -> (u64, u64) {
    let stat = fs::read_to_string("/proc/stat").unwrap();
    let ticks: Vec<u64> = stat
        .lines()
        .next()
        .unwrap()
        .split_whitespace()
        .skip(1)
        .take(8)
        .map(|x| x.parse().unwrap())
        .collect();
    (ticks[3], ticks.iter().sum())
}

pub fn wait_idle() -> f64 {
    let mut prev = idle_ticks();
    loop {
        thread::sleep(Duration::from_secs(2));
        let now = idle_ticks();
        let idle = 100.0 * (now.0 - prev.0) as f64 / (now.1 - prev.1) as f64;
        if idle > 90.0 {
            return idle;
        }
        prev = now;
    }
}

type Sample = BTreeMap<(String, u32), u64>;

fn ticks(stat: &str) -> (&str, u64) {
    let (head, fields) = stat.rsplit_once(')').unwrap();
    let name = head.split_once('(').unwrap().1;
    let cpu = fields
        .split_whitespace()
        .skip(11)
        .take(2)
        .map(|x| x.parse::<u64>().unwrap())
        .sum();
    (name, cpu)
}

pub fn sample(peer: Option<u32>) -> Sample {
    let mut out = Sample::new();
    let test = fs::read_link("/proc/thread-self").unwrap();
    for entry in fs::read_dir("/proc/self/task").unwrap() {
        let task = entry.unwrap();
        let stat = match fs::read_to_string(task.path().join("stat")) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
            other => other.unwrap(),
        };
        let (name, ticks) = ticks(&stat);
        let group = if Some(task.file_name().as_os_str()) == test.file_name() {
            "test"
        } else {
            name
        };
        if matches!(group, "doona-core" | "doona-carrier" | "test" | "endpoint") {
            out.insert(
                (
                    group.into(),
                    task.file_name().to_str().unwrap().parse().unwrap(),
                ),
                ticks,
            );
        }
    }
    if let Some(pid) = peer {
        out.insert(
            ("sing-box".into(), pid),
            ticks(&fs::read_to_string(format!("/proc/{pid}/stat")).unwrap()).1,
        );
    }
    out
}

pub fn percent(start: &Sample, end: Sample, elapsed: f64) -> BTreeMap<String, Vec<f64>> {
    // SAFETY: sysconf reads a process-wide constant and takes no pointers.
    let hz = unsafe { libc::sysconf(libc::_SC_CLK_TCK) };
    assert!(hz > 0);
    let mut groups: BTreeMap<String, Vec<f64>> = BTreeMap::new();
    for (key, end) in end {
        let delta = end - start.get(&key).copied().unwrap_or(0);
        groups
            .entry(key.0)
            .or_default()
            .push(100.0 * delta as f64 / hz as f64 / elapsed);
    }
    groups
}
