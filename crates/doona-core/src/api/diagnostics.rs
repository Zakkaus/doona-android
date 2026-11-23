//! `diagnostics`, `setLogLevel` and `clearCrashes` (core-api.md), and the ring's event for every
//! command: its name from the closed list, its error code, what the command counted and how long
//! it took. Reads that screens repeat are `debug`, other commands `info`, failures `warn`
//! (`internal`: `error`).

use super::{Core, Life, empty, error_reply, lock, ok, parse};
use crate::catalog::Model;
use crate::diag::report::{
    ClearCrashesArgs, CoreBuild, DiagEvent, Diagnostics, DiagnosticsArgs, LastSnapshot, Level,
    LevelArgs, StatsSummary,
};
use crate::diag::{Count, Diag, Event, Seen, crash};
use crate::dto::{self, ErrorCode, Mode, Traffic};
use crate::state::{Loaded, State};
use crate::stats::Clock;
use std::sync::Arc;
use std::time::Instant;

/// The commands, by the names the log may hold; never caller text.
const COMMANDS: [&str; 15] = [
    "select",
    "testLatency",
    "addSubscription",
    "updateSubscription",
    "removeSubscription",
    "setMode",
    "setRuleSource",
    "setLogLevel",
    "clearCrashes",
    "trafficSeries",
    "usage",
    "nodes",
    "rules",
    "effectiveConfig",
    "diagnostics",
];
/// The reads among them that screens repeat.
const READS: [&str; 6] = [
    "trafficSeries",
    "usage",
    "nodes",
    "rules",
    "effectiveConfig",
    "diagnostics",
];

impl Core {
    /// Runs `run`, which may note counts and a detail on the command's event, then records it.
    pub(super) fn traced(
        &self,
        command: &str,
        run: impl FnOnce(&mut Event) -> Result<String, ErrorCode>,
    ) -> String {
        let begun = Instant::now();
        let name = command_name(command);
        let mut event = if READS.contains(&name) {
            Event::debug(name)
        } else {
            Event::info(name)
        };
        let reply = run(&mut event);
        let event = event.since(begun);
        match reply {
            Ok(reply) => {
                self.diag.record(event);
                reply
            }
            Err(code) => {
                let level = match code {
                    ErrorCode::Internal => Level::Error,
                    _ => Level::Warn,
                };
                self.diag.record(Event { level, ..event }.code(code));
                error_reply(code)
            }
        }
    }

    pub(crate) fn diag(&self) -> &Arc<Diag> {
        &self.diag
    }

    /// The commands of this file; any other is `invalid_argument`.
    pub(super) fn diagnostic(
        &self,
        command: &str,
        args: &str,
        event: &mut Event,
    ) -> Result<String, ErrorCode> {
        match command {
            "diagnostics" => self.diagnostics(args),
            "setLogLevel" => self.set_log_level(args, event),
            "clearCrashes" => self.clear_crashes(args, event),
            _ => Err(ErrorCode::InvalidArgument),
        }
    }

    /// `diagnostics`: the ring (from `level` up), what the last snapshot said, the stats, the
    /// crash files and the build.
    fn diagnostics(&self, args: &str) -> Result<String, ErrorCode> {
        let DiagnosticsArgs { level } = parse(args)?;
        let traffic = match &*lock(&self.life) {
            Life::Running(tunnel) => tunnel.session().traffic(),
            _ => Traffic::default(),
        };
        let today = self.stats.today(&Clock::now());
        let (events, overwritten) = self.diag.events(level);
        let listing = self.diag.crashes().map(|c| c.listing());
        let (crashes, crash_mark) = listing.unwrap_or_default();
        Ok(ok(Diagnostics {
            core: CoreBuild::current(),
            level: self.diag.level(),
            events: events.iter().map(event).collect(),
            overwritten,
            snapshot: self.diag.seen().map(last_snapshot),
            stats: StatsSummary { traffic, today },
            crashes,
            crash_mark,
        }))
    }

    /// `setLogLevel`: the ring keeps events of `level` and more severe from now on. Not saved:
    /// the app sends it after each `init`.
    fn set_log_level(&self, args: &str, event: &mut Event) -> Result<String, ErrorCode> {
        let LevelArgs { level } = parse(args)?;
        self.diag.set_level(level);
        *event = event.detail(level.as_str());
        Ok(empty(()))
    }

    /// `clearCrashes`: removes the crash files named, those the app just exported, at or below the
    /// mark their listing gave; a file written since stays. A name that is not a crash file's is
    /// refused.
    fn clear_crashes(&self, args: &str, event: &mut Event) -> Result<String, ErrorCode> {
        let ClearCrashesArgs { names, mark } = parse(args)?;
        if names.iter().any(|n| crash::number(n).is_none()) {
            return Err(ErrorCode::InvalidArgument);
        }
        let removed = match self.diag.crashes() {
            Some(crashes) => crashes.remove(&names, mark).map_err(|e| {
                *event = event.io(e.kind());
                ErrorCode::Internal
            })?,
            None => 0,
        };
        *event = event.count(Count::Files, removed);
        Ok(empty(()))
    }
}

/// `init`'s event: what `state.json` gave, and whether it had to be set aside.
pub(super) fn loaded(diag: &Diag, state: &State, model: &Model, loaded: Loaded) {
    let event = match loaded {
        Loaded::SetAside => Event::warn("init").detail("stateSetAside"),
        _ => Event::info("init"),
    };
    let event = event.count(Count::Subscriptions, state.subscriptions.len());
    diag.record(event.count(Count::Nodes, model.nodes.len()));
}

/// `testLatency`'s reply, its event counting the probes it started.
pub(super) fn probing(event: &mut Event, probes: usize) -> String {
    *event = event.count(Count::Probes, probes);
    empty(())
}

/// `addSubscription`'s or `updateSubscription`'s reply, its event counting the nodes.
pub(super) fn refreshed(event: &mut Event, subscription: dto::Subscription) -> String {
    *event = event.count(Count::Nodes, subscription.nodes);
    ok(subscription)
}

pub(super) fn mode_name(mode: Mode) -> &'static str {
    match mode {
        Mode::Global => "Global",
        Mode::Direct => "Direct",
        Mode::Rule => "Rule",
    }
}

/// A command name fit for the log: the closed list, never caller text.
fn command_name(command: &str) -> &'static str {
    #[cfg(feature = "fault-hook")]
    if command == "debugFault" {
        return "debugFault";
    }
    COMMANDS
        .into_iter()
        .find(|k| *k == command)
        .unwrap_or("unknown")
}

fn event(e: &Event) -> DiagEvent {
    let counts = e.counts.iter().flatten();
    DiagEvent {
        at_epoch_ms: e.at_ms,
        level: e.level,
        name: e.name.to_owned(),
        code: e.code.map(str::to_owned),
        detail: e.detail.map(str::to_owned),
        counts: counts.map(|(c, n)| (c.as_str().to_owned(), *n)).collect(),
        ms: e.ms,
    }
}

fn last_snapshot(s: Seen) -> LastSnapshot {
    LastSnapshot {
        at_epoch_ms: s.at_ms,
        revision: s.revision,
        running: s.running,
        generation: s.generation,
        error: s.error,
        warning: s.warning,
    }
}
