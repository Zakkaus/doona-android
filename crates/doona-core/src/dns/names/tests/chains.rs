//! CNAME chains as long as an answer's records make them.

use super::*;

/// A CNAME chain of `hops` aliases from the question `c.example`, each `h<i>.example`, and the
/// address of the last; every name after the question is compressed.
fn chain(hops: usize) -> Vec<u8> {
    // The question's name is at 12; its label `example` at 14; the answer section at 27.
    let example = [0xc0, 14];
    let datas: Vec<Vec<u8>> = (0..hops)
        .map(|i| {
            let label = format!("h{i}");
            let mut d = vec![label.len() as u8];
            d.extend_from_slice(label.as_bytes());
            d.extend_from_slice(&example);
            d
        })
        .collect();
    let to = |at: usize| vec![0xc0 | (at >> 8) as u8, at as u8];
    let (mut records, mut owner, mut at) = (Vec::new(), AT_QUESTION.to_vec(), 27);
    for d in &datas {
        records.push(Rr {
            owner: owner.clone(),
            kind: TYPE_CNAME,
            ttl: 300,
            data: d,
        });
        // The alias this record gives starts after its owner's pointer and the fixed fields.
        owner = to(at + 12);
        at += 12 + d.len();
    }
    records.push(Rr {
        owner,
        kind: TYPE_A,
        ttl: 300,
        data: &[192, 0, 2, 1],
    });
    answer("c.example", &records)
}

/// A chain is followed as far as the answer's records take it: the address at the end of 16
/// aliases, or of 600 (as many as 14-bit pointers reach), maps to the question's name.
#[test]
fn a_long_cname_chain_maps_its_end() {
    for hops in [16, 17, 600] {
        let m = chain(hops);
        // The address's owner, after every alias.
        let at = 27 + (0..hops).map(|i| 16 + format!("{i}").len()).sum::<usize>();
        let last = format!("h{}.example", hops - 1);
        assert_eq!(read_name(&m, at), Some(last));
        let names = Names::new();
        names.learn_at(&m, 0);
        assert_eq!(
            name_of(&names, v4(192, 0, 2, 1), 1).as_deref(),
            Some("c.example"),
            "{hops}"
        );
    }
}
