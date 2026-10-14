#!/usr/bin/env bash
# NT5 static audit (no kotlinc/Gradle): legacy glass look is gone, launcher/window colours = Neo.BgEnd, focus ring rules, font-scale safety.
# Usage: bash tests/test_nt5.sh
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
A="$ROOT/android/app/src"; SRC="$A/main/java/com/gitdrip/app"
pass=0; fail=0
ok()  { pass=$((pass+1)); echo "ok   $1"; }
bad() { fail=$((fail+1)); echo "FAIL $1"; shift; printf '       %s\n' "$@"; }
none() { # name regex [paths...]: no match anywhere in main + test Kotlin
  local name="$1" re="$2" hits; hits=$(grep -rnE "$re" --include=*.kt "$A" 2>/dev/null)
  [ -z "$hits" ] && ok "$name" || bad "$name" "$hits"
}

none "no Palette / GlassAlpha anywhere"           '\b(Palette|GlassAlpha)\b'
none "no glassFill / Modifier.glass / GlassBackground / ThemeOverride" 'glassFill|\.glass\(|fun Modifier\.glass|GlassBackground|ThemeOverride'
none "no Color(0x...) literals in main/test"      'Color\(0x'
grep -q 'object Palette\|object GlassAlpha' "$SRC/ui/Tokens.kt" && bad "Tokens.kt still defines legacy objects" || ok "Tokens.kt defines Neo tokens only"

# Launcher + window background = Neo.BgEnd #10131A
for f in "$A/main/res/values/ic_launcher_background.xml" "$A/main/res/values/themes.xml" "$A/main/res/values-night/themes.xml"; do
  grep -qi '#10131A' "$f" && ok "$(basename "$(dirname "$f")")/$(basename "$f") = #10131A" || bad "$f is not #10131A"
done
grep -rniE '#0B1020|#121B3A|#EEF2FF' "$A/main/res" >/dev/null && bad "old Palette colour left in res/" "$(grep -rniE '#0B1020|#121B3A|#EEF2FF' "$A/main/res")" || ok "no old Palette hex in res/"

# Focus ring: drawn ON TOP of the surface (drawWithContent), colour chosen per surface.
RING=$(sed -n '/^fun Modifier.neoRing/,/^}/p' "$SRC/ui/Neo.kt")
echo "$RING" | grep -q 'drawWithContent' && echo "$RING" | grep -q 'drawContent()' && ! echo "$RING" | grep -q 'drawBehind' && ok "neoRing draws over the surface (not drawBehind)" || bad "neoRing must use drawWithContent + drawContent()"
echo "$RING" | grep -q 'NeoLogic.ringColor(onLight)' && ok "neoRing colour comes from NeoLogic.ringColor" || bad "neoRing does not use ringColor"
for spec in "GlassComponents.kt:Radius.Card, onLight = true" "GlassComponents.kt:ringOnLight" "TabComponents.kt:onLight = true"; do
  f="${spec%%:*}"; pat="${spec#*:}"
  grep -q "$pat" "$SRC/ui/$f" && ok "$f: light-surface ring ($pat)" || bad "$f: missing '$pat'"
done

# Font scale 1.5: only FloatNavBar sets a text size (sp scales); no dp-sized text, no fixed text heights in screens.
sp=$(grep -rnE 'fontSize *=|[0-9]\.sp\b' --include=*.kt "$SRC" | grep -v '/ui/Theme.kt')
[ "$(echo "$sp" | grep -c .)" -le 1 ] && ok "text sizes set only in Theme.kt (+ FloatNavBar label)" || bad "unexpected fixed text sizes" "$sp"
hh=$(grep -rnE '\.height\([0-9]+\.dp\)' --include=*.kt "$SRC" | grep -E 'Text|Button|Chip|Field')
[ -z "$hh" ] && ok "no fixed .height(N.dp) on text/button/chip/field rows" || bad "fixed heights near text" "$hh"
grep -q 'maxLines = 1' "$SRC/ui/FloatNavBar.kt" && grep -qE 'overflow|Ellipsis|maxLines' "$SRC/ui/FloatNavBar.kt" && ok "FloatNavBar label is single-line (device check: 4 tabs at font scale 1.5)" || bad "FloatNavBar label rule"

# Touch targets
grep -q 'MinTouch\|Touch.Min' "$SRC/ui/GlassComponents.kt" && ok "components use the 48dp minimum" || bad "no Touch.Min in components"

echo "---"; echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
