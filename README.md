# tv-home

**Make a slow, ad-filled Android TV fast and clean again.** Debloat it over Wi-Fi and give it a
tiny Apple TV-style home screen with rotating nature wallpapers. You can hand the whole thing to
Claude Code with one prompt, or do it by hand with the scripts here.

![Before: Google TV home with a sponsored hero. After: clean dock over a Lake Louise wallpaper](before-after.png)

On a 2 GB TCL Google TV this disabled 25 bloat apps and cut the home screen's memory use from
192 MB to 83 MB. Everything is reversible: packages are disabled, never uninstalled.

## Do it with Claude Code (easiest)

1. On the TV: Settings → System → About → press OK on **Build** 7 times → back → Developer options
   → turn on **USB debugging**. Keep the TV on, on the same Wi-Fi as your computer.
2. Clone this repo, open Claude Code inside it (so it can reuse the launcher and scripts), and paste:

   ```text
   My Android TV is slow and full of ads. Find it on my Wi-Fi, connect over ADB, and debloat it:
   disable the manufacturer's ads, telemetry and junk (reversibly, never uninstall), asking me first
   about apps and features I might use. Then build and install a fast, minimal Apple TV-style home
   screen with a dock of my favourite apps and rotating nature wallpapers. Back up before changing
   anything, check every step with screenshots from the TV, and give me a restore script.

   Heads-up from people who've done this: use the adb-shell Python package if macOS blocks adb; on
   Google TV also disable com.google.android.tungsten.setupwraith or it hijacks the Home button; and
   test the Home button from inside an app.
   ```

3. Accept "Allow USB debugging?" on the TV when asked, then answer a few questions: which apps you
   use, which go in the dock, whether you want wallpapers.

The prompt is short on purpose; Claude works out the details. The gotchas below are there in case it gets stuck.

## Do it by hand (macOS)

```bash
./setup.sh                                                  # Python ADB client, JDK 17, Android SDK
tools/find-tv.sh                                            # finds the TV, saves its IP
tools/venv/bin/python -I tools/tv.py --probe                # model, Android version
tools/disable.sh examples/tcl-google-tv-g07/debloat.txt     # or your own list, see examples/
launcher/build-and-install.sh                               # build + install the launcher
```

Undo everything with `tools/restore.sh <the same list>`.

## What's here

| Path | What it is |
|---|---|
| [PROMPT.md](PROMPT.md) | The prompt for Claude Code / any coding agent |
| [launcher/](launcher/) | The home screen. 4.7 MB including the wallpapers; plain Android, no background services. Network only for the optional Spotify widget |
| [launcher/res/values/config.xml](launcher/res/values/config.xml) | Default dock apps, hidden apps, banner overrides, Live TV app, grid columns |
| [tools/tv.py](tools/tv.py) | Pure-Python ADB client: shell, batch, screenshots, chunked APK push |
| [tools/find-tv.sh](tools/find-tv.sh) | Finds Android TVs on your Wi-Fi |
| [tools/type.sh](tools/type.sh) | Type into the TV's on-screen keyboard from your laptop (`tools/type.sh` alone prompts hidden, for passwords) |
| [tools/disable.sh](tools/disable.sh), [tools/restore.sh](tools/restore.sh) | Apply / undo a debloat list |
| [tools/add-wallpaper.sh](tools/add-wallpaper.sh) | Send your own photos (JPG/PNG/HEIC) to the TV as wallpapers, converted to sRGB |
| [tools/spotify-connect.py](tools/spotify-connect.py) | One-time Spotify login for the now-playing widget |
| [restore-my-tv.sh](restore-my-tv.sh) | Undo everything on the Xiaomi MiTV this fork was set up on |
| [examples/](examples/) | Debloat lists per TV model |

## The launcher

- Minimal, Apple TV-like: a frosted dock of your favourite apps, a small cluster top-right with
  **Sources** (HDMI/AV/Live TV; the Menu key opens it too), **Fond d'écran**, **Réglages** and the time.
- Two frosted widgets top-left: a world clock (Tahiti; change `WORLD_TZ` / `WORLD_CITY` in
  `MainActivity.java`) and a Spotify widget showing the current or last played track as a disc that
  spins while playing. Select it with the remote and press OK to open Spotify.
- **Edit the dock from the remote**: long-press OK or press Menu on a tile to move it left/right,
  remove it, or add an app from the grid (9 max). Saved on the TV; `config.xml` is only the default.
- **Wallpapers**: 12 nature photos crossfade every 10 minutes. The Fond d'écran picker shows
  thumbnails, switches instantly and turns rotation on or off. Your own photos go to
  `/sdcard/Pictures/TVHome` with `tools/add-wallpaper.sh` (credits for the built-in ones in
  `launcher/WALLPAPER-CREDITS.md`).
- `banner_from` in `config.xml` gives a tile another app's banner (TizenTube shows YouTube's).
- Deterministic D-pad focus, remembers the last tile, picks up newly installed apps on return.

Spotify widget: create an app at https://developer.spotify.com/dashboard (redirect URI
`http://127.0.0.1:8888/callback`, Web API), then `python3 tools/spotify-connect.py <id> <secret>`.

The build script defaults to the Android TV launcher (`com.google.android.tvlauncher`). On Google TV
run it as `STOCK_LAUNCHER=com.google.android.apps.tv.launcherx launcher/build-and-install.sh`.

## Gotchas we hit

- **macOS blocks the `adb` binary** on the local network ("No route to host"). `tools/tv.py` uses
  the `adb-shell` Python package instead.
- **Google TV hijacks the Home button.** With the stock launcher disabled, the setup app
  (`com.google.android.tungsten.setupwraith`) has a higher HOME priority than any third-party
  launcher. The install script disables it; `restore.sh` re-enables it.
- **Home opened the launcher behind the current app**, because of a stale home task from switching
  launchers. Force-stopping the launcher and `com.android.tv.settings` and pressing Home fixes it.
  The install script does this.
- **Chained `pm` commands** (`pm disable a; pm disable b`) can silently stop after the first. The
  scripts run one per shell call.
- **Apps installed from the Play Store didn't appear**, because the package broadcast was missed
  while another app was in front. The launcher re-checks with `getChangedPackages` on every return.
- **Unsplash blocks scripted downloads** with a bot check. The wallpapers come from Wikimedia
  Commons Featured Pictures instead.
- **Network calls failed with `EPERM`** after adding the INTERNET permission in an update (Android 10).
  The permission shows as granted, but netd only picks it up after a reboot of the TV.
- **Wallpapers looked washed out and banded**: decoding as `RGB_565` and a heavy scrim. They're now
  decoded in full colour (sRGB) into a 32-bit window with a light, dithered scrim.

## Supported TVs

Android TV and Google TV: TCL, Xiaomi/Redmi, Hisense, Sony, OnePlus, Realme, Nvidia Shield,
Chromecast with Google TV and most others. Not Samsung (Tizen), LG (webOS) or Fire TV.
Tested on: TCL Google TV (Android 11), Xiaomi Mi TV MiTV-MOOQ1 (Android TV 10). Add yours to `examples/`.

## Credits

Inspired by [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack). Wallpaper photos are
from Wikimedia Commons under their own licences, see
[launcher/WALLPAPER-CREDITS.md](launcher/WALLPAPER-CREDITS.md). Code is MIT, see [LICENSE](LICENSE).
