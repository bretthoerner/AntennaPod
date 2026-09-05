#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

ADB="${ADB:-}"
if [ -z "$ADB" ]; then
    if [ -x "/Users/brett/Library/Android/sdk/platform-tools/adb" ]; then
        ADB="/Users/brett/Library/Android/sdk/platform-tools/adb"
    elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
        ADB="$ANDROID_HOME/platform-tools/adb"
    elif command -v adb >/dev/null 2>&1; then
        ADB="$(command -v adb)"
    else
        echo "Error: adb not found" >&2
        exit 1
    fi
fi

echo "==> Building signed release APK..."
./gradlew :app:assemblePlayRelease

APK="$SCRIPT_DIR/app/build/outputs/apk/play/release/app-play-release.apk"
if [ ! -f "$APK" ]; then
    echo "Error: APK not found at $APK" >&2
    exit 1
fi

echo "==> Installing release APK to personal profile (user 0)..."
"$ADB" install -r --user 0 "$APK"

echo "==> Successfully installed personal release!"
