# doona-core schema 4 fixtures (draft)

Examples for `plans/v1/honk/core-api-schema4.md`. Nothing reads this directory yet: the Rust contract test and the Kotlin JVM test list only the top-level `*.json` of `contracts/doona-core/`. Slice 11c moves these files up and makes both tests cover them; until then `check.py` is the gate. Credentials and URLs are invented.

`sources/*.dae` are the honk sources the snapshots stand for. Each loads and validates through honk-config at the pin `63de98ba`. A snapshot's `source.line` is the line of its group's declaration in that file, and `call-ok-rules.json` carries the byte spans of the statements in `no-proxy.dae`. Subscription nodes cannot come from a file, so each snapshot lists the nodes of its feed `office` itself; `subscriptions[].nodes` is the count, and `check.py` compares it with the nodes the catalog attributes to the feed.

| Sample | Source | Snapshot |
|---|---|---|
| No group called `Proxy`, Rule falls back to a group | `no-proxy.dae` | `snapshot-no-proxy.json` |
| All five kinds, with `default`, `final`, `exit`, `implicitAll` | `five-kinds.dae` | `snapshot-five-kinds.json` |
| Three-level nesting, an empty group, a literal `direct` member | `nesting.dae` | `snapshot-nesting.json` |
| Proxy, Auto, Streaming and AI as ordinary names; a name in emoji and CJK | `ordinary-names.dae` | `snapshot-ordinary-names.json` |
| Zero groups, `fallback: direct` | `zero-groups.dae` | `snapshot-zero-groups.json` |
| No node and no group | `empty-profile.dae` | `snapshot-empty-profile.json` |
| `default:` and `final:` name nothing | `stale-default.dae` | `snapshot-stale-default.json` |
| A node in no group | `ungrouped-node.dae` | `snapshot-ungrouped-node.json` |
| Global target is a node, a group, or missing | `global-target.dae` | `snapshot-global-{node,group,no}-target.json` |
| LoadBalance, unpinned and pinned | `loadbalance.dae` | `snapshot-loadbalance.json` |
| DNS request and response rules, one paused | `dns-rules.dae` | `call-ok-rules-dns-request.json`, `call-ok-rules-dns-response.json` |
| Automatic groups before the first measurement | `warming.dae` | `snapshot-warming.json` |
| Two groups with one name | `duplicate-group.dae` | `snapshot-duplicate-group.json`, `call-ok-config-check-duplicate-group.json` |

Other files: `init.json`; `catalog/` (one node of each source, the two built-in members, the four `blockAds` states, the three fallback targets); `call-*-args.json` and `call-ok-*.json` / `call-error-<code>.json` for `setGlobalTarget`, `setMode`, `select`, `rules` (traffic, DNS request, DNS response), `configEdit` and `usage`.

Member order in a group is an example: leaf nodes first, then subgroups, as the manager lists them. Lane B's inventory decides the real order of nodes.

Run `python3 check.py`. It checks:

- IDs are unique, and every member, default, final, exit, Global target and fallback resolves.
- Nesting is acyclic and at most eight groups in a chain.
- The LoadBalance, pin and Selector rules hold, and a member's latency equals its node-table entry.
- Each group's line is the last declaration of its name in the source, and its `kind` matches the `policy:` line.
- `summary.groups` equals the source's group declarations, and `summary.subscriptions` and `subscriptions[].nodes` equal what the catalog lists.
- Each rule row's line, byte span, `paused`, `fallback` and action equal the statement of the same index in its source block, for the traffic, DNS request and DNS response lists.
- The `blockAds` states give `dnsRequestRules` 1 for `on` and `elsewhere`, else 0.
- Hourly usage arrays have 24 elements and sum to the outbound and rule totals.
- Each `call-error-<code>.json` carries its own code, and the four new codes exist.
- No removed name (`mainGroupId`, `ruleSource`, `unmatched`, `rulesRevision`, `dnsVia`, `dnsUpstreams`, `RuleOptions`, `setDnsUpstreams`) appears in any file, and all five kinds are covered.
- Every snapshot has a mapped source.

`check.py DIR` checks a copy.
