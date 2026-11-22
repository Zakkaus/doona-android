//! `select` and `testLatency`.

use super::{Core, lock};
use crate::dto::{ErrorCode, SelectArgs};

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
            if c.state.selections.get(&args.group) == args.member_id.as_ref() {
                return Ok(());
            }
            c.state.clone()
        };
        match args.member_id {
            Some(member) => next.selections.insert(args.group, member),
            None => next.selections.remove(&args.group),
        };
        self.commit_state(next, "select", None)
    }

    /// Starts probing the nodes of `group`, or of every group, and returns at once; the groups
    /// show `testing` until the test ends.
    pub(super) fn test_latency(&self, group: Option<String>) -> Result<(), ErrorCode> {
        let (plan, cancel) = {
            let mut c = lock(&self.shared.catalog);
            let plan = c.model.plan(group.as_deref())?;
            c.revision += 1;
            (plan, c.probes.clone())
        };
        log::info!("testLatency: {} probes", plan.targets.len());
        self.probes
            .spawn(self.runtime.handle(), &self.shared, plan, cancel);
        Ok(())
    }
}
