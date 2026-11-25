"""Regenerates the :icons module from a pinned commit of google/material-design-icons.

Usage: python -I tools/gen_icons.py --work DIR
DIR is a new, empty directory outside the repository: the script fetches the
upstream commit there, and nothing is written into the repository but the module's output.

The module holds the complete Material Symbols Rounded set (Apache-2.0), filled (FILL 1), weight 500, 24 px, as vector
drawables named sym_<name>, plus the weight-600 variants of add and close as sym_add_w600 and sym_close_w600. Upstream's
Android XML is taken as it is, bar two normalisations that keep it independent of the theme: the fill is black (the icon
is tinted where it is drawn) and the colorControlNormal tint is dropped. Any other shape of file stops the script.

Writes, relative to the doona-android directory: icons/src/main/res/drawable/sym_*.xml, icons/LICENSE, icons/NOTICE and
app/src/main/assets/licenses/Apache-2.0.txt. Run it again with a new COMMIT to take a newer release.
"""

import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = "https://github.com/google/material-design-icons.git"
COMMIT = "10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7"  # master, 2026-10-23
STYLE = "materialsymbolsrounded"
BASE = ("wght500fill1", "")  # upstream's variant token, the suffix of the drawable name
HEAVY = {"add": ("wght600fill1", "_w600"), "close": ("wght600fill1", "_w600")}

MODULE = Path(__file__).resolve().parent.parent  # android/doona-android
REPO_ROOT = MODULE.parent.parent
ANDROID = "{http://schemas.android.com/apk/res/android}"
NAME = re.compile(r"^[a-z0-9][a-z0-9_]*$")


def git(work, *args, check=True):
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    done = subprocess.run(["git", "-C", str(work), *args], capture_output=True, text=True, env=env)
    if check and done.returncode:
        sys.exit("git %s failed:\n%s" % (" ".join(args[:2]), done.stderr.strip()))
    return done.stdout


def fetch(work):
    """Fetches COMMIT without its history or blobs into work/src; returns that directory."""
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
    return src


def variants(src):
    """Returns {drawable name: path of upstream's file in the checkout}, and checks it out."""
    names = [line.rsplit("/", 1)[-1] for line in git(src, "ls-tree", "--name-only", "FETCH_HEAD", "symbols/android/").split()]
    wanted = {}
    for name in names:
        if not NAME.match(name):
            sys.exit("unexpected icon name %r" % name)
        token, suffix = BASE
        wanted["sym_" + name + suffix] = "symbols/android/%s/%s/%s_%s_24px.xml" % (name, STYLE, name, token)
    for name, (token, suffix) in HEAVY.items():
        wanted["sym_" + name + suffix] = "symbols/android/%s/%s/%s_%s_24px.xml" % (name, STYLE, name, token)
    git(src, "sparse-checkout", "init", "--no-cone")
    (src / ".git" / "info" / "sparse-checkout").write_text("".join("/" + p + "\n" for p in [*wanted.values(), "LICENSE"]))
    git(src, "checkout", "-q", "FETCH_HEAD")
    missing = [p for p in wanted.values() if not (src / p).is_file()]
    if missing:
        sys.exit("upstream lacks %d expected files, e.g. %s" % (len(missing), missing[0]))
    return {name: src / path for name, path in wanted.items()}


def convert(path):
    root = ET.fromstring(path.read_text())
    paths = list(root)
    attrs = dict(root.attrib)
    expected = {"width", "height", "viewportWidth", "viewportHeight", "tint"}
    optional = {"autoMirrored"}
    keys = {k.removeprefix(ANDROID) for k in attrs}
    if root.tag != "vector" or not expected <= keys or keys - expected - optional:
        raise ValueError("unexpected <vector> attributes %s" % sorted(keys))
    if attrs[ANDROID + "width"] != "24dp" or attrs[ANDROID + "height"] != "24dp":
        raise ValueError("not 24dp")
    if attrs[ANDROID + "tint"] != "?attr/colorControlNormal":
        raise ValueError("unexpected tint")
    if len(paths) != 1 or paths[0].tag != "path" or len(paths[0]):
        raise ValueError("not exactly one <path>")
    path_attrs = {k.removeprefix(ANDROID): v for k, v in paths[0].attrib.items()}
    if set(path_attrs) != {"fillColor", "pathData"} or path_attrs["fillColor"] != "@android:color/white":
        raise ValueError("unexpected <path> attributes %s" % sorted(path_attrs))
    width, height = attrs[ANDROID + "viewportWidth"], attrs[ANDROID + "viewportHeight"]
    if width != height or not re.fullmatch(r"\d+", width):
        raise ValueError("unexpected viewport %sx%s" % (width, height))
    data = path_attrs["pathData"]
    if '"' in data or "<" in data or "&" in data:
        raise ValueError("pathData needs escaping")
    mirrored = attrs.get(ANDROID + "autoMirrored")
    if mirrored not in (None, "true"):
        raise ValueError("unexpected autoMirrored %r" % mirrored)
    out = ['<?xml version="1.0" encoding="utf-8"?>\n']
    out.append('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n')
    out.append('    android:width="24dp"\n    android:height="24dp"\n')
    out.append('    android:viewportWidth="%s"\n    android:viewportHeight="%s"' % (width, height))
    if mirrored:
        out.append('\n    android:autoMirrored="true"')
    out.append('>\n    <path\n        android:fillColor="#FF000000"\n        android:pathData="%s" />\n</vector>\n' % data)
    return "".join(out)


NOTICE = """Material Symbols Rounded (res/drawable/sym_*.xml)
Copyright Google LLC. From google/material-design-icons at commit
{commit},
the Rounded style, filled (FILL 1), weight 500, 24 px, as upstream ships it in
symbols/android/<name>/{style}/<name>_wght500fill1_24px.xml.
sym_add_w600 and sym_close_w600 are the weight-600 files. Only the fill (black)
and the colorControlNormal tint differ from upstream. Apache License 2.0, in
LICENSE. tools/gen_icons.py regenerates every file.
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--work", required=True, type=Path, help="a new, empty directory outside the repository")
    work = parser.parse_args().work
    src = fetch(work)
    files = variants(src)
    res = MODULE / "icons/src/main/res/drawable"
    res.mkdir(parents=True, exist_ok=True)
    for old in res.glob("sym_*.xml"):
        old.unlink()
    for name, path in sorted(files.items()):
        try:
            text = convert(path)
        except ValueError as error:
            sys.exit("%s: %s" % (path.relative_to(src), error))
        (res / (name + ".xml")).write_text(text)
    licence = (src / "LICENSE").read_text()
    notice = NOTICE.format(commit=COMMIT, style=STYLE)
    (MODULE / "icons/LICENSE").write_text(licence)
    (MODULE / "icons/NOTICE").write_text(notice)
    (MODULE / "app/src/main/assets/licenses/Apache-2.0.txt").write_text(licence)
    print("wrote %d drawables from %s" % (len(files), COMMIT))


if __name__ == "__main__":
    main()
