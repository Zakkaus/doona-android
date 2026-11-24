"""Regenerates the :flags module from a pinned commit of HatScripts/circle-flags.

Usage: python -I tools/gen_flags.py --work DIR
DIR is a new, empty directory outside the repository: the script fetches the
upstream commit there, and nothing is written into the repository but the module's output.

The module holds every flag of upstream's flags/*.svg (countries, territories, and the subnational and other flags it
files under codes) as a vector drawable flag_<code>, the code with '-' written as '_'. A file that is a link to another
(ac.svg -> sh-ac.svg, uk.svg -> gb.svg, un.svg -> other/united_nations.svg) adds a code to the lookup, not a drawable.
flags/language and the rest of flags/other (a pirate flag, a Klingon one) are left out: they are not places, and a
language code such as af would collide with the country's.

Converter: this script's own, written for the one shape upstream's files have and strict about it. A file is a 512 x 512
<svg> whose only content is <mask><circle fill="#fff"/></mask> and a <g mask> of <path>, <circle>, <ellipse> and <rect>
with a plain fill. The mask becomes a clip-path of the same circle, path data is copied number for number (arc flags
separated), and the other shapes become paths. Anything else, or a changed upstream shape, stops the script.

Writes, relative to the doona-android directory: flags/src/main/res/drawable/flag_*.xml, flags/src/main/kotlin/.../
FlagTable.kt (the generated when from code to drawable), flags/LICENSE, flags/NOTICE and
app/src/main/assets/licenses/circle-flags-MIT.txt. Run it again with a new COMMIT to take newer flags.
"""

import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = "https://github.com/HatScripts/circle-flags.git"
COMMIT = "379588b5da95482d6bbf10bd45644a35b0609ea6"  # gh-pages, 2026-07-26
SVG = "{http://www.w3.org/2000/svg}"

MODULE = Path(__file__).resolve().parent.parent  # android/doona-android
REPO_ROOT = MODULE.parent.parent
PACKAGE = "dev.doona.android.flags"
CODE = re.compile(r"^[a-z0-9]+(?:[-_][a-z0-9]+)*$")
NUMBER = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")
ARITY = {"m": 2, "l": 2, "h": 1, "v": 1, "c": 6, "s": 4, "q": 4, "t": 2, "a": 7, "z": 0}


def git(work, *args):
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    done = subprocess.run(["git", "-C", str(work), *args], capture_output=True, text=True, env=env)
    if done.returncode:
        sys.exit("git %s failed:\n%s" % (" ".join(args[:2]), done.stderr.strip()))
    return done.stdout


def fetch(work):
    """Fetches COMMIT into work/src, with flags/*.svg, flags/other/*.svg and the licence; returns that directory."""
    work = work.resolve()
    if work == REPO_ROOT or REPO_ROOT in work.parents:
        sys.exit("--work must be outside the repository")
    if work.exists() and any(work.iterdir()):
        sys.exit("--work must be a new, empty directory")
    src = work / "src"
    src.mkdir(parents=True, exist_ok=True)
    git(src, "init", "-q")
    git(src, "remote", "add", "origin", REPO)
    git(src, "fetch", "-q", "--depth", "1", "--filter=blob:none", "origin", COMMIT)
    head = git(src, "rev-parse", "FETCH_HEAD").strip()
    if head != COMMIT:
        sys.exit("fetched %s, expected %s" % (head, COMMIT))
    git(src, "sparse-checkout", "init", "--no-cone")
    (src / ".git" / "info" / "sparse-checkout").write_text("/flags/*.svg\n/flags/other/*.svg\n/LICENSE.md\n")
    git(src, "checkout", "-q", "FETCH_HEAD")
    return src


def number(value):
    text = ("%.3f" % value).rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


def join(values):
    """Numbers as path data: a space between two, none before a minus sign."""
    out = ""
    for value in values:
        out += value if not out or value.startswith("-") else " " + value
    return out


def path_data(d, where):
    """Re-serialises SVG path data: every number kept as written, arc flags apart, M's later pairs written as L's."""
    out, position, command = [], 0, None
    while True:
        match = re.compile(r"[\s,]*([A-Za-z])").match(d, position)
        if not match:
            break
        command = match.group(1)
        if command.lower() not in ARITY:
            raise ValueError("%s: path command %r" % (where, command))
        position = match.end()
        arity = ARITY[command.lower()]
        out.append(command)
        groups = []
        while arity:
            group = []
            for index in range(arity):
                flag = command.lower() == "a" and index in (3, 4)
                pattern = re.compile(r"[\s,]*([01])" if flag else r"[\s,]*(" + NUMBER.pattern + ")")
                found = pattern.match(d, position)
                if not found:
                    break
                group.append(found.group(1))
                position = found.end()
            if len(group) != arity:
                if group:
                    raise ValueError("%s: path data ends inside a %r" % (where, command))
                break
            groups.append(group)
        if arity and not groups:
            raise ValueError("%s: %r without numbers" % (where, command))
        if command in "Mm" and len(groups) > 1:
            out.append(join(groups[0]))
            out.append("L" if command == "M" else "l")
            groups = groups[1:]
        out.append(join([value for group in groups for value in group]))
    if d[position:].strip():
        raise ValueError("%s: path data not understood at %r" % (where, d[position : position + 20]))
    return "".join(out)


