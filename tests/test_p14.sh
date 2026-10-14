#!/usr/bin/env bash
# P14 acceptance test: encrypted one-time token handoff (app -> Termux), redaction, manifest/permission audit. No jq needed.
# Needs openssl (+ java for the optional Kotlin-algorithm interop check).
set -uo pipefail
APP="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export GITDRIP_HOME="$(mktemp -d)"; export GITDRIP_BIN_DIR="$GITDRIP_HOME/bin"; unset PREFIX
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 GITDRIP_NET=online
T="$(mktemp -d)"; export GITDRIP_EXCHANGE="$T/ex"; mkdir -p "$GITDRIP_EXCHANGE/handoff"
trap 'rm -rf "$GITDRIP_HOME" "$T"' EXIT
pass=0; fail=0
ok() { if eval "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi; }
bash "$APP/install.sh" >/dev/null 2>&1
G="$GITDRIP_BIN_DIR/gitdrip"; EX="$GITDRIP_EXCHANGE"; R() { echo "$EX/results/$1.json"; }
TOK="ghp_$(printf 'a%.0s' $(seq 1 36))"
KEY="$(printf '0123456789abcdef%.0s' 1 2 3 4)"; BADKEY="$(printf 'f%.0s' $(seq 1 64))"
enc() { printf %s "$1" | openssl enc -aes-256-cbc -pbkdf2 -md sha256 -iter 10000 -pass "pass:$2" -out "$3"; }   # same format the app writes
LOGF="$GITDRIP_HOME/logs/execution.log"

# --- happy path
enc "$TOK" "$KEY" "$EX/handoff/h1.enc"
"$G" bridge h1 auth-handoff octocat "$KEY" >/dev/null 2>&1
ok "handoff stores token for the user" 'grep -qx "user=octocat" "$GITDRIP_HOME/credentials" && grep -qx "token=$TOK" "$GITDRIP_HOME/credentials"'
ok "credentials file is chmod 600" '[[ "$(stat -c %a "$GITDRIP_HOME/credentials")" == 600 ]]'
ok "encrypted file deleted after success" '[[ ! -e "$EX/handoff/h1.enc" ]]'
ok "result is OK / auth-handoff" 'grep -q "\"state\":\"OK\"" "$(R h1)" && grep -q "\"action\":\"auth-handoff\"" "$(R h1)"'
ok "result has no token or key" '! grep -q "$TOK\|$KEY" "$(R h1)"'
ok "log has no token or key" '! grep -rq "$TOK\|$KEY" "$GITDRIP_HOME/logs"'
ok "doctor reports openssl + auth true" '"$G" bridge d1 doctor >/dev/null 2>&1; grep -q "\"openssl\":true" "$(R d1)" && grep -q "\"auth\":true" "$(R d1)"'

# --- failure paths: file always removed, credentials untouched
cp "$GITDRIP_HOME/credentials" "$T/cred.before"
enc "$TOK" "$KEY" "$EX/handoff/h2.enc"
"$G" bridge h2 auth-handoff octocat "$BADKEY" >/dev/null 2>&1; rc=$?
ok "wrong key fails (exit != 0)" '[[ $rc -ne 0 ]]'
ok "wrong key: file deleted + ERROR result" '[[ ! -e "$EX/handoff/h2.enc" ]] && grep -q "\"state\":\"ERROR\"" "$(R h2)"'
enc "not-a-token-at-all" "$KEY" "$EX/handoff/h3.enc"
"$G" bridge h3 auth-handoff octocat "$KEY" >/dev/null 2>&1
ok "non-token plaintext rejected, file deleted" '[[ ! -e "$EX/handoff/h3.enc" ]] && grep -q "\"state\":\"ERROR\"" "$(R h3)"'
enc "$TOK" "$KEY" "$EX/handoff/h4.enc"
"$G" bridge h4 auth-handoff "bad user" "$KEY" >/dev/null 2>&1
ok "bad user name rejected, file deleted" '[[ ! -e "$EX/handoff/h4.enc" ]] && grep -q "\"state\":\"ERROR\"" "$(R h4)"'
enc "$TOK" "$KEY" "$EX/handoff/h5.enc"
"$G" bridge h5 auth-handoff octocat "SHORT" >/dev/null 2>&1
ok "malformed key rejected, file deleted" '[[ ! -e "$EX/handoff/h5.enc" ]] && grep -q "invalid user or key" "$(R h5)"'
"$G" bridge h6 auth-handoff octocat "$KEY" >/dev/null 2>&1
ok "missing file -> ERROR" 'grep -q "\"state\":\"ERROR\"" "$(R h6)"'
head -c 5000 /dev/urandom > "$EX/handoff/h7.enc"
"$G" bridge h7 auth-handoff octocat "$KEY" >/dev/null 2>&1
ok "oversized file rejected + deleted" '[[ ! -e "$EX/handoff/h7.enc" ]] && grep -q "\"state\":\"ERROR\"" "$(R h7)"'
enc "$TOK" "$KEY" "$T/real.enc"; ln -s "$T/real.enc" "$EX/handoff/h8.enc"
"$G" bridge h8 auth-handoff octocat "$KEY" >/dev/null 2>&1
ok "symlinked handoff file refused (target untouched)" 'grep -q "\"state\":\"ERROR\"" "$(R h8)" && [[ -f "$T/real.enc" ]]'
ok "credentials unchanged by all failures" 'cmp -s "$GITDRIP_HOME/credentials" "$T/cred.before"'
ok "bad request id cannot escape handoff dir" '! "$G" bridge "../x" auth-handoff octocat "$KEY" >/dev/null 2>&1'

# --- stale file cleanup via doctor
echo junk > "$EX/handoff/old.enc"; touch -d '30 minutes ago' "$EX/handoff/old.enc"; echo junk > "$EX/handoff/new.enc"
"$G" bridge d2 doctor >/dev/null 2>&1
ok "doctor removes stale .enc (>10 min), keeps fresh" '[[ ! -e "$EX/handoff/old.enc" && -e "$EX/handoff/new.enc" ]]'

# --- redaction of the key on a command line
ok "log redaction hides handoff key in a command line" '[[ "$( { source "$APP/lib/common.sh"; source "$APP/lib/logger.sh"; echo "run gitdrip bridge r1 auth-handoff octocat $KEY"; } | { source "$APP/lib/common.sh"; source "$APP/lib/logger.sh"; _log_redact; } )" == *"[REDACTED]"* ]]'

# --- Known-answer vector shared with HandoffTest.kt (fixed salt => deterministic openssl output)
KAT="AG8rVV9dwDr0o/l4KO5LErVqcEBualVY6xnbeKQ8066N1cgPpq5P7cCoT7ufMpql"
{ printf 'Salted__\001\002\003\004\005\006\007\010'; printf '%s' "$KAT" | base64 -d; } > "$T/kat.enc"
ok "KAT blob decrypts with openssl CLI to the token" '[[ "$(printf "%s\n" "$KEY" | openssl enc -d -aes-256-cbc -pbkdf2 -md sha256 -iter 10000 -pass stdin -in "$T/kat.enc")" == "$TOK" ]]'
if command -v java >/dev/null 2>&1; then
  cat > "$T/Interop.java" <<'EOF'
import javax.crypto.*; import javax.crypto.spec.*; import java.util.*;
public class Interop { public static void main(String[] a) throws Exception {   // same JCE calls as Handoff.encrypt in Secure.kt
  byte[] salt = {1,2,3,4,5,6,7,8};
  byte[] dk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(a[0].toCharArray(), salt, 10000, 48*8)).getEncoded();
  Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
  c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dk, 0, 32, "AES"), new IvParameterSpec(dk, 32, 16));
  System.out.print(Base64.getEncoder().encodeToString(c.doFinal(a[1].getBytes("UTF-8")))); } }
