#!/usr/bin/env bash
# P5 acceptance test: auth, secret scanner, preflight eligibility, push-error classifier. Needs git + jq.
set -euo pipefail
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export GITDRIP_HOME="$(mktemp -d)"; export GITDRIP_BIN_DIR="$GITDRIP_HOME/bin"; unset PREFIX
command -v jq >/dev/null && command -v git >/dev/null || { echo "SKIP: jq/git missing"; exit 0; }
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
pass=0; fail=0
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }
bash "$APP/install.sh" >/dev/null 2>&1
G="$GITDRIP_BIN_DIR/gitdrip"
TOK="ghp_$(printf 'A1b2C3d4E5%.0s' 1 2 3 4)"      # 40-char fake token
T="$(mktemp -d)"; REMOTE="$T/remote.git"; git init -q --bare "$REMOTE"; git -C "$REMOTE" symbolic-ref HEAD refs/heads/main

# --- auth
rc=0; echo "nonsense" | "$G" auth set --user alif >/dev/null 2>&1 || rc=$?
ok "auth set rejects malformed token" '[[ $rc -ne 0 && ! -e "$GITDRIP_HOME/credentials" ]]'
rc=0; "$G" auth set --user alif "$TOK" >/dev/null 2>&1 || rc=$?
ok "auth set refuses token on argv" '[[ $rc -ne 0 ]]'
echo "$TOK" | "$G" auth set --user alif >/dev/null
CR="$GITDRIP_HOME/credentials"
ok "credentials file chmod 600" '[[ "$(stat -c %a "$CR")" == 600 ]]'
chmod 644 "$CR"; "$G" auth status >/dev/null
ok "auth status repairs loose perms, masks token" '[[ "$(stat -c %a "$CR")" == 600 && "$("$G" auth status)" != *"$TOK"* ]]'
out="$(printf 'protocol=https\nhost=github.com\n\n' | "$G" credential-helper get)"
ok "helper answers for https github.com" '[[ "$out" == *"username=alif"* && "$out" == *"password=$TOK"* ]]'
ok "helper silent for other host" '[[ -z "$(printf "protocol=https\nhost=evil.com\n\n" | "$G" credential-helper get)" ]]'
ok "helper silent for http" '[[ -z "$(printf "protocol=http\nhost=github.com\n\n" | "$G" credential-helper get)" ]]'
ok "helper store/erase are no-ops" 'printf "protocol=https\nhost=github.com\nusername=x\npassword=y\n\n" | "$G" credential-helper store; [[ "$(sed -n 2p "$CR")" == "token=$TOK" ]]'
# wired through real git
out="$( (source "$APP/lib/common.sh"; GITDRIP_APP="$APP"; source "$APP/lib/logger.sh"; source "$APP/lib/auth.sh"; auth_git_args
  printf 'protocol=https\nhost=github.com\n\n' | GIT_TERMINAL_PROMPT=0 git "${AUTH_ARGS[@]}" credential fill) 2>&1 )"
ok "git credential fill gets token via helper" '[[ "$out" == *"password=$TOK"* ]]'

# --- scanner
S="$T/sample"; mkdir -p "$S"/{src/auth,docs,cfg}
echo "# Sample" > "$S/README.md"; echo "export const ok=1" > "$S/src/auth/ok.js"
echo "const t='$TOK'" > "$S/src/auth/leak.js"
echo "AKIA""ABCDEFGHIJKLMNOP" > "$S/docs/aws.md"
printf -- '-----BEGIN RSA PRIVATE'' KEY-----\nxx\n' > "$S/cfg/k.txt"
echo 'pass''word = "supersecretvalue123456"' > "$S/cfg/app.conf"
echo "const demo='$TOK' // gitdrip:allow" > "$S/cfg/demo.js"
echo "KEY=1" > "$S/cfg/.env.example"; echo "KEY=1" > "$S/cfg/.env"
out="$("$G" scan "$S" 2>&1)" || true; rc=0; "$G" scan "$S" >/dev/null 2>&1 || rc=$?
ok "scan exits 1 on hits" '[[ $rc -eq 1 ]]'
ok "scan flags github token, aws key, private key, generic, .env" '[[ "$out" == *"src/auth/leak.js:1: github_token"* && "$out" == *"docs/aws.md:1: aws_access_key"* && "$out" == *"cfg/k.txt:1: private_key"* && "$out" == *"cfg/app.conf:1: generic_secret"* && "$out" == *"cfg/.env:0: secret_filename"* ]]'
ok "scan ignores clean file, .env.example, gitdrip:allow line" '[[ "$out" != *"ok.js"* && "$out" != *".env.example"* && "$out" != *"demo.js"* ]]'
ok "scan output never contains the secret" '[[ "$out" != *"$TOK"* && "$out" != *"AKIA""ABCDEFGHIJKLMNOP"* ]]'

