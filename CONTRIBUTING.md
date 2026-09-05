# Contributing to GHOST Protocol

Thank you for your interest in contributing to the **GHOST Protocol** (Global Hybrid Offline Secure Transport). 

GHOST is an offline, cryptographically secure mesh messaging platform engineered for infrastructure-denied environments: internet blackouts, natural disasters, active protests, and remote operations. The codebase comprises Kotlin (Android app, Compose UI, Room DB), Go (delay-tolerant Spray-and-Wait router, BoltDB, simulation harness), and Rust (cryptographic core primitives).

---

## 1. Core Engineering Principles

1. **Physical Reality Over Marketing Claims:** We never make unsubstantiated claims about stealth, range, or security. GHOST protects message content and sender integrity; it cannot defeat physical RF direction-finding if radios are actively transmitting.
2. **Zero Central Dependencies:** The production application requires zero internet access, zero Google Play Services, zero centralized trackers, and zero accounts.
3. **Strict Invariant Enforcement:** All changes must respect established protocol invariants:
   - Wire formats and opcodes (`0x01`, `0x10`/`0x11`, `0x20`–`0x23`, `0x30`/`0x31`, `0x40`, `0x50`) are strictly immutable without a formal RFC revision.
   - Cryptographic primitives (X25519, Ed25519, AES-256-GCM) must remain pure and audited.
   - Spray-and-Wait parameters ($L=4, \text{MaxHops}=10, \text{TTL}=24\text{h}$) are fixed bounds.
4. **4-Stage Verification Contract:** Any routing or state change must pass the 120,000-scenario adversarial verification suite with 0 invariant violations and 0 data races.

---

## 2. Repository Layout

- `android/`: Android application, Jetpack Compose UI, Room persistence, and BLE service.
- `go/ghostrouter/`: Spray-and-Wait routing engine, BoltDB carrier store, SQLite deduplication, and the 4-stage simulation suite (`sim/`).
- `rust/`: Pure Rust cryptographic core (`rust/ghost-crypto/`) and JNI bindings.
- `docs/`: Comprehensive technical specifications, RFCs, threat models, and audit reports.
- `scripts/`: Development and compilation scripts.

---

## 3. Development Workflow

### Prerequisites
- **Go:** 1.22+
- **Rust:** 1.75+ with `cargo-ndk`
- **Android SDK & NDK:** API Level 34 (Android 14), NDK 27.x
- **JDK:** OpenJDK 17 or 21 (or Android Studio JBR)

### Building & Testing

#### Go Subsystem & Simulation Suite
```bash
cd go/ghostrouter

# Run all unit and race tests
CGO_ENABLED=0 go test -v ./...

# Build the simulator CLI
go build -o bin/ghost-sim ./cmd/ghost-sim

# Execute verification stages
./bin/ghost-sim run --nodes 20 --scenario 04
./bin/ghost-sim torture --scenarios 1000 --seed 42 --workers 8
./bin/ghost-sim ux --scenarios 1000 --seed 42 --workers 8
./bin/ghost-sim oem --scenarios 10000 --seed 42 --workers 8
```

#### Rust Cryptographic Core
```bash
cd rust/ghost-crypto

# Run native host test suite
cargo test
cargo check
```

#### Android Application
```bash
# Build debug APK
./gradlew assembleDebug

# Run JVM unit tests
./gradlew testDebugUnitTest
```

---

## 4. Pull Request Guidelines

1. **Focused Scopes:** Keep PRs focused on a single responsibility. Do not bundle unrelated refactorings or style changes.
2. **Deterministic Tests:** Include automated unit tests or simulation scenarios for new features or bug fixes.
3. **Documentation:** Update relevant RFCs, API references, or architecture documentation in `docs/` alongside code changes.
4. **Branch Naming:**
   - `feature/<description>`
   - `fix/<issue-description>`
   - `audit/<scope>`
   - `docs/<topic>`

---

## 5. Security & Vulnerability Reporting

If you discover a potential cryptographic weakness, memory safety issue, or security flaw, **DO NOT file a public issue**. Follow the responsible disclosure guidelines documented in [SECURITY.md](SECURITY.md).
