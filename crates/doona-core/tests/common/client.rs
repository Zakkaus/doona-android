//! The apps behind the VPN: an endpoint-mode honk Stack at the tun's address, on the far end of
//! the socketpair the core uses as its tun, driven from the test thread.

use honk_netstack::{Config, FlowId, Rx, Stack, StreamError, TcpState};
use std::io::ErrorKind;
use std::net::{Ipv4Addr, SocketAddr};
use std::os::fd::{AsRawFd, OwnedFd};
use std::time::{Duration, Instant};

pub const TUN_ADDR: Ipv4Addr = Ipv4Addr::new(172, 19, 0, 1);
pub const MTU: u16 = 8500;

pub struct Client {
    st: Stack,
    peer: OwnedFd,
    buf: Vec<u8>,
    pkt: Vec<u8>,
    udp: Option<FlowId>,
    /// Datagrams received: (source, payload).
    pub datagrams: Vec<(SocketAddr, Vec<u8>)>,
    /// Bytes of the packets sent to the core's tun, and received from it.
    pub sent: u64,
    pub received: u64,
}

fn io_call(r: isize) -> std::io::Result<usize> {
    if r < 0 {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(r as usize)
    }
}

impl Client {
    pub fn new(peer: OwnedFd) -> Client {
        let mut cfg = Config::new([7; 32]);
        cfg.local_v4 = Some(TUN_ADDR);
        cfg.mtu = MTU;
        Client {
            st: Stack::new(cfg, Instant::now()),
            peer,
            buf: vec![0; 65536],
            pkt: vec![0; usize::from(MTU) + 64],
            udp: None,
            datagrams: Vec::new(),
            sent: 0,
            received: 0,
        }
    }

    fn send_packet(&mut self, len: usize) {
        let fd = self.peer.as_raw_fd();
        loop {
            // SAFETY: `pkt` is valid for `len` bytes.
            let r = unsafe { libc::send(fd, self.pkt.as_ptr().cast(), len, libc::MSG_DONTWAIT) };
            match io_call(r) {
                Ok(n) => {
                    self.sent += n as u64;
                    return;
                }
                // The core stops reading while its own write to us is blocked, so take its
                // packets before waiting, or both sides wait for each other.
                Err(e) if e.kind() == ErrorKind::WouldBlock => {
                    if !self.receive(Instant::now()) {
                        std::thread::sleep(Duration::from_micros(100));
                    }
                }
                Err(e) => panic!("peer send: {e}"),
            }
        }
    }

    /// Feeds every packet waiting on the socketpair to the stack; true if there was one.
    fn receive(&mut self, now: Instant) -> bool {
        let fd = self.peer.as_raw_fd();
        let mut any = false;
        loop {
            // SAFETY: `buf` is valid for its length.
            let r = unsafe {
                libc::recv(
                    fd,
                    self.buf.as_mut_ptr().cast(),
                    self.buf.len(),
                    libc::MSG_DONTWAIT,
                )
            };
            match io_call(r) {
                Ok(0) => return any,
                Ok(n) => {
                    any = true;
                    self.received += n as u64;
                    if let Rx::Datagram { src, payload, .. } = self.st.receive(now, &self.buf[..n])
                    {
                        self.datagrams.push((src, self.buf[payload].to_vec()));
                    }
                }
                Err(e) if e.kind() == ErrorKind::WouldBlock => return any,
                Err(e) => panic!("peer recv: {e}"),
            }
        }
    }

    /// Moves packets both ways once, waiting up to a millisecond for input.
    pub fn turn(&mut self) {
        let fd = self.peer.as_raw_fd();
        let now = Instant::now();
        self.receive(now);
        if self.st.poll_timeout().is_some_and(|d| d <= now) {
            self.st.handle_timeout(now);
        }
        while self.st.poll_event().is_some() {}
        while let Some(n) = self.st.poll_transmit(now, &mut self.pkt) {
            self.send_packet(n);
        }
        let mut pfd = libc::pollfd {
            fd,
            events: libc::POLLIN,
            revents: 0,
        };
        // SAFETY: `pfd` is valid for the call.
        unsafe { libc::poll(&mut pfd, 1, 1) };
    }

