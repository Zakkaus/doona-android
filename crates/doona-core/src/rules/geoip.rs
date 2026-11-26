//! The GeoIP codes rule mode counts as compiled without geodata (rule-mode.md, decision 4): `CN`,
//! `private` and Mihomo's `LAN`. Any other code is skipped as `geodata_missing` until geodata is
//! downloaded (`e-geo`).

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Code {
    Cn,
    Private,
    Lan,
}

impl Code {
    /// `CN`, `private` or `LAN`, in any case, as Mihomo lower-cases a code
    /// (`rules/common/geoip.go:203`); `None` for a code with no data.
    pub(crate) fn parse(value: &str) -> Option<Code> {
        [
            ("cn", Code::Cn),
            ("private", Code::Private),
            ("lan", Code::Lan),
        ]
        .into_iter()
        .find_map(|(name, code)| value.eq_ignore_ascii_case(name).then_some(code))
    }
}
