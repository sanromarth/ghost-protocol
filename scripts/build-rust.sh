#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
RUST_DIR="$PROJECT_ROOT/rust"
JNI_LIBS_DIR="$PROJECT_ROOT/android/app/src/main/jniLibs"

echo "=== Building ghost-crypto for Android ==="

# Check prerequisites
command -v cargo-ndk >/dev/null 2>&1 || { echo "ERROR: cargo-ndk not found. Install: cargo install cargo-ndk"; exit 1; }

# Build for Android targets
cd "$RUST_DIR"
cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -o "$JNI_LIBS_DIR" \
  build --release -p ghost-crypto

echo "=== Done. .so files written to $JNI_LIBS_DIR ==="
ls -lhR "$JNI_LIBS_DIR"