# --- integration: leaking batch blocked, clean batches fine
CLEAN="$T/clean"; mkdir -p "$CLEAN"/{a,b}; echo 1 > "$CLEAN/a/one.js"; echo 2 > "$CLEAN/b/two.js"; echo "const t='$TOK'" > "$CLEAN/b/leak.js"
"$G" import "$CLEAN" --name proj --max 5 >/dev/null
# importer only excludes by filename, so the content leak must reach the scanner
"$G" repo-init proj --remote "$REMOTE" --user Alif --email "1+alif@users.noreply.github.com" >/dev/null
P="$GITDRIP_HOME/projects/proj"; R="$P/repo"; B="$P/batches.json"
bid="$(jq -r '.batches[]|select(.files|any(test("leak")))|.id' "$B")"
good="$(jq -r '.batches[]|select(.files|any(test("leak"))|not)|.id' "$B" | head -n1)"
before="$(git -C "$R" rev-list --count HEAD 2>/dev/null || echo 0)"
tid="$("$G" task-create proj "$bid")"; rc=0; "$G" task-run "$tid" >/dev/null 2>&1 || rc=$?
tf="$GITDRIP_HOME/tasks/$tid.json"
ok "leaking batch: task FAILED secret_blocked, exit 1" '[[ $rc -eq 1 && "$(jq -r .state "$tf")" == FAILED && "$(jq -r .reason "$tf")" == secret_blocked && "$(jq -r .error_class "$tf")" == secret ]]'
ok "leaking batch: no commit, batch PENDING, file not copied to repo" '[[ "$(git -C "$R" rev-list --count HEAD 2>/dev/null || echo 0)" == "$before" && "$(jq -r ".batches[]|select(.id==$bid)|.status" "$B")" == PENDING && ! -e "$R/b/leak.js" ]]'
ok "block report names file:line:rule only" '[[ "$(jq -r .output "$tf")" == *"b/leak.js:1: github_token"* && "$(jq -r .output "$tf")" != *"$TOK"* ]]'
rc=0; "$G" run-batch proj "$bid" --dry >/dev/null 2>&1 || rc=$?
ok "dry-run also reports the block" '[[ $rc -eq 6 ]]'
rc=0; "$G" task-run "$("$G" task-create proj "$good")" >/dev/null 2>&1 || rc=$?
ok "clean batch still commits and pushes" '[[ $rc -eq 0 && "$(git -C "$REMOTE" rev-parse main)" == "$(git -C "$R" rev-parse HEAD)" ]]'
echo "const t='ok_value'" > "$P/source/b/leak.js"
rc=0; "$G" task-run "$("$G" task-create proj "$bid")" >/dev/null 2>&1 || rc=$?
ok "after the leak is removed, batch goes through" '[[ $rc -eq 0 && "$(jq -r ".batches[]|select(.id==$bid)|.status" "$B")" == SUCCESS ]]'

# --- no token anywhere
ok "token absent from logs, tasks, repo config, remote" '! grep -rqF "$TOK" "$GITDRIP_HOME/logs" "$GITDRIP_HOME/tasks" "$R/.git/config" "$P/batches.json" "$P/manifest.json"'
"$G" log "debug token $TOK and Authorization: Bearer abcdef1234567890 and password=hunter2hunter2" >/dev/null
ok "logger redacts token, bearer, password=" '! grep -qF "$TOK" "$GITDRIP_HOME/logs/execution.log" && ! grep -q "abcdef1234567890\|hunter2hunter2" "$GITDRIP_HOME/logs/execution.log"'
printf 'user=alif\ntoken=customtokenvalue99\n' > "$CR"; chmod 600 "$CR"
"$G" log "leaked customtokenvalue99 here" >/dev/null
ok "logger masks stored token literally (non-standard format)" '! grep -q customtokenvalue99 "$GITDRIP_HOME/logs/execution.log"'
echo "$TOK" | "$G" auth set --user alif >/dev/null

# --- .gitignore merge
ok "repo-init merges .gitignore (idempotent, keeps user lines)" '
  echo "mine/" >> "$R/.gitignore"; "$G" repo-init proj >/dev/null; "$G" repo-init proj >/dev/null
  [[ "$(grep -cx "\.env" "$R/.gitignore")" == 1 ]] && grep -qx "mine/" "$R/.gitignore" && grep -qx "\*.pem" "$R/.gitignore"'

