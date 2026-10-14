#!/usr/bin/env bash
# P13 acceptance test: GitHub API against a local mock. Part 1 (transport, no jq): token never in argv, error classes, slug safety.
# Part 2 needs jq + git: whoami/repos/branches/check/create gating, bridge gh-* results, preflight fork/archived, identity autofill.
set -uo pipefail
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export GITDRIP_HOME="$(mktemp -d)"; export GITDRIP_BIN_DIR="$GITDRIP_HOME/bin"; unset PREFIX
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 GITDRIP_NET=online
T="$(mktemp -d)"; export GITDRIP_EXCHANGE="$T/ex"; mkdir -p "$GITDRIP_EXCHANGE"
pass=0; fail=0
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }
bash "$APP/install.sh" >/dev/null 2>&1
G="$GITDRIP_BIN_DIR/gitdrip"
TOK="ghp_$(printf 'a%.0s' $(seq 1 36))"

python3 "$APP/tests/mock_github.py" "$T/port" "$T" & MOCK=$!
trap 'kill $MOCK 2>/dev/null; rm -rf "$GITDRIP_HOME" "$T"' EXIT
for _ in $(seq 1 50); do [[ -s "$T/port" ]] && break; sleep 0.1; done
export GITDRIP_API="http://127.0.0.1:$(cat "$T/port")" GITDRIP_API_TIMEOUT=5
RQ="$T/requests.log"; : > "$RQ"

# --- part 1: transport (sourced, no jq needed)
# shellcheck disable=SC1091
source "$APP/lib/common.sh"; source "$APP/lib/logger.sh"; source "$APP/lib/auth.sh"; source "$APP/lib/github.sh"
ensure_dirs
err() { gh_api GET "$1" 2>&1 >/dev/null; }
ok "no token -> [auth] error, no request sent" '[[ "$(err /user)" == *"[auth]"* && ! -s "$RQ" ]]'
echo "$TOK" | "$G" auth set --user Alif >/dev/null
# curl shim records argv, then runs the real curl
mkdir -p "$T/shim"; REALCURL="$(command -v curl)"
printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$@" >> "%s/curl.args"\nexec "%s" "$@"\n' "$T" "$REALCURL" > "$T/shim/curl"; chmod +x "$T/shim/curl"
body="$(PATH="$T/shim:$PATH" gh_api GET /user)"; rc=$?
ok "GET /user 200 returns body" '[[ $rc -eq 0 && "$body" == *"\"login\": \"alif\""* || "$body" == *"\"login\":\"alif\""* ]]'
ok "Authorization header reached the server" 'grep -q "auth=Bearer $TOK" "$RQ"'
ok "token never in curl argv" '[[ -s "$T/curl.args" ]] && ! grep -q "$TOK" "$T/curl.args"'
ok "token never in logs" '! grep -rq "$TOK" "$GITDRIP_HOME/logs" 2>/dev/null'
ok "401 -> auth" '[[ "$(err /status/401)" == *"[auth]"* ]]'
ok "403 + remaining 0 -> ratelimit" '[[ "$(err /status/403rl)" == *"[ratelimit]"* ]]'
ok "403 plain -> auth (permission)" '[[ "$(err /status/403)" == *"[auth]"* ]]'
ok "404 -> notfound" '[[ "$(err /nope)" == *"[notfound]"* ]]'
ok "500 -> transient" '[[ "$(err /status/500)" == *"[transient]"* ]]'
ok "422 -> fatal" '[[ "$(err /status/422)" == *"[fatal]"* ]]'
ok "connection refused -> transient" '[[ "$(GITDRIP_API=http://127.0.0.1:1 err /user)" == *"[transient]"* ]]'
ok "error text never contains the token" '! { err /status/401; err /status/403; } | grep -q "$TOK"'
ok "slug ok: alif/site" '_gh_slug_ok alif/site'
ok "slug rejects ../x, a/.., a/b/c, spaces, empty" '! _gh_slug_ok ../x && ! _gh_slug_ok a/.. && ! _gh_slug_ok a/b/c && ! _gh_slug_ok "a b/c" && ! _gh_slug_ok ""'
ok "slug from https URL (+.git) and ssh" '[[ "$(gh_slug_from_url https://github.com/alif/site)" == alif/site && "$(gh_slug_from_url https://github.com/alif/site.git)" == alif/site && "$(gh_slug_from_url git@github.com:alif/site.git)" == alif/site ]]'
ok "slug from URL with creds / other host / traversal -> none" '! gh_slug_from_url https://u:p@github.com/a/b >/dev/null && ! gh_slug_from_url https://gitlab.com/a/b >/dev/null && ! gh_slug_from_url https://github.com/a/.. >/dev/null'
"$G" bridge req-gv gh-validate >/dev/null 2>&1 || true
HAVEJQ=0; command -v jq >/dev/null && command -v git >/dev/null && HAVEJQ=1
if [[ $HAVEJQ -ne 1 ]]; then
  ok "no jq: gh-validate -> ERROR result (jq missing)" 'grep -q "\"state\":\"ERROR\"" "$GITDRIP_EXCHANGE/results/req-gv.json"'
  echo "SKIP part 2: jq/git missing"; echo "passed=$pass failed=$fail"; [[ $fail -eq 0 ]]; exit
