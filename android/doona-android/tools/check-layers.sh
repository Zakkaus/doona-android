#!/bin/sh
# Layer rules of ARCH D17, D18 and D23, checked on the imports and calls of app/src/main/kotlin:
#   - androidx.compose.material3 only inside designsystem/;
#   - designsystem.tokens only inside designsystem/tokens and designsystem/theme: components read the theme's roles;
#   - feature/ imports no vpn/ or data/ package, no component Defaults object, and writes no clickable, toggleable,
#     selectable or BasicText: every interactive element is a designsystem component;
#   - engine/ imports no android.* class;
#   - no Resources.getIdentifier anywhere in the app or its icon and flag modules: an icon or flag is named by a static
#     R.drawable field, so the release build's resource shrinker keeps the ones the code names and strips the rest of
#     the 4,000 symbols; a lookup by name would keep them all.
# Inside one module `internal` hides nothing, so imports are the only boundary there is.
# Override the scanned source root with $1 (the negative control points it at a planted tree).
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
src=${1:-$root/app/src/main/kotlin/dev/doona/android}
fail=0

# rule <description> <regex> <directory or file filter: grep -v pattern of allowed paths> <search dir>
rule() {
    hits=$(grep -rnE "$2" --include='*.kt' --include='*.java' "$4" 2>/dev/null | grep -vE "$3" || true)
    if [ -n "$hits" ]; then
        printf 'check-layers: %s\n%s\n' "$1" "$hits" >&2
        fail=1
    fi
}

rule 'androidx.compose.material3 outside designsystem/' 'androidx\.compose\.material3' '/designsystem/' "$src"
rule 'designsystem.tokens outside designsystem/tokens and designsystem/theme' 'designsystem\.tokens' '/designsystem/(tokens|theme)/' "$src"
rule 'feature/ imports vpn/ or data/' 'import dev\.doona\.android\.(vpn|data)\.' '^$' "$src/feature"
rule 'feature/ reaches into a component Defaults object' 'Defaults\b' '^$' "$src/feature"
rule 'feature/ builds its own interactive element' '\b(clickable|toggleable|selectable|combinedClickable|BasicText)\b' '^$' "$src/feature"
rule 'engine/ imports android.*' 'import android\.' '^$' "$src/engine"
if [ -n "${1:-}" ]; then
    lookups=$src
else
    lookups="$root/app/src $root/icons/src $root/flags/src"
fi
for dir in $lookups; do
    rule 'getIdentifier looks a resource up by name, so resource shrinking keeps every icon' 'getIdentifier[[:space:]]*\(' '^$' "$dir"
done

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "check-layers: material3, tokens, feature and engine boundaries hold, and no resource is looked up by name"
