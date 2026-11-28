#!/bin/sh
# Fails when a spacing token (`object RvSpace`, `object RvSettingsSpace`) is not a step of the layout scale
# (docs/LAYOUT-RULES.md, "The spacing scale": 0, 4, 8, 12, 16, 24 dp) and is not a listed exception. A token takes a
# value outside the scale only with its reason in the rules file and its name here.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
files="$root/app/src/main/kotlin/dev/doona/android/designsystem/rv/RvTokens.kt $root/app/src/main/kotlin/dev/doona/android/designsystem/rv/RvSettingsTokens.kt"
scale=" 0 4 8 12 16 24 "
# Exceptions from LAYOUT-RULES.md, in dp.
exceptions=" subGap:6 titleToSubtitle:2 emptyLine:10 barGap:14 headerTop:152 heroToActions:64 actionColumn:96 titleToActions:40 accentInset:10 "
bad=$(
    for f in $files; do
        awk -v f="$f" '
            {
                if (comment) {
                    end = index($0, "*/"); if (!end) next
                    $0 = substr($0, end + 2); comment = 0
                }
                gsub(/\/\*([^*]|\*+[^*\/])*\*+\//, " ")
                sub(/\/\/.*/, "")
                if (sub(/\/\*.*/, "")) comment = 1
            }
            /^internal object Rv(Settings)?Space[ {]/ { inside = 1; next }
            /^}/ { inside = 0 }
            inside && $0 !~ /^[[:space:]]*$/ {
                decl = $0
                if (!sub(/^[[:space:]]*val[[:space:]]+/, "", decl) || decl !~ /^[[:alnum:]_]+[[:space:]]*(:[[:space:]]*Dp[[:space:]]*)?=[[:space:]]*[0-9]+\.dp[[:space:]]*$/) {
                    print "?", "unsupported-declaration:", f ":" FNR; next
                }
                name = decl; sub(/[^[:alnum:]_].*$/, "", name)
                sub(/^.*=[[:space:]]*/, "", decl); sub(/\.dp.*$/, "", decl); print name, decl, f
            }' "$f"
    done | while read -r name v f; do
        case "$exceptions" in
            *" $name:$v "*) continue ;;
            *" $name:"*) ;;
            *) case "$scale" in *" $v "*) continue ;; esac ;;
        esac
        echo "$f: $name = $v dp is not a step of the scale"
    done
)
if [ -n "$bad" ]; then
    printf '%s\n' "$bad" >&2
    exit 1
fi
echo "check-scale: every spacing token is a step of the scale or a listed exception"
