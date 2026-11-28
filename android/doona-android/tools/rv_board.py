#!/usr/bin/env python3
"""The design board: one sheet per RV page, state, theme and screen, with redlines drawn from the page's real bounds.

    uv run --no-project --with pillow python -I tools/rv_board.py PAGES NOW [--baselines MAP] [--approved REV=DIR ...] [--out DIR]

NOW (and each approved DIR) hold what a board run of the shot tests writes (`./gradlew -Ddoona.board=<dir>
:app:testDebugUnitTest`, see RvBoard.kt): `<screen>-<theme>-<n>.png` and `.json` per window, at 2 px per dp, every
window of a long page. Both current and approved captures use `app-rv/` and `proto-rv/`; the board uses the
wired state when both exist. PAGES is a JSON list of {"page", "state", "screen"} (tools/rv_board_pages.json). MAP
(tools/rv_board_baselines.json) names the revision each page compares with: the version the owner last reviewed, a
default and the revisions of later reviews with their screens; each revision a page uses needs its captures as
`--approved REV=DIR`. For every page, theme (light, dark) and window, OUT (default build/design-board) gets
`<screen>-<theme>-<n>.webp`: the screen; the same screen with redlines (the 4 dp grid, the side keylines, every outlined
node and library part, text baselines, each gap between blocks in dp); then, where the page's approved DIR has the same
window, the approved version and the changed pixels in red over a faded screen, or "new" in the header. An edge off its keyline by more than 0.5 dp, a gap that is not a spacing token or not the one the rulers
expect, a row or control off its height and a header off its top are drawn in red and listed under the sheet. OUT also
gets index.webp (every page state with its red marks in light and dark and its baseline, in columns) and manifest.json,
which also holds the baseline map.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
from dataclasses import dataclass, field
from functools import cache
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

PX = 2  # pixels per dp in the captures
THEMES = ("light", "dark")
GUTTER = 16.0
TOLERANCE = 0.5
WHITE = (255, 255, 255)
INK = (40, 40, 44)
MUTED = (120, 120, 128)
RED = (225, 30, 30)
BLUE = (20, 110, 230)
PART = (150, 60, 220)
NODE = (40, 150, 200)
BASELINE = (240, 140, 0)
GRID = (0, 160, 200, 28)
GAP = 32  # px between columns
MARGIN = 32  # px around the sheet
HEADER = 64  # px for the sheet's header line
INDEX_ROWS = 60  # page states per column of the index
INDEX_COLUMN = 900  # px per column of the index
INDEX_COUNTS = 560  # px from a column's start to its counts
INDEX_LINE = 34  # px per line of the index


@dataclass
class Mark:
    """One redline the rulers flag: what it is, the text listed under the sheet, and where it is drawn (dp)."""

    kind: str
    text: str
    at: tuple[float, float, float, float] | None


@dataclass
class Window:
    """One captured window: its image and layout, and the marks found on it."""

    name: str
    image: Image.Image
    layout: dict
    marks: list[Mark] = field(default_factory=list)
    screen: Image.Image | None = None


class BaselineError(ValueError):
    """A baseline map that names a screen twice, or a page whose revision has no captures."""


@dataclass(frozen=True)
class Baseline:
    """The revision a page compares with, and the page's screen name in that revision's captures."""

    rev: str
    screen: str


def baseline_of(baselines: dict) -> dict[str, Baseline]:
    """
    Each screen the map names, with its baseline. A screen is its name, or {"screen", "was"} where it had another name at
    that revision. A screen named twice is an error.
    """
    found: dict[str, Baseline] = {}
    for entry in baselines["revs"]:
        for item in entry["screens"]:
            screen, was = (item, item) if isinstance(item, str) else (item["screen"], item["was"])
            if screen in found:
                raise BaselineError(f"{screen} is under {found[screen].rev} and {entry['rev']}")
            found[screen] = Baseline(entry["rev"], was)
    return found


def baseline_for(baselines: dict, screen: str) -> Baseline:
    """The baseline [screen] compares with: its own in the map, else the map's default under the same name."""
    return baseline_of(baselines).get(screen, Baseline(baselines["default"]["rev"], screen))


@cache
def font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    """The system's sans-serif face for Traditional Chinese, or Pillow's own when fontconfig has none."""
    found = shutil.which("fc-match")
    if found:
        path = subprocess.run([found, "-f", "%{file}", "sans-serif:lang=zh-tw"], capture_output=True, text=True, check=False).stdout.strip()
        if path:
            return ImageFont.truetype(path, size)
    return ImageFont.load_default(size)


