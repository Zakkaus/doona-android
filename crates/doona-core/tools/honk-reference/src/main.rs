//! Stock honk as the reference for doona-core: loads each corpus case with honk's own loader,
//! routes its probes through `Router::route_action` and resolves the leaf with `GroupManager`.
//! No network, no daemon, no doona-core code.

mod assets;
mod corpus;
mod report;
mod run;

use std::fmt::Debug;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};

use anyhow::{Context, Result, bail, ensure};
use tracing::{Event, Level, Subscriber, field::Field};
use tracing_subscriber::{Layer, layer, prelude::*};

use report::{REPORT_VERSION, Report};

const USAGE: &str = "usage: honk-reference (emit OUT.json | check EXPECTED.json) \
--corpus DIR --geo BUNDLE_DIR --geo-pins geo-pins.json";

static GEO_FAILED: AtomicBool = AtomicBool::new(false);

/// honk reports failed geo expansion through tracing, not through `Result`.
struct GeoFailures;

impl<S: Subscriber> Layer<S> for GeoFailures {
    fn on_event(&self, event: &Event<'_>, _: layer::Context<'_, S>) {
        let meta = event.metadata();
        if meta.target() != "honk_core::routing::geo" || *meta.level() != Level::WARN {
            return;
        }
        event.record(&mut |field: &Field, _: &dyn Debug| {
            if field.name() == "message" {
                GEO_FAILED.store(true, Ordering::Relaxed);
            }
        });
    }
}

struct Args {
    check: bool,
    file: PathBuf,
    corpus: PathBuf,
    geo: PathBuf,
    pins: PathBuf,
}

fn parse_args() -> Result<Args> {
    let mut args = std::env::args_os().skip(1);
    let check = match args
        .next()
        .and_then(|mode| mode.into_string().ok())
        .as_deref()
    {
        Some("emit") => false,
        Some("check") => true,
        _ => bail!(USAGE),
    };
    let file = PathBuf::from(args.next().context(USAGE)?);
    let (mut corpus, mut geo, mut pins) = (None, None, None);
    while let Some(flag) = args.next() {
        let value = PathBuf::from(args.next().context(USAGE)?);
        match flag.to_str() {
            Some("--corpus") => corpus = Some(value),
            Some("--geo") => geo = Some(value),
            Some("--geo-pins") => pins = Some(value),
            _ => bail!(USAGE),
        }
    }
    Ok(Args {
        check,
        file,
        corpus: corpus.context(USAGE)?,
        geo: geo.context(USAGE)?,
        pins: pins.context(USAGE)?,
    })
}

fn build(args: &Args) -> Result<Report> {
    let geo = assets::verify(&args.geo, &args.pins)?;
    // Before any thread exists. honk looks here first, so no host geo file can stand in.
    unsafe { std::env::set_var("DAE_LOCATION_ASSET", &args.geo) };
    tracing_subscriber::registry().with(GeoFailures).init();
    let corpus = corpus::load(&args.corpus)?;
    let mut cases = Vec::new();
    for case in &corpus.cases {
        cases.push(run::run(case, &args.corpus)?);
    }
    ensure!(
        !GEO_FAILED.load(Ordering::Relaxed),
        "the geo bundle failed coverage"
    );
    Ok(Report {
        version: REPORT_VERSION,
        honk_rev: env!("HONK_PIN"),
        geo,
        cases,
    })
}

fn main() -> Result<()> {
    let args = parse_args()?;
    let actual = serde_json::to_value(build(&args)?)?;
    if !args.check {
        let mut text = serde_json::to_string_pretty(&actual)?;
        text.push('\n');
        std::fs::write(&args.file, text).with_context(|| args.file.display().to_string())?;
        return Ok(());
    }
    let text =
        std::fs::read_to_string(&args.file).with_context(|| args.file.display().to_string())?;
    let expected: serde_json::Value = serde_json::from_str(&text)?;
    let differences = report::diff(&expected, &actual);
    for line in &differences {
        eprintln!("{line}");
    }
    ensure!(
        differences.is_empty(),
        "{} differences from {}",
        differences.len(),
        args.file.display()
    );
    Ok(())
}
