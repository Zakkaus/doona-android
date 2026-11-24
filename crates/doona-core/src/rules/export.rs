//! Rules as Clash-style text, for `effectiveConfig` (rule-mode.md, R3): a line per compiled
//! value, and each value not compiled as a comment with its reason, in text capped at a line
//! boundary.

use super::listing::Listing;
use super::{Kind, Skip, Target};

impl Listing {
    /// Writes each row as Clash lines, a line per value, to `out` until it is full. A value not
    /// compiled is a comment with its reason. `group` gives a group's display name by id.
    pub fn export(&self, group: &dyn Fn(&str) -> String, out: &mut Capped) {
        for row in &self.rows {
            let kind = clash_name(&row.kind);
            let target = match &row.target {
                Target::Direct => "DIRECT".to_owned(),
                Target::Reject => "REJECT".to_owned(),
                Target::Group(id) => group(id),
            };
            if row.values.is_empty() {
                out.rule(&format!("{kind},{target}"), row.skip);
            }
            for (column, value) in row.values.iter().enumerate() {
                out.rule(
                    &format!("{kind},{value},{target}"),
                    row.skip.filter(|_| {
                        matches!(row.kind, Kind::Skipped { .. })
                            || row.missing.binary_search(&(column as u32)).is_ok()
                    }),
                );
            }
            if out.truncated {
                return;
            }
        }
    }
}

/// Text that ends at a line boundary: the line that would take it past `cap` bytes, and every
/// line after it, is left out and the text is `truncated`. Control characters, line breaks
/// among them, become spaces, so a name or a value never starts a line of its own.
pub(crate) struct Capped {
    text: String,
    cap: usize,
    pub truncated: bool,
}

impl Capped {
    pub fn new(cap: usize) -> Capped {
        Capped {
            text: String::new(),
            cap,
            truncated: false,
        }
    }

    pub fn line(&mut self, line: &str) {
        if self.truncated || self.text.len() + line.len() + 1 > self.cap {
            self.truncated = true;
            return;
        }
        let clean = line.chars().map(|c| if c.is_control() { ' ' } else { c });
        self.text.extend(clean);
        self.text.push('\n');
    }

    /// A rule's line, or a comment with the reason it was not compiled.
    fn rule(&mut self, line: &str, skip: Option<Skip>) {
        match skip {
            Some(reason) => self.line(&format!("# {line} (skipped: {})", reason.as_str())),
            None => self.line(line),
        }
    }

    pub fn finish(self) -> (String, bool) {
        (self.text, self.truncated)
    }
}

/// Clash's name of a kind.
fn clash_name(kind: &Kind) -> &str {
    match kind {
        Kind::Domain => "DOMAIN",
        Kind::DomainSuffix => "DOMAIN-SUFFIX",
        Kind::DomainKeyword => "DOMAIN-KEYWORD",
        Kind::IpCidr => "IP-CIDR",
        Kind::GeoIp => "GEOIP",
        Kind::GeoSite => "GEOSITE",
        Kind::DstPort => "DST-PORT",
        Kind::Network => "NETWORK",
        Kind::Match => "MATCH",
        Kind::Skipped { name, .. } => name,
    }
}

#[cfg(test)]
mod tests;
