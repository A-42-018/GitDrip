#!/usr/bin/env bash
# P16 acceptance test: backup/restore safety + config export. No jq needed (import test skipped without jq).
set -u
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export GITDRIP_HOME="$(mktemp -d)"; export GITDRIP_BIN_DIR="$GITDRIP_HOME/bin"; unset PREFIX
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 GITDRIP_NET=online
T="$(mktemp -d)"; trap 'rm -rf "$GITDRIP_HOME" "$T"' EXIT
pass=0; fail=0
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }
bash "$APP/install.sh" >/dev/null 2>&1
G="$GITDRIP_BIN_DIR/gitdrip"; H="$GITDRIP_HOME"
mkdir -p "$H/projects/demo/repo/.git" "$H/projects/demo/source" "$H/locks"
echo '{"batches":[{"id":1,"status":"SUCCESS"}]}' > "$H/projects/demo/batches.json"
echo big > "$H/projects/demo/source/a.txt"; echo r > "$H/projects/demo/repo/x"
echo '{"id":"t1","state":"SUCCESS"}' > "$H/tasks/t1.json"
echo '[]' > "$H/schedules.json"
printf 'user=u\ntoken=ghp_SECRETSECRETSECRET\n' > "$H/credentials"; echo lock > "$H/locks/demo.lock"

B="$T/b.tar.gz"
ok "backup creates archive" '"$G" backup "$B" >/dev/null && [[ -s "$B" ]]'
ok "backup is chmod 600" '[[ "$(stat -c %a "$B")" == 600 ]]'
ok "backup has no credentials/locks/logs" '! tar -tzf "$B" | grep -Eq "^(credentials|locks|logs)"'
ok "backup has no token text" '! tar -xzOf "$B" 2>/dev/null | grep -q ghp_SECRET'
ok "lean backup omits repo/source" '! tar -tzf "$B" | grep -Eq "projects/demo/(repo|source)"'
ok "lean backup keeps batches + tasks" 'tar -tzf "$B" | grep -q "projects/demo/batches.json" && tar -tzf "$B" | grep -q "tasks/t1.json"'
F="$T/f.tar.gz"; "$G" backup "$F" --full >/dev/null
ok "--full includes source" 'tar -tzf "$F" | grep -q "projects/demo/source/a.txt"'

# restore: clash refused, force ok, local repo/source kept
ok "restore refuses clash without --force" '! "$G" restore "$B" >/dev/null 2>&1'
echo '{"batches":[{"id":1,"status":"PENDING"}]}' > "$H/projects/demo/batches.json"
ok "restore --force overwrites state" '"$G" restore "$B" --force >/dev/null 2>&1 && grep -q SUCCESS "$H/projects/demo/batches.json"'
ok "restore keeps local repo + source (lean)" '[[ -f "$H/projects/demo/repo/x" && -f "$H/projects/demo/source/a.txt" ]]'
ok "restore leaves credentials untouched" 'grep -q ghp_SECRET "$H/credentials"'

# restore into empty home
rm -rf "$H/projects" "$H/tasks"; mkdir -p "$H/projects" "$H/tasks"
ok "restore into empty home" '"$G" restore "$F" >/dev/null 2>&1 && [[ -f "$H/projects/demo/source/a.txt" && -f "$H/tasks/t1.json" ]]'

# hostile archives
mkdir -p "$T/ev"; (cd "$T/ev" && mkdir -p tasks && ln -s /etc/passwd tasks/evil && tar -czf "$T/link.tgz" tasks)
ok "restore refuses symlink entries" '! "$G" restore "$T/link.tgz" >/dev/null 2>&1'
(cd "$T/ev" && rm -rf tasks && echo x > evil.txt && tar -czf "$T/top.tgz" evil.txt)
ok "restore refuses unexpected top-level" '! "$G" restore "$T/top.tgz" >/dev/null 2>&1'
(cd "$T/ev" && mkdir -p credentials && echo x > credentials/a && tar -czf "$T/cred.tgz" credentials)
ok "restore refuses credentials entry" '! "$G" restore "$T/cred.tgz" >/dev/null 2>&1'
(cd "$T/ev" && mkdir -p tasks && echo x > tasks/ok && tar -czf "$T/dd.tgz" -P --transform='s,tasks/ok,tasks/../../esc,' tasks/ok 2>/dev/null)
ok "restore refuses .. paths" '! "$G" restore "$T/dd.tgz" >/dev/null 2>&1 && [[ ! -e "$H/../esc" ]]'
echo notatar > "$T/bad.tgz"
ok "restore refuses non-archive" '! "$G" restore "$T/bad.tgz" >/dev/null 2>&1'
ok "no stage dirs left behind" '! ls -a "$H" | grep -q "^.restore"'