# --- preflight / eligibility
rep="$("$G" preflight proj)"
ok "preflight report lists OK checks" '[[ "$rep" == *"[OK] author email"* && "$rep" == *"[OK] branch main"* && "$rep" == *"Private contributions"* ]]'
git -C "$R" config user.email "someone@example.com"
rep="$("$G" preflight proj)"; ok "preflight warns non-noreply email" '[[ "$rep" == *"[WARN] email"* ]]'
git -C "$R" config user.email "1+other@users.noreply.github.com"
rep="$("$G" preflight proj)"; ok "preflight warns noreply user != authenticated user" '[[ "$rep" == *"differs from authenticated user"* ]]'
git -C "$R" config user.email "1+alif@users.noreply.github.com"
git -C "$R" remote set-url origin "https://oauth:$TOK@github.com/a/b.git"
rc=0; rep="$("$G" preflight proj)" || rc=$?
ok "preflight BLOCKs remote URL with credentials (token not echoed)" '[[ $rc -eq 1 && "$rep" == *"[BLOCK] remote URL contains credentials"* && "$rep" != *"$TOK"* ]]'
git -C "$R" remote set-url origin "https://gitlab.com/a/b.git"; rep="$("$G" preflight proj)"
ok "preflight warns remote not github.com" '[[ "$rep" == *"not github.com"* ]]'
git -C "$R" remote set-url origin "https://github.com/a/b.git"; "$G" auth clear >/dev/null; rep="$("$G" preflight proj)"
ok "preflight warns github https remote without credentials" '[[ "$rep" == *"no credentials stored"* ]]'
"$G" config set max_commits_per_day 1; rep="$("$G" preflight proj)"
ok "preflight warns when daily cap reached" '[[ "$rep" == *"max_commits_per_day=1"* ]]'
git -C "$R" remote set-url origin "$REMOTE"
N2="$T/n2"; mkdir -p "$N2/m"; echo 1 > "$N2/m/x.js"; "$G" import "$N2" --name p2 >/dev/null
"$G" repo-init p2 --remote "$REMOTE" --user A --email "1+alif@users.noreply.github.com" >/dev/null; R2="$GITDRIP_HOME/projects/p2/repo"
rc=0; git -C "$R2" config user.email ""; "$G" run-batch p2 1 >/dev/null 2>&1 || rc=$?
ok "missing identity still blocks run-batch" '[[ $rc -ne 0 ]]'

# --- push error classifier
cls() { (source "$APP/lib/common.sh"; source "$APP/lib/logger.sh"; source "$APP/lib/auth.sh"; printf '%s' "$1" | classify_push_error); }
ok "classify auth"      '[[ "$(cls "remote: Invalid username or password.
fatal: Authentication failed for https://github.com/a/b.git/")" == auth && "$(cls "fatal: could not read Username for https://github.com: terminal prompts disabled")" == auth && "$(cls "remote: Permission to a/b.git denied to x.")" == auth ]]'
ok "classify transient" '[[ "$(cls "fatal: unable to access https://github.com/a/b.git/: Could not resolve host: github.com")" == transient && "$(cls "error: RPC failed; HTTP 502 curl 22 The requested URL returned error: 502")" == transient && "$(cls "fatal: the remote end hung up unexpectedly")" == transient ]]'
ok "classify conflict"  '[[ "$(cls " ! [rejected] HEAD -> main (fetch first)")" == conflict && "$(cls "Updates were rejected because the tip is behind (non-fast-forward)")" == conflict ]]'
ok "classify fatal"     '[[ "$(cls "remote: Repository not found.")" == fatal && "$(cls "remote: error: GH006: Protected branch update failed")" == fatal ]]'
ok "classify unknown"   '[[ "$(cls "something odd")" == unknown ]]'

# push failure class recorded on task
git -C "$R2" config user.email "1+alif@users.noreply.github.com"; git -C "$R2" remote set-url origin "$T/nope.git"
"$G" config set max_commits_per_day 3
tid="$("$G" task-create p2 1)"; rc=0; "$G" task-run "$tid" >/dev/null 2>&1 || rc=$?
ok "failed push: task FAILED with error_class (feeds P6)" '[[ $rc -eq 1 && "$(jq -r .error_class "$GITDRIP_HOME/tasks/$tid.json")" == fatal ]]'

# --- regression
bash "$APP/tests/test_p4.sh" >/dev/null 2>&1 && ok "P1-P4 tests still pass" true || ok "P1-P4 tests still pass" false

rm -rf "$GITDRIP_HOME" "$T"
echo "passed=$pass failed=$fail"; [[ $fail -eq 0 ]]
