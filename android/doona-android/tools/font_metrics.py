"""Compares the vertical metrics and the source version of two sets of the app's Noto Sans TC files.

Usage: python -I tools/font_metrics.py <old font dir> <new font dir>
Needs fontTools. A rebuild of the subset (tools/fonts.py) may only change which characters the files hold: the line
height and the text's place in a row come from these fields, so any change in them moves text in every golden. Prints
each field that differs and exits 1, or exits 0 when every weight matches.
"""

import sys
from pathlib import Path

from fontTools.ttLib import TTFont

WEIGHTS = [400, 500, 600, 700, 800]
FIELDS = {
    "head": ["xMin", "yMin", "xMax", "yMax", "unitsPerEm", "fontRevision"],
    "hhea": ["ascent", "descent", "lineGap"],
    "OS/2": ["sTypoAscender", "sTypoDescender", "sTypoLineGap", "usWinAscent", "usWinDescent", "fsSelection"],
}


def metrics(path):
    font = TTFont(path)
    values = {f"{table}.{field}": getattr(font[table], field) for table, fields in FIELDS.items() for field in fields}
    values["name.version"] = font["name"].getDebugName(5)
    return values


def main():
    old_dir, new_dir = Path(sys.argv[1]), Path(sys.argv[2])
    differ = False
    for weight in WEIGHTS:
        name = "noto_sans_tc_w%d.ttf" % weight
        old, new = metrics(old_dir / name), metrics(new_dir / name)
        for key in old:
            if old[key] != new[key]:
                differ = True
                print(f"{name} {key}: {old[key]} -> {new[key]}")
    print("font metrics differ" if differ else "font metrics match")
    sys.exit(1 if differ else 0)


if __name__ == "__main__":
    main()
