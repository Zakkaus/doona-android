//! The names DNS answers give addresses (rule-mode.md decision 3, ARCH D33). An answer the core
//! forwards maps to the question's name the address of each A record that name reaches through its
//! CNAME chain, so a new flow to that address is matched as that name; an answer to another
//! question than the one asked maps nothing, as does one with a CNAME whose data is not one name.
//! A flow is looked up once, when it opens.
//! Strict Private DNS and an app's own DoH never reach the core's resolver: their flows have no
//! name, so domain rules miss them and the rules that match by address, or the last one, decide.
//! The tun carries IPv4 only (`DoonaVpnService.tunBuilder`), so AAAA records name no flow.
//!
//! What an answer maps is read first ([`Learned`]) and put in the cache only once the answer is on
//! its way to the client (see `dns.rs`), so an answer dropped, or one the client never takes, maps
//! nothing.
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
use std::collections::{HashMap, HashSet};
use std::net::{IpAddr, Ipv4Addr};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

/// The most addresses one generation keeps a name for.
pub(crate) const CAPACITY: usize = 8_192;
/// The shortest and longest an answer's address keeps its name, in seconds.
pub(crate) const MIN_TTL: u32 = 60;
pub(crate) const MAX_TTL: u32 = 3_600;
/// The most addresses mapped while the lock is held: a lookup waits for one batch at most, and an
/// answer of thousands of addresses (one over TCP may hold some 4,000) maps all of them.
const BATCH: usize = 64;
const TYPE_A: u16 = 1;
const TYPE_CNAME: u16 = 5;
const CLASS_IN: u16 = 1;
/// The longest name, without its final dot.
const MAX_NAME: usize = 253;

pub(crate) struct Names {
    cache: Mutex<LruCache<Ipv4Addr, Entry>>,
    /// How many answers were put: a decision taken under one count can tell a name may have come
    /// since.
    puts: AtomicU64,
}

struct Entry {
    name: Arc<str>,
    /// When the mapping ends, in ms on `CLOCK_BOOTTIME`.
    until_ms: u64,
}

/// What an answer maps, read but not yet in a cache: its question's name, and the addresses that
/// name reaches with their TTLs.
pub(crate) struct Learned {
    name: Arc<str>,
    found: Vec<(Ipv4Addr, u32)>,
}

impl Learned {
    /// What `answer` maps to its question's name, when it answers the question of `query`; none
    /// when that is nothing. Anything but a successful answer to one A question in class IN, with a
    /// name of letters, digits, `-` and `_`, maps nothing. It reads the whole answer, so it runs
    /// before any lock is taken, and off the driver.
    pub fn read(query: &[u8], answer: &[u8]) -> Option<Learned> {
        if !same_question(query, answer) {
            return None;
        }
        Learned::of(answer)
    }

    /// What `m` maps, whatever question it was sent for.
    fn of(m: &[u8]) -> Option<Learned> {
        let (name, at) = question(m)?;
        let found = reached(m, &name, at).filter(|f| !f.is_empty())?;
        let name = name.into();
        Some(Learned { name, found })
    }
}

impl Names {
    pub fn new() -> Names {
        Names {
            cache: Mutex::new(LruCache::unbounded()),
            puts: AtomicU64::new(0),
        }
    }

    /// Maps what an answer gave, from now on.
    pub fn put(&self, learned: Learned) {
        self.put_at(learned, sys::boot_ms());
    }

    /// Maps what `answer` gives when it answers the question of `query` (see [`Learned::read`]).
    #[cfg(test)]
    pub fn learn(&self, query: &[u8], answer: &[u8]) {
        if let Some(learned) = Learned::read(query, answer) {
            self.put(learned);
        }
    }

    /// The name `ip` answered, while its mapping lasts.
    pub fn name(&self, ip: IpAddr) -> Option<Arc<str>> {
        self.name_at(ip, sys::boot_ms())
    }

    /// How many answers were put so far. Read before a lookup, it tells a decision taken on what
    /// the lookup found whether an answer was put since: a count that moved on means one was.
    pub fn version(&self) -> u64 {
        self.puts.load(Ordering::Acquire)
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        lock(&self.cache).len()
    }

    /// Maps `ip` to `name` for an hour, as an answer would.
    #[cfg(test)]
    pub fn map(&self, ip: Ipv4Addr, name: &str) {
        let until_ms = sys::boot_ms() + u64::from(MAX_TTL) * 1000;
        let name = name.into();
        lock(&self.cache).put(ip, Entry { name, until_ms });
        self.puts.fetch_add(1, Ordering::Release);
    }

    /// Maps what `m` gives, whatever question it was sent for.
    #[cfg(test)]
    pub(super) fn learn_at(&self, m: &[u8], now_ms: u64) {
        if let Some(learned) = Learned::of(m) {
            self.put_at(learned, now_ms);
        }
    }

    fn put_at(&self, Learned { name, found }: Learned, now_ms: u64) {
        for batch in found.chunks(BATCH) {
            let mut cache = lock(&self.cache);
            for &(addr, ttl) in batch {
                if cache.len() >= CAPACITY && !cache.contains(&addr) {
                    cache.pop_lru();
                }
                let until_ms = now_ms + u64::from(ttl) * 1000;
                let name = Arc::clone(&name);
                cache.put(addr, Entry { name, until_ms });
            }
        }
        // Counted once every address can be looked up: a reader that sees the new count finds them.
        self.puts.fetch_add(1, Ordering::Release);
    }

