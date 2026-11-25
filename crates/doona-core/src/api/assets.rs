mod network;
mod storage;
use super::{Core, Gate, Life, lock, ok, parse};
use crate::{
    dto::ErrorCode,
    honkconf::assets::Release,
    rules::{dat::Data, template},
    tunnel::route::Route,
};
use network::{Remote, RequestKind};
use serde::{Deserialize, Serialize};
use std::{
    fs,
    io::{self, Read},
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::Instant,
};
use storage::{Disk, Source};
use tokio_util::sync::CancellationToken;

const PINS: &[u8] = include_bytes!("../../tools/geo-pins.json");
const LATEST: &str = "https://api.github.com/repos/MetaCubeX/meta-rules-dat/releases/latest";
const BRANCH: &str = "https://api.github.com/repos/MetaCubeX/meta-rules-dat/branches/release";

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
enum ResultState {
    #[default]
    Idle,
    Checking,
    Downloading,
    Verifying,
    ReadyOnNextStart,
    UpToDate,
    Activated,
    Failed,
}
#[derive(Default, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct Status {
    source: Source,
    release: Option<String>,
    last_result: ResultState,
    reason: Option<&'static str>,
    update_waiting_for_next_start: bool,
    #[serde(skip)]
    updating: bool,
}
impl Status {
    fn set(&mut self, state: ResultState, reason: Option<&'static str>) {
        self.last_result = state;
        self.reason = reason;
    }
    fn busy(&self) -> bool {
        self.updating || self.update_waiting_for_next_start
    }
    fn installed(&mut self, disk: &Disk) {
        let active = disk.active();
        self.source = active.map_or(Source::Builtin, |g| g.source);
        self.release = active
            .and_then(|g| g.pins().ok())
            .map(|p| p.date().to_owned());
        self.update_waiting_for_next_start = disk.pending();
    }
}
#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase")]
enum Action {
    #[default]
    Status,
    Update,
}
#[derive(Deserialize)]
struct Args {
    #[serde(default)]
    action: Action,
}

pub(super) struct Assets {
    root: PathBuf,
    disk: Mutex<Disk>,
    status: Mutex<Status>,
    pins: Release,
}
impl Assets {
    pub(super) fn new(data_dir: &Path) -> Self {
        Self::with_pins(
            data_dir,
            Release::from_manifest(PINS).expect("compiled geo pins"),
        )
    }
    fn with_pins(data_dir: &Path, pins: Release) -> Self {
        let root = data_dir.join("geo");
        let (disk, failed) = Disk::new(root.clone());
        let mut status = Status::default();
        status.installed(&disk);
        if failed {
            status.set(ResultState::Failed, Some("invalid_generation"));
        } else if disk.pending() {
            status.last_result = ResultState::ReadyOnNextStart;
        }
        Self {
            root,
            disk: Mutex::new(disk),
            status: Mutex::new(status),
            pins,
        }
    }
    /// The active generation's directory, where the engine reads its geodata; the empty root
    /// before one is installed.
    pub(super) fn geo_dir(&self) -> PathBuf {
        lock(&self.disk).active_dir()
    }
    fn update<R: Read>(
        &self,
        mut open: impl FnMut(&str, RequestKind) -> io::Result<R>,
        cancel: &CancellationToken,
    ) -> io::Result<()> {
        let release = metadata(open(LATEST, RequestKind::Metadata)?)?;
        let branch = metadata(open(BRANCH, RequestKind::Metadata)?)?;
        let pins = Release::from_github(&release, &branch)?;
        if lock(&self.disk)
            .active()
            .map(|g| g.pins())
            .transpose()?
            .is_some_and(|p| p.commit() == pins.commit() || p.date() > pins.date())
        {
            lock(&self.status).set(ResultState::UpToDate, None);
            return Ok(());
        }
        lock(&self.status).set(ResultState::Downloading, None);
        fs::create_dir_all(&self.root)?;
        let stage = self.root.join("download.stage");
        storage::remove(&stage)?;
        pins.stage_update(&stage, |url| open(url, RequestKind::Dat))?;
        lock(&self.status).set(ResultState::Verifying, None);
        let result = self.publish_update(&stage, &pins, cancel);
        if result.is_err() {
            storage::remove(&stage)?;
        }
        result
    }

