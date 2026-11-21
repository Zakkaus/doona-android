#!/bin/sh
# Fails when a Kotlin file outside the theme package writes a dp, sp or colour literal (`16.dp`, `14.sp`, `Dp(...)`,
# `Color(0x...)`). Screens take every value from dev.doona.android.theme.DoonaTheme.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
src="$root/app/src/main/kotlin"
hits=$(grep -rnE '[0-9]\.(dp|sp)\b|\.(dp|sp)\b[^(]|(^|[^A-Za-z_.])Dp\(|Color\(0x|TextUnit\(' --include='*.kt' "$src" | grep -v '/theme/' || true)
if [ -n "$hits" ]; then
    printf 'literal outside the theme package:\n%s\n' "$hits" >&2
    exit 1
fi
echo "check-tokens: no dp, sp or colour literal outside $src/dev/doona/android/theme"
