//! What a body must pass before serde_json or serde_yaml builds anything from it. Each check is
//! linear in the body and refuses it as `parse_failed`.

use crate::dto::ErrorCode;
use std::mem::MaybeUninit;

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

/// Refuses a body that has a directive, more than [`MAX_ITEMS`] items, or flow collections nested
/// deeper than [`MAX_FLOW_DEPTH`].
pub(super) fn check(text: &str) -> Result<(), ErrorCode> {
    if has_directive(text) || items(text) > MAX_ITEMS || flow_deeper_than(text, MAX_FLOW_DEPTH) {
        return Err(ErrorCode::ParseFailed);
    }
    Ok(())
}

/// An upper bound on the items `text` describes: every item after the first follows one of these
/// bytes, a separator, a collection opening, a block entry, a key, an alias or a line break.
pub(super) fn items(text: &str) -> usize {
    text.bytes()
        .filter(|b| matches!(b, b',' | b':' | b'-' | b'?' | b'[' | b'{' | b'*' | b'\n'))
        .count()
}

/// A `%YAML` or `%TAG` line. libyaml copies a `%TAG` prefix into every node that names its
/// handle, and serde_yaml keeps every event of a document before [`super::bounded`] counts one:
/// a 1 MB body, a 1 MiB prefix named 2,000 times, peaked at 2 GB. Clash and JSON bodies use
/// none.
fn has_directive(text: &str) -> bool {
    text.lines().any(|line| line.starts_with('%'))
}

/// Whether libyaml's scanner, reading `text`, opens more than `limit` flow collections at once.
/// The scanner looks at every open flow level again at each token, so N nested brackets cost N²
/// before serde_yaml's limit applies (20,000 took 0.43 s in a release build; an 800 KB body,
/// minutes), and JSON that serde_json refuses at its limit comes to serde_yaml next. This scan
/// stops at `limit + 1`. It runs libyaml's own scanner rather than counting bracket bytes: what
/// is a bracket and what is text in a quoted scalar, a comment or a tag is what libyaml decides,
/// and a count that judged one quote differently could be led to count low. A body the scanner
/// refuses ends the count where serde_yaml stops reading too.
fn flow_deeper_than(text: &str, limit: usize) -> bool {
    use unsafe_libyaml::{
        YAML_FLOW_MAPPING_END_TOKEN, YAML_FLOW_MAPPING_START_TOKEN, YAML_FLOW_SEQUENCE_END_TOKEN,
        YAML_FLOW_SEQUENCE_START_TOKEN, YAML_NO_TOKEN, YAML_STREAM_END_TOKEN, yaml_parser_delete,
        yaml_parser_initialize, yaml_parser_scan, yaml_parser_set_input_string, yaml_parser_t,
        yaml_token_delete, yaml_token_t,
    };
    let mut parser = MaybeUninit::<yaml_parser_t>::uninit();
    let mut token = MaybeUninit::<yaml_token_t>::uninit();
    let mut depth = 0_usize;
    let mut over = false;
    // SAFETY: the parser is initialized before any other call and deleted once, after the last;
    // it reads `text`, which outlives it. `yaml_parser_scan` fills `token` whether or not it
    // succeeds, and each token is deleted before the next scan.
    unsafe {
        if !yaml_parser_initialize(parser.as_mut_ptr()).ok {
            // Out of memory: no scan, and no tree either.
            return true;
        }
        let parser = parser.as_mut_ptr();
        yaml_parser_set_input_string(parser, text.as_ptr(), text.len() as u64);
        while !over && yaml_parser_scan(parser, token.as_mut_ptr()).ok {
            let kind = (*token.as_ptr()).type_;
            yaml_token_delete(token.as_mut_ptr());
            match kind {
                YAML_FLOW_SEQUENCE_START_TOKEN | YAML_FLOW_MAPPING_START_TOKEN => {
                    depth += 1;
                    over = depth > limit;
                }
                YAML_FLOW_SEQUENCE_END_TOKEN | YAML_FLOW_MAPPING_END_TOKEN => {
                    depth = depth.saturating_sub(1);
                }
                // The end, or no more tokens after an error.
                YAML_STREAM_END_TOKEN | YAML_NO_TOKEN => break,
                _ => {}
            }
        }
        yaml_parser_delete(parser);
    }
    over
}
