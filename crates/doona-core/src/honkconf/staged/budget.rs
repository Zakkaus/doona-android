use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::io::Read;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use honk_config::diagnostic::DiagnosticSources;

use super::{Files, HonkConfigError, LIMITS, invalid, io_error};

enum Input<'a> {
    File(File, usize),
    Edit(&'a str),
}

/// Reserve known sizes for the whole batch before decoding its first source.
/// A metadata length of zero need not mean EOF; charge actual bytes on read.
pub(super) struct Batch<'a> {
    inputs: HashMap<PathBuf, Input<'a>>,
    slots: usize,
    remaining: usize,
}

impl<'a> Batch<'a> {
    pub(super) fn after(files: &Files) -> Self {
        Self {
            inputs: HashMap::new(),
            slots: LIMITS.max_sources - files.len(),
            remaining: LIMITS.max_bytes - files.values().map(|s| s.len()).sum::<usize>(),
        }
    }

    pub(super) fn contains(&self, path: &Path) -> bool {
        self.inputs.contains_key(path)
    }

    pub(super) fn file(&mut self, path: PathBuf, regular: bool) -> Result<(), HonkConfigError> {
        if self.inputs.len() == self.slots {
            return Err(HonkConfigError::SourceLimit);
        }
        let source = || DiagnosticSources::new(Some(path.clone())).root();
        let not_regular = || {
            invalid(
                &source(),
                "invalid-config-source",
                "configuration source must be a regular file",
            )
        };
        if !regular {
            return Err(not_regular());
        }
        // O_NOFOLLOW closes the window between the scan and this open: a file swapped for a
        // symlink fails with ELOOP instead of being read.
        let file = OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&path)
            .map_err(|e| match e.raw_os_error() {
                Some(libc::ELOOP) => not_regular(),
                _ => io_error(e, &source()),
            })?;
        let len = file.metadata().map_err(|e| io_error(e, &source()))?.len();
        if len > self.remaining as u64 {
            return Err(HonkConfigError::SourceLimit);
        }
        self.remaining -= len as usize;
        self.inputs.insert(path, Input::File(file, len as usize));
        Ok(())
    }

    pub(super) fn edit(&mut self, path: PathBuf, text: &'a str) -> Result<(), HonkConfigError> {
        if self.inputs.len() == self.slots || text.len() > self.remaining {
            return Err(HonkConfigError::SourceLimit);
        }
        self.remaining -= text.len();
        self.inputs.insert(path, Input::Edit(text));
        Ok(())
    }

    pub(super) fn finish(mut self, output: &mut Files) -> Result<(), HonkConfigError> {
        let mut inputs: Vec<_> = std::mem::take(&mut self.inputs).into_iter().collect();
        inputs.sort_by(|a, b| a.0.cmp(&b.0));
        for (path, input) in inputs {
            let text = match input {
                Input::Edit(text) => Arc::from(text),
                Input::File(file, hint) => {
                    self.remaining += hint;
                    read_text(file, hint, &mut self.remaining, &path)?
                }
            };
            output.insert(path, text);
        }
        Ok(())
    }
}

fn read_text(
    mut reader: impl Read,
    hint: usize,
    remaining: &mut usize,
    path: &Path,
) -> Result<Arc<str>, HonkConfigError> {
    let source = || DiagnosticSources::new(Some(path.into())).root();
    let mut bytes = Vec::with_capacity(hint);
    reader
        .by_ref()
        .take(hint as u64)
        .read_to_end(&mut bytes)
        .map_err(|e| io_error(e, &source()))?;
    // A regular file normally ends at its hint. Probe for growth without growing the buffer.
    // Invariant: bytes.len() plus the probe never exceeds the remaining budget, so peak
    // allocation stays within it.
    let mut chunk = [0; 8192];
    loop {
        let available = *remaining - bytes.len();
        let probe = chunk.len().min(available + 1);
        let n = match reader.read(&mut chunk[..probe]) {
            Ok(0) => break,
            Ok(n) if n <= available => n,
            Ok(_) => return Err(HonkConfigError::SourceLimit),
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(io_error(e, &source())),
        };
        if bytes.len() + n > bytes.capacity() {
            let capacity = (bytes.capacity() * 2).max(bytes.len() + n).min(*remaining);
            bytes.reserve_exact(capacity - bytes.len());
        }
        bytes.extend_from_slice(&chunk[..n]);
    }
    *remaining -= bytes.len();
    String::from_utf8(bytes).map(Arc::from).map_err(|_| {
        invalid(
            &source(),
            "invalid-config-encoding",
            "configuration source must be UTF-8",
        )
    })
}

#[test]
fn unknown_length_stream_refuses_the_extra_byte_within_the_budget() {
    let path = Path::new("input.dae");
    let mut remaining = LIMITS.max_bytes;
    let (result, peak, _) = crate::testutil::alloc::peak(|| {
        let stream = std::io::repeat(b'x').take(remaining as u64 + 1);
        read_text(stream, 0, &mut remaining, path)
    });
    assert!(matches!(result, Err(HonkConfigError::SourceLimit)));
    assert!(
        peak <= LIMITS.max_bytes,
        "buffered {peak} bytes before refusal"
    );
}

#[test]
fn a_symlink_is_refused_even_when_it_names_a_regular_file() {
    let dir = crate::testutil::TempDir::new("budget-symlink");
    let target = dir.path().join("target.dae");
    std::fs::write(&target, "global {}").unwrap();
    let link = dir.path().join("link.dae");
    std::os::unix::fs::symlink(&target, &link).unwrap();
    let mut batch = Batch::after(&Files::new());
    let err = batch.file(link, true).unwrap_err();
    assert!(matches!(err, HonkConfigError::ParseFailed { ref cause, .. }
        if cause.diagnostic.code == "invalid-config-source"));
    assert!(batch.file(target, true).is_ok());
}
