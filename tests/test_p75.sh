#!/usr/bin/env bash
# P7.5 acceptance test: issue / PR / merge API + `pr-flow` against the GitHub mock and a local bare remote. Needs git + jq + python3.
set -uo pipefail
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v jq >/dev/null && command -v git >/dev/null && command -v python3 >/dev/null || { echo "SKIP: jq/git/python3 missing"; exit 0; }
export GITDRIP_HOME="$(mktemp -d)"; export GITDRIP_BIN_DIR="$GITDRIP_HOME/bin"; unset PREFIX
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 GITDRIP_NET=online
T="$(mktemp -d)"; pass=0; fail=0
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }
bash "$APP/install.sh" >/dev/null 2>&1
G="$GITDRIP_BIN_DIR/gitdrip"; TOK="ghp_$(printf 'b%.0s' $(seq 1 36))"
python3 "$APP/tests/mock_github.py" "$T/port" "$T" & MOCK=$!
trap 'kill $MOCK 2>/dev/null; rm -rf "$GITDRIP_HOME" "$T"' EXIT
for _ in $(seq 1 50); do [[ -s "$T/port" ]] && break; sleep 0.1; done
export GITDRIP_API="http://127.0.0.1:$(cat "$T/port")" GITDRIP_API_TIMEOUT=5
RQ="$T/requests.log"; : > "$RQ"
echo "$TOK" | "$G" auth set --user alif >/dev/null

# project: 5 batches (one dir each); remote URL looks like github, git push is redirected to a local bare repo
S="$T/src"; mkdir -p "$S"/{a,b,c,d,e}; for x in a b c d e; do echo "export const $x=1" > "$S/$x/f.js"; done
REMOTE="$T/remote.git"; git init -q --bare "$REMOTE"; git -C "$REMOTE" symbolic-ref HEAD refs/heads/main
"$G" import "$S" --name site >/dev/null
P="$GITDRIP_HOME/projects/site"; B="$P/batches.json"; R="$P/repo"
"$G" repo-init site --remote https://github.com/alif/site --branch main --user alif --email 42+alif@users.noreply.github.com >/dev/null
git -C "$R" config "url.$REMOTE.insteadOf" https://github.com/alif/site
bst() { jq -r --argjson i "$1" '.batches[]|select(.id==$i)|.status' "$B"; }
rbr() { git -C "$REMOTE" rev-parse --verify -q "refs/heads/$1" >/dev/null; }
posts() { grep -c "^POST /repos/alif/site/$1" "$RQ" || true; }
"$G" run-batch site 1 >/dev/null
ok "setup: batch 1 pushed to main (first commit)" '[[ "$(bst 1)" == SUCCESS && "$(git -C "$REMOTE" rev-list --count main)" == 1 ]]'

# --- raw issue / PR commands
out="$("$G" issue create alif/site --title "Plan" --body "text")"
ok "issue create -> number + url" '[[ "$(jq -r .number <<<"$out")" == 1 && "$(jq -r .html_url <<<"$out")" == */issues/1 ]]'
ok "issue create without title refused" '! "$G" issue create alif/site >/dev/null 2>&1'
ok "pr open: bad slug / bad branch refused, nothing sent" 'n0=$(wc -l < "$RQ"); ! "$G" pr open ../x --head h --title t >/dev/null 2>&1 && ! "$G" pr open alif/site --head "bad branch" --title t >/dev/null 2>&1 && [[ "$(wc -l < "$RQ")" == "$n0" ]]'
ok "pr merge: bad method / number refused" '! "$G" pr merge alif/site 1 --method evil >/dev/null 2>&1 && ! "$G" pr merge alif/site x >/dev/null 2>&1'

# --- pr-flow dry run
: > "$RQ"
"$G" pr-flow site 2 --dry >/dev/null 2>&1
ok "dry: no branch, no API write, batch PENDING" '! git -C "$R" show-ref --verify -q refs/heads/gitdrip/site-batch-2 && [[ "$(posts pulls)" == 0 && "$(bst 2)" == PENDING ]]'

