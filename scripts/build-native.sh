#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

export ANDROID_HOME="${ANDROID_HOME:-/home/sanro/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
export JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}"
export GOPATH="${GOPATH:-/home/sanro/go}"
export PATH="$PATH:$GOPATH/bin"

echo "=== Building Rust native libs ==="
cd "$PROJECT_ROOT/rust"
cargo ndk -t arm64-v8a -t x86_64 \
    -o "$PROJECT_ROOT/android/app/src/main/jniLibs" build --release -p ghost-crypto

echo "=== Building Go mesh router AAR ==="
cd "$PROJECT_ROOT/go/ghostrouter"
gomobile bind -target android/arm64,android/amd64 -androidapi 26 \
    -o "$PROJECT_ROOT/android/app/libs/ghostrouter.aar" .

echo "=== Building Android APK ==="
cd "$PROJECT_ROOT"
./gradlew assembleDebug --no-daemon

echo "=== Done ==="
echo "APK: $PROJECT_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
