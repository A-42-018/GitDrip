#!/usr/bin/env bash
# P3 acceptance test: git engine against a local bare remote. Needs git + jq.
set -euo pipefail
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export GITDRIP_HOME="$(mktemp -d)"; export GITDRIP_BIN_DIR="$GITDRIP_HOME/bin"; unset PREFIX
command -v jq >/dev/null && command -v git >/dev/null || { echo "SKIP: jq/git missing"; exit 0; }
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
pass=0; fail=0
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }
bash "$APP/install.sh" >/dev/null 2>&1
G="$GITDRIP_BIN_DIR/gitdrip"

T="$(mktemp -d)"; S="$T/sample"; REMOTE="$T/remote.git"; mkdir -p "$S"/{src/auth,src/api,tests}
echo "# Sample" > "$S/README.md"
for i in 1 2 3; do echo "export const a$i=$i" > "$S/src/auth/f$i.js"; done
echo "x" > "$S/src/api/my route.js"; echo "t" > "$S/tests/test_a.js"
git init -q --bare "$REMOTE"; git -C "$REMOTE" symbolic-ref HEAD refs/heads/main
"$G" import "$S" --name sample >/dev/null
P="$GITDRIP_HOME/projects/sample"; B="$P/batches.json"; R="$P/repo"
count() { git -C "$R" rev-list --count HEAD 2>/dev/null || echo 0; }
rcount() { git -C "$REMOTE" rev-list --count main 2>/dev/null || echo 0; }

rc=0; "$G" repo-init sample --remote "https://user:pw@github.com/a/b.git" >/dev/null 2>&1 || rc=$?
ok "creds in remote URL rejected" '[[ $rc -ne 0 ]]'
rc=0; "$G" repo-init sample --remote "https://github.com/a/b.git" --user A --email a@example.com >/dev/null 2>&1 || true
"$G" repo-init sample --remote "$REMOTE" --user Alif --email "1+alif@users.noreply.github.com" >/dev/null
ok "repo-init: repo + origin + branch main" '[[ "$(git -C "$R" remote get-url origin)" == "$REMOTE" && "$(git -C "$R" symbolic-ref --short HEAD)" == main ]]'

rc=0; "$G" run-batch sample 1 --dry >/dev/null || rc=$?
ok "dry-run: no commit, no copy" '[[ $rc -eq 0 && "$(count)" == 0 && ! -e "$R/README.md" ]]'
ok "dry-run leaves batch PENDING" '[[ "$(jq -r ".batches[0].status" "$B")" == PENDING ]]'

"$G" run-batch sample 1 >/dev/null
ok "run-batch 1: commit pushed to remote" '[[ "$(count)" == 1 && "$(rcount)" == 1 ]]'
ok "batch 1 SUCCESS + commit hash stored" '[[ "$(jq -r ".batches[0].status" "$B")" == SUCCESS && "$(jq -r ".batches[0].commit" "$B")" == "$(git -C "$R" rev-parse HEAD)" ]]'
ok "commit has only batch files" '[[ "$(git -C "$R" show --name-only --format= HEAD | sort | tr "\n" " ")" == "$(jq -r ".batches[0].files|sort|join(\" \")" "$B") " ]]'
ok "commit message = batch message" '[[ "$(git -C "$R" log -1 --format=%s)" == "$(jq -r ".batches[0].message" "$B")" ]]'
ok "author date is now (not backdated)" '(( $(date +%s) - $(git -C "$R" log -1 --format=%at) < 120 ))'
rc=0; out="$("$G" run-batch sample 1)" || rc=$?
ok "rerun does nothing" '[[ $rc -eq 0 && "$(count)" == 1 && "$(rcount)" == 1 && "$out" == *"already SUCCESS"* ]]'

# no-change = STOP: put batch 2 content in repo manually + commit, then run it
while IFS= read -r f; do mkdir -p "$R/$(dirname "$f")"; cp "$P/source/$f" "$R/$f"; done < <(jq -r '.batches[1].files[]' "$B")
git -C "$R" add -A && git -C "$R" commit -q -m "manual"; before="$(count)"
rc=0; "$G" run-batch sample 2 >/dev/null || rc=$?
ok "no change: STOP, exit 3, no commit" '[[ $rc -eq 3 && "$(count)" == "$before" ]]'
ok "no change: batch marked NOCHANGE" '[[ "$(jq -r ".batches[1].status" "$B")" == NOCHANGE ]]'

# unrelated staged file blocks commit
echo stray > "$R/stray.txt"; git -C "$R" add stray.txt; before="$(count)"
rc=0; "$G" run-batch sample 3 >/dev/null 2>&1 || rc=$?
ok "unrelated staged file: refused, no commit" '[[ $rc -ne 0 && "$(count)" == "$before" ]]'
git -C "$R" reset -q stray.txt; rm -f "$R/stray.txt"

# push failure keeps commit; rerun pushes without a new commit
git -C "$R" remote set-url origin "$T/nope.git"; before="$(count)"
rc=0; "$G" run-batch sample 3 >/dev/null 2>&1 || rc=$?
ok "push fail: exit!=0, local commit kept, status COMMITTED" '[[ $rc -ne 0 && "$(count)" == $((before+1)) && "$(jq -r ".batches[2].status" "$B")" == COMMITTED ]]'
git -C "$R" remote set-url origin "$REMOTE"
"$G" run-batch sample 3 >/dev/null
ok "resume: pushed, no duplicate commit" '[[ "$(count)" == $((before+1)) && "$(jq -r ".batches[2].status" "$B")" == SUCCESS && "$(git -C "$REMOTE" rev-parse main)" == "$(git -C "$R" rev-parse HEAD)" ]]'

ok "filename with space committed" '"$G" run-batch sample 4 >/dev/null; git -C "$R" ls-files | grep -q "my route.js"'
ok "no empty commits in history" '[[ -z "$(for c in $(git -C "$R" rev-list HEAD); do [[ -n "$(git -C "$R" show --name-only --format= $c)" ]] || echo $c; done)" ]]'

# preflight warnings
git -C "$R" config user.email "someone@example.com"; git -C "$R" checkout -q -b feature
echo "// new" >> "$P/source/tests/test_a.js"; jq '.batches[3].status="PENDING"' "$B" > "$B.t" && mv "$B.t" "$B"
"$G" run-batch sample 4 --no-push >/dev/null 2>"$T/err" || true
ok "preflight warns on non-noreply email" 'grep -q "not a noreply" "$T/err"'
ok "preflight warns on non-default branch" 'grep -q "not default" "$T/err"'
git -C "$R" config --unset user.email
echo "// more" >> "$P/source/tests/test_a.js"; jq '.batches[3].status="PENDING"' "$B" > "$B.t" && mv "$B.t" "$B"
rc=0; "$G" run-batch sample 4 >/dev/null 2>&1 || rc=$?
ok "missing identity blocks commit" '[[ $rc -ne 0 ]]'
gs="$("$G" git-status sample 2>&1)" || true
ok "git-status runs" '[[ "$gs" == branch:* ]]'

rm -rf "$GITDRIP_HOME" "$T"
echo "passed=$pass failed=$fail"; [[ $fail -eq 0 ]]