def visible(at: list[float], height: float) -> bool:
    """Whether a box (dp) shows in the window at all: a scrolled page also lays out parts above and below it."""
    return at[3] >= 0 and at[1] <= height


def fmt(value: float) -> str:
    return f"{value:.1f}".rstrip("0").rstrip(".")


def marks(layout: dict) -> list[Mark]:
    """The red marks of one window: every ruler off its token, every gap that is no spacing token, and drifts no ruler holds."""
    height = layout["height"]
    found: list[Mark] = []
    for rule in layout["rules"]:
        at = rule["at"]
        if not visible(at, height):
            continue
        actual, expected = rule["actual"], rule["expected"]
        if rule["kind"] == "Gap" and not rule["token"]:
            want = f", the rulers expect {fmt(expected)}" if expected is not None else ""
            found.append(Mark("Gap", f"{rule['what']}: {fmt(actual)} dp is no spacing token{want}", tuple(at)))
        elif rule["off"]:
            found.append(Mark(rule["kind"], f"{rule['what']}: {fmt(actual)} dp, expected {fmt(expected)}", tuple(at)))
    held = {r["what"] for r in layout["rules"] if r["off"]}
    for line in layout["drift"]:
        if not any(line.startswith(what + ":") for what in held):
            found.append(Mark("Drift", line, None))
    return found


def load(directory: Path, name: str) -> Window | None:
    png, js = directory / f"{name}.png", directory / f"{name}.json"
    if not png.is_file() or not js.is_file():
        return None
    layout = json.loads(js.read_text())
    screen = directory / f"{name}-screen.png"
    return Window(name, Image.open(png).convert("RGB"), layout, marks(layout), Image.open(screen).convert("RGB") if screen.is_file() else None)


def box(at, inset: float = 0) -> list[float]:
    return [at[0] * PX + inset, at[1] * PX + inset, at[2] * PX - inset, at[3] * PX - inset]


def grid(size: tuple[int, int]) -> Image.Image:
    """The 4 dp grid, faint."""
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    for x in range(0, size[0], 4 * PX):
        draw.line([(x, 0), (x, size[1])], fill=GRID)
    for y in range(0, size[1], 4 * PX):
        draw.line([(0, y), (size[0], y)], fill=GRID)
    return layer


def redlines(window: Window, label: ImageFont.ImageFont) -> Image.Image:
    """The window faded, with the grid, keylines, outlines, baselines, gaps and the red marks over it."""
    base = Image.blend(window.image, Image.new("RGB", window.image.size, WHITE), 0.45).convert("RGBA")
    base.alpha_composite(grid(base.size))
    draw = ImageDraw.Draw(base)
    layout = window.layout
    width, height = layout["width"], layout["height"]
    for x in (GUTTER, width - GUTTER):
        draw.line([(x * PX, 0), (x * PX, height * PX)], fill=BLUE, width=1)
    draw.text((2, 2), fmt(GUTTER), fill=BLUE, font=label)
    draw.text(((width - GUTTER) * PX + 2, 2), fmt(GUTTER), fill=BLUE, font=label)
    for node in layout["nodes"]:
        draw.rectangle(box(node["rect"]), outline=NODE, width=1)
    for part in layout["parts"]:
        if visible(part["rect"], height):
            draw.rectangle(box(part["rect"]), outline=PART, width=2)
    for line in layout["baselines"]:
        draw.line([(line["x0"] * PX, line["y"] * PX), (line["x1"] * PX, line["y"] * PX)], fill=BASELINE, width=1)
    red = {m.at for m in window.marks if m.at is not None}
    for rule in layout["rules"]:
        if rule["kind"] == "Gap" and visible(rule["at"], height):
            gap_line(draw, rule, RED if tuple(rule["at"]) in red else BLUE, label)
        elif rule["kind"] == "Top":
            # The header's top, or a tab's first content, measured from the bar's bottom.
            at = rule["at"]
            under_bar = {**rule, "actual": at[1] - layout["bar"], "at": [at[0], layout["bar"], at[2], at[1]]}
            gap_line(draw, under_bar, RED if tuple(at) in red else BLUE, label)
    for mark in window.marks:
        if mark.at is not None and mark.kind != "Gap":
            draw.rectangle(box(mark.at), outline=RED, width=3)
            draw.text((mark.at[0] * PX + 6, mark.at[1] * PX + 4), mark.text.rsplit(": ", 1)[-1], fill=RED, font=label)
    return base.convert("RGB")


