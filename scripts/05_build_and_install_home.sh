#!/bin/bash
# Glass Home for TCL Google TV - by Basel Majid
# Builds the Glass Home launcher from source (macOS or Linux/WSL) or installs the ready-made APK (any OS),
# installs it on the TV, makes it the Home screen and disables the Google TV launcher,
# with automatic checks and automatic revert on any failure.
#
#   bash 05_build_and_install_home.sh                     build from source + install
#   bash 05_build_and_install_home.sh --prebuilt <apk>    install a ready-made APK (no build tools needed)
#   BUILD_ONLY=1 bash 05_build_and_install_home.sh        build + sign only
#
# The build toolchain is self-contained in ../toolchain (delete that folder to remove it).
set -u
# --- portability: macOS, Linux/WSL and Windows (Git Bash) ---
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"   # stop Git Bash rewriting /sdcard/... paths
hostpath(){ if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
DIR="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${HOME_SRC:-$DIR/home-launcher}"; TC="$DIR/toolchain"; OUT="${HOME_OUT:-$SRC/build}"; LOGD="$DIR/logs"
VERSION_CODE=10; VERSION_NAME=1.0
mkdir -p "$TC" "$LOGD"
PREBUILT=""
if [ "${1:-}" = "--prebuilt" ]; then
  PREBUILT="${2:?Usage: --prebuilt path/to/GlassHome.apk}"
  [ -f "$PREBUILT" ] || { echo "APK not found: $PREBUILT"; exit 1; }
fi
PKG=com.basel.tvhome; HOMEACT="$PKG/.HomeActivity"; GTV=com.google.android.apps.tv.launcherx; SW=com.google.android.tungsten.setupwraith

case "$(uname -s)" in Darwin) PLAT=mac;; Linux) PLAT=linux;; MINGW*|MSYS*|CYGWIN*) PLAT=windows;; *) PLAT=other;; esac
if [ -z "$PREBUILT" ] && [ "$PLAT" != mac ] && [ "$PLAT" != linux ]; then
  echo "Building from source needs macOS or Linux (on Windows: use WSL/Ubuntu)."
  APKP=$(ls "$DIR"/apk/GlassHome-*.apk 2>/dev/null | head -1)
  echo "To install the ready-made app instead:  bash scripts/05_build_and_install_home.sh --prebuilt ${APKP:-apk/GlassHome-1.0.apk}"
  exit 1
fi
if [ -z "$PREBUILT" ]; then
echo "== 1/5 Java 17"
if [ "$PLAT" = linux ]; then
  miss=""; for c in curl unzip zip openssl; do command -v $c >/dev/null || miss="$miss $c"; done
  [ -n "$miss" ] && { echo "Missing tools:$miss  ->  sudo apt install -y$miss   (then run again)"; exit 1; }
fi
case "$(uname -m)" in arm64|aarch64) ARCH=aarch64 ;; *) ARCH=x64 ;; esac
JAVA_HOME=""
if [ "$PLAT" = mac ]; then
  for v in 21 17; do h=$(/usr/libexec/java_home -v $v 2>/dev/null) && { JAVA_HOME="$h"; break; }; done
  [ -z "$JAVA_HOME" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ] && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
else
  if command -v java >/dev/null; then
    jv=$(java -version 2>&1 | awk -F'"' '/version/{print $2}' | cut -d. -f1)
    [ "${jv:-0}" -ge 17 ] 2>/dev/null && JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
  fi
fi
JDKSUB=$( [ "$PLAT" = mac ] && echo "/Contents/Home" || echo "" )
if [ -z "$JAVA_HOME" ]; then
  JAVA_HOME=$(ls -d "$TC"/jdk-17*$JDKSUB 2>/dev/null | head -1)
  if [ -z "$JAVA_HOME" ]; then
    echo "Downloading Eclipse Temurin JDK 17 (official Adoptium build) into toolchain/ ..."
    META=$(curl -fsSL "https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=$ARCH&image_type=jdk&os=$PLAT&vendor=eclipse")
    if command -v python3 >/dev/null; then
      LINK=$(echo "$META" | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['binary']['package']['link'])")
      SUM=$(echo "$META" | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['binary']['package']['checksum'])")
    else
      LINK=$(echo "$META" | grep -o '"link" *: *"[^"]*\.tar\.gz"' | head -1 | sed 's/.*"\(http[^"]*\)"/\1/')
      SUM=$(echo "$META" | grep -o '"checksum" *: *"[0-9a-f]\{64\}"' | head -1 | grep -o '[0-9a-f]\{64\}')
    fi
    curl -fL --progress-bar -o "$TC/jdk.tar.gz" "$LINK" || exit 1
    if command -v sha256sum >/dev/null; then echo "$SUM  $TC/jdk.tar.gz" | sha256sum -c - ; else echo "$SUM  $TC/jdk.tar.gz" | shasum -a 256 -c - ; fi \
      || { echo "JDK checksum mismatch - aborting"; exit 1; }
    tar -xzf "$TC/jdk.tar.gz" -C "$TC" && rm "$TC/jdk.tar.gz"
    JAVA_HOME=$(ls -d "$TC"/jdk-17*$JDKSUB | head -1)
  fi
