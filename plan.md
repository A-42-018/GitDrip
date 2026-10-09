# GitDrip — Plan (next work only)
Old history (P1–P17, U1–U6, NT1–NT10) moved to `plan-archive.md`. Don't upload it; it's for reference only.

## Context (read first)
- **What it is:** turns finished project files into small, real GitHub commits on a schedule.
  - **Engine:** bash in Termux (`bin/gitdrip`, `lib/*.sh`, engine 0.23.0, needs bash 4+, git, jq, curl, openssl).
  - **Android app:** Kotlin/Compose, Room v7, app 0.33.0 / versionCode 26, minSdk 26, targetSdk 34.
- **Bridge:** the app writes `/storage/emulated/0/GitDrip/<p>/plan.json` and `source/`, then sends a Termux `RUN_COMMAND` with `gitdrip bridge <req> <action> …`. The engine writes `GitDrip/results/<req>.json`, and the app polls it and mirrors it into Room. Engine JSON (`tasks/*.json`, `batches.json`) is the source of truth.
- **Key app files:**
  - `data/Scheduler.kt` (AlarmManager), `ScheduleReceivers.kt`, `data/ScheduleMath.kt` (pure), `data/Db.kt` (Room)
  - `MainViewModel.kt` (runs, sync, reconcile), `data/TermuxBridge.kt`
  - UI: `ui/` Neo components; screens `ScheduleTab.kt`, `SettingsScreen.kt`, `SetupScreen.kt`, `ui/screens/DashboardScreen.kt`
- **Rules:**
  - 1 phase = 1 chat. At most 8 files and about 500 new lines per phase, with one "Done when".
  - Upload only this file plus the phase's **Upload** list.
  - End each chat with an updated plan.md and the full project zip.
  - Real commits only: never empty, dummy, backdated or force-pushed.
  - Secrets never go in args, logs or the repo.
  - When a build fails, paste only the `e:` lines.
- **Tests:**
  - `bash tests/kt_run.sh` (pure Kotlin, needs kotlinc 1.9.24)
  - `bash tests/test_nt4b.sh` and `bash tests/test_nt5.sh` (static UI checks)
  - Engine suites `tests/test_p*.sh` need bash 4+ and jq (run in Termux, or on a Mac with `brew install bash`)
  - `./gradlew assembleDebug` locally after every phase
- **Deploy engine:** `rm -rf ~/gitdrip && cp -r ~/storage/shared/Download/gitdrip ~/gitdrip && bash ~/gitdrip/install.sh`
- **Pending from NT (do inside FX6):** device look of the Neo-Tactile UI, nav-bar taps, Retry of the failed batch (commit 110e175).

## STATUS
- **FX1–FX5 (2026-10-07): CODE DONE.** Engine 0.23.0, app 0.33.0 (versionCode 26), Room v7. `./gradlew assembleDebug` was NOT run here (no Android SDK in the sandbox): run it first and paste only the `e:` lines if anything fails.
- **Checked here:** `bash tests/kt_run.sh` (131 pure-JVM tests), `test_fx1.sh`, `test_fx3.sh` (15), `test_fx4.sh`, `test_nt4b.sh`, `test_nt5.sh`, and the engine suites `test_p*.sh` (same results as before; the only failures are macOS-only `stat -c` / `date -d` ones that also fail on the untouched FX0 zip).
- **UI polish (2026-10-07):** `neoGlowRing` (cyan comet ring: Run button while a run is in flight, RUNNING badges), `SlotTimeline` (Auto Drip day track with a glowing next slot, in "Today's plan"), and a shadow rework (smoothstep feather via `NeoLogic.shadowStepAlphas`, extra contact shadow, fainter white top-left lift, feathered blue glow under primary buttons). 139 pure-JVM tests pass; look and frame rate still need a device check.
- **FX6 (device QA): NOT DONE, needs a phone.** Checklist and a results table are in `qa-fx6.md`. Record failures as `FX6-n` and fix them in FX7.
- **Deviations from the plan (all small):**
  - Auto alarms use one request code per project (`300000 + projectId`) instead of `100000 + slotId`, so cancelling is one call.
  - `Importer.sharedAccess(context)` now takes a Context (API < 30 needs it for the permission check).
  - `dispatch()` treats a project as having batches only if one is runnable (PENDING/COMMITTED/FAILED with files), so Auto Drip never sends a run for a finished project.
  - Skip reason is `daily cap (used/cap) reached` so History shows both numbers.
  - The Hero shows "4/10 commits today".
  - `tests/test_p14.sh` manifest audit and `NotifyBackupTest` (`createTempDir`) were updated for the new permissions / newer kotlinc.

