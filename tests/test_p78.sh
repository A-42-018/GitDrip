#!/usr/bin/env bash
# P7.8 acceptance test: bridge `watch-scan` + cron `schedule tick` -> `watch --tick`. Needs git + jq.
set -uo pipefail
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v jq >/dev/null && command -v git >/dev/null || { echo "SKIP: jq/git missing"; exit 0; }
export GITDRIP_HOME="$(mktemp -d)"; export GITDRIP_BIN_DIR="$GITDRIP_HOME/bin"; export GITDRIP_EXCHANGE="$(mktemp -d)"; unset PREFIX
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 GITDRIP_NET=online
T="$(mktemp -d)"; pass=0; fail=0
trap 'rm -rf "$GITDRIP_HOME" "$GITDRIP_EXCHANGE" "$T"' EXIT
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }
bash "$APP/install.sh" >/dev/null 2>&1
G="$GITDRIP_BIN_DIR/gitdrip"
W="$T/work"; mkdir -p "$W/api"; echo "export const a=1" > "$W/api/a.js"
REMOTE="$T/remote.git"; git init -q --bare "$REMOTE"; git -C "$REMOTE" symbolic-ref HEAD refs/heads/main
"$G" import "$W" --name app --max 2 >/dev/null
P="$GITDRIP_HOME/projects/app"; B="$P/batches.json"
"$G" repo-init app --remote "$REMOTE" --user Alif --email "1+alif@users.noreply.github.com" >/dev/null
nb() { jq '.batches|length' "$B"; }
R() { cat "$GITDRIP_EXCHANGE/results/$1.json"; }
st() { jq -r ".$2" "$GITDRIP_EXCHANGE/results/$1.json"; }
old() { touch -d "-10 minutes" "$@"; }

"$G" bridge w0 watch-scan app >/dev/null 2>&1
ok "no watch dir -> ERROR" '[[ "$(st w0 state)" == ERROR && "$(st w0 reason)" == *"no watch dir"* ]]'
"$G" watch app --dir "$W" >/dev/null
echo "export const n=1" > "$W/api/n.js"; old "$W/api/n.js"
"$G" bridge w1 watch-scan app --settle 60 >/dev/null 2>&1
ok "bridge scan -> SUCCESS, 1 new batch" '[[ "$(st w1 state)" == SUCCESS && "$(nb)" == 2 && "$(st w1 reason)" == *"1 new batch"* ]]'
ok "result lists batches incl. new PENDING" '[[ "$(jq "[.batches[]|select(.status==\"PENDING\")]|length" "$GITDRIP_EXCHANGE/results/w1.json")" == 2 ]]'
"$G" bridge w2 watch-scan app --settle 60 >/dev/null 2>&1; ok "rerun idempotent" '[[ "$(nb)" == 2 && "$(st w2 reason)" == *"0 changed"* ]]'
"$G" bridge w3 watch-scan app --bogus >/dev/null 2>&1; ok "unknown option -> ERROR" '[[ "$(st w3 state)" == ERROR ]]'
"$G" bridge w4 watch-scan app --settle x >/dev/null 2>&1; ok "bad settle -> ERROR" '[[ "$(st w4 state)" == ERROR ]]'
"$G" bridge w5 watch-scan "../x" >/dev/null 2>&1; ok "bad project -> ERROR" '[[ "$(st w5 state)" == ERROR ]]'
echo "x=1" > "$W/.env"; old "$W/.env"; "$G" bridge w6 watch-scan app --settle 1 >/dev/null 2>&1
ok ".env never imported" '[[ ! -e "$P/source/.env" ]]'
echo "token=ghp_$(printf 'a%.0s' {1..36})" > "$W/api/s.js"; old "$W/api/s.js"; "$G" bridge w7 watch-scan app --settle 1 >/dev/null 2>&1
ok "secret blocked, token not in result/log" '[[ ! -e "$P/source/api/s.js" ]] && ! grep -rq "ghp_aaaa" "$GITDRIP_EXCHANGE/results" "$GITDRIP_HOME/logs"'
rm -f "$W/api/s.js"
# commit via bridge
"$G" bridge w8 watch-scan app --commit >/dev/null 2>&1
ok "--commit pushed one batch under cap" '[[ "$(git -C "$REMOTE" rev-list --count main)" == 1 ]]'
# locked
echo "$$ $(date +%s)" > "$GITDRIP_HOME/locks/app.lock"; echo "export const l=1" > "$W/api/l.js"; old "$W/api/l.js"
"$G" bridge w9 watch-scan app --settle 1 >/dev/null 2>&1; ok "locked project -> PENDING exit 4" '[[ "$(st w9 state)" == PENDING && "$(st w9 exit_code)" == 4 ]]'
rm -f "$GITDRIP_HOME/locks/app.lock"
# tick mode
"$G" bridge w10 watch-scan app --tick bogus >/dev/null 2>&1; ok "bad tick mode -> ERROR" '[[ "$(st w10 state)" == ERROR ]]'
"$G" watch app --tick scan >/dev/null; ok "tick mode stored" '[[ "$(jq -r .tick "$P/watch.json")" == scan ]]'
ok "status shows tick" '"$G" watch app --status | grep -q "cron tick: scan"'
n0="$(nb)"; "$G" schedule tick >/dev/null 2>&1; ok "schedule tick runs a scan pass (picks up l.js)" '[[ "$(nb)" -gt "$n0" ]]'
echo "export const m=1" > "$W/api/m.js"; old "$W/api/m.js"; "$G" watch app --tick off >/dev/null; n1="$(nb)"
"$G" schedule tick >/dev/null 2>&1; ok "tick off -> no scan" '[[ "$(nb)" == "$n1" ]]'
"$G" watch app --tick commit >/dev/null; c0="$(git -C "$REMOTE" rev-list --count main)"; "$G" schedule tick >/dev/null 2>&1
ok "tick commit -> 1 commit, scan done" '[[ "$(git -C "$REMOTE" rev-list --count main)" == $((c0 + 1)) ]]'
"$G" watch app --dir "$W" >/dev/null; ok "re-configuring dir keeps tick mode" '[[ "$(jq -r .tick "$P/watch.json")" == commit ]]'
"$G" watch app --tick weird >/dev/null 2>&1; ok "bad tick mode refused" '[[ $? -ne 0 ]]'
echo "---- $pass passed, $fail failed"; [[ $fail -eq 0 ]]
