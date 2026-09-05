# GHOST Android Subsystem

> **Directory:** `android/`  
> **Target SDK:** API 34 (Android 14)  
> **Minimum SDK:** API 26 (Android 8.0)  
> **Language:** Kotlin 2.0.20 / Jetpack Compose  
> **Dependencies:** Pure AOSP (Zero Google Play Services)

---

## 1. Purpose & Responsibilities

The `android/` subsystem contains the entire user-facing Android application, background mesh services, and operating system integration. It is responsible for:
- **Presentation Layer (`ui/`):** Jetpack Compose interface with single-activity navigation, optimistic bubble dispatch via `ChatViewModel`, and 60 FPS scrolling passes.
- **Data & Persistence (`data/`):** Room Database v9 (SQLite WAL mode) managing messages, direct contacts, 8-member Cell Groups, and atomic status transition guards (`updateStatusIfPending`).
- **Foreground Mesh Service (`GhostService.kt`):** `START_STICKY` service holding a `PARTIAL_WAKE_LOCK` for continuous BLE scanning, advertising, and delay-tolerant message handling.
- **BLE GATT Coordination (`ble/`):** Mutex-free `GattOperationQueue` enforcing single-connection FIFO serialization and 5s timeouts to prevent Android `133 (GATT_ERROR)` faults. Dynamic MTU transport framing (`0xFB`) and bounded reassembly for low-end device compatibility.
- **Power Management (`power/`):** `PowerPolicyEngine` driving 4 dynamic duty-cycle postures (`ACTIVE`, `ECO`, `CRITICAL`, `DEEP_SLEEP`) and automatic relay load shedding.
- **Native Bridges:** JNI interface to `libghost_crypto.so` (`GhostCrypto.kt`) and gomobile interface to `ghostrouter.aar` (`GhostRouter.kt`).

---

## 2. What Belongs Here

- All Android-specific source code (`app/src/main/java/com/ghostprotocol/...`).
- Android application resources (strings, vector drawables, layouts, launcher mipmaps, BIP-39 wordlist).
- Android JVM unit tests (`app/src/test/java/...`).
- Prebuilt native libraries (`app/libs/ghostrouter.aar`).
- Android Gradle configuration (`app/build.gradle.kts`, ProGuard rules).

---

## 3. What Does NOT Belong Here

- Core Go routing algorithms or BoltDB store implementations (belongs in `go/ghostrouter/`).
- Rust cryptographic implementations or Cargo configs (belongs in `rust/ghost-crypto/`).
- Pure simulation logic or virtual-time test generators (belongs in `go/ghostrouter/sim/`).
- Generated APK or build outputs (automatically ignored via `.gitignore`).

---

## 4. Building & Testing

### Building the Debug APK
```bash
# From repository root
./gradlew assembleDebug

# Output APK:
# android/app/build/outputs/apk/debug/app-debug.apk
```

### Running JVM Unit Tests
```bash
./gradlew testDebugUnitTest
```

### Verified Test Suites:
- `BleReliabilityTest.kt` — GATT queue serialization, late callback dropping, MTU 23/185/247/512 slicing, out-of-order and duplicate fragment reassembly.
- `MessageStatusTransitionTest.kt` — Monotonic status progression ($U_2, U_{15}, O_6$).
- `GroupProtocolTest.kt` — Cell Group 0x30 envelopes, 0x31 invites, self-healing META.
- `IntroductionProtocolTest.kt` — 0x50 Introduction envelope generation and verification.
- `DeliveryReceiptProtocolTest.kt` — 0x40 Signed delivery receipt generation.
- `PowerPolicyTest.kt` — Dynamic power state transitions and relay load shedding.
- `ShortCodeTest.kt` — Deterministic HMAC-SHA256 BIP-39 short code derivation.
