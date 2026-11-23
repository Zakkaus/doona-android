//! The groups of a source as a graph (rule-mode.md, decision 9): ids are unique, every member is
//! a group of the source or a node the caller knows, no group reaches itself, and no chain of
//! groups nests deeper than [`MAX_DEPTH`]. Any of these refuses the whole source, as Mihomo
//! refuses a profile whose group names a proxy it does not hold
//! (`adapter/outboundgroup/parser.go:156,223-228`, `config/config.go:956-958`, Mihomo `100ff73a`).

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

/// Each group's position by id, once the graph is sound. `is_node` tells whether a member that
/// is not a group is a node the caller holds.
pub(super) fn check<'a>(
    groups: &'a [GroupDef],
    is_node: &dyn Fn(&str) -> bool,
) -> Result<HashMap<&'a str, usize>, ErrorCode> {
    let mut index = HashMap::with_capacity(groups.len());
    for (i, g) in groups.iter().enumerate() {
        if index.insert(g.id.as_str(), i).is_some() {
            return Err(ErrorCode::ParseFailed);
        }
    }
    let known = |m: &String| {
        if is_group(m) {
            index.contains_key(m.as_str())
        } else {
            is_node(m)
        }
    };
    if !groups.iter().flat_map(|g| &g.members).all(known) {
        return Err(ErrorCode::ParseFailed);
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