def colour(value, where):
    match = re.fullmatch(r"#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})", value)
    if not match:
        raise ValueError("%s: fill %r" % (where, value))
    digits = match.group(1)
    if len(digits) == 3:
        digits = "".join(c * 2 for c in digits)
    return "#FF" + digits.upper()


def ellipse(cx, cy, rx, ry):
    return "M%s %sa%s %s 0 1 0 %s 0a%s %s 0 1 0 %s 0z" % (
        number(cx - rx), number(cy), number(rx), number(ry), number(2 * rx), number(rx), number(ry), number(-2 * rx))


def rect(x, y, w, h, rx, ry):
    # SVG 1.1 §9.2 clamps each radius to half its side.
    rx, ry = min(rx or 0, w / 2), min(ry or 0, h / 2)
    if not rx and not ry:
        return "M%s %sh%sv%sh%sz" % (number(x), number(y), number(w), number(h), number(-w))
    arc = "a%s %s 0 0 1 " % (number(rx), number(ry))
    side_x, side_y = w - 2 * rx, h - 2 * ry
    return "".join([
        "M%s %s" % (number(x + rx), number(y)),
        "h%s%s%s %s" % (number(side_x), arc, number(rx), number(ry)),
        "v%s%s%s %s" % (number(side_y), arc, number(-rx), number(ry)),
        "h%s%s%s %s" % (number(-side_x), arc, number(-rx), number(-ry)),
        "v%s%s%s %s" % (number(-side_y), arc, number(rx), number(-ry)),
        "z",
    ])


def floats(element, names, where, default=None):
    out = []
    for name in names:
        raw = element.get(name, default)
        if raw is None or not re.fullmatch(NUMBER.pattern, raw):
            raise ValueError("%s: <%s> %s=%r" % (where, element.tag.removeprefix(SVG), name, raw))
        out.append(float(raw))
    return out


def shape(element, where):
    """Returns (fill, path data) of one drawn element; None for an element that draws nothing."""
    tag = element.tag.removeprefix(SVG)
    allowed = {"fill", "class"}
    transform = element.get("transform")
    if transform is not None and (tag != "circle" or transform != "scale(-1 1)"):
        raise ValueError("%s: transform %r on <%s>" % (where, transform, tag))
    allowed |= {"transform"}
    if tag == "path":
        allowed |= {"d"}
    elif tag == "circle":
        allowed |= {"cx", "cy", "r"}
    elif tag == "ellipse":
        allowed |= {"cx", "cy", "rx", "ry"}
    elif tag == "rect":
        allowed |= {"x", "y", "width", "height", "rx", "ry"}
    else:
        raise ValueError("%s: element <%s>" % (where, tag))
    if set(element.attrib) - allowed or len(element):
        raise ValueError("%s: <%s> attributes %s" % (where, tag, sorted(element.attrib)))
    fill = element.get("fill")
    if fill is None:
        raise ValueError("%s: <%s> without a fill" % (where, tag))
    if fill == "none":
        return None
    if tag == "path":
        return colour(fill, where), path_data(element.get("d", ""), where)
    if tag == "circle":
        cx, cy, r = floats(element, ("cx", "cy", "r"), where)
        return colour(fill, where), ellipse(-cx if transform else cx, cy, r, r)
    if tag == "ellipse":
        return colour(fill, where), ellipse(*floats(element, ("cx", "cy", "rx", "ry"), where))
    x, y = floats(element, ("x", "y"), where, default="0")
    w, h = floats(element, ("width", "height"), where)
    # A radius given alone sets both, as in SVG.
    given = [element.get(name) for name in ("rx", "ry") if name in element.attrib]
    rx, ry = floats(element, ("rx", "ry"), where, default=given[0] if given else "0")
    return colour(fill, where), rect(x, y, w, h, rx, ry)


