"""Simple Icons SVG -> Android vector drawables for 新增服務規則 (owner-fix4 item 32).

usage: python3 -I tools/gen_brand_icons.py <simple-icons package dir> app/src/main/res/drawable

The package dir is the unpacked npm tarball (simple-icons-<version>.tgz, its package/ folder).

Each 24-unit Simple Icons glyph is placed in a 28.8-unit viewport, so it draws 20 dp of the 24 dp box, as the GitHub
mark (rv_sym_github) and the Material Symbols glyphs beside it do. Numbers are re-emitted with separators (Simple
Icons writes arc flags run together, "01.5"), so every Android path parser reads the data; the fill is white and
the row tints it, as the other row glyphs.
"""

import json
import re
import sys
from pathlib import Path

# slug -> drawable name; GitHub reuses rv_sym_github (the same mark, already in the app).
SLUGS = [
    "steam", "epicgames", "playstation", "ea", "ubisoft", "riotgames",
    "youtube", "netflix", "hbo", "twitch", "spotify", "tiktok", "bilibili",
    "telegram", "discord", "x", "facebook", "instagram", "whatsapp", "line",
    "anthropic", "googlegemini",
    "docker", "google", "apple",
]

ARGS = {"m": 2, "l": 2, "h": 1, "v": 1, "c": 6, "s": 4, "q": 4, "t": 2, "a": 7, "z": 0}
NUM = re.compile(r"[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?")


def normalise(d: str) -> str:
    """Re-emit SVG path data with every number separated: arc flags written as "01.5" become "0 1 .5"."""
    out, i, cmd = [], 0, None
    while i < len(d):
        ch = d[i]
        if ch in " ,\t\n":
            i += 1
            continue
        if ch.isalpha():
            cmd = ch
            out.append(ch)
            i += 1
            if ch in "zZ":
                continue
        n = ARGS[cmd.lower()]
        vals = []
        while len(vals) < n:
            while d[i] in " ,\t\n":
                i += 1
            if cmd.lower() == "a" and len(vals) in (3, 4):
                vals.append(d[i])
                i += 1
                continue
            m = NUM.match(d, i)
            if not m:
                raise SystemExit(f"bad path at {i}: {d[i:i+20]}")
            vals.append(m.group(0))
            i = m.end()
        out.append(" ".join(vals) + " ")
        if cmd == "m":
            cmd = "l"
        elif cmd == "M":
            cmd = "L"
    return "".join(out).strip()


def path_data(svg_file: Path) -> tuple[str, str]:
    text = svg_file.read_text()
    title = text.split("<title>")[1].split("</title>")[0]
    ds = re.findall(r'<path d="([^"]+)"', text)
    assert len(ds) == 1, svg_file
    return title, normalise(ds[0])


def main() -> None:
    pkg, res = Path(sys.argv[1]), Path(sys.argv[2])
    version = json.loads((pkg / "package.json").read_text())["version"]
    for slug in SLUGS:
        title, d = path_data(pkg / "icons" / f"{slug}.svg")
        (res / f"rv_brand_{slug}.xml").write_text(TEMPLATE.format(version=version, title=title, slug=slug, d=d))
        print(slug, title, len(d))


TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<!-- Simple Icons {version} "{title}" (icons/{slug}.svg, CC0 1.0; see app/src/main/SIMPLE_ICONS.md). The 24-unit glyph sits in a
     28.8-unit box, so it draws 20 dp in the 24 dp glyph, as the Material Symbols glyphs beside it do; tinted as they are. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="28.8"
    android:viewportHeight="28.8">
  <group
      android:translateX="2.4"
      android:translateY="2.4">
    <path
        android:fillColor="@android:color/white"
        android:pathData="{d}" />
  </group>
</vector>
"""


if __name__ == "__main__":
    main()