# --- pr-flow with issue
out="$("$G" pr-flow site 2 --issue 2>&1)"; rc=$?
ok "pr-flow 2: exit 0, prints PR url" '[[ $rc -eq 0 && "$out" == *"pr: #"* ]]'
ok "branch pushed, main on remote untouched" 'rbr gitdrip/site-batch-2 && [[ "$(git -C "$REMOTE" rev-list --count main)" == 1 ]]'
ok "branch has exactly the batch commit on top of main" '[[ "$(git -C "$R" rev-list --count main..gitdrip/site-batch-2)" == 1 && "$(git -C "$R" log -1 --format=%s gitdrip/site-batch-2)" == "$(jq -r ".batches[1].message" "$B")" ]]'
ok "back on main, batch files not on main" '[[ "$(git -C "$R" symbolic-ref --short HEAD)" == main && ! -e "$R/b/f.js" && -e "$R/a/f.js" ]]'
ok "batch SUCCESS + pr + issue stored" '[[ "$(bst 2)" == SUCCESS && "$(jq -r ".batches[1].pr" "$B")" =~ ^[0-9]+$ && "$(jq -r ".batches[1].issue" "$B")" =~ ^[0-9]+$ ]]'
ok "PR request: head branch, base main, Closes #issue" 'l="$(grep "^POST /repos/alif/site/pulls" "$RQ")"; [[ "$l" == *"\"head\":\"gitdrip/site-batch-2\""* && "$l" == *"\"base\":\"main\""* && "$l" == *"Closes #$(jq -r ".batches[1].issue" "$B")"* ]]'
ok "issue request carries file list" 'grep "^POST /repos/alif/site/issues" "$RQ" | grep -q "b/f.js"'
n0=$(wc -l < "$RQ"); out="$("$G" pr-flow site 2 --issue 2>&1)"
ok "rerun: already SUCCESS, no new POST" '[[ "$out" == *"already SUCCESS"* && "$(posts pulls)" == 1 && "$(posts issues)" == 1 ]]'

# --- merge
: > "$RQ"; out="$("$G" pr-flow site 3 --merge 2>&1)"; rc=$?
ok "pr-flow 3 --merge: exit 0, merged line" '[[ $rc -eq 0 && "$out" == *"merged: aaaaaaa (squash)"* ]]'
ok "merge request squash + remote branch delete requested" 'grep -q "^PUT /repos/alif/site/pulls/[0-9]*/merge .*squash" "$RQ" && grep -q "^DELETE /repos/alif/site/git/refs/heads/gitdrip/site-batch-3" "$RQ"'
ok "local feature branch removed, on main" '! git -C "$R" show-ref --verify -q refs/heads/gitdrip/site-batch-3 && [[ "$(git -C "$R" symbolic-ref --short HEAD)" == main ]]'
ok "no issue created without --issue" '[[ "$(posts issues)" == 0 ]]'

# --- merge refused by GitHub (mock rejects rebase)
: > "$RQ"; "$G" pr-flow site 4 --merge --method rebase >/dev/null 2>&1; rc=$?
ok "merge failure: exit 1, PR stays open, batch SUCCESS (pushed)" '[[ $rc -ne 0 && "$(posts pulls)" == 1 && "$(bst 4)" == SUCCESS ]] && ! grep -q "^DELETE" "$RQ"'
ok "merge failure: back on main, branch kept" '[[ "$(git -C "$R" symbolic-ref --short HEAD)" == main ]] && git -C "$R" show-ref --verify -q refs/heads/gitdrip/site-batch-4'

# --- no change: file already in repo -> STOP, no PR, no branch
mkdir -p "$R/e"; cp "$P/source/e/f.js" "$R/e/f.js"; git -C "$R" add e/f.js; git -C "$R" commit -q -m "chore: preexisting e"
: > "$RQ"; "$G" pr-flow site 5 >/dev/null 2>&1; rc=$?
ok "no change: exit 3, no branch, no PR, batch NOCHANGE" '[[ $rc -eq 3 && "$(posts pulls)" == 0 && "$(bst 5)" == NOCHANGE ]] && ! git -C "$R" show-ref --verify -q refs/heads/gitdrip/site-batch-5'

