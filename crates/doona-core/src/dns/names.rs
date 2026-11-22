//! The names DNS answers give addresses (rule-mode.md decision 3, ARCH D33). Each A record of an
//! answer the core forwards maps its address to the question's name, through any CNAME chain, so a
//! new flow to that address is matched as that name. A flow is looked up once, when it opens.
//! Strict Private DNS and an app's own DoH never reach the core's resolver: their flows have no
//! name, so domain rules miss them and the rules that match by address, or the last one, decide.
//! The tun carries IPv4 only (`DoonaVpnService.tunBuilder`), so AAAA records name no flow.
//!
//! One cache per generation (see `Session`), at most [`CAPACITY`] addresses, the least recently
//! used leaving first. A TTL is held to [`MIN_TTL`]–[`MAX_TTL`] on `CLOCK_BOOTTIME`, which counts
//! the time the phone slept. Nothing runs on a timer: an entry past its time is dropped when it is
//! looked up, or leaves as the least recently used. An address two names answered maps to the
//! last of them.

use super::{HEADER, count, question_end, skip_name, u16_at};
use crate::api::lock;
use crate::sys;
use lru::LruCache;
use std::net::{IpAddr, Ipv4Addr};
use std::sync::{Arc, Mutex};

/// The most addresses one generation keeps a name for.
pub(crate) const CAPACITY: usize = 8_192;
/// The shortest and longest an answer's address keeps its name, in seconds.
pub(crate) const MIN_TTL: u32 = 60;
pub(crate) const MAX_TTL: u32 = 3_600;
/// The most addresses one answer maps: a real one holds a few, and a larger one would only push
/// the other names out of the cache.
const PER_ANSWER: usize = 64;
const TYPE_A: u16 = 1;
const CLASS_IN: u16 = 1;
/// The longest name, without its final dot.
const MAX_NAME: usize = 253;

pub(crate) struct Names(Mutex<LruCache<Ipv4Addr, Entry>>);

struct Entry {
    name: Arc<str>,
    /// When the mapping ends, in ms on `CLOCK_BOOTTIME`.
    until_ms: u64,
}

impl Names {
    pub fn new() -> Names {
        Names(Mutex::new(LruCache::unbounded()))
    }

    /// Maps the addresses `answer` gives to its question's name. Anything but a successful answer
    /// to one A question in class IN, with a name of letters, digits, `-` and `_`, maps nothing.
    pub fn learn(&self, answer: &[u8]) {
        self.learn_at(answer, sys::boot_ms());
    }

    /// The name `ip` answered, while its mapping lasts.
    pub fn name(&self, ip: IpAddr) -> Option<Arc<str>> {
        self.name_at(ip, sys::boot_ms())
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        lock(&self.0).len()
    }

    /// Maps `ip` to `name` for an hour, as an answer would.
    #[cfg(test)]
    pub fn map(&self, ip: Ipv4Addr, name: &str) {
        let until_ms = sys::boot_ms() + u64::from(MAX_TTL) * 1000;
        let name = name.into();
        lock(&self.0).put(ip, Entry { name, until_ms });
    }

    pub(super) fn learn_at(&self, m: &[u8], now_ms: u64) {
        let Some((name, mut at)) = question(m) else {
            return;
        };
        // Read before the lock is taken, so a new flow's lookup never waits on a long answer.
        let mut found = Vec::new();
        for _ in 0..count(m, 6) {
            let Some((end, a)) = record(m, at) else {
                break;
            };
            at = end;
            found.extend(a);
            if found.len() == PER_ANSWER {
                break;
            }
        }
        if found.is_empty() {
            return;
        }
        let name: Arc<str> = name.into();
        let mut cache = lock(&self.0);
        for (addr, ttl) in found {
            if cache.len() >= CAPACITY && !cache.contains(&addr) {
                cache.pop_lru();
            }
            let until_ms = now_ms + u64::from(ttl) * 1000;
            let name = Arc::clone(&name);
            cache.put(addr, Entry { name, until_ms });
        }
    }

    pub(super) fn name_at(&self, ip: IpAddr, now_ms: u64) -> Option<Arc<str>> {
        let IpAddr::V4(addr) = ip.to_canonical() else {
            return None;
        };
        let mut cache = lock(&self.0);
        let live = cache
            .get(&addr)
            .map(|e| (e.until_ms > now_ms).then(|| Arc::clone(&e.name)))?;
        if live.is_none() {
            cache.pop(&addr);
        }
        live
    }
}

/// The name of `m`'s question, lower case without the final dot, and the offset past the
/// question; none unless `m` is a successful answer (QR set, opcode QUERY, NOERROR) to one A
/// question in IN.
fn question(m: &[u8]) -> Option<(String, usize)> {
    let answered = m.len() >= HEADER && m[2] & 0x80 != 0 && m[2] & 0x78 == 0 && m[3] & 0x0f == 0;
    if !answered || count(m, 4) != 1 {
        return None;
    }
    let name = read_name(m, HEADER)?;
    let end = question_end(m)?;
    (u16_at(m, end - 4)? == TYPE_A && u16_at(m, end - 2)? == CLASS_IN).then_some((name, end))
}

/// The record at `at`: the offset past it, and its address and TTL (held to the bounds) when it
/// is an A record in IN. None when it runs past the message.
fn record(m: &[u8], at: usize) -> Option<(usize, Option<(Ipv4Addr, u32)>)> {
    let at = skip_name(m, at)?;
    let (kind, class, len) = (u16_at(m, at)?, u16_at(m, at + 2)?, u16_at(m, at + 8)?);
    let ttl = u32::from_be_bytes(m.get(at + 4..at + 8)?.try_into().ok()?);
    let data = m.get(at + 10..at + 10 + usize::from(len))?;
    let end = at + 10 + data.len();
    let octets = <[u8; 4]>::try_from(data)
        .ok()
        .filter(|_| kind == TYPE_A && class == CLASS_IN);
    // RFC 2181 §8: a TTL with the top bit set is read as zero.
    let ttl = if ttl >> 31 == 1 { 0 } else { ttl };
    let ttl = ttl.clamp(MIN_TTL, MAX_TTL);
    Some((end, octets.map(|o| (Ipv4Addr::from(o), ttl))))
}

/// The name at `at`, compressed or not, lower case and dot-separated; none when a label holds
/// anything but letters, digits, `-` and `_` (a dot inside a label would make another name), when
/// it is the root or longer than a name can be, or when it does not end within 128 labels and
/// pointers.
fn read_name(m: &[u8], mut at: usize) -> Option<String> {
    let mut name = String::new();
    for _ in 0..128 {
        let len = *m.get(at)?;
        match len & 0xc0 {
            0x00 if len == 0 => return (!name.is_empty()).then_some(name),
            0x00 => {
                let label = m.get(at + 1..at + 1 + usize::from(len))?;
                if !label
                    .iter()
                    .all(|b| b.is_ascii_alphanumeric() || b"-_".contains(b))
                {
                    return None;
                }
                if !name.is_empty() {
                    name.push('.');
                }
                name.extend(label.iter().map(|b| char::from(b.to_ascii_lowercase())));
                if name.len() > MAX_NAME {
                    return None;
                }
                at += 1 + usize::from(len);
            }
            0xc0 => at = usize::from(u16_at(m, at)? & 0x3fff),
            _ => return None,
        }
    }
    None
}

#[cfg(test)]
mod tests;
