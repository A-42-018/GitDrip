#!/usr/bin/env bash
# P7.7 acceptance test: `gitdrip watch` (live work dir -> new batches -> optional commit). Needs git + jq.
set -uo pipefail
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v jq >/dev/null && command -v git >/dev/null || { echo "SKIP: jq/git missing"; exit 0; }
export GITDRIP_HOME="$(mktemp -d)"; export GITDRIP_BIN_DIR="$GITDRIP_HOME/bin"; unset PREFIX
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 GITDRIP_NET=online
T="$(mktemp -d)"; pass=0; fail=0
trap 'kill ${WPID:-0} 2>/dev/null; rm -rf "$GITDRIP_HOME" "$T"' EXIT
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }
bash "$APP/install.sh" >/dev/null 2>&1
G="$GITDRIP_BIN_DIR/gitdrip"
W="$T/work"; mkdir -p "$W/api" "$W/ui"; echo "export const a=1" > "$W/api/a.js"; echo "export const u=1" > "$W/ui/u.js"
REMOTE="$T/remote.git"; git init -q --bare "$REMOTE"; git -C "$REMOTE" symbolic-ref HEAD refs/heads/main
"$G" import "$W" --name app --max 2 >/dev/null
P="$GITDRIP_HOME/projects/app"; B="$P/batches.json"; R="$P/repo"
"$G" repo-init app --remote "$REMOTE" --user Alif --email "1+alif@users.noreply.github.com" >/dev/null
nb() { jq '.batches|length' "$B"; }
old() { touch -d "-10 minutes" "$@"; }
out="$("$G" watch app --dir "$W" --once --settle 0 2>&1)"
ok "no changes -> 0 new batches" '[[ "$(nb)" == 2 && "$out" == *"0 changed, 0 new"* ]]'
"$G" watch nope --dir "$W" >/dev/null 2>&1; ok "unknown project refused" '[[ $? -ne 0 ]]'
"$G" watch app --dir "$GITDRIP_HOME/projects/app/source" >/dev/null 2>&1; ok "GitDrip data dir refused as watch dir" '[[ $? -ne 0 ]]'

# new + modified file; modified file lives in a batch that already ran
"$G" run-batch app 1 >/dev/null 2>&1; b1="$(jq -r '.batches[0].status' "$B")"
echo "export const a=2" > "$W/api/a.js"; echo "export const n=1" > "$W/api/new.js"; echo "# notes" > "$W/NOTES.md"; old "$W/api/a.js" "$W/api/new.js" "$W/NOTES.md"
out="$("$G" watch app --once --settle 60 2>&1)"
ok "batch 1 executed before watch" '[[ "$b1" == SUCCESS ]]'
ok "3 changes -> 2 new batches (api, root)" '[[ "$(nb)" == 4 && "$out" == *"3 changed, 2 new"* ]]'
ok "new batches are PENDING, origin watch, ids 3-4" '[[ "$(jq -r "[.batches[2:][]|select(.status==\"PENDING\" and .origin==\"watch\")]|length" "$B")" == 2 && "$(jq -r ".batches[3].id" "$B")" == 4 ]]'
ok "modified file gets an update message" 'jq -r ".batches[].message" "$B" | grep -q "^feat: update api module"'
ok "docs-only root batch is docs:" 'jq -r ".batches[].message" "$B" | grep -q "^docs: add project root files"'
ok "source copy refreshed" 'cmp -s "$W/api/a.js" "$P/source/api/a.js"'
ok "manifest regenerated (4 files in api/ui/root)" '[[ "$(jq .file_count "$P/manifest.json")" == 4 ]]'
out="$("$G" watch app --once --settle 60 2>&1)"; ok "rerun is idempotent" '[[ "$(nb)" == 4 && "$out" == *"0 changed, 0 new"* ]]'

# settle window: fresh file waits
echo "export const f=1" > "$W/ui/fresh.js"
out="$("$G" watch app --once --settle 300 2>&1)"; ok "fresh file still settling, no batch" '[[ "$(nb)" == 4 && "$out" == *"1 still settling"* && ! -e "$P/source/ui/fresh.js" ]]'
out="$("$G" watch app --once --settle 0 2>&1)"; ok "settled file becomes a batch" '[[ "$(nb)" == 5 && -f "$P/source/ui/fresh.js" ]]'

