#!/bin/bash
# Glass Home for TCL Google TV - by Basel Majid
# Step 12: safe performance boost, measured.
#   1. read-only audit (memory, background activity, how apps are compiled)
#   2. pre-compiles your main apps (ART "speed") and MEASURES cold-start before/after;
#      any app that got slower is reverted automatically
#   3. optional background tidy-ups (each asked), with an essentials health check after each
# Every change is appended to scripts/99_restore_all.sh.
set -u
# --- portability: macOS, Linux/WSL and Windows (Git Bash) ---
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
DIR="$(cd "$(dirname "$0")/.." && pwd)"; L="$DIR/logs/perf"; mkdir -p "$L"
export ANDROID_SERIAL="$(cat "$DIR/scripts/.tv_target" 2>/dev/null)"; adb connect "$ANDROID_SERIAL" >/dev/null 2>&1
[ "$(adb get-state 2>&1 | tr -d '\r')" = "device" ] || { echo "TV not connected - run 00_reconnect.sh <TV-IP:PORT> first"; exit 1; }
S(){ adb shell "$@" 2>&1 | tr -d '\r'; }
RESTORE="$DIR/scripts/99_restore_all.sh"; LOG="$DIR/logs/changes.csv"; mkdir -p "$DIR/logs"
[ -f "$RESTORE" ] || { printf '#!/bin/bash\n# Auto-generated: reverses every change made by the Glass Home scripts on this TV\nDIR="$(cd "$(dirname "$0")/.." && pwd)"; export ANDROID_SERIAL="$(cat "$DIR/scripts/.tv_target")"; adb connect "$ANDROID_SERIAL"\n' > "$RESTORE"; chmod +x "$RESTORE"; }
[ -f "$LOG" ] || echo "time,package,action,result,health" > "$LOG"
ask(){ local def=$2 a; read -r -p "  $1 [$( [ "$def" = Y ] && echo Y/n || echo y/N )]: " a; a=${a:-$def}; [ "$a" = y ] || [ "$a" = Y ]; }
has(){ S pm list packages -e "$1" | grep -qx "package:$1"; }
rec(){ grep -qxF "$1" "$RESTORE" || echo "$1" >> "$RESTORE"; }

# essentials health check (only what exists and works on this TV right now)
ESS=""; for q in com.google.android.gms com.android.vending com.google.android.apps.mediashell com.google.android.katniss \
  com.android.tv.settings com.tcl.tvinput com.tcl.tv com.android.bluetooth com.tcl.globalkeyoverlay com.basel.tvhome; do has $q && ESS="$ESS $q"; done
CV=$(S settings get secure voice_interaction_service | grep -q katniss && echo 1 || echo 0)
health(){ local bad="" q; for q in $ESS; do has $q || bad="$bad $q"; done
  [ $CV = 1 ] && { S settings get secure voice_interaction_service | grep -q katniss || bad="$bad voice"; }
  S ip route | grep -q "^default" || bad="$bad network"
  [ -z "$bad" ] && return 0; echo "  HEALTH FAIL:$bad"; return 1; }

echo "== 1/4 Audit (read-only)"
S dumpsys meminfo > "$L/meminfo_before.txt"
grep -E "Free RAM|Used RAM" "$L/meminfo_before.txt" | sed 's/^/  /'
echo "  Biggest memory users now:"
sed -n '/Total PSS by process/,/Total PSS by OOM/p' "$L/meminfo_before.txt" | grep -E "K: " | head -12 | sed 's/^ */    /'
S dumpsys procstats --hours 24 > "$L/procstats_24h.txt" 2>&1
KEY=""; for p in com.google.android.youtube.tv com.netflix.ninja com.apple.atve.androidtv.appletv com.wbd.stream \
  com.amazon.amazonvideo.livingroom com.disney.disneyplus com.spotify.tv.android com.android.vending com.android.tv.settings com.basel.tvhome; do
  has $p && KEY="$KEY $p"; done
S dumpsys package dexopt > "$L/dexopt_before.txt" 2>&1
echo "  How your main apps are compiled now:"
for p in $KEY; do f=$(grep -A6 "^  \[$p\]" "$L/dexopt_before.txt" | grep -o "status=[a-z-]*" | head -1 | cut -d= -f2); echo "    $p: ${f:-unknown}"; done

launch_ms(){  # median cold start of $1 over 3 runs
  local p=$1 act t arr=() r
  act=$(S cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER $p | tail -1)
  case "$act" in */*) ;; *) act=$(S cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $p | tail -1);; esac
  case "$act" in */*) ;; *) echo ""; return;; esac
  for r in 1 2 3; do
    S am force-stop $p >/dev/null; S input keyevent KEYCODE_HOME >/dev/null; sleep 3
    t=$(S am start -W -n "$act" | awk '/TotalTime/{print $2}'); arr+=("${t:-0}"); sleep 5
  done
  S am force-stop $p >/dev/null; S input keyevent KEYCODE_HOME >/dev/null
  printf '%s\n' "${arr[@]}" | sort -n | sed -n '2p'
}

