# Customise

**Glass Home for TCL Google TV — by Basel Majid**

## On the TV (no code)

- **Favourites:** hold OK on any tile → *Add to favourites*, *Move left/right*, *Remove from favourites*.
- **Hide apps:** hold OK → *Hide*. Bring them back: hold OK on **Settings** → *Show hidden apps*.
- **Wallpaper:** the streaks switch at the top right turns it on/off. The choice is remembered.
- **Focus View:** the ring switch at the top right, or Back to leave. The choice is remembered.

## In the source (then build with Option B of the install guide — macOS, Linux or WSL)

| What | Where |
|---|---|
| Default favourites for a fresh install | `DEFAULT_FAVS` in `home-launcher/app/src/com/basel/tvhome/HomeActivity.java` |
| Apps shown in Continue Watching | `RESUME_APPS` in the same file |
| Apps hidden by default | `DEFAULT_HIDDEN` / `DEFAULT_HIDDEN_LABELS` in the same file |
| Wallpaper image | replace `home-launcher/app/assets/wallpaper.jpg` (a 1920×1080 JPG works best; use an image you have the right to use) |
| Wallpaper dimming | `composeWallpaper()` in `Backdrop.java` (`dim.setScale(0.36f, …)`) |
| Background gradient | `buildMatte()` in `Backdrop.java` |
| Glass look (refraction, dispersion, rim light) | `Glass.java` (panels), `GlassTile.java` (tiles), `GlassClock.java` (Focus View clock) |
| Clock size | `GlassClock` text size in `HomeActivity.java` (`100` sp) |

The app ID is `com.basel.tvhome`. If you publish your own modified version, please change the app ID
and the name, and keep the copyright notice as required by the MIT License.