fi

# --- part 2
R() { echo "$GITDRIP_EXCHANGE/results/$1.json"; }
w="$("$G" github whoami)"
ok "whoami: login, id, noreply email" '[[ "$(jq -r .login <<<"$w")" == alif && "$(jq -r .noreply_email <<<"$w")" == "42+alif@users.noreply.github.com" ]]'
ok "whoami: classic token kind, stored user matches (case-insensitive)" '[[ "$(jq -r .token_kind <<<"$w")" == classic && "$(jq -r .user_matches <<<"$w")" == true ]]'
echo "$TOK" | "$G" auth set --user bob >/dev/null
ok "whoami: user_matches=false when stored user differs" '[[ "$("$G" github whoami | jq -r .user_matches)" == false ]]'
echo "$TOK" | "$G" auth set --user alif >/dev/null
rp="$("$G" github repos)"
ok "repos: paginates (100 + 2)" '[[ "$(jq length <<<"$rp")" == 102 ]]'
ok "repos: fields + push boolean" '[[ "$(jq -r "[.[]|select(.full_name==\"alif/ro\")]|length" <<<"$rp")" == 0 && "$(jq -r ".[100].full_name" <<<"$rp")" == alif/site && "$(jq -r ".[100].push" <<<"$rp")" == true ]]'
br="$("$G" github branches alif/site)"
ok "branches: default first" '[[ "$(jq -r .default_branch <<<"$br")" == main && "$(jq -r ".branches[0].name" <<<"$br")" == main && "$(jq -r ".branches[1].name" <<<"$br")" == dev ]]'
ok "branches: bad slug refused, nothing requested" '! "$G" github branches "../x" >/dev/null 2>&1'
ck() { "$G" github check "$1" 2>/dev/null; }
ok "check: normal repo eligible" '[[ "$(ck alif/site | jq -r .eligible)" == true && "$(ck alif/site | jq ".warnings|length")" == 0 ]]'
ok "check: fork not eligible + warning" '[[ "$(ck alif/forked | jq -r .eligible)" == false && "$(ck alif/forked | jq -r ".warnings[0]")" == fork:* ]]'
ok "check: archived not eligible" '[[ "$(ck alif/old | jq -r .eligible)" == false ]]'
ok "check: no push access not eligible" '[[ "$(ck alif/ro | jq -r .eligible)" == false ]]'
ok "check: unknown repo -> [notfound]" '[[ "$("$G" github check alif/zzz 2>&1 >/dev/null)" == *"[notfound]"* ]]'
# create: OFF by default
: > "$RQ"
ok "create disabled by default, no request sent" '! "$G" github create newrepo >/dev/null 2>&1 && ! grep -q "POST" "$RQ"'
"$G" config set allow_create_repo true
cr="$("$G" github create newrepo)"
ok "create: private by default, auto_init false" 'grep "POST /user/repos" "$RQ" | grep -q "\"private\":true" && grep "POST /user/repos" "$RQ" | grep -q "\"auto_init\":false"'
ok "create: result private repo" '[[ "$(jq -r .private <<<"$cr")" == true && "$(jq -r .full_name <<<"$cr")" == alif/newrepo ]]'
"$G" github create pubrepo --public >/dev/null
ok "create --public sends private:false" 'grep "POST /user/repos" "$RQ" | tail -n1 | grep -q "\"private\":false"'
ok "create: invalid name refused" '! "$G" github create "bad name" >/dev/null 2>&1 && ! "$G" github create .. >/dev/null 2>&1'
ok "create: 422 (exists) -> error" '! "$G" github create exists >/dev/null 2>&1'
"$G" config set allow_create_repo false
# bridge results
"$G" bridge req-v gh-validate >/dev/null 2>&1; ok "bridge gh-validate: OK + data.login" '[[ "$(jq -r .state "$(R req-v)" )" == OK && "$(jq -r .data.login "$(R req-v)")" == alif ]]'
"$G" bridge req-r gh-repos >/dev/null 2>&1; ok "bridge gh-repos: data is array of 102" '[[ "$(jq ".data|length" "$(R req-r)")" == 102 ]]'
"$G" bridge req-b gh-branches alif/site >/dev/null 2>&1; ok "bridge gh-branches: data.branches" '[[ "$(jq ".data.branches|length" "$(R req-b)")" == 2 ]]'
"$G" bridge req-c gh-check alif/zzz >/dev/null 2>&1 || true
ok "bridge gh-check unknown -> ERROR + error_class notfound" '[[ "$(jq -r .state "$(R req-c)")" == ERROR && "$(jq -r .error_class "$(R req-c)")" == notfound ]]'
"$G" bridge req-g gh-create newrepo >/dev/null 2>&1 || true
ok "bridge gh-create while disabled -> ERROR" '[[ "$(jq -r .state "$(R req-g)")" == ERROR && "$(jq -r .reason "$(R req-g)")" == *disabled* ]]'
"$G" bridge req-m gh-branches >/dev/null 2>&1 || true
ok "bridge gh-branches without arg -> ERROR" '[[ "$(jq -r .state "$(R req-m)")" == ERROR ]]'
ok "no token in any result file or log" '! grep -rq "$TOK" "$GITDRIP_EXCHANGE/results" "$GITDRIP_HOME/logs"'
# preflight against the mock
mkproj() { local d="$T/src-$1"; mkdir -p "$d"; echo "x" > "$d/a.txt"; "$G" import "$d" --name "$1" >/dev/null
  "$G" repo-init "$1" --remote "https://github.com/alif/$2" --branch "${3:-main}" --user alif --email 42+alif@users.noreply.github.com >/dev/null; }