    fn publish_update(
        &self,
        stage: &Path,
        pins: &Release,
        cancel: &CancellationToken,
    ) -> io::Result<()> {
        Data::verify(stage)?;
        Data::load(stage, &template::rules())?;
        let mut disk = lock(&self.disk);
        if cancel.is_cancelled() {
            return Err(io::ErrorKind::Interrupted.into());
        }
        disk.install(stage, pins, Source::Download)?;
        let mut status = lock(&self.status);
        status.update_waiting_for_next_start = true;
        status.set(ResultState::ReadyOnNextStart, None);
        Ok(())
    }
}
fn metadata(mut reader: impl Read) -> io::Result<Vec<u8>> {
    let mut bytes = Vec::new();
    let mut buf = [0; 8192];
    loop {
        let n = reader.read(&mut buf)?;
        if n == 0 {
            return Ok(bytes);
        }
        if bytes.len() + n > 1 << 20 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "geo metadata limit",
            ));
        }
        bytes.extend_from_slice(&buf[..n]);
    }
}
impl Core {
    pub(super) fn activate_assets(
        &self,
        bundle: Option<PathBuf>,
        gate: &Arc<Gate>,
        deadline: Instant,
    ) -> Result<(), ErrorCode> {
        let (tx, rx) = std::sync::mpsc::sync_channel(1);
        let assets = Arc::clone(&self.assets);
        let starting = Arc::clone(gate);
        self.runtime.spawn_blocking(move || {
            let mut disk = lock(&assets.disk);
            if starting.aborted() || Instant::now() >= deadline {
                return;
            }
            let was_pending = disk.pending();
            let (data, failed) = disk.activate(bundle.as_deref(), &assets.pins);
            let mut status = lock(&assets.status);
            status.installed(&disk);
            if !status.updating {
                if failed {
                    status.set(ResultState::Failed, Some("invalid_generation"));
                } else if was_pending
                    || (disk.active().is_some() && status.last_result == ResultState::Idle)
                {
                    status.set(ResultState::Activated, None);
                }
            }
            let _ = tx.send(data);
        });
        let result = rx
            .recv_timeout(deadline.saturating_duration_since(Instant::now()))
            .map_err(|_| {
                if gate.aborted() {
                    ErrorCode::Busy
                } else {
                    ErrorCode::Internal
                }
            })
            .and_then(|data| self.publish_geo(data, gate, deadline));
        if result.is_err() {
            let mut life = lock(&self.life);
            if let Life::Starting { gate: current, tun } = &*life
                && Arc::ptr_eq(current, gate)
            {
                gate.abort();
                tun.revoke();
                *life = Life::Stopped;
            }
        }
        result
    }

    fn publish_geo(
        &self,
        data: Option<Data>,
        gate: &Gate,
        deadline: Instant,
    ) -> Result<(), ErrorCode> {
        if data.is_none() && lock(&self.shared.catalog).model.geo.is_none() {
            return Ok(());
        }
        let _writer = self.writer_until(deadline)?;
        let _life = lock(&self.life);
        if gate.aborted() {
            return Err(ErrorCode::Busy);
        }
        let mut c = lock(&self.shared.catalog);
        let source = c.state.rule_source.clone();
        c.model.geo = data.map(Arc::new);
        c.model.set_source(&source);
        c.revision += 1;
        self.shared.publish(&c);
        Ok(())
    }
    pub(super) fn config_assets(&self, args: &str) -> Result<String, ErrorCode> {
        let args: Args = parse(args)?;
        if matches!(args.action, Action::Status) {
            return Ok(ok(&*lock(&self.assets.status)));
        }
        let life = lock(&self.life);
        let mut status = lock(&self.assets.status);
        if status.busy() {
            return Err(ErrorCode::Busy);
        }
        let tunnel = match &*life {
            Life::Running(t) if !t.session().failed() => t,
            _ => {
                status.set(ResultState::Failed, Some("not_connected"));
                return Err(ErrorCode::FetchFailed);
            }
        };
        let view = self.shared.outbounds.view();
        let Some(dialer) = view.dialer(view.policy().main()) else {
            status.set(ResultState::Failed, Some("no_active_outbound"));
            return Err(ErrorCode::NoNodes);
        };
        let route = Route::Proxy(Arc::new(dialer), Arc::clone(tunnel.session()));
        let remote = Arc::new(Remote::new(
            route,
            self.runtime.handle().clone(),
            tunnel.cancellation(),
        ));
        status.set(ResultState::Checking, None);
        status.updating = true;
        let assets = Arc::clone(&self.assets);
        self.runtime.spawn_blocking(move || {
            if let Err(e) = assets.update(|url, kind| remote.open(url, kind), &remote.cancel) {
                let reason = match e.kind() {
                    io::ErrorKind::Interrupted => "disconnected",
                    io::ErrorKind::InvalidData => "verification_failed",
                    _ => "update_failed",
                };
                lock(&assets.status).set(ResultState::Failed, Some(reason));
            }
            lock(&assets.status).updating = false;
        });
        Ok(ok(&*status))
    }
}
#[cfg(test)]
mod tests;
