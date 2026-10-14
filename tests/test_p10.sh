#!/usr/bin/env bash
# P10 acceptance test: app bridge (doctor, sync, run, results files). Parts 1 need no jq; part 2 needs jq + git.
set -euo pipefail
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export GITDRIP_HOME="$(mktemp -d)"; export GITDRIP_BIN_DIR="$GITDRIP_HOME/bin"; unset PREFIX
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 GITDRIP_NET=online
T="$(mktemp -d)"; export GITDRIP_EXCHANGE="$T/ex"; mkdir -p "$GITDRIP_EXCHANGE"
pass=0; fail=0
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }
bash "$APP/install.sh" >/dev/null 2>&1
G="$GITDRIP_BIN_DIR/gitdrip"
R() { echo "$GITDRIP_EXCHANGE/results/$1.json"; }
HAVEJQ=0; command -v jq >/dev/null && command -v git >/dev/null && HAVEJQ=1

# --- part 1: no jq needed
"$G" bridge req-doc doctor >/dev/null 2>&1 || true
ok "doctor writes results/<req>.json" '[[ -s "$(R req-doc)" ]] && grep -q "\"action\":\"doctor\"" "$(R req-doc)"'
ok "doctor reports version 0.23.0" 'grep -q "\"version\":\"0.23.0\"" "$(R req-doc)"'
ok "doctor reports exchange_writable true" 'grep -q "\"exchange_writable\":true" "$(R req-doc)"'
ok "doctor reports auth false before login" 'grep -q "\"auth\":false" "$(R req-doc)"'
ok "bad request id rejected (no path escape)" '! "$G" bridge "../evil" doctor >/dev/null 2>&1 && [[ ! -e "$T/evil.json" ]]'
"$G" bridge req-badp run "Bad Name" >/dev/null 2>&1 || true
ok "invalid project name -> ERROR result" 'grep -q "\"state\":\"ERROR\"" "$(R req-badp)"'
ok "unknown action fails" '! "$G" bridge req-x nonsense >/dev/null 2>&1'
ok "result file has no leftover tmp files" '[[ -z "$(ls "$GITDRIP_EXCHANGE/results" | grep "\.tmp\." || true)" ]]'

if [[ $HAVEJQ -ne 1 ]]; then echo "SKIP part 2: jq/git missing"; echo "passed=$pass failed=$fail"; rm -rf "$GITDRIP_HOME" "$T"; [[ $fail -eq 0 ]]; exit; fi

# --- part 2: sync + run
mkplan() { # <project> <repo-remote>: 2 batches from files a/x.js a/y.js b/z.js, written like the app does
  local p="$1" d="$GITDRIP_EXCHANGE/$1"; mkdir -p "$d/source/a" "$d/source/b"
  echo "x1" > "$d/source/a/x.js"; echo "y1" > "$d/source/a/y.js"; echo "z1" > "$d/source/b/z.js"
  jq -n --arg p "$p" --arg r "${2:-}" '{project:$p, repo:"", branch:"main", user:"Alif", email:"1+alif@users.noreply.github.com",
    batches:[{id:7,message:"feat: add b module",files:["b/z.js"]},{id:3,message:"feat: add a module",files:["a/x.js","a/y.js"]}]}' > "$d/plan.json"
}
BARE="$T/remote.git"; git init -q --bare "$BARE"; git -C "$BARE" symbolic-ref HEAD refs/heads/main
rcount() { git -C "$BARE" rev-list --count main 2>/dev/null || echo 0; }

mkplan proj1
"$G" bridge req-s1 sync proj1 >/dev/null 2>&1
ok "sync: OK result with 2 batches" '[[ "$(jq -r .state "$(R req-s1)")" == OK && "$(jq ".batches|length" "$(R req-s1)")" == 2 ]]'
ok "sync: ids renumbered 1..n by plan id (a module first)" '[[ "$(jq -r ".batches[0].message" "$GITDRIP_HOME/projects/proj1/batches.json")" == "feat: add a module" ]]'
ok "sync: all batches PENDING" '[[ "$(jq "[.batches[]|select(.status!=\"PENDING\")]|length" "$GITDRIP_HOME/projects/proj1/batches.json")" == 0 ]]'
ok "sync: repo initialised with identity" '[[ "$(git -C "$GITDRIP_HOME/projects/proj1/repo" config user.email)" == "1+alif@users.noreply.github.com" ]]'
git -C "$GITDRIP_HOME/projects/proj1/repo" remote add origin "$BARE"

# secrets / traversal / duplicates are refused and nothing is applied
mkplan bad1; echo "TOKEN=1" > "$GITDRIP_EXCHANGE/bad1/source/.env"
jq '.batches[0].files=[".env"]' "$GITDRIP_EXCHANGE/bad1/plan.json" > "$T/p.json" && mv "$T/p.json" "$GITDRIP_EXCHANGE/bad1/plan.json"
"$G" bridge req-b1 sync bad1 >/dev/null 2>&1 || true
ok "plan containing .env refused" '[[ "$(jq -r .state "$(R req-b1)")" == ERROR && ! -f "$GITDRIP_HOME/projects/bad1/batches.json" ]]'
mkplan bad2; jq '.batches[0].files=["../../etc/passwd"]' "$GITDRIP_EXCHANGE/bad2/plan.json" > "$T/p.json" && mv "$T/p.json" "$GITDRIP_EXCHANGE/bad2/plan.json"
"$G" bridge req-b2 sync bad2 >/dev/null 2>&1 || true
ok "plan with .. path refused" '[[ "$(jq -r .state "$(R req-b2)")" == ERROR ]]'
mkplan bad3; jq '.batches[1].files=["b/z.js"]' "$GITDRIP_EXCHANGE/bad3/plan.json" > "$T/p.json" && mv "$T/p.json" "$GITDRIP_EXCHANGE/bad3/plan.json"
"$G" bridge req-b3 sync bad3 >/dev/null 2>&1 || true
ok "file in two batches refused" '[[ "$(jq -r .state "$(R req-b3)")" == ERROR ]]'
mkplan bad4; jq '.repo="https://user:pw@github.com/o/r"' "$GITDRIP_EXCHANGE/bad4/plan.json" > "$T/p.json" && mv "$T/p.json" "$GITDRIP_EXCHANGE/bad4/plan.json"
"$G" bridge req-b4 sync bad4 >/dev/null 2>&1 || true
ok "repo URL with credentials refused" '[[ "$(jq -r .state "$(R req-b4)")" == ERROR ]]'

