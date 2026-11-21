"""Check file length, function length, cyclomatic complexity and package names.

Limits: a file over 600 lines, a function over 80 non-blank, non-comment lines (NLOC) or over a
cyclomatic complexity of 15. Sources: the app's Kotlin and Java, and crates/honk-android's Rust,
tests included. App source directories must not be named util, common, helper or misc.

Existing violations are listed by name in limits-ratchet.txt with exactly their measured value: an
entry above it, or for an item back within the limit, fails, so no entry leaves room to grow back.
With --base-rev, the ratchet is also compared with the one at that revision, so a change cannot
raise or add its own.

This is a heuristic: lizard tokenises rather than parses, so syntax errors are left to the
compilers, Clippy and Spotless. A function's key is lizard's signature (name and parameters, no
class), and Rust's `?` counts as a branch.

Exit status: 0 clean, 1 a violation, 2 the check could not run.
"""

import argparse
import subprocess
import sys
from pathlib import Path

from lizard import analyze_file

LIMITS = {"file": 600, "nloc": 80, "ccn": 15}
GROUPS = {
    "app": (
        "android/tunbench/app/src",
        [f"*/{d}/**/*.{e}" for d in ("java", "kotlin") for e in ("kt", "java")],
    ),
    "rust": ("crates/honk-android", ["src/**/*.rs", "tests/**/*.rs"]),
}
BANNED_DIRS = {"util", "common", "helper", "misc"}
RATCHET = "android/scripts/limits-ratchet.txt"


class CannotRun(Exception):
    pass


def measure(root):
    """Returns {(kind, path, signature): value} for every item over its limit, and problems."""
    over, problems = {}, []
    for group, (base, patterns) in GROUPS.items():
        files = sorted({p for pattern in patterns for p in (root / base).glob(pattern)})
        functions = 0
        for path in files:
            rel = path.relative_to(root).as_posix()
            try:
                code = path.read_bytes().decode("utf-8")
            except (OSError, UnicodeDecodeError) as e:
                raise CannotRun(f"cannot read {rel}: {e}") from e
            if code.count("\n") > LIMITS["file"]:
                over["file", rel, ""] = code.count("\n")
            if group == "app":
                package = path.relative_to(root / base).parts[2:-1]
                problems += [f"{rel}: banned package name {d}" for d in BANNED_DIRS & set(package)]
            found = analyze_file.analyze_source_code(rel, code).function_list
            functions += len(found)
            signatures = [" ".join(f.long_name.split()) for f in found]
            for f, sig in zip(found, signatures, strict=True):
                values = {"nloc": f.nloc, "ccn": f.cyclomatic_complexity}
                for kind, value in values.items():
                    if value > LIMITS[kind]:
                        over[kind, rel, sig] = value
                        if signatures.count(sig) > 1:
                            problems.append(
                                f"{rel}: {sig}: ambiguous, {signatures.count(sig)} functions"
                            )
        print(f"{group}: {len(files)} files, {functions} functions")
        if not files or not functions:
            raise CannotRun(f"{group}: nothing to check under {base}")
    return over, problems


def parse_ratchet(text, origin):
    entries = {}
    for n, line in enumerate(text.splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split(maxsplit=3)
        if len(fields) < 3 or fields[0] not in LIMITS or not fields[1].isdigit():
            raise CannotRun(f"{origin}:{n}: cannot parse {line!r}")
        key = (fields[0], fields[2], fields[3] if len(fields) == 4 else "")
        if key in entries:
            raise CannotRun(f"{origin}:{n}: duplicate entry")
        entries[key] = int(fields[1])
    return entries


def base_ratchet(root, rev):
    """The ratchet at rev, or None when that revision has none yet (the change that adds it)."""

    def git(*args):
        return subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)

    if git("rev-parse", "--verify", "--quiet", f"{rev}^{{commit}}").returncode != 0:
        raise CannotRun(f"cannot resolve base revision {rev}")
    shown = git("show", f"{rev}:{RATCHET}")
    if shown.returncode != 0:
        print(f"{rev} has no {RATCHET}: first freeze, not compared with the base")
        return None
    return parse_ratchet(shown.stdout, f"{rev}:{RATCHET}")


def check(root, base_rev):
    try:
        entries = parse_ratchet((root / RATCHET).read_text(encoding="utf-8"), RATCHET)
    except (OSError, UnicodeDecodeError) as e:
        raise CannotRun(f"cannot read {RATCHET}: {e}") from e
    over, problems = measure(root)
    for key, value in sorted(over.items()):
        name = " ".join(filter(None, key[1:]))
        if key not in entries:
            problems.append(f"{name}: {key[0]} {value} over {LIMITS[key[0]]}, not in the ratchet")
        elif value > entries[key]:
            problems.append(f"{name}: {key[0]} {value} grew from {entries[key]}")
        elif value < entries[key]:
            problems.append(f"{name}: {key[0]} {value}, lower the entry to {value}")
    for key in sorted(entries.keys() - over.keys()):
        name = " ".join(filter(None, key[1:]))
        problems.append(f"{name}: {key[0]} within the limit, delete the line from {RATCHET}")
    if base_rev and (base := base_ratchet(root, base_rev)) is not None:
        for key, value in sorted(entries.items()):
            name = " ".join(filter(None, key[1:]))
            if key not in base:
                problems.append(f"{RATCHET}: {name}: {key[0]} added, not in {base_rev}")
            elif value > base[key]:
                problems.append(
                    f"{RATCHET}: {name}: {key[0]} raised from {base[key]} in {base_rev}"
                )
    for problem in dict.fromkeys(problems):
        print(problem)
    return 1 if problems else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--base-rev", help="compare the ratchet with the one at this revision")
    args = parser.parse_args(argv)
    print(f"root: {args.root}")
    try:
        return check(args.root, args.base_rev)
    except CannotRun as e:
        print(f"error: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
