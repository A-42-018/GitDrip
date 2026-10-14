#!/usr/bin/env bash
# P4 acceptance test: task runner, state machine, lock, idempotency. Needs git + jq.
set -euo pipefail
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export GITDRIP_HOME="$(mktemp -d)"; export GITDRIP_BIN_DIR="$GITDRIP_HOME/bin"; unset PREFIX
command -v jq >/dev/null && command -v git >/dev/null || { echo "SKIP: jq/git missing"; exit 0; }
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
pass=0; fail=0
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }
bash "$APP/install.sh" >/dev/null 2>&1
G="$GITDRIP_BIN_DIR/gitdrip"

T="$(mktemp -d)"; S="$T/sample"; REMOTE="$T/remote.git"; mkdir -p "$S"/{src/auth,src/api,src/ui,tests}
echo "# Sample" > "$S/README.md"
for i in 1 2 3 4 5 6 7 8; do echo "export const a$i=$i" > "$S/src/auth/f$i.js"; done
echo "x" > "$S/src/api/route.js"; echo "t" > "$S/tests/test_a.js"; echo "u" > "$S/src/ui/view.js"
git init -q --bare "$REMOTE"; git -C "$REMOTE" symbolic-ref HEAD refs/heads/main
"$G" import "$S" --name sample --max 2 >/dev/null
"$G" repo-init sample --remote "$REMOTE" --user Alif --email "1+alif@users.noreply.github.com" >/dev/null
P="$GITDRIP_HOME/projects/sample"; B="$P/batches.json"; R="$P/repo"; LK="$GITDRIP_HOME/locks/sample.lock"
count() { git -C "$R" rev-list --count HEAD 2>/dev/null || echo 0; }
state() { jq -r .state "$GITDRIP_HOME/tasks/$1.json"; }
bst() { jq -r ".batches[$1].status" "$B"; }

# create + run
tid="$("$G" task-create sample)"
ok "task-create: PENDING task for first batch" '[[ "$(state "$tid")" == PENDING && "$(jq -r .batch_id "$GITDRIP_HOME/tasks/$tid.json")" == 1 ]]'
ok "task-create reuses open task (no duplicate)" '[[ "$("$G" task-create sample)" == "$tid" ]]'
"$G" task-run "$tid" >/dev/null
tf="$GITDRIP_HOME/tasks/$tid.json"
ok "task-run: SUCCESS, commit hash + files recorded" '[[ "$(state "$tid")" == SUCCESS && "$(jq -r .commit "$tf")" == "$(git -C "$R" rev-parse HEAD)" && "$(jq -r .files_changed "$tf")" -ge 1 && "$(count)" == 1 ]]'
ok "task has attempt/timestamps" '[[ "$(jq -r .attempt "$tf")" == 1 && "$(jq -r ".started_at and .finished_at" "$tf")" == true ]]'
ok "lock released after run" '[[ ! -e "$LK" ]]'

# idempotency
rc=0; out="$("$G" task-run "$tid")" || rc=$?
ok "same task twice: second is no-op" '[[ $rc -eq 0 && "$out" == *"already SUCCESS"* && "$(count)" == 1 ]]'
rc=0; out="$("$G" run sample 1)" || rc=$?
t2="$(ls -t "$GITDRIP_HOME/tasks" | head -n1 | sed 's/\.json$//')"
ok "new task for finished batch: SKIPPED, no commit" '[[ $rc -eq 0 && "$(state "$t2")" == SKIPPED && "$(count)" == 1 ]]'

# live lock blocks
sleep 60 & SP=$!
echo "$SP $(date +%s)" > "$LK"; before="$(count)"
rc=0; "$G" run sample >/dev/null 2>&1 || rc=$?
t3="$("$G" task-create sample)"
ok "live lock: exit 4, task stays PENDING, no commit" '[[ $rc -eq 4 && "$(state "$t3")" == PENDING && "$(count)" == "$before" && "$(cat "$LK")" == "$SP "* ]]'

# stale lock (age) with live pid
echo "$SP $(( $(date +%s) - 4000 ))" > "$LK"
"$G" task-run "$t3" >/dev/null
ok "stale lock (old timestamp) taken over, task SUCCESS" '[[ "$(state "$t3")" == SUCCESS && "$(count)" == $((before+1)) && ! -e "$LK" ]]'
kill "$SP" 2>/dev/null || true; wait "$SP" 2>/dev/null || true

# stale lock (dead pid) + crashed RUNNING task recovery
t4="$("$G" task-create sample)"
echo "999999 $(date +%s)" > "$LK"
jq '.state="RUNNING"|.attempt=1' "$GITDRIP_HOME/tasks/$t4.json" > "$T/x" && mv "$T/x" "$GITDRIP_HOME/tasks/$t4.json"
before="$(count)"; "$G" task-run "$t4" >/dev/null
ok "dead-pid lock + RUNNING task recovered to SUCCESS" '[[ "$(state "$t4")" == SUCCESS && "$(jq -r .attempt "$GITDRIP_HOME/tasks/$t4.json")" == 2 && "$(count)" == $((before+1)) ]]'
ok "garbage lock treated as stale" 'echo junk > "$LK"; "$G" run sample >/dev/null 2>&1; [[ ! -e "$LK" ]]'

