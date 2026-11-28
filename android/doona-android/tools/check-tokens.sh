#!/bin/sh
# Fails when a Kotlin file outside the token files writes a dp, sp or colour literal (`16.dp`, `14.sp`, `Dp(...)`,
# `Color(0x...)`, `0xFF...`) or a literal alpha (`alpha = 0.7f`).
#
# The app (app/src/main/kotlin): the theme package names roles from the raw values in designsystem/tokens; components
# and screens read the roles. The one exception is the metrics of a single component: an
# `object Doona<Component>Defaults` in designsystem/components may hold its own dp literals (D17, layer 3).
#
# The RV library (app/src/main/kotlin/.../designsystem/rv) and the RV pages (app/src/debug/kotlin/.../prototype/rv):
# the token files are RvTokens.kt and the features' `Rv<Feature>Tokens.kt`. A component's own metrics may sit in its named object, `object Rv<Component>Size` or
# `RvMotion`, as the app's Defaults objects do. A value that is literal on purpose (the zero end of an interpolation,
# a gradient's transparent end) carries `// literal: <reason>` on its line.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
src="$root/app/src/main/kotlin"
rv="$src/dev/doona/android/designsystem/rv $root/app/src/debug/kotlin/dev/doona/android/prototype/rv"
pattern='[0-9]\.(dp|sp)\b|\.(dp|sp)\b[^(]|(^|[^A-Za-z_.])Dp\(|Color\(0x|\b0x[0-9A-Fa-f]{8}\b|TextUnit\(|\balpha[[:space:]]*=[[:space:]]*[0-9]'
exempt=$(mktemp)
trap 'rm -f "$exempt"' EXIT
# `file:line:` of every line of an exempt object, from its declaration to the closing brace in column 0.
find "$src" -path '*/designsystem/components/*' -name '*.kt' -exec awk '
    /^(internal )?object Doona[A-Za-z]*Defaults[ {]/ { inside = 1 }
    inside { print FILENAME ":" FNR ":" }
    /^}/ { inside = 0 }
' {} + > "$exempt"
# shellcheck disable=SC2086 # $rv is two directories
find $rv -name '*.kt' -exec awk '
    /^(internal )?object Rv[A-Za-z]*(Size|Motion)[ {]/ { inside = 1 }
    inside { print FILENAME ":" FNR ":" }
    /^}/ { inside = 0 }
' {} + >> "$exempt"
hits=$(
    grep -rnE "$pattern" --include='*.kt' "$src" | grep -vE '/designsystem/(tokens|rv)/' || true
    # shellcheck disable=SC2086
    grep -rnE "$pattern" --include='*.kt' $rv | grep -vE '/Rv[A-Za-z]*Tokens\.kt:' | grep -vE '// literal: [^ ]' || true
)
hits=$(printf '%s\n' "$hits" | grep -vF -f "$exempt" | sed '/^$/d' || true)
if [ -n "$hits" ]; then
    printf 'literal outside the token files:\n%s\n' "$hits" >&2
    exit 1
fi
echo "check-tokens: no dp, sp, colour or alpha literal outside designsystem/tokens and the RV token files, bar Defaults and Size objects and lines marked // literal"
"$root/tools/check-scale.sh"
