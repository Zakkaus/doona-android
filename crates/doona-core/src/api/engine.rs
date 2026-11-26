//! What a start checks before it stops anything, and honk's engine it routes by: the committed
//! configuration, built against the active asset generation, with its GLOBAL target.

use super::{Core, MIN_MTU};
use crate::bootstrap;
use crate::diag::Event;
use crate::dto::Network;
use crate::dto::{ErrorCode, Mode, StartConfig};
use crate::honkconf::runtime::{GlobalTarget, Runtime, RuntimeError, Target};
use crate::honkconf::{Capabilities, HonkStore, StoreError};
use crate::tunnel::route;
use std::io::ErrorKind;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;

/// honk's engine and the data root it read; `None` for a Direct start with no committed
/// configuration.
pub(super) type Built = Option<(Arc<Runtime>, PathBuf)>;

impl Core {
    /// The bootstrap resolvers and honk's engine of a start with `config`, once it is one the
    /// core can start. A malformed address or a nonempty list with no surviving scope is
    /// `invalid_argument`. Global with no GLOBAL target, or one the catalog has no node for, is
    /// `no_nodes`, as it would refuse every flow; Rule may still send flows direct and refuse
    /// those bound for a group with no node. Direct starts without a committed configuration.
    pub(super) fn checked(
        &self,
        config: &StartConfig,
    ) -> Result<(Vec<SocketAddr>, Built), ErrorCode> {
        if config.mtu < MIN_MTU {
            return Err(ErrorCode::InvalidArgument);
        }
        let (port, fallback) = (
            self.options.bootstrap_port,
            &self.options.bootstrap_fallback,
        );
        let resolvers = bootstrap::servers(&config.bootstrap_dns, port, Some(fallback))
            .ok_or(ErrorCode::InvalidArgument)?;
        let mode = self.mode.get();
        let engine = self.engine(mode)?;
        let global = engine.as_ref().map(|(engine, _)| engine.as_ref());
        if mode == Mode::Global && !self.global_dials(global) {
            return Err(ErrorCode::NoNodes);
        }
        Ok((resolvers, engine))
    }

    /// Whether Global under `engine` has a GLOBAL target the catalog holds a node for.
    pub(super) fn global_dials(&self, engine: Option<&Runtime>) -> bool {
        let target = match engine.and_then(Runtime::global_target) {
            Some(GlobalTarget::Group(name)) => Target::Group(name),
            Some(GlobalTarget::Node(id)) => Target::Node(id),
            None => return false,
        };
        let view = self.shared.outbounds.view();
        let any = SocketAddr::from(([0, 0, 0, 0], 0));
        route::dial(&view, &target, (any, Network::Tcp), None, None).is_some()
    }

    /// honk's engine for the committed configuration and the data root it read: the active asset
    /// generation, with [`default_global_target`]'s GLOBAL target; without one Global blocks what
    /// is not `must`. With no committed configuration there is nothing to route by: `None` in
    /// Direct, `no_nodes` in Rule and Global.
    pub(super) fn engine(&self, mode: Mode) -> Result<Built, ErrorCode> {
        let failed = |e: &RuntimeError| {
            let code = engine_code(e);
            let event = Event::warn("engine").code(code).detail(engine_detail(e));
            self.diag.record(match e {
                RuntimeError::Store(StoreError::Io(io)) | RuntimeError::Asset(io) => {
                    event.io(io.kind())
                }
                _ => event,
            });
            code
        };
        let store =
            HonkStore::open(self.store.dir()).map_err(|e| failed(&RuntimeError::Store(e)))?;
        let data = self.assets.data_root();
        let engine = match Runtime::load(&store, &Capabilities::linked(), &data) {
            Ok(engine) => engine,
            Err(RuntimeError::NoRevision) if mode == Mode::Direct => return Ok(None),
            Err(e) => return Err(failed(&e)),
        };
        default_global_target(&engine)?;
        Ok(Some((Arc::new(engine), data)))
    }
}

/// Sets the GLOBAL target a start begins with: honk's `routing.fallback` group, none for a
/// fallback to `direct` or `block`. The one place to replace once `setGlobalTarget` stores a
/// target (core-api-schema4 §6) and honk 03b's dial plan chooses the leaf.
fn default_global_target(engine: &Runtime) -> Result<(), ErrorCode> {
    match engine.fallback_group() {
        Some(group) => engine
            .set_global_target(GlobalTarget::Group(group))
            .map_err(|_| ErrorCode::NotFound),
        None => Ok(()),
    }
}

/// The code a start that could not build the engine fails with. A configuration honk refuses
/// or that no longer loads is `parse_failed`, a geo asset it needs and the active generation
/// lacks `not_found`.
fn engine_code(e: &RuntimeError) -> ErrorCode {
    match e {
        RuntimeError::NoRevision => ErrorCode::NoNodes,
        RuntimeError::Store(StoreError::Config(_) | StoreError::Incomplete) => {
            ErrorCode::ParseFailed
        }
        RuntimeError::Asset(io) if io.kind() == ErrorKind::NotFound => ErrorCode::NotFound,
        RuntimeError::Asset(_) | RuntimeError::Config(_) => ErrorCode::ParseFailed,
        RuntimeError::Store(_) | RuntimeError::Layout(_) => ErrorCode::Internal,
    }
}

/// What the diagnostics log names a failed build by.
fn engine_detail(e: &RuntimeError) -> &'static str {
    match e {
        RuntimeError::NoRevision => "no_revision",
        RuntimeError::Store(_) => "store",
        RuntimeError::Asset(_) => "asset",
        RuntimeError::Config(_) => "config",
        RuntimeError::Layout(_) => "layout",
    }
}

#[cfg(test)]
mod tests;
