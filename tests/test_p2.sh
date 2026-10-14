#!/usr/bin/env bash
# P2 acceptance test. Temp GITDRIP_HOME; needs jq.
set -euo pipefail
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export GITDRIP_HOME="$(mktemp -d)"; export GITDRIP_BIN_DIR="$GITDRIP_HOME/bin"; unset PREFIX
command -v jq >/dev/null || { echo "SKIP: jq missing"; exit 0; }
pass=0; fail=0
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }
bash "$APP/install.sh" >/dev/null 2>&1
G="$GITDRIP_BIN_DIR/gitdrip"

S="$(mktemp -d)/sample"; mkdir -p "$S"/{src/auth,src/api,tests,.git,node_modules/x}
echo "# Sample" > "$S/README.md"; echo "{}" > "$S/package.json"
for i in 1 2 3 4 5 6 7; do echo "export const a$i=$i" > "$S/src/auth/f$i.js"; done
echo "x" > "$S/src/api/route.js"; echo "t" > "$S/tests/test_a.js"
echo "SECRET=1" > "$S/.env"; echo "k" > "$S/server.pem"; echo "g" > "$S/.git/HEAD"; echo "n" > "$S/node_modules/x/i.js"

"$G" import "$S" --name Sample >/dev/null
M="$GITDRIP_HOME/projects/sample/manifest.json"; B="$GITDRIP_HOME/projects/sample/batches.json"
ok "manifest created"            '[[ -f "$M" ]]'
ok "manifest has 11 files"       '[[ "$(jq .file_count "$M")" == 11 ]]'
ok "sha256 correct"              '[[ "$(jq -r ".files[]|select(.path==\"README.md\")|.sha256" "$M")" == "$(sha256sum "$S/README.md"|cut -d" " -f1)" ]]'
ok ".env/.pem excluded + recorded" '[[ "$(jq -r ".excluded|sort|join(\",\")" "$M")" == ".env,server.pem" ]]'
ok "no secrets in source copy"   '[[ ! -e "$GITDRIP_HOME/projects/sample/source/.env" ]]'
ok "no .git/node_modules copied" '[[ ! -e "$GITDRIP_HOME/projects/sample/source/.git" && ! -e "$GITDRIP_HOME/projects/sample/source/node_modules" ]]'
ok "batches generated (>=3)"     '[[ "$(jq ".batches|length" "$B")" -ge 3 ]]'
ok "max 5 files per batch"       '[[ "$(jq "[.batches[].files|length]|max" "$B")" -le 5 ]]'
ok "all files batched once"      '[[ "$(jq "[.batches[].files[]]|unique|length" "$B")" == 11 && "$(jq "[.batches[].files[]]|length" "$B")" == 11 ]]'
ok "auth split in 2 parts"       '[[ "$(jq "[.batches[]|select(.name|startswith(\"src/auth\"))]|length" "$B")" == 2 ]]'
ok "root batch is docs/chore msg" 'jq -e ".batches[0].message|test(\"project root files\")" "$B" >/dev/null'
ok "tests batch typed test"      'jq -e "[.batches[]|select(.name==\"tests\")|.message|startswith(\"test:\")]|all" "$B" >/dev/null'
ok "batches cmd shows available" '"$G" batches sample | grep -q "commits available: 5 of 5"'
ok "projects lists sample"       '"$G" projects | grep -q "^sample"'
rc=0; "$G" import "$S" --name sample >/dev/null 2>&1 || rc=$?
ok "re-import refused w/o force" '[[ $rc -ne 0 ]]'
jq '.batches[0].status="SUCCESS"' "$B" > "$B.t" && mv "$B.t" "$B"
rc=0; "$G" batch-create sample >/dev/null 2>&1 || rc=$?
ok "regen refused after progress" '[[ $rc -ne 0 ]]'
rc=0; "$G" import "$S" --name sample --force >/dev/null 2>&1 || rc=$?
ok "force re-import refused after progress" '[[ $rc -ne 0 ]]'

rm -rf "$GITDRIP_HOME" "$(dirname "$S")"
echo "passed=$pass failed=$fail"; [[ $fail -eq 0 ]]