---

## 1. Findings: why scheduled runs are skipped while manual pushes work

These come from reading `Scheduler.fire()`, `MainViewModel` and `Db.kt`.

| # | Cause | Where | Effect |
|---|---|---|---|
| **S1** | **A scheduled run is never closed in the background.** `fire()` inserts a `PENDING` execution, sends the intent to Termux and returns without polling for the result. The row is only resolved by `reconcile()`, and that runs only when you open the project screen (`ProjectScreens.kt:147`) or press Sync. | `Scheduler.fire` | The next slot finds that row still `PENDING`, so `ex.open(p.id)` is not empty and the run is **SKIPPED "previous run still open"**. Manual push looks fine because you open the project first: `reconcile()` clears the stale row, then `runInner` polls its own result. |
| **S2** | **The cap counts the wrong rows.** `countSince()` counts every execution that is not `FAILED` or `SKIPPED`. That includes `PENDING`, `RUNNING`, `PENDING_RETRY`, stuck rows from S1 and engine tasks imported by Sync. | `Db.kt:129` | After about 3 rows of any kind in a day (manual pushes included), every alarm is **SKIPPED "daily cap (3) reached"**. Manual runs never check the cap, so they keep working. |
| **S3** | **The cap is hard-coded to 3** in both places. The app has `const val DAILY_CAP = 3`. The engine has `config.json max_commits_per_day: 3`, and `install.sh` keeps an existing config, so a reinstall never changes it. | `ScheduleMath.kt:12`, `config/config.json` | The app limits you to 3 a day. The engine's watch/tick path also stops at 3. |
| **S4** | **Slot is consumed before the checks run.** `markFired(today)` happens before the paused, cap, open-run, Termux and permission checks. | `Scheduler.fire` | A temporary block (a run still open, Termux busy) loses the whole day's slot, and run-now catch-up won't fire it again. |
| **S5** | **Editing a schedule keeps `lastFireDay`.** `saveSchedule` uses `old.copy(...)`. | `MainViewModel.saveSchedule` | If you move a slot that already fired today to a later time (for example to test it), it's **SKIPPED "already ran today's slot"**. |
| **S6** | **Inexact fallback can't start Termux.** Without exact-alarm permission the app uses `setAndAllowWhileIdle`. On API 31+ that alarm gives no background-FGS exemption, so `startForegroundService` to Termux is likely to throw. Android 14 doesn't grant `SCHEDULE_EXACT_ALARM` by default. | `Scheduler.arm`, `TermuxBridge.send` | Run shows FAILED "Could not reach Termux", or the alarm is heavily delayed. |

**Check before FX2 (no code needed):** open the project's History tab and read the `HH:MM: …` reason on the SKIPPED rows.

| Reason shown | Cause |
|---|---|
| `previous run still open` | S1 |
| `daily cap (3) reached` | S2 and S3 |
| `already ran today's slot` | S5 |
| `Could not reach Termux` | S6 |
| No row at all | The alarm was never delivered (OEM battery killer or exact-alarm permission). Fixed by FX1. |

## 2. Other findings to fix (from the analysis)
| # | Problem | Fix phase |
|---|---|---|
| F1 | `canScheduleExactAlarms()` is called without an API check, so it **crashes with NoSuchMethodError on Android 8–11**. Scheduling never works there. | FX1 |
| F2 | Below API 30 the app assumes it has shared storage, but the manifest has no `WRITE_EXTERNAL_STORAGE` and no `requestLegacyExternalStorage`, so Android 8–10 can't write `/storage/emulated/0/GitDrip`. | FX1 |
| F3 | The app counts days in local time. The engine eligibility check uses `date -u` (UTC). In Bangladesh (UTC+6), commits between 00:00 and 06:00 local land on the wrong day. | FX3 |
| F4 | `MainViewModel.dayStart` and `failedToday` are computed once and go stale after midnight. | FX1 |
| F5 | Watcher-imported files show size 0 and an empty sha. | Known gap, not fixed here |
| F6 | Source files and `plan.json` sit in shared storage, readable by any app with all-files access. | Documented in Setup text (FX5); no code change |

