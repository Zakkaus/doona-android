use super::wire::{bad, next};
use std::{
    io,
    net::{IpAddr, Ipv4Addr, Ipv6Addr},
};

pub fn cidr(mut bytes: &[u8]) -> io::Result<(IpAddr, u8)> {
    let (mut addr, mut prefix) = (&[][..], 0);
    while !bytes.is_empty() {
        match next(&mut bytes)? {
            (10, _, b) => addr = b,
            (16, n, _) => prefix = n,
            _ => {}
        }
    }
    let ip = match addr.len() {
        4 if prefix <= 32 => IpAddr::V4(Ipv4Addr::from(<[u8; 4]>::try_from(addr).unwrap())),
        16 if prefix <= 128 => IpAddr::V6(Ipv6Addr::from(<[u8; 16]>::try_from(addr).unwrap())),
        _ => return Err(bad()),
    };
    Ok((ip, prefix as u8))
}
fn text(bytes: &[u8]) -> io::Result<&str> {
    std::str::from_utf8(bytes).map_err(|_| bad())
}
fn attr_key(mut bytes: &[u8]) -> io::Result<&str> {
    let mut key = None;
    while !bytes.is_empty() {
        if let (10, _, b) = next(&mut bytes)? {
            key = Some(text(b)?);
        }
    }
    key.ok_or_else(bad)
}
pub fn domain<'a>(
    mut bytes: &'a [u8],
    attrs: &[&str],
    seen: &mut [bool],
) -> io::Result<Option<(u64, &'a str)>> {
    let (mut kind, mut value) = (0, "");
    seen.fill(false);
    while !bytes.is_empty() {
        match next(&mut bytes)? {
            (8, n, _) => kind = n,
            (18, _, b) => value = text(b)?,
            (26, _, b) => {
                let key = attr_key(b)?;
                for (want, found) in attrs.iter().zip(seen.iter_mut()) {
                    *found |= key.eq_ignore_ascii_case(want);
                }
            }
            _ => {}
        }
    }
    if kind > 3 || value.is_empty() {
        return Err(bad());
    }
    Ok(seen.iter().all(|b| *b).then_some((kind, value)))
}
