#!/usr/bin/env bash
# P17: remote already has commits (GitHub README) -> "fetch first" rejection is integrated by merge, never force-push. Needs git + jq.
set -euo pipefail
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export GITDRIP_HOME="$(mktemp -d)"; export GITDRIP_BIN_DIR="$GITDRIP_HOME/bin"; unset PREFIX
command -v jq >/dev/null && command -v git >/dev/null || { echo "SKIP: jq/git missing"; exit 0; }
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
pass=0; fail=0
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }
bash "$APP/install.sh" >/dev/null 2>&1
G="$GITDRIP_BIN_DIR/gitdrip"
T="$(mktemp -d)"; REMOTE="$T/remote.git"; W="$T/w"
git init -q --bare "$REMOTE"; git -C "$REMOTE" symbolic-ref HEAD refs/heads/main
git clone -q "$REMOTE" "$W" 2>/dev/null; git -C "$W" checkout -q -b main
git -C "$W" -c user.name=x -c user.email=x@x commit -q --allow-empty -m init; echo "# hi" > "$W/README.md"
git -C "$W" add README.md; git -C "$W" -c user.name=x -c user.email=x@x commit -q -m "Initial commit"; git -C "$W" push -q origin main
mk() { local d="$T/$1"; mkdir -p "$d"; echo "export const a=1" > "$d/a.js"; echo "export const b=2" > "$d/b.js"; [[ -z "${2:-}" ]] || echo "mine" > "$d/README.md"; printf '%s' "$d"; }
S1="$(mk s1)"; "$G" import "$S1" --name p1 --max 2 >/dev/null
"$G" repo-init p1 --remote "$REMOTE" --user Alif --email "1+alif@users.noreply.github.com" >/dev/null
tid="$("$G" task-create p1 1)"; rc=0; "$G" task-run "$tid" >/dev/null 2>&1 || rc=$?
R="$GITDRIP_HOME/projects/p1/repo"
ok "unrelated remote history: task SUCCESS" '[[ $rc -eq 0 && "$(jq -r .state "$GITDRIP_HOME/tasks/$tid.json")" == SUCCESS ]]'
ok "remote has README + our files (no force)" '[[ "$(git -C "$REMOTE" ls-tree -r --name-only main | sort | tr "\n" " ")" == *README.md* && "$(git -C "$REMOTE" ls-tree -r --name-only main)" == *a.js* ]]'
ok "remote still has its original commit" '[[ "$(git -C "$REMOTE" log --format=%s main)" == *"Initial commit"* ]]'
ok "local batch commit is on remote (hash kept)" 'git -C "$REMOTE" merge-base --is-ancestor "$(jq -r .commit "$GITDRIP_HOME/tasks/$tid.json")" main'
ok "batch status SUCCESS" '[[ "$(jq -r ".batches[0].status" "$GITDRIP_HOME/projects/p1/batches.json")" == SUCCESS ]]'

# real clash: same path README.md with different content -> stays conflict, remote untouched, no stuck merge
S2="$(mk s2 clash)"; "$G" import "$S2" --name p2 --max 3 >/dev/null
"$G" repo-init p2 --remote "$REMOTE" --user Alif --email "1+alif@users.noreply.github.com" >/dev/null
before="$(git -C "$REMOTE" rev-parse main)"
bid="$(jq -r '.batches[]|select(.files[]=="README.md")|.id' "$GITDRIP_HOME/projects/p2/batches.json" | head -1)"
tid2="$("$G" task-create p2 "$bid")"; rc=0; "$G" task-run "$tid2" >/dev/null 2>&1 || rc=$?
ok "real file clash: FAILED conflict, remote unchanged" '[[ $rc -ne 0 && "$(jq -r .error_class "$GITDRIP_HOME/tasks/$tid2.json")" == conflict && "$(git -C "$REMOTE" rev-parse main)" == "$before" ]]'
ok "no half-finished merge left behind" '[[ ! -e "$GITDRIP_HOME/projects/p2/repo/.git/MERGE_HEAD" ]]'
echo "P17: $pass passed, $fail failed"; [[ $fail -eq 0 ]]
