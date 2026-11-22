"""Converts doona's duck mark, the app's own logo, into an Android vector drawable.

Usage: python -I tools/duck.py <doona checkout> <res/drawable dir>
Source: src/logo.svg. The icons and flags are the :icons and :flags modules, made by tools/gen_icons.py and
tools/gen_flags.py.
"""

import re
import sys
from pathlib import Path

HEADER = '<?xml version="1.0" encoding="utf-8"?>\n'


def logo(src):
    svg = src.read_text()
    vx, vy, vw, vh = (float(v) for v in re.search(r'viewBox="([^"]+)"', svg).group(1).split())
    out = [HEADER, "<!-- doona's duck mark, from doona src/logo.svg. -->\n"]
    out.append('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
               '    android:width="28dp"\n    android:height="28dp"\n'
               '    android:viewportWidth="%g"\n    android:viewportHeight="%g">\n' % (vw, vh))
    out.append('    <group android:translateX="%g" android:translateY="%g">\n' % (-vx, -vy))
    for block in re.findall(r"<path\b(.*?)/>", svg, re.S):
        a = dict(re.findall(r'([\w-]+)="([^"]*)"', block))
        attrs = ['android:pathData="%s"' % " ".join(a["d"].split()), 'android:fillColor="%s"' % a["fill"]]
        if a.get("fill-rule") == "evenodd":
            attrs.append('android:fillType="evenOdd"')
        out.append("        <path\n            " + "\n            ".join(attrs) + " />\n")
    out.append("    </group>\n</vector>\n")
    return "".join(out)


def main():
    doona, res = Path(sys.argv[1]), Path(sys.argv[2])
    res.mkdir(parents=True, exist_ok=True)
    (res / "ic_duck.xml").write_text(logo(doona / "src/logo.svg"))


if __name__ == "__main__":
    main()
