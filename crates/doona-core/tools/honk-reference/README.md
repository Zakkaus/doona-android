# honk-reference

The honk reference for doona-core's native config path (lane c6a-ref, CP:144). It links
honk-config and honk-core at the revision named in `Cargo.toml` (the `rev` of `honk-core`), on the
Linux host, without honk's default features (so no clash-api and no axum) and with `embedded`,
and runs the corpus through honk's own paths. The revision is honk main with items 03, 04 and 09
on the Zakkaus/honk fork until they land upstream; on that repin the traffic report changed only
in `honk_rev`. It imports nothing from doona-core and reads no device data.

For each case it records, in one versioned JSON report:

- the source diagnostics of honk's loader (`parse_dae_config_file_with_detailed_diagnostics`),
  or the load error;
- per probe, the winning rule index and payload, the action (`outbound`, `must`, `mark`) from
  `Router::route_action`, and the selected leaf with its group chain from `GroupManager`.

Inputs are fixed. The geo bundle is the MetaCubeX pair pinned in `../geo-pins.json`; its size and
SHA-256 are checked before honk reads it, and `DAE_LOCATION_ASSET` points honk at it, so no host
copy can stand in. Provider bodies are static `node { ... }` entries in each case's `.dae`.
Health and latency are set per case in `corpus.json` through `AliveDialerSet`, and Selector
choices through `GroupManager::set_selector_choice`. Nothing reads the network. The clock is not
injected: honk reads it on the selection path in three places, and no corpus case depends on any
of them.

- A node's first 60 s after registration (`GRACE_PERIOD`) ignores plain deaths. The harness marks
  deaths forced, which bypasses it. A case that used a plain death would depend on time.
- Samples carry timestamps (`SystemTime`, `Instant`), but the ranking reads only the moving
  average, not their age.
- Score groups read the clock when traffic feedback and verification are recorded
  (`group/score/{feedback,evidence,verification}.rs`). The corpus records none, so its Score
  groups stay cold. A case with recorded Score feedback would depend on time.

Each probe's `protocol` picks the selection: `tcp` reads the TCP health domain, `udp` the data-UDP
one. A health entry's `network` says which one it sets; `udp` death marks both UDP domains, since
either keeps a node selectable. `five-policies-udp` makes TCP and UDP pick different leaves under
urltest, loadbalance, fallback and score.

Corpus and expected report: `../../tests/fixtures/honk/reference/` (`corpus.json`, `cases/*.dae`,
`expected.json`).

## DNS

The DNS corpus (`reference/dns/`, same layout) goes through the embedded engine's
`Engine::resolve_dns`, which answers as the daemon's DNS controller does. Each case names its
upstreams with the one IPv4 address each answers every A query with (TTL 60); the harness binds
each to a loopback UDP port and writes that address for `{name}` in the case's `.dae`. A query's
`server` is the upstream the app sent it to (`asis` forwards there), or `null` for the virtual
resolver. The query has id 0, RD and one A question, with an OPT record when `edns` is set. Per
query the report records:

- the reply bytes as hex (id zeroed) and their RCODE;
- `final_upstream` and `requery_history` from honk's `DnsOutcome` (none for SERVFAIL or REFUSED);
- `dialed`: the upstreams that received a query for it.

Cases: CN to alidns through the pinned geosite (`geosite-cn`); an alternate upstream, the
fallback over UDP with EDNS and over TCP, a reject, `asis` to an addressed server and `asis` to the
virtual resolver, which dials nothing and answers SERVFAIL (`split`); a requery of google's answer
at alidns (`requery`). Nothing leaves loopback.

## Run

Stage the bundle once (needs network): `python3 -I ../update_geo.py /scratch/ssd/honk-android/geodata-pinned`.

```sh
cd crates/doona-core/tools/honk-reference
F=../../tests/fixtures/honk/reference
# compare (exit 1 with one line per difference)
cargo run --locked -- check $F/expected.json --corpus $F --geo BUNDLE --geo-pins ../geo-pins.json
# regenerate after a corpus change or a repin
cargo run --locked -- emit $F/expected.json --corpus $F --geo BUNDLE --geo-pins ../geo-pins.json
# the same for the DNS corpus
cargo run --locked -- dns-check $F/dns/expected.json --corpus $F/dns --geo BUNDLE --geo-pins ../geo-pins.json
cargo run --locked -- dns-emit $F/dns/expected.json --corpus $F/dns --geo BUNDLE --geo-pins ../geo-pins.json
# the guards
HONK_REFERENCE_GEO=BUNDLE cargo test --locked
```

`check` and `dns-check` fail on a different `honk_rev`, a missing corpus or case file, a changed
probe result or reply, and a bundle that does not match its pin. A changed `rev` in `Cargo.toml` therefore needs a
regenerated `expected.json`, reviewed as a diff.

## Deferred

- Global and Direct override cases: the typed mode override is not public until PR 03 lands.
- Subscription bodies through honk's supervisor: its parsers are crate-private. Nodes come from
  the `.dae` instead.
- The repin to honk's upstream revision once 03, 04 and 09 land: change `rev` in `Cargo.toml`,
  regenerate both reports, review.
- UDP selection through nested groups and Selector choices: the Selector choice is set for both
  networks, so the UDP case covers only the policies above.
