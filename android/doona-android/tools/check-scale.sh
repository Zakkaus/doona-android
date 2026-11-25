#!/bin/sh
# Fails when a spacing token (`object RvSpace`, `object RvSettingsSpace`) is not a step of the layout scale
# (docs/LAYOUT-RULES.md, "The spacing scale": 0, 4, 8, 12, 16, 24 dp) and is not a listed exception. A token takes a
# value outside the scale only with its reason in the rules file and its name here.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
files="$root/app/src/main/kotlin/dev/doona/android/designsystem/rv/RvTokens.kt $root/app/src/debug/kotlin/dev/doona/android/prototype/rv/settings/RvSettingsTokens.kt"
scale=" 0 4 8 12 16 24 "
# name: reason (LAYOUT-RULES.md, "Exceptions")
exceptions=" subGap emptyLine barGap headerTop heroToActions actionColumn titleToActions accentInset "
bad=$(
    for f in $files; do
        awk -v f="$f" '
            /^internal object Rv(Settings)?Space[ {]/ { inside = 1; next }
            /^}/ { inside = 0 }
            inside && /^[[:space:]]+val [A-Za-z]+ = [0-9.]+\.dp/ {
                name = $2; v = $4; sub(/\.dp.*/, "", v); print name, v, f
            }' "$f"
    done | while read -r name v f; do
        case "$scale" in *" $v "*) continue ;; esac
        case "$exceptions" in *" $name "*) continue ;; esac
        echo "$f: $name = $v dp is not a step of the scale"
    done
)
if [ -n "$bad" ]; then
    printf '%s\n' "$bad" >&2
    exit 1
fi
echo "check-scale: every spacing token is a step of the scale or a listed exception"
