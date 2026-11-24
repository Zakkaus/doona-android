//! `select` and `testLatency`.

use super::{Core, lock};
use crate::dto::{ErrorCode, Network, SelectArgs};

impl Core {
    /// Pins `memberId` in a group, or with `null` hands the group back to its own choice (an
    /// automatic group's fastest member, a Selector's first). Committed to `state.json`.
    pub(super) fn select(&self, args: SelectArgs) -> Result<(), ErrorCode> {
        let _writer = lock(&self.writer);
        let mut next = {
            let c = lock(&self.shared.catalog);
            let group = c.model.group(&args.group).ok_or(ErrorCode::NotFound)?;
            if let Some(member) = &args.member_id
                && !group.members.contains(member)
            {
                return Err(ErrorCode::NotFound);
            }
            let unchanged = match args.network {
                Some(net) => c.state.pin(&args.group, net) == args.member_id.as_ref(),
                None => {
                    c.state.selections.get(&args.group) == args.member_id.as_ref()
                        && !c.state.tcp_selections.contains_key(&args.group)
                        && !c.state.udp_selections.contains_key(&args.group)
                }
            };
            if unchanged {
                return Ok(());
            }
            c.state.clone()
        };
        let pins = if let Some(net) = args.network {
            let (pins, other) = match net {
                Network::Tcp => (&mut next.tcp_selections, &mut next.udp_selections),
                Network::Udp => (&mut next.udp_selections, &mut next.tcp_selections),
            };
            // Split a shared pin so clearing one network never resurrects it.
            if let Some(shared) = next.selections.remove(&args.group) {
                other.entry(args.group.clone()).or_insert(shared);
            }
            pins
        } else {
            next.tcp_selections.remove(&args.group);
            next.udp_selections.remove(&args.group);
            &mut next.selections
        };
        match args.member_id {
            Some(member) => pins.insert(args.group, member),
            None => pins.remove(&args.group),
        };
        self.commit_state(next, "select", None)
    }

    /// Starts probing the nodes of `group`, or of every group, and returns at once with how many
    /// probes it started; the groups show `testing` until the test ends.
    pub(super) fn test_latency(&self, group: Option<String>) -> Result<usize, ErrorCode> {
        // Any registry to build again is built here, outside the lifecycle lock.
        self.shared.outbounds.prepare()?;
        #[cfg(test)]
        self.hooks.prepared(self);
        let (plan, cancel, published) = {
            // A stop or start ends the probes and the carriers under the lifecycle lock, so the
            // token, the plan and the registry taken under it go together. Carriers ended since
            // the `prepare` above are replaced first.
            let _life = lock(&self.life);
            self.shared.outbounds.prepare()?;
            #[cfg(test)]
            self.hooks.registering(self);
            let mut c = lock(&self.shared.catalog);
            // A refresh publishes its nodes and their registry under the catalog lock, so the
            // registry taken here holds every node of the plan.
            let published = self.shared.outbounds.published();
            let plan = c.model.plan(group.as_deref())?;
            c.revision += 1;
            (plan, c.probes.clone(), published)
        };
        let probes = plan.targets.len();
        let rt = self.runtime.handle();
        self.probes.spawn(rt, &self.shared, plan, cancel, published);
        Ok(probes)
    }
}
