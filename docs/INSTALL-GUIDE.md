# Install guide

**Glass Home for TCL Google TV — by Basel Majid**

Allow about 20–30 minutes the first time. Keep the TV remote down whenever a script is running.
Every command below works the same on **macOS (Terminal)** and **Windows (Git Bash)**.

---

## 0. Get your computer ready

You need the **TV and the computer on the same network** (not a guest network). Pick your system:

### macOS

1. Install Homebrew if you don't have it: https://brew.sh
2. Install ADB (Google's official Android debugging tool):
   ```
   brew install --cask android-platform-tools
   adb version
   ```
3. Unzip this package, open **Terminal** and go to the folder:
   ```
   cd ~/Downloads/GlassHome-TCL-GoogleTV
   ```

### Windows 10 / 11

1. Open **PowerShell** (Start → type *PowerShell*) and install Git Bash and ADB:
   ```
   winget install --id Git.Git -e
   winget install --id Google.PlatformTools -e
   ```
2. Unzip this package: right-click the zip → **Extract All…** → extract to your **Downloads** folder.
3. Open **Git Bash** (Start → type *Git Bash*). All commands in this guide are typed here,
   exactly as written — they are the same as on a Mac.
   ```
   adb version
   cd ~/Downloads/GlassHome-TCL-GoogleTV
   ```
   If `adb` is "command not found", close Git Bash and open it again (or restart the PC) so it
   picks up the new install.
4. The first time ADB connects, **Windows Defender Firewall** may ask about *adb.exe*: choose
   **Allow** on **Private networks**.
5. Make sure your Wi-Fi is set as a **Private network**: Settings → Network & Internet → Wi-Fi →
   your network → *Network profile type* → **Private**.

### Linux, or WSL on Windows (only needed to build from source)

```
sudo apt install -y curl unzip zip openssl
curl -LO https://dl.google.com/android/repository/platform-tools-latest-linux.zip
unzip -o platform-tools-latest-linux.zip -d ~ && echo 'export PATH="$HOME/platform-tools:$PATH"' >> ~/.bashrc && source ~/.bashrc
adb version
```

(Use Google's platform-tools as above — older distribution packages of `adb` cannot pair over Wi-Fi.)

---

## 1. Turn on wireless debugging on the TV

1. **Settings → System → About** → click **Android TV OS build** 7 times
   ("You are now a developer").
2. **Settings → System → Developer options** → turn on **Wireless debugging**
   (on some models it is called *USB debugging*; turn that on too if present).
3. Open **Wireless debugging** and note the **IP address & port**, for example `192.168.1.50:41235`.

> **Can't connect even though the TV is on?**
> - **macOS** ("No route to host"): System Settings → Privacy & Security → **Local Network** → enable
>   **Terminal** (or the app you run commands from), then quit and reopen it.
> - **Windows**: allow *adb.exe* in Windows Defender Firewall and set your Wi-Fi to **Private** (step 0).
> - Both: turn off any VPN.

---

## 2. Connect and audit (read-only)

```
bash scripts/01_connect_and_audit.sh 192.168.1.50:41235
```

The first time, the TV must be paired:

1. On the TV open **Wireless debugging → Pair device with pairing code** and keep that box open.
2. The script asks for the **IP:port shown inside the pairing box** (it is a different port) and the
   **6-digit code**.
3. If the TV asks *Allow debugging?*, tick **Always allow** and press **OK**.

This step only reads information. The results are saved in `logs/baseline/`.

**Reconnecting later** (after the TV restarts or Wi-Fi drops — the port changes every time):

```
bash scripts/00_reconnect.sh 192.168.1.50:NEWPORT
```

---

## 3. Back up and measure

```
bash scripts/02_backup_and_benchmark.sh baseline
```

Saves which apps are on/off and your settings to `backup/`, then measures app launch times,
home-screen smoothness and memory. The TV navigates by itself for a minute — don't touch it.

---

## 4. Clean up (reversible)

```
bash scripts/03_debloat.sh
```

- **Recommended clean-up** (asked once): TCL store-demo, sticker, hotel mode, Alexa, TCL Channel,
  promo feed, T-Cast, TCL account, TCL promo push, Brazilian ISDB interactive TV, and Google's
  Ambient-mode screensaver.
- **Optional, asked one by one:** Miracast, AirPlay, the TCL service bundle (analytics/Alexa/T-Cast),
  TCL Browser, Prime Video, YouTube Music, Play Games. The default answer is **No**.
- **Snappier animations** (0.5×), asked at the end.

After every single change the script checks Google services, Play Store, Cast, voice search,
HDMI-CEC, Widevine, Bluetooth and the network. If anything fails it undoes that change and stops.

---

## 5. Install Glass Home

**Option A — ready-made app (recommended, macOS and Windows):**

```
bash scripts/05_build_and_install_home.sh --prebuilt apk/GlassHome-1.0.apk
```

**Option B — build it yourself from the source (macOS, Linux, or WSL on Windows):**

```
bash scripts/05_build_and_install_home.sh
```

Option B downloads Java 17 (Adoptium, checksum-verified) and Google's Android build tools
(about 400 MB) into a local `toolchain/` folder, asks you to accept Google's Android SDK licence,
and signs the app with a private key created on your computer. Use it if you want to change the code
or the wallpaper.

What the install step does:

1. Installs the app and grants three permissions: *read TV listings* and *usage access*
   (Continue Watching), and *notification access* (used only to read what is playing).
2. Makes Glass Home your Home app.
3. Disables the Google TV launcher and Google TV's setup/recovery screen — Android always prefers
   these two over any other Home app, so they have to be switched off. (Settings keeps a built-in
   emergency home screen as a safety net.)
4. Presses Home three times and checks that Glass Home opens, that Settings opens and that voice
   search still works. **If any check fails, everything is switched straight back to Google TV.**

> Only one copy can be installed. If you switch from the ready-made app to your own build (or the
> other way round), remove the old one first: `adb uninstall com.basel.tvhome`

---

## 6. Using Glass Home

| Action | How |
|---|---|
| Move around | D-pad on the remote |
| Open | **OK** |
| Rearrange, add to / remove from favourites, hide | **Hold OK** on a tile |
| Show hidden apps | **Hold OK** on **Settings** → *Show hidden apps* |
| Focus View on/off | **Up** from favourites → ring icon → **OK** (or **Back** to leave) |
| Wallpaper on/off | **Up** from favourites → streaks icon → **OK** |
| Switch to a console / HDMI device | Select its tile (named by the device, e.g. *PlayStation 5*) |

**Continue Watching** shows the last title you actually watched in each app (trailers and autoplay
previews are ignored), newest first. It fills in from the moment Glass Home is installed — play
something for about 30 seconds, then press Home. Apps that don't report what they are playing will
not appear. Hold OK on a card to remove it.

---

## 7. Optional extras

| Script | What it does | Works on |
|---|---|---|
| `02_backup_and_benchmark.sh after` | Re-measures, so you can compare with the baseline | macOS, Windows |
| `06_check_continue.sh` | Shows what each app reports as "now playing" + a screenshot of the row | macOS, Windows |
| `09_record_tour.sh` | Records a smooth tour of the home screen and masters it to 4K 60 fps | macOS, Windows |
| `07_airplay_boost.sh` | Measures AirPlay mirroring and applies safe, reversible Wi-Fi tweaks | macOS |
| `08_airplay_test.sh 4k` / `1080p` | A/B test of AirPlay smoothness (dropped frames, freezes) | macOS |

The 4K recording needs **ffmpeg**: macOS `brew install ffmpeg` · Windows (PowerShell)
`winget install --id Gyan.FFmpeg -e` · Linux `sudo apt install -y ffmpeg`.

**AirPlay tip (Mac):** mirroring always re-encodes your screen. For smooth mirroring choose **1080p** for
the TV in the Mac's Display settings. For films, use the AirPlay button inside the video app
instead of mirroring — the TV then plays the original 4K HDR stream directly.

---

## 8. When you are done

You can turn **Wireless debugging** off in Developer options. Turn it back on (and reconnect with
`00_reconnect.sh`) whenever you want to undo or update something.
