//! `DST-PORT` values, read as Mihomo reads them ([`parse`]).

use crate::dto::ErrorCode;

/// The most pieces one value may have, as Mihomo counts them, empty ones included
/// (`common/utils/ranges.go:25-28`, Mihomo `100ff73a`).
pub(super) const MAX_PIECES: usize = 28;

/// The pieces of a `DST-PORT` value that are ranges, as `utils.NewUnsignedRanges` splits it
/// (`common/utils/ranges.go:18-24,36-38`): the value trimmed, cut at `/` and at `,`, and the empty
/// pieces dropped. The limits count these, and [`parse`] reads them.
pub(super) fn pieces(value: &str) -> impl Iterator<Item = &str> {
    value.trim().split(['/', ',']).filter(|p| !p.is_empty())
}

/// Reads every range of a `DST-PORT` value; `parse_failed` where Mihomo refuses the
/// value: no range in it (`rules/common/port.go:42-49`, `*` reads as none at `ranges.go:19-20`),
/// more than [`MAX_PIECES`], or a piece that does not parse.
pub(super) fn parse(value: &str) -> Result<(), ErrorCode> {
    let refused = Err(ErrorCode::ParseFailed);
    let value = value.trim();
    if value == "*" || value.split(['/', ',']).count() > MAX_PIECES {
        return refused;
    }
    let mut any = false;
    for piece in pieces(value) {
        range(piece)?;
        any = true;
    }
    if any { Ok(()) } else { refused }
}

/// One piece (`common/utils/range.go:16-28,65-87`): spaces only are the range 0-0; else one port
/// or two joined by `-`, a reversed pair turned around.
fn range(piece: &str) -> Result<(u16, u16), ErrorCode> {
    let piece = piece.trim();
    if piece.is_empty() {
        return Ok((0, 0));
    }
    let (a, b) = match piece.split_once('-') {
        None => (piece, piece),
        Some((_, b)) if b.contains('-') => return Err(ErrorCode::ParseFailed),
        Some(ends) => ends,
    };
    let (a, b) = (port(a)?, port(b)?);
    Ok((a.min(b), a.max(b)))
}

/// A port as `strconv.ParseUint` reads it once `[`, ` ` and `]` are trimmed from its ends
/// (`range.go:71,79,89-95`): decimal digits, no sign. A number past 65,535 is refused, where
/// Mihomo keeps its low 16 bits (`T(val)`, `range.go:91`) and so matches a port the profile never
/// named.
fn port(s: &str) -> Result<u16, ErrorCode> {
    let s = s.trim_matches(['[', ' ', ']']);
    if s.is_empty() || !s.bytes().all(|b| b.is_ascii_digit()) {
        return Err(ErrorCode::ParseFailed);
    }
    s.parse().map_err(|_| ErrorCode::ParseFailed)
}
