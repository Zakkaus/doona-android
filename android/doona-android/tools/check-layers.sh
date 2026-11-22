#!/bin/sh
# Fails when the layers of app/src/main/kotlin/dev/doona/android leak into each other (ARCH D23):
#   - androidx.compose.material3 and designsystem.tokens appear only inside designsystem/ (designsystem.tokens also
#     inside the theme and tokens packages' own files, which are part of designsystem/);
#   - feature/ imports neither dev.doona.android.vpn nor dev.doona.android.data, and builds no interaction itself
#     (clickable, toggleable, selectable, BasicText belong to the design system's components);
#   - engine/ imports nothing from android.*.
# Override the scanned source root with $1 (the negative control points it at a planted tree).
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
src=${1:-$root/app/src/main/kotlin/dev/doona/android}
fail=0

# report <message> <grep -rn output>
report() {
    if [ -n "$2" ]; then
        printf '%s:\n%s\n' "$1" "$2" >&2
        fail=1
    fi
}

outside_designsystem=$(grep -rnE 'androidx\.compose\.material3|designsystem\.tokens' --include='*.kt' "$src" |
    grep -v "^$src/designsystem/" || true)
report 'material3 or designsystem.tokens outside designsystem/' "$outside_designsystem"

if [ -d "$src/feature" ]; then
    report 'feature/ imports vpn or data' "$(grep -rnE '^import dev\.doona\.android\.(vpn|data)\b' --include='*.kt' "$src/feature" || true)"
    report 'feature/ builds its own interaction' "$(grep -rnE '\b(clickable|toggleable|selectable|BasicText)\b' --include='*.kt' "$src/feature" || true)"
fi
if [ -d "$src/engine" ]; then
    report 'engine/ imports android.*' "$(grep -rnE '^import android\.' --include='*.kt' "$src/engine" || true)"
fi

[ "$fail" -eq 0 ] || exit 1
echo "check-layers: no layer leak under $src"
