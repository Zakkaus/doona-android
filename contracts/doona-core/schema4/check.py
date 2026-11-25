"""Checks the schema 4 fixtures against the rules of plans/v1/honk/core-api-schema4.md.

Usage: check.py [DIR]   (DIR defaults to this file's directory)
Exit status 0 when every fixture holds, 1 with one line per violation otherwise.
"""
import json
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent
KINDS = {"Selector", "UrlTest", "LoadBalance", "Fallback", "Score"}
BLOCK_ADS = {"on", "off", "absent", "elsewhere"}
NEW_CODES = {"conflict", "source_limit", "non_native_source", "activation_failed"}
MAX_DEPTH = 8  # honk MAX_GROUP_DEPTH: a group is entered only while the depth is below it
REMOVED = ("mainGroupId", "ruleSource", "unmatched", "rulesRevision", "dnsVia", "dnsUpstreams", "RuleOptions",
           "setDnsUpstreams")
BUILTIN = {"direct", "block"}
CATALOG_KEYS = {"mode", "globalTargetId", "groups", "nodes", "subscriptions", "config"}
# snapshot fixture -> the honk source its `source.line` points into
SOURCE_OF = {
    "snapshot-no-proxy": "no-proxy", "snapshot-five-kinds": "five-kinds", "snapshot-warming": "warming",
    "snapshot-nesting": "nesting", "snapshot-ordinary-names": "ordinary-names",
    "snapshot-ungrouped-node": "ungrouped-node", "snapshot-global-node-target": "global-target",
    "snapshot-global-group-target": "global-target", "snapshot-global-no-target": "global-target",
    "snapshot-loadbalance": "loadbalance", "snapshot-duplicate-group": "duplicate-group",
    "snapshot-zero-groups": "zero-groups", "snapshot-empty-profile": "empty-profile",
    "snapshot-stale-default": "stale-default",
}

errors = []


def fail(name, text):
    errors.append(f"{name}: {text}")


def load(path):
    return json.loads(path.read_text())


def source_lines(src):
    return (ROOT / "sources" / f"{src}.dae").read_text().split("\n")


def declarations(lines):
    """Group declarations of a source: `name {` lines one level inside the top-level `group {` block."""
    count, inside = 0, False
    for line in lines:
        if line.startswith("group {"):
            inside = True
        elif inside and line.startswith("}"):
            inside = False
        elif inside and line.startswith("    ") and not line.startswith("     ") and line.rstrip().endswith("{"):
            count += 1
    return count


def routing_statements(raw):
    """(start byte, text) of each statement inside the top-level `routing {` block."""
    out, inside, pos = [], False, 0
    for line in raw.split(b"\n"):
        text = line.strip().decode()
        if line.startswith(b"routing {"):
            inside = True
        elif inside and line.startswith(b"}"):
            inside = False
        elif inside and text:
            out.append((pos + len(line) - len(line.lstrip()), text))
        pos += len(line) + 1
    return out


def depth_ok(name, groups):
    by_id = {g["id"]: g for g in groups}
    stack = []

    def walk(gid, depth):
        if gid in stack:
            fail(name, f"group cycle through {gid}")
            return
        if depth >= MAX_DEPTH:
            fail(name, f"group {gid} is nested deeper than {MAX_DEPTH}")
            return
        stack.append(gid)
        for m in by_id[gid]["members"]:
            if m["isGroup"] and m["id"] in by_id:
                walk(m["id"], depth + 1)
        stack.pop()

    for g in groups:
        walk(g["id"], 0)


