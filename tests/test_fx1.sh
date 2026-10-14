#!/usr/bin/env bash
# FX1 static checks: API guards + manifest permissions (no Android toolchain needed).
set -u
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; M="$APP/android/app/src/main"; J="$M/java/com/gitdrip/app"
f=0; ok(){ echo "ok   $1"; }; bad(){ echo "FAIL $1"; f=1; }
chk(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }
chk "canScheduleExactAlarms only behind SDK_INT" '[ "$(grep -rn canScheduleExactAlarms "$J" | grep -vc "SDK_INT")" = 0 ]'
chk "USE_EXACT_ALARM present" 'grep -q "android.permission.USE_EXACT_ALARM" "$M/AndroidManifest.xml"'
chk "SCHEDULE_EXACT_ALARM maxSdkVersion=32" 'grep "SCHEDULE_EXACT_ALARM" "$M/AndroidManifest.xml" | grep -q "maxSdkVersion=\"32\""'
chk "WRITE_EXTERNAL_STORAGE maxSdk 29" 'grep "WRITE_EXTERNAL_STORAGE" "$M/AndroidManifest.xml" | grep -q "maxSdkVersion=\"29\""'
chk "requestLegacyExternalStorage" 'grep -q "requestLegacyExternalStorage=\"true\"" "$M/AndroidManifest.xml"'
chk "FGS-not-allowed handled" 'grep -q ForegroundServiceStartNotAllowedException "$J/data/TermuxBridge.kt"'
chk "sharedAccess checks WRITE_EXTERNAL_STORAGE below API 30" 'grep -q WRITE_EXTERNAL_STORAGE "$J/data/Importer.kt"'
chk "version 0.33.0 / 26" 'grep -q "versionCode = 26" "$M/../../build.gradle.kts" && grep -q "0.33.0" "$M/../../build.gradle.kts"'
exit $f
