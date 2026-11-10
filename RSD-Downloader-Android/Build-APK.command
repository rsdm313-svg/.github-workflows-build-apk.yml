#!/bin/bash
# Double-click on a Mac to build RSD-Downloader.apk
# Needs: Android Studio installed once (gives us Java + the Android SDK).
set -e
cd "$(dirname "$0")"
echo "=== RSD Downloader — building the Android APK ==="

# 1) Java 17+
STUDIO="/Applications/Android Studio.app"
if [ -x "$STUDIO/Contents/jbr/Contents/Home/bin/java" ]; then
  export JAVA_HOME="$STUDIO/Contents/jbr/Contents/Home"
elif /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
else
  echo "Android Studio (or Java 17) is required. Opening the download page…"
  open "https://developer.android.com/studio"
  echo "Install it, open it once so it sets up the SDK, then run this file again."
  read -n 1 -s -r -p "Press any key to close…"; exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

# 2) Android SDK
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [ ! -d "$SDK" ]; then
  echo "Android SDK not found at $SDK."
  echo "Open Android Studio once and finish its setup wizard, then run this file again."
  read -n 1 -s -r -p "Press any key to close…"; exit 1
fi
echo "sdk.dir=$SDK" > local.properties
SDKMGR="$SDK/cmdline-tools/latest/bin/sdkmanager"
[ -x "$SDKMGR" ] && yes | "$SDKMGR" --licenses >/dev/null 2>&1 || true

# 3) Gradle 8.9 (downloaded once into ~/.rsd-build)
GRADLE="$HOME/.rsd-build/gradle-8.9/bin/gradle"
if [ ! -x "$GRADLE" ]; then
  echo "→ Downloading Gradle 8.9 (one time)…"
  mkdir -p "$HOME/.rsd-build"
  curl -fL --progress-bar -o /tmp/gradle-8.9-bin.zip "https://services.gradle.org/distributions/gradle-8.9-bin.zip"
  unzip -q -o /tmp/gradle-8.9-bin.zip -d "$HOME/.rsd-build"
fi

echo "→ Building (first build downloads ~500 MB of Android tools and takes several minutes)…"
"$GRADLE" assembleDebug --no-daemon

APK="app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "Build failed: APK not found."; read -n 1 -s -r -p "Press any key…"; exit 1; }
OUT="RSD-Downloader-1.5.6.apk"
cp "$APK" "$OUT"
echo
echo "✓ Done: $OUT"
open -R "$OUT"
read -n 1 -s -r -p "Press any key to close…"
