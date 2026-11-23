#!/usr/bin/env python3
"""Before/after sheets of the RV prototype's goldens between two revisions, for a reviewer to look at.

    uv run --no-project --with pillow python -I tools/rv_sheet.py BASE [HEAD] [--out DIR] [--match GLOB]

Run from android/doona-android. BASE and HEAD are git revisions; HEAD defaults to the working tree. For each golden
under app/src/test/screenshots that matches GLOB (default proto-rv-*.png) and differs between the two, DIR/<name>.png
(default build/rv-sheet) holds before, after, and the changed pixels in red over a faded after, side by side at half
size, each headed with its height in dp (the goldens are 2 px per dp). DIR/index.txt lists the changed, added and
removed goldens with their heights.
"""

import argparse
import fnmatch
import io
import subprocess
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

SHOTS = "app/src/test/screenshots"
PX_PER_DP = 2
GAP = 24
HEAD_ROOM = 40
PAD = (232, 232, 236)
RED = (230, 40, 40)


def git(*args: str) -> bytes:
    return subprocess.run(["git", *args], check=True, capture_output=True).stdout


def listing(rev: str | None, pattern: str) -> dict[str, bytes]:
    """The goldens at [rev] (the working tree when None) whose names match [pattern], by name."""
    if rev is None:
        return {p.name: p.read_bytes() for p in sorted(Path(SHOTS).glob("*.png")) if fnmatch.fnmatch(p.name, pattern)}
    names = git("ls-tree", "--name-only", "--full-tree", f"{rev}:./{SHOTS}").decode().split()
    return {n: git("show", f"{rev}:./{SHOTS}/{n}") for n in names if fnmatch.fnmatch(n, pattern)}


def open_rgb(data: bytes) -> Image.Image:
    return Image.open(io.BytesIO(data)).convert("RGB")


def changes(before: Image.Image, after: Image.Image) -> Image.Image:
    """The after image faded, with every pixel that differs from before (or lies outside it) in red."""
    width, height = after.size
    base = Image.new("RGB", (width, height), PAD)
    base.paste(before, (0, 0))
    mask = ImageChops.difference(base, after).convert("L").point(lambda v: 255 if v > 8 else 0)
    if before.size[1] < height:
        mask.paste(255, (0, before.size[1], width, height))
    faded = Image.blend(after, Image.new("RGB", after.size, (255, 255, 255)), 0.7)
    faded.paste(Image.new("RGB", after.size, RED), (0, 0), mask)
    return faded


def sheet(name: str, before: Image.Image | None, after: Image.Image | None) -> Image.Image:
    panels = [("before", before), ("after", after)]
    if before is not None and after is not None:
        panels.append(("changed", changes(before, after)))
    shown = [(label, img.resize((img.width // 2, img.height // 2))) for label, img in panels if img is not None]
    width = sum(img.width for _, img in shown) + GAP * (len(shown) + 1)
    height = max(img.height for _, img in shown) + HEAD_ROOM + GAP
    out = Image.new("RGB", (width, height), (255, 255, 255))
    draw = ImageDraw.Draw(out)
    x = GAP
    for label, img in shown:
        draw.text((x, 8), f"{name} {label}: {img.height * 2 // PX_PER_DP} dp tall", fill=(0, 0, 0))
        out.paste(img, (x, HEAD_ROOM))
        x += img.width + GAP
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("base")
    parser.add_argument("head", nargs="?")
    parser.add_argument("--out", default="build/rv-sheet")
    parser.add_argument("--match", default="proto-rv-*.png")
    args = parser.parse_args()
    old = listing(args.base, args.match)
    new = listing(args.head, args.match)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    lines = []
    for name in sorted(old.keys() | new.keys()):
        if old.get(name) == new.get(name):
            continue
        before = open_rgb(old[name]) if name in old else None
        after = open_rgb(new[name]) if name in new else None
        state = "added" if before is None else "removed" if after is None else "changed"
        sizes = " -> ".join(f"{img.height // PX_PER_DP} dp" for img in (before, after) if img is not None)
        lines.append(f"{state:8} {name} ({sizes})")
        sheet(name.removesuffix(".png"), before, after).save(out / name)
    (out / "index.txt").write_text("\n".join(lines) + "\n")
    print(f"{len(lines)} goldens differ; sheets in {out}")


if __name__ == "__main__":
    main()
