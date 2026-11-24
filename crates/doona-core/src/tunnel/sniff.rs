//! Best-effort diagnostics on the first client chunk. Never buffer payload or delay forwarding
//! for an incomplete header. Store only a hostname, not a request target or a header value.
pub(super) fn host(bytes: &[u8]) -> Option<&str> {
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
fn vector<'a>(input: &mut &'a [u8], width: usize) -> Option<&'a [u8]> {
    let n = input
        .split_off(..width)?
        .iter()
        .fold(0, |n, &b| (n << 8) | usize::from(b));
    input.split_off(..n)
}

fn sni(mut record: &[u8]) -> Option<&str> {
    if record.split_off(..3)?[..2] != [22, 3] {
        return None;
    }
    let mut handshake = vector(&mut record, 2)?;
    if handshake.split_off_first()? != &1 {
        return None;
    }
    let mut hello = vector(&mut handshake, 3)?;
    hello.split_off(..34)?; // protocol version and random
    vector(&mut hello, 1)?; // session ID
    vector(&mut hello, 2)?; // cipher suites
    vector(&mut hello, 1)?; // compression methods
    server_name(vector(&mut hello, 2)?)
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
    use super::host;

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
}
