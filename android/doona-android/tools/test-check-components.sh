#!/bin/sh
# The negative control of tools/check-components.sh: the gate must pass a page that composes the library, fail on a
# planted violation of each rule, and fail when the allowlist is above or below what a file measures or grew since a
# base revision.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
page="$tmp/app/src/debug/kotlin/dev/doona/android/prototype/rv"
mkdir -p "$page"
none="$tmp/none.allow"
: > "$none"
gate() { # <allowlist> [gate arguments]
    gate_allow=$1
    shift
    COMPONENTS_ROOT=$tmp COMPONENTS_ALLOW=$gate_allow "$here/check-components.sh" "$@" 2>&1
}

cat > "$page/RvCleanPage.kt" <<'KT'
@Composable
internal fun RvCleanPage() {
    // Text(" a comment may name Text( and 16.dp")
    RvText("clean", Rv.type.rowTitle, Modifier.padding(RvSpace.gutter).rvRowPress(Role.Button, {}))
}
KT
out=$(gate "$none") || { echo "FAIL: a clean page was refused: $out" >&2; exit 1; }
echo "ok: a clean page passes"

plant() { # <rule> <line>
    printf '@Composable\ninternal fun RvPlanted() {\n    %s\n}\n' "$2" > "$page/RvPlanted.kt"
    if out=$(gate "$none"); then echo "FAIL: a planted $1 violation passed: $2" >&2; exit 1; fi
    case $out in *"RvPlanted.kt: 1 $1 hit"*) echo "ok: a planted $1 violation fails" ;; *) echo "FAIL: $1 not named in: $out" >&2; exit 1 ;; esac
}
plant dp 'Box(Modifier.padding(13.dp))'
plant colour 'Box(Modifier.background(Color(0xFF123456)))'
plant colour 'Box(Modifier.background(Color.White))'
plant style 'RvText("x", TextStyle(fontSize = 15.sp))'
plant widget 'Text("a raw Text")'
plant widget 'Icon(painterResource(R.drawable.x), null)'
plant click 'Box(Modifier.clickable { })'
plant click 'Box(Modifier.selectable(true, onClick = { }))'
# A `//` inside a string literal starts no comment, so what follows the string is still read.
plant widget 'val note = "see https://doona.dev // the site"; Text("a raw Text")'
plant dp "val quote = '\"'; Box(Modifier.padding(13.dp))"
rm "$page/RvPlanted.kt"

# An allowlist line: at its measure it passes, below the measure it fails (new hit), above it fails (stale).
printf '@Composable\ninternal fun RvTwo() {\n    Text("a")\n    Text("b")\n}\n' > "$page/RvTwo.kt"
f=debug/kotlin/dev/doona/android/prototype/rv/RvTwo.kt
printf '%s|widget|2|two raw texts for the control\n' "$f" > "$tmp/exact.allow"
printf '%s|widget|1|one only\n' "$f" > "$tmp/low.allow"
printf '%s|widget|3|three\n' "$f" > "$tmp/high.allow"
printf '%s|widget|2|\n' "$f" > "$tmp/noreason.allow"
gate "$tmp/exact.allow" > /dev/null || { echo "FAIL: an allowlist at the measure was refused" >&2; exit 1; }
echo "ok: an allowlist at the measure passes"
for a in low high noreason; do
    if gate "$tmp/$a.allow" > /dev/null; then echo "FAIL: the $a allowlist passed" >&2; exit 1; fi
    echo "ok: the $a allowlist fails"
done

# The allowlist only shrinks: against the one at a base revision, an added line or a raised count fails, a lowered one
# passes. The fixture tree is its own repository, with exact.allow committed as the base.
git -C "$tmp" init -q
cp "$tmp/exact.allow" "$tmp/base.allow"
git -C "$tmp" add base.allow
git -C "$tmp" -c user.name=gate -c user.email=gate@invalid -c commit.gpgsign=false commit -q -m base
ratchet() { cp "$tmp/$1.allow" "$tmp/base.allow"; gate "$tmp/base.allow" --base-rev HEAD; }
printf '%s|widget|2|two raw texts for the control\n%s|dp|1|a new exception\n' "$f" "$f" > "$tmp/added.allow"
printf '@Composable\ninternal fun RvTwo() {\n    Text("a")\n    Text("b")\n    Box(Modifier.padding(13.dp))\n}\n' > "$page/RvTwo.kt"
if out=$(ratchet added); then echo "FAIL: an allowlist line not in the base passed" >&2; exit 1; fi
case $out in *"added to the allowlist, not in HEAD"*) echo "ok: an added allowlist line fails" ;; *) echo "FAIL: added line not named in: $out" >&2; exit 1 ;; esac
printf '@Composable\ninternal fun RvTwo() {\n    Text("a")\n    Text("b")\n    Text("c")\n}\n' > "$page/RvTwo.kt"
printf '%s|widget|3|three raw texts\n' "$f" > "$tmp/raised.allow"
if out=$(ratchet raised); then echo "FAIL: a count raised over the base passed" >&2; exit 1; fi
case $out in *"raised from 2 in HEAD to 3"*) echo "ok: a raised count fails" ;; *) echo "FAIL: raised count not named in: $out" >&2; exit 1 ;; esac
printf '@Composable\ninternal fun RvTwo() {\n    Text("a")\n}\n' > "$page/RvTwo.kt"
printf '%s|widget|1|one raw text\n' "$f" > "$tmp/lowered.allow"
out=$(ratchet lowered) || { echo "FAIL: a lowered count was refused: $out" >&2; exit 1; }
echo "ok: a lowered count passes"

"$here/check-components.sh" > /dev/null
echo "check-components negative control: the gate fails on each planted violation, passes a clean page, keeps the allowlist from growing, and the real tree passes"