## 3. Decisions
- **D1: daily cap = 10, user-configurable from 1 to 10** in Settings and stored in app preferences. The app sends it to the engine (`max_commits_per_day`) over a new bridge `config-set` action, so the app and engine always agree.
- **D2: the cap counts real commits only.** `used today` = executions in `SUCCESS` with a commit hash, finished today in local time, across all projects. Runs in flight (`PENDING`/`RUNNING` with a request id, under 15 min old) also count, so the cap can't be overshot. FAILED, SKIPPED, NOCHANGE and stale rows never count. Manual pushes count as commits but **are not blocked** by the cap. Past the cap they show a warning ("10/10 today, push anyway?").
- **D3: two modes per project, chosen automatically:**
  1. **Fixed times:** the project has at least one *enabled* schedule. Only those times are used, and Auto Drip is ignored. The UI says "Auto Drip off while fixed times exist".
  2. **Auto Drip:** the project has no enabled schedule and Auto Drip is switched on. The app picks *N* random times each day (default 10, 1–10) inside an active window (default 09:00–22:00).
  3. Neither: nothing runs automatically.
- **D4: how random times are picked.** Stratified random: split the window into N equal segments and pick one uniform random minute in each. Keep at least 20 min between slots (or `window/N` when that's smaller). Across all projects, keep at least 5 min between any two slots by shifting a clash forward. Use `SecureRandom`. Times are planned once per day and **stored**, so restarting the app doesn't reshuffle them. If planning happens mid-day, only the rest of the window (from now + 5 min) is used.
- **D5: number of slots = min(N, cap − used today, pending batches).** If there are no pending batches, no slots are planned. Auto Drip only pushes real, existing batches; when the backlog runs out it stops.
- **D6: no new dependency.** Background result tracking reuses AlarmManager with follow-up "check" alarms instead of adding WorkManager, which avoids compile risk in a chat sandbox that has no Gradle.
- **D7: exact alarms on sideloaded builds.** Add `USE_EXACT_ALARM` (API 33+, granted automatically). Play Store policy would restrict it, but GitDrip is sideloaded, so that doesn't apply. Keep `SCHEDULE_EXACT_ALARM` with `maxSdkVersion="32"`. This fixes S6 on Android 13 and 14 without a trip to Settings.

---

## FX1 — Platform crash + permission fixes  (~120 lines)
**Fixes:** F1, F2, F4, S6.

1. `Scheduler.canExact()`: return `Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()`.
2. Manifest:
   - Add `<uses-permission android:name="android.permission.USE_EXACT_ALARM" />`.
   - Set `SCHEDULE_EXACT_ALARM` to `android:maxSdkVersion="32"`.
   - Add `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="29"`) and `READ_EXTERNAL_STORAGE` (`maxSdkVersion="32"`).
   - Add `android:requestLegacyExternalStorage="true"` on `<application>`.
3. `Importer.sharedAccess()`: on API < 30, return true only if `WRITE_EXTERNAL_STORAGE` is granted. Setup gets a "Grant storage" button that requests it on API < 30.
   - **Alternative if you'd rather not support old phones:** raise `minSdk` to 30 and skip steps 2 (storage part) and 3. Pick one at the start of FX1. Recommended: keep 26 and fix it.
4. `MainViewModel.failedToday`: recompute `dayStart` on each emission with a `flatMapLatest` over a midnight-ticking flow. A simpler option: `TabLogic` gets `todayStart()` and the dashboard flow re-subscribes when the date changes.
5. `TermuxBridge.send()`: catch `ForegroundServiceStartNotAllowedException` (API 31+) separately and show "Android blocked starting Termux from background: allow exact alarms / disable battery optimization".
6. Bump the version to app 0.33.0 (versionCode 26).

**Upload:** `Scheduler.kt`, `AndroidManifest.xml`, `build.gradle.kts`, `Importer.kt`, `MainViewModel.kt`, `TermuxBridge.kt`, `SetupScreen.kt`.

