use std::env;
use std::fmt::Debug;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};

use anyhow::{Context, Result, ensure};
use honk_config::parser::parse_dae_config_file;
use honk_core::{dns::routing::DnsRouter, routing::Router};
use tracing::{Event, Level, Subscriber, field::Field};
use tracing_subscriber::{Layer, layer, prelude::*};

static GEO_FAILED: AtomicBool = AtomicBool::new(false);

struct GeoFailures;

impl<S: Subscriber> Layer<S> for GeoFailures {
    fn on_event(&self, event: &Event<'_>, _: layer::Context<'_, S>) {
        let meta = event.metadata();
        if meta.target() != "honk_core::routing::geo" || *meta.level() != Level::WARN {
            return;
        }
        // The pinned routers report failed expansions and asset I/O via tracing, not Result.
        event.record(&mut |field: &Field, value: &dyn Debug| {
            if field.name() == "message" {
                let message = format!("{value:?}");
                if message.contains("code expanded to zero ")
                    || message.starts_with("failed to parse retained ")
                    || message.starts_with("failed to read ")
                    || message.starts_with("required geo asset not found")
                {
                    GEO_FAILED.store(true, Ordering::Relaxed);
                }
            }
        });
    }
}

fn main() -> Result<()> {
    let mut args = env::args_os().skip(1);
    let (Some(config), None) = (args.next(), args.next()) else {
        anyhow::bail!("usage: DAE_LOCATION_ASSET=BUNDLE_DIR doona-geo-check CONFIG.dae");
    };
    let bundle =
        env::var("DAE_LOCATION_ASSET").context("set DAE_LOCATION_ASSET to the bundle directory")?;
    for name in ["geoip.dat", "geosite.dat"] {
        let path = Path::new(&bundle).join(name);
        // Do not let honk satisfy the check with a system DAT when the bundle lacks one.
        ensure!(path.is_file(), "missing bundle file: {}", path.display());
    }

    let config = PathBuf::from(config);
    let cfg = parse_dae_config_file(&config).with_context(|| config.display().to_string())?;
    tracing_subscriber::registry()
        .with(GeoFailures)
        .with(
            tracing_subscriber::fmt::layer()
                .with_writer(std::io::stderr)
                .without_time()
                .with_ansi(false)
                .with_filter(tracing_subscriber::filter::LevelFilter::WARN),
        )
        .init();
    Router::from_config(&cfg.routing)?;
    DnsRouter::new_from_dns_config(&cfg.dns)?;
    ensure!(
        !GEO_FAILED.load(Ordering::Relaxed),
        "geo bundle failed coverage for {}",
        config.display()
    );
    Ok(())
}
