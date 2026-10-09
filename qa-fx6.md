# FX6 — Device QA (run on an Android 14 phone, ideally Xiaomi/HyperOS, and an Android 10/11 device or emulator)

Before you start: `./gradlew assembleDebug` must compile, then `bash tests/kt_run.sh` must show 0 failed.
Deploy the engine: `rm -rf ~/gitdrip && cp -r ~/storage/shared/Download/gitdrip ~/gitdrip && bash ~/gitdrip/install.sh`

| # | Step | Expected | A14 | A10/11 |
|---|------|----------|-----|--------|
| 1 | Install the APK over 0.32.0 | Room migrates v6→v7; projects, batches, history still there | | |
| 2 | Settings → Daily limit (touch it once), then `gitdrip config get max_commits_per_day` in Termux | Prints the stepper value (default 10) | | |
| 3 | Fixed mode: 3 slots 10 min apart, swipe the app away, screen off | 3 SUCCESS rows in History; none says "previous run still open" | | |
| 4 | Auto mode: 10/day, window = next 3 h | 10 planned times in "Today's plan"; all run → SUCCESS (enough batches); an 11th never happens | | |
| 5 | Set the limit to 2 after 1 manual push today | Only 1 automatic run; rest SKIPPED "daily cap (2/2) reached" | | |
| 6 | Reboot mid-day | Remaining auto slots still planned; a missed fixed slot with "Run when back" fires once | | |
| 7 | Force-stop Termux, wait for a slot | History: "…will retry at HH:MM", then it runs when Termux is back (within 60 min) | | |
| 8 | Android 10/11: add a schedule, import files | No crash; alarm fires; files appear in /storage/emulated/0/GitDrip (after "Grant storage" in Setup) | | |
| 9 | GitHub contribution graph | Commits show on the day they were pushed (verified or noreply author email) | | |

Quick checks that cost nothing: Setup shows "Exact alarms" and "Battery" rows; Schedule tab shows the Mode chip; with a fixed time enabled the Auto Drip card says it is paused; Dashboard hero reads "N/cap commits today"; manual Run past the cap warns first and goes through on the second tap within 30 s.

Visual checks (UI polish): cards and raised buttons show soft shadows with no banding or white halo; the Run button and RUNNING badges show the cyan comet ring while a run is in flight (steady ring with "Remove animations" on); Auto Drip → Today's plan shows the timeline with the next slot glowing; scroll the Schedule tab on a mid-range phone and watch for dropped frames.

Record failures below as `FX6-n: what happened / History row text`, then fix them in FX7.
