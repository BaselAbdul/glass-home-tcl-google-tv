#!/bin/bash
# Glass Home for TCL Google TV - by Basel Majid
# Phase 1: connect + READ-ONLY audit. Changes nothing on the TV.
# Usage: bash 01_connect_and_audit.sh <TV-IP:CONNECT-PORT>   (Wireless debugging screen)
set -u
# --- portability: macOS, Linux/WSL and Windows (Git Bash) ---
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"   # stop Git Bash rewriting /sdcard/... paths
hostpath(){ if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
TARGET="${1:?Usage: bash 01_connect_and_audit.sh 192.168.1.50:34293}"
IP="${TARGET%%:*}"
DIR="$(cd "$(dirname "$0")/.." && pwd)"; L="$DIR/logs/baseline"; mkdir -p "$L"
command -v adb >/dev/null || { echo "adb not found. macOS: brew install --cask android-platform-tools   Windows (PowerShell): winget install --id Google.PlatformTools -e  (then reopen Git Bash)"; exit 1; }
adb version | head -1
adb connect "$TARGET" >/dev/null 2>&1
if [ "$(adb -s "$TARGET" get-state 2>&1 | tr -d '\r')" != "device" ]; then
  echo ""
  echo ">>> Pairing needed. On the TV open: Wireless debugging > Pair device with pairing code"
  echo ">>> Keep that dialog OPEN. It shows  IP:PORT  and a 6-digit code."
  PP=$(adb mdns services 2>/dev/null | tr -d '\r' | awk '/_adb-tls-pairing/ {print $NF}' | grep "^$IP:" | head -1)
  [ -n "$PP" ] && echo ">>> Auto-detected pairing address: $PP (press Enter to accept)"
  read -r -p "Pairing IP:PORT from the dialog [${PP:-none}]: " IN; PP="${IN:-$PP}"
  read -r -p "6-digit pairing code from the dialog: " CODE
  adb pair "$PP" "$CODE" 2>&1 | tee "$L/00_pair.txt"
  sleep 2; adb connect "$TARGET"
fi
for i in $(seq 1 15); do
  st=$(adb -s "$TARGET" get-state 2>&1 | tr -d '\r'); [ "$st" = "device" ] && break
  adb connect "$TARGET" >/dev/null 2>&1; sleep 2
done
[ "$st" = "device" ] || { echo "NOT CONNECTED: $st"; adb devices -l | tee "$L/00_connect_error.txt"; exit 1; }
echo "$TARGET" > "$DIR/scripts/.tv_target"
export ANDROID_SERIAL="$TARGET"
S(){ adb shell "$@" 2>&1 | tr -d '\r'; }
echo "Connected. Collecting (about 1-2 min)..."
S getprop                                  > "$L/01_getprop.txt"
S cat /proc/cpuinfo                        > "$L/02_cpuinfo.txt"
S cat /proc/meminfo                        > "$L/03_meminfo.txt"
S df -h                                    > "$L/04_df.txt"
S dumpsys diskstats                        > "$L/05_diskstats.txt"
S pm list packages -f -s                   > "$L/10_pkgs_system.txt"
S pm list packages -f -3                   > "$L/11_pkgs_3rdparty.txt"
S pm list packages -d                      > "$L/12_pkgs_disabled.txt"
S pm list packages -u                      > "$L/13_pkgs_all_incl_uninstalled.txt"
S pm list packages -e                      > "$L/14_pkgs_enabled.txt"
S dumpsys package packages | grep -E "Package \[|versionName|enabled=|codePath|firstInstallTime|lastUpdateTime" > "$L/15_pkg_details.txt"
S dumpsys meminfo                          > "$L/20_dumpsys_meminfo.txt"
S ps -A -o PID,USER,RSS,NAME               > "$L/21_ps.txt"
S top -b -n 2 -d 3 -m 30                   > "$L/22_top.txt"
S dumpsys activity processes               > "$L/23_activity_processes.txt"
S dumpsys activity services                > "$L/24_activity_services.txt"
S dumpsys procstats --hours 3              > "$L/25_procstats.txt"
S cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$L/30_home_launcher.txt"
S cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME >> "$L/30_home_launcher.txt"
S cmd package query-receivers --brief -a android.intent.action.BOOT_COMPLETED > "$L/31_boot_receivers.txt"
S wm size                                  > "$L/32_display.txt"; S wm density >> "$L/32_display.txt"
S dumpsys display | grep -iE "mode|hdr|supportedModes|DisplayDeviceInfo" | head -60 >> "$L/32_display.txt"
S dumpsys hdmi_control                     > "$L/33_hdmi_cec.txt"
S dumpsys audio | head -150                > "$L/34_audio.txt"
S dumpsys media.drm                        > "$L/35_drm.txt"
S settings list global                     > "$L/40_settings_global.txt"
S settings list secure                     > "$L/41_settings_secure.txt"
S settings list system                     > "$L/42_settings_system.txt"
S dumpsys connectivity | head -80          > "$L/43_network.txt"
S dumpsys bluetooth_manager | head -60     > "$L/44_bluetooth.txt"
S uptime                                   > "$L/50_uptime.txt"
S "logcat -b events -d | grep -E 'boot_progress|sf_stop_bootanim'" > "$L/51_boot_events.txt"
S dumpsys gfxinfo com.google.android.apps.tv.launcherx > "$L/52_launcher_gfx.txt"
S dumpsys accessibility | head -40         > "$L/53_accessibility.txt"
S dumpsys deviceidle whitelist             > "$L/54_deviceidle.txt"
S "pm list packages -s | wc -l; pm list packages -3 | wc -l"    > "$L/60_counts.txt"
# per-package sizes (read-only)
date > "$L/99_done.txt"
echo "DONE. Results saved in: $L"
