#!/usr/bin/env bash
# GitDrip watch (P7.7): commit real work in small steps while it is being written.
# Requires common, logger, manifest, batch, scan, git, runner, schedule sourced. Needs jq.
#
# A project is imported once (`gitdrip import <dir>`); `watch` then compares the LIVE work dir with the project's
# source/ copy. Files that changed and have been quiet for `watch_settle_seconds` (mtime based) are:
#   - secret-scanned (a hit is skipped, never copied, reported as blocked),
#   - copied into source/ (never deleted; removed files are ignored),
#   - put into NEW batches appended to batches.json (origin "watch", <= batch_max_files, one dir per batch),
#     unless the file already sits in a PENDING batch (then only its source copy is refreshed).
# With --commit the new batches are committed + pushed through the normal task runner (secret scan, preflight,
# lock, retry all apply), never above `max_commits_per_day`. No empty commits, no backdating, no force.
# State: projects/<p>/watch.json {dir, created_at}; watcher pid: locks/<p>.watch.
# P7.8: `watch <p> --tick off|scan|commit` lets cron `schedule tick` run one pass per project (scan, or scan + 1 commit under the cap).
# Exit codes: 0 ok, 4 project locked.

_watch_file() { printf '%s/watch.json' "$(project_dir "$1")"; }
_watch_pidfile() { printf '%s/locks/%s.watch' "$GITDRIP_HOME" "$1"; }
_watch_mtime() { stat -c %Y "$1" 2>/dev/null || stat -f %m "$1" 2>/dev/null || echo 0; }
_watch_alive() { local pid=""; { read -r pid < "$(_watch_pidfile "$1")"; } 2>/dev/null || true; [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; }

# watch_set_dir <p> <dir>
watch_set_dir() {
  local p="$1" d="$2" pd; pd="$(project_dir "$p")"
  [[ -f "$pd/batches.json" ]] || die "unknown project '$p' (run: gitdrip import <dir> --name $p first)"
  [[ -d "$d" ]] || die "watch dir not found: $d"
  d="$(cd -P "$d" && pwd)"
  case "$d/" in "$GITDRIP_HOME"/*|"$pd"/*) die "watch dir must be your working copy, not GitDrip's own data" ;; esac
  jq -n --arg d "$d" --arg t "$(_now)" --arg k "$(jq -r '.tick // "off"' "$(_watch_file "$p")" 2>/dev/null || echo off)" '{dir:$d, created_at:$t, tick:$k}' > "$(_watch_file "$p").tmp" && mv "$(_watch_file "$p").tmp" "$(_watch_file "$p")"
  log_info "watch $p dir set"; echo "watching $d for project $p"
}

# watch_scan <p> [settle_seconds]  -> one pass; prints a summary line
watch_scan() {
  have jq || die "jq required"
  local p="$1" settle="${2:-$(config_get watch_settle_seconds 60)}" pd rc=0
  pd="$(project_dir "$p")"; [[ -f "$(_watch_file "$p")" ]] || die "no watch dir for '$p' (run: gitdrip watch $p --dir <dir>)"
  [[ "$settle" =~ ^[0-9]+$ ]] || die "settle must be a number of seconds"
  if ! lock_acquire "$p"; then echo "project '$p' is locked; try again later"; return 4; fi
  _watch_scan_locked "$p" "$settle" "$pd" || rc=$?
  lock_release "$p"; return "$rc"
}

_watch_scan_locked() {
  local p="$1" settle="$2" pd="$3" dir now max maxb rel f sz mt tsv pend nch=0 nblk=0 nuns=0 nold=0 before after first
  dir="$(jq -r .dir "$(_watch_file "$p")")"; [[ -d "$dir" ]] || die "watch dir missing: $dir"
  now="$(date +%s)"; max="$(config_get batch_max_files 5)"; maxb="$(config_get watch_max_file_bytes 5242880)"
  tsv="$(mktemp)"; pend="$(mktemp)";
  [[ -f "$pd/excluded.txt" ]] || : > "$pd/excluded.txt"
  jq -r '.batches[]|select(.status=="PENDING")|.files[]' "$pd/batches.json" > "$pend"
  while IFS= read -r -d '' f; do
    rel="${f#"$dir"/}"
    if [[ "$rel" == *$'\t'* || "$rel" == *$'\n'* ]] || manifest_excluded "$rel" || [[ -L "$f" ]]; then continue; fi
    sz="$(wc -c < "$f" | tr -d ' ')"; (( sz <= maxb )) || { log_warn "watch $p: skipped large file $rel"; continue; }
    if [[ -f "$pd/source/$rel" ]] && cmp -s "$f" "$pd/source/$rel"; then continue; fi
    mt="$(_watch_mtime "$f")"
    if (( now - mt < settle )); then nuns=$((nuns + 1)); continue; fi
    if ! scan_file "$f" "$rel" >&2; then nblk=$((nblk + 1)); log_warn "watch $p: secret scan blocked $rel"; continue; fi
    first=new; [[ -f "$pd/source/$rel" ]] && first=mod
    mkdir -p "$pd/source/$(dirname "$rel")"; cp -p "$f" "$pd/source/$rel"; nch=$((nch + 1))
    if grep -qxF -- "$rel" "$pend"; then nold=$((nold + 1)); else printf '%s\t%s\n' "$rel" "$first" >> "$tsv"; fi
  done < <(find "$dir" \( -name .git -o -name node_modules \) -prune -o -type f -print0 | sort -z)
  before="$(jq '.batches|length' "$pd/batches.json")"
  if [[ -s "$tsv" ]]; then
    jq --argjson max "$max" --arg t "$(_now)" --rawfile fr "$tsv" '
      def grp: if (.path|contains("/")) then (.path|split("/")[:-1]|join("/")) else "(root)" end;
      def ext: (.path|split("/")|last|split(".")|if length>1 then last|ascii_downcase else "" end);
      def isdoc: (ext|IN("md","txt","rst"));
      def istest: (.path|test("(^|/)(tests?|__tests__|spec)(/|$)|\\.(test|spec)\\.|(^|/)test_"));
      def kind: if all(.[]; isdoc) then "docs" elif all(.[]; istest) then "test" else "feat" end;
      def verb: if all(.[]; .isnew) then "add" else "update" end;
      ($fr|split("\n")|map(select(length>0)|split("\t")|{path:.[0], isnew:(.[1]=="new")})) as $f
      | ((.batches|map(.id)|max) // 0) as $base
      | [ ($f|group_by(grp)|map({g:(.[0]|grp), f:.})|sort_by(if .g=="(root)" then "" else .g end))[]
          | .g as $g | .f as $fl
          | [range(0; ($fl|length); $max) as $i | $fl[$i:$i+$max]] as $chunks
          | $chunks | to_entries[] | .key as $k | .value as $c
          | ( if $g=="(root)" then "project root files" else "\($g) module" end ) as $what
          | { name: (if ($chunks|length)>1 then "\($g) (part \($k+1)/\($chunks|length))" else $g end),
              message: ("\($c|kind): \($c|verb) \($what)" + (if ($chunks|length)>1 then " (part \($k+1)/\($chunks|length))" else "" end)),
              files: ($c|map(.path)), status: "PENDING", origin: "watch", created_at: $t } ] as $new
      | .batches += ($new | to_entries | map(.value + {id: ($base + .key + 1)}))' \
      "$pd/batches.json" > "$pd/batches.json.tmp" && mv "$pd/batches.json.tmp" "$pd/batches.json"
  fi
  (( nch == 0 )) || manifest_generate "$p"
  after="$(jq '.batches|length' "$pd/batches.json")"
  rm -f "$tsv" "$pend"
  log_info "watch $p: changed=$nch new_batches=$((after - before)) blocked=$nblk unsettled=$nuns"
  echo "watch $p: $nch changed, $((after - before)) new batch(es), $nblk blocked, $nuns still settling, $nold already queued"
}

# _watch_commit <p> <max>  -> run up to <max> next batches under the daily cap
_watch_commit() {
  local p="$1" lim="$2" ran=0 used cap tid rc=0
  cap="$(config_get max_commits_per_day 10)"
  while (( ran < lim )); do
    used="$(sched_used_today)"
    if (( used >= cap )); then echo "daily cap reached ($used/$cap); remaining batches stay queued"; break; fi
    tid="$(task_create "$p")" || { echo "no pending batches for $p"; break; }
    rc=0; (task_run "$tid") || rc=$?; ran=$((ran + 1))
    (( rc == 0 )) || { echo "commit stopped (exit $rc); batches stay queued"; break; }
  done
  return 0
}

# watch_set_tick <p> <off|scan|commit>
watch_set_tick() {
  local p="$1" m="$2" f; f="$(_watch_file "$p")"
  [[ "$m" =~ ^(off|scan|commit)$ ]] || die "--tick must be off, scan or commit"
  [[ -f "$f" ]] || die "no watch dir for '$p' (run: gitdrip watch $p --dir <dir>)"
  jq --arg m "$m" '.tick=$m' "$f" > "$f.tmp" && mv "$f.tmp" "$f"; log_info "watch $p tick=$m"; echo "watch $p: cron tick = $m"
}

# watch_tick_all -- called by `schedule tick` (cron, every 5 min): one pass per project whose tick mode is scan|commit.
# Never blocks on a locked project (skipped until next tick); a running loop watcher for the project is left alone.
watch_tick_all() {
  have jq || return 0
  local f p m last now iv; now="$(date +%s)"; iv="$(config_get watch_interval_seconds 60)"
  for f in "$GITDRIP_HOME"/projects/*/watch.json; do
    [[ -f "$f" ]] || continue
    p="$(basename "$(dirname "$f")")"; m="$(jq -r '.tick // "off"' "$f" 2>/dev/null)"
    [[ "$m" == scan || "$m" == commit ]] || continue
    _watch_alive "$p" && continue
    watch_scan "$p" >/dev/null 2>&1 || continue
    [[ "$m" != commit ]] || _watch_commit "$p" 1 >/dev/null 2>&1 || true
  done
  return 0
}

# watch_main <p> [--dir D]  (--dir alone = configure only) [--once] [--settle S] [--interval S] [--commit] [--max-commits N] [--tick off|scan|commit] [--stop] [--status]
watch_main() {
  have jq || die "jq required"
  local p="${1:-}"; shift || true
  [[ "$p" =~ ^[a-z0-9][a-z0-9._-]{0,39}$ ]] || die "usage: gitdrip watch <project> [--dir D] [--once] [--settle S] [--interval S] [--commit] [--max-commits N] [--stop|--status]"
  local tick="" dir="" once=0 settle="" interval="" commit=0 mc=1 stop=0 status=0 pid="" rc
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --dir) dir="${2:-}"; shift 2 ;; --once) once=1; shift ;; --settle) settle="${2:-}"; shift 2 ;;
      --interval) interval="${2:-}"; shift 2 ;; --commit) commit=1; shift ;;
      --max-commits) mc="${2:-}"; shift 2 ;; --tick) tick="${2:-}"; shift 2 ;; --stop) stop=1; shift ;; --status) status=1; shift ;;
      *) die "unknown option: $1" ;;
    esac
  done
  interval="${interval:-$(config_get watch_interval_seconds 60)}"
  [[ "$interval" =~ ^[1-9][0-9]*$ && "$mc" =~ ^[1-9][0-9]*$ ]] || die "interval and max-commits must be positive integers"
  if [[ $stop -eq 1 ]]; then
    _watch_alive "$p" || { rm -f "$(_watch_pidfile "$p")"; echo "no watcher running for $p"; return 0; }
    read -r pid < "$(_watch_pidfile "$p")"; kill "$pid" 2>/dev/null || true; echo "stopped watcher $pid"; return 0
  fi
  if [[ -n "$dir" ]]; then   # --dir alone only configures; add --once to scan right away, or run `watch <p>` to start the loop
    watch_set_dir "$p" "$dir"; [[ $once -eq 1 || $status -eq 1 ]] || return 0
  fi
  if [[ -n "$tick" ]]; then watch_set_tick "$p" "$tick"; [[ $once -eq 1 || $status -eq 1 ]] || return 0; fi
  if [[ $status -eq 1 ]]; then
    [[ -f "$(_watch_file "$p")" ]] || { echo "not configured"; return 0; }
    echo "dir: $(jq -r .dir "$(_watch_file "$p")")"; _watch_alive "$p" && echo "watcher: running" || echo "watcher: stopped"
    echo "cron tick: $(jq -r '.tick // "off"' "$(_watch_file "$p")")"
    jq -r '"queued batches from watch: \([.batches[]|select(.origin=="watch" and .status=="PENDING")]|length)"' "$(project_dir "$p")/batches.json"
    return 0
  fi
  if [[ $once -eq 1 ]]; then
    rc=0; watch_scan "$p" "$settle" || rc=$?
    [[ $rc -ne 0 || $commit -eq 0 ]] || _watch_commit "$p" "$mc"
    return "$rc"
  fi
  _watch_alive "$p" && die "a watcher is already running for $p (gitdrip watch $p --stop)"
  mkdir -p "$GITDRIP_HOME/locks"; echo "$$" > "$(_watch_pidfile "$p")"
  trap 'rm -f "$(_watch_pidfile "$p")"; exit 0' INT TERM EXIT
  echo "watching project $p every ${interval}s (Ctrl-C or: gitdrip watch $p --stop)"; log_info "watch $p started"
  while :; do
    rc=0; watch_scan "$p" "$settle" || rc=$?
    [[ $rc -ne 0 || $commit -eq 0 ]] || _watch_commit "$p" "$mc"
    sleep "$interval"
  done
}
