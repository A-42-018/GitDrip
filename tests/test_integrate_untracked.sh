#!/usr/bin/env bash
# Regression: remote has .gitignore, local repo has it untracked -> integrate must merge, keep our copy.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export GITDRIP_HOME="$(mktemp -d)"; T="$(mktemp -d)"; export TMPDIR=/nonexistent   # prove the engine has no /tmp dependency
source "$ROOT/lib/common.sh" 2>/dev/null || true; for f in "$ROOT"/lib/*.sh; do source "$f" 2>/dev/null || true; done
cd "$T"
git init -q --bare -b main r.git
git clone -q r.git a 2>/dev/null; ( cd a; git config user.email a@b.c; git config user.name a; echo remote>.gitignore; git add .; git commit -qm r; git branch -M main; git push -q origin main )
git init -q -b main loc; cd loc; git config user.email a@b.c; git config user.name a
echo f>f; echo ours>.gitignore; git add f; git commit -qm b1; git remote add origin ../r.git
REPO="$PWD"; _git() { git -C "$REPO" "$@"; }; auth_git_args() { AUTH_ARGS=(); }; config_get() { echo "${2:-}"; }
_integrate_remote
[[ "$(cat .gitignore)" == ours ]] && git status --short | grep -q '^ M .gitignore' && echo "PASS integrate with untracked .gitignore"
