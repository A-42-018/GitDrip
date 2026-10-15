#!/usr/bin/env bash
# P7 acceptance test: schedules, crontab block, fire, daily cap, missed-slot policies. Needs git + jq.
set -euo pipefail
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export GITDRIP_HOME="$(mktemp -d)"; export GITDRIP_BIN_DIR="$GITDRIP_HOME/bin"; unset PREFIX
command -v jq >/dev/null && command -v git >/dev/null || { echo "SKIP: jq/git missing"; exit 0; }
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 GITDRIP_NET=online
pass=0; fail=0
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }
bash "$APP/install.sh" >/dev/null 2>&1
G="$GITDRIP_BIN_DIR/gitdrip"; T="$(mktemp -d)"
# fake crontab: state in $T/cron.txt
cat > "$T/fakecron" <<'FC'
#!/usr/bin/env bash
f="$(dirname "$0")/cron.txt"
case "${1:-}" in -l) [[ -s "$f" ]] && cat "$f" || exit 1 ;; -r) rm -f "$f" ;; -) cat > "$f" ;; esac
FC
chmod +x "$T/fakecron"; export GITDRIP_CRONTAB_CMD="$T/fakecron"
echo "0 3 * * * /usr/bin/other-job" > "$T/cron.txt"

mk() { # <name>: 9 files -> 3 batches, own bare remote
  local s="$T/src-$1" r="$T/$1.git"; mkdir -p "$s"/{a,b,c}
  for i in 1 2 3; do echo "a$i" > "$s/a/f$i.js"; echo "b$i" > "$s/b/g$i.js"; echo "c$i" > "$s/c/h$i.js"; done
  git init -q --bare "$r"; git -C "$r" symbolic-ref HEAD refs/heads/main
  "$G" import "$s" --name "$1" --max 3 >/dev/null
  "$G" repo-init "$1" --remote "$r" --user A --email "1+a@users.noreply.github.com" >/dev/null
}
rc_() { git -C "$T/$1.git" rev-list --count main 2>/dev/null || echo 0; }
sj() { jq -r "$1" "$GITDRIP_HOME/schedules.json"; }
cap() { "$G" config set max_commits_per_day "$1" >/dev/null; }
resetday() { jq '.schedules|=map(.last_fire_day="")' "$GITDRIP_HOME/schedules.json" > "$T/s" && mv "$T/s" "$GITDRIP_HOME/schedules.json"; }

mk alpha; cap 1
id="$("$G" schedule add "08:05" alpha | head -n1)"
ok "add returns id and lists it" '[[ -n "$id" ]] && "$G" schedule list | grep -q "08:05.*alpha"'
ok "crontab has fire line (minute/hour) + tick, keeps foreign jobs" 'grep -q "^5 8 \* \* \* .*schedule fire $id" "$T/cron.txt" && grep -q "schedule tick" "$T/cron.txt" && grep -q other-job "$T/cron.txt"'
ok "rejects bad time / policy / project / duplicate" '! "$G" schedule add "25:00" alpha >/dev/null 2>&1 && ! "$G" schedule add "09:00" alpha --policy x >/dev/null 2>&1 && ! "$G" schedule add "09:00" nope >/dev/null 2>&1 && ! "$G" schedule add "08:05" alpha >/dev/null 2>&1'
ok "install is idempotent (one managed block)" '"$G" schedule install >/dev/null; "$G" schedule install >/dev/null; [[ "$(grep -c ">>> gitdrip" "$T/cron.txt")" == 1 ]]'

