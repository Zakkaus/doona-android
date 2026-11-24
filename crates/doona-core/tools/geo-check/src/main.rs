use std::collections::BTreeSet;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, ensure};
use honk_config::dns::{DnsCond, DnsDomainMatcher};
use honk_config::parser::parse_dae_config_file;
use honk_core::routing::{GeoipScan, GeositeKind, GeositeScan};
use regex::Regex;

#[derive(Default)]
struct Labels<'a> {
    ip: BTreeSet<&'a str>,
    site: BTreeSet<&'a str>,
}

impl<'a> Labels<'a> {
    fn dns(&mut self, conditions: &'a [DnsCond]) {
        for condition in conditions {
            match condition {
                DnsCond::Ip { geoip, .. } => self.ip.extend(geoip.iter().map(String::as_str)),
                DnsCond::Qname { matchers, .. } => {
                    self.site.extend(matchers.iter().filter_map(|matcher| {
                        if let DnsDomainMatcher::Geosite(code) = matcher {
                            Some(code.as_str())
                        } else {
                            None
                        }
                    }));
                }
                _ => {}
            }
        }
    }
}

fn check_template(path: &Path, ip: &GeoipScan, site: &GeositeScan) -> Result<()> {
    let cfg = parse_dae_config_file(path)?;
    let mut labels = Labels::default();
    for rule in &cfg.routing.rules {
        let c = &rule.condition;
        labels
            .ip
            .extend(c.geo_ip.iter().chain(&c.not.geo_ip).map(String::as_str));
        labels
            .site
            .extend(c.geosite.iter().chain(&c.not.geosite).map(String::as_str));
    }
    for rule in &cfg.dns.routing.request.rules {
        labels.dns(&rule.conditions);
    }
    for rule in &cfg.dns.routing.response.rules {
        labels.dns(&rule.conditions);
    }
    for code in labels.ip {
        let code = code.trim();
        // honk expands private from an internal CIDR list, without consulting the DAT.
        ensure!(
            code.eq_ignore_ascii_case("private")
                || ip
                    .categories()
                    .iter()
                    .rev()
                    .find(|c| c.code.eq_ignore_ascii_case(code))
                    .is_some_and(|c| !c.cidrs.is_empty()),
            "geoip:{code} expanded to zero entries"
        );
        println!("{}: geoip:{code}", path.display());
    }
    for code in labels.site {
        ensure!(
            geosite_resolves(site, code),
            "geosite:{code} expanded to zero entries"
        );
        println!("{}: geosite:{code}", path.display());
    }
    Ok(())
}

fn geosite_resolves(site: &GeositeScan, code: &str) -> bool {
    let code = code.trim();
    let (base, attr) = code
        .split_once('@')
        .map_or((code, None), |(base, attr)| (base, Some(attr)));
    // honk's routing index uses the last category block. Its scan API prefixes false keys with '!'.
    site.categories()
        .iter()
        .rev()
        .find(|c| c.code.eq_ignore_ascii_case(base))
        .is_some_and(|cat| {
            cat.entries.iter().any(|e| {
                attr.is_none_or(|key| {
                    e.attrs
                        .iter()
                        .any(|a| a.strip_prefix('!').unwrap_or(a).eq_ignore_ascii_case(key))
                }) && (e.kind != GeositeKind::Regex || Regex::new(&e.value).is_ok())
            })
        })
}

fn main() -> Result<()> {
    let mut args = env::args_os().skip(1).map(PathBuf::from);
    let (Some(bundle), Some(templates), None) = (args.next(), args.next(), args.next()) else {
        anyhow::bail!("usage: doona-geo-check BUNDLE_DIR TEMPLATE_DIR");
    };

    let ip = GeoipScan::load(&bundle.join("geoip.dat"))?;
    let site = GeositeScan::load(&bundle.join("geosite.dat"))?;
    ensure!(!ip.categories().is_empty(), "geoip.dat has no categories");
    ensure!(
        !site.categories().is_empty(),
        "geosite.dat has no categories"
    );

    let mut paths = Vec::new();
    for entry in fs::read_dir(&templates).with_context(|| templates.display().to_string())? {
        let path = entry?.path();
        if path.extension().is_some_and(|ext| ext == "dae") {
            paths.push(path);
        }
    }
    ensure!(
        !paths.is_empty(),
        "{} has no .dae templates",
        templates.display()
    );
    paths.sort_unstable();
    for path in paths {
        check_template(&path, &ip, &site).with_context(|| path.display().to_string())?;
    }
    Ok(())
}
