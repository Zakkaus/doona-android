//! Prefix matching over IP addresses: a binary trie walked from the most significant bit, IPv4
//! and IPv6 apart, after honk-core's `routing/lpm.rs` (`BinaryLpmTrie`, daeuniverse/honk
//! `8919784a`). Two changes:
//! - a node holds a run of bits, not one, so n prefixes take at most 2n + 1 nodes whatever their
//!   lengths. honk's trie takes a node per bit: 20,000 `/128` prefixes would be 2.56 million
//!   nodes, this one 40,001 (32 bytes each);
//! - a node keeps a value, the smallest inserted at its prefix, and a walk yields the value of
//!   every prefix that holds the address, shortest first. Its last is the longest prefix's; the
//!   rules take the smallest, the first rule that matches.

use super::NONE;
use std::net::IpAddr;

#[derive(Clone, Copy, Debug)]
struct Node {
    /// The prefix, left-aligned (IPv4 in the top 32 bits); bits past `len` are zero.
    bits: u128,
    len: u8,
    /// The smallest value inserted at exactly this prefix, [`NONE`] for a node only the trie
    /// needed.
    value: u32,
    /// The nodes under this one whose next bit is 0 and 1; 0, the root, for none.
    child: [u32; 2],
}

impl Node {
    fn new(bits: u128, len: u8, value: u32) -> Node {
        Node {
            bits,
            len,
            value,
            child: [0; 2],
        }
    }
}

#[derive(Clone, Debug)]
pub(super) struct Trie {
    v4: Vec<Node>,
    v6: Vec<Node>,
}

impl Default for Trie {
    fn default() -> Trie {
        Trie {
            v4: vec![Node::new(0, 0, NONE)],
            v6: vec![Node::new(0, 0, NONE)],
        }
    }
}

impl Trie {
    /// Adds the prefix of `len` bits of `addr`, at most the family's width, with `value`. The
    /// address's other bits are ignored.
    pub(super) fn insert(&mut self, addr: IpAddr, len: u8, value: u32) {
        let (key, width) = key(addr);
        debug_assert!(len <= width);
        let len = len.min(width);
        let nodes = match addr {
            IpAddr::V4(_) => &mut self.v4,
            IpAddr::V6(_) => &mut self.v6,
        };
        insert(nodes, mask(key, len), len, value);
    }

    /// Releases the spare capacity of the build.
    pub(super) fn shrink(&mut self) {
        self.v4.shrink_to_fit();
        self.v6.shrink_to_fit();
    }

    /// The length and value of every prefix that holds `addr`, shortest first.
    pub(super) fn walk(&self, addr: IpAddr) -> Walk<'_> {
        let (key, width) = key(addr);
        let nodes = match addr {
            IpAddr::V4(_) => &self.v4,
            IpAddr::V6(_) => &self.v6,
        };
        Walk {
            nodes,
            key,
            width,
            at: Some(0),
        }
    }

    /// The smallest value of the prefixes that hold `addr`, [`NONE`] if none does.
    pub(super) fn first(&self, addr: IpAddr) -> u32 {
        self.walk(addr)
            .map(|(_, value)| value)
            .min()
            .unwrap_or(NONE)
    }

    /// The nodes of both families, the roots included.
    #[cfg(test)]
    pub(super) fn nodes(&self) -> usize {
        self.v4.len() + self.v6.len()
    }
}

/// `addr` left-aligned in 128 bits, and its family's width.
fn key(addr: IpAddr) -> (u128, u8) {
    match addr {
        IpAddr::V4(a) => (u128::from(u32::from(a)) << 96, 32),
        IpAddr::V6(a) => (u128::from(a), 128),
    }
}

/// The prefixes holding one address, from [`Trie::walk`].
pub(super) struct Walk<'a> {
    nodes: &'a [Node],
    key: u128,
    width: u8,
    /// The next node to look at.
    at: Option<u32>,
}

impl Iterator for Walk<'_> {
    type Item = (u8, u32);

    fn next(&mut self) -> Option<(u8, u32)> {
        while let Some(at) = self.at {
            let node = &self.nodes[at as usize];
            if mask(self.key, node.len) != node.bits {
                self.at = None;
                return None;
            }
            self.at = (node.len < self.width)
                .then(|| node.child[bit(self.key, node.len)])
                .filter(|&c| c != 0);
            if node.value != NONE {
                return Some((node.len, node.value));
            }
        }
        None
    }
}

/// Adds the prefix `key`/`len`, `key` masked to `len` bits, keeping the smaller value where the
/// prefix is already in.
fn insert(nodes: &mut Vec<Node>, key: u128, len: u8, value: u32) {
    // Invariant: the node at `at` is a prefix of `key` shorter than or as long as `len`.
    let mut at = 0;
    loop {
        let node = nodes[at];
        if node.len == len {
            nodes[at].value = node.value.min(value);
            return;
        }
        let side = bit(key, node.len);
        let c = node.child[side];
        if c == 0 {
            nodes[at].child[side] = push(nodes, Node::new(key, len, value));
            return;
        }
        let child = nodes[c as usize];
        let common = shared(child.bits, key).min(child.len).min(len);
        if common == child.len {
            at = c as usize;
            continue;
        }
        // The child and the new prefix part at `common`: a node there takes the child, and the
        // new prefix is that node, or its other child.
        let fork_value = if common == len { value } else { NONE };
        let fork = push(nodes, Node::new(mask(key, common), common, fork_value));
        nodes[fork as usize].child[bit(child.bits, common)] = c;
        if common < len {
            let leaf = push(nodes, Node::new(key, len, value));
            nodes[fork as usize].child[bit(key, common)] = leaf;
        }
        nodes[at].child[side] = fork;
        return;
    }
}

fn push(nodes: &mut Vec<Node>, node: Node) -> u32 {
    nodes.push(node);
    (nodes.len() - 1) as u32
}

/// `bits` with every bit past the first `len` cleared.
fn mask(bits: u128, len: u8) -> u128 {
    match len {
        0 => 0,
        _ => bits & (u128::MAX << (128 - u32::from(len))),
    }
}

/// Bit `pos` of `bits`, counted from the most significant; `pos` < 128.
fn bit(bits: u128, pos: u8) -> usize {
    ((bits >> (127 - u32::from(pos))) & 1) as usize
}

/// How many leading bits `a` and `b` share.
fn shared(a: u128, b: u128) -> u8 {
    (a ^ b).leading_zeros() as u8
}

#[cfg(test)]
mod tests;
