#!/usr/bin/env bash
# Compiles the pure-JVM Kotlin logic + its JUnit tests with kotlinc (no Gradle/Android) and runs them via a tiny runner.
# Needs: kotlinc (1.9.x) + java. Not covered: GitHubTest/BridgeTest (org.json), anything importing android.*.
set -euo pipefail
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; S="$APP/android/app/src"; D="$S/main/java/com/gitdrip/app/data"; W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
KC="${KOTLINC:-kotlinc}"
cp "$D"/{Batching,History,ScheduleMath,AutoDrip,NoteLogic,Backup,WatchSync}.kt "$S/main/java/com/gitdrip/app/ui"/{Tokens,NeoLogic,StateKind,TimelineLogic,DashboardLogic,ProjectLogic,TabLogic,ScreenLogic,MotionLogic,NavBarLogic}.kt "$APP"/tests/kt/*.kt "$W/"
{ sed -n '1,2p' "$D/Secure.kt"; sed -n '6p;8,16p' "$D/Secure.kt"; sed -n '17,52p' "$D/Secure.kt" | grep -v 'fun handoffFile'; } > "$W/HandoffOnly.kt"
{ echo "package com.gitdrip.app.data"; grep -E '^private val (NAME_RE|REPO_RE|BRANCH_RE)' "$D/Db.kt" | sed 's/^private //'; sed -n '/^fun validateProject/,/^}/p' "$D/Db.kt"; } > "$W/Validate.kt"
for t in Batching History ScheduleMath FireDecision AutoDrip TimelineLogic Validation Handoff NotifyBackup Contrast DashboardLogic ProjectLogic TabLogic ScreenLogic MotionLogic NavBarLogic WatchSync NeoLogic; do sed 's/^import org.junit.Assert.\*/import org.junit.*/' "$S/test/java/com/gitdrip/app/${t}Test.kt" > "$W/${t}Test.kt"; done
"$KC" "$W"/*.kt -include-runtime -d "$W/t.jar" 2>&1 | grep -v '^warning' || true
java -cp "$W/t.jar" RunnerKt $(cd "$W" && grep -l '@Test' *Test.kt | sed 's/\.kt$//;s/^/com.gitdrip.app./')
# Optional org.json part (GitHubTest + BridgeTest): JSONJAR=/usr/share/java/json.jar (apt: libandroid-json-org-java)
if [ -n "${JSONJAR:-}" ]; then
  W2="$(mktemp -d)"; trap 'rm -rf "$W2"' EXIT
  { sed -n '1,2p;6,7p;9,108p' "$D/TermuxBridge.kt" | grep -v '^import android'; } > "$W2/BridgeOnly.kt"
  cp "$D"/{GitHub,History}.kt "$APP"/tests/kt/*.kt "$W2/"
  for t in GitHub Bridge; do sed 's/^import org.junit.Assert.\*/import org.junit.*/' "$S/test/java/com/gitdrip/app/${t}Test.kt" > "$W2/${t}Test.kt"; done
  "$KC" -cp "$JSONJAR" "$W2"/*.kt -include-runtime -d "$W2/t.jar" 2>&1 | grep -E 'error' || true
  java -cp "$W2/t.jar:$JSONJAR" RunnerKt com.gitdrip.app.GitHubTest com.gitdrip.app.BridgeTest
fi
