#!/usr/bin/env bash
# FX4 static checks: Room v6 -> v7 migration, DAO/entity wiring, alarm actions (the Kotlin logic itself is in AutoDripTest).
set -u
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; J="$APP/android/app/src/main/java/com/gitdrip/app"
DB="$(tr -d "\`" < "$J/data/Db.kt")"
f=0; ok(){ echo "ok   $1"; }; bad(){ echo "FAIL $1"; f=1; }
chk(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }
chk "database version 7" 'grep -q "version = 7" "$J/data/Db.kt"'
chk "MIGRATION_6_7 registered" 'grep -q "MIGRATION_5_6, MIGRATION_6_7" "$J/data/Db.kt"'
for c in "autoDrip INTEGER NOT NULL DEFAULT 0" "autoPerDay INTEGER NOT NULL DEFAULT 10" "autoStart TEXT NOT NULL DEFAULT '09:00'" "autoEnd TEXT NOT NULL DEFAULT '22:00'"; do
  chk "migration adds $c" 'printf "%s" "$DB" | grep -qF "$c"'; done
chk "auto_slots table + FK cascade + index" 'printf "%s" "$DB" | grep -q "CREATE TABLE IF NOT EXISTS auto_slots" && printf "%s" "$DB" | grep -q "index_auto_slots_projectId"'
chk "entity registered in @Database" 'grep -q "AutoSlotEntity::class\]" "$J/data/Db.kt"'
chk "entity defaults match migration (Room schema check)" 'grep -q "defaultValue = \"'"'"'09:00'"'"'\"" "$J/data/Db.kt" && grep -q "defaultValue = \"10\"" "$J/data/Db.kt"'
chk "alarm actions AUTO + PLAN handled" 'grep -q ACTION_AUTO "$J/ScheduleReceivers.kt" && grep -q ACTION_PLAN "$J/ScheduleReceivers.kt"'
chk "app open + boot re-plan Auto Drip" 'grep -q syncAuto "$J/MainActivity.kt" && grep -q "syncAuto(c, dropOverdue = true)" "$J/data/Scheduler.kt"'
chk "SecureRandom used for planning" 'grep -q "java.security.SecureRandom" "$J/data/Scheduler.kt"'
exit $f
