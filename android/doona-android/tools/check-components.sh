#!/bin/sh
# The components gate (component-rule-1125 item 4): an RV page composes the library, it does not rebuild it.
#
# A page file is every Kotlin file under app/src/debug/kotlin/**/prototype/rv/** or app/src/main/kotlin/**/feature/**,
# apart from the token files `Rv*Tokens.kt`. It fails on a line of such a file that
#   dp      writes a dp or sp literal (`16.dp`, `Dp(`): spacing and size come from the library's tokens;
#   colour  writes a raw colour (`Color(0x..)`, `0xFF..`, `Color.White`, `MaterialTheme.`): colour comes from `Rv.colors`;
#   style   writes a text style (`TextStyle(`, `fontSize`, `FontWeight.`): type comes from `Rv.type`;
#   widget  calls `Text(`, `BasicText(`, `Icon(`, `Image(` or a Material control where an Rv component exists (RvText,
#           RvGlyph, RvSwitch, RvPrimaryButton, ...);
#   click   calls `clickable(`, `selectable(`, `toggleable(` or `combinedClickable(`: a press and its focus outline come
#           from the library's `rvPress`, `rvRowPress`, `rvRowSelect`, `rvRowToggle` and `rvPressSelect`;
#   add     names the add glyph (`R.drawable.rv_sym_add`) at all: every 新增 X entry is the library's `RvAddRow` or
#           `RvSettingsAddRow` (owner 2026-11-26: one add component for list cards and empty states), and an action
#           that is not one (導入設定) takes its own glyph;
#   rowctl  calls `RvSwitch(` or `RvRadio(`: a switch row is `RvSwitchRow` and a radio row `RvRadioRow` (unify-1126),
#           not a page's RvRow with the control as its end;
#   navend  ends a hand-built row end with `RvChevron()` on the line after a `trailingGap` spacer (a value or busy bar,
#           the gap, the chevron): the row's end comes from the library;
#   tint    gives `RvIconDisc` a colour of `Rv.colors` per call: a disc's colour is its role's, one per role.
# Comments are not read: a comment line, and the rest of a line after a `//` outside a string literal. Each existing
# exception is a line of tools/check-components.allow:
#   <file under app/src>|<rule>|<count>|<reason>
# The count is the number of hits of that rule in that file. A hit past it fails, and so does a count above the
# measure: lower it, or delete the line, once the page uses the library.
# The allowlist only shrinks, as android/scripts/limits-ratchet.txt does: with --base-rev it is also compared with the
# one at that revision, and a line not there, or a count above its count there, fails. The allowlist's `# rules:` line
# names the rules it measures; a line of a rule the base did not name is a new rule's first freeze, not compared (a base
# with no such line measured the first six).
#
#   check-components.sh                    check against the allowlist
#   check-components.sh --base-rev <rev>   the same, and the allowlist against the one at <rev>
#   check-components.sh --list             every hit, allowlisted or not (the "before" count), as file:line:rule:text
# COMPONENTS_ROOT and COMPONENTS_ALLOW point the gate at another tree and allowlist (the negative control's fixtures).
set -eu
root=${COMPONENTS_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}
allow=${COMPONENTS_ALLOW:-$(dirname "$0")/check-components.allow}
src="$root/app/src"
base_rev=
if [ "${1:-}" = "--base-rev" ]; then
    base_rev=${2:?--base-rev needs a revision}
fi

