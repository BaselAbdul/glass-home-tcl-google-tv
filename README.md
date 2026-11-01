# Glass Home for TCL Google TV

**by Basel Majid** · version 1.0.1 · MIT License

A complete makeover for TCL Google TVs: a clean, fast, premium **liquid-glass home screen**, plus a
careful, fully reversible clean-up of the bloatware TCL ships with the TV. No rooting, no firmware
changes, nothing permanent. Everything is done from a **Mac or a Windows PC** over your home Wi-Fi.

---

## What you get

**Glass Home launcher**
- Dark, minimal, Apple TV-inspired home screen built for a TV across the room
- Crystal-clear liquid-glass tiles with chrome edges, light sweeps and subtle chromatic (red/blue) dispersion
- Your apps as clean white glass logos (no clashing coloured banners)
- **Favourites row**, **Continue Watching** (the last title you watched in each app, with artwork,
  progress and a platform tag) and an **Apps** shelf
- HDMI inputs named by the connected device (for example *PlayStation 5*), one press to switch
- **Focus View**: a calm single-pane mode with a large 12-hour liquid-glass clock
- **Wallpaper switch**: an optional, heavily dimmed wallpaper that the glass refracts
- No ads, no recommendations feed, no tracking, no network calls except loading artwork

**Clean-up**
- Turns off TCL retail-demo, promo, analytics and regional extras, one package at a time
- Checks the essentials after every single change and undoes it automatically if anything looks wrong
- Optional removals are asked one by one (nothing you use is removed without asking)

**What stays working:** Google account, Play Store, app updates, system updates, Chromecast,
voice search, HDMI-CEC, eARC/ARC, Dolby Vision / HDR, Dolby Atmos, Widevine (Netflix, Apple TV,
YouTube, HBO Max in HD/4K), Bluetooth, the remote, AirPlay (unless you choose to turn it off).

---

## Quick start

### macOS
1. Install ADB: `brew install --cask android-platform-tools`
2. On the TV, turn on **Developer options** and **Wireless debugging** (see the guide)
3. In **Terminal**, from this folder:
   ```
   bash scripts/01_connect_and_audit.sh <TV-IP:PORT>
   bash scripts/02_backup_and_benchmark.sh baseline
   bash scripts/03_debloat.sh
   bash scripts/05_build_and_install_home.sh --prebuilt apk/GlassHome-1.0.1.apk
   ```
4. Press **Home** on your remote.

### Windows 10 / 11
1. In **PowerShell**, install Git Bash and ADB:
   ```
   winget install --id Git.Git -e
   winget install --id Google.PlatformTools -e
   ```
2. On the TV, turn on **Developer options** and **Wireless debugging** (see the guide)
3. Open **Git Bash** from the Start menu, go to this folder and run the same commands:
   ```
   cd ~/Downloads/GlassHome-TCL-GoogleTV
   bash scripts/01_connect_and_audit.sh <TV-IP:PORT>
   bash scripts/02_backup_and_benchmark.sh baseline
   bash scripts/03_debloat.sh
   bash scripts/05_build_and_install_home.sh --prebuilt apk/GlassHome-1.0.1.apk
   ```
4. Press **Home** on your remote.

Full step-by-step instructions for both: **[docs/INSTALL-GUIDE.md](docs/INSTALL-GUIDE.md)**
Undo anything / fix problems: **[docs/UNDO-AND-TROUBLESHOOTING.md](docs/UNDO-AND-TROUBLESHOOTING.md)**
Personalise it: **[docs/CUSTOMISE.md](docs/CUSTOMISE.md)**

---

## Compatibility

- **Developed and tested on:** TCL Google TV, Android TV 14, MediaTek MT9689 platform, build AU02
- **Expected to work on:** other TCL Google TV models running Android 12–14. Package names can
  differ between models; the scripts simply skip anything that is not present on your TV.
- **Computer:** a Mac (Apple Silicon or Intel) **or** a Windows 10/11 PC. Linux works too.
  The TV and computer must be on the same network.

| | macOS | Windows |
|---|---|---|
| Connect, back up, clean up, install the ready-made app | ✓ | ✓ (Git Bash) |
| Build the app from source | ✓ | ✓ inside WSL (Ubuntu) |
| Continue Watching check, 4K tour recording | ✓ | ✓ |
| AirPlay tuning tools | ✓ | — (AirPlay is for Apple devices) |

## Safety

- No root, no bootloader unlock, no firmware flashing, no system partition changes.
- Every change is recorded in `scripts/99_restore_all.sh` (created on your computer as you go).
  Running it puts everything back the way it was.
- A factory reset also restores the TV to stock.
- Read the guide before you start, and keep your remote down while scripts are running.

## Privacy

Everything runs locally between your computer and your TV. Continue Watching is built from what your
apps report as "now playing"; it is stored only on the TV, in the launcher's private storage.
Logs and backups are saved on your computer, inside this folder, and are never uploaded anywhere.

## Folder contents

| Path | What it is |
|---|---|
| `apk/` | Ready-made Glass Home app, signed by Basel Majid |
| `scripts/` | Step-by-step scripts (connect, back up, clean up, install, extras) |
| `home-launcher/` | Full source code of the launcher |
| `docs/` | Install guide, undo and troubleshooting, customisation |

---

Created by **Basel Majid**. Shared under the MIT License — free to use, modify and pass on, with
this notice kept. Provided as-is, without warranty; you use it at your own risk. Not affiliated with
TCL, Google or Apple. All trademarks belong to their owners.
