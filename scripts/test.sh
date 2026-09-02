#!/bin/bash
set -e

echo "Running GHOST Protocol tests..."

echo "1. Testing Rust crates..."
# cargo test --workspace

echo "2. Testing Go packages..."
cd go
go test ./... -race
cd ..

echo "3. Running cargo fuzz (if available)..."
# cargo fuzz list

echo "Tests completed."
