//! Two private generation slots. Only the atomically replaced journal names an installed
//! pair; a crash while copying or verifying the other slot cannot overwrite the active one.
use crate::{
    honkconf::assets::Release,
    rules::{dat::Data, template},
    state::{sync_dir, write_synced},
};
use serde::{Deserialize, Serialize};
use std::{
    fs, io,
    path::{Path, PathBuf},
};

#[derive(Clone, Copy, Serialize, Deserialize, Debug, PartialEq, Eq, Default)]
#[serde(rename_all = "snake_case")]
pub(super) enum Source {
    #[default]
    Builtin,
    Apk,
    Download,
}
#[derive(Clone, Serialize, Deserialize)]
pub(super) struct Generation {
    slot: bool,
    pub source: Source,
    manifest: serde_json::Value,
}
impl Generation {
    pub fn pins(&self) -> io::Result<Release> {
        Release::from_manifest(&serde_json::to_vec(&self.manifest)?)
    }
}
#[derive(Clone, Default, Serialize, Deserialize)]
struct Journal {
    active: Option<Generation>,
    pending: Option<Generation>,
}
pub(super) struct Disk {
    root: PathBuf,
    journal: Journal,
}

pub(super) fn remove(dir: &Path) -> io::Result<()> {
    match fs::remove_dir_all(dir) {
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(()),
        other => other,
    }
}
impl Disk {
    pub fn new(root: PathBuf) -> (Self, bool) {
        let journal = match fs::read(root.join("generations.json")) {
            Ok(b) => serde_json::from_slice(&b).ok(),
            Err(e) if e.kind() == io::ErrorKind::NotFound => Some(Journal::default()),
            Err(_) => None,
        };
        let failed = journal.is_none();
        (
            Self {
                root,
                journal: journal.unwrap_or_default(),
            },
            failed,
        )
    }
    pub fn active(&self) -> Option<&Generation> {
        self.journal.active.as_ref()
    }
    pub fn pending(&self) -> bool {
        self.journal.pending.is_some()
    }
    fn slot(&self, slot: bool) -> PathBuf {
        self.root.join(if slot { "b" } else { "a" })
    }
    fn commit(&mut self, journal: Journal) -> io::Result<()> {
        let temp = self.root.join("generations.tmp");
        write_synced(&temp, &serde_json::to_vec(&journal)?)?;
        fs::rename(temp, self.root.join("generations.json"))?;
        self.journal = journal;
        sync_dir(&self.root)
    }
    pub fn install(&mut self, stage: &Path, pins: &Release, source: Source) -> io::Result<()> {
        let mut journal = self.journal.clone();
        let slot = !journal.active.as_ref().is_some_and(|g| g.slot);
        let dir = self.slot(slot);
        remove(&dir)?;
        fs::rename(stage, dir)?;
        let gen_ = Some(Generation {
            slot,
            source,
            manifest: pins.manifest(),
        });
        if source == Source::Apk {
            journal.active = gen_;
            journal.pending = None;
        } else {
            journal.pending = gen_;
        }
        self.commit(journal)
    }
    fn load(&self, g: &Generation) -> io::Result<Data> {
        let dir = self.slot(g.slot);
        g.pins()?.verify(&dir)?;
        Data::load(&dir, &template::rules())
    }
    fn apk(&mut self, bundle: &Path, pins: &Release) -> io::Result<Data> {
        fs::create_dir_all(&self.root)?;
        let stage = self.root.join("apk.stage");
        remove(&stage)?;
        pins.stage_bundle(bundle, &stage)?;
        let data = Data::verify(&stage).and_then(|()| Data::load(&stage, &template::rules()));
        if data.is_err() {
            remove(&stage)?;
        }
        let data = data?;
        self.install(&stage, pins, Source::Apk)?;
        Ok(data)
    }
    pub fn activate(&mut self, bundle: Option<&Path>, pins: &Release) -> (Option<Data>, bool) {
        let mut failed = false;
        if let Some(next) = self.journal.pending.clone() {
            let candidate = self.load(&next);
            let mut journal = self.journal.clone();
            journal.pending = None;
            if candidate.is_ok() {
                journal.active = Some(next);
            }
            if self.commit(journal).is_ok()
                && let Ok(data) = candidate
            {
                return (Some(data), false);
            }
            failed = true;
        }
        let active = self.active().cloned();
        let newer = active
            .as_ref()
            .and_then(|g| g.pins().ok())
            .is_none_or(|p| p.date() < pins.date());
        if let Some(dir) = bundle.filter(|_| newer) {
            match self.apk(dir, pins) {
                Ok(data) => return (Some(data), failed),
                Err(_) => failed = true,
            }
        }
        if let Some(active) = active {
            if let Ok(data) = self.load(&active) {
                return (Some(data), failed);
            }
            failed = true;
            if let Some(dir) = bundle.filter(|_| !newer)
                && let Ok(data) = self.apk(dir, pins)
            {
                return (Some(data), true);
            }
            let _ = self.commit(Journal::default());
        }
        (None, failed)
    }
}
