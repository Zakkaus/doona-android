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
# honk policy text (HC/parser/groups.rs:273-277) -> catalog kind
POLICY_KIND = {
    "select": "Selector", "selector": "Selector", "fixed": "Selector",
    "urltest": "UrlTest", "min_moving_avg": "UrlTest", "min_avg10": "UrlTest", "min_last_delay": "UrlTest",
    "roundrobin": "LoadBalance", "round_robin": "LoadBalance", "loadbalance": "LoadBalance",
    "balance": "LoadBalance", "fallback": "Fallback", "score": "Score",
}
# rule list fixture -> (honk source, block path of its statements, honk action text -> row action)
RULE_LISTS = {
    "call-ok-rules": ("no-proxy", ["routing"], None),
    "call-ok-rules-dns-request": ("dns-rules", ["dns", "routing", "request"], {"reject": "reject", "asis": "asIs"}),
    "call-ok-rules-dns-response": ("dns-rules", ["dns", "routing", "response"], {"reject": "reject", "accept": "accept"}),
}
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
    """(line, name) of each group declared one level inside the top-level `group {` block."""
    found, inside = [], False
    for number, line in enumerate(lines, 1):
        if line.startswith("group {"):
            inside = True
        elif inside and line.startswith("}"):
            inside = False
        elif inside and line.startswith("    ") and not line.startswith("     ") and line.rstrip().endswith("{"):
            found.append((number, line.strip()[:-1].strip()))
    return found


def source_policy(lines, line):
    """Policy text of the group declared at `line`; honk defaults to select."""
    for text in lines[line:]:
        text = text.strip()
        if text == "}":
            break
        if text.startswith("policy:"):
            return text.split(":", 1)[1].split("(")[0].strip().lower()
    return "select"


def statements(raw, path):
    """(line, start byte, text) of each statement directly inside the block `path`, comments included."""
    out, stack, pos = [], [], 0
    for number, line in enumerate(raw.split(b"\n"), 1):
        text = line.strip().decode()
        if text.endswith("{"):
            stack.append(text[:-1].strip())
        elif text == "}":
            stack.pop()
        elif text and stack == path:
            out.append((number, pos + len(line) - len(line.lstrip()), text))
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
    else:
        winning = [n for n, declared in declarations(lines) if declared == g["name"]]
        if not winning or src["line"] != winning[-1]:
            fail(name, f"{gid}: source line {src['line']} is not the last declaration of {g['name']} ({winning})")
        elif POLICY_KIND.get(source_policy(lines, src["line"])) != g["kind"]:
            fail(name, f"{gid}: kind {g['kind']} does not match the policy {source_policy(lines, src['line'])!r}")


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
    if name not in SOURCE_OF:
        fail(name, "no honk source is mapped to this snapshot")
        return
    lines = source_lines(SOURCE_OF[name])
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
    declared = len(declarations(lines))
    if cfg["summary"]["groups"] != declared:
        fail(name, f"summary.groups {cfg['summary']['groups']} is not the {declared} declarations")
    if cfg["summary"]["subscriptions"] != len(cat["subscriptions"]):
        fail(name, "summary.subscriptions differs from subscriptions")
    if {n["subscriptionId"] for n in nodes} - {None} - {s["id"] for s in cat["subscriptions"]}:
        fail(name, "a node names a subscription the catalog lacks")
    for sub in cat["subscriptions"]:
        fed = sum(n["subscriptionId"] == sub["id"] for n in nodes)
        if sub["nodes"] != fed:
            fail(name, f"subscription {sub['id']} counts {sub['nodes']} nodes, the catalog lists {fed}")


def check_rules(stem, source, path, actions):
    ok = load(ROOT / f"{stem}.json")["ok"]
    rows = ok["rows"]
    raw = (ROOT / "sources" / f"{source}.dae").read_bytes()
    if ok["total"] != len(rows):
        fail(stem, "total differs from the rows shown")
    if [r["index"] for r in rows] != list(range(len(rows))):
        fail(stem, "row indices are not 0..n")
    if [r["fallback"] for r in rows] != [False] * (len(rows) - 1) + [True]:
        fail(stem, "the fallback row is not last")
    found = statements(raw, path)
    if len(found) != len(rows):
        fail(stem, f"{len(rows)} rows for {len(found)} statements")
    for r, (line, first, statement) in zip(rows, found):
        start, end = r["source"]["bytes"]
        text = raw[start:end].decode()
        if (r["source"]["line"], start, text) != (line, first, statement):
            fail(stem, f"{r['id']}: line {r['source']['line']}, bytes {start}..{end} are {text!r}, not {statement!r}")
        body = statement.removeprefix("#").strip()
        if r["paused"] != statement.startswith("#"):
            fail(stem, f"{r['id']}: paused differs from the comment marker")
        if r["fallback"] != body.startswith("fallback:"):
            fail(stem, f"{r['id']}: fallback differs from the statement")
        if actions is not None:
            tail = body.split("->")[-1] if "->" in body else body.split(":", 1)[1]
            tail = tail.strip()
            want = (actions[tail.lower()], None) if tail.lower() in actions else ("upstream", tail)
            if (r["action"], r["upstream"]) != want:
                fail(stem, f"{r['id']}: action {r['action']}/{r['upstream']} is not {want} for {tail!r}")


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
    codes = set()
    for path in ROOT.glob("call-error-*.json"):
        code = path.stem.removeprefix("call-error-").replace("-", "_")
        codes.add(code)
        if load(path) != {"error": {"code": code}}:
            fail(path.stem, "payload is not the code in the file name")
    if not NEW_CODES <= codes:
        fail("call-error", f"no fixture for {sorted(NEW_CODES - codes)}")
    states = load(ROOT / "catalog" / "config-block-ads.json")
    if {c["blockAds"] for c in states} != BLOCK_ADS:
        fail("config-block-ads", "does not show all four states")
    for ads in states:
        # a paused (commented-out) or absent statement is no effective DNS rule (§7.3)
        want = 1 if ads["blockAds"] in ("on", "elsewhere") else 0
        if ads["summary"]["dnsRequestRules"] != want:
            fail("config-block-ads", f"{ads['blockAds']}: dnsRequestRules is {ads['summary']['dnsRequestRules']}")
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
    for stem, (source, path, actions) in RULE_LISTS.items():
        check_rules(stem, source, path, actions)
    check_usage()
    check_small()
    check_kinds_covered()
    for line in errors:
        print(line)
    print(f"{len(snaps)} snapshots checked, {len(errors)} violations")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
