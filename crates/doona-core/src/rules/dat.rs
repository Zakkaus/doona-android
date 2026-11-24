//! Immutable selections from one verified v2ray GeoIPList/GeoSiteList generation.
mod record;
#[cfg(test)]
mod tests;
mod wire;
use super::{Flow, Kind, NONE, RuleSpec, domain, geoip, lpm};
use regex::Regex;
use std::{
    collections::{HashMap, HashSet},
    io,
    path::Path,
    sync::Arc,
};

#[derive(Default, Debug)]
struct Site {
    names: domain::Index,
    regex: Vec<Regex>,
    skipped: u32,
}
impl Site {
    fn load(list: &mut wire::List, selector: &str) -> io::Result<Option<Self>> {
        let mut parts = selector.split('@').map(str::trim);
        let code = parts.next().unwrap();
        let attrs: Vec<_> = parts.filter(|p| !p.is_empty()).collect();
        if unsupported_filter(selector) {
            return Ok(None);
        }
        let mut seen = vec![false; attrs.len()];
        let mut site = Self::default();
        let found = list.records(code, |bytes| {
            let Some((kind, value)) = record::domain(bytes, &attrs, &mut seen)? else {
                return Ok(());
            };
            match kind {
                0 => site.names.add_keyword(value, 0),
                1 => {
                    match Regex::new(value) {
                        Ok(re) => site.regex.push(re),
                        Err(_) => site.skipped += 1,
                    }
                    Ok(())
                }
                2 => site.names.add_suffix(value, 0),
                3 => site.names.add_full(value, 0),
                _ => unreachable!(),
            }
            .map_err(|_| wire::bad())
        })?;
        site.names.finish().map_err(|_| wire::bad())?;
        Ok(found.then_some(site))
    }
    fn contains(&self, name: &str) -> bool {
        self.names.first(name, NONE) == 0
            || (!self.regex.is_empty() && {
                let name = domain::flow_name(name);
                self.regex.iter().any(|r| r.is_match(&name))
            })
    }
}

#[derive(Default, Debug)]
pub(crate) struct Data {
    ips: HashMap<String, Arc<lpm::Trie>>,
    sites: HashMap<String, Arc<Site>>,
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
            let mut trie = lpm::Trie::default();
            if ip.records(&code, |b| {
                let (addr, len) = record::cidr(b)?;
                trie.insert(addr, len, 0);
                Ok(())
            })? {
                trie.shrink();
                data.ips.insert(code, Arc::new(trie));
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
            if let Some(site) = Site::load(&mut sites, &code)? {
                data.sites.insert(code, Arc::new(site));
            }
        }
        Ok(data)
    }
}

#[derive(Clone, Debug, Default)]
pub(super) struct Matches {
    ips: Vec<(u32, bool, Arc<lpm::Trie>)>,
    sites: Vec<(u32, bool, Arc<Site>)>,
}
pub(super) fn unsupported_filter(value: &str) -> bool {
    value
        .split('@')
        .skip(1)
        .any(|attr| attr.trim().starts_with('!'))
}

impl Matches {
    pub fn add(&mut self, data: &Data, kind: &Kind, value: &str, row: u32) -> Option<u32> {
        let code = value
            .strip_prefix('!')
            .unwrap_or(value)
            .to_ascii_lowercase();
        let invert = value.starts_with('!');
        match kind {
            Kind::GeoSite => {
                let site = data.sites.get(&code)?;
                bind(&mut self.sites, Some(site), row, invert);
                Some(site.skipped)
            }
            Kind::GeoIp => bind(&mut self.ips, data.ips.get(&code), row, invert).then_some(0),
            _ => unreachable!(),
        }
    }
    pub fn has_names(&self) -> bool {
        !self.sites.is_empty()
    }
    pub fn first(&self, flow: &Flow, mut best: u32) -> u32 {
        for (row, not, trie) in &self.ips {
            if *row >= best {
                break;
            }
            if (trie.first(flow.ip.to_canonical()) != NONE) != *not {
                best = *row;
                break;
            }
        }
        if let Some(name) = flow.domain.filter(|s| !s.is_empty()) {
            for (row, not, site) in &self.sites {
                if *row >= best {
                    break;
                }
                if site.contains(name) != *not {
                    return *row;
                }
            }
        }
        best
    }
}
fn bind<T>(
    rows: &mut Vec<(u32, bool, Arc<T>)>,
    matcher: Option<&Arc<T>>,
    row: u32,
    not: bool,
) -> bool {
    let Some(matcher) = matcher else {
        return false;
    };
    if !rows
        .iter()
        .any(|(_, n, m)| *n == not && Arc::ptr_eq(m, matcher))
    {
        rows.push((row, not, Arc::clone(matcher)));
    }
    true
}
