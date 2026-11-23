//! CNAME data that does not decompress.

use super::*;

/// A CNAME whose name does not decompress, a pointer to itself or past the message, makes the
/// whole answer map nothing, the address before it included, as other bad CNAME data does.
#[test]
fn a_cname_whose_pointer_does_not_decompress_maps_nothing() {
    let name = "www.example";
    // The question, an A record of 16 bytes, then the CNAME's owner pointer and fixed fields.
    let data_at = 12 + wire(name).len() + 4 + 16 + 12;
    let itself = [0xc0 | (data_at >> 8) as u8, data_at as u8];
    for (case, data) in [("itself", itself), ("past the message", [0xff, 0xff])] {
        let records = [
            a(&[192, 0, 2, 1], 300),
            Rr {
                owner: AT_QUESTION.to_vec(),
                kind: TYPE_CNAME,
                ttl: 300,
                data: &data,
            },
        ];
        let m = answer(name, &records);
        assert_eq!(m.len(), data_at + 2, "{case}");
        let names = Names::new();
        names.learn_at(&m, 0);
        assert_eq!(names.len(), 0, "{case}");
    }
}
