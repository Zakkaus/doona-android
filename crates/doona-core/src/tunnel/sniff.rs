//! Diagnostic inspection never delays forwarding. Only an incomplete first TLS record needs a copy.
const LIMIT: usize = 8192;

pub(super) enum Sniff {
    First,
    Partial(Vec<u8>),
    Done,
}

fn target(bytes: &[u8]) -> usize {
    bytes.get(3..5).map_or(LIMIT, |n| {
        (5 + usize::from(u16::from_be_bytes([n[0], n[1]]))).min(LIMIT)
    })
}

impl Sniff {
    pub fn feed(&mut self, chunk: &[u8], found: impl FnOnce(&str)) {
        match self {
            Self::Done => return,
            Self::First => {
                let prefix = &chunk[..chunk.len().min(LIMIT)];
                if let Some(name) = host(prefix) {
                    found(name);
                } else if prefix.starts_with(&[22])
                    && prefix.get(1).is_none_or(|v| *v == 3)
                    && prefix.len() < target(prefix)
                {
                    let mut buf = Vec::with_capacity(target(prefix));
                    buf.extend_from_slice(prefix);
                    *self = Self::Partial(buf);
                    return;
                }
            }
            Self::Partial(buf) => {
                let header = chunk.len().min(5usize.saturating_sub(buf.len()));
                buf.extend_from_slice(&chunk[..header]);
                let needed = target(buf) - buf.len();
                let rest = &chunk[header..];
                buf.extend_from_slice(&rest[..rest.len().min(needed)]);
                if rest.len() < needed {
                    return;
                }
                if let Some(name) = host(buf) {
                    found(name);
                }
            }
        }
        *self = Self::Done;
    }
}

fn host(bytes: &[u8]) -> Option<&str> {
    let name = match bytes.first()? {
        22 => sni(bytes)?,
        b'A'..=b'Z' => http(bytes)?,
        _ => return None,
    };
    let name = name.strip_suffix('.').unwrap_or(name);
    (name.len() <= 253
        && name.split('.').all(|label| {
            (1..=63).contains(&label.len())
                && label
                    .bytes()
                    .all(|c| c.is_ascii_alphanumeric() || c == b'-')
        }))
    .then_some(name)
}

fn http(bytes: &[u8]) -> Option<&str> {
    let mut headers = [httparse::EMPTY_HEADER; 64];
    let mut request = httparse::Request::new(&mut headers);
    request.parse(bytes).ok()?.is_complete().then_some(())?;
    let host = request
        .headers
        .iter()
        .find(|h| h.name.eq_ignore_ascii_case("host"))?;
    let value = std::str::from_utf8(host.value).ok()?;
    match value.split_once(':') {
        Some((name, port)) => port.parse::<u16>().ok().map(|_| name),
        None => Some(value),
    }
}

/// TLS vectors have a big-endian length of one, two or three bytes.
fn length(input: &mut &[u8], width: usize) -> Option<usize> {
    Some(
        input
            .split_off(..width)?
            .iter()
            .fold(0, |n, &b| (n << 8) | usize::from(b)),
    )
}

fn prefix<'a>(input: &mut &'a [u8], width: usize) -> Option<&'a [u8]> {
    let n = length(input, width)?.min(input.len());
    input.split_off(..n)
}

fn vector<'a>(input: &mut &'a [u8], width: usize) -> Option<&'a [u8]> {
    let n = length(input, width)?;
    input.split_off(..n)
}

fn sni(mut record: &[u8]) -> Option<&str> {
    if record.split_off(..3)?[..2] != [22, 3] {
        return None;
    }
    let mut handshake = prefix(&mut record, 2)?;
    if handshake.split_off_first()? != &1 {
        return None;
    }
    let mut hello = prefix(&mut handshake, 3)?;
    hello.split_off(..34)?; // protocol version and random
    vector(&mut hello, 1)?; // session ID
    vector(&mut hello, 2)?; // cipher suites
    vector(&mut hello, 1)?; // compression methods
    server_name(prefix(&mut hello, 2)?)
}

fn server_name(mut extensions: &[u8]) -> Option<&str> {
    while !extensions.is_empty() {
        let kind = extensions.split_off(..2)?;
        let mut body = vector(&mut extensions, 2)?;
        if kind == [0, 0] {
            let mut names = vector(&mut body, 2)?;
            while !names.is_empty() {
                let kind = *names.split_off_first()?;
                let name = vector(&mut names, 2)?;
                if kind == 0 {
                    return std::str::from_utf8(name).ok();
                }
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::{LIMIT, Sniff, host};

    #[test]
    fn only_complete_hostnames_are_reported() {
        let mut hello = vec![22, 3, 1, 0, 64, 1, 0, 0, 60, 3, 3];
        hello.extend([0; 32]);
        hello.extend([0, 0, 2, 19, 1, 1, 0, 0, 17, 0, 0, 0, 13, 0, 11, 0, 0, 8]);
        hello.extend(b"sni.test");
        assert_eq!(host(&hello), Some("sni.test"));
        for cut in 0..hello.len() {
            assert_eq!(host(&hello[..cut]), None);
        }
        assert_eq!(
            host(b"GET / HTTP/1.1\r\nHost: name.test/token\r\n\r\n"),
            None
        );
    }

    fn hello(padding: usize, sni_first: bool) -> Vec<u8> {
        let sni = b"\x00\x00\x00\x0d\x00\x0b\x00\x00\x08sni.test";
        let mut pad = vec![0, 21];
        pad.extend((padding as u16).to_be_bytes());
        pad.resize(4 + padding, 0);
        let len = sni.len() + pad.len();
        let mut wire = vec![22, 3, 1];
        wire.extend(((47 + len) as u16).to_be_bytes());
        wire.extend([1, 0]);
        wire.extend(((43 + len) as u16).to_be_bytes());
        wire.extend([3, 3]);
        wire.extend([0; 32]);
        wire.extend([0, 0, 2, 19, 1, 1, 0]);
        wire.extend((len as u16).to_be_bytes());
        if sni_first {
            wire.extend(sni);
            wire.extend(pad);
        } else {
            wire.extend(pad);
            wire.extend(sni);
        }
        wire
    }

    #[test]
    fn sni_before_a_cut_needs_no_second_read() {
        let bytes = hello(1700, true);
        let mut sniff = Sniff::First;
        let mut found = None;
        sniff.feed(&bytes[..1400], |s| found = Some(s.to_owned()));
        assert_eq!(found.as_deref(), Some("sni.test"));
        assert!(matches!(sniff, Sniff::Done));
    }

    #[test]
    fn later_sni_is_recovered_only_within_the_limit() {
        for (padding, expected) in [(1700, Some("sni.test")), (LIMIT, None)] {
            let bytes = hello(padding, false);
            for cut in [1, 1400] {
                let mut sniff = Sniff::First;
                sniff.feed(&bytes[..cut], |_| panic!("SNI not present yet"));
                let mut found = None;
                sniff.feed(&bytes[cut..], |s| found = Some(s.to_owned()));
                assert_eq!(found.as_deref(), expected);
                assert!(matches!(sniff, Sniff::Done));
            }
        }
    }
}
