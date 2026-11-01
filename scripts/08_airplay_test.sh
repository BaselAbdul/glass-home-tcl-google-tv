#!/bin/bash
# Glass Home for TCL Google TV - by Basel Majid
# AirPlay A/B test. Usage: bash 08_airplay_test.sh <label>   e.g.  4k   or   1080p
# 1) finds where network jitter comes from (Mac side vs TV side)
# 2) optional: tests the Mac with AWDL (AirDrop radio) paused  - asks first, uses sudo
# 3) measures the AirPlay stream: resolution, fps, dropped frames, freezes
set -u
# --- portability: macOS, Linux/WSL and Windows (Git Bash) ---
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"   # stop Git Bash rewriting /sdcard/... paths
hostpath(){ if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
[ "$(uname -s)" = "Darwin" ] || { echo "This AirPlay tool is for Macs (AirPlay mirroring comes from Apple devices)."; exit 0; }
LABEL="${1:-test}"
DIR="$(cd "$(dirname "$0")/.." && pwd)"; L="$DIR/logs/airplay"; mkdir -p "$L"
export ANDROID_SERIAL="$(cat "$DIR/scripts/.tv_target")"; adb connect "$ANDROID_SERIAL" >/dev/null 2>&1
[ "$(adb get-state 2>&1 | tr -d '\r')" = "device" ] || { echo "TV not connected - run 00_reconnect.sh first"; exit 1; }
S(){ adb shell "$@" 2>&1 | tr -d '\r'; }
TVIP="${ANDROID_SERIAL%%:*}"; GW=$(route -n get default 2>/dev/null | awk '/gateway/{print $2}')
pingstat(){ ping -c 40 -i 0.2 "$1" 2>/dev/null | awk -F'[/ =]+' '/round-trip/{printf "avg %.1f ms | max %.1f ms | jitter %.1f ms", $7, $8, $9}'; }

echo "== 1/3 Where is the jitter?"
R=$(pingstat "$GW"); T=$(pingstat "$TVIP")
echo "  Mac -> router ($GW): $R"
echo "  Mac -> TV    ($TVIP): $T"
echo "$(date +%F_%T),$LABEL,router,$R,tv,$T" >> "$L/jitter.csv"

if ifconfig awdl0 2>/dev/null | grep -q "status: active"; then
  echo ""
  echo "== 2/3 Optional: the Mac's AirDrop radio (AWDL) hops channels and is a known cause of"
  echo "   Wi-Fi latency spikes. Pausing it disables AirDrop/Sidecar/Universal Control until you"
  echo "   turn it back on or restart the Mac. Nothing else changes."
  read -r -p "   Test with AWDL paused? (asks for your Mac password) [y/N]: " A
  if [ "${A:-N}" = "y" ] || [ "${A:-N}" = "Y" ]; then
    sudo ifconfig awdl0 down && echo "  AWDL paused."
    T2=$(pingstat "$TVIP"); echo "  Mac -> TV with AWDL paused: $T2"
    echo "$(date +%F_%T),$LABEL,awdl_off,tv,$T2" >> "$L/jitter.csv"
    read -r -p "   Keep AWDL paused for this AirPlay session? [y/N]: " K
    if [ "${K:-N}" != "y" ] && [ "${K:-N}" != "Y" ]; then sudo ifconfig awdl0 up && echo "  AWDL restored."
    else echo "  Kept paused. Restore any time:  sudo ifconfig awdl0 up   (or restart the Mac)"; fi
  fi
fi

echo ""
echo "== 3/3 Stream test [$LABEL]"
echo "   a) Start AirPlay mirroring to the TV and play a video for ~30 seconds."
echo "   b) STOP mirroring (this makes the TV write its playback statistics)."
read -r -p "   c) Then press Enter... " _
sleep 2
UID_AP=$(S dumpsys package com.mediatek.AirplayAPK | awk -F'[= ]+' '/userId=/{print $3; exit}')
REC=$(S dumpsys media.metrics | grep -E "codec, .*\($UID_AP, " | grep -E "mediacodec.mode=video" | tail -1)
[ -z "$REC" ] && { echo "   No AirPlay video session found - make sure mirroring was stopped before pressing Enter."; exit 1; }
k(){ echo "$REC" | grep -o "android.media.mediacodec.$1=[^,)]*" | head -1 | cut -d= -f2; }
W=$(k width); H=$(k height); MIME=$(k mime); FPS=$(k framerate-content); REN=$(k frames-rendered); DROP=$(k frames-dropped)
FRZ=$(k freeze-count); DUR=$(k playback-duration-sec); JUD=$(k judder-count)
PCT=$(awk -v d="${DROP:-0}" -v r="${REN:-0}" 'BEGIN{t=d+r; if(t>0) printf "%.1f", 100*d/t; else print "n/a"}')
FPM=$(awk -v f="${FRZ:-0}" -v s="${DUR:-0}" 'BEGIN{if(s>0) printf "%.1f", f/s; else print "n/a"}')
printf "   Stream: %sx%s %s @ %.0f fps | %s s played\n" "$W" "$H" "$MIME" "${FPS:-0}" "$DUR"
echo "   Dropped frames: $DROP of $((${REN:-0}+${DROP:-0})) ($PCT%) | freezes: $FPM per second | judder events: $JUD"
echo "$(date +%F_%T),$LABEL,${W}x${H},$MIME,$FPS,$DUR,$REN,$DROP,$PCT,$FRZ,$FPM,$JUD" >> "$L/results.csv"
echo ""; echo "History (label, resolution, dropped %, freezes/s):"
awk -F, '{printf "   %-8s %-10s dropped %5s%%  freezes %s/s\n", $2, $3, $9, $11}' "$L/results.csv" | tail -6