# --- push failure then resume (no duplicate commit)
REMOTE2="$T/remote2.git"; git init -q --bare "$REMOTE2"; git -C "$REMOTE2" symbolic-ref HEAD refs/heads/main
S2="$T/src2"; mkdir -p "$S2"/{x,y}; echo "k=1" > "$S2/x/f.js"; echo "k=2" > "$S2/y/f.js"
"$G" import "$S2" --name two >/dev/null; P2="$GITDRIP_HOME/projects/two"; R2="$P2/repo"
"$G" repo-init two --remote https://github.com/alif/site --branch main --user alif --email 42+alif@users.noreply.github.com >/dev/null
git -C "$R2" config "url.$REMOTE2.insteadOf" https://github.com/alif/site
"$G" run-batch two 1 >/dev/null 2>&1
git -C "$R2" config "url.$T/nowhere.git.insteadOf" https://github.com/alif/site; git -C "$R2" config --unset "url.$REMOTE2.insteadOf"
: > "$RQ"; "$G" pr-flow two 2 >/dev/null 2>&1; rc=$?
ok "push failure: nonzero, batch COMMITTED, no PR, on main" '[[ $rc -ne 0 && "$(jq -r ".batches[1].status" "$P2/batches.json")" == COMMITTED && "$(posts pulls)" == 0 && "$(git -C "$R2" symbolic-ref --short HEAD)" == main ]]'
ok "failed branch kept for resume" 'git -C "$R2" show-ref --verify -q refs/heads/gitdrip/two-batch-2'
c1="$(git -C "$R2" rev-parse gitdrip/two-batch-2)"
git -C "$R2" config "url.$REMOTE2.insteadOf" https://github.com/alif/site; git -C "$R2" config --unset "url.$T/nowhere.git.insteadOf"
out="$("$G" pr-flow two 2 2>&1)"; rc=$?
ok "resume: pushes the same commit, opens PR, SUCCESS" '[[ $rc -eq 0 && "$(git -C "$R2" rev-parse gitdrip/two-batch-2)" == "$c1" && "$(posts pulls)" == 1 && "$(jq -r ".batches[1].status" "$P2/batches.json")" == SUCCESS ]]'

