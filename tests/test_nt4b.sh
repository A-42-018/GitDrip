#!/usr/bin/env bash
# NT4b static audit: screens must use Neo tokens / Glass* kit only (no old Palette, glass(), stock widgets, hard-coded colours).
# Runs without kotlinc/Gradle. Usage: bash tests/test_nt4b.sh
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/android/app/src/main/java/com/gitdrip/app"
pass=0; fail=0
ok()  { pass=$((pass+1)); echo "ok   $1"; }
bad() { fail=$((fail+1)); echo "FAIL $1"; shift; printf '       %s\n' "$@"; }

# Files that legitimately define primitives (NT5: Palette/glass()/GlassAlpha are gone, so Tokens.kt and Glass.kt are scanned like screens).
DEF='/ui/(Theme|Neo|NeoControls|NeoLogic)\.kt$'
screens() { find "$SRC" -name '*.kt' | grep -v -E "$DEF"; }

check() { # name regex [allow-file-regex]
  local name="$1" re="$2" allow="${3:-^$}" hits
  hits=$(screens | grep -v -E "$allow" | xargs grep -n -E "$re" 2>/dev/null)
  if [ -z "$hits" ]; then ok "$name"; else bad "$name" "$hits"; fi
}

check "no old Palette in screens"            'Palette\.'
check "no old glass() / glassFill"           '\.glass\(|glassFill|GlassAlpha'
check "no stock Switch"                      '(^|[^A-Za-z])Switch\('
check "no stock IconButton"                  '(^|[^A-Za-z])IconButton\('
check "no stock Card/Button/TextField"       '(^|[^A-Za-z])(Card|ElevatedCard|OutlinedCard|OutlinedButton|FilledTonalButton|TextButton|OutlinedTextField|TextField|TopAppBar|CenterAlignedTopAppBar)\(' '/ui/GlassComponents\.kt$'
check "no hard-coded Color(0x...)"           'Color\(0x'
check "no Color.White/Black/Gray/Red"        'Color\.(White|Black|Gray|DarkGray|LightGray|Red|Green|Blue)\b' '/ui/GlassComponents\.kt$'
check "AlertDialog only in dialog kit"       '(^|[^A-Za-z])AlertDialog\(' '/ui/(GlassComponents|TabComponents)\.kt$'
check "isDarkTheme only in kit"              'isDarkTheme\(\)' '/ui/(GlassComponents|Glass)\.kt$'

# Every AlertDialog title/buttons must sit in NeoRaisedTheme (light modal).
for f in "$SRC/ui/GlassComponents.kt" "$SRC/ui/TabComponents.kt"; do
  n=$(grep -c 'AlertDialog(' "$f"); t=$(grep -c 'title = { NeoRaisedTheme' "$f")
  [ "$n" -eq "$t" ] && ok "$(basename "$f"): $n dialog(s) with NeoRaisedTheme title" || bad "$(basename "$f"): dialog/title mismatch" "AlertDialog=$n NeoRaisedTheme titles=$t"
done

# Scaffold supplies the default content colour for loose text on the backdrop.
grep -q 'contentColor = MaterialTheme.colorScheme.onBackground' "$SRC/ui/GlassComponents.kt" \
  && ok "GlassScaffold sets onBackground content colour" || bad "GlassScaffold content colour missing"

# Every screen composable root uses GlassScaffold.
miss=""
for f in SettingsScreen SetupScreen ProjectScreens; do grep -q 'GlassScaffold' "$SRC/$f.kt" || miss="$miss $f"; done
grep -q 'GlassScaffold' "$SRC/HistoryUi.kt" || miss="$miss HistoryUi"
[ -z "$miss" ] && ok "screens use GlassScaffold" || bad "screens without GlassScaffold" "$miss"

echo "---"; echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
