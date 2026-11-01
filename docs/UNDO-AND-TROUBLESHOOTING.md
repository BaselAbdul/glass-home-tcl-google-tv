# Undo and troubleshooting

**Glass Home for TCL Google TV — by Basel Majid**

## Undo everything

Every change the scripts make is written to `scripts/99_restore_all.sh` on your computer
(run it from Terminal on macOS or Git Bash on Windows).

```
bash scripts/00_reconnect.sh <TV-IP:PORT>
bash scripts/99_restore_all.sh
```

This re-enables everything that was disabled, reinstalls preinstalled apps that were removed,
restores animation speeds and Wi-Fi settings, puts the Google TV home screen back and uninstalls
Glass Home. (Apps from the Play Store that you chose to remove can be reinstalled from the Play Store.)

A **factory reset** (Settings → System → About → Reset) also returns the TV completely to stock.

## Put the Google TV home screen back (manually)

```
adb shell pm enable com.google.android.tungsten.setupwraith
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell cmd role add-role-holder --user 0 android.app.role.HOME com.google.android.apps.tv.launcherx
adb uninstall com.basel.tvhome
```

## Common problems

**"No route to host" but the TV is on (macOS)**
macOS Local Network permission: System Settings → Privacy & Security → Local Network → enable
Terminal (or Cursor/iTerm), then quit and reopen it. Also check the Mac is not on a VPN.

**"cannot connect" / "failed to connect" on Windows**
Allow *adb.exe* in Windows Defender Firewall (Private networks), set your Wi-Fi network profile to
**Private**, turn off any VPN, and check the port on the TV (it changes after every restart).

**"adb: command not found" (Windows)**
Close Git Bash and open it again so it picks up the new install. If it still fails, run
`winget install --id Google.PlatformTools -e` in PowerShell again, then restart the PC.

**`$'\r': command not found` (Windows)**
A script was opened and saved by an editor that changed its line endings. Fix all scripts with:
`sed -i 's/\r$//' scripts/*.sh`

**Building from source on Windows**
Git Bash can install the ready-made app but cannot build from source. To build, use WSL (Ubuntu)
— see the Linux/WSL section of the install guide — or a Mac.

**"TV not connected" / the port stopped working**
The wireless debugging port changes after every TV restart or Wi-Fi drop. Open Wireless debugging on
the TV, note the new IP:port and run `bash scripts/00_reconnect.sh <IP:PORT>`.

**Pairing fails ("protocol fault")**
Use the IP:port shown **inside** the *Pair device with pairing code* box (not the main screen's
port) and the code currently shown. The code changes each time the box is opened.

**Install fails with a signature error**
A copy signed with a different key is already installed. Run `adb uninstall com.basel.tvhome` and
install again.

**Voice search stopped working**
If you disabled the TCL service bundle, re-enable it: `adb shell pm enable com.tcl.ttvs`

**A blank or Settings screen appears when pressing Home**
Glass Home is not running. Reinstall it with step 5 of the install guide, or put the Google TV home
screen back using the commands above.

**Continue Watching is empty**
Play something for at least 30 seconds, then press Home. Run `bash scripts/06_check_continue.sh` to
see which apps report what they are playing. Apps that report nothing cannot appear.

**After a TCL system update something came back**
Updates can re-enable packages. Run `bash scripts/03_debloat.sh` again (it skips anything already done).

**AirPlay stutters**
See the AirPlay tip in the install guide, then run `07_airplay_boost.sh` and `08_airplay_test.sh`.
