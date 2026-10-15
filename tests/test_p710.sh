#!/usr/bin/env bash
# P7.10 acceptance test: watcher batches are visible in bridge results (origin/message/files) and a plan sync never drops them. Needs git + jq.
set -uo pipefail
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v jq >/dev/null && command -v git >/dev/null || { echo "SKIP: jq/git missing"; exit 0; }
export GITDRIP_HOME="$(mktemp -d)"; export GITDRIP_BIN_DIR="$GITDRIP_HOME/bin"; export GITDRIP_EXCHANGE="$(mktemp -d)"; unset PREFIX
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 GITDRIP_NET=online
T="$(mktemp -d)"; pass=0; fail=0
trap 'rm -rf "$GITDRIP_HOME" "$GITDRIP_EXCHANGE" "$T"' EXIT
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }
bash "$APP/install.sh" >/dev/null 2>&1
G="$GITDRIP_BIN_DIR/gitdrip"; EX="$GITDRIP_EXCHANGE"
W="$T/work"; mkdir -p "$W/api"; echo "export const a=1" > "$W/api/a.js"
"$G" import "$W" --name app --max 2 >/dev/null
P="$GITDRIP_HOME/projects/app"; B="$P/batches.json"
"$G" repo-init app --user Alif --email "1+alif@users.noreply.github.com" >/dev/null
rj() { jq -r "$2" "$EX/results/$1.json"; }
"$G" watch app --dir "$W" >/dev/null
echo "export const n=1" > "$W/api/n.js"; touch -d "-10 minutes" "$W/api/n.js"
"$G" bridge s0 watch-scan app --settle 60 >/dev/null 2>&1
ok "scan appended a watch batch" '[[ "$(jq "[.batches[]|select(.origin==\"watch\")]|length" "$B")" == 1 ]]'
"$G" bridge s1 status app >/dev/null 2>&1
ok "status lists batch 1 without watch fields" '[[ "$(rj s1 ".batches[0].origin // \"none\"")" == none && "$(rj s1 ".batches[0].files // \"none\"")" == none ]]'
ok "status lists watch batch with origin" '[[ "$(rj s1 ".batches[1].origin")" == watch ]]'
ok "watch batch carries message" '[[ "$(rj s1 ".batches[1].message")" == *api* ]]'
ok "watch batch carries files" '[[ "$(rj s1 ".batches[1].files|join(\",\")")" == "api/n.js" ]]'
ok "watch batch id continues the sequence" '[[ "$(rj s1 ".batches[1].id")" == 2 ]]'
# a plan sync must not drop the watcher batch (all batches still PENDING)
mkdir -p "$EX/app/source/api"; cp "$W/api/a.js" "$EX/app/source/api/a.js"
printf '{"project":"app","branch":"main","batches":[{"id":1,"message":"feat: add api module","files":["api/a.js"]}]}' > "$EX/app/plan.json"
"$G" bridge s2 sync app >/dev/null 2>&1
ok "sync returns OK" '[[ "$(rj s2 .state)" == OK ]]'
ok "sync keeps the watcher batch" '[[ "$(jq "[.batches[]|select(.origin==\"watch\")]|length" "$B")" == 1 && -f "$P/source/api/n.js" ]]'
ok "result still lists the watch batch" '[[ "$(rj s2 "[.batches[]|select(.origin==\"watch\")]|length")" == 1 ]]'
ok "history result carries watch batch too" '"$G" bridge s3 history app >/dev/null 2>&1; [[ "$(rj s3 "[.batches[]|select(.origin==\"watch\")]|length")" == 1 ]]'
ok "no secret/exclusion leak: .env still not imported" 'echo x=1 > "$W/.env"; touch -d "-10 minutes" "$W/.env"; "$G" bridge s4 watch-scan app --settle 60 >/dev/null 2>&1; [[ ! -e "$P/source/.env" ]]'
echo "---- $pass passed, $fail failed"; [[ $fail -eq 0 ]]
