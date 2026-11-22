//! What a body must pass before serde_json or serde_yaml builds anything from it. Each check is
//! linear in the body and refuses it as `parse_failed`. The body checked is the one parsed
//! ([`super::content`]), byte for byte.

use crate::dto::ErrorCode;
use std::mem::MaybeUninit;
use unsafe_libyaml::yaml_token_type_t;

/// The most items, YAML events or JSON values, a structured body may describe; past it a body is
/// `parse_failed` before any tree is built. serde_yaml lists every event of a document before it
/// builds a value, at about 200 bytes an event: an 8 MiB body of one-digit items peaked at 836 MB.
/// At this bound the worst body measured, `proxies:` and 399,997 nested `- `, peaks at 127 MB; a
/// flow sequence peaks at 85 MB and a block one at 46 MB. A Mihomo profile of 2,000 nodes, a group
/// of all of them and 20,000 rules (1.6 MB) counts 204,000 and peaks at 30 MB (VmHWM, release
/// build on the desktop, 2026-11-23). The tree is held to as many values, and to the body's
/// length in string bytes, aliases included ([`super::bounded`]).
pub(super) const MAX_ITEMS: usize = 400_000;

/// The deepest flow nesting a body may reach. serde_yaml reads no deeper, and serde_json one less.
const MAX_FLOW_DEPTH: usize = 128;

/// How libyaml's scanner read a body [`check`] let through.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum Scan {
    /// To its end: serde_yaml may build it.
    Read,
    /// Up to an error. serde_yaml runs the same scanner over the same bytes and would fail there,
    /// so only serde_json, whose recursion limit bounds it, may build the body.
    Failed,
}

/// Refuses a body that describes more than [`MAX_ITEMS`] items or that [`scan`] refuses; says
/// how libyaml's scanner read any other.
pub(super) fn check(text: &str) -> Result<Scan, ErrorCode> {
    if items(text) > MAX_ITEMS {
        return Err(ErrorCode::ParseFailed);
    }
    scan(text)
}

/// An upper bound on the items `text` describes: every item after the first follows one of these
/// bytes, a separator, a collection opening, a block entry, a key, an alias or a line break.
pub(super) fn items(text: &str) -> usize {
    text.bytes()
        .filter(|b| matches!(b, b',' | b':' | b'-' | b'?' | b'[' | b'{' | b'*' | b'\n'))
        .count()
}

/// Runs libyaml's scanner over `text`, as serde_yaml would, and refuses the body at the first
/// token that [`Tokens::next`] refuses. These are libyaml's own tokens, not a count of bytes or
/// lines: what is a bracket and what is text in a quoted scalar, a comment or a tag, and where a
/// line ends (a bare `\r` ends one), is what libyaml decides, and a count that judged one of them
/// otherwise could be led to count low.
fn scan(text: &str) -> Result<Scan, ErrorCode> {
    use unsafe_libyaml::{
        yaml_parser_delete, yaml_parser_initialize, yaml_parser_scan, yaml_parser_set_input_string,
        yaml_parser_t, yaml_token_delete, yaml_token_t,
    };
    let mut parser = MaybeUninit::<yaml_parser_t>::uninit();
    let mut token = MaybeUninit::<yaml_token_t>::uninit();
    let mut tokens = Tokens::default();
    let scan;
    // SAFETY: the parser is initialized before any other call and deleted once, after the last;
    // it reads `text`, which outlives it. `yaml_parser_scan` fills `token` whether or not it
    // succeeds, and each token is deleted before the next scan.
    unsafe {
        if !yaml_parser_initialize(parser.as_mut_ptr()).ok {
            // Out of memory: no scan, and no tree either.
            return Err(ErrorCode::ParseFailed);
        }
        let parser = parser.as_mut_ptr();
        yaml_parser_set_input_string(parser, text.as_ptr(), text.len() as u64);
        scan = loop {
            if yaml_parser_scan(parser, token.as_mut_ptr()).fail {
                break Ok(Scan::Failed);
            }
            let kind = (*token.as_ptr()).type_;
            yaml_token_delete(token.as_mut_ptr());
            if let Some(done) = tokens.next(kind) {
                break done;
            }
        };
        yaml_parser_delete(parser);
    }
    scan
}

/// What the scan has seen so far.
#[derive(Default)]
struct Tokens {
    /// The flow collections open.
    depth: usize,
    /// Whether the first document has begun: a start marker, an end marker, or content.
    begun: bool,
    /// Whether it has content: any token but the stream's, a directive or a document marker.
    content: bool,
}

impl Tokens {
    /// What the token `kind` decides, `None` to read on. Refused:
    /// - a flow collection opened past [`MAX_FLOW_DEPTH`]. The scanner looks at every open flow
    ///   level again at each token, so N nested brackets cost N² before serde_yaml's limit
    ///   applies (20,000 took 0.43 s in a release build; an 800 KB body, minutes), and JSON that
    ///   serde_json refuses at its limit comes to serde_yaml next;
    /// - a `%YAML` or `%TAG` directive. libyaml copies a `%TAG` prefix into every node that names
    ///   its handle, and serde_yaml keeps every event of a document before [`super::bounded`]
    ///   counts one: a 1 MB body, a 1 MiB prefix named 2,000 times, peaked at 2 GB. Clash and
    ///   JSON bodies use none;
    /// - a second document: a start marker once the first has begun, or an end marker once it
    ///   has content. serde_yaml reads a second document in full before it refuses it. A
    ///   leading `---`, which Clash files carry, begins the first.
    fn next(&mut self, kind: yaml_token_type_t) -> Option<Result<Scan, ErrorCode>> {
        use unsafe_libyaml::{
            YAML_DOCUMENT_END_TOKEN, YAML_DOCUMENT_START_TOKEN, YAML_FLOW_MAPPING_END_TOKEN,
            YAML_FLOW_MAPPING_START_TOKEN, YAML_FLOW_SEQUENCE_END_TOKEN,
            YAML_FLOW_SEQUENCE_START_TOKEN, YAML_NO_TOKEN, YAML_STREAM_END_TOKEN,
            YAML_STREAM_START_TOKEN, YAML_TAG_DIRECTIVE_TOKEN, YAML_VERSION_DIRECTIVE_TOKEN,
        };
        let refused = Some(Err(ErrorCode::ParseFailed));
        match kind {
            YAML_STREAM_START_TOKEN => {}
            YAML_STREAM_END_TOKEN => return Some(Ok(Scan::Read)),
            // No token comes only after the end or an error, which ended the scan already.
            YAML_NO_TOKEN => return Some(Ok(Scan::Failed)),
            YAML_VERSION_DIRECTIVE_TOKEN | YAML_TAG_DIRECTIVE_TOKEN => return refused,
            YAML_DOCUMENT_START_TOKEN if self.begun => return refused,
            YAML_DOCUMENT_END_TOKEN if self.content => return refused,
            YAML_DOCUMENT_START_TOKEN | YAML_DOCUMENT_END_TOKEN => self.begun = true,
            _ => {
                (self.begun, self.content) = (true, true);
                match kind {
                    YAML_FLOW_SEQUENCE_START_TOKEN | YAML_FLOW_MAPPING_START_TOKEN => {
                        self.depth += 1;
                    }
                    YAML_FLOW_SEQUENCE_END_TOKEN | YAML_FLOW_MAPPING_END_TOKEN => {
                        self.depth = self.depth.saturating_sub(1);
                    }
                    _ => {}
                }
                if self.depth > MAX_FLOW_DEPTH {
                    return refused;
                }
            }
        }
        None
    }
}
