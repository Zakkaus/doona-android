# doona-core contract fixtures

Example payloads for `plans/v1/core-api.md` (schema 3). `static-nodes.json` is an ordered editor session: execute the commands against a fresh core and compare each reply. Credentials in the examples are invented.

`init-options.json` is `init`'s second argument. `performanceMode` raises the worker threads of each of the core's runtimes from 2 to 4, for more throughput over several flows at the cost of CPU and power. A missing field is `false`; options that do not parse are the defaults, and so is a stored setting the app cannot read. Both runtimes are built once per process, so a change takes effect when the app process starts next.

`call-ok-config-assets.json` is the `configAssets` status before any DAT is installed. `call-config-assets-update-args.json` starts a check through the connected proxy. A verified download stays pending until the next tunnel start; it does not change the running policy.

`call-error-static-row-count.json` covers empty batches and batches over 200 rows. `call-ok-static-limits.json` shows two row errors in a full catalog: an oversized link and a new endpoint with no room left.

`call-set-bootstrap-dns-args.json` includes an IPv6 resolver scoped to `wlan0`. In any tunnel state the reply is `call-ok-empty.json` on success or `call-error-bootstrap-dns.json` for invalid arguments. A rejected request leaves the previous resolvers in use.

Android supplies DNS from the system-selected non-VPN network and keeps the last list when that network supplies none. A probe that cannot resolve its target or proxy server reports `dns_failed` in the member's `error` field.

`setDnsUpstreams` takes `{"servers":["9.9.9.9","[2001:db8::53]:5353"]}` and returns `{}`.
Unlike `setBootstrapDns`, it saves the endpoints for queries from apps. `effectiveConfig` reports
them in order, including ports. An invalid entry rejects the entire list.
Duplicate endpoints (including equivalent IP spellings and implicit port 53), scoped addresses,
link-local, unspecified, multicast and broadcast addresses are invalid. Explicitly selecting the
defaults saves that choice. An invalid list on disk makes `state.json` unreadable, so it is set
aside like any other unreadable file.
In Rule and Global modes, a loopback upstream refers to the proxy server's loopback, not the device's.

`renameSubscription` takes `{"id":"s1","name":"harbor"}` and returns the updated subscription.
Add and rename both reject blank names, controls, line separators and names over 64 Unicode code points after trimming.
If add receives no name, it uses the first 64 code points of the URL host and keeps the full URL.
`nodeShareLink` takes a node `id` and returns its original URI as the `ok` string, or `not_found`
when the ID is absent or its source is not `share_links`. The URI is private to this reply.
A legacy subscription with `format: null` returns `not_found` even if it has stored links. Refresh it to detect its format.

The two `hk-01` nodes are distinct endpoints with the same display name, and the second one's probe failed with `local_network`; the running snapshot carries the `local_network` warning, and the stopped one kept no yesterday (`yesterdayByNow` null); the subscription URL's token is a placeholder. The subscription was added with its own User-Agent, which the `addSubscription` reply carries; `setSubscriptionUserAgent` then set it back to null, the default, as the stopped catalog shows. The stats replies use one invented day, 2026-11-22: a five-second rate window, today and yesterday by the hour with each outbound's payload, the daily totals (2026-11-20 had no bytes, so it is absent), and two nodes' details with their latency tests, one of them failed.

`catalog/member-locations.json` shows the location fields for a group, feed nodes and a static node. The stopped snapshot above remains a schema 3 example from before these optional fields.

`select` without `network` sets or clears both pins and removes any separate choices for that group. Pass `"network":"tcp"` or `"network":"udp"` to change only that network. A null `memberId` restores the group's own choice on the affected networks. A node without UDP support does not gain a fallback.

Each catalog group reports `tcpSelectedId` and `udpSelectedId`, its effective direct member IDs (null when no member is selected). `networksDiffer` compares those IDs, not the leaves of nested groups. It can be true when Score chooses different members automatically. `tcpPinned` and `udpPinned` tell whether the user pinned each network. The legacy `selectedId` and `pinned` describe TCP.

`catalog/static-member.json` is the complete `g-Proxy` member from `snapshot(-1)` after adding `socks5://192.0.2.1:1080#example` to an empty core. Decode it as one member: `isGroup` is false and `subscriptionId` is explicitly null.

The rule mode payloads are what the core produces for its template `cn-direct` version 2: the catalog's `mainGroupId` and `ruleSource`, the five rows of `rules` with their real ids and `rulesRevision`, `setRuleSource` and `setMode` arguments, and `effectiveConfig` over one subscription with two nodes (`hk-01`, `jp-01`), whose text names each node only by its code, `n-` and the first 8 hex digits of its id. Today's `byRule` in the usage reply uses the real ids of the `GEOIP,CN` and `MATCH` rows, so its bytes add up to the `direct` and `g-Proxy` outbounds. Regenerate the ids from the core's tests when the template changes; never write them by hand.

The diagnostics payloads describe one invented session on the same day: `init` with one subscription of two nodes, a refresh, a latency test with one failed probe, generation 3 started, warned `local_network`, its subscription refresh failed, a panic ended it (`died`) and the stop left one task detached, then the mode went to `Rule`. The last snapshot reported `internal`, and the panic left one crash file. Every text in an event is a fixed word of the core, so the reply holds no URL, address, name or secret. `setLogLevel` and `diagnostics` take a level each, and `clearCrashes` the name of the crash file the export took with the reply's `crashMark`.

`scoreState` reads live Score evidence independently of the catalog revision. Each group lists its direct `nodeIds` and reports `tcp` and `udp` separately. `selected` is honk's ordinary aggregate member tag (IPv4 health, no target family), not a pin or a trial. An ambiguous tag has no `selectedId`; a challenger has a `nodeId` only for a unique direct node. `validForMs` is the remaining lifetime of the comparison. With no eligible candidate, selection is null and coverage is zero. Per-network counters and the deduplicated `rootBusinessStarts` are process-local, separate from daily usage. Reading does not start a trial or spend budget.
When a subgroup wins, `selectedId` is its group ID, but `nodeIds` lists only direct nodes.

`recentFlows` takes `{}` and returns up to 128 app connections, newest first. `local` and `remote` are the original TUN socket addresses. `startedMs` counts milliseconds since tunnel start on `CLOCK_BOOTTIME`, including suspend, like Android's `SystemClock.elapsedRealtime()`. `name` is the lowercase TLS SNI or HTTP Host when available, otherwise a live DNS answer name or null. The core inspects up to 8 KiB of the first TLS record without delaying forwarding. Core-served DNS is excluded. The core neither logs nor persists the entries; stopping the tunnel clears them. They do not appear in `snapshot` or `diagnostics`.
