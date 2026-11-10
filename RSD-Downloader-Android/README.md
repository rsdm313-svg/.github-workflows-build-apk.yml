# RSD Downloader — Android

Phone version of RSD Downloader: paste or **share** a link → download video/audio → saved to your gallery / music.
Uses the icon you supplied (adaptive icon + round + legacy sizes included).

## 1. Build the APK (pick one)

**A. Android Studio (most reliable)**
1. Install Android Studio → https://developer.android.com/studio
2. *File ▸ Open…* → choose this folder → wait for "Gradle sync" to finish.
3. *Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)* → click **locate** in the popup.
   The file is `app/build/outputs/apk/debug/app-debug.apk`.

**B. One-click on a Mac** — install Android Studio once (open it once to finish setup), then double-click
**`Build-APK.command`** (right-click ▸ Open if macOS blocks it). It produces `RSD-Downloader-1.5.6.apk`.

**C. No local setup** — upload this folder to a GitHub repo, open *Actions ▸ Build APK ▸ Run workflow*,
then download **RSD-Downloader-apk** from the finished run.

## 2. Install on the phone
Copy the `.apk` to the phone (USB, Google Drive, email…), tap it, allow *Install unknown apps* for the app you
opened it from. If Play Protect warns about an unrecognized developer → *Install anyway*
(the APK is signed with Android's debug key, which is fine for personal use).
Or from a computer: `adb install RSD-Downloader-1.5.6.apk`.

## Phone components (what was added for downloading)

| Component | Purpose |
|---|---|
| yt-dlp + Python + FFmpeg (`youtubedl-android` 0.18.1) | the downloader itself — bundled in the APK, nothing else to install |
| **UPDATE YT-DLP** button | updates yt-dlp inside the app when a site changes |
| Share target | in YouTube / Instagram / Facebook / X / Pinterest tap **Share ▸ RSD Downloader** → link is filled in |
| Clipboard: paste button + auto-paste | copy a link, open the app, it's pasted (toggle under the link field) |
| Foreground service + notification | download keeps running with the screen off / app in background; progress + **Cancel** in the notification |
| MediaStore saving | video → `Movies/RSD Downloader`, audio → `Music/RSD Downloader` (shows in Gallery / Files / music apps) |
| **Open** / **Share** buttons | open or share the finished file straight from the app |
| Permissions | notifications (Android 13+), storage only on Android 7–9 |

## Differences from the Mac/Premiere version
- No Premiere import and no "convert for Premiere" step. Instead video is requested as **H.264 + AAC MP4**
  where the site offers it, so files play everywhere without re-encoding.
- "Selected Area": cuts start at the nearest keyframe (no re-encode on a phone).
- No Facebook "browser cookies" option (phones have no browser profile to read) → public videos only.
- Save folder is fixed (Movies / Music) instead of a folder picker.

## Notes
- Sites change often. If a link fails (YouTube especially), tap **UPDATE YT-DLP** and try again.
- APK size is ~100 MB because Python and FFmpeg are bundled (arm64 + armv7 phones; no x86 emulator).
- The downloader library and FFmpeg build are GPL-licensed; fine for personal use, but if you distribute the APK
  publicly you need to publish the source under a GPL-compatible license.

## Project layout
`app/src/main/java/com/rsd/downloader/` — `Engine.kt` (all yt-dlp calls), `DownloadService.kt`, `MainActivity.kt`, `App.kt` ·
`app/src/main/assets/www/` — the UI (`index.html`, `app.js`) · `branding/` — icon source files.
