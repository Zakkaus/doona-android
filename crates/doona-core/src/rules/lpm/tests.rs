use super::*;
use crate::rules::tests::Rng;
use std::collections::BTreeMap;
use std::net::{Ipv4Addr, Ipv6Addr};

fn addr(key: u128, width: u8) -> IpAddr {
    match width {
        32 => IpAddr::V4(Ipv4Addr::from((key >> 96) as u32)),
        _ => IpAddr::V6(Ipv6Addr::from(key)),
    }
}

/// What a walk must yield, by brute force: for each length, the smallest value inserted at a
/// prefix of that length that holds `key`.
fn oracle(prefixes: &[(u128, u8, u32)], key: u128) -> Vec<(u8, u32)> {
    let mut by_len = BTreeMap::new();
    for &(bits, len, value) in prefixes {
        if mask(bits, len) == mask(key, len) {
            let v = by_len.entry(len).or_insert(value);
            *v = (*v).min(value);
        }
    }
    by_len.into_iter().collect()
}

/// Random keys near a few bases, so prefixes nest, share runs and part at every depth.
fn near(rng: &mut Rng, bases: &[u128], width: u8) -> u128 {
    let base = bases[rng.below(bases.len() as u64) as usize];
    let flip = rng.below(u64::from(width) + 1) as u32;
    let noise = if flip == 0 {
        0
    } else {
        rng.u128() >> (128 - flip)
    };
    let key = base ^ (noise << (128 - u32::from(width)));
    mask(key, width)
}

fn check_against_oracle(width: u8, seed: u64) {
    let mut rng = Rng(seed);
    for _ in 0..40 {
        let bases: Vec<u128> = (0..6).map(|_| mask(rng.u128(), width)).collect();
        let count = 1 + rng.below(300) as usize;
        let prefixes: Vec<(u128, u8, u32)> = (0..count)
            .map(|_| {
                let len = rng.below(u64::from(width) + 1) as u8;
                let value = rng.below(count as u64 * 2) as u32;
                (near(&mut rng, &bases, width), len, value)
            })
            .collect();
        let mut trie = Trie::default();
        for &(bits, len, value) in &prefixes {
            trie.insert(addr(bits, width), len, value);
        }
        // 2n + 1 in the family, and the other's root.
        assert!(trie.nodes() <= 2 * count + 2, "{} nodes", trie.nodes());
        for _ in 0..500 {
            let key = if rng.below(4) == 0 {
                mask(rng.u128(), width)
            } else {
                near(&mut rng, &bases, width)
            };
            let want = oracle(&prefixes, key);
            let got: Vec<(u8, u32)> = trie.walk(addr(key, width)).collect();
            assert_eq!(got, want, "key {key:#x}");
            // The longest prefix is the walk's last; the first rule, its smallest value.
            assert_eq!(got.last(), want.iter().max_by_key(|p| p.0));
            let first = want.iter().map(|p| p.1).min().unwrap_or(NONE);
            assert_eq!(trie.first(addr(key, width)), first);
        }
    }
}

#[test]
fn ipv4_walks_match_a_brute_force_oracle() {
    check_against_oracle(32, 0x5eed_0004);
}

#[test]
fn ipv6_walks_match_a_brute_force_oracle() {
    check_against_oracle(128, 0x5eed_0006);
}

#[test]
fn zero_and_full_length_prefixes_hold_all_and_one_address() {
    let mut trie = Trie::default();
    trie.insert("0.0.0.0".parse().unwrap(), 0, 7);
    trie.insert("192.0.2.1".parse().unwrap(), 32, 3);
    let v4 = |s: &str| trie.walk(s.parse().unwrap()).collect::<Vec<_>>();
    assert_eq!(v4("192.0.2.1"), [(0, 7), (32, 3)]);
    assert_eq!(v4("192.0.2.0"), [(0, 7)]);
    assert_eq!(v4("255.255.255.255"), [(0, 7)]);
    // The families are apart: a v4 /0 holds no IPv6 address.
    assert_eq!(trie.first("::".parse().unwrap()), NONE);
    trie.insert("::".parse().unwrap(), 0, 9);
    trie.insert("2001:db8::1".parse().unwrap(), 128, 1);
    assert_eq!(trie.first("2001:db8::1".parse().unwrap()), 1);
    assert_eq!(trie.first("2001:db8::2".parse().unwrap()), 9);
}

#[test]
fn host_bits_are_ignored_and_the_smaller_value_is_kept() {
    let mut trie = Trie::default();
    trie.insert("10.1.2.3".parse().unwrap(), 8, 5);
    trie.insert("10.0.0.0".parse().unwrap(), 8, 2);
    trie.insert("10.9.9.9".parse().unwrap(), 8, 4);
    assert_eq!(
        trie.walk("10.200.0.1".parse().unwrap()).collect::<Vec<_>>(),
        [(8, 2)]
    );
    assert_eq!(trie.nodes(), 3);
}

#[test]
fn twenty_thousand_host_prefixes_take_at_most_two_nodes_each() {
    let mut rng = Rng(0x5eed_1280);
    let mut trie = Trie::default();
    for i in 0..20_000 {
        trie.insert(IpAddr::V6(Ipv6Addr::from(rng.u128())), 128, i);
    }
    trie.shrink();
    assert!(trie.nodes() <= 2 * 20_000 + 2, "{}", trie.nodes());
}
