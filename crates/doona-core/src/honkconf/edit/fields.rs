use honk_config::parser::cursor::{Document, Segment};
use honk_config::parser::lexer::{Source, TokenKind};
use std::ops::Range;

use super::{File, List, RuleRow, UpstreamField};

fn plain(s: &str) -> bool {
    !s.is_empty()
        && !s
            .chars()
            .any(|c| c.is_control() || c.is_whitespace() || "#{}'\"()".contains(c))
        && !s.contains("->")
        && !s.contains("outbound:")
}

fn scalar(text: &str, mut bytes: Range<usize>, value: &str) -> Option<String> {
    let old = &text[bytes.clone()];
    if old.is_empty() || value.is_empty() {
        return None;
    }
    let quote = old.chars().next().filter(|c| matches!(c, '\'' | '"'));
    if let Some(q) = quote {
        let inner = old.strip_prefix(q)?.strip_suffix(q)?;
        if inner.contains(q) || value.contains(q) || value.ends_with('\\') {
            return None;
        }
        bytes.start += 1;
        bytes.end -= 1;
    } else if !plain(old) || !plain(value) {
        return None;
    }
    let mut out = text.to_owned();
    out.replace_range(bytes, value);
    Some(out)
}

fn trimmed(text: &str, bytes: Range<usize>) -> Range<usize> {
    let tail = text[bytes.clone()].trim_start();
    let start = bytes.end - tail.len();
    start..start + tail.trim_end().len()
}

pub(super) fn filter(
    file: &File,
    name: &str,
    occurrence: usize,
    expression: &str,
) -> Option<String> {
    let doc = file.document()?;
    let group = doc
        .sections()
        .filter(|s| s.header() == "group")
        .flat_map(|s| s.body().into_iter().flatten())
        .filter(|s| s.header() == name)
        .last()?;
    let mut filters = group
        .body()?
        .filter(|s| s.span() == s.header_span())
        .filter_map(|s| {
            let (key, _) = s.header().split_once(':')?;
            let span = s.header_span();
            (key.trim() == "filter")
                .then(|| trimmed(&file.source.content, span.start + key.len() + 1..span.end))
        });
    let bytes = filters.nth(occurrence)?;
    let tokens = Source::new(expression, file.source.source.clone()).tokenize(&mut Vec::new());
    if expression.trim().is_empty()
        || tokens
            .iter()
            .any(|t| !matches!(t.kind, TokenKind::Word | TokenKind::Whitespace))
    {
        return None;
    }
    let text = &file.source.content;
    let mut out = text.to_string();
    out.replace_range(bytes, expression);
    Some(out)
}

pub(super) fn fallback(file: &File, row: &RuleRow, value: &str) -> Option<String> {
    let text = &file.source.content;
    file.rule_block(&file.document()?, row)?;
    let colon = text[row.bytes.clone()].find(':')? + row.bytes.start;
    let bytes = trimmed(text, colon + 1..row.bytes.end);
    if row.list == List::Traffic && text[bytes.clone()].starts_with(['\'', '"']) {
        return None;
    }
    scalar(text, bytes, value)
}

fn split(
    doc: &Document<'_>,
    bytes: Range<usize>,
    delimiter: &str,
) -> Option<(Range<usize>, Range<usize>)> {
    let text = doc.source().text();
    let tokens = doc.tokens();
    let i = tokens.partition_point(|t| t.span.end <= bytes.start);
    let pos = tokens[i..]
        .iter()
        .take_while(|t| t.span.start < bytes.end)
        .flat_map(|t| super::unquoted(text, t, delimiter))
        .find(|p| *p >= bytes.start && p + delimiter.len() <= bytes.end)?;
    Some((
        trimmed(text, bytes.start..pos),
        trimmed(text, pos + delimiter.len()..bytes.end),
    ))
}

fn upstream_value(doc: &Document<'_>, entry: Segment<'_, '_>, name: &str) -> Option<Range<usize>> {
    let span = entry.header_span();
    let (key, value) = split(doc, span.start..span.end, ":")?;
    let tokens = doc.tokens();
    let first = &tokens[tokens.partition_point(|t| t.span.end <= span.start)];
    let decoded = first
        .quoted
        .first()
        .filter(|q| q.start == key.start && q.end == key.end)
        .map_or_else(
            || &doc.source().text()[key],
            |q| doc.source().raw(q.interior()),
        );
    (decoded == name).then_some(value)
}

pub(super) fn upstream(
    file: &File,
    name: &str,
    field: UpstreamField,
    value: &str,
) -> Option<String> {
    let doc = file.document()?;
    let mut fields = doc
        .sections()
        .filter(|s| s.header() == "dns")
        .flat_map(|s| s.body().into_iter().flatten())
        .filter(|s| s.header() == "upstream")
        .flat_map(|s| s.body().into_iter().flatten())
        .filter(|s| s.span() == s.header_span())
        .filter_map(|s| upstream_value(&doc, s, name));
    let rest = fields.next()?;
    if fields.next().is_some() {
        return None;
    }
    let parts = split(&doc, rest.clone(), "->").or_else(|| split(&doc, rest.clone(), "outbound:"));
    let text = &file.source.content;
    match (field, parts) {
        (UpstreamField::Uri, parts) => scalar(text, parts.map_or(rest, |(uri, _)| uri), value),
        (UpstreamField::Detour, Some((_, detour))) => scalar(text, detour, value),
        (UpstreamField::Detour, None) if plain(value) => {
            let mut out = text.to_string();
            out.insert_str(rest.end, &format!(" -> {value}"));
            Some(out)
        }
        _ => None,
    }
}