EOF
  ok "JCE (Kotlin algorithm) output == openssl CLI KAT" '[[ "$(java "$T/Interop.java" "$KEY" "$TOK" 2>/dev/null)" == "$KAT" ]]'
fi

# --- manifest / permission audit
M="$APP/android/app/src/main/AndroidManifest.xml"
ok "manifest: no INTERNET permission" '! grep -q "android.permission.INTERNET" "$M"'
ok "manifest: permissions are exactly the audited set" '[[ "$(grep -o "uses-permission android:name=\"[^\"]*\"" "$M" | sed "s/.*name=\"//;s/\"//" | sort | tr "\n" " ")" == "android.permission.MANAGE_EXTERNAL_STORAGE android.permission.POST_NOTIFICATIONS android.permission.READ_EXTERNAL_STORAGE android.permission.RECEIVE_BOOT_COMPLETED android.permission.SCHEDULE_EXACT_ALARM android.permission.USE_EXACT_ALARM android.permission.WRITE_EXTERNAL_STORAGE com.termux.permission.RUN_COMMAND " ]]'
ok "manifest: allowBackup=false, cleartext off" 'grep -q "allowBackup=\"false\"" "$M" && grep -q "usesCleartextTraffic=\"false\"" "$M"'
ok "manifest: only MainActivity is exported" '[[ "$(grep -c "exported=\"true\"" "$M")" == 1 ]] && grep -q "MainActivity\" android:exported=\"true\"" "$M"'

echo "---- $pass passed, $fail failed"; [[ $fail -eq 0 ]]
