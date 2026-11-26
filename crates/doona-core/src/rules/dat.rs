//! Which selectors of one verified v2ray GeoIPList/GeoSiteList generation hold data, as the rules
//! page counts skipped lines; honk's engine reads the files itself.
mod record;
#[cfg(test)]
mod tests;
mod wire;
use super::{Kind, RuleSpec, domain, geoip};
use regex::Regex;
use std::{
    collections::{HashMap, HashSet},
    io,
    path::Path,
};

/// Whether a GeoSite selector's records are all usable: `false` when one holds a regex that does
/// not compile, which skips the selector's line.
fn site(list: &mut wire::List, selector: &str) -> io::Result<Option<bool>> {
    let mut parts = selector.split('@').map(str::trim);
    let code = parts.next().unwrap();
    let attrs: Vec<_> = parts.filter(|p| !p.is_empty()).collect();
    if unsupported_filter(selector) {
        return Ok(None);
    }
    let mut seen = vec![false; attrs.len()];
    let mut complete = true;
    let found = list.records(code, |bytes| {
        let Some((kind, value)) = record::domain(bytes, &attrs, &mut seen)? else {
            return Ok(());
        };
        match kind {
            0 => domain::keyword(value),
            1 => {
                complete &= Regex::new(value).is_ok();
                Ok(())
            }
            2 | 3 => domain::rule_name(value).map(drop),
            _ => unreachable!(),
        }
        .map_err(|_| wire::bad())
    })?;
    Ok(found.then_some(complete))
}

#[derive(Default, Debug)]
pub(crate) struct Data {
    /// The GeoIP codes with records.
    ips: HashSet<String>,
    /// The GeoSite selectors with records, and whether all of them are usable.
    sites: HashMap<String, bool>,
}
impl Data {
    /// Validate each streamed record before admitting a release; retain no unselected payloads.
    pub fn verify(dir: &Path) -> io::Result<()> {
        wire::List::verify(&dir.join("geoip.dat"), |b| record::cidr(b).map(drop))?;
        wire::List::verify(&dir.join("geosite.dat"), |b| {
            record::domain(b, &[], &mut []).map(drop)
        })
    }
    pub fn load(dir: &Path, rules: &[RuleSpec]) -> io::Result<Self> {
        let selectors = |kind: Kind| -> HashSet<String> {
            rules
                .iter()
                .filter(|r| r.kind == kind)
                .flat_map(|r| &r.values)
                .map(|s| s.strip_prefix('!').unwrap_or(s).to_ascii_lowercase())
                .collect()
        };
        let mut data = Self::default();
        let codes = selectors(Kind::GeoIp);
        let mut ip = wire::List::open(
            &dir.join("geoip.dat"),
            &codes.iter().map(String::as_str).collect(),
        )?;
        for code in codes {
            if matches!(
                geoip::Code::parse(&code),
                Some(geoip::Code::Private | geoip::Code::Lan)
            ) {
                continue;
            }
            if ip.records(&code, |b| record::cidr(b).map(drop))? {
                data.ips.insert(code);
            }
        }
        drop(ip);
        let codes = selectors(Kind::GeoSite);
        let names = codes
            .iter()
            .map(|s| s.split('@').next().unwrap().trim())
            .collect();
        let mut sites = wire::List::open(&dir.join("geosite.dat"), &names)?;
        for code in codes {
            if let Some(complete) = site(&mut sites, &code)? {
                data.sites.insert(code, complete);
            }
        }
        Ok(data)
    }

    /// Whether a GeoIP or GeoSite `value` (`!` for its complement) has usable data.
    pub(super) fn holds(&self, kind: &Kind, value: &str) -> bool {
        let code = value
            .strip_prefix('!')
            .unwrap_or(value)
            .to_ascii_lowercase();
        match kind {
            Kind::GeoSite => self.sites.get(&code).copied().unwrap_or(false),
            Kind::GeoIp => self.ips.contains(&code),
            _ => unreachable!(),
        }
    }
}

pub(super) fn unsupported_filter(value: &str) -> bool {
    value
        .split('@')
        .skip(1)
        .any(|attr| attr.trim().starts_with('!'))
}
