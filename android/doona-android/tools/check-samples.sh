#!/bin/sh
# Fails when a file in app/src/main/kotlin names a sample from app/src/debug/kotlin.
# main and debug compile together in the debug variants, so a main call that omits a parameter can bind a debug
# overload and render sample data without any error; the release build then fails far from the cause. The sample
# bindings in debug carry a `Sample` suffix (`RvActivityScreenSample`), so a name here is never a page's own.
# Flagged: every name debug declares that ends in `Sample` or starts with `rvSample`, `RV_SAMPLE` or `sample` + capital,
# and the sample data objects RvData and RvPolicyData. A main file may declare a name of its own that ends in
# `Sample` (a gallery section, a traffic sample): only a name debug declares is a reference to debug.
# Override the roots with $1 (main) and $2 (debug); the negative control points them at a planted tree.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
main=${1:-$root/app/src/main/kotlin}
debug=${2:-$root/app/src/debug/kotlin}

names=$(
    {
        printf '%s\n' RvData RvPolicyData
        grep -rhE '^[[:space:]]*(@[A-Za-z]+[[:space:]]+)*((internal|private|public|const|data|inline)[[:space:]]+)*(fun|val|var|object|class|interface|typealias)[[:space:]]' --include='*.kt' "$debug" |
            sed -E 's/^[[:space:]]*(@[A-Za-z]+[[:space:]]+)*((internal|private|public|const|data|inline)[[:space:]]+)*(fun|val|var|object|class|interface|typealias)[[:space:]]+(<[^>]*>[[:space:]]+)?([A-Za-z0-9_<>?]+\.)?([A-Za-z0-9_]+).*/\7/' |
            grep -E 'Sample$|^rvSample|^RV_SAMPLE|^sample[A-Z]'
    } | sort -u
)
# A name that main declares itself is main's own. Every declaration on a line counts, not only the last: in
# `private data class Sample(val row: RuleRow)` the class is main's own as much as its property.
own=$(
    grep -rhoE '(fun|val|var|object|class|interface|typealias)[[:space:]]+(<[^>]*>[[:space:]]+)?([A-Za-z0-9_<>?]+\.)?[A-Za-z0-9_]+' --include='*.kt' "$main" |
        sed -E 's/.*[^A-Za-z0-9_]([A-Za-z0-9_]+)$/\1/' | sort -u
)
fail=0
for name in $names; do
    if printf '%s\n' "$own" | grep -qx "$name"; then
        continue
    fi
    hits=$(grep -rnw --include='*.kt' -e "$name" "$main" || true)
    if [ -n "$hits" ]; then
        printf 'check-samples: src/main refers to %s, which only src/debug declares\n%s\n' "$name" "$hits" >&2
        fail=1
    fi
done
if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "check-samples: src/main names no sample of src/debug ($(printf '%s\n' "$names" | wc -l) names checked)"
