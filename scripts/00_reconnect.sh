#!/bin/bash
# Glass Home for TCL Google TV - by Basel Majid
# Reconnect to the TV after Wi-Fi drop / reboot. Does NOT re-run the audit.
# Usage: bash 00_reconnect.sh <TV-IP:PORT>   (IP:PORT shown on the TV's Wireless debugging screen)
set -u
# --- portability: macOS, Linux/WSL and Windows (Git Bash) ---
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"   # stop Git Bash rewriting /sdcard/... paths
hostpath(){ if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
DIR="$(cd "$(dirname "$0")/.." && pwd)"
T="${1:?Usage: bash 00_reconnect.sh 192.168.1.50:PORT  (from TV > Wireless debugging)}"
IP="${T%%:*}"
adb disconnect >/dev/null 2>&1
adb connect "$T"
sleep 2
if [ "$(adb -s "$T" get-state 2>&1 | tr -d '\r')" != "device" ]; then
  echo ""
  echo ">>> The TV needs pairing again. On the TV: Wireless debugging > Pair device with pairing code (keep it open)."
  PP=$(adb mdns services 2>/dev/null | tr -d '\r' | awk '/_adb-tls-pairing/ {print $NF}' | grep "^$IP:" | head -1)
  [ -n "$PP" ] && echo ">>> Auto-detected pairing address: $PP (press Enter to accept)"
  read -r -p "Pairing IP:PORT [${PP:-none}]: " IN; PP="${IN:-$PP}"
  read -r -p "6-digit code: " CODE
  adb pair "$PP" "$CODE"
  sleep 2; adb connect "$T"; sleep 2
fi
if [ "$(adb -s "$T" get-state 2>&1 | tr -d '\r')" = "device" ]; then
  echo "$T" > "$DIR/scripts/.tv_target"
  echo "CONNECTED to $T - saved for all scripts."
else
  echo "Still not connected. Check the TV and Mac are on the same Wi-Fi and Wireless debugging is ON."; exit 1
fi
