#!/bin/bash
# Glass Home for TCL Google TV - by Basel Majid
# After playing something in Apple TV / YouTube / Netflix / HBO Max: shows what each app
# reports as "now playing", what the launcher captured, and screenshots the home screen.
set -u
# --- portability: macOS, Linux/WSL and Windows (Git Bash) ---
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"   # stop Git Bash rewriting /sdcard/... paths
hostpath(){ if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
DIR="$(cd "$(dirname "$0")/.." && pwd)"; L="$DIR/logs"; mkdir -p "$L/screens"
export ANDROID_SERIAL="$(cat "$DIR/scripts/.tv_target")"; adb connect "$ANDROID_SERIAL" >/dev/null 2>&1
[ "$(adb get-state 2>&1 | tr -d '\r')" = "device" ] || { echo "TV not connected - run 00_reconnect.sh first"; exit 1; }
S(){ adb shell "$@" 2>&1 | tr -d '\r'; }
{
  echo "=== Media sessions reported by apps (package / state / metadata)"
  S dumpsys media_session | grep -E "package=|  state=PlaybackState|metadata:|description=" | sed 's/^ *//' | head -60
  echo; echo "=== Captured by launcher"
  S logcat -d -s TvHome:* | grep -E "MEDIAWATCHER|WATCHNEXT" | tail -15
} > "$L/continue_check.txt"
cat "$L/continue_check.txt" | grep -E "package=|description=|MEDIAWATCHER record|WATCHNEXT" | tail -25
S input keyevent KEYCODE_HOME; sleep 3; S input keyevent KEYCODE_DPAD_DOWN; sleep 2
adb exec-out screencap -p > "$L/screens/continue_row.png"; S input keyevent KEYCODE_DPAD_UP
echo "DONE. Saved logs/continue_check.txt and logs/screens/continue_row.png"
