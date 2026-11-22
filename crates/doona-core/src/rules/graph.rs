//! The groups of a source as a graph (rule-mode.md, decision 9): ids are unique, every member
//! that is a group exists, no group reaches itself, and no chain of groups nests deeper than
//! [`MAX_DEPTH`]. Any of these refuses the whole source, as Clash refuses such a profile.

use super::limits::MAX_DEPTH;
use crate::dto::ErrorCode;
use crate::groups::GroupDef;
use crate::ids::is_group;
use std::collections::HashMap;

#[derive(Clone, Copy)]
enum Mark {
    New,
    /// On the path being followed: reaching it again closes a cycle.
    Open,
    /// Followed: the longest chain of groups below it.
    Done(u8),
}

/// Each group's position by id, once the graph is sound.
pub(super) fn check(groups: &[GroupDef]) -> Result<HashMap<&str, usize>, ErrorCode> {
    let mut index = HashMap::with_capacity(groups.len());
    for (i, g) in groups.iter().enumerate() {
        if index.insert(g.id.as_str(), i).is_some() {
            return Err(ErrorCode::ParseFailed);
        }
    }
    let mut marks = vec![Mark::New; groups.len()];
    for i in 0..groups.len() {
        depth(groups, &index, &mut marks, i)?;
    }
    Ok(index)
}

/// The longest chain of groups below group `i`. Recursion is bounded by the groups' count.
fn depth(
    groups: &[GroupDef],
    index: &HashMap<&str, usize>,
    marks: &mut [Mark],
    i: usize,
) -> Result<u8, ErrorCode> {
    match marks[i] {
        Mark::Done(d) => return Ok(d),
        Mark::Open => return Err(ErrorCode::ParseFailed),
        Mark::New => marks[i] = Mark::Open,
    }
    let mut longest = 0;
    for member in groups[i].members.iter().filter(|m| is_group(m)) {
        let &j = index.get(member.as_str()).ok_or(ErrorCode::ParseFailed)?;
        longest = longest.max(depth(groups, index, marks, j)? + 1);
        if longest > MAX_DEPTH {
            return Err(ErrorCode::ParseFailed);
        }
    }
    marks[i] = Mark::Done(longest);
    Ok(longest)
}