def gap_line(draw: ImageDraw.ImageDraw, rule: dict, colour, label) -> None:
    """A gap as a dimension line down the strip between two blocks, labelled in dp."""
    left, top, right, bottom = rule["at"]
    x = (left + min(48.0, (right - left) / 2)) * PX
    y0, y1 = top * PX, bottom * PX
    draw.line([(x, y0), (x, y1)], fill=colour, width=2)
    for y in (y0, y1):
        draw.line([(x - 8, y), (x + 8, y)], fill=colour, width=2)
    draw.text((x + 10, (y0 + y1) / 2 - 12), fmt(rule["actual"]), fill=colour, font=label)


def changes(now: Image.Image, before: Image.Image) -> tuple[Image.Image, int]:
    """The pixels that differ, red over the faded screen, and how many."""
    size = (max(now.width, before.width), max(now.height, before.height))
    a, b = Image.new("RGB", size, WHITE), Image.new("RGB", size, WHITE)
    a.paste(now)
    b.paste(before)
    mask = ImageChops.difference(a, b).convert("L").point(lambda v: 255 if v else 0)
    faded = Image.blend(a, Image.new("RGB", size, WHITE), 0.7)
    faded.paste(Image.new("RGB", size, RED), mask=mask)
    return faded, mask.histogram()[255]


@dataclass
class Sheet:
    """One drawn sheet and what the manifest says of it."""

    image: Image.Image
    entry: dict


def sheet(page: dict, theme: str, index: int, now: Window, approved: Window | None, rev: str) -> Sheet:
    """Screen, redlines and, where there is one, the approved window at [rev] and the red diff, over the list of red marks."""
    head, label, small = font(30), font(22), font(24)
    panels = [("完整畫面", now.screen), ("底頁標線", redlines(now, label))] if now.screen else [("畫面", now.image), ("標線", redlines(now, label))]
    diff_px = None
    if approved is not None:
        before = approved.screen or approved.image
        diff, diff_px = changes(now.screen or now.image, before)
        panels += [(f"核准 {rev}", before), ("差異", diff)]
    width = MARGIN * 2 + sum(p.width for _, p in panels) + GAP * (len(panels) - 1)
    lines = [m.text for m in now.marks] or ["無紅標"]
    panel_height = max(p.height for _, p in panels)
    height = MARGIN * 2 + HEADER + 40 + panel_height + 24 + 32 * len(lines)
    image = Image.new("RGB", (width, height), WHITE)
    draw = ImageDraw.Draw(image)
    title = f"{page['page']} / {page['state']} / {theme} / {index}/{page['screens']}"
    if approved is None:
        title += " / new"
    title += f" / 紅標 {len(now.marks)}"
    draw.text((MARGIN, MARGIN), title, fill=INK, font=head)
    x, y = MARGIN, MARGIN + HEADER
    for caption, panel in panels:
        draw.text((x, y), caption, fill=MUTED, font=small)
        image.paste(panel, (x, y + 40))
        x += panel.width + GAP
    y += 40 + panel_height + 24
    for line in lines:
        draw.text((MARGIN, y), line, fill=RED if now.marks else MUTED, font=small)
        y += 32
    entry = {
        "page": page["page"],
        "state": page["state"],
        "screen": page["screen"],
        "theme": theme,
        "window": index,
        "file": f"{now.name}.webp",
        "red": len(now.marks),
        "marks": [m.text for m in now.marks],
        "baseline": rev,
        "approved": rev if approved is not None else None,
        "diff_px": diff_px,
        "top": now.layout["top"],
        "scrolled": now.layout["scrolled"],
    }
    return Sheet(image, entry)