**Tests:** new `ScheduleMathTest.canExactGuard` is not possible in pure JVM, so the API guard is grepped instead. New `tests/test_fx1.sh` (static) checks:
- `canScheduleExactAlarms` appears only behind `SDK_INT`
- the manifest has `USE_EXACT_ALARM` and `maxSdkVersion="32"`
- `requestLegacyExternalStorage` is present

**Done when:** `test_fx1.sh` passes, `./gradlew assembleDebug` compiles, and on an Android 10 or 11 device (or emulator) adding a schedule doesn't crash and a History row appears at the slot time.

---

## FX2 — Scheduler root-cause fix (always-skipped runs)  (~350 lines)
**Fixes:** S1, S2, S4, S5.

1. **New `data/RunRecorder.kt`.** Move `applyResult`, `reconcileNow`, `ranked`, `notifyChange` and `redact` out of `MainViewModel` into an `object RunRecorder` that takes `(Context, AppDb)`. The ViewModel and the receivers both call it, so there's one copy of the logic.
2. **Close scheduled runs in the background (S1).** After `fire()` sends the intent, arm **check alarms** (`ACTION_CHECK`, extra `req`) at +2, +5, +10 and +20 min:
   - Each check calls `RunRecorder.reconcileRequest(req)`: read `results/<req>.json`, and apply it if it's final.
   - If it's final, cancel the remaining checks.
   - On the last check with no result, mark the run FAILED "no answer from Termux (is Termux allowed to run in background?)".
   - Use request code = `req.hashCode()` and `setExactAndAllowWhileIdle`.
3. **Reconcile before deciding (S1, safety net).** At the start of `fire()`, call `RunRecorder.reconcileProject(p)`. It applies any finished result files and fails open rows older than 15 min that have no result file. Only after that, check "previous run still open".
4. **Correct cap counting (S2).** New DAO query `usedToday(since)`:
   ```sql
   SELECT
     (SELECT COUNT(*) FROM executions WHERE state='SUCCESS' AND commitHash IS NOT NULL AND commitHash!='' AND COALESCE(finishedAt,startedAt) >= :since)
   + (SELECT COUNT(*) FROM executions WHERE state IN ('PENDING','RUNNING') AND requestId!='' AND startedAt >= :fresh)
   ```
   `:fresh` = now − 15 min. Delete `countSince`. Pure helper `capReached(used, cap)` goes in `ScheduleMath.kt`.
5. **Don't burn the slot on temporary blocks (S4).** Reorder `fire()`:
   - **Permanent for today** (calls `markFired` + SKIPPED row): not an enabled day, project paused, cap reached, no batches.
   - **Temporary** (no `markFired`; SKIPPED row "will retry at HH:MM"; one-shot retry alarm in 10 min): previous run still open, Termux not reachable or refused.
   - Retry stops at slot + 60 min. After that, `markFired` and SKIPPED "gave up after 60 min".
   - Pure function `retryAt(slotMs, nowMs, step=10, limit=60): Long?` in `ScheduleMath.kt`.
   - `markFired` happens **right before** `TermuxBridge.send`, so the slot is consumed only when a run is really dispatched.
6. **Editing resets the day (S5).** In `saveSchedule`, if `time` or `days` changed, set `lastFireDay = ""`. "Disable then enable" also resets it.
7. **One dispatch function.** Add `Scheduler.dispatch(c, project, origin: "schedule"|"auto", label)`. It does the checks, plan.json, the intent and the check alarms, and returns `Dispatched | Skipped(reason, permanent)`. FX4 reuses it for Auto Drip.
8. **Logging.** Every SKIPPED or FAILED row keeps its `HH:MM: reason` format so History stays the diagnostic tool.

**Upload:** `Scheduler.kt`, `ScheduleReceivers.kt`, `ScheduleMath.kt`, `Db.kt`, `MainViewModel.kt`, `ProjectScreens.kt`, `test/ScheduleMathTest.kt`. New file: `data/RunRecorder.kt`.

**Tests (pure JVM, `tests/kt_run.sh`):**
- `ScheduleMathTest.retryAtWindow`: +10, +20 … stops at +60.
- `ScheduleMathTest.capReached`.
- A new `FireDecisionTest` on a pure `decide(state) -> Decision` function extracted from `fire()`, with a table covering:
  - paused → permanent skip
  - open run → temporary
  - cap 10/10 → permanent
  - 9 SUCCESS + 5 PENDING_RETRY → **not** capped
  - stale PENDING older than 15 min → not counted
  - slot fired today plus edited time → runs