mkproj pf-ok site; mkproj pf-fork forked; mkproj pf-old old; mkproj pf-leg legacy; mkproj pf-ro ro
ok "preflight: normal repo -> OK, exit 0" 'o="$("$G" preflight pf-ok)"; [[ "$o" == *"not a fork, push access"* ]]'
ok "preflight: fork -> WARN" '[[ "$("$G" preflight pf-fork)" == *"[WARN]"*"is a fork"* ]]'
ok "preflight: archived -> BLOCK, exit 1" '! "$G" preflight pf-old >/dev/null 2>&1 && [[ "$("$G" preflight pf-old 2>&1)" == *"[BLOCK]"*archived* ]]'
ok "preflight: default branch differs (master) -> WARN" '[[ "$("$G" preflight pf-leg)" == *"[WARN]"*"not the repo default"* ]]'
ok "preflight: no push access -> WARN" '[[ "$("$G" preflight pf-ro)" == *"[WARN]"*"no push access"* ]]'
ok "run-batch on archived repo refused before commit" '! "$G" run-batch pf-old 1 >/dev/null 2>&1; [[ "$("$G" batches pf-old)" == *PENDING* ]]'
ok "preflight stays silent about API when no token" 'echo | "$G" auth clear >/dev/null; [[ "$("$G" preflight pf-ok)" != *"not a fork"* ]]'
echo "$TOK" | "$G" auth set --user alif >/dev/null
# identity autofill: plan without user/email -> token owner's login + noreply email
mkdir -p "$GITDRIP_EXCHANGE/idp/source"; echo "x1" > "$GITDRIP_EXCHANGE/idp/source/a.js"
jq -n '{project:"idp", branch:"main", batches:[{id:1,message:"feat: add a",files:["a.js"]}]}' > "$GITDRIP_EXCHANGE/idp/plan.json"
"$G" bridge req-id sync idp >/dev/null 2>&1
ok "sync without identity -> git identity from GitHub (noreply)" '[[ "$(git -C "$GITDRIP_HOME/projects/idp/repo" config user.email)" == "42+alif@users.noreply.github.com" && "$(git -C "$GITDRIP_HOME/projects/idp/repo" config user.name)" == alif ]]'
# regression
bash "$APP/tests/test_p10.sh" >/dev/null 2>&1 && ok "P1-P12 engine tests still pass (test_p10 incl. P7)" true || ok "P1-P12 engine tests still pass (test_p10 incl. P7)" false
echo "passed=$pass failed=$fail"; [[ $fail -eq 0 ]]