fi
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"; java -version 2>&1 | head -1

echo "== 2/5 Android SDK (platform 34 + build-tools 34) from dl.google.com [$PLAT]"
SDK="$TC/android-sdk"; BT="$SDK/build-tools/34.0.0"; AJ="$SDK/platforms/android-34/android.jar"
if [ ! -f "$AJ" ] || [ ! -x "$BT/aapt2" ]; then
  if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    curl -fL --progress-bar -o "$TC/clt.zip" "https://dl.google.com/android/repository/commandlinetools-$PLAT-11076708_latest.zip" || exit 1
    mkdir -p "$SDK/cmdline-tools"; unzip -q -o "$TC/clt.zip" -d "$SDK/cmdline-tools" && rm "$TC/clt.zip"
    rm -rf "$SDK/cmdline-tools/latest"; mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  fi
  echo ""; echo ">>> Google's Android SDK licence will be shown. Read it and type 'y' to accept (required to build)."
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-34" "build-tools;34.0.0" || exit 1
fi

echo "== 3/5 Build"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/gen"
{
"$BT/aapt2" compile --dir "$SRC/app/res" -o "$OUT/res.zip" &&
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJ" --manifest "$SRC/app/AndroidManifest.xml" "$OUT/res.zip" -A "$SRC/app/assets" \
   --java "$OUT/gen" --min-sdk-version 31 --target-sdk-version 34 --version-code $VERSION_CODE --version-name $VERSION_NAME &&
javac -nowarn -Xlint:none -source 1.8 -target 1.8 -bootclasspath "$AJ" -d "$OUT/classes" \
   $(find "$SRC/app/src" "$OUT/gen" -name '*.java') &&
"$BT/d8" --release --min-api 31 --lib "$AJ" --output "$OUT" $(find "$OUT/classes" -name '*.class') &&
cp "$OUT/base.apk" "$OUT/unsigned.apk" && (cd "$OUT" && zip -q unsigned.apk classes.dex) &&
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
} > "$LOGD/build.log" 2>&1 || { echo "BUILD FAILED - details in logs/build.log"; tail -30 "$LOGD/build.log"; exit 1; }
KS="${HOME_KEYSTORE:-$TC/home-release.jks}"; KP="${HOME_KEYPASS:-$TC/keystore.pass}"
if [ ! -f "$KS" ]; then   # a private signing key is created once, on this Mac only
  openssl rand -hex 16 > "$KP"; chmod 600 "$KP"
  keytool -genkeypair -keystore "$KS" -alias home -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$(cat "$KP")" -keypass "$(cat "$KP")" -dname "${HOME_DNAME:-CN=Glass Home (local build)}" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass file:"$KP" --out "$OUT/Home.apk" "$OUT/aligned.apk" >> "$LOGD/build.log" 2>&1 \
  || { echo "SIGNING FAILED - see logs/build.log"; exit 1; }
"$BT/apksigner" verify "$OUT/Home.apk" && ls -lh "$OUT/Home.apk" | awk '{print "Built Home.apk ("$5")"}'
[ "${BUILD_ONLY:-0}" = "1" ] && { echo "APK: $OUT/Home.apk"; exit 0; }
APK="$OUT/Home.apk"
else
APK="$PREBUILT"; echo "== Using ready-made APK: $APK"
fi

