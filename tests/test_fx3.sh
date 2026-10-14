#!/usr/bin/env bash
# FX3 acceptance test: daily cap 10 default, install config merge, bridge config-set, local-day eligibility. Needs bash 4+, git, jq.
set -euo pipefail
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v jq >/dev/null && command -v git >/dev/null || { echo "SKIP: jq/git missing"; exit 0; }
export GITDRIP_HOME="$(mktemp -d)"; export GITDRIP_BIN_DIR="$GITDRIP_HOME/bin"; unset PREFIX
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
T="$(mktemp -d)"; export GITDRIP_EXCHANGE="$T/ex"; mkdir -p "$GITDRIP_EXCHANGE"
pass=0; fail=0
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }
G="$GITDRIP_BIN_DIR/gitdrip"; CFG="$GITDRIP_HOME/config.json"
R() { echo "$GITDRIP_EXCHANGE/results/$1.json"; }

# --- install
bash "$APP/install.sh" >/dev/null 2>&1
ok "fresh install -> max_commits_per_day 10" '[[ "$(jq .max_commits_per_day "$CFG")" == 10 ]]'
echo '{"max_commits_per_day":3,"log_level":"DEBUG"}' > "$CFG"
out="$(bash "$APP/install.sh" 2>&1)"
ok "old config: 3 is kept" '[[ "$(jq .max_commits_per_day "$CFG")" == 3 && "$(jq -r .log_level "$CFG")" == DEBUG ]]'
ok "old config: missing keys added" '[[ "$(jq .batch_max_files "$CFG")" == 5 && "$(jq .missed_grace_minutes "$CFG")" == 10 ]]'
ok "install prints the cap" '[[ "$out" == *"config: max_commits_per_day is 3"* ]]'

# --- config-set
"$G" bridge c1 config-set max_commits_per_day 10 >/dev/null 2>&1 || true
ok "config-set 10 -> OK, engine value changed" 'grep -q "\"state\":\"OK\"" "$(R c1)" && grep -q "max_commits_per_day=10" "$(R c1)" && [[ "$(jq .max_commits_per_day "$CFG")" == 10 ]]'
"$G" bridge c2 config-set max_commits_per_day 1 >/dev/null 2>&1 || true
ok "config-set 1 -> OK" '[[ "$(jq .max_commits_per_day "$CFG")" == 1 ]]'
n=3
for bad in 0 11 abc "" -1 "5; rm -rf x"; do
  n=$((n+1)); "$G" bridge "c$n" config-set max_commits_per_day "$bad" >/dev/null 2>&1 || true
  ok "config-set value '$bad' -> ERROR, value unchanged" 'grep -q "\"state\":\"ERROR\"" "$(R c$n)" && [[ "$(jq .max_commits_per_day "$CFG")" == 1 ]]'
done
"$G" bridge c9 config-set dry_run true >/dev/null 2>&1 || true
ok "config-set unknown key -> ERROR, config untouched" 'grep -q "\"state\":\"ERROR\"" "$(R c9)" && [[ "$(jq .dry_run "$CFG")" != true ]]'

# --- local-day eligibility (Asia/Dhaka, UTC+6): a commit at 01:00 local belongs to the local day, not yesterday's UTC day
"$G" bridge c10 config-set max_commits_per_day 1 >/dev/null 2>&1
S="$T/src"; mkdir -p "$S/m"; echo 1 > "$S/m/x.js"; echo 2 > "$S/m/y.js"
"$G" import "$S" --name proj >/dev/null
"$G" repo-init proj --user alif --email "1+alif@users.noreply.github.com" >/dev/null 2>&1 || true
B="$GITDRIP_HOME/projects/proj/batches.json"
jq '.batches[0].status="SUCCESS" | .batches[0].updated_at="2026-10-05T19:00:00Z"' "$B" > "$B.t" && mv "$B.t" "$B"   # = 2026-10-06 01:00 Dhaka
export TZ=Asia/Dhaka
NOW="$(TZ=UTC date -d '2026-10-06 03:00:00' +%s 2>/dev/null || date -j -u -f '%Y-%m-%d %H:%M:%S' '2026-10-06 03:00:00' +%s)"   # 09:00 Dhaka, same local day
rep="$(GITDRIP_NOW=$NOW "$G" preflight proj 2>&1 || true)"
ok "01:00 local commit counts on the local day (cap 1 reached)" '[[ "$rep" == *"1 commit(s) already today"* ]]'
NOW2="$(TZ=UTC date -d '2026-10-07 03:00:00' +%s 2>/dev/null || date -j -u -f '%Y-%m-%d %H:%M:%S' '2026-10-07 03:00:00' +%s)"   # next local day
rep="$(GITDRIP_NOW=$NOW2 "$G" preflight proj 2>&1 || true)"
ok "next local day starts at 0 commits" '[[ "$rep" == *"0/1 commits today"* ]]'
echo "passed=$pass failed=$fail"; rm -rf "$GITDRIP_HOME" "$T"; [[ $fail -eq 0 ]]
