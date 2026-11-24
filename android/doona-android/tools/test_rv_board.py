"""Tests of rv_board.py: which measurements turn red, and what the sheets, index and manifest hold.

uv run --no-project --with pillow --with pytest python -I -m pytest tools/test_rv_board.py
"""

import json
import sys
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))

import pytest
import rv_board

DEFAULT = {"default": {"rev": "3a2940ea", "why": "every page"}, "revs": []}


def rule(kind, what, actual, expected, at, token=True):
    off = expected is not None and abs(actual - expected) > 0.5
    return {"kind": kind, "what": what, "actual": actual, "expected": expected, "off": off, "token": token, "at": at}


def layout(rules, drift=(), height=915.0, scrolled=0.0):
    return {
        "name": "x",
        "top": "Title",
        "bar": 92.0,
        "scrolled": scrolled,
        "width": 412.0,
        "height": height,
        "tokens": [4.0, 16.0, 24.0],
        "parts": [{"tag": "rv-card", "rect": [16, 200, 396, 300]}, {"tag": "rv-card", "rect": [16, 316, 396, 400]}],
        "blocks": [],
        "rules": rules,
        "drift": list(drift),
        "nodes": [{"rect": [16, 200, 396, 300], "tag": None, "text": "版本", "click": False}],
        "baselines": [{"y": 230.0, "x0": 32.0, "x1": 80.0}],
    }


def write(directory: Path, name: str, data: dict, colour=(250, 250, 250)) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", (int(data["width"]) * 2, int(data["height"]) * 2), colour).save(directory / f"{name}.png")
    (directory / f"{name}.json").write_text(json.dumps(data))


def test_a_gap_on_its_token_is_not_red():
    assert rv_board.marks(layout([rule("Gap", "gap rv-card to rv-card at 316 dp", 16.0, 16.0, [16, 300, 396, 316])])) == []


def test_a_gap_that_is_no_token_is_red_even_where_no_ruler_expects_one():
    found = rv_board.marks(layout([rule("Gap", "gap a to b", 13.0, None, [16, 300, 396, 313], token=False)]))
    assert [m.kind for m in found] == ["Gap"]
    assert "13 dp is no spacing token" in found[0].text


def test_a_gap_that_is_a_token_but_not_the_expected_one_is_red():
    found = rv_board.marks(layout([rule("Gap", "gap card to section", 16.0, 24.0, [16, 300, 396, 316])]))
    assert [m.text for m in found] == ["gap card to section: 16 dp, expected 24"]


def test_an_edge_off_its_keyline_by_more_than_half_a_dp_is_red():
    assert rv_board.marks(layout([rule("Edge", "card's start", 16.5, 16.0, [16.5, 0, 396, 50])])) == []
    found = rv_board.marks(layout([rule("Edge", "card's start", 17.0, 16.0, [17, 0, 396, 50])]))
    assert found[0].kind == "Edge" and found[0].at == (17, 0, 396, 50)


def test_rules_outside_the_window_are_left_to_the_other_screen():
    off_screen = rule("Edge", "card's start", 20.0, 16.0, [20, 1000, 396, 1100])
    assert rv_board.marks(layout([off_screen])) == []


def test_a_drift_no_ruler_holds_is_listed_once():
    edge = rule("Edge", "card's start at 200 dp", 20.0, 16.0, [20, 200, 396, 300])
    found = rv_board.marks(layout([edge], drift=["card's start at 200 dp: 20.0 dp, the tokens say 16.0 dp", "no large title"]))
    assert [m.kind for m in found] == ["Edge", "Drift"]


def test_build_draws_four_columns_against_the_approved_and_two_for_a_new_page(tmp_path):
    now, approved, out = tmp_path / "now", tmp_path / "approved", tmp_path / "out"
    red = layout([rule("Gap", "gap a to b", 13.0, None, [16, 300, 396, 313], token=False)])
    for theme in rv_board.THEMES:
        write(now, f"about-{theme}-1", red)
        write(approved, f"about-{theme}-1", red, colour=(240, 240, 240))
        write(now, f"fresh-{theme}-1", layout([]))
        write(now, f"fresh-{theme}-2", layout([], scrolled=723.0))
    pages = [{"page": "關於", "state": "正常", "screen": "about"}, {"page": "新頁", "state": "正常", "screen": "fresh"}]
    entries = rv_board.build(pages, now, {"3a2940ea": approved}, DEFAULT, out)
    assert [(e["screen"], e["theme"], e["window"]) for e in entries] == [
        ("about", "light", 1),
        ("about", "dark", 1),
        ("fresh", "light", 1),
        ("fresh", "light", 2),
        ("fresh", "dark", 1),
        ("fresh", "dark", 2),
    ]
    about, fresh = entries[0], entries[2]
    assert about["red"] == 1 and about["approved"] == "3a2940ea" and about["diff_px"] == 824 * 1830
    assert fresh["red"] == 0 and fresh["approved"] is None and fresh["diff_px"] is None and fresh["baseline"] == "3a2940ea"
    wide = Image.open(out / "about-light-1.webp")
    narrow = Image.open(out / "fresh-light-1.webp")
    assert wide.width == 2 * rv_board.MARGIN + 4 * 824 + 3 * rv_board.GAP
    assert narrow.width == 2 * rv_board.MARGIN + 2 * 824 + rv_board.GAP
    assert (out / "index.webp").is_file()
    manifest = json.loads((out / "manifest.json").read_text())
    assert manifest["baselines"] == DEFAULT and len(manifest["sheets"]) == 6 and manifest["missing"] == []