    pub(super) fn name_at(&self, ip: IpAddr, now_ms: u64) -> Option<Arc<str>> {
        let IpAddr::V4(addr) = ip.to_canonical() else {
            return None;
        };
        let mut cache = lock(&self.cache);
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
/// question; none unless `m` is a whole successful answer (QR set, opcode QUERY, TC clear,
/// NOERROR) to one A question in IN.
fn question(m: &[u8]) -> Option<(String, usize)> {
    let answered = m.len() >= HEADER && m[2] & 0xfa == 0x80 && m[3] & 0x0f == 0;
    if !answered || count(m, 4) != 1 {
        return None;
    }
    let name = read_name(m, HEADER)?;
    let end = question_end(m)?;
    (u16_at(m, end - 4)? == TYPE_A && u16_at(m, end - 2)? == CLASS_IN).then_some((name, end))
}

/// Whether `answer` answers the question of `query`: one question each, with the same name as each
/// reads it, pointers followed and in any case (DNS 0x20), and the same type and class. The same
/// bytes are not enough: a pointer reads as whatever its own message holds where it points.
fn same_question(query: &[u8], answer: &[u8]) -> bool {
    if count(query, 4) != 1 || count(answer, 4) != 1 {
        return false;
    }
    let (Some(q), Some(a)) = (question_end(query), question_end(answer)) else {
        return false;
    };
    let asked = read_name(query, HEADER);
    asked.is_some() && asked == read_name(answer, HEADER) && query[q - 4..q] == answer[a - 4..a]
}

/// What an A or CNAME record in IN holds.
enum Data {
    /// An address, and its TTL held to the bounds.
    A(Ipv4Addr, u32),
    /// Where the name it aliases starts.
    Alias(usize),
}

/// A CNAME record whose data is not one name that ends where the data does: the answer is not
/// read further.
struct Malformed;

/// The addresses, with their TTLs, of the A records in the answer section from `at` that `name`
/// reaches through its CNAME chain: `name`, the name its CNAME gives (the first, if it has more),
/// and so on, as far as the answer's records go. An alias back into the chain ends it, as does a
/// record that runs past the message; what came before counts. None when a CNAME's data is not one
/// name: its alias would be read from the bytes around it.
fn reached(m: &[u8], name: &str, mut at: usize) -> Option<Vec<(Ipv4Addr, u32)>> {
    let (mut aliases, mut addresses) = (HashMap::new(), Vec::new());
    for _ in 0..count(m, 6) {
        let Some((end, held)) = record(m, at) else {
            break;
        };
        at = end;
        match held.ok()? {
            Some((owner, Data::Alias(to))) => {
                aliases.entry(owner).or_insert(to);
            }
            Some((owner, Data::A(addr, ttl))) => addresses.push((owner, addr, ttl)),
            None => {}
        }
    }
    // Each step takes the alias of a name not taken before, so the chain has at most one step per
    // CNAME record.
    let (mut chain, mut last) = (HashSet::from([name.to_owned()]), name.to_owned());
    for _ in 0..aliases.len() {
        let Some(next) = aliases.get(&last).and_then(|&to| read_name(m, to)) else {
            break;
        };
        if !chain.insert(next.clone()) {
            break;
        }
        last = next;
    }
    let found = addresses
        .into_iter()
        .filter(|(owner, ..)| chain.contains(owner))
        .map(|(_, addr, ttl)| (addr, ttl))
        .collect();
    Some(found)
}

/// The offset past a record, and for an A or CNAME record in IN, its owner's name and what it
/// holds; `Malformed` for a CNAME whose name does not end exactly where its data does.
type Record = (usize, Result<Option<(String, Data)>, Malformed>);

/// The record at `at`; none when it runs past the message.
fn record(m: &[u8], at: usize) -> Option<Record> {
    let owner = at;
    let at = skip_name(m, at)?;
    let (kind, class, len) = (u16_at(m, at)?, u16_at(m, at + 2)?, u16_at(m, at + 8)?);
    let ttl = u32::from_be_bytes(m.get(at + 4..at + 8)?.try_into().ok()?);
    let data = m.get(at + 10..at + 10 + usize::from(len))?;
    let end = at + 10 + data.len();
    // RFC 2181 §8: a TTL with the top bit set is read as zero.
    let ttl = if ttl >> 31 == 1 { 0 } else { ttl };
    let held = match (kind, class) {
        (TYPE_A, CLASS_IN) => <[u8; 4]>::try_from(data)
            .ok()
            .map(|o| Data::A(Ipv4Addr::from(o), ttl.clamp(MIN_TTL, MAX_TTL))),
        // With no data, or too little, the name would run on into the next record.
        (TYPE_CNAME, CLASS_IN) if skip_name(m, at + 10) != Some(end) => {
            return Some((end, Err(Malformed)));
        }
        (TYPE_CNAME, CLASS_IN) => Some(Data::Alias(at + 10)),
        _ => None,
    };
    Some((end, Ok(held.and_then(|d| Some((read_name(m, owner)?, d))))))
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
