//! Syntax admission for the structured rule form. Whether a geo label exists in the selected
//! assets is the preparer's check, not this one.

use std::fmt;
use std::net::{IpAddr, Ipv6Addr};

use honk_config::routing::RoutingCondition;

use super::FormError;

/// One structured match of the rule form, as the user entered it.
#[derive(Clone, Copy, Debug)]
pub enum Match<'a> {
    Full(&'a str),
    Suffix(&'a str),
    Keyword(&'a str),
    Network(&'a str),
    China,
    Private,
}

#[derive(Debug)]
pub(super) enum Literal {
    Full(String),
    Suffix(String),
    Keyword(String),
    Network(Net),
    Geo(&'static str),
}

impl Literal {
    pub(super) fn admit(matcher: Match<'_>) -> Result<Self, FormError> {
        Ok(match matcher {
            Match::Full(value) => Self::Full(name(value)?),
            Match::Suffix(value) => Self::Suffix(name(value)?),
            Match::Keyword(value) => Self::Keyword(keyword(value)?),
            Match::Network(value) => Self::Network(Net::admit(value)?),
            Match::China => Self::Geo("cn"),
            Match::Private => Self::Geo("private"),
        })
    }

    pub(super) fn predicate(&self) -> String {
        match self {
            Self::Full(n) => format!("domain(full: {n})"),
            Self::Suffix(n) => format!("domain(full: {n}, suffix: .{n})"),
            Self::Keyword(k) => format!("domain(keyword: {k})"),
            Self::Network(net) => format!("dip({net})"),
            Self::Geo(label) => format!("dip(geoip:{label})"),
        }
    }

    /// The condition honk must read back from [`Self::predicate`].
    /// A raw `domain(suffix: x)` row, as imported configs have, does not equal the form's
    /// suffix pair, so it is never read as the form's Suffix kind.
    pub(super) fn condition(&self) -> RoutingCondition {
        let mut c = RoutingCondition::default();
        let (field, value) = match self {
            Self::Full(n) => (&mut c.domain, n.clone()),
            Self::Suffix(n) => {
                c.domain.push(n.clone());
                (&mut c.domain_suffix, format!(".{n}"))
            }
            Self::Keyword(k) => (&mut c.domain_keyword, k.clone()),
            Self::Network(net) => (&mut c.ip, net.to_string()),
            Self::Geo(label) => (&mut c.geo_ip, (*label).to_owned()),
        };
        field.push(value);
        c
    }
}

fn trimmed(value: &str) -> Result<&str, FormError> {
    let value = value.trim_matches(' ');
    if value.is_empty() {
        Err(FormError::Empty)
    } else {
        Ok(value)
    }
}

/// Unicode general category Cf (Unicode 16) and the other default-ignorable code points (fillers,
/// variation selectors, combining grapheme joiner): invisible in a `.dae` file, and IDNA drops
/// some of them, so a literal holding one would read like, or silently become, the plain one.
pub(super) fn is_invisible(c: char) -> bool {
    matches!(u32::from(c),
        0xAD | 0x34F | 0x600..=0x605 | 0x61C | 0x6DD | 0x70F | 0x890..=0x891 | 0x8E2
        | 0x115F..=0x1160 | 0x17B4..=0x17B5 | 0x180B..=0x180F | 0x200B..=0x200F
        | 0x202A..=0x202E | 0x2060..=0x2064 | 0x2066..=0x206F | 0x3164 | 0xFE00..=0xFE0F
        | 0xFEFF | 0xFFA0 | 0xFFF9..=0xFFFB | 0x110BD | 0x110CD | 0x13430..=0x1343F
        | 0x1BCA0..=0x1BCA3 | 0x1D173..=0x1D17A | 0xE0001 | 0xE0020..=0xE007F
        | 0xE0100..=0xE01EF)
}

/// Spaces are trimmed; any other control, invisible character, separator or whitespace is refused
/// rather than dropped, as are ASCII delimiters a hostname never contains.
fn literal(value: &str) -> Result<&str, FormError> {
    let value = trimmed(value)?;
    let allowed = |c: char| {
        c.is_ascii_alphanumeric()
            || matches!(c, '.' | '-' | '_')
            || !(c.is_ascii() || c.is_control() || c.is_whitespace() || is_invisible(c))
    };
    if value.chars().all(allowed) {
        Ok(value)
    } else {
        Err(FormError::Forbidden)
    }
}

/// A full or suffix name: lowercase IDNA A-labels without one terminal dot. A suffix is written
/// as the full name plus `.name` because honk's own `suffix:` is a plain `ends_with` that also
/// matches `notexample.org`; the leading dot is the writer's, never the user's.
fn name(value: &str) -> Result<String, FormError> {
    let trimmed = value.trim_matches(' ');
    if ["*.", "+.", "."].iter().any(|p| trimmed.starts_with(p)) {
        return Err(FormError::Wildcard);
    }
    let Ok(url::Host::Domain(ascii)) = url::Host::parse(literal(value)?) else {
        return Err(FormError::Name);
    };
    let ascii = ascii.strip_suffix('.').unwrap_or(&ascii);
    let label = |l: &str| {
        (1..=63).contains(&l.len())
            && !l.starts_with('-')
            && !l.ends_with('-')
            && l.bytes()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-' || b == b'_')
    };
    if ascii.len() <= 253 && ascii.split('.').all(label) {
        Ok(ascii.to_owned())
    } else {
        Err(FormError::Name)
    }
}

/// A literal substring of the ASCII names honk matches, which carry IDN hosts as A-labels
/// (honk-core `sniffing.rs:432-452`); ASCII case folds.
fn keyword(value: &str) -> Result<String, FormError> {
    let value = literal(value)?;
    if value
        .bytes()
        .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.'))
    {
        Ok(value.to_ascii_lowercase())
    } else {
        Err(FormError::Keyword)
    }
}

/// An IP network with its explicit prefix length.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(super) struct Net {
    addr: IpAddr,
    prefix: u8,
}

impl Net {
    fn admit(value: &str) -> Result<Self, FormError> {
        let (addr, prefix) = trimmed(value)?.split_once('/').ok_or(FormError::Network)?;
        let addr: IpAddr = addr.parse().map_err(|_| FormError::Network)?;
        if let IpAddr::V6(v6) = addr
            && v6.to_ipv4_mapped().is_some()
        {
            return Err(FormError::MappedIpv6);
        }
        let net = Self::new(addr, prefix).ok_or(FormError::Network)?;
        if net.addr == net.network() {
            Ok(net)
        } else {
            Err(FormError::HostBits)
        }
    }

    fn new(addr: IpAddr, prefix: &str) -> Option<Self> {
        let width = if addr.is_ipv4() { 32 } else { 128 };
        let prefix = prefix
            .bytes()
            .all(|b| b.is_ascii_digit())
            .then(|| prefix.parse().ok())??;
        (prefix <= width).then_some(Self { addr, prefix })
    }

    /// An earlier rule's `dip` value as honk wrote it: a bare address is a host network.
    /// `None` when the value does not name one network exactly.
    pub(super) fn exact(value: &str) -> Option<Self> {
        let net = match value.split_once('/') {
            Some((addr, prefix)) => Self::new(addr.parse().ok()?, prefix)?,
            None => {
                let addr: IpAddr = value.parse().ok()?;
                Self::new(addr, if addr.is_ipv4() { "32" } else { "128" })?
            }
        };
        (net.addr == net.network()).then_some(net)
    }

    /// Both families in one space, IPv4 as its mapped IPv6 range.
    fn bits(self) -> (u128, u32) {
        match self.addr {
            IpAddr::V4(v4) => (u128::from(v4.to_ipv6_mapped()), 96 + u32::from(self.prefix)),
            IpAddr::V6(v6) => (u128::from(v6), u32::from(self.prefix)),
        }
    }

    fn network(self) -> IpAddr {
        let (bits, prefix) = self.bits();
        let mask = u128::MAX.checked_shl(128 - prefix).unwrap_or(0);
        let v6 = Ipv6Addr::from(bits & mask);
        match self.addr {
            IpAddr::V4(_) => IpAddr::V4(v6.to_ipv4_mapped().unwrap()),
            IpAddr::V6(_) => IpAddr::V6(v6),
        }
    }

    pub(super) fn contains(self, other: Self) -> bool {
        let ((a, p), (b, q)) = (self.bits(), other.bits());
        p <= q && (a ^ b).checked_shr(128 - p).unwrap_or(0) == 0
    }

    pub(super) fn same_family(self, other: Self) -> bool {
        self.addr.is_ipv4() == other.addr.is_ipv4()
    }
}

impl fmt::Display for Net {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}/{}", self.addr, self.prefix)
    }
}