def test_a_page_with_no_capture_is_listed_as_missing(tmp_path):
    pages = [{"page": "無", "state": "正常", "screen": "none"}]
    entries = rv_board.build(pages, tmp_path / "now", {"3a2940ea": tmp_path / "approved"}, DEFAULT, tmp_path / "out")
    assert entries == []
    assert json.loads((tmp_path / "out" / "manifest.json").read_text())["missing"] == ["none-light", "none-dark"]


def test_redlines_keep_the_window_size():
    window = rv_board.Window(
        "x", Image.new("RGB", (824, 1830), (255, 255, 255)), layout([rule("Gap", "g", 13.0, None, [16, 300, 396, 313], token=False)])
    )
    window.marks = rv_board.marks(window.layout)
    drawn = rv_board.redlines(window, rv_board.font(22))
    assert drawn.size == (824, 1830)
    assert drawn.getpixel(((16 * 2), 10)) != (255, 255, 255)


def test_the_header_top_is_drawn_from_the_bar():
    top = rule("Top", "header's top", 106.0, 106.0, [16, 106, 396, 136])
    window = rv_board.Window("x", Image.new("RGB", (824, 1830), (255, 255, 255)), layout([top]))
    drawn = rv_board.redlines(window, rv_board.font(22))
    x = (16 + 48) * 2
    assert drawn.getpixel((x, 99 * 2)) == rv_board.BLUE


def test_the_index_has_a_line_per_page_state_in_columns():
    entries = [
        {"page": f"頁{i}", "state": "正常", "theme": theme, "window": 1, "red": i % 2, "approved": None}
        for i in range(rv_board.INDEX_ROWS + 1)
        for theme in rv_board.THEMES
    ]
    image = rv_board.index_sheet(entries)
    assert image.width == 2 * rv_board.MARGIN + 2 * rv_board.INDEX_COLUMN
    assert image.height < 16383


def test_a_long_page_gets_a_sheet_for_every_window(tmp_path):
    now, out = tmp_path / "now", tmp_path / "out"
    for theme in rv_board.THEMES:
        for n in (1, 2, 3, 4):
            write(now, f"long-{theme}-{n}", layout([], scrolled=723.0 * (n - 1)))
    entries = rv_board.build([{"page": "長", "state": "正常", "screen": "long"}], now, {"3a2940ea": tmp_path / "a"}, DEFAULT, out)
    assert [e["window"] for e in entries if e["theme"] == "light"] == [1, 2, 3, 4]
    assert (out / "long-dark-4.webp").is_file()


def test_each_page_compares_with_its_own_baseline_under_its_name_there(tmp_path):
    now, old, later, out = tmp_path / "now", tmp_path / "old", tmp_path / "later", tmp_path / "out"
    for theme in rv_board.THEMES:
        for directory, screen in ((now, "plain"), (old, "plain"), (now, "fixed"), (later, "fixed"), (now, "renamed"), (later, "renamed-b")):
            write(directory, f"{screen}-{theme}-1", layout([]))
    baselines = {
        "default": {"rev": "old", "why": "the rest"},
        "revs": [{"rev": "later", "why": "reviewed later", "screens": ["fixed", {"screen": "renamed", "was": "renamed-b"}, "gone"]}],
    }
    pages = [{"page": p, "state": "正常", "screen": p} for p in ("plain", "fixed", "renamed")]
    entries = rv_board.build(pages, now, {"old": old, "later": later}, baselines, out)
    light = {e["screen"]: e for e in entries if e["theme"] == "light"}
    assert {s: (e["baseline"], e["approved"]) for s, e in light.items()} == {
        "plain": ("old", "old"),
        "fixed": ("later", "later"),
        "renamed": ("later", "later"),
    }
    manifest = json.loads((out / "manifest.json").read_text())
    assert manifest["baselines"] == baselines and manifest["baseline_unused"] == ["gone"]


def test_a_baseline_with_no_captures_or_a_screen_named_twice_is_an_error(tmp_path):
    pages = [{"page": "p", "state": "正常", "screen": "p"}]
    later = {"default": {"rev": "old", "why": ""}, "revs": [{"rev": "later", "why": "", "screens": ["p"]}]}
    with pytest.raises(rv_board.BaselineError, match="no approved captures for later"):
        rv_board.build(pages, tmp_path, {"old": tmp_path}, later, tmp_path / "out")
    twice = {**later, "revs": [*later["revs"], {"rev": "old", "why": "", "screens": [{"screen": "p", "was": "q"}]}]}
    with pytest.raises(rv_board.BaselineError, match="p is under later and old"):
        rv_board.baseline_of(twice)
    with pytest.raises(rv_board.BaselineError):
        rv_board.approved_dirs(["later"])
    assert rv_board.approved_dirs(["a=x", "b=y"]) == {"a": Path("x"), "b": Path("y")}


def test_the_map_in_the_tree_names_each_screen_once_and_every_screen_is_on_the_board():
    tools = Path(__file__).parent
    baselines = json.loads((tools / "rv_board_baselines.json").read_text())
    screens = {p["screen"] for p in json.loads((tools / "rv_board_pages.json").read_text())}
    named = rv_board.baseline_of(baselines)
    # 服務規則 is named as it is after owner-fix3 renames it (rules-service-b at 4dcd0f57).
    assert named.keys() - screens <= {"rules-service"}
    assert "welcome" in screens