def index_sheet(entries: list[dict], default: str | None = None) -> Image.Image:
    """
    Every page state, a line each, with its red marks in light and in dark (all its windows), in columns; "new" where a
    window has no approved one, and the baseline where it is not the [default].
    """
    head, row = font(30), font(24)
    pages: dict[tuple[str, str], dict] = {}
    for e in entries:
        line = pages.setdefault((e["page"], e["state"]), {"light": 0, "dark": 0, "new": False, "rev": e.get("baseline")})
        line[e["theme"]] += e["red"]
        line["new"] |= e["approved"] is None
    total = sum(e["red"] for e in entries)
    rows = INDEX_ROWS
    columns = max(1, -(-len(pages) // rows))
    shown = min(rows, len(pages))
    image = Image.new("RGB", (MARGIN * 2 + INDEX_COLUMN * columns, MARGIN * 2 + HEADER + 40 + INDEX_LINE * shown), WHITE)
    draw = ImageDraw.Draw(image)
    draw.text((MARGIN, MARGIN), f"設計板 / {len(pages)} 個頁面狀態 / {len(entries)} 張 / 紅標 {total}", fill=INK, font=head)
    for c in range(columns):
        draw.text((MARGIN + INDEX_COLUMN * c, MARGIN + HEADER), "頁面 / 狀態        light  dark", fill=MUTED, font=row)
    for i, ((page, state), line) in enumerate(pages.items()):
        x = MARGIN + INDEX_COLUMN * (i // rows)
        y = MARGIN + HEADER + 40 + INDEX_LINE * (i % rows)
        red = line["light"] + line["dark"]
        draw.text((x, y), f"{page} / {state}", fill=RED if red else INK, font=row)
        rev = f"  {line['rev']}" if line["rev"] and line["rev"] != default else ""
        draw.text(
            (x + INDEX_COUNTS, y),
            f"{line['light']:>5}  {line['dark']:>5}{'  new' if line['new'] else ''}{rev}",
            fill=RED if red else INK,
            font=row,
        )
    return image


def windows(directory: Path, screen: str, theme: str):
    """Every window of one page and theme, `-1` on until the first one missing."""
    for source in (directory / "app-rv", directory / "proto-rv"):
        index = 1
        while window := load(source, f"{screen}-{theme}-{index}"):
            yield window
            index += 1
        if index > 1:
            return


def build(pages: list[dict], now_dir: Path, approved: dict[str, Path], baselines: dict, out: Path) -> list[dict]:
    """
    Draws every sheet into [out] with the index and the manifest; returns the manifest's entries. Each page compares with
    the captures in [approved] of its revision in [baselines].
    """
    bases = {page["screen"]: baseline_for(baselines, page["screen"]) for page in pages}
    if unknown := sorted({b.rev for b in bases.values()} - approved.keys()):
        raise BaselineError(f"no approved captures for {', '.join(unknown)}")
    out.mkdir(parents=True, exist_ok=True)
    entries = []
    missing = []
    for page in pages:
        base = bases[page["screen"]]
        for theme in THEMES:
            screens = list(windows(now_dir, page["screen"], theme))
            if not screens:
                missing.append(f"{page['screen']}-{theme}")
                continue
            baseline = windows(approved[base.rev], base.screen, theme)
            for n, window in enumerate(screens, start=1):
                before = next(baseline, None)
                drawn = sheet({**page, "screens": len(screens)}, theme, n, window, before, base.rev)
                drawn.image.save(out / drawn.entry["file"], "WEBP", quality=90, method=6)
                entries.append(drawn.entry)
    # Screens the map names that these pages do not have: a renamed or removed page, or a run over some pages only.
    unused = sorted(baseline_of(baselines).keys() - bases.keys())
    manifest = {"baselines": baselines, "sheets": entries, "missing": missing, "baseline_unused": unused}
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1) + "\n")
    index_sheet(entries, baselines["default"]["rev"]).save(out / "index.webp", "WEBP", quality=90, method=6)
    return entries


def approved_dirs(values: list[str]) -> dict[str, Path]:
    """`REV=DIR` pairs, each revision once."""
    found: dict[str, Path] = {}
    for value in values:
        rev, sep, path = value.partition("=")
        if not sep or not rev or not path or rev in found:
            raise BaselineError(f"--approved wants REV=DIR, each revision once: {value}")
        found[rev] = Path(path)
    return found


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("pages", type=Path)
    parser.add_argument("now", type=Path)
    parser.add_argument("--baselines", type=Path, default=Path(__file__).with_name("rv_board_baselines.json"))
    parser.add_argument("--approved", action="append", default=[], metavar="REV=DIR", help="the captures at one baseline revision")
    parser.add_argument("--out", type=Path, default=Path("build/design-board"))
    args = parser.parse_args()
    try:
        baselines = json.loads(args.baselines.read_text())
        entries = build(json.loads(args.pages.read_text()), args.now, approved_dirs(args.approved), baselines, args.out)
    except BaselineError as error:
        parser.error(str(error))
    print(f"{len(entries)} sheets, {sum(e['red'] for e in entries)} red marks, in {args.out}")


if __name__ == "__main__":
    main()
