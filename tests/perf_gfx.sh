#!/usr/bin/env bash
# Low-end scroll check (device, adb). Usage: bash tests/perf_gfx.sh [package]  -> scroll Dashboard/History while it runs the swipes itself.
# PASS when janky frames < 5%. Needs: adb, a connected device/emulator with the app installed.
set -u
PKG="${1:-com.gitdrip.app}"; MAX="${MAX_JANKY_PCT:-5}"
command -v adb >/dev/null || { echo "adb not found"; exit 2; }
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 2
for i in 1 2 3 4 5 6; do adb shell input swipe 540 1700 540 500 250; sleep 0.4; adb shell input swipe 540 500 540 1700 250; sleep 0.4; done
out=$(adb shell dumpsys gfxinfo "$PKG")
line=$(echo "$out" | grep -m1 'Janky frames'); total=$(echo "$out" | grep -m1 'Total frames rendered' | grep -oE '[0-9]+')
echo "$line"; echo "total frames: ${total:-?}"
pct=$(echo "$line" | grep -oE '\(([0-9.]+)%\)' | grep -oE '[0-9.]+')
[ -z "${pct:-}" ] && { echo "could not read janky %"; exit 2; }
awk -v p="$pct" -v m="$MAX" 'BEGIN{ if (p+0 < m+0) { print "PASS janky " p "% < " m "%"; exit 0 } else { print "FAIL janky " p "% >= " m "%"; exit 1 } }'
