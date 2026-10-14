#!/usr/bin/env bash
# P6 acceptance test: retry/backoff, offline handling, verify-commit-before-push. Needs git + jq.
set -euo pipefail
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export GITDRIP_HOME="$(mktemp -d)"; export GITDRIP_BIN_DIR="$GITDRIP_HOME/bin"; unset PREFIX
command -v jq >/dev/null && command -v git >/dev/null || { echo "SKIP: jq/git missing"; exit 0; }
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
unset HTTPS_PROXY HTTP_PROXY https_proxy http_proxy ALL_PROXY all_proxy; export NO_PROXY="127.0.0.1,localhost"
pass=0; fail=0
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }
bash "$APP/install.sh" >/dev/null 2>&1
G="$GITDRIP_BIN_DIR/gitdrip"
T="$(mktemp -d)"; S="$T/sample"; REMOTE="$T/remote.git"; mkdir -p "$S"/{a,b,c}
for i in 1 2 3; do echo "export const a$i=$i" > "$S/a/f$i.js"; echo "export const b$i=$i" > "$S/b/g$i.js"; echo "export const c$i=$i" > "$S/c/h$i.js"; done
git init -q --bare "$REMOTE"; git -C "$REMOTE" symbolic-ref HEAD refs/heads/main
"$G" import "$S" --name sample --max 3 >/dev/null
"$G" repo-init sample --remote "$REMOTE" --user Alif --email "1+alif@users.noreply.github.com" >/dev/null
P="$GITDRIP_HOME/projects/sample"; R="$P/repo"; B="$P/batches.json"
tf() { printf '%s/tasks/%s.json' "$GITDRIP_HOME" "$1"; }
tj() { jq -r "$2" "$(tf "$1")"; }
rcount() { git -C "$REMOTE" rev-list --count main 2>/dev/null || echo 0; }
lcount() { git -C "$R" rev-list --count HEAD 2>/dev/null || echo 0; }
bst() { jq -r ".batches[$1].status" "$B"; }
mkdue() { jq '.next_retry_epoch=1' "$(tf "$1")" > "$(tf "$1").x" && mv "$(tf "$1").x" "$(tf "$1")"; }

# --- offline (airplane mode): nothing attempted, retry scheduled
export GITDRIP_NET=offline
ok "net-check reports offline" '! "$G" net-check sample >/dev/null 2>&1'
tid="$("$G" task-create sample 1)"; rc=0; out="$("$G" task-run "$tid" 2>&1)" || rc=$?
ok "offline: PENDING_RETRY, exit 7, no commit, no attempt consumed" '[[ $rc -eq 7 && "$(tj "$tid" .state)" == PENDING_RETRY && "$(tj "$tid" .reason)" == offline && "$(lcount)" == 0 && "$(tj "$tid" .attempt)" == 0 && "$(tj "$tid" .retries)" == 0 ]]'
ok "offline: next_retry_at set, lock released" '[[ "$(tj "$tid" .next_retry_at)" == 20* && ! -e "$GITDRIP_HOME/locks/sample.lock" ]]'
ok "task-create reuses PENDING_RETRY task" '[[ "$("$G" task-create sample 1)" == "$tid" ]]'
rc=0; out="$("$G" task-run "$tid" 2>&1)" || rc=$?
ok "not-due task-run is refused (exit 7)" '[[ $rc -eq 7 && "$out" == *"not due"* ]]'
ok "retry-due skips not-due tasks" '"$G" retry-due | grep -q "0 task"'
ok "tasks list shows PENDING_RETRY" '"$G" tasks sample | grep -q PENDING_RETRY'

# --- back online: due retry succeeds exactly once
unset GITDRIP_NET; mkdue "$tid"
"$G" retry-due >/dev/null
ok "online + due: retry-due runs it -> SUCCESS, 1 local + 1 remote commit" '[[ "$(tj "$tid" .state)" == SUCCESS && "$(lcount)" == 1 && "$(rcount)" == 1 && "$(bst 0)" == SUCCESS ]]'
"$G" retry-due >/dev/null; rc=0; "$G" task-run "$tid" >/dev/null || rc=$?
ok "no duplicate commit on repeat" '[[ "$(lcount)" == 1 && "$(rcount)" == 1 && $rc -eq 0 ]]'

