#!/usr/bin/env python3
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
NEW_CODES = {"profile_newer", "conflict", "source_limit", "non_native_source", "activation_failed"}
MAX_DEPTH = 8
BUILTIN = {"direct", "block"}
CATALOG_KEYS = {"mode", "globalTargetId", "groups", "nodes", "subscriptions", "config"}
# snapshot fixture -> the honk source its `source.line` points into
SOURCE_OF = {
    "snapshot-no-proxy": "no-proxy", "snapshot-five-kinds": "five-kinds", "snapshot-warming": "five-kinds",
    "snapshot-nesting": "nesting", "snapshot-ordinary-names": "ordinary-names",
    "snapshot-ungrouped-node": "ungrouped-node", "snapshot-global-node-target": "global-target",
    "snapshot-global-group-target": "global-target", "snapshot-global-no-target": "global-target",
    "snapshot-loadbalance": "loadbalance", "snapshot-duplicate-group": "duplicate-group",
}
# fixtures whose `summary.groups` counts a shadowed declaration the catalog lists once
SHADOWED = {"snapshot-duplicate-group": 1}

errors = []


def fail(name, text):
    errors.append(f"{name}: {text}")


def load(path):
    return json.loads(path.read_text())


def source_lines(src):
    return (ROOT / "sources" / f"{src}.dae").read_text().split("\n")


def depth_ok(name, groups):
    by_id = {g["id"]: g for g in groups}
    stack = []

    def walk(gid, depth):
        if gid in stack:
            fail(name, f"group cycle through {gid}")
            return
        if depth > MAX_DEPTH:
            fail(name, f"group {gid} is nested deeper than {MAX_DEPTH}")
            return
        stack.append(gid)
        for m in by_id[gid]["members"]:
            if m["isGroup"] and m["id"] in by_id:
                walk(m["id"], depth + 1)
        stack.pop()

    for g in groups:
        walk(g["id"], 0)


def check_group(name, g, node_ids, group_ids, lines):
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
        check_group(name, g, node_ids, group_ids, lines)
    depth_ok(name, groups)
    target = cat["globalTargetId"]
    if target is not None and target not in group_ids | node_ids:
        fail(name, f"globalTargetId {target} resolves to nothing")
    if cat["mode"] == "Global" and load(path)["running"] and target is None:
        fail(name, "running in Global without a target")
    cfg = cat["config"]
    if cfg["fallback"]["target"] not in BUILTIN | group_ids:
        fail(name, f"fallback target {cfg['fallback']['target']} is not a group, direct or block")
    if cfg["blockAds"] not in BLOCK_ADS:
        fail(name, f"blockAds {cfg['blockAds']}")
    if cfg["summary"]["nodes"] != len(nodes):
        fail(name, "summary.nodes differs from nodes")
    if cfg["summary"]["groups"] != len(groups) + SHADOWED.get(name, 0):
        fail(name, "summary.groups differs from groups")
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
    for r in rows:
        start, end = r["source"]["bytes"]
        text = raw[start:end].decode()
        if not (("->" in text) or text.startswith("fallback:")):
            fail("call-ok-rules", f"{r['id']}: bytes {start}..{end} are {text!r}")
        if raw[:start].count(b"\n") + 1 != r["source"]["line"]:
            fail("call-ok-rules", f"{r['id']}: line does not match the byte offset")
    if load(ROOT / "call-ok-rules.json")["ok"]["total"] != len(rows):
        fail("call-ok-rules", "total differs from the rows shown")


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
    for key in ("mainGroupId", "ruleSource", "unmatched"):
        for path in ROOT.glob("*.json"):
            if f'"{key}"' in path.read_text():
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
    check_small()
    check_kinds_covered()
    for line in errors:
        print(line)
    print(f"{len(snaps)} snapshots checked, {len(errors)} violations")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
