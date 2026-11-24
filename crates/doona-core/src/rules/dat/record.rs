use super::wire::bad;
use quick_protobuf::BytesReader;
use std::{
    io,
    net::{IpAddr, Ipv4Addr, Ipv6Addr},
};

pub fn cidr(bytes: &[u8]) -> io::Result<(IpAddr, u8)> {
    let mut r = BytesReader::from_bytes(bytes);
    let (mut addr, mut prefix) = (&[][..], 0);
    while !r.is_eof() {
        match r.next_tag(bytes).map_err(|_| bad())? {
            10 => addr = r.read_bytes(bytes).map_err(|_| bad())?,
            16 => prefix = r.read_uint32(bytes).map_err(|_| bad())?,
            tag => r.read_unknown(bytes, tag).map_err(|_| bad())?,
        }
    }
    let ip = match addr.len() {
        4 if prefix <= 32 => IpAddr::V4(Ipv4Addr::from(<[u8; 4]>::try_from(addr).unwrap())),
        16 if prefix <= 128 => IpAddr::V6(Ipv6Addr::from(<[u8; 16]>::try_from(addr).unwrap())),
        _ => return Err(bad()),
    };
    Ok((ip, prefix as u8))
}

fn attr_key(bytes: &[u8]) -> io::Result<&str> {
    let mut r = BytesReader::from_bytes(bytes);
    let mut key = None;
    while !r.is_eof() {
        match r.next_tag(bytes).map_err(|_| bad())? {
            10 => key = Some(r.read_string(bytes).map_err(|_| bad())?),
            tag => r.read_unknown(bytes, tag).map_err(|_| bad())?,
        }
    }
    key.ok_or_else(bad)
}

pub fn domain<'a>(
    bytes: &'a [u8],
    attrs: &[&str],
    seen: &mut [bool],
) -> io::Result<Option<(u32, &'a str)>> {
    let mut r = BytesReader::from_bytes(bytes);
    let (mut kind, mut value) = (0, "");
    seen.fill(false);
    while !r.is_eof() {
        match r.next_tag(bytes).map_err(|_| bad())? {
            8 => kind = r.read_uint32(bytes).map_err(|_| bad())?,
            18 => value = r.read_string(bytes).map_err(|_| bad())?,
            26 => {
                let key = attr_key(r.read_bytes(bytes).map_err(|_| bad())?)?;
                for (want, found) in attrs.iter().zip(seen.iter_mut()) {
                    *found |= key.eq_ignore_ascii_case(want);
                }
            }
            tag => r.read_unknown(bytes, tag).map_err(|_| bad())?,
        }
    }
    if kind > 3 || value.is_empty() {
        return Err(bad());
    }
    Ok(seen.iter().all(|b| *b).then_some((kind, value)))
}