# --- fire pushes the next batch
rc=0; "$G" schedule fire "$id" >/dev/null 2>&1 || rc=$?
ok "fire: next batch committed + pushed" '[[ $rc -eq 0 && "$(rc_ alpha)" == 1 ]]'
ok "fire: last_fire_day recorded" '[[ "$(_=1; sj ".schedules[0].last_fire_day")" == "$(date +%Y-%m-%d)" ]]'
rc=0; "$G" schedule fire "$id" >/dev/null 2>&1 || rc=$?
ok "daily cap (1) enforced: exit 8, no new commit" '[[ $rc -eq 8 && "$(rc_ alpha)" == 1 ]]'
cap 5; "$G" schedule fire "$id" >/dev/null 2>&1
ok "cap raised: next batch runs, no duplicate" '[[ "$(rc_ alpha)" == 2 ]]'
"$G" schedule fire "$id" >/dev/null 2>&1; rc=0; "$G" schedule fire "$id" >/dev/null 2>&1 || rc=$?
ok "all batches done: fire exits 0, no extra commit" '[[ $rc -eq 0 && "$(rc_ alpha)" == 3 ]]'

# --- disable / remove
"$G" schedule disable "$id"
ok "disable removes slot from crontab" '! grep -q "fire $id" "$T/cron.txt"'
"$G" schedule remove "$id" >/dev/null
ok "remove deletes schedule" '[[ "$(sj ".schedules|length")" == 0 ]]'

# --- missed slots (clock fixed at 12:00 local)
export GITDRIP_NOW="$(date -d 'today 12:00' +%s)"; cap 5
mk beta; mk gamma; mk delta
b="$("$G" schedule add "08:00" beta --policy skip | head -n1)"
g="$("$G" schedule add "08:00" gamma --policy run-now | head -n1)"
d1="$("$G" schedule add "08:00" delta --policy next-slot | head -n1)"
d2="$("$G" schedule add "20:00" delta --policy skip | head -n1)"
"$G" schedule tick >/dev/null 2>&1
ok "missed + skip: dropped, marked, no commit" '[[ "$(rc_ beta)" == 0 && "$(jq -r --arg i "$b" ".schedules[]|select(.id==\$i)|.last_fire_day" "$GITDRIP_HOME/schedules.json")" == "$(date -d @$GITDRIP_NOW +%Y-%m-%d)" ]]'
ok "missed + run-now: fires immediately" '[[ "$(rc_ gamma)" == 1 ]]'
ok "missed + next-slot: carried to 20:00 slot, nothing run now" '[[ "$(rc_ delta)" == 0 && "$(jq -r --arg i "$d2" ".schedules[]|select(.id==\$i)|.carry" "$GITDRIP_HOME/schedules.json")" == 1 ]]'
"$G" schedule tick >/dev/null 2>&1
ok "tick is idempotent (no second fire / carry)" '[[ "$(rc_ gamma)" == 1 && "$(rc_ delta)" == 0 ]]'
cap 10; "$G" schedule fire "$d2" >/dev/null 2>&1
ok "carried slot runs 1+carry batches" '[[ "$(rc_ delta)" == 2 ]]'
ok "carry cleared after fire" '[[ "$(jq -r --arg i "$d2" ".schedules[]|select(.id==\$i)|.carry" "$GITDRIP_HOME/schedules.json")" == 0 ]]'

# --- not missed yet (before slot + grace) does nothing
export GITDRIP_NOW="$(date -d 'today 07:00' +%s)"; mk eps; resetday
e="$("$G" schedule add "07:05" eps | head -n1)"; "$G" schedule tick >/dev/null 2>&1
ok "slot within grace window is not treated as missed" '[[ "$(rc_ eps)" == 0 ]]'

# --- uninstall keeps foreign jobs
"$G" schedule uninstall >/dev/null
ok "uninstall removes only gitdrip block" '! grep -q gitdrip "$T/cron.txt" && grep -q other-job "$T/cron.txt"'
ok "no token in logs" '! grep -rq "ghp_" "$GITDRIP_HOME/logs"'

# --- regression
unset GITDRIP_NOW
bash "$APP/tests/test_p6.sh" >/dev/null 2>&1 && ok "P1-P6 tests still pass" true || ok "P1-P6 tests still pass" false
rm -rf "$GITDRIP_HOME" "$T"
echo "passed=$pass failed=$fail"; [[ $fail -eq 0 ]]
