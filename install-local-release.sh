#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

ADB="${ADB:-}"
if [ -z "$ADB" ]; then
    if [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then
        ADB="$HOME/Android/Sdk/platform-tools/adb"
    elif [ -x "/Users/brett/Library/Android/sdk/platform-tools/adb" ]; then
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

echo "==> Ensuring wireless adb is running and finding device..."
if ! "$ADB" mdns check >/dev/null 2>&1; then
    "$ADB" kill-server >/dev/null 2>&1 || true
fi
"$ADB" start-server

TARGET_DEVICE="${ANDROID_SERIAL:-}"
if [ -z "$TARGET_DEVICE" ]; then
    for i in $(seq 1 15); do
        TARGET_DEVICE=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')
        if [ -n "$TARGET_DEVICE" ]; then
            break
        fi
        sleep 1
    done
else
    for i in $(seq 1 15); do
        if "$ADB" -s "$TARGET_DEVICE" get-state >/dev/null 2>&1; then
            break
        fi
        sleep 1
    done
fi

if [ -z "$TARGET_DEVICE" ]; then
    echo "Error: No connected Android device found." >&2
    echo "Please ensure Wireless Debugging is enabled on your phone and it is connected to Wi-Fi." >&2
    "$ADB" devices -l >&2
    exit 1
fi

DEVICE_MODEL=$("$ADB" -s "$TARGET_DEVICE" shell getprop ro.product.model 2>/dev/null || echo "$TARGET_DEVICE")
echo "==> Found device: $DEVICE_MODEL ($TARGET_DEVICE)"

echo "==> Building signed release APK..."
./gradlew :app:assemblePlayRelease

APK="$SCRIPT_DIR/app/build/outputs/apk/play/release/app-play-release.apk"
if [ ! -f "$APK" ]; then
    echo "Error: APK not found at $APK" >&2
    exit 1
fi

echo "==> Installing release APK to personal profile (user 0)..."
"$ADB" -s "$TARGET_DEVICE" install -r --user 0 "$APK"

echo "==> Disconnecting wireless adb session..."
"$ADB" disconnect >/dev/null 2>&1 || true
"$ADB" kill-server >/dev/null 2>&1 || true

echo "==> Successfully installed personal release!"