APPS=""; for p in $KEY; do case $p in com.basel.tvhome|com.android.tv.settings) ;; *) APPS="$APPS $p";; esac; done
echo ""
echo "== 2/4 Pre-compiling your apps for speed (measured; keep the remote down, ~10 min)"
echo "app,before_ms,after_ms,result" > "$L/compile_ab.csv"
for p in $APPS; do echo -n "  measuring $p ... "; b=$(launch_ms $p); echo "${b:-n/a} ms"; echo "$p=$b" >> "$L/before.tmp"; done
for p in $KEY; do
  echo -n "  compiling $p (speed) ... "
  r=$(S cmd package compile -m speed -f $p | tail -1); echo "$r"
  rec "adb shell cmd package compile --reset $p"
done
for p in $APPS; do
  b=$(grep "^$p=" "$L/before.tmp" | tail -1 | cut -d= -f2); echo -n "  re-measuring $p ... "; a=$(launch_ms $p); echo "${a:-n/a} ms (before ${b:-n/a})"
  res=kept
  if [ -n "$a" ] && [ -n "$b" ] && [ "$b" -gt 0 ] && [ "$a" -gt $(( b * 110 / 100 )) ]; then
    S cmd package compile --reset $p >/dev/null; res="reverted (slower)"; echo "    -> slower, reverted to default"; fi
  echo "$p,$b,$a,$res" >> "$L/compile_ab.csv"
  echo "$(date +%H:%M:%S),$p,compile-speed,$res,$(health >/dev/null && echo OK || echo FAIL)" >> "$LOG"
done
rm -f "$L/before.tmp"

echo ""
echo "== 3/4 Background tidy-ups (each optional)"
if ask "Stop leftover TCL utilities running in the background (Gallery, Media Player, Safety Guard, T-Solo, log collector)? They still open normally" Y; then
  for p in com.tcl.gallery com.tcl.ui_mediaCenter com.tcl.guard com.tcl.t_solo com.tcl.logkit; do
    S pm list packages -u $p | grep -qx "package:$p" || continue
    S cmd appops set $p RUN_ANY_IN_BACKGROUND ignore && rec "adb shell cmd appops set $p RUN_ANY_IN_BACKGROUND allow"
    S am force-stop $p >/dev/null; echo "    $p: background restricted"
  done
  health || echo "  (check the message above)"
fi
if S pm list packages -e com.android.printspooler | grep -q . && ask "Disable Print Spooler (TVs can't print)?" Y; then
  S pm disable-user --user 0 com.android.printspooler >/dev/null && rec "adb shell pm enable com.android.printspooler"
  health && echo "    Print Spooler disabled" || { S pm enable com.android.printspooler >/dev/null; echo "    reverted"; }
fi
if [ "$(S settings get global ble_scan_always_enabled)" != "0" ] && ask "Turn off background Bluetooth scanning for location? (remote & headphones unaffected)" Y; then
  old=$(S settings get global ble_scan_always_enabled); S settings put global ble_scan_always_enabled 0
  rec "adb shell settings put global ble_scan_always_enabled ${old/null/1}"; echo "    BLE background scanning off"
fi
if [ "$(S settings get secure location_mode)" != "0" ] && ask "Turn off location services? (less Google background work; some apps may ask for your region)" N; then
  old=$(S settings get secure location_mode); S settings put secure location_mode 0
  rec "adb shell settings put secure location_mode ${old/null/3}"; echo "    location off"
fi
if has com.google.android.tts && ask "Disable Google Text-to-Speech (~100 MB)? Voice SEARCH keeps working, spoken voice ANSWERS go silent" N; then
  S pm disable-user --user 0 com.google.android.tts >/dev/null && rec "adb shell pm enable com.google.android.tts"
  health && echo "    Text-to-Speech disabled" || { S pm enable com.google.android.tts >/dev/null; echo "    reverted"; }
fi
if has com.google.android.syncadapters.calendar && ask "Disable Google Calendar sync (nothing on the TV shows a calendar)?" N; then
  S pm disable-user --user 0 com.google.android.syncadapters.calendar >/dev/null && rec "adb shell pm enable com.google.android.syncadapters.calendar"
  health && echo "    calendar sync disabled" || { S pm enable com.google.android.syncadapters.calendar >/dev/null; echo "    reverted"; }
fi

echo ""
echo "== 4/4 Result"
sleep 20; S dumpsys meminfo > "$L/meminfo_after.txt"
echo "  Before:"; grep -E "Used RAM" "$L/meminfo_before.txt" | sed 's/^/    /'
echo "  After: "; grep -E "Used RAM" "$L/meminfo_after.txt" | sed 's/^/    /'
echo "  App cold starts (median of 3, ms):"
if command -v column >/dev/null; then column -t -s, "$L/compile_ab.csv" | sed 's/^/    /'; else tr ',' '\t' < "$L/compile_ab.csv" | sed 's/^/    /'; fi
health && echo "  ALL ESSENTIALS OK"
cat <<'TIP'

  Manual tips (TV menus), only if they apply to you:
   - No soundbar? Settings > Display & Sound > Audio output: turn eARC/ARC off (its service polls constantly).
   - Only use the remote's mic? Settings > Google Assistant: turn off hands-free "Hey Google".
   - Ethernet instead of Wi-Fi gives the steadiest streaming and AirPlay.
  Note: app updates from the Play Store reset pre-compilation for that app - run this script again any time.
TIP
echo "DONE. Details: logs/perf/   Undo: bash scripts/99_restore_all.sh"