# --- transient push failure after commit: commit kept, retry only pushes
export GITDRIP_NET=online
git -C "$R" remote set-url origin "https://127.0.0.1:1/x.git"
tid2="$("$G" task-create sample 2)"; rc=0; "$G" task-run "$tid2" >/dev/null 2>&1 || rc=$?
ok "transient push fail: PENDING_RETRY retries=1 (+2min), exit 7" '[[ $rc -eq 7 && "$(tj "$tid2" .state)" == PENDING_RETRY && "$(tj "$tid2" .retries)" == 1 && "$(tj "$tid2" .error_class)" == transient ]]'
d=$(( $(tj "$tid2" .next_retry_epoch) - $(date +%s) ))
ok "first backoff is ~2 min" '(( d > 100 && d <= 120 ))'
ok "batch COMMITTED locally, 2 local commits, remote unchanged" '[[ "$(bst 1)" == COMMITTED && "$(lcount)" == 2 && "$(rcount)" == 1 ]]'
mkdue "$tid2"; "$G" retry-due >/dev/null
ok "second failure: retries=2 (+5 min)" '[[ "$(tj "$tid2" .retries)" == 2 ]] && d=$(( $(tj "$tid2" .next_retry_epoch) - $(date +%s) )) && (( d > 280 && d <= 300 ))'
mkdue "$tid2"; "$G" retry-due >/dev/null
d=$(( $(tj "$tid2" .next_retry_epoch) - $(date +%s) ))
ok "third failure: retries=3 (+15 min)" '[[ "$(tj "$tid2" .retries)" == 3 ]] && (( d > 880 && d <= 900 ))'
mkdue "$tid2"; "$G" retry-due >/dev/null
ok "retries exhausted -> FAILED, reason says so" '[[ "$(tj "$tid2" .state)" == FAILED && "$(tj "$tid2" .reason)" == "retries exhausted"* ]]'
ok "still exactly one commit for batch 2 locally" '[[ "$(lcount)" == 2 && "$(rcount)" == 1 ]]'

# --- connectivity restored: manual retry pushes the SAME commit
git -C "$R" remote set-url origin "$REMOTE"; h="$(git -C "$R" rev-parse HEAD)"
"$G" task-run "$tid2" >/dev/null
ok "manual retry after outage: SUCCESS, same commit, no duplicate" '[[ "$(tj "$tid2" .state)" == SUCCESS && "$(rcount)" == 2 && "$(lcount)" == 2 && "$(git -C "$REMOTE" rev-parse main)" == "$h" ]]'

# --- verify-before-retry: remote already has the commit (push succeeded but ack lost)
tid3="$("$G" task-create sample 3)"
git -C "$R" remote set-url origin "https://127.0.0.1:1/x.git"; "$G" task-run "$tid3" >/dev/null 2>&1 || true
git -C "$R" remote set-url origin "$REMOTE"; git -C "$R" push -q origin HEAD:refs/heads/main   # "lost ack": remote got it
before="$(rcount)"; "$G" task-run "$tid3" --now >/dev/null 2>&1
ok "remote already has commit: marked SUCCESS without pushing again" '[[ "$(tj "$tid3" .state)" == SUCCESS && "$(bst 2)" == SUCCESS && "$(rcount)" == "$before" ]] && grep -q "already on remote" "$GITDRIP_HOME/logs/execution.log"'

# --- non-transient errors are never retried
N2="$T/n2"; mkdir -p "$N2/m"; echo 1 > "$N2/m/x.js"; "$G" import "$N2" --name p2 >/dev/null
"$G" repo-init p2 --remote "$T/nope.git" --user A --email "1+alif@users.noreply.github.com" >/dev/null
t4="$("$G" task-create p2 1)"; rc=0; "$G" task-run "$t4" >/dev/null 2>&1 || rc=$?
ok "fatal push error: FAILED, no retry scheduled" '[[ $rc -eq 1 && "$(tj "$t4" .state)" == FAILED && "$(tj "$t4" .next_retry_at)" == null ]]'
N3="$T/n3"; mkdir -p "$N3/m"; echo "tok='ghp_$(printf 'A1b2C3d4E5%.0s' 1 2 3 4)'" > "$N3/m/leak.js"; "$G" import "$N3" --name p3 >/dev/null
"$G" repo-init p3 --remote "$REMOTE" --user A --email "1+alif@users.noreply.github.com" >/dev/null
t5="$("$G" task-create p3 1)"; rc=0; "$G" task-run "$t5" >/dev/null 2>&1 || rc=$?
ok "secret block: FAILED, never retried" '[[ "$(tj "$t5" .state)" == FAILED && "$(tj "$t5" .error_class)" == secret ]]'

# --- commit missing from local history -> fatal, no push
N4="$T/n4"; mkdir -p "$N4/m"; echo 1 > "$N4/m/y.js"; "$G" import "$N4" --name p4 >/dev/null
"$G" repo-init p4 --remote "$REMOTE" --user A --email "1+alif@users.noreply.github.com" >/dev/null
"$G" run-batch p4 1 --no-push >/dev/null; jq '.batches[0].commit="deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"' "$P/../p4/batches.json" > "$T/b4" && mv "$T/b4" "$P/../p4/batches.json"
t6="$("$G" task-create p4 1)"; rc=0; "$G" task-run "$t6" >/dev/null 2>&1 || rc=$?
ok "commit missing locally: FAILED fatal, not retried" '[[ "$(tj "$t6" .state)" == FAILED && "$(tj "$t6" .error_class)" == fatal ]]'

# --- logs clean
ok "no token in logs" '! grep -rq "ghp_" "$GITDRIP_HOME/logs"'

# --- regression
bash "$APP/tests/test_p5.sh" >/dev/null 2>&1 && ok "P1-P5 tests still pass" true || ok "P1-P5 tests still pass" false
rm -rf "$GITDRIP_HOME" "$T"
echo "passed=$pass failed=$fail"; [[ $fail -eq 0 ]]
