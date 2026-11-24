"""Brand marks from Bootstrap Icons and Wikimedia Commons -> Android vector drawables for 新增服務規則 (owner-fix5).

usage: python3 -I tools/gen_brand_marks.py <source dir> app/src/main/res/drawable

The source dir holds the files exactly as downloaded (app/src/main/BRAND_ICONS.md gives each URL and checksum):
  bootstrap/xbox.svg, bootstrap/microsoft.svg, bootstrap/openai.svg   (twbs/icons v1.13.2, icons/<name>.svg)
  commons/Nintendo.svg, commons/Blizzard_Entertainment_Logo_2015.svg, commons/Disney+_2024.svg,
  commons/Prime_Video_logo_(2024).svg

Each mark keeps its paths; numbers are re-emitted with separators as tools/gen_brand_icons.py does. A group scales the
mark so its longer side is 20 units of the 24-unit viewport and centres it, so it draws 20 dp of the 24 dp box like the
Simple Icons marks (a wide wordmark is 20 dp wide and shorter). The ink's bounds come from rendering the source with
rsvg-convert. Nintendo's red plate with its white wordmark becomes one white plate with the wordmark cut out (even-odd).
Fill white; the row tints it.
"""

import importlib.util
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("gen_brand_icons", HERE / "gen_brand_icons.py")
_icons = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_icons)
normalise = _icons.normalise

BOX = 24.0
MARK = 20.0
RENDER_W = 4000

# drawable -> (file, source line for the header comment)
MARKS = {
    "rv_brand_xbox": ("bootstrap/xbox.svg", "Bootstrap Icons 1.13.2 \"xbox\" (MIT"),
    "rv_brand_microsoft": ("bootstrap/microsoft.svg", "Bootstrap Icons 1.13.2 \"microsoft\" (MIT"),
    "rv_brand_openai": ("bootstrap/openai.svg", "Bootstrap Icons 1.13.2 \"openai\" (MIT"),
    "rv_brand_nintendo": ("commons/Nintendo.svg", "Wikimedia Commons \"File:Nintendo.svg\" (PD-textlogo"),
    "rv_brand_blizzard": ("commons/Blizzard_Entertainment_Logo_2015.svg", "Wikimedia Commons \"File:Blizzard Entertainment Logo 2015.svg\" (PD-textlogo"),
    "rv_brand_disneyplus": ("commons/Disney+_2024.svg", "Wikimedia Commons \"File:Disney+ 2024.svg\" (PD-textlogo"),
    "rv_brand_primevideo": ("commons/Prime_Video_logo_(2024).svg", "Wikimedia Commons \"File:Prime Video logo (2024).svg\" (PD-textlogo"),
}


def view_box(text: str) -> tuple[float, float, float, float]:
    m = re.search(r'viewBox="([^"]+)"', text)
    x, y, w, h = (float(v) for v in m.group(1).replace(",", " ").split())
    return x, y, w, h


def ink_bounds(svg: Path, box: tuple[float, float, float, float]) -> tuple[float, float, float, float]:
    """The drawn ink's bounds in the SVG's own units: rendered RENDER_W px wide, alpha above 0."""
    with tempfile.TemporaryDirectory() as tmp:
        png = Path(tmp) / "ink.png"
        subprocess.run(["rsvg-convert", "-w", str(RENDER_W), "-o", str(png), str(svg)], check=True)
        im = Image.open(png).convert("RGBA")
    left, top, right, bottom = im.split()[3].getbbox()
    k = box[2] / im.width
    return box[0] + left * k, box[1] + top * k, box[0] + right * k, box[1] + bottom * k


# aapt's string pool holds a string of at most 32767 units; a longer pathData is read back garbled.
MAX_PATH = 32767


def compact(d: str) -> str:
    """normalise()'s output without the space before a command or a minus sign, which no parser needs."""
    return re.sub(r" (?=[A-Za-z-])", "", d)


def paths(name: str, text: str) -> tuple[list[tuple[str, str]], float]:
    """The mark's (pathData, fillType) list and the source's own translateY (Nintendo's layer)."""
    ds = re.findall(r'<path\b[^>]*?\sd="([^"]+)"', text, re.S)
    if name == "rv_brand_nintendo":
        rect = re.search(r'<rect\b[^>]*?y="([^"]+)"[^>]*?x="([^"]+)"[^>]*?height="([^"]+)"[^>]*?width="([^"]+)"', text, re.S)
        y, x, h, w = (float(v) for v in rect.groups())
        shift = float(re.search(r'transform="translate\(0,([^)]+)\)"', text).group(1))
        plate = f"M{x} {y} h{w} v{h} h{-w} z "
        assert len(ds) == 1, name
        return [(compact(plate + normalise(ds[0])), "evenOdd")], shift
    if name == "rv_brand_disneyplus":
        layer = text.split('<g id="Layer_1">')[1].split('<g id="Layer_2"')[0]
        ds = re.findall(r'<path\b[^>]*?\sd="([^"]+)"', layer, re.S)
    return [(compact(normalise(d)), "nonZero") for d in ds], 0.0


def placement(bounds: tuple[float, float, float, float]) -> tuple[float, float, float]:
    """Scale and translation that put the ink's longer side at MARK units, centred in BOX."""
    x0, y0, x1, y1 = bounds
    s = MARK / max(x1 - x0, y1 - y0)
    tx = (BOX - s * (x1 - x0)) / 2 - s * x0
    ty = (BOX - s * (y1 - y0)) / 2 - s * y0
    return s, tx, ty


def drawable(source: str, s: float, tx: float, ty: float, shift: float, marks: list[tuple[str, str]]) -> str:
    body = "".join(PATH.format(d=d, fill=fill) for d, fill in marks)
    inner = INNER.format(shift=f"{shift:.5f}", body=body) if shift else body
    return TEMPLATE.format(source=source, s=f"{s:.6f}", tx=f"{tx:.5f}", ty=f"{ty:.5f}", body=inner)


def main() -> None:
    src, res = Path(sys.argv[1]), Path(sys.argv[2])
    for name, (file, source) in MARKS.items():
        svg = src / file
        text = svg.read_text()
        bounds = ink_bounds(svg, view_box(text))
        marks, shift = paths(name, text)
        assert all(len(d) <= MAX_PATH for d, _ in marks), name
        s, tx, ty = placement(bounds)
        (res / f"{name}.xml").write_text(drawable(source, s, tx, ty, shift, marks))
        print(name, [round(v, 3) for v in bounds], round(s, 6), len(marks))


PATH = """
    <path
        android:fillColor="@android:color/white"
        android:fillType="{fill}"
        android:pathData="{d}" />"""

INNER = """
    <group android:translateY="{shift}">{body}
    </group>"""

TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<!-- {source}; see app/src/main/BRAND_ICONS.md),
     by tools/gen_brand_marks.py. Scaled so the mark's longer side is 20 of the 24 units, centred: it draws 20 dp of the
     24 dp glyph as the Simple Icons marks do. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
  <group
      android:scaleX="{s}"
      android:scaleY="{s}"
      android:translateX="{tx}"
      android:translateY="{ty}">{body}
  </group>
</vector>
"""


if __name__ == "__main__":
    main()
