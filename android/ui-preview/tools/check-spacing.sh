#!/bin/sh
# Fails when a Kotlin file outside the theme package writes a dp literal (`16.dp`, `x.dp`, `Dp(...)`).
# Screens take spacing and sizes from dev.tunbench.preview.theme or from a component's own defaults.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
src="$root/app/src/main/kotlin"
hits=$(grep -rnE '\.dp\b|(^|[^A-Za-z_.])Dp\(' --include='*.kt' "$src" | grep -v '/theme/' || true)
if [ -n "$hits" ]; then
    printf 'dp literal outside the theme package:\n%s\n' "$hits" >&2
    exit 1
fi
echo "check-spacing: no dp literal outside $src/dev/tunbench/preview/theme"
