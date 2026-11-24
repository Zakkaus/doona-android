//! The outer lists and their country messages are larger than a decode buffer. Index only
//! their labels and file offsets. A selected record (one Domain or CIDR) fits the scratch buffer.
use std::{
    collections::{HashMap, HashSet},
    fs::File,
    io::{self, BufReader, Read},
    ops::Range,
    path::Path,
};

pub(super) fn bad() -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, "invalid geo DAT")
}

type Span = Range<u64>;
fn varint(reader: &mut impl Read) -> io::Result<u64> {
    let mut n = 0;
    for shift in (0..64).step_by(7) {
        let mut b = [0];
        reader.read_exact(&mut b).map_err(|_| bad())?;
        if shift == 63 && b[0] > 1 {
            return Err(bad());
        }
        n |= u64::from(b[0] & 127) << shift;
        if b[0] < 128 {
            return Ok(n);
        }
    }
    Err(bad())
}

/// Tag, scalar value (varint fields only), and number of payload bytes after the header.
fn header(reader: &mut impl Read) -> io::Result<(u64, u64, u64)> {
    let tag = varint(reader)?;
    if tag >> 3 == 0 {
        return Err(bad());
    }
    let (scalar, len) = match tag & 7 {
        0 => (varint(reader)?, 0),
        1 => (0, 8),
        2 => (0, varint(reader)?),
        5 => (0, 4),
        _ => return Err(bad()),
    };
    Ok((tag, scalar, len))
}

pub(super) fn next<'a>(bytes: &mut &'a [u8]) -> io::Result<(u64, u64, &'a [u8])> {
    let (tag, n, len) = header(bytes)?;
    let (field, rest) = bytes
        .split_at_checked(usize::try_from(len).map_err(|_| bad())?)
        .ok_or_else(bad)?;
    *bytes = rest;
    Ok((tag, n, field))
}
struct FileWire {
    reader: BufReader<File>,
    pos: u64,
}
impl FileWire {
    fn seek(&mut self, pos: u64) -> io::Result<()> {
        self.reader.seek_relative(pos as i64 - self.pos as i64)?;
        self.pos = pos;
        Ok(())
    }
    fn field(&mut self, end: u64) -> io::Result<(u64, Span)> {
        let mut bounded = (&mut self.reader).take(end - self.pos);
        let (tag, _, len) = header(&mut bounded)?;
        self.pos = end - bounded.limit();
        if len > end - self.pos {
            return Err(bad());
        }
        Ok((tag, self.pos..self.pos + len))
    }
    fn bytes(&mut self, span: Span, buf: &mut Vec<u8>, max: usize) -> io::Result<()> {
        let len = (span.end - span.start) as usize;
        if len > max {
            return Err(bad());
        }
        buf.resize(len, 0);
        self.reader.read_exact(buf)?;
        self.pos = span.end;
        Ok(())
    }
    fn label(&mut self, end: u64) -> io::Result<String> {
        while self.pos < end {
            let (tag, span) = self.field(end)?;
            if tag == 10 {
                let mut buf = Vec::new();
                self.bytes(span, &mut buf, 256)?;
                if buf.is_empty() {
                    return Err(bad());
                }
                buf.make_ascii_lowercase();
                return String::from_utf8(buf).map_err(|_| bad());
            }
            self.seek(span.end)?;
        }
        Err(bad())
    }

    fn records(
        &mut self,
        span: &Span,
        buf: &mut Vec<u8>,
        mut record: impl FnMut(&[u8]) -> io::Result<()>,
    ) -> io::Result<()> {
        self.seek(span.start)?;
        while self.pos < span.end {
            let (tag, field) = self.field(span.end)?;
            if tag == 18 {
                self.bytes(field, buf, 64 << 10)?;
                record(buf)?;
            } else {
                self.seek(field.end)?;
            }
        }
        Ok(())
    }
}

pub(super) struct List {
    wire: FileWire,
    entries: HashMap<String, Span>,
    scratch: Vec<u8>,
}
impl List {
    fn scan(
        path: &Path,
        mut entry: impl FnMut(&mut FileWire, String, &Span) -> io::Result<()>,
    ) -> io::Result<FileWire> {
        let file = File::open(path)?;
        let end = file.metadata()?.len();
        let mut wire = FileWire {
            reader: BufReader::new(file),
            pos: 0,
        };
        let mut saw_entry = false;
        while wire.pos < end {
            let (tag, span) = wire.field(end)?;
            if tag == 10 {
                let code = wire.label(span.end)?;
                saw_entry = true;
                entry(&mut wire, code, &span)?;
            }
            wire.seek(span.end)?;
        }
        if !saw_entry {
            return Err(bad());
        }
        Ok(wire)
    }
    pub fn verify(path: &Path, mut record: impl FnMut(&[u8]) -> io::Result<()>) -> io::Result<()> {
        let mut buf = Vec::new();
        Self::scan(path, |wire, _, span| {
            wire.records(span, &mut buf, &mut record)
        })
        .map(drop)
    }
    pub fn open(path: &Path, wanted: &HashSet<&str>) -> io::Result<Self> {
        let mut entries = HashMap::new();
        let wire = Self::scan(path, |_, code, span| {
            if wanted.contains(code.as_str()) && entries.insert(code, span.clone()).is_some() {
                return Err(bad());
            }
            Ok(())
        })?;
        Ok(Self {
            wire,
            entries,
            scratch: Vec::new(),
        })
    }
    pub fn records(
        &mut self,
        code: &str,
        record: impl FnMut(&[u8]) -> io::Result<()>,
    ) -> io::Result<bool> {
        let Some(span) = self.entries.get(code) else {
            return Ok(false);
        };
        self.wire.records(span, &mut self.scratch, record)?;
        Ok(true)
    }
}