def convert(path):
    where = path.name
    root = ET.fromstring(path.read_text())
    if root.tag != SVG + "svg" or root.attrib != {"width": "512", "height": "512", "viewBox": "0 0 512 512"}:
        raise ValueError("%s: <svg> is not 512 x 512" % where)
    mask, body = list(root) if len(root) == 2 else (None, None)
    circle = list(mask) if mask is not None and mask.tag == SVG + "mask" else []
    if not (len(circle) == 1 and circle[0].tag == SVG + "circle" and mask.attrib == {"id": "a"}):
        raise ValueError("%s: no <mask> of one circle" % where)
    if circle[0].attrib != {"cx": "256", "cy": "256", "r": "256", "fill": "#fff"}:
        raise ValueError("%s: the mask circle is not the full disc" % where)
    if body.tag != SVG + "g" or body.attrib != {"mask": "url(#a)"}:
        raise ValueError("%s: no <g mask> after the mask" % where)
    drawn = [s for s in (shape(e, where) for e in body) if s]
    if not drawn:
        raise ValueError("%s: draws nothing" % where)
    out = ['<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n']
    out.append('    android:width="24dp"\n    android:height="24dp"\n    android:viewportWidth="512"\n')
    out.append('    android:viewportHeight="512">\n    <group>\n')
    out.append('        <clip-path android:pathData="%s" />\n' % ellipse(256, 256, 256, 256))
    for fill, data in drawn:
        out.append('        <path\n            android:fillColor="%s"\n            android:pathData="%s" />\n' % (fill, data))
    out.append("    </group>\n</vector>\n")
    return "".join(out)


def entries(src):
    """Returns ({code: drawable name}, {drawable name: svg file}) for flags/*.svg, links resolved."""
    flags = src / "flags"
    table, files = {}, {}
    for path in sorted(flags.glob("*.svg")):
        code = path.stem
        if not CODE.match(code):
            sys.exit("unexpected flag code %r" % code)
        target = path
        if path.is_symlink():
            target = (path.parent / os.readlink(path)).resolve()
            if target.parent not in (flags.resolve(), (flags / "other").resolve()) or not target.is_file():
                sys.exit("%s links outside flags/ and flags/other/: %s" % (path.name, os.readlink(path)))
        stem = target.stem
        if not CODE.match(stem):
            sys.exit("unexpected flag name %r" % stem)
        name = "flag_" + stem.replace("-", "_")
        if files.setdefault(name, target) != target:
            sys.exit("two files for %s" % name)
        table[code] = name
    return table, files


TABLE = """// Generated by tools/gen_flags.py from HatScripts/circle-flags {commit}: do not edit.
package {package}

/**
 * The circle flag for [code]: an ISO 3166-1 alpha-2 code, or one of the other codes circle-flags files a flag under
 * (`gb-eng`, `eu`, `xk`, ...), in any case. Null when the set has none, and the caller then shows its own fallback.
 * Flags are looked up by code at run time, so the drawables are all kept in a release build.
 */
fun flagResource(code: String): Int? = when (code.lowercase()) {{
{rows}
    else -> null
}}
"""

NOTICE = """circle-flags (res/drawable/flag_*.xml)
Copyright (c) 2026 HatScripts. From HatScripts/circle-flags at commit
{commit},
flags/*.svg, converted to vector drawables by tools/gen_flags.py (the circle
mask becomes a clip-path; geometry and colours are copied). MIT licence, in
LICENSE. FlagTable.kt is generated by the same script.
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--work", required=True, type=Path, help="a new, empty directory outside the repository")
    src = fetch(parser.parse_args().work)
    table, files = entries(src)
    res = MODULE / "flags/src/main/res/drawable"
    res.mkdir(parents=True, exist_ok=True)
    for old in res.glob("flag_*.xml"):
        old.unlink()
    for name, path in sorted(files.items()):
        try:
            (res / (name + ".xml")).write_text(convert(path))
        except ValueError as error:
            sys.exit(str(error))
    kotlin = MODULE / "flags/src/main/kotlin" / PACKAGE.replace(".", "/")
    kotlin.mkdir(parents=True, exist_ok=True)
    rows = "\n".join('    "%s" -> R.drawable.%s' % (code, name) for code, name in sorted(table.items()))
    (kotlin / "FlagTable.kt").write_text(TABLE.format(commit=COMMIT, package=PACKAGE, rows=rows))
    licence = (src / "LICENSE.md").read_text()
    (MODULE / "flags/LICENSE").write_text(licence)
    (MODULE / "flags/NOTICE").write_text(NOTICE.format(commit=COMMIT))
    (MODULE / "app/src/main/assets/licenses/circle-flags-MIT.txt").write_text(licence)
    print("wrote %d drawables and %d codes from %s" % (len(files), len(table), COMMIT))


if __name__ == "__main__":
    main()