**Done when:** all of the following pass.
- Unit tests pass.
- On device: two slots 5 min apart. Both produce runs, the first run becomes SUCCESS in History **without opening the app**, and the second isn't skipped as "previous run still open".
- 3 manual pushes followed by a scheduled slot → the slot runs.

---

## FX3 — Daily cap 10, configurable, app and engine in sync  (~250 lines)
**Fixes:** S3, F3.

**Engine (0.23.0):**
1. In `config/config.json`, set `max_commits_per_day` to 10.
2. In `install.sh`, when `config.json` exists, add any **missing** keys from the default (jq merge: existing values win). Print "config: max_commits_per_day is N (change in app Settings)".
3. New bridge action `bridge <req> config-set <key> <value>` in `lib/bridge.sh`:
   - Whitelist: `max_commits_per_day` (integer 1–10) only. Room for more keys later.
   - Validate, `config_set`, and return result OK/ERROR with `reason="max_commits_per_day=10"`.
4. In `lib/git.sh` `_elig_checks`, change `today="$(date -u +%Y-%m-%d)"` to local `date +%Y-%m-%d`. Termux uses the Android timezone. Also compare `updated_at` by its local date: convert with `jq fromdateiso8601` and compare against local midnight epoch, as `sched_used_today` already does.
5. In `lib/common.sh`, set `GITDRIP_VERSION="0.23.0"` and bump version asserts in `test_p10` and `test_p16`.

**App:**
6. Replace `const val DAILY_CAP = 3` with `Limits.dailyCap(context)` (SharedPreferences `gitdrip_prefs/daily_cap`, default 10, range 1–10). Pure `Limits.clampCap()`.
7. Settings → new **"Daily limit"** card:
   - A stepper from 1 to 10 (two `GlassIconButton`s with a value; no slider, since the app has none).
   - Text: "Max automatic commits per day, all projects. Manual pushes count but are not blocked."
   - On change: save, then send `config-set max_commits_per_day N` and show OK/error. Also re-send on "Test connection" in Setup so a reinstalled engine gets the value.
8. `TabLogic.scheduleHelp(cap)` shows the live cap. Dashboard hero shows "used/cap today".
9. Manual run past the cap (D2): `runInner` checks `usedToday >= cap` and returns "Daily limit reached (10/10). Tap Run again to push anyway". A second tap within 30 s goes through.

**Upload:** `config/config.json`, `install.sh`, `lib/bridge.sh`, `lib/git.sh`, `lib/common.sh`, `SettingsScreen.kt`, `ScheduleMath.kt` (→ `Limits`), `ui/TabLogic.kt`. `MainViewModel.kt` is the 9th file; split it into FX3b if over budget.

**Tests:**
- New `tests/test_fx3.sh` (needs bash 4+ and jq; run in Termux, or on Mac with `brew install bash`):
  - fresh install → 10
  - old config with 3 + install → 3 is kept, and missing keys are added
  - `config-set max_commits_per_day 10` → OK
  - `config-set` with 0, 11, `abc` or an unknown key → ERROR
  - `_elig_checks` counts by local day (`TZ=Asia/Dhaka` with a commit at 01:00 local)
- Kotlin: `LimitsTest.clamp`.

**Done when:** test_fx3 passes, the Settings stepper changes the engine value (check with `gitdrip config get max_commits_per_day` in Termux), and the 4th scheduled slot of the day runs.

---

## FX4 — Auto Drip engine: random times, storage, alarms  (~400 lines)
**Implements:** D3, D4, D5.

1. **Room v7 → v7 (`MIGRATION_6_7`):**
   - `projects`: add `autoDrip INTEGER NOT NULL DEFAULT 0`, `autoPerDay INTEGER NOT NULL DEFAULT 10`, `autoStart TEXT NOT NULL DEFAULT '09:00'`, `autoEnd TEXT NOT NULL DEFAULT '22:00'`.
   - New table `auto_slots(id PK, projectId FK CASCADE, day TEXT, atMs INTEGER, state TEXT, reason TEXT, requestId TEXT)`.
   - `state` values: `PLANNED|SENT|SKIPPED|DONE|FAILED`.