dp='[0-9]\.(dp|sp)\b|(^|[^A-Za-z_.])Dp\('
colour='Color\(0x|\b0x[0-9A-Fa-f]{8}\b|\bColor\.[A-Z]|MaterialTheme\.'
style='TextStyle\(|\bfontSize\b|\bFontWeight\.'
widget='(^|[^[:alnum:]_.])(androidx\.compose\.[[:alnum:]_.]+\.)?(Text|BasicText|Icon|Image|IconButton|Switch|Button|TextButton|OutlinedButton|Card|Surface|TextField|OutlinedTextField|RadioButton|Checkbox|HorizontalDivider|Divider)\('
click='(^|[^A-Za-z_])(clickable|selectable|toggleable|combinedClickable)[[:space:]]*[({]'
add='\bR\.drawable\.rv_sym_add\b'
rowctl='(^|[^A-Za-z_.])(RvSwitch|RvRadio)\('
tint='RvIconDisc\([^)]*,[[:space:]]*(color[[:space:]]*=[[:space:]]*)?Rv\.colors\.'
# navend is read over two lines: `RvChevron()` on the line after one that spaces a `trailingGap` (blank lines between).
# shellcheck disable=SC2016 # an awk program: its $0 is awk's
navend='$0 ~ /(^|[^A-Za-z_.])RvChevron\(\)/ && prev ~ /trailingGap/ { print NR ":" $0 } $0 !~ /^[[:space:]]*$/ { prev = $0 }'
# A class, object or interface of a widget's name (`data class Icon(`): a declaration, read past by the widget rule.
declared='^[0-9]+:.*\b(class|interface)[[:space:]]+(Text|BasicText|Icon|Image|IconButton|Switch|Button|TextButton|OutlinedButton|Card|Surface|TextField|OutlinedTextField|RadioButton|Checkbox|HorizontalDivider|Divider)\('

# Prints each line with its imports, comments and string text blanked, so a line keeps its number. Code inside a
# string's `${...}` is still read, nested strings and braces too; a `/* */` comment nests and runs over lines.
# shellcheck disable=SC2016 # an awk program: its $0 is awk's
uncomment='BEGIN { raw = ins = depth = 0 }
{
    ins = 0
    if (!block && !raw && $0 ~ /^import /) { print ""; next }
    out = ""; n = length($0); i = 1
    while (i <= n) {
        c = substr($0, i, 1)
        pair = substr($0, i, 2)
        if (!raw && !ins && pair == "/*") { block++; out = out " "; i += 2; continue }
        if (block) {
            if (pair == "*/") { block--; i += 2 } else i++
            continue
        }
        if (raw || ins) {
            if (ins && c == "\\") { i += 2; continue }
            if (substr($0, i, 2) == "${") {
                resume[++depth] = raw ? "raw" : "string"; raw = ins = 0; out = out " "; i += 2; continue
            }
            if (raw && substr($0, i, 3) == "\"\"\"") { raw = 0; i += 3; continue }
            if (ins && c == "\"") ins = 0
            i++; continue
        }
        if (depth && c == "{") resume[++depth] = "code"
        if (depth && c == "}") { raw = resume[depth] == "raw"; ins = resume[depth] == "string"; depth-- }
        if (c == "/" && substr($0, i + 1, 1) == "/") break
        if (substr($0, i, 3) == "\"\"\"") { raw = 1; out = out " "; i += 3; continue }
        if (c == "\"") { ins = 1; out = out " "; i++; continue }
        if (c == "\047") {
            j = (substr($0, i + 1, 1) == "\\") ? i + 3 : i + 2
            if (substr($0, j, 1) == "\047") { out = out substr($0, i, j - i + 1); i = j + 1; continue }
        }
        out = out c; i++
    }
    print out
}'

