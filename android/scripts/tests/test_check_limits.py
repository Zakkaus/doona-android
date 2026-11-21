import shutil
import subprocess
import sys
from pathlib import Path

import pytest

import check_limits

KT = "android/tunbench/app/src/main/kotlin/dev/tunbench"
RS = "crates/honk-android/src"


def kt_long(nloc):
    """A Kotlin function of nloc NLOC, with blank and comment lines that NLOC skips."""
    return "fun f() {\n" + "    val a = 1\n    // c\n\n" * (nloc - 2) + "}\n"


def kt_branchy(ccn):
    body = "".join(f"    if (x == {i}) return {i}\n" for i in range(ccn - 1))
    return f"fun g(x: Int): Int {{\n{body}    return 0\n}}\n"


@pytest.fixture
def tree(tmp_path):
    """A minimal compliant tree: one Kotlin and one Rust function, an empty ratchet, no Java."""
    write(tmp_path, f"{KT}/A.kt", "fun a() = 1\n")
    write(tmp_path, f"{RS}/lib.rs", "fn b() {}\n")
    write(tmp_path, check_limits.RATCHET, "# empty\n")
    return tmp_path


def write(root, rel, text):
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def run(root, *args):
    return check_limits.main(["--root", str(root), *args])


@pytest.mark.parametrize(
    ("rel", "text", "expected"),
    [
        (f"{KT}/B.kt", "\n" * 601, 1),
        (f"{KT}/B.kt", "\n" * 600, 0),
        ("android/tunbench/app/src/debug/java/Large.kt", "\n" * 601, 1),
        (f"{KT}/B.kt", kt_long(81), 1),
        (f"{KT}/B.kt", kt_long(80), 0),
        (f"{KT}/B.kt", kt_branchy(16), 1),
        (f"{KT}/B.kt", kt_branchy(15), 0),
        (
            f"{RS}/q.rs",
            "fn h() -> Option<u8> {\n" + "    let a = Some(1)?;\n" * 15 + "    Some(a)\n}\n",
            1,
        ),
        (f"{KT}/util/B.kt", "fun b() = 1\n", 1),
        (f"{KT}/utility.kt", "fun b() = 1\n", 0),
    ],
)
def test_limits(tree, rel, text, expected):
    write(tree, rel, text)
    assert run(tree) == expected


@pytest.mark.parametrize(
    ("ratchet", "expected"),
    [
        (f"ccn 16 {KT}/B.kt g x : Int", 0),
        (f"ccn 15 {KT}/B.kt g x : Int", 1),
        (f"ccn 17 {KT}/B.kt g x : Int", 1),
        (f"ccn 16 {KT}/B.kt g y : Int", 1),
        (f"ccn 16 {KT}/B.kt g x : Int\nccn 16 {KT}/B.kt g x : Int", 2),
        ("ccn sixteen x", 2),
    ],
)
def test_ratchet(tree, ratchet, expected):
    write(tree, f"{KT}/B.kt", kt_branchy(16))
    write(tree, check_limits.RATCHET, ratchet + "\n")
    assert run(tree) == expected


def test_ratchet_above_the_measurement_fails(tree, capsys):
    # A slack entry would let the code grow back to the old value unnoticed.
    write(tree, f"{KT}/B.kt", kt_branchy(16))
    write(tree, check_limits.RATCHET, f"ccn 17 {KT}/B.kt g x : Int\n")
    assert run(tree) == 1
    assert "lower the entry to 16" in capsys.readouterr().out


def test_ratchet_entry_within_the_limit_fails(tree, capsys):
    write(tree, f"{KT}/B.kt", kt_branchy(15))
    write(tree, check_limits.RATCHET, f"ccn 16 {KT}/B.kt g x : Int\n")
    assert run(tree) == 1
    assert "delete the line" in capsys.readouterr().out


def test_same_signature_is_ambiguous(tree, capsys):
    # One ratchet entry would cover both methods, so either could grow under the other's entry.
    body = "".join(f"        if x == {i} {{ return {i}; }}\n" for i in range(15))
    method = f"    fn g(x: i32) -> i32 {{\n{body}        0\n    }}\n"
    write(tree, f"{RS}/two.rs", "".join(f"struct {t};\nimpl {t} {{\n{method}}}\n" for t in "XY"))
    write(tree, check_limits.RATCHET, f"ccn 16 {RS}/two.rs g x : i32\n")
    assert run(tree) == 1
    assert "ambiguous, 2 functions" in capsys.readouterr().out


def git(root, *args):
    subprocess.run(
        ["git", "-C", str(root), "-c", "user.name=t", "-c", "user.email=t@t"]
        + ["-c", "core.hooksPath=/dev/null", "-c", "commit.gpgSign=false", *args],
        check=True,
        capture_output=True,
    )


@pytest.mark.parametrize(
    ("base", "head", "expected"),
    [
        (17, 16, 0),
        (16, 16, 0),
        (16, 17, 1),
        (None, 16, 1),
    ],
)
def test_base_ratchet(tree, base, head, expected):
    """The source and the change's own ratchet move together; only the base can refuse a raise."""
    entry = f"ccn {{}} {KT}/B.kt g x : Int\n"
    git(tree, "init", "-q")
    write(tree, check_limits.RATCHET, entry.format(base) if base else "# empty\n")
    git(tree, "add", "-A")
    git(tree, "commit", "-q", "-m", "base")
    write(tree, f"{KT}/B.kt", kt_branchy(head))
    write(tree, check_limits.RATCHET, entry.format(head))
    assert run(tree, "--base-rev", "HEAD") == expected


def test_base_without_ratchet_is_a_first_freeze(tree, capsys):
    git(tree, "init", "-q")
    write(tree, "README", "x\n")
    git(tree, "add", "README")
    git(tree, "commit", "-q", "-m", "base")
    assert run(tree, "--base-rev", "HEAD") == 0
    assert "first freeze" in capsys.readouterr().out
    assert run(tree, "--base-rev", "no-such-rev") == 2


@pytest.mark.parametrize("empty", ["android/tunbench/app/src", RS])
def test_empty_group_cannot_run(tree, empty):
    shutil.rmtree(tree / empty)
    assert run(tree) == 2


def test_unreadable_file_cannot_run(tree, capsys):
    write(tree, f"{KT}/C.kt", "fun c() = 1\n")
    (tree / KT / "C.kt").write_bytes(b"fun c() = \xff\n")
    assert run(tree) == 2
    assert "C.kt" in capsys.readouterr().err


def test_default_root_is_the_scripts_repository(tree, tmp_path_factory):
    script = tree / "android/scripts/check_limits.py"
    shutil.copy(Path(check_limits.__file__), script)
    elsewhere = tmp_path_factory.mktemp("cwd")
    result = subprocess.run([sys.executable, script], cwd=elsewhere, capture_output=True, text=True)
    assert result.returncode == 0
    assert f"root: {tree}" in result.stdout