2. **Pure `data/AutoDrip.kt` (no Android):**
   - `plan(day, window, n, nowMs, zone, rnd, minGapMin=20, taken: List<Long>): List<Long>`. Uses stratified random (D4). Starts from max(windowStart, now+5 min). Keeps the 5-min global spacing against `taken` (other projects' slots). Returns sorted epoch ms. Returns empty if the window has already passed or n ≤ 0.
   - `slotsToPlan(perDay, cap, usedToday, pendingBatches) = minOf(perDay, cap − used, pending).coerceAtLeast(0)`.
   - `mode(enabledSchedules, autoDrip) = Fixed | Auto | Off` (D3).
3. **`Scheduler` additions:**
   - `planToday(c)`: for each project with `mode == Auto` and no slots for today, compute and insert slots, then arm the earliest PLANNED slot for that project (`ACTION_AUTO`, extra `slotId`, request code `100000 + slotId`). Safe to call repeatedly.
   - `armPlanner(c)`: exact alarm at 00:05 local tomorrow (`ACTION_PLAN`). It runs `planToday` and re-arms itself.
   - `fireAuto(c, slotId)`:
     - Check the mode is still Auto.
     - Call `dispatch(origin="auto", label="auto HH:MM")` from FX2.
     - Set the slot state.
     - If the dispatch was skipped temporarily, push the slot back by +10 min while it stays inside the window.
     - Arm the project's next PLANNED slot.
   - Called from: app open (`MainActivity`), `BootReceiver` (also on time or timezone change: delete today's PLANNED slots that are in the past and re-plan only the rest), turning Auto Drip on, saving or deleting fixed schedules (the mode may change: switching to Fixed cancels the auto alarms and marks PLANNED slots SKIPPED "fixed times set").
   - Runs that come from batches: if `pendingBatches` hits 0 mid-day, remaining slots become SKIPPED "no batches left". No empty commits, ever.
4. **Receivers:** `AlarmReceiver` handles `ACTION_FIRE`, `ACTION_CHECK` (FX2), `ACTION_AUTO` and `ACTION_PLAN`.

**Upload:** `Db.kt`, `Scheduler.kt`, `ScheduleReceivers.kt`, `MainActivity.kt`, `GitDripApp.kt` (DB version + migration), `MainViewModel.kt` (toggle hook). New files: `data/AutoDrip.kt`, `test/AutoDripTest.kt`.

**Tests (`AutoDripTest`, seeded `Random`):**
- 10 slots inside 09:00–22:00, sorted, at least 20 min apart, one per segment
- planning at 15:00 gives only slots after 15:05
- n larger than what the window fits falls back to even spacing with no overlap
- `taken` slots of another project are at least 5 min away
- `slotsToPlan(10, 10, 4, 3) = 3`
- `mode`: enabled schedule → Fixed; none + on → Auto; none + off → Off
- DST day (`Europe/Berlin` 2026-03-29): no slot inside the gap
- Same day, same seed → same plan (stable)

Also add the migration SQL to `tests/test_fx4.sh` (static grep).

**Done when:** unit tests pass, the migration compiles, and on device a project with no schedules and Auto Drip on (window = now+10 min … now+70 min, 3 per day) produces 3 History rows at the planned times, each with a commit.

---

## FX5 — Auto Drip UI  (~300 lines)
1. **Schedule tab, top section "Mode":**
   - Read-only chip showing *Fixed times* / *Auto Drip* / *Off* and why ("2 fixed times enabled").
   - Auto Drip card: `LabeledSwitch` "Auto Drip: random times every day"; stepper for commits/day (1–10, capped at the global limit with a note); window start and end as two `GlassTextField`s (HH:MM, validated with `validTime`, start < end, at least 30 min).
   - When fixed schedules exist: card disabled, `NoticeCard` "Auto Drip is paused while fixed times are enabled. Disable them to use random times."
2. **"Today's plan" list:** a row per auto slot with time and a `StateBadge` (Planned / Sent / Done / Skipped + reason). A **"Re-roll"** button replaces only future PLANNED slots.
3. **Dashboard:** `nextRun` also takes the earliest PLANNED auto slot ("Next: 14:37 · auto · myproject"). Hero shows "4/10 today".
4. **Setup:**
   - An exact-alarm row (Ready / Needs attention).
   - A battery row with an OEM hint: Xiaomi/HyperOS → Autostart ON, No restrictions, lock in Recents, for **GitDrip and Termux**.
   - An F6 privacy note: "Files in GitDrip/ are visible to apps with all-files access."
5. Strings go through `TabLogic` (pure) so they can be tested. Everything uses the Neo components (GlassCard / NeoToggle / StateBadge). No raw colors, so `test_nt5.sh` keeps passing.

**Upload:** `ScheduleTab.kt`, `ui/TabLogic.kt`, `ui/screens/DashboardScreen.kt`, `ui/DashboardLogic.kt`, `SetupScreen.kt`, `MainViewModel.kt`, `test/TabLogicTest.kt`.

**Done when:** `kt_run.sh` and `test_nt4b.sh`/`test_nt5.sh` pass, it compiles, and on device switching modes updates the chip, the plan list fills in, Re-roll changes only future times, and the Dashboard shows the next auto time.

---

## FX6 — Device QA pass (no new features)
Run on **one Android 14 phone (Xiaomi/HyperOS if available) and one Android 10 or 11 device or emulator**:
1. Install the APK over 0.32.0. Room migrates v6→v7 with no data loss (projects, batches and history are still there).
2. Termux: `rm -rf ~/gitdrip && cp -r ~/storage/shared/Download/gitdrip ~/gitdrip && bash ~/gitdrip/install.sh`. Then `gitdrip config get max_commits_per_day` shows 10 after you touch Settings → Daily limit.
3. Fixed mode: 3 slots 10 min apart, app swiped away and screen off → 3 SUCCESS rows; nothing is "previous run still open".
4. Auto mode: 10 per day, window = next 3 h → 10 planned times; all run and all are SUCCESS (enough batches); the 11th never happens.
5. Cap: lower to 2 with 1 manual push already today → only 1 automatic push runs, the rest SKIPPED "daily cap (2/2)".
6. Reboot mid-day → remaining auto slots are still planned; a missed fixed slot with run-now fires once.
7. Termux killed → slot shows "will retry at …", then runs when Termux is back (within 60 min).
8. Android 10/11: no crash, alarms fire, files are written to `/storage/emulated/0/GitDrip`.
9. Contribution graph: check the commits appear on the day they were pushed (author email is verified or noreply).

**Done when:** all 9 pass. Record any failures as `FX6-n` bugs here and fix them in FX7.

---

## Files touched (summary)
| Area | Files |
|---|---|
| Engine | `config/config.json`, `install.sh`, `lib/bridge.sh`, `lib/git.sh`, `lib/common.sh`, `tests/test_fx3.sh` |
| App data | `Scheduler.kt`, `ScheduleReceivers.kt`, `ScheduleMath.kt`, `Db.kt`, `TermuxBridge.kt`, `Importer.kt`, **new** `RunRecorder.kt`, **new** `AutoDrip.kt`, `GitDripApp.kt` |
| App UI | `MainActivity.kt`, `MainViewModel.kt`, `ProjectScreens.kt`, `ScheduleTab.kt`, `SettingsScreen.kt`, `SetupScreen.kt`, `DashboardScreen.kt`, `DashboardLogic.kt`, `TabLogic.kt` |
| Manifest/build | `AndroidManifest.xml`, `build.gradle.kts` |
| Tests | `ScheduleMathTest`, new `FireDecisionTest`, new `LimitsTest`, new `AutoDripTest`, `TabLogicTest`, `tests/test_fx1.sh`, `tests/test_fx3.sh`, `tests/test_fx4.sh` |

## Risks
- **OEM killers (Xiaomi, Oppo, Vivo)** can still drop alarms. FX5 Setup hints and re-arming on app open reduce this but can't remove it. If FX6 step 3 fails with *no History row at all*, the fallback is the engine's own cron: `gitdrip schedule add` plus `crond` in Termux with `termux-wake-lock`.
- **10 commits a day** only looks natural if the batches are real, small, meaningful units. Auto Drip never invents changes. When the backlog is empty, it stops.
- **Compile risk:** FX2 and FX4 touch `Scheduler`/`MainViewModel` heavily and none of it compiles in the chat sandbox. Run `./gradlew assembleDebug` after every phase, not only at the end.
- **Bash 4+ needed for engine tests.** macOS bash 3.2 fails on `${x^^}`/`${x,,}`. Run them in Termux or with Homebrew bash.
