use super::{CancellationToken, Tunnel};

impl Tunnel {
    pub(crate) fn cancellation(&self) -> CancellationToken {
        self.gen_.cancel.clone()
    }
    /// Panics in a task of the generation, which ends its data path as a crash would.
    #[cfg(feature = "fault-hook")]
    pub fn fault(&self) {
        self.gen_.spawn(async { panic!("debugFault") });
    }
}