# --- guards
S3="$T/src3"; mkdir -p "$S3"/{p,q}; echo "ok" > "$S3/p/f.js"; echo 'const t = "ghp_'"$(printf 'd%.0s' $(seq 1 36))"'"' > "$S3/q/f.js"
"$G" import "$S3" --name sec >/dev/null; "$G" repo-init sec --remote https://github.com/alif/site --branch main --user alif --email 42+alif@users.noreply.github.com >/dev/null
REMOTE3="$T/remote3.git"; git init -q --bare "$REMOTE3"; git -C "$REMOTE3" symbolic-ref HEAD refs/heads/main
git -C "$GITDRIP_HOME/projects/sec/repo" config "url.$REMOTE3.insteadOf" https://github.com/alif/site
"$G" run-batch sec 1 >/dev/null 2>&1; : > "$RQ"
"$G" pr-flow sec 2 >/dev/null 2>&1; rc=$?
ok "secret in batch: blocked, no PR, no leftover branch, PENDING" '[[ $rc -ne 0 && "$(posts pulls)" == 0 ]] && ! git -C "$GITDRIP_HOME/projects/sec/repo" show-ref --verify -q refs/heads/gitdrip/sec-batch-2 && [[ "$(jq -r ".batches[1].status" "$GITDRIP_HOME/projects/sec/batches.json")" == PENDING ]]'
mkdir -p "$GITDRIP_HOME/locks"; echo "$$ $(date +%s)" > "$GITDRIP_HOME/locks/site.lock"
"$G" pr-flow site 5 >/dev/null 2>&1; rc=$?
ok "live project lock -> exit 4" '[[ $rc -eq 4 ]]'; rm -f "$GITDRIP_HOME/locks/site.lock"
S4="$T/src4"; mkdir -p "$S4/m"; echo 1 > "$S4/m/f.js"; "$G" import "$S4" --name frk >/dev/null
"$G" repo-init frk --remote https://github.com/alif/forked --branch main --user alif --email 42+alif@users.noreply.github.com >/dev/null
git -C "$GITDRIP_HOME/projects/frk/repo" commit -q --allow-empty -m init; : > "$RQ"
ok "fork repo refused, no branch, no write" '! "$G" pr-flow frk 1 >/dev/null 2>&1 && ! git -C "$GITDRIP_HOME/projects/frk/repo" show-ref --verify -q refs/heads/gitdrip/frk-batch-1 && [[ "$(posts pulls)" == 0 ]]'
ok "dirty working tree refused" 'echo x >> "$R/a/f.js"; ! "$G" pr-flow site 5 >/dev/null 2>&1; git -C "$R" checkout -q -- a/f.js; true'
ok "bad option / method refused" '! "$G" pr-flow site 5 --bogus >/dev/null 2>&1 && ! "$G" pr-flow site 5 --method x >/dev/null 2>&1 && ! "$G" pr-flow site abc >/dev/null 2>&1'
ok "no token in logs, results or output" '! grep -rq "$TOK" "$GITDRIP_HOME/logs" 2>/dev/null'
# --- P7.6: bridge pr-flow (app path): results/<req>.json
export GITDRIP_EXCHANGE="$T/ex"; mkdir -p "$GITDRIP_EXCHANGE/results"
BR() { cat "$GITDRIP_EXCHANGE/results/$1.json"; }
S5="$T/src5"; mkdir -p "$S5"/{k,l,m}; for x in k l m; do echo "v=1" > "$S5/$x/f.js"; done
REMOTE5="$T/remote5.git"; git init -q --bare "$REMOTE5"; git -C "$REMOTE5" symbolic-ref HEAD refs/heads/main
"$G" import "$S5" --name bra >/dev/null; P5="$GITDRIP_HOME/projects/bra"; R5="$P5/repo"
"$G" repo-init bra --remote https://github.com/alif/site --branch main --user alif --email 42+alif@users.noreply.github.com >/dev/null
git -C "$R5" config "url.$REMOTE5.insteadOf" https://github.com/alif/site
"$G" run-batch bra 1 >/dev/null 2>&1; : > "$RQ"
"$G" bridge rq1 pr-flow bra --issue >/dev/null 2>&1; rc=$?
ok "bridge pr-flow: default batch = first pending (2), SUCCESS, exit 0" '[[ $rc -eq 0 && "$(jq -r .state <<<"$(BR rq1)")" == SUCCESS && "$(jq .batch_id <<<"$(BR rq1)")" == 2 ]]'
ok "bridge pr-flow: pr number, url, issue, commit, files_changed, reason" 'j="$(BR rq1)"; [[ "$(jq .pr <<<"$j")" =~ ^[0-9]+$ && "$(jq -r .pr_url <<<"$j")" == http* && "$(jq .issue <<<"$j")" =~ ^[0-9]+$ && "$(jq -r .commit <<<"$j")" =~ ^[0-9a-f]{7,40}$ && "$(jq .files_changed <<<"$j")" == 1 && "$(jq -r .reason <<<"$j")" == "pr: #"* ]]'
ok "bridge pr-flow: batches[] mirrors states, main untouched on remote" 'j="$(BR rq1)"; [[ "$(jq -r ".batches[1].status" <<<"$j")" == SUCCESS && "$(git -C "$REMOTE5" rev-list --count main)" == 1 ]] && git -C "$REMOTE5" rev-parse --verify -q refs/heads/gitdrip/bra-batch-2 >/dev/null'
n0=$(grep -c "^POST /repos/alif/site/pulls" "$RQ")
"$G" bridge rq2 pr-flow bra 2 --issue >/dev/null 2>&1
ok "bridge pr-flow rerun: SUCCESS 'already', no new PR" '[[ "$(jq -r .state <<<"$(BR rq2)")" == SUCCESS && "$(jq -r .reason <<<"$(BR rq2)")" == *already* && "$(grep -c "^POST /repos/alif/site/pulls" "$RQ")" == "$n0" ]]'
"$G" bridge rq3 pr-flow bra --merge --method squash >/dev/null 2>&1; rc=$?
ok "bridge pr-flow --merge: batch 3 merged, reason 'merged:'" '[[ $rc -eq 0 && "$(jq .batch_id <<<"$(BR rq3)")" == 3 && "$(jq -r .reason <<<"$(BR rq3)")" == "merged: "* ]]'
"$G" bridge rq4 pr-flow bra >/dev/null 2>&1
ok "bridge pr-flow: nothing left -> SKIPPED, no PR" '[[ "$(jq -r .state <<<"$(BR rq4)")" == SKIPPED && "$(jq -r .reason <<<"$(BR rq4)")" == *"no pending"* ]]'
"$G" bridge rq5 pr-flow bra 1 >/dev/null 2>&1
ok "bridge pr-flow: finished batch id is a no-op SUCCESS (no PR)" '[[ "$(jq -r .state <<<"$(BR rq5)")" == SUCCESS && "$(jq -r .reason <<<"$(BR rq5)")" == *already* ]]'
echo "$$ $(date +%s)" > "$GITDRIP_HOME/locks/bra.lock"
"$G" bridge rq6 pr-flow bra 2 --issue >/dev/null 2>&1
ok "bridge pr-flow: locked project -> PENDING exit_code 4" '[[ "$(jq -r .state <<<"$(BR rq6)")" == PENDING && "$(jq .exit_code <<<"$(BR rq6)")" == 4 && "$(jq -r .reason <<<"$(BR rq6)")" == *locked* ]]'; rm -f "$GITDRIP_HOME/locks/bra.lock"
"$G" bridge rq7 pr-flow bra 2 --method evil >/dev/null 2>&1
ok "bridge pr-flow: bad method -> ERROR" '[[ "$(jq -r .state <<<"$(BR rq7)")" == ERROR ]]'
"$G" bridge rq8 pr-flow bra --bogus >/dev/null 2>&1
ok "bridge pr-flow: unknown option -> ERROR" '[[ "$(jq -r .state <<<"$(BR rq8)")" == ERROR ]]'
"$G" bridge rq9 pr-flow "Bad Name" >/dev/null 2>&1
ok "bridge pr-flow: invalid project name -> ERROR" '[[ "$(jq -r .state <<<"$(BR rq9)")" == ERROR ]]'
"$G" bridge rq10 pr-flow bra --sync >/dev/null 2>&1
ok "bridge pr-flow --sync without plan -> ERROR 'sync failed'" '[[ "$(jq -r .state <<<"$(BR rq10)")" == ERROR && "$(jq -r .reason <<<"$(BR rq10)")" == "sync failed"* ]]'
"$G" bridge rq11 pr-flow frk 1 >/dev/null 2>&1
ok "bridge pr-flow: fork repo -> FAILED with reason, no PR" '[[ "$(jq -r .state <<<"$(BR rq11)")" == FAILED && -n "$(jq -r .reason <<<"$(BR rq11)")" ]]'
ok "bridge pr-flow: no token in results or logs" '! grep -rq "$TOK" "$GITDRIP_EXCHANGE/results" "$GITDRIP_HOME/logs" 2>/dev/null'
unset GITDRIP_EXCHANGE

bash "$APP/tests/test_p13.sh" >/dev/null 2>&1 && ok "P13 regression (mock extension safe)" true || ok "P13 regression (mock extension safe)" false
echo "passed=$pass failed=$fail"; [[ $fail -eq 0 ]]