# config export / import
ok "config export copies config" '"$G" config export "$T/c.json" >/dev/null && cmp -s "$T/c.json" "$H/config.json"'
if command -v jq >/dev/null; then
  echo '{"max_commits_per_day":2,"batch_max_files":"x","version":99,"junk":1,"dry_run":true}' > "$T/in.json"
  "$G" config import "$T/in.json" >/dev/null 2>&1
  ok "import applies typed known keys" '[[ "$(jq -r .max_commits_per_day "$H/config.json")" == 2 && "$(jq -r .dry_run "$H/config.json")" == true ]]'
  ok "import ignores mistyped/unknown/version" '[[ "$(jq -r .batch_max_files "$H/config.json")" == 5 && "$(jq -r .version "$H/config.json")" == 1 ]] && ! jq -e has\(\"junk\"\) "$H/config.json" >/dev/null'
  ok "import rejects non-object" '! (echo "[1]" > "$T/arr.json"; "$G" config import "$T/arr.json" >/dev/null 2>&1)'
else echo "SKIP config import (no jq)"; fi

# --- P16b: bridge backup / restore / config (no jq needed for backup+restore)
export GITDRIP_EXCHANGE="$T/ex"; mkdir -p "$GITDRIP_EXCHANGE"
R() { echo "$GITDRIP_EXCHANGE/results/$1.json"; }
"$G" bridge bk1 backup >/dev/null 2>&1
BF="$(ls "$GITDRIP_EXCHANGE/backups"/gitdrip-backup-*.tar.gz 2>/dev/null | head -1)"
ok "bridge backup: OK result + archive in <ex>/backups" 'grep -q "\"state\":\"OK\"" "$(R bk1)" && [[ -s "$BF" ]]'
ok "bridge backup: no credentials inside" '! tar -tzf "$BF" | grep -Eq "^(credentials|locks|logs)"'
"$G" bridge bk2 backup --full >/dev/null 2>&1; sleep 1
ok "bridge backup --full includes source" 'tar -tzf "$(ls -t "$GITDRIP_EXCHANGE"/backups/gitdrip-backup-*.tar.gz | head -1)" | grep -q "projects/demo/source/a.txt"'
BN="$(basename "$BF")"
"$G" bridge rs1 restore "$BN" >/dev/null 2>&1 || true
ok "bridge restore: clash without --force -> ERROR with reason" 'grep -q "\"state\":\"ERROR\"" "$(R rs1)" && grep -q "already exist" "$(R rs1)"'
"$G" bridge rs2 restore "$BN" --force >/dev/null 2>&1 || true
ok "bridge restore --force: OK" 'grep -q "\"state\":\"OK\"" "$(R rs2)" && grep -q "restored 1" "$(R rs2)"'
"$G" bridge rs3 restore "../../etc/passwd" >/dev/null 2>&1 || true
ok "bridge restore: path traversal name refused" 'grep -q "invalid backup name" "$(R rs3)"'
"$G" bridge rs4 restore "gitdrip-backup-20200101-000000.tar.gz" >/dev/null 2>&1 || true
ok "bridge restore: missing file -> ERROR" 'grep -q "backup not found" "$(R rs4)"'
ln -s /etc/passwd "$GITDRIP_EXCHANGE/backups/gitdrip-backup-20200101-000001.tar.gz"
"$G" bridge rs5 restore "gitdrip-backup-20200101-000001.tar.gz" >/dev/null 2>&1 || true
ok "bridge restore: symlink refused" 'grep -q "backup not found" "$(R rs5)"'
"$G" bridge rs6 restore "evil.tar.gz" >/dev/null 2>&1 || true
ok "bridge restore: arbitrary name refused" 'grep -q "invalid backup name" "$(R rs6)"'
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do touch "$GITDRIP_EXCHANGE/backups/gitdrip-backup-2021010$((i%10))-0000$((10+i)).tar.gz"; done
"$G" bridge bk3 backup >/dev/null 2>&1
ok "bridge backup keeps at most 10 backups" '[[ "$(ls "$GITDRIP_EXCHANGE"/backups | grep -c "^gitdrip-backup-.*tar.gz$")" -le 10 ]]'
"$G" bridge cx1 cfg-export >/dev/null 2>&1
CF="$(ls "$GITDRIP_EXCHANGE/backups"/gitdrip-config-*.json 2>/dev/null | head -1)"
ok "bridge cfg-export: OK + file equals config" 'grep -q "\"state\":\"OK\"" "$(R cx1)" && cmp -s "$CF" "$H/config.json"'
"$G" bridge cx2 cfg-import "../config.json" >/dev/null 2>&1 || true
ok "bridge cfg-import: bad name refused" 'grep -q "invalid config name" "$(R cx2)"'
if command -v jq >/dev/null; then
  "$G" bridge cx3 cfg-import "$(basename "$CF")" >/dev/null 2>&1 || true
  ok "bridge cfg-import: OK" 'grep -q "\"state\":\"OK\"" "$(R cx3)"'
else echo "SKIP bridge cfg-import (no jq)"; fi
ok "no result tmp files left" '[[ -z "$(ls "$GITDRIP_EXCHANGE/results" | grep "\.tmp\." || true)" ]]'
ok "version is 0.23.0" '"$G" version | grep -q 0.23.0'
echo "P16: $pass passed, $fail failed"; [[ $fail -eq 0 ]]
