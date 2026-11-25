//! Pull-based exchange for a caller that verifies and writes each body slice before reading more.
use super::{exchange_head, read_line, request};
use std::io;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, BufReader};
use url::Url;

enum Frame {
    Length(usize),
    UntilClose,
    Chunk { left: usize, first: bool },
}
pub(crate) struct Body<S> {
    reader: BufReader<S>,
    frame: Frame,
}

pub(crate) async fn exchange<S: AsyncRead + AsyncWrite + Unpin>(
    stream: S,
    url: &Url,
) -> io::Result<Body<S>> {
    let (reader, head) = exchange_head(stream, &request(url, None, None))
        .await
        .map_err(|_| io::Error::other("geo HTTP head"))?;
    if !(200..300).contains(&head.status) {
        return Err(io::Error::other("geo HTTP status"));
    }
    let frame = if head.status == 204 {
        Frame::Length(0)
    } else if head.chunked {
        Frame::Chunk {
            left: 0,
            first: true,
        }
    } else {
        head.length.map_or(Frame::UntilClose, Frame::Length)
    };
    Ok(Body { reader, frame })
}
impl<S: AsyncRead + Unpin> Body<S> {
    pub(crate) async fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        let left = match &mut self.frame {
            Frame::UntilClose => return self.reader.read(buf).await,
            Frame::Length(0) => return Ok(0),
            Frame::Length(left) => left,
            Frame::Chunk { left, first } => {
                if *left == 0 {
                    if !*first {
                        let mut end = [0; 2];
                        self.reader.read_exact(&mut end).await?;
                        if end != *b"\r\n" {
                            return Err(io::Error::other("chunk delimiter"));
                        }
                    }
                    *first = false;
                    let line = read_line(&mut self.reader)
                        .await
                        .map_err(|_| io::Error::other("chunk header"))?;
                    *left = super::chunk_size(&line).map_err(|_| io::Error::other("chunk size"))?;
                    if *left == 0 {
                        self.frame = Frame::Length(0);
                        return Ok(0);
                    }
                }
                left
            }
        };
        let limit = (*left).min(buf.len());
        let n = self.reader.read(&mut buf[..limit]).await?;
        if n == 0 {
            return Err(io::ErrorKind::UnexpectedEof.into());
        }
        *left -= n;
        Ok(n)
    }
}
