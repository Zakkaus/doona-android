#!/bin/sh
# Fails when a Kotlin file outside designsystem/tokens writes a dp, sp or colour literal (`16.dp`, `14.sp`, `Dp(...)`,
# `Color(0x...)`) or a literal alpha (`alpha = 0.7f`). The theme package names roles from the raw values in tokens;
# components and screens read the roles. The one exception is the metrics of a single component: an
# `object Doona<Component>Defaults` in designsystem/components may hold its own dp literals (D17, layer 3).
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
src="$root/app/src/main/kotlin"
exempt=$(mktemp)
trap 'rm -f "$exempt"' EXIT
# `file:line:` of every line of a Defaults object, from its declaration to the closing brace in column 0.
find "$src" -path '*/designsystem/components/*' -name '*.kt' -exec awk '
    /^(internal )?object Doona[A-Za-z]*Defaults[ {]/ { inside = 1 }
    inside { print FILENAME ":" FNR ":" }
    /^}/ { inside = 0 }
' {} + > "$exempt"
hits=$(grep -rnE '[0-9]\.(dp|sp)\b|\.(dp|sp)\b[^(]|(^|[^A-Za-z_.])Dp\(|Color\(0x|TextUnit\(|\balpha[[:space:]]*=[[:space:]]*[0-9]' --include='*.kt' "$src" | grep -vE '/designsystem/tokens/' | grep -vF -f "$exempt" || true)
if [ -n "$hits" ]; then
    printf 'literal outside designsystem/tokens:\n%s\n' "$hits" >&2
    exit 1
fi
echo "check-tokens: no dp, sp, colour or alpha literal outside $src/dev/doona/android/designsystem/tokens, bar component Defaults objects"