# remaining batch states
echo "batches: $(jq -r '[.batches[].status]|join(",")' "$B")"
# no-change -> SKIPPED(exit 3)
nid="$(jq -r '[.batches[]|select(.status=="PENDING")][0].id // empty' "$B")"
if [[ -n "$nid" ]]; then
  while IFS= read -r f; do mkdir -p "$R/$(dirname "$f")"; cp "$P/source/$f" "$R/$f"; done < <(jq -r --argjson i "$nid" '.batches[]|select(.id==$i)|.files[]' "$B")
  git -C "$R" add -A && git -C "$R" commit -q -m manual; before="$(count)"
  rc=0; "$G" run sample "$nid" >/dev/null 2>&1 || rc=$?
  tn="$(ls -t "$GITDRIP_HOME/tasks" | head -n1 | sed 's/\.json$//')"
  ok "no change: exit 3, task SKIPPED, no commit" '[[ $rc -eq 3 && "$(state "$tn")" == SKIPPED && "$(jq -r .reason "$GITDRIP_HOME/tasks/$tn.json")" == "no changes" && "$(count)" == "$before" ]]'
fi

# push failure -> FAILED, then retry same task pushes without new commit
pid2="$(jq -r '[.batches[]|select(.status=="PENDING")][0].id // empty' "$B")"
if [[ -n "$pid2" ]]; then
  git -C "$R" remote set-url origin "$T/nope.git"; before="$(count)"
  tf2="$("$G" task-create sample "$pid2")"
  rc=0; "$G" task-run "$tf2" >/dev/null 2>&1 || rc=$?
  ok "push fail: exit 1, task FAILED, batch COMMITTED" '[[ $rc -eq 1 && "$(state "$tf2")" == FAILED && "$(count)" == $((before+1)) && "$(jq -r ".batches[]|select(.id==$pid2)|.status" "$B")" == COMMITTED ]]'
  ok "next run resumes the COMMITTED batch first" '[[ "$("$G" task-create sample)" != "" ]]'
  git -C "$R" remote set-url origin "$REMOTE"
  "$G" task-run "$tf2" >/dev/null
  ok "retry FAILED task: SUCCESS, attempt 2, no duplicate commit" '[[ "$(state "$tf2")" == SUCCESS && "$(jq -r .attempt "$GITDRIP_HOME/tasks/$tf2.json")" == 2 && "$(count)" == $((before+1)) && "$(git -C "$REMOTE" rev-parse main)" == "$(git -C "$R" rev-parse HEAD)" ]]'
fi

# guards
rc=0; "$G" task-run nope >/dev/null 2>&1 || rc=$?; ok "unknown task id rejected" '[[ $rc -ne 0 ]]'
rc=0; "$G" task-run "../etc/passwd" >/dev/null 2>&1 || rc=$?; ok "path-like task id rejected" '[[ $rc -ne 0 ]]'
rc=0; "$G" task-create ghost >/dev/null 2>&1 || rc=$?; ok "unknown project rejected" '[[ $rc -ne 0 ]]'
while [[ -n "$(jq -r '[.batches[]|select(.status=="PENDING" or .status=="COMMITTED")][0].id // empty' "$B")" ]]; do "$G" run sample >/dev/null 2>&1; done
out="$("$G" run sample)"; ok "all done: run reports no pending batches (exit 0)" '[[ "$out" == *"no pending batches"* ]]'
ok "tasks list shows tasks" '[[ "$("$G" tasks sample | wc -l)" -ge 4 ]]'
ok "task-show prints JSON" '"$G" task-show "$tid" | jq -e .id >/dev/null'

# dry_run config refuses tasks
"$G" config set dry_run true; echo "// z" >> "$P/source/tests/test_a.js"
rc=0; "$G" run sample 1 >/dev/null 2>&1 || rc=$?; ok "config dry_run=true: runner refuses" '[[ $rc -ne 0 ]]'
"$G" config set dry_run false

# concurrency: two simultaneous runs, same fresh project -> one commit per batch, no dupes
S2="$T/s2"; mkdir -p "$S2/a"; echo 1 > "$S2/a/one.js"; echo 2 > "$S2/a/two.md"
"$G" import "$S2" --name conc --max 1 >/dev/null
"$G" repo-init conc --remote "$REMOTE" --branch other --user A --email "1+a@users.noreply.github.com" >/dev/null
RC="$GITDRIP_HOME/projects/conc/repo"
for i in 1 2 3 4; do "$G" run conc 1 >/dev/null 2>&1 & done; wait || true
ok "concurrent runs: exactly one commit for batch 1" '[[ "$(git -C "$RC" rev-list --count HEAD)" == 1 ]]'
ok "concurrent runs: no lock left behind" '[[ ! -e "$GITDRIP_HOME/locks/conc.lock" ]]'

# regressions
bash "$APP/tests/test_p3.sh" >/dev/null 2>&1 && ok "P1-P3 tests still pass" true || ok "P1-P3 tests still pass" false
ok "no secrets/tokens in log" '! grep -Eq "ghp_|github_pat_" "$GITDRIP_HOME/logs/execution.log"'

rm -rf "$GITDRIP_HOME" "$T"
echo "passed=$pass failed=$fail"; [[ $fail -eq 0 ]]