echo "== 4/5 Install on TV"
export ANDROID_SERIAL="$(cat "$DIR/scripts/.tv_target")"; adb connect "$ANDROID_SERIAL" >/dev/null 2>&1
[ "$(adb get-state 2>&1 | tr -d '\r')" = "device" ] || { echo "TV not connected - run 00_reconnect.sh <TV-IP:PORT> first"; exit 1; }
S(){ adb shell "$@" 2>&1 | tr -d '\r'; }
echo "Current state: home role=$(S cmd role get-role-holders --user 0 android.app.role.HOME) | GTV launcher $(S pm list packages -d $GTV | grep -q . && echo disabled || echo enabled) | setupwraith $(S pm list packages -d $SW | grep -q . && echo disabled || echo enabled)"
adb install -r "$(hostpath "$APK")" || { echo "Install failed. If an older copy signed with a different key is installed, remove it first: adb uninstall com.basel.tvhome"; exit 1; }
echo "Granting: read TV listings (Continue Watching) + usage access (resume fallback)"
S pm grant $PKG com.android.providers.tv.permission.READ_EPG_DATA
S appops set $PKG GET_USAGE_STATS allow
S cmd notification allow_listener $PKG/$PKG.MediaWatcher
{ echo "--- grants"; S dumpsys package $PKG | grep -E "READ_EPG_DATA|PACKAGE_USAGE" ; S appops get $PKG GET_USAGE_STATS
  echo "--- listener"; S dumpsys notification | grep -i "$PKG" | head -3
  echo "--- watch_next rows visible to shell"; S content query --uri content://android.media.tv/watch_next_program --projection package_name:title 2>&1 | head -8
  echo "--- usage (last 2d) for streaming apps"; S dumpsys usagestats | grep -E "package=(com.netflix.ninja|com.google.android.youtube.tv|com.apple.atve.androidtv.appletv|com.wbd.stream) " | head -6
} > "$LOGD/cw_probe.txt" 2>&1
S logcat -c
LOG="$LOGD/changes.csv"; RESTORE="$DIR/scripts/99_restore_all.sh"; SH="$LOGD/screens"; mkdir -p "$SH"
top(){ S dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity"; }
homecheck(){ S input keyevent KEYCODE_HOME; sleep 4; top | grep -q "$PKG/"; }
diag(){ D="$LOGD/home_diag.txt"; { echo "--- role holders"; S cmd role get-role-holders --user 0 android.app.role.HOME; echo "--- HOME resolve"; S cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME; echo "--- recent starts"; S logcat -d -b events -t 400 | grep -E "wm_create_activity|wm_restart_activity|wm_resume" | tail -15; S logcat -d -t 1500 | grep -iE "START u0|ActivityTaskManager.*home|launcherx" | tail -25; } > "$D" 2>&1; echo "Diagnostics saved: logs/home_diag.txt"; }
revert(){ diag; echo "!!! Reverting: Google TV home restored"; S pm enable $SW; S pm enable $GTV; S cmd role add-role-holder --user 0 android.app.role.HOME $GTV; S cmd package set-home-activity "$GTV/.home.HomeActivity"; S input keyevent KEYCODE_HOME; exit 2; }
[ -f "$RESTORE" ] || { printf '#!/bin/bash\n# Auto-generated: reverses every change made by the Glass Home scripts on this TV\nDIR="$(cd "$(dirname "$0")/.." && pwd)"; export ANDROID_SERIAL="$(cat "$DIR/scripts/.tv_target")"; adb connect "$ANDROID_SERIAL"\n' > "$RESTORE"; chmod +x "$RESTORE"; }
grep -q "uninstall $PKG" "$RESTORE" || printf "adb shell pm enable %s\nadb shell cmd role add-role-holder --user 0 android.app.role.HOME %s\nadb shell cmd package set-home-activity %s/.home.HomeActivity\nadb uninstall %s\n" $GTV $GTV $GTV $PKG >> "$RESTORE"

echo "== 5/5 Switch Home"
echo "Home role before: $(S cmd role get-role-holders --user 0 android.app.role.HOME)"
S logcat -c
S cmd role add-role-holder --user 0 android.app.role.HOME $PKG
S cmd package set-home-activity "$HOMEACT"
echo "Home role now:    $(S cmd role get-role-holders --user 0 android.app.role.HOME)"
echo "$(date +%H:%M:%S),$PKG,install+home-role,ok,OK" >> "$LOG"
echo "Disabling the two Home apps that outrank any custom launcher: Google TV launcher + setup/recovery screen"
S pm disable-user --user 0 $GTV; S pm disable-user --user 0 $SW
sleep 3
for i in 1 2 3; do homecheck || { echo "Home check $i failed:"; top; revert; }; done
S cmd package resolve-activity --brief -a android.settings.SETTINGS | tail -1 | grep -q tv.settings || { echo "Settings unreachable"; revert; }
S settings get secure voice_interaction_service | grep -q katniss || { echo "Voice service lost"; revert; }
echo "$(date +%H:%M:%S),$GTV,disable,ok,OK" >> "$LOG"; echo "$(date +%H:%M:%S),$SW,disable,ok,OK" >> "$LOG"
echo "Home OK: custom launcher is Home (3/3 checks); Settings + voice reachable"
sleep 6
echo "Launcher diagnostics:"; S logcat -d -s TvHome:* | grep -E "GLASS|WATCHNEXT|USAGE|MEDIAWATCHER|NP_|WALLPAPER" | tail -6 | tee "$LOGD/home_app_diag.txt"
S logcat -d -b crash | grep -A5 "$PKG" | head -20 | tee -a "$LOGD/home_app_diag.txt"
adb exec-out screencap -p > "$SH/home_custom.png"
S input keyevent KEYCODE_DPAD_DOWN; sleep 2; adb exec-out screencap -p > "$SH/home_custom_cw.png"; S input keyevent KEYCODE_DPAD_UP
S dumpsys meminfo $PKG | grep -E "TOTAL PSS|TOTAL:" | head -2 > "$LOGD/home_meminfo.txt"
echo "DONE. Screenshot: $SH/home_custom.png"
