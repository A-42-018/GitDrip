#!/usr/bin/env bash
# P1 acceptance test. Uses a temp GITDRIP_HOME; touches nothing real.
set -euo pipefail
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export GITDRIP_HOME="$(mktemp -d)"
export GITDRIP_BIN_DIR="$GITDRIP_HOME/bin"
unset PREFIX
pass=0; fail=0
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }

bash "$APP/install.sh" >/dev/null 2>&1
G="$GITDRIP_BIN_DIR/gitdrip"

ok "install links CLI"          '[[ -x "$G" ]]'
ok "config.json created"        '[[ -f "$GITDRIP_HOME/config.json" ]]'
ok "--help works"               '"$G" --help | grep -q "Usage: gitdrip"'
rc=0; "$G" nope >/dev/null 2>&1 || rc=$?
ok "unknown cmd exits 2"        '[[ $rc -eq 2 ]]'
"$G" log "test"
ok "log writes execution.log"   'grep -q "\[INFO\] test" "$GITDRIP_HOME/logs/execution.log"'
"$G" log "token gh""p_abcdefghijklmnopqrstuvwxyz0123456789"
ok "log redacts tokens"         '! grep -q "ghp_abcdefghij" "$GITDRIP_HOME/logs/execution.log"'
ok "config get"                 '[[ "$("$G" config get default_branch)" == "main" ]]'
if command -v jq >/dev/null; then
  "$G" config set max_commits_per_day 2
  ok "config set typed number"  '[[ "$(jq .max_commits_per_day "$GITDRIP_HOME/config.json")" == "2" ]]'
fi
sed -i 's/"log_level": "INFO"/"log_level": "ERROR"/' "$GITDRIP_HOME/config.json"
"$G" log "hidden"
ok "log level filters INFO"     '! grep -q "hidden" "$GITDRIP_HOME/logs/execution.log"'
ok "logs command"               '"$G" logs -n 5 >/dev/null'

rm -rf "$GITDRIP_HOME"
echo "passed=$pass failed=$fail"
[[ $fail -eq 0 ]]
