#!/bin/bash
set -e

APK_PATH="android/app/build/outputs/apk/debug/app-debug.apk"

echo "Installing GHOST Protocol APK to connected device..."
adb install -r $APK_PATH

echo "Launching app..."
adb shell am start -n com.ghostprotocol/.MainActivity

echo "Done."
