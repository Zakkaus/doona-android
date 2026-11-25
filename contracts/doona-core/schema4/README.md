# doona-core schema 4 fixtures (draft)

Examples for `plans/v1/honk/core-api-schema4.md`. Nothing reads this directory yet: the Rust contract test and the Kotlin JVM test list only the top-level `*.json` of `contracts/doona-core/`. Slice 11c moves these files up and makes both tests cover them; until then `check.py` is the gate. Credentials and URLs are invented.

`sources/*.dae` are the honk sources the snapshots stand for. Each loads and validates through honk-config at the pin `63de98ba`. A snapshot's `source.line` is the line of its group's declaration in that file, and `call-ok-rules.json` carries the byte spans of the statements in `no-proxy.dae`. Subscription nodes cannot come from a file, so the subscription feed `office` is a fixed list of three nodes in the snapshots.

| Sample | Source | Snapshot |
|---|---|---|
| No group called `Proxy`, Rule falls back to a group | `no-proxy.dae` | `snapshot-no-proxy.json` |
| All five kinds, with `default`, `final`, `exit`, `implicitAll` | `five-kinds.dae` | `snapshot-five-kinds.json` |
| Three-level nesting, an empty group, a literal `direct` member | `nesting.dae` | `snapshot-nesting.json` |
| Proxy, Auto, Streaming and AI as ordinary names; a name in emoji and CJK | `ordinary-names.dae` | `snapshot-ordinary-names.json` |
| Zero groups, `fallback: direct` | `zero-groups.dae` | `snapshot-zero-groups.json`, `snapshot-empty-profile.json` |
| A node in no group | `ungrouped-node.dae` | `snapshot-ungrouped-node.json` |
| Global target is a node, a group, or missing | `global-target.dae` | `snapshot-global-{node,group,no}-target.json` |
| LoadBalance, unpinned and pinned | `loadbalance.dae` | `snapshot-loadbalance.json` |
| Automatic groups before the first measurement | `five-kinds.dae` | `snapshot-warming.json` |
| Two groups with one name | `duplicate-group.dae` | `snapshot-duplicate-group.json`, `call-ok-config-check-duplicate-group.json` |

Other files: `init.json`; `catalog/` (one node of each source, the two built-in members, the four `blockAds` states, the three fallback targets); `call-*-args.json` and `call-ok-*.json` / `call-error-*.json` for `setGlobalTarget`, `setMode`, `select`, `rules`, `configEdit` and `usage`.

Member order in a group is an example: leaf nodes first, then subgroups, as the manager lists them. Lane B's inventory decides the real order of nodes.

Run `python3 check.py`. It checks unique IDs, that every member, default, final, exit, Global target and fallback resolves, acyclic nesting of at most eight levels, the LoadBalance and pin rules, that each group's line is its declaration, that rule byte spans cut the statement they name, that no removed field appears, and that all five kinds are covered. `check.py DIR` checks a copy.