# file already in a PENDING batch: only source refreshed
echo "export const f=2" > "$W/ui/fresh.js"; old "$W/ui/fresh.js"
out="$("$G" watch app --once --settle 60 2>&1)"; ok "file in PENDING batch -> no new batch, source refreshed" '[[ "$(nb)" == 5 && "$out" == *"1 already queued"* ]] && cmp -s "$W/ui/fresh.js" "$P/source/ui/fresh.js"'

# secrets and excluded files
printf 'const k = "ghp_%s"\n' "$(printf 'z%.0s' $(seq 1 36))" > "$W/api/leak.js"; echo "SECRET=1" > "$W/.env"; old "$W/api/leak.js" "$W/.env"
out="$("$G" watch app --once --settle 0 2>&1)"
ok "secret file blocked, not copied, no batch" '[[ "$(nb)" == 5 && "$out" == *"1 blocked"* && ! -e "$P/source/api/leak.js" ]]'
ok "scan output never prints the token" '[[ "$out" != *zzzzzzzz* && "$(cat "$GITDRIP_HOME/logs/execution.log")" != *zzzzzzzz* ]]'
ok ".env never imported" '[[ ! -e "$P/source/.env" ]]'
rm -f "$W/api/leak.js"

# project lock
echo "$$ $(date +%s)" > "$GITDRIP_HOME/locks/app.lock"; echo "x=1" > "$W/ui/late.js"; old "$W/ui/late.js"
rc=0; "$G" watch app --once --settle 0 >/dev/null 2>&1 || rc=$?
"$G" watch app --dir "$W" >/dev/null; ok "--dir alone only configures (no loop)" '[[ ! -e "$GITDRIP_HOME/locks/app.watch" ]]'
ok "locked project -> exit 4, nothing changed" '[[ $rc -eq 4 && "$(nb)" == 5 ]]'; rm -f "$GITDRIP_HOME/locks/app.lock"

# --commit: real commits through the runner, cap enforced
"$G" config set max_commits_per_day 2 >/dev/null
out="$("$G" watch app --once --settle 0 --commit --max-commits 5 2>&1)"
ok "--commit pushes up to the daily cap (cap 2)" '[[ "$out" == *"daily cap reached"* ]]'
n="$(git -C "$REMOTE" rev-list --count main)"
ok "task commits stay within the cap (initial run-batch commit + 2)" '[[ "$n" -le 3 ]]'
"$G" config set max_commits_per_day 50 >/dev/null
"$G" watch app --once --settle 0 --commit --max-commits 10 >/dev/null 2>&1
ok "all queued batches end SUCCESS/NOCHANGE" '[[ "$(jq "[.batches[]|select(.status==\"PENDING\" or .status==\"COMMITTED\")]|length" "$B")" == 0 ]]'
ok "remote has the modified a.js content" '[[ "$(git -C "$REMOTE" show main:api/a.js)" == "export const a=2" ]]'
ok "no empty commit: each commit touches >=1 file" '! git -C "$REMOTE" log --format="C %H" --name-only main | awk "/^C /{if(c&&!f)bad=1;c=1;f=0;next} NF{f=1} END{if(c&&!f)bad=1;exit !bad}"'

# loop mode + stop
echo "export const l=1" > "$W/ui/loop.js"; old "$W/ui/loop.js"
"$G" watch app --settle 0 --interval 1 >"$T/watch.out" 2>&1 & WPID=$!
for _ in $(seq 1 40); do [[ -f "$P/source/ui/loop.js" ]] && break; sleep 0.25; done
ok "loop mode picks up a new file" '[[ -f "$P/source/ui/loop.js" ]] && [[ "$("$G" watch app --status)" == *"watcher: running"* ]]'
rc=0; "$G" watch app --settle 0 --interval 1 >/dev/null 2>&1 || rc=$?; ok "second watcher refused" '[[ $rc -ne 0 ]]'
[[ "$("$G" watch app --stop)" == *stopped* ]]; sleep 1.5
ok "stop ends the watcher and clears pidfile" '! kill -0 $WPID 2>/dev/null && [[ ! -e "$GITDRIP_HOME/locks/app.watch" ]]'
rc=0; "$G" watch "bad;name" --once >/dev/null 2>&1 || rc=$?; ok "bad project name rejected" '[[ $rc -ne 0 ]]'
echo "P77: $pass passed, $fail failed"; [[ $fail -eq 0 ]]
