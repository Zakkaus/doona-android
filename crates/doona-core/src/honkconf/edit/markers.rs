use std::ops::Range;

use honk_config::parser::cursor::Document;
use honk_config::parser::{SourceSnapshot, lexer::Source};

use super::{List, RuleRow};

const PREFIX: &str = "# paused ";

pub(super) fn marker(id: &str) -> String {
    format!("{PREFIX}{id}: ")
}

pub(super) struct Run {
    pub id: String,
    pub bytes: Range<usize>,
    pub list: Option<List>,
}

impl Run {
    pub(super) fn row(self) -> Option<RuleRow> {
        Some(RuleRow {
            id: self.id,
            list: self.list?,
            fallback: false,
            paused: true,
            bytes: self.bytes,
        })
    }
}

pub(super) fn runs(source: &SourceSnapshot) -> Option<Vec<Run>> {
    let text = &source.content;
    if !text.contains(PREFIX) {
        return Some(Vec::new());
    }
    let mut runs: Vec<Run> = Vec::new();
    let mut start = 0;
    for line in text.split_inclusive('\n') {
        let end = start + line.len();
        if let Some((id, _)) = line.strip_prefix(PREFIX).and_then(|s| s.split_once(": "))
            && id
                .strip_prefix("r-")
                .is_some_and(|hex| hex.len() == 16 && hex.bytes().all(|b| b.is_ascii_hexdigit()))
        {
            if let Some(last) = runs
                .last_mut()
                .filter(|r| r.id == id && r.bytes.end == start)
            {
                last.bytes.end = end;
            } else {
                runs.push(Run {
                    id: id.into(),
                    bytes: start..end,
                    list: None,
                });
            }
        }
        start = end;
    }
    if !runs.is_empty() {
        let doc =
            Document::parse(Source::new(text, source.source.clone()), &mut Vec::new()).ok()?;
        for list in [List::Traffic, List::DnsRequest, List::DnsResponse] {
            for block in list.blocks(&doc) {
                let first = runs.partition_point(|r| r.bytes.start <= block.header_span().end);
                for run in runs[first..]
                    .iter_mut()
                    .take_while(|r| r.bytes.end <= block.span().end)
                {
                    run.list = Some(list);
                }
            }
        }
    }
    Some(runs)
}