    /// Turns until `cond` holds or `limit` passes.
    pub fn until(&mut self, limit: Duration, mut cond: impl FnMut(&mut Client) -> bool) -> bool {
        let end = Instant::now() + limit;
        while Instant::now() < end {
            self.turn();
            if cond(self) {
                return true;
            }
        }
        false
    }

    pub fn connect(&mut self, dst: SocketAddr) -> FlowId {
        let id = self.st.tcp_connect(Instant::now(), dst).unwrap();
        self.turn();
        id
    }

    /// Writes all of `data`, then FIN; reads until EOF. Returns what was read, or the error
    /// that ended the flow.
    pub fn exchange(&mut self, id: FlowId, data: &[u8]) -> Result<Vec<u8>, StreamError> {
        let (mut sent, mut shut, mut got) = (0, false, Vec::new());
        let end = Instant::now() + Duration::from_secs(20);
        loop {
            assert!(
                Instant::now() < end,
                "exchange timed out: sent {sent} got {}",
                got.len()
            );
            while sent < data.len() {
                match self.st.tcp_write(id, &data[sent..]) {
                    Ok(n) => sent += n,
                    Err(StreamError::WouldBlock) => break,
                    Err(e) => return Err(e),
                }
            }
            if sent == data.len() && !shut {
                self.st.tcp_shutdown(id)?;
                shut = true;
            }
            loop {
                match self.st.tcp_read(id, &mut self.buf) {
                    Ok(0) => return Ok(got),
                    Ok(n) => got.extend_from_slice(&self.buf[..n]),
                    Err(StreamError::WouldBlock) => break,
                    Err(e) => return Err(e),
                }
            }
            self.turn();
        }
    }

    /// Reads until EOF without writing. Returns what was read, or the error that ended the flow.
    pub fn read_to_end(&mut self, id: FlowId) -> Result<Vec<u8>, StreamError> {
        let mut got = Vec::new();
        let end = Instant::now() + Duration::from_secs(20);
        loop {
            assert!(Instant::now() < end, "read timed out: got {}", got.len());
            loop {
                match self.st.tcp_read(id, &mut self.buf) {
                    Ok(0) => return Ok(got),
                    Ok(n) => got.extend_from_slice(&self.buf[..n]),
                    Err(StreamError::WouldBlock) => break,
                    Err(e) => return Err(e),
                }
            }
            self.turn();
        }
    }

    /// Reads whatever is buffered; Err once the flow failed or closed.
    pub fn read_now(&mut self, id: FlowId) -> Result<Vec<u8>, StreamError> {
        let mut got = Vec::new();
        loop {
            match self.st.tcp_read(id, &mut self.buf) {
                Ok(0) => return Err(StreamError::Closed),
                Ok(n) => got.extend_from_slice(&self.buf[..n]),
                Err(StreamError::WouldBlock) => return Ok(got),
                Err(e) => return Err(e),
            }
        }
    }

    pub fn write_now(&mut self, id: FlowId, data: &[u8]) -> usize {
        self.st.tcp_write(id, data).unwrap_or(0)
    }

    /// Sends FIN after what was written.
    pub fn shutdown(&mut self, id: FlowId) {
        self.st.tcp_shutdown(id).unwrap();
        self.turn();
    }

    /// The flow's state, without reading from it.
    pub fn state(&self, id: FlowId) -> Option<TcpState> {
        self.st.tcp_state(id)
    }

    pub fn send_udp(&mut self, dst: SocketAddr, payload: &[u8]) {
        let flow = *self
            .udp
            .get_or_insert_with(|| self.st.udp_bind(None).unwrap());
        let n = self.st.udp_send(flow, dst, payload, &mut self.pkt).unwrap();
        self.send_packet(n);
    }

    /// Waits for a datagram from `from` and takes it.
    pub fn recv_udp(&mut self, from: SocketAddr, limit: Duration) -> Option<Vec<u8>> {
        let found = self.until(limit, |c| c.datagrams.iter().any(|(s, _)| *s == from));
        if !found {
            return None;
        }
        let at = self.datagrams.iter().position(|(s, _)| *s == from)?;
        Some(self.datagrams.remove(at).1)
    }
}
