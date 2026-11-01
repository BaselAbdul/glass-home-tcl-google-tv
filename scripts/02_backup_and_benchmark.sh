#!/bin/bash
# Glass Home for TCL Google TV - by Basel Majid
# Phase 2+4: backup of package/settings state + repeatable baseline benchmarks.
# Changes nothing permanent. Only force-stops apps between timing runs (normal app stop).
# Usage: bash 02_backup_and_benchmark.sh [label]   (label: baseline | after)
set -u
# --- portability: macOS, Linux/WSL and Windows (Git Bash) ---
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"   # stop Git Bash rewriting /sdcard/... paths
hostpath(){ if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
DIR="$(cd "$(dirname "$0")/.." && pwd)"; LABEL="${1:-baseline}"
export ANDROID_SERIAL="$(cat "$DIR/scripts/.tv_target")"
adb connect "$ANDROID_SERIAL" >/dev/null 2>&1
[ "$(adb get-state 2>&1 | tr -d '\r')" = "device" ] || { echo "TV not connected ($ANDROID_SERIAL). If the port changed, run 01 script again with the new IP:PORT."; exit 1; }
S(){ adb shell "$@" 2>&1 | tr -d '\r'; }
B="$DIR/backup"; M="$DIR/logs/bench_$LABEL"; mkdir -p "$B" "$M"
if [ "$LABEL" = "baseline" ] && [ ! -f "$B/pkgs_enabled.txt" ]; then
  echo "== Backup"
  S pm list packages -e   > "$B/pkgs_enabled.txt"
  S pm list packages -d   > "$B/pkgs_disabled.txt"
  S pm list packages -u   > "$B/pkgs_all.txt"
  S pm list packages -3 -f > "$B/pkgs_3rdparty.txt"
  for ns in global secure system; do S settings list $ns > "$B/settings_$ns.txt"; done
  S cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$B/home_activity.txt"
  S dumpsys package preferred-activities > "$B/preferred_activities.txt"
  date > "$B/backup_time.txt"
fi
echo "== Launch timing (3 cold runs each)"
APPS="com.netflix.ninja com.google.android.youtube.tv com.apple.atve.androidtv.appletv com.android.vending com.android.tv.settings"
: > "$M/launch_times.csv"; echo "package,run,TotalTime_ms,WaitTime_ms" >> "$M/launch_times.csv"
for p in $APPS; do
  act=$(S cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER $p | tail -1)
  case "$act" in */*) ;; *) act=$(S cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $p | tail -1);; esac
  for r in 1 2 3; do
    S am force-stop $p >/dev/null; S input keyevent KEYCODE_HOME >/dev/null; sleep 4
    out=$(S am start -W -n "$act")
    tt=$(echo "$out" | awk '/TotalTime/{print $2}'); wt=$(echo "$out" | awk '/WaitTime/{print $2}')
    echo "$p,$r,$tt,$wt" | tee -a "$M/launch_times.csv"; sleep 6
  done
  S am force-stop $p >/dev/null
done
echo "== Home return timing"
: > "$M/home_times.csv"
for r in 1 2 3 4 5; do
  S am start -W -n com.android.tv.settings/.MainSettings >/dev/null; sleep 3
  out=$(S am start -W -a android.intent.action.MAIN -c android.intent.category.HOME)
  echo "home,$r,$(echo "$out" | awk '/TotalTime/{print $2}'),$(echo "$out" | awk '/WaitTime/{print $2}')" | tee -a "$M/home_times.csv"; sleep 3
done
echo "== Launcher scroll jank (automated D-pad navigation)"
S input keyevent KEYCODE_HOME; sleep 5
HOMEPKG=$(S cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | cut -d/ -f1)
S dumpsys gfxinfo $HOMEPKG reset >/dev/null
for k in DOWN DOWN DOWN RIGHT RIGHT RIGHT RIGHT DOWN DOWN LEFT LEFT UP UP UP UP UP RIGHT RIGHT DOWN DOWN DOWN UP UP UP; do S input keyevent KEYCODE_DPAD_$k; sleep 0.5; done
S dumpsys gfxinfo $HOMEPKG > "$M/launcher_gfx.txt"
S input keyevent KEYCODE_HOME
echo "== Settle 60s, then memory snapshot"
sleep 60
S cat /proc/meminfo > "$M/proc_meminfo.txt"
S dumpsys meminfo > "$M/dumpsys_meminfo.txt"
S ps -A -o PID,USER,RSS,NAME > "$M/ps.txt"
S top -b -n 2 -d 5 -m 25 > "$M/top.txt"
S df -h /data > "$M/df.txt"
S uptime > "$M/uptime.txt"
date > "$M/done.txt"
echo "DONE ($LABEL). Saved in $M"
