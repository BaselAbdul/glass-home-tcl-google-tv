#!/bin/bash
# Glass Home for TCL Google TV - by Basel Majid
# Records a smooth, real-time tour of the home screen (normal view -> wallpaper -> Focus View)
# and masters it to 3840x2160 @ 60 fps (10-bit HEVC). Output: ../recordings/
# Note: the TV renders its interface at 1920x1080, so the capture is 1080p60 and is upscaled
# to 4K with Lanczos + light sharpening (no speed-up, constant 60 fps).
set -u
# --- portability: macOS, Linux/WSL and Windows (Git Bash) ---
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"   # stop Git Bash rewriting /sdcard/... paths
hostpath(){ if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
DIR="$(cd "$(dirname "$0")/.." && pwd)"; OUTD="$DIR/recordings"; mkdir -p "$OUTD"
export ANDROID_SERIAL="$(cat "$DIR/scripts/.tv_target")"; adb connect "$ANDROID_SERIAL" >/dev/null 2>&1
[ "$(adb get-state 2>&1 | tr -d '\r')" = "device" ] || { echo "TV not connected - run 00_reconnect.sh first"; exit 1; }
S(){ adb shell "$@" 2>&1 | tr -d '\r'; }
PKG=com.basel.tvhome

echo "== 1/5 Checks"
VC=$(S dumpsys package $PKG | grep -o "versionCode=[0-9]*" | head -1 | cut -d= -f2)
if [ -z "$VC" ] || [ "$VC" -lt 10 ]; then
  echo "  Launcher v${VC:-none} -> installing v10 first (adds the recording hook)"
  APKP=$(ls "$DIR"/apk/GlassHome-*.apk 2>/dev/null | head -1)
  if [ -n "$APKP" ]; then bash "$DIR/scripts/05_build_and_install_home.sh" --prebuilt "$APKP" || exit 1
  else bash "$DIR/scripts/05_build_and_install_home.sh" || exit 1; fi
fi
if ! command -v ffmpeg >/dev/null; then
  case "$(uname -s)" in
    Darwin) read -r -p "  ffmpeg (video encoder) is needed for the 4K master. Install it with Homebrew? [y/N]: " A
            if [ "${A:-N}" = "y" ] || [ "${A:-N}" = "Y" ]; then brew install ffmpeg; else NOFF=1; fi ;;
    Linux)  echo "  ffmpeg not found. Install it with:  sudo apt install -y ffmpeg   (then run again)"; NOFF=1 ;;
    *)      echo "  ffmpeg not found. Install it in PowerShell with:  winget install --id Gyan.FFmpeg -e"
            echo "  then reopen Git Bash and run again."; NOFF=1 ;;
  esac
  [ -n "${NOFF:-}" ] && echo "  Continuing with the raw 1080p capture only."
fi

echo "== 2/5 Setting a clean starting state (normal view, wallpaper on, first favourite)"
S input keyevent KEYCODE_HOME >/dev/null; sleep 1.5
S logcat -c
S am start -n $PKG/.HomeActivity --ez focusView false --ez wallpaper true >/dev/null
sleep 3
CW=$(S logcat -d -s TvHome:* | grep -o "shown=[0-9]*" | tail -1 | cut -d= -f2); CW=${CW:-0}
echo "  Continue Watching items: $CW"

# Choreography (runs on the TV in one shell, so timing is steady). Calm, natural pacing.
K='k(){ input keyevent $1; }'
FAV='k 22; sleep 0.75; k 22; sleep 0.75; k 22; sleep 1.0'
if [ "$CW" -gt 0 ]; then
  ROWS='k 20; sleep 1.1; k 22; sleep 0.9; k 20; sleep 1.0; k 22; sleep 0.65; k 22; sleep 0.65; k 22; sleep 0.9; k 19; sleep 0.75; k 19; sleep 0.8'
  PILL='k 20; sleep 1.3; k 19; sleep 0.9'
else
  ROWS='k 20; sleep 1.0; k 22; sleep 0.65; k 22; sleep 0.65; k 22; sleep 0.9; k 19; sleep 0.8'
  PILL=''
fi
SWITCHES='k 19; sleep 1.0; k 22; sleep 0.9; k 23; sleep 1.5; k 23; sleep 1.5; k 21; sleep 0.9'
FOCUS='k 23; sleep 2.4; k 20; sleep 0.9; k 21; sleep 0.75; k 21; sleep 0.75; k 21; sleep 0.75; k 21; sleep 0.9'
EXIT='k 4; sleep 1.7'
TOUR="$K; sleep 0.6; $FAV; $ROWS; $SWITCHES; $FOCUS; $PILL; $EXIT"

echo "== 3/5 Recording (about 35 s) - please don't touch the remote"
S rm -f /sdcard/tvhome_tour.mp4 >/dev/null
adb shell "screenrecord --bit-rate 50000000 --size 1920x1080 /sdcard/tvhome_tour.mp4" >/dev/null 2>&1 &
REC=$!
sleep 1.6
adb shell "$TOUR"
sleep 0.4
adb shell "pkill -INT screenrecord" >/dev/null 2>&1
wait $REC 2>/dev/null; sleep 1.2
TS=$(date +%Y%m%d_%H%M%S)
RAW="$OUTD/home_tour_${TS}_raw1080p.mp4"
adb pull /sdcard/tvhome_tour.mp4 "$(hostpath "$RAW")" >/dev/null && S rm -f /sdcard/tvhome_tour.mp4 >/dev/null
[ -s "$RAW" ] || { echo "Recording failed (no file)."; exit 1; }
echo "  Raw capture: $RAW"
[ -n "${NOFF:-}" ] && exit 0

echo "== 4/5 Mastering to 3840x2160 @ 60 fps (10-bit HEVC)"
DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$(hostpath "$RAW")" | tr -d '\r')
LEN=$(awk -v d="$DUR" 'BEGIN{printf "%.2f", d-1.0-0.25}')   # trim the idle lead-in and tail only
OUT="$OUTD/home_tour_${TS}_4K60.mp4"
VF="fps=60,scale=3840:2160:flags=lanczos+accurate_rnd+full_chroma_int,unsharp=5:5:0.35:5:5:0.0,format=yuv420p10le"
if ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libx265; then
  ENC=(-c:v libx265 -preset slow -crf 14 -x265-params "aq-mode=3:deblock=-1,-1:log-level=error")
else
  ENC=(-c:v hevc_videotoolbox -profile:v main10 -b:v 80M)
fi
ffmpeg -hide_banner -loglevel error -stats -ss 1.0 -i "$(hostpath "$RAW")" -t "$LEN" -vf "$VF" "${ENC[@]}" \
  -tag:v hvc1 -color_primaries bt709 -color_trc bt709 -colorspace bt709 -movflags +faststart -an -y "$(hostpath "$OUT")" || exit 1

echo "== 5/5 Done"
ffprobe -v error -select_streams v:0 -show_entries stream=width,height,r_frame_rate,codec_name,pix_fmt:format=duration -of default=nw=1 "$(hostpath "$OUT")" | tr '\n' ' '; echo
echo "  4K master: $OUT"
case "$(uname -s)" in
  Darwin) open -R "$OUT" 2>/dev/null ;;
  MINGW*|MSYS*|CYGWIN*) explorer.exe "/select,$(hostpath "$OUT")" 2>/dev/null ;;
  *) command -v xdg-open >/dev/null && xdg-open "$OUTD" >/dev/null 2>&1 ;;
esac
true