def check_group(name, g, nodes, group_ids, lines):
    node_ids = set(nodes)
    gid = g["id"]
    if gid != "g-" + g["name"]:
        fail(name, f"{gid}: id is not g- plus the name")
    if g["kind"] not in KINDS:
        fail(name, f"{gid}: unknown kind {g['kind']}")
    member_ids = []
    for m in g["members"]:
        member_ids.append(m["id"])
        if m["isGroup"]:
            if m["id"] not in group_ids:
                fail(name, f"{gid}: member group {m['id']} is not in groups")
        elif m["id"] in BUILTIN:
            if m["subscriptionId"] is not None:
                fail(name, f"{gid}: built-in member {m['id']} has a subscription")
        elif m["id"] not in node_ids:
            fail(name, f"{gid}: member {m['id']} is not in nodes")
        elif m["latencyMs"] != nodes[m["id"]]["latencyMs"]:
            fail(name, f"{gid}: member {m['id']} latency differs from the node table")
    if len(set(member_ids)) != len(member_ids):
        fail(name, f"{gid}: repeated member")
    for field in ("selectedId", "tcpSelectedId", "udpSelectedId"):
        if g[field] is not None and g[field] not in member_ids:
            fail(name, f"{gid}: {field} {g[field]} is not a member")
    if g["kind"] == "LoadBalance" and g["selectedId"] is not None and not g["pinned"]:
        fail(name, f"{gid}: an unpinned LoadBalance has a selected member")
    if g["pinned"] and g["selectedId"] is None:
        fail(name, f"{gid}: pinned without a selected member")
    if g["kind"] == "Selector" and g["members"] and g["selectedId"] is None:
        fail(name, f"{gid}: a non-empty Selector has no selected member")
    if g["kind"] == "Selector" and g["members"] and not g["pinned"]:
        want = g["default"] if g["default"] is not None else member_ids[0]
        if g["selectedId"] != want:
            fail(name, f"{gid}: an unpinned Selector selects {g['selectedId']}, not {want}")
    if g["default"] is not None and g["default"] not in member_ids:
        fail(name, f"{gid}: default {g['default']} is not a member")
    if g["final"] is not None and g["final"] not in BUILTIN | group_ids | node_ids:
        fail(name, f"{gid}: final {g['final']} resolves to nothing")
    if g["exit"] is not None and g["exit"] not in node_ids:
        fail(name, f"{gid}: exit {g['exit']} is not a node")
    src = g["source"]
    if src["file"] != "f1" or not isinstance(src["line"], int) or src["line"] < 1:
        fail(name, f"{gid}: bad source {src}")
    elif lines is not None:
        text = lines[src["line"] - 1].strip() if src["line"] <= len(lines) else ""
        if text != g["name"] + " {":
            fail(name, f"{gid}: source line {src['line']} is {text!r}, not the declaration")


def check_snapshot(path):
    name = path.stem
    cat = load(path)["catalog"]
    if set(cat) != CATALOG_KEYS:
        fail(name, f"catalog keys {sorted(cat)}")
        return
    groups, nodes = cat["groups"], cat["nodes"]
    group_ids = {g["id"] for g in groups}
    node_ids = {n["id"] for n in nodes}
    if len(group_ids) != len(groups):
        fail(name, "group IDs are not unique")
    if len(node_ids) != len(nodes):
        fail(name, "node IDs are not unique")
    for n in nodes:
        if n["source"] not in ("static", "subscription") or "isGroup" in n:
            fail(name, f"{n['id']}: bad node entry")
        if (n["source"] == "static") != (n["subscriptionId"] is None):
            fail(name, f"{n['id']}: source and subscriptionId disagree")
    lines = source_lines(SOURCE_OF[name]) if name in SOURCE_OF else None
    for g in groups:
        check_group(name, g, {n['id']: n for n in nodes}, group_ids, lines)
    depth_ok(name, groups)
    target = cat["globalTargetId"]
    if target is not None and target not in group_ids | node_ids:
        fail(name, f"globalTargetId {target} resolves to nothing")
    cfg = cat["config"]
    if cfg["fallback"]["target"] not in BUILTIN | group_ids:
        fail(name, f"fallback target {cfg['fallback']['target']} is not a group, direct or block")
    if cfg["blockAds"] not in BLOCK_ADS:
        fail(name, f"blockAds {cfg['blockAds']}")
    if cfg["summary"]["nodes"] != len(nodes):
        fail(name, "summary.nodes differs from nodes")
    declared = declarations(lines) if lines is not None else len(groups)
    if cfg["summary"]["groups"] != declared:
        fail(name, f"summary.groups {cfg['summary']['groups']} is not the {declared} declarations")
    if any(s["id"] for s in cat["subscriptions"]) and {n["subscriptionId"] for n in nodes} - {None} - {
            s["id"] for s in cat["subscriptions"]}:
        fail(name, "a node names a subscription the catalog lacks")


