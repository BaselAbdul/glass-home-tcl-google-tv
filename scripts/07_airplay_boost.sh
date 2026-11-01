#!/bin/bash
# Glass Home for TCL Google TV - by Basel Majid
# AirPlay smoothness: measure Mac Wi-Fi + jitter, apply reversible TV radio tweaks,
# then measure the live AirPlay stream (resolution / fps / codec / CPU) while you mirror.
set -u
# --- portability: macOS, Linux/WSL and Windows (Git Bash) ---
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"   # stop Git Bash rewriting /sdcard/... paths
hostpath(){ if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
[ "$(uname -s)" = "Darwin" ] || { echo "This AirPlay tool is for Macs (AirPlay mirroring comes from Apple devices)."; exit 0; }
DIR="$(cd "$(dirname "$0")/.." && pwd)"; L="$DIR/logs/airplay"; mkdir -p "$L"
export ANDROID_SERIAL="$(cat "$DIR/scripts/.tv_target")"; adb connect "$ANDROID_SERIAL" >/dev/null 2>&1
[ "$(adb get-state 2>&1 | tr -d '\r')" = "device" ] || { echo "TV not connected - run 00_reconnect.sh first"; exit 1; }
S(){ adb shell "$@" 2>&1 | tr -d '\r'; }
TVIP="${ANDROID_SERIAL%%:*}"; RESTORE="$DIR/scripts/99_restore_all.sh"; LOG="$DIR/logs/changes.csv"

echo "== 1/4 Mac Wi-Fi"
system_profiler SPAirPortDataType 2>/dev/null | awk '/Current Network Information:/{f=1;next} f&&/Other Local Wi-Fi Networks:/{exit} f' \
  | grep -E "PHY Mode|Channel|Transmit Rate|Signal / Noise|Security|:$" | head -8 | tee "$L/mac_wifi.txt"
echo "== Mac -> TV latency/jitter (30 pings)"
ping -c 30 -i 0.2 "$TVIP" | tail -2 | tee "$L/ping_before.txt"

echo "== 2/4 TV radio tweaks (reversible)"
[ -f "$RESTORE" ] || { printf '#!/bin/bash\n# Auto-generated: reverses every change made by the Glass Home scripts on this TV\nDIR="$(cd "$(dirname "$0")/.." && pwd)"; export ANDROID_SERIAL="$(cat "$DIR/scripts/.tv_target")"; adb connect "$ANDROID_SERIAL"\n' > "$RESTORE"; chmod +x "$RESTORE"; }
old=$(S settings get global wifi_scan_always_enabled)
S cmd wifi set-scan-always-available disabled >/dev/null 2>&1 || S settings put global wifi_scan_always_enabled 0
echo "  background Wi-Fi scanning: $old -> $(S settings get global wifi_scan_always_enabled)"
grep -q "wifi_scan_always_enabled" "$RESTORE" || echo "adb shell settings put global wifi_scan_always_enabled ${old:-1}" >> "$RESTORE"
r=$(S cmd wifi force-low-latency-mode enabled)
if [ -z "$r" ] || echo "$r" | grep -qi "success\|enabled"; then
  echo "  Wi-Fi low-latency mode: ON (power-save + roaming scans off; resets on TV reboot)"
  grep -q "force-low-latency-mode" "$RESTORE" || echo "adb shell cmd wifi force-low-latency-mode disabled" >> "$RESTORE"
else
  r2=$(S cmd wifi force-hi-perf-mode enabled)
  if [ -z "$r2" ]; then echo "  Wi-Fi high-performance mode: ON (resets on TV reboot)"
    grep -q "force-hi-perf-mode" "$RESTORE" || echo "adb shell cmd wifi force-hi-perf-mode disabled" >> "$RESTORE"
  else echo "  Wi-Fi low-latency/hi-perf mode: not permitted on this firmware ($r)"; fi
fi
echo "$(date +%H:%M:%S),wifi_tuning,airplay,done,OK" >> "$LOG"

echo ""
echo "== 3/4 Now start AirPlay mirroring from the Mac to the TV and play a video (YouTube in Safari is fine)."
read -r -p ">>> Press Enter once it has been playing for ~15 seconds..." _
S logcat -c
sleep 12
{
  echo "--- decoder sessions (most recent)"; S dumpsys media.metrics | grep -iE "codec|mime|width|height|frame-rate|framerate|android.media.mediacodec" | tail -25
  echo "--- media codec instances"; S dumpsys media.player | grep -iE "mime|width|height|framerate" | tail -10
  echo "--- CPU top 12"; S top -b -n 1 -m 12
  echo "--- thermal"; S dumpsys thermalservice | grep -iE "Status|Temperature\{" | head -6
  echo "--- TV Wi-Fi now"; S cmd wifi status | grep -iE "RSSI|Link speed|Frequency|standard"
  echo "--- AirPlay logs"; S logcat -d | grep -iE "airplay|mirror|fps|drop" | tail -30
} > "$L/during_mirror.txt" 2>&1
ping -c 30 -i 0.2 "$TVIP" | tail -2 | tee "$L/ping_during.txt"
echo "== 4/4 Summary"
grep -iE "width|height|frame-rate|mime" "$L/during_mirror.txt" | tail -8
grep -iE "airplay|mediatek" "$L/during_mirror.txt" | grep -iE "^ *[0-9]+ " | head -4
echo "DONE. Details saved in logs/airplay/"