pages=$(
    {
        find "$src/debug/kotlin" -path '*/prototype/rv/*' -name '*.kt' 2>/dev/null
        find "$src/main/kotlin" -path '*/feature/*' -name '*.kt' 2>/dev/null
    } | grep -vE '/Rv[A-Za-z]*Tokens\.kt$' | sort -u || true
)
hits=$(mktemp)
counts=$(mktemp)
stripped=$(mktemp)
trap 'rm -f "$hits" "$counts" "$stripped"' EXIT
for f in $pages; do
    rel=${f#"$src"/}
    # Comment lines, imports and trailing comments are blanked, not removed, so the line numbers stay. A trailing comment
    # starts at a `//` outside a string literal: `"https://..."` and `"a // b"` are a string's text, and what follows them is read.
    awk "$uncomment" "$f" > "$stripped"
    for rule in dp colour style widget click add rowctl tint; do
        eval "pattern=\$$rule"
        # A declaration is no call: `data class Icon(` names a type, it draws nothing.
        grep -nE "$pattern" "$stripped" | { [ "$rule" = widget ] && grep -vE "$declared" || cat; } | sed -E "s#^([0-9]+):#$rel:\1:$rule:#" || true
    done
    awk "$navend" "$stripped" | sed -E "s#^([0-9]+):#$rel:\1:navend:#"
done > "$hits"

if [ "${1:-}" = "--list" ]; then
    cat "$hits"
    printf 'check-components: %s hits in %s page files\n' "$(wc -l < "$hits" | tr -d ' ')" "$(printf '%s\n' "$pages" | sed '/^$/d' | wc -l | tr -d ' ')" >&2
    exit 0
fi

# file|rule|count of the hits
awk -F: '{ n[$1 "|" $3]++ } END { for (k in n) print k "|" n[k] }' "$hits" | sort > "$counts"
bad=$(
    # a measured file and rule, against its allowed count
    while IFS='|' read -r file rule n; do
        allowed=$(awk -F'|' -v f="$file" -v r="$rule" '$1 == f && $2 == r { print $3 }' "$allow" 2>/dev/null || true)
        if [ -z "$allowed" ]; then
            echo "$file: $n $rule hit(s), none allowed:"
            grep -F "$file:" "$hits" | awk -F: -v r="$rule" '$3 == r' | head -5 | sed 's/^/    /'
        elif [ "$n" -gt "$allowed" ]; then
            echo "$file: $n $rule hit(s), $allowed allowed:"
            grep -F "$file:" "$hits" | awk -F: -v r="$rule" '$3 == r' | sed 's/^/    /' | head -8
        fi
    done < "$counts"
    # an allowed file and rule that now measures less, or nothing
    if [ -f "$allow" ]; then
        grep -vE '^[[:space:]]*(#|$)' "$allow" | while IFS='|' read -r file rule allowed reason; do
            [ -n "$reason" ] || { echo "$file|$rule: the allowlist line has no reason"; continue; }
            n=$(awk -F'|' -v f="$file" -v r="$rule" '$1 == f && $2 == r { print $3 }' "$counts")
            n=${n:-0}
            [ "$n" -ge "$allowed" ] || echo "$file: allows $allowed $rule hit(s), measures $n: lower the count in tools/check-components.allow"
        done
    fi
)
# the allowlist against the one at the base revision: nothing added, no count raised
if [ -n "$base_rev" ]; then
    dir=$(cd "$(dirname "$allow")" && pwd)
    git -C "$dir" rev-parse --verify --quiet "$base_rev^{commit}" > /dev/null || { echo "check-components: cannot resolve base revision $base_rev" >&2; exit 2; }
    path=$(git -C "$dir" rev-parse --show-prefix)$(basename "$allow")
    if base=$(git -C "$dir" show "$base_rev:$path" 2> /dev/null); then
        known=$(printf '%s\n' "$base" | sed -n 's/^# rules: //p')
        known=" ${known:-dp colour style widget click add} "
        grew=$(
            grep -vE '^[[:space:]]*(#|$)' "$allow" | while IFS='|' read -r file rule allowed reason; do
                case $known in *" $rule "*) ;; *) continue ;; esac
                was=$(printf '%s\n' "$base" | awk -F'|' -v f="$file" -v r="$rule" '$1 == f && $2 == r { print $3 }')
                if [ -z "$was" ]; then
                    echo "$file|$rule: added to the allowlist, not in $base_rev"
                elif [ "$allowed" -gt "$was" ]; then
                    echo "$file|$rule: raised from $was in $base_rev to $allowed"
                fi
            done
        )
        [ -z "$grew" ] || bad="${bad:+$bad
}$grew"
    else
        echo "check-components: $base_rev has no $path: first freeze, not compared with the base"
    fi
fi

total=$(wc -l < "$hits" | tr -d ' ')
if [ -n "$bad" ]; then
    printf 'check-components:\n%s\n' "$bad" >&2
    exit 1
fi
echo "check-components: $total hit(s) in the page files, every one on the allowlist with its reason"