def check_rules():
    rows = load(ROOT / "call-ok-rules.json")["ok"]["rows"]
    raw = (ROOT / "sources" / "no-proxy.dae").read_bytes()
    if [r["index"] for r in rows] != list(range(len(rows))):
        fail("call-ok-rules", "row indices are not 0..n")
    if [r["fallback"] for r in rows] != [False] * (len(rows) - 1) + [True]:
        fail("call-ok-rules", "the fallback row is not last")
    statements = routing_statements(raw)
    if len(statements) != len(rows):
        fail("call-ok-rules", f"{len(rows)} rows for {len(statements)} statements")
    for r, (first, statement) in zip(rows, statements):
        start, end = r["source"]["bytes"]
        text = raw[start:end].decode()
        if (start, text) != (first, statement):
            fail("call-ok-rules", f"{r['id']}: bytes {start}..{end} are {text!r}, not {statement!r}")
        if raw[:start].count(b"\n") + 1 != r["source"]["line"]:
            fail("call-ok-rules", f"{r['id']}: line does not match the byte offset")
    if load(ROOT / "call-ok-rules.json")["ok"]["total"] != len(rows):
        fail("call-ok-rules", "total differs from the rows shown")


def check_usage():
    today = load(ROOT / "call-ok-usage-by-rule.json")["ok"]["today"]
    for field in ("up", "down", "flows"):
        if not isinstance(today[field], list) or len(today[field]) != 24:
            fail("call-ok-usage-by-rule", f"today.{field} is not a 24-element hourly array")
            return
    for field in ("up", "down"):
        if sum(today[field]) != sum(r[field] for r in today["byRule"]):
            fail("call-ok-usage-by-rule", f"hourly {field} does not sum to the byRule rows")
        if sum(today[field]) != sum(r[field] for r in today["outbounds"]):
            fail("call-ok-usage-by-rule", f"hourly {field} does not sum to the outbounds")
    if sum(today["flows"]) != sum(r["flows"] for r in today["byRule"]):
        fail("call-ok-usage-by-rule", "hourly flows do not sum to the byRule rows")


def check_small():
    if load(ROOT / "init.json") != {"schema": 4}:
        fail("init", "schema is not 4")
    for code in NEW_CODES:
        path = ROOT / f"call-error-{code.replace('_', '-')}.json"
        if load(path) != {"error": {"code": code}}:
            fail(path.stem, "wrong error payload")
    for ads in load(ROOT / "catalog" / "config-block-ads.json"):
        if ads["blockAds"] not in BLOCK_ADS:
            fail("config-block-ads", ads["blockAds"])
    if {c["blockAds"] for c in load(ROOT / "catalog" / "config-block-ads.json")} != BLOCK_ADS:
        fail("config-block-ads", "does not show all four states")
    targets = load(ROOT / "catalog" / "config-fallback-targets.json")
    if {t["fallback"]["target"][:2] for t in targets} != {"g-", "di", "bl"}:
        fail("config-fallback-targets", "does not show a group, direct and block")
    for path in sorted(ROOT.glob("call-*-args.json")):
        load(path)
    for path in ROOT.rglob("*.json"):
        text = path.read_text()
        for key in REMOVED:
            if key in text:
                fail(path.stem, f"mentions removed {key}")


def check_kinds_covered():
    seen = set()
    for path in ROOT.glob("snapshot-*.json"):
        for g in load(path)["catalog"]["groups"]:
            seen.add(g["kind"])
    if seen != KINDS:
        fail("kinds", f"samples do not cover {sorted(KINDS - seen)}")


def main():
    snaps = sorted(ROOT.glob("snapshot-*.json"))
    for path in snaps:
        check_snapshot(path)
    check_rules()
    check_usage()
    check_small()
    check_kinds_covered()
    for line in errors:
        print(line)
    print(f"{len(snaps)} snapshots checked, {len(errors)} violations")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
