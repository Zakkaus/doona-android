"""Builds the app's Noto Sans TC files from doona's font package.

Usage: python -I tools/fonts.py <doona checkout> <app/src/main dir>
Needs fontTools and brotli. doona ships Noto Sans TC as a variable font cut into unicode-range slices
(@fontsource-variable/noto-sans-tc 5.3.0, SIL OFL 1.1). This script keeps the slices that hold a character the app
shows (printable ASCII plus every character in the string resources and Kotlin sources), fixes each at the weights
the type scale uses, merges them into one TrueType file per weight, and applies doona's metric overrides from
src/fonts-tc.css:4 (ascent 100 %, descent 20 %, line gap 0).
"""

import io
import re
import sys
import tempfile
from pathlib import Path

from fontTools.merge import Merger
from fontTools.subset import Options, Subsetter
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

WEIGHTS = [400, 500, 600, 700, 800]
DROP = ["vhea", "vmtx", "BASE", "STAT", "HVAR", "VORG"]


def used_chars(main):
    chars = {chr(c) for c in range(0x20, 0x7F)}
    for path in list(main.glob("res/values*/strings.xml")) + list(main.glob("kotlin/**/*.kt")):
        chars |= {ch for ch in path.read_text() if ord(ch) > 0x7F}
    return chars


def slices(package):
    css = (package / "index.css").read_text()
    for block in re.findall(r"@font-face\s*{(.*?)}", css, re.S):
        src = re.search(r"url\(\./files/([^)]+)\)", block).group(1)
        ranges = []
        for part in re.search(r"unicode-range:\s*([^;]+);", block).group(1).split(","):
            lo, _, hi = part.strip()[2:].partition("-")
            ranges.append((int(lo, 16), int(hi or lo, 16)))
        yield package / "files" / src, ranges


def main():
    doona, main_dir = Path(sys.argv[1]), Path(sys.argv[2])
    package = doona / "node_modules/@fontsource-variable/noto-sans-tc"
    wanted = used_chars(main_dir)
    plan = {}
    for path, ranges in slices(package):
        hits = {ch for ch in wanted if any(lo <= ord(ch) <= hi for lo, hi in ranges)}
        if not hits:
            continue
        cmap = TTFont(path).getBestCmap()
        hits = {ch for ch in hits if ord(ch) in cmap}
        if hits:
            plan[path] = hits
            wanted -= hits
    missing = sorted(ch for ch in wanted if not ch.isspace() or ch == " ")
    if missing:
        print("not in Noto Sans TC:", " ".join("U+%04X" % ord(ch) for ch in missing), file=sys.stderr)
    out_dir = main_dir / "res/font"
    out_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for weight in WEIGHTS:
            parts = []
            for i, (path, chars) in enumerate(plan.items()):
                font = TTFont(path)
                options = Options()
                options.layout_features = ["kern", "tnum", "ccmp", "locl", "mark", "mkmk"]
                options.name_IDs = ["*"]
                subsetter = Subsetter(options)
                subsetter.populate(unicodes=[ord(ch) for ch in chars])
                subsetter.subset(font)
                font = instancer.instantiateVariableFont(font, {"wght": weight})
                for tag in DROP:
                    if tag in font:
                        del font[tag]
                part = Path(tmp) / ("%d-%d.ttf" % (weight, i))
                font.save(part)
                parts.append(str(part))
            merged = Merger().merge(parts) if len(parts) > 1 else TTFont(parts[0])
            hhea, os2 = merged["hhea"], merged["OS/2"]
            hhea.ascent, hhea.descent, hhea.lineGap = 1000, -200, 0
            os2.sTypoAscender, os2.sTypoDescender, os2.sTypoLineGap = 1000, -200, 0
            os2.usWinAscent, os2.usWinDescent = 1000, 200
            os2.fsSelection |= 1 << 7
            os2.usWeightClass = weight
            buffer = io.BytesIO()
            merged.save(buffer)
            (out_dir / ("noto_sans_tc_w%d.ttf" % weight)).write_bytes(buffer.getvalue())
    print("slices:", ", ".join(p.name for p in plan), "| chars:", sum(len(c) for c in plan.values()))


if __name__ == "__main__":
    main()
