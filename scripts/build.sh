#!/bin/bash
set -e

echo "Building GHOST Protocol..."

echo "1. Building Rust workspace..."
# cd rust && cargo build --release

echo "2. Building Rust for Android targets..."
# cargo build --target aarch64-linux-android --release
# cargo build --target armv7-linux-androideabi --release

echo "3. Building Go packages..."
cd go
go build ./...
cd ..

echo "4. Building Go for Android via gomobile..."
# gomobile bind -target=android ./go/...

echo "5. Assembling Android APK..."
cd android
./gradlew assembleDebug
cd ..

echo "Build complete."
