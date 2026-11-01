#!/bin/bash
# Glass Home for TCL Google TV - by Basel Majid
# Step 3: reversible clean-up. One package at a time:
#   apply -> verify -> health check of essentials -> log. Any failure: auto-revert + stop.
# Every change is written to 99_restore_all.sh (run it to undo everything).
set -u
# --- portability: macOS, Linux/WSL and Windows (Git Bash) ---
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"   # stop Git Bash rewriting /sdcard/... paths
hostpath(){ if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
DIR="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_SERIAL="$(cat "$DIR/scripts/.tv_target" 2>/dev/null)"
adb connect "$ANDROID_SERIAL" >/dev/null 2>&1
[ "$(adb get-state 2>&1 | tr -d '\r')" = "device" ] || { echo "TV not connected - run 00_reconnect.sh <TV-IP:PORT> first"; exit 1; }
[ -f "$DIR/backup/pkgs_enabled.txt" ] || { echo "No backup found - run 02_backup_and_benchmark.sh first"; exit 1; }
S(){ adb shell "$@" 2>&1 | tr -d '\r'; }
mkdir -p "$DIR/logs"
LOG="$DIR/logs/changes.csv"; [ -f "$LOG" ] || echo "time,package,action,result,health" > "$LOG"
RESTORE="$DIR/scripts/99_restore_all.sh"
[ -f "$RESTORE" ] || printf '#!/bin/bash\n# Auto-generated: reverses every change made by the Glass Home scripts on this TV\nDIR="$(cd "$(dirname "$0")/.." && pwd)"; export ANDROID_SERIAL="$(cat "$DIR/scripts/.tv_target")"; adb connect "$ANDROID_SERIAL"\n' > "$RESTORE"
chmod +x "$RESTORE"

ask(){  # ask "question" Y|N  -> returns 0 for yes
  local def=$2 a; read -r -p "  $1 [$( [ "$def" = Y ] && echo Y/n || echo y/N )]: " a; a=${a:-$def}
  [ "$a" = y ] || [ "$a" = Y ]
}

# Essentials are checked only if they exist and work on THIS TV before any change.
CANDIDATES="com.google.android.gms com.android.vending com.google.android.apps.mediashell com.google.android.katniss
 com.google.android.tv.remote.service com.android.tv.settings com.tcl.tvinput com.tcl.tv com.dolby.android.audio.service
 com.android.bluetooth com.tcl.autopair com.tcl.globalkeyoverlay com.tcl.gamebar com.android.providers.tv"
ESSENTIALS=""
for q in $CANDIDATES; do S pm list packages -e $q | grep -qx "package:$q" && ESSENTIALS="$ESSENTIALS $q"; done
CHK_VOICE=$(S settings get secure voice_interaction_service | grep -q katniss && echo 1 || echo 0)
CHK_CEC=$([ "$(S settings get global hdmi_control_enabled)" = "1" ] && echo 1 || echo 0)
CHK_DRM=$(S ps -A | grep -q "drm-service.widevine" && echo 1 || echo 0)
CHK_BT=$([ "$(S settings get global bluetooth_on)" = "1" ] && echo 1 || echo 0)

health(){
  local bad="" q
  for q in $ESSENTIALS; do S pm list packages -e $q | grep -qx "package:$q" || bad="$bad $q-disabled"; done
  [ $CHK_VOICE = 1 ] && { S settings get secure voice_interaction_service | grep -q katniss || bad="$bad voice"; }
  [ $CHK_CEC = 1 ] && { [ "$(S settings get global hdmi_control_enabled)" = "1" ] || bad="$bad hdmi-cec"; }
  [ $CHK_DRM = 1 ] && { S ps -A | grep -q "drm-service.widevine" || bad="$bad widevine"; }
  [ $CHK_BT = 1 ] && { [ "$(S settings get global bluetooth_on)" = "1" ] || bad="$bad bluetooth"; }
  S ip route | grep -q "^default" || bad="$bad network"
  [ "$(S am get-current-user)" = "0" ] || bad="$bad system"
  [ -z "$bad" ] && return 0; echo "HEALTH FAIL:$bad"; return 1
}

do_pkg(){  # $1=package $2=disable|remove
  local p=$1 a=$2 r h ok sys
  if ! S pm list packages -u $p | grep -qx "package:$p"; then echo "  skip $p (not on this TV)"; return; fi
  if [ "$a" = disable ]; then
    S pm list packages -d $p | grep -qx "package:$p" && { echo "  skip $p (already disabled)"; return; }
    r=$(S pm disable-user --user 0 $p | tail -1)
    S pm list packages -d $p | grep -qx "package:$p" && ok=1 || ok=0
    echo "adb shell pm enable $p" >> "$RESTORE"
  else
    S pm list packages $p | grep -qx "package:$p" || { echo "  skip $p (already removed)"; return; }
    S pm list packages -s $p | grep -qx "package:$p" && sys=1 || sys=0
    r=$(S pm uninstall --user 0 $p | tail -1)
    S pm list packages $p | grep -qx "package:$p" && ok=0 || ok=1
    if [ $sys = 1 ]; then echo "adb shell cmd package install-existing $p" >> "$RESTORE"
    else echo "# $p was a Play Store app: reinstall from the Play Store if wanted" >> "$RESTORE"; fi
  fi
  sleep 2
  if [ $ok = 1 ] && health; then h=OK; else h=FAIL; fi
  echo "$(date +%H:%M:%S),$p,$a,$r,$h" | tee -a "$LOG"
  if [ "$h" = FAIL ]; then
    echo "!!! Problem after $p - reverting it and stopping."
    if [ "$a" = disable ]; then S pm enable $p; else S cmd package install-existing $p; fi
    exit 2
  fi
}

echo "== Pre-check"
echo "  Essentials found on this TV:$(echo $ESSENTIALS | wc -w | tr -d ' ') packages, voice=$CHK_VOICE cec=$CHK_CEC widevine=$CHK_DRM bluetooth=$CHK_BT"
health || { echo "Essentials not healthy BEFORE changes - stopping, nothing changed."; exit 1; }

echo ""
echo "== Recommended clean-up (TCL retail/demo/promo/analytics extras + Google Ambient screensaver)"
echo "   store demo, sticker, hotel mode, Alexa, TCL Channel, promo feed, T-Cast, TCL account,"
echo "   TCL home/promo push, Brazilian ISDB interactive TV, Google Ambient-mode screensaver"
if ask "Apply the recommended clean-up?" Y; then
  for p in com.tcl.esticker com.tcl.exhibit com.tcl.ocean.instructions com.tcl.repairguide com.tcl.hotelmenu \
           com.tcl.smartalexa com.tcl.channelplus com.tcl.waterfall.overseas tv.mopa.ginga com.tcl.magiconnectfree \
           com.tcl.usercenter com.tcl.tv.tclhome_passive com.tcl.messagebox com.google.android.apps.tv.dreamx; do
    do_pkg $p disable; done
fi

echo ""
echo "== Optional (your choice, one by one)"
ask "Disable Miracast (Windows/Android screen mirroring; Google Cast is NOT affected)?" N && do_pkg com.tcl.miracast disable
ask "Disable AirPlay (iPhone/Mac to TV)? Saves ~150 MB RAM" N && { do_pkg com.tcl.airplay2 disable; do_pkg com.mediatek.airplaydaemon disable; do_pkg com.mediatek.AirplayAPK disable; }
ask "Disable TCL service bundle (analytics, Alexa, T-Cast)? Test voice search afterwards" N && do_pkg com.tcl.ttvs disable
ask "Remove TCL Browser?" N && do_pkg com.tcl.browser remove
ask "Remove Prime Video (preinstalled)?" N && do_pkg com.amazon.amazonvideo.livingroom remove
ask "Remove YouTube Music (preinstalled)?" N && do_pkg com.google.android.youtube.tvmusic remove
ask "Remove Google Play Games (preinstalled)?" N && do_pkg com.google.android.play.games remove

echo ""
if ask "Make animations snappier (0.5x)?" Y; then
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do
    old=$(S settings get global $k); [ "$old" = "null" ] && old=1.0
    grep -q "settings put global $k" "$RESTORE" || echo "adb shell settings put global $k $old" >> "$RESTORE"
    S settings put global $k 0.5
  done
  echo "$(date +%H:%M:%S),animation_scales,0.5,done,$(health && echo OK || echo FAIL)" | tee -a "$LOG"
fi

echo ""; echo "== Final health"; health && echo "ALL ESSENTIALS OK"
echo "DONE. Log: logs/changes.csv   Undo everything: bash scripts/99_restore_all.sh"
