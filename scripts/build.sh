#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "================================================================================"
echo "GHOST Protocol — Monorepo Build"
echo "================================================================================"

echo ""
echo "=== 1. Building Go Mesh Router Packages & CLI ==="
cd "$PROJECT_ROOT/go/ghostrouter"
go build ./...
go build -o bin/ghost-sim ./cmd/ghost-sim
echo "Built ghost-sim CLI: $PROJECT_ROOT/go/ghostrouter/bin/ghost-sim"

echo ""
echo "=== 2. Building Rust Cryptographic Core ==="
cd "$PROJECT_ROOT/rust"
cargo check --workspace

echo ""
echo "=== 3. Assembling Android APK ==="
cd "$PROJECT_ROOT"
if [ -n "${JAVA_HOME:-}" ] && [ -d "$JAVA_HOME" ]; then
    ./gradlew assembleDebug --no-daemon || echo "WARNING: Gradle build requires Android SDK/JDK configured."
else
    echo "NOTE: Skipping gradlew assembleDebug (JAVA_HOME not configured)."
fi

echo ""
echo "================================================================================"
echo "Build complete."
echo "================================================================================"