# run: first batch commits + pushes; result has commit hash
"$G" bridge req-r1 run proj1 --sync >/dev/null 2>&1 || true
ok "run: SUCCESS result with commit + batches status" '[[ "$(jq -r .state "$(R req-r1)")" == SUCCESS && -n "$(jq -r .commit "$(R req-r1)")" && "$(jq -r ".batches[0].status" "$(R req-r1)")" == SUCCESS ]]'
ok "run: files_changed + task_id reported" '[[ "$(jq .files_changed "$(R req-r1)")" == 2 && -n "$(jq -r .task_id "$(R req-r1)")" ]]'
ok "run: exactly one commit on remote" '[[ "$(rcount)" == 1 ]]'
"$G" bridge req-r2 run proj1 --sync >/dev/null 2>&1 || true
ok "second run (--sync after start) takes batch 2, plan not re-applied" '[[ "$(jq -r .state "$(R req-r2)")" == SUCCESS && "$(rcount)" == 2 ]]'
"$G" bridge req-r3 run proj1 --sync >/dev/null 2>&1 || true
ok "no pending batches -> SKIPPED, no empty commit" '[[ "$(jq -r .state "$(R req-r3)")" == SKIPPED && "$(rcount)" == 2 ]]'
"$G" bridge req-r4 run proj1 1 >/dev/null 2>&1 || true
ok "explicit finished batch id is a no-op (SKIPPED), still 2 commits" '[[ "$(rcount)" == 2 && "$(jq -r .state "$(R req-r4)")" == SKIPPED ]]'
"$G" bridge req-st status proj1 >/dev/null 2>&1
ok "status action lists batch states" '[[ "$(jq "[.batches[]|select(.status==\"SUCCESS\")]|length" "$(R req-st)")" == 2 ]]'

# secret in a planned file: task FAILED with secret_blocked, batch stays PENDING, no secret in result
mkplan sec1; echo 'const k = "gh''p_abcdefghijklmnopqrstuvwxyz0123456789";' > "$GITDRIP_EXCHANGE/sec1/source/a/x.js"
"$G" bridge req-sc1 sync sec1 >/dev/null 2>&1
git -C "$GITDRIP_HOME/projects/sec1/repo" remote add origin "$BARE"
"$G" bridge req-sc2 run sec1 --sync >/dev/null 2>&1 || true
ok "secret in batch -> FAILED secret_blocked" '[[ "$(jq -r .state "$(R req-sc2)")" == FAILED && "$(jq -r .reason "$(R req-sc2)")" == secret_blocked ]]'
ok "result file never contains the token" '! grep -rq "ghp_" "$GITDRIP_EXCHANGE/results"'

# locked project -> reported, task not lost
mkplan lk1; "$G" bridge req-l0 sync lk1 >/dev/null 2>&1
echo "$$ $(date +%s)" > "$GITDRIP_HOME/locks/lk1.lock"
"$G" bridge req-l1 run lk1 >/dev/null 2>&1 || true
ok "locked project -> PENDING result with reason" '[[ "$(jq -r .state "$(R req-l1)")" == PENDING && "$(jq -r .reason "$(R req-l1)")" == *locked* ]]'
rm -f "$GITDRIP_HOME/locks/lk1.lock"

ok "no token in logs" '! grep -rq "ghp_" "$GITDRIP_HOME/logs"'
# history: all tasks of the project, newest first, other projects excluded
"$G" bridge req-h1 history proj1 >/dev/null 2>&1 || true
ok "history: OK result, only proj1 tasks, >=2" '[[ "$(jq -r .state "$(R req-h1)")" == OK && "$(jq ".tasks|length" "$(R req-h1)")" -ge 2 && "$(jq "[.tasks[]|select(.id|contains(\"-proj1-b\")|not)]|length" "$(R req-h1)")" == 0 ]]'
ok "history: SUCCESS task has commit + files_changed" '[[ "$(jq "[.tasks[]|select(.state==\"SUCCESS\" and (.commit|length)>0 and .files_changed>=1)]|length" "$(R req-h1)")" == 2 ]]'
ok "history: newest first" '[[ "$(jq -r ".tasks[0].created_at>=.tasks[1].created_at" "$(R req-h1)")" == true ]]'
"$G" bridge req-h2 history proj-none >/dev/null 2>&1 || true
ok "history: unknown project -> empty list" '[[ "$(jq ".tasks|length" "$(R req-h2)")" == 0 ]]'
"$G" bridge req-h3 history "Bad Name" >/dev/null 2>&1 || true
ok "history: invalid project name -> ERROR" '[[ "$(jq -r .state "$(R req-h3)")" == ERROR ]]'

# --- regression
bash "$APP/tests/test_p7.sh" >/dev/null 2>&1 && ok "P1-P7 tests still pass" true || ok "P1-P7 tests still pass" false
rm -rf "$GITDRIP_HOME" "$T"
echo "passed=$pass failed=$fail"; [[ $fail -eq 0 ]]
