# GHOST Protocol — v0.1 Implementation Status

> **Last updated:** 2026-09-02
> **Version:** v0.1.5
> **Build:** `app-debug.apk` (46 MB)
> **Tested on:** 2× Android phones (Android 12+), direct BLE range
> **Status:** ✅ Fully working — bidirectional encrypted messaging through Go router verified on hardware

This document describes what the GHOST Protocol v0.1 **actually does** — not what the whitepaper envisions. For the full protocol vision, see `whitepaper.md`. For future layer specs, see `docs/rfc/`.

---

## 1. What v0.1 Is

A minimal offline mesh messenger for Android. Two phones discover each other over BLE, exchange Ed25519 keys via QR code, and send end-to-end encrypted text messages routed through a Go spray-and-wait router. Messages can hop through intermediate phones when sender and receiver are not in direct BLE range. No servers, no internet, no accounts.

**Languages:** Kotlin (UI + BLE), Rust (crypto via JNI), Go (routing via gomobile)
**Lines of code:** ~5,500 across all three languages

---

## 2. What Works (Tested on Real Hardware)

### Core Messaging
- ✅ **BLE 5.0 discovery and advertisement** — connectable advertising with 4-byte fingerprint in scan response, tested on Android 12+
- ✅ **QR code contact exchange** — both directions, encodes Ed25519 + X25519 public keys + display name
- ✅ **Ed25519/X25519 key generation** — via Rust `ghost-crypto` crate, loaded as `libghost_crypto.so` through JNI
- ✅ **AES-256-GCM encryption + Ed25519 signatures** — every message encrypted with recipient's X25519 pubkey, signed with sender's Ed25519 key
- ✅ **Direct BLE GATT messaging** — connect → MTU 512 → discover services → write characteristic → disconnect
- ✅ **Persistent message history** — Room database, survives app restart
- ✅ **Background message processing** — incoming messages decrypted and saved even when chat screen is closed

### Go Mesh Router (Fully Wired + Verified)
- ✅ **Spray-and-wait routing** — binary spray algorithm (copies=4, max hops=10, TTL=24h)
- ✅ **BoltDB message persistence** — queued messages survive app restart
- ✅ **Router send path** — messages routed through Go router before BLE transmission
- ✅ **Router receive path** — incoming BLE data decoded by router, delivered or forwarded
- ✅ **Peer discovery trigger** — router notified on BLE peer match, delivers queued messages + sprays copies
- ✅ **Direct delivery** — peer seen <60s ago → send immediately via BLE
- ✅ **Queued delivery** — peer not seen → store in BoltDB → spray when carrier discovered
- ✅ **Expiry janitor** — background goroutine deletes expired messages every 60s
- ✅ **Stale peer cleanup** — removes peers not seen in 7 days
- ✅ **Database pruning** — auto-prune when BoltDB exceeds 50MB
- ✅ **13 unit tests passing** — store, serializer, routing, spray, dedup, hop limit, TTL

### UI + Polish
- ✅ **Custom username** — set at first launch, stored in SharedPreferences, embedded in QR code
- ✅ **Deterministic avatars** — color + initial derived from SHA-256 of public key
- ✅ **Short handles** — `#c7efcb` format (first 6 hex chars of SHA-256(ed25519_pubkey))
- ✅ **Message timestamps** — relative display ("now", "2m", "1h", "3d")
- ✅ **Delivery status icons** — ⏳ pending, ✓ sent, 📡 sprayed (queued for relay), ⚠ failed
- ✅ **Contact search** — filter by name or handle
- ✅ **Settings screen** — identity, QR display, battery debug, delete all data
- ✅ **Foreground service** — persistent notification with "Quit GHOST" button
- ✅ **Peer fingerprint matching** — BLE-discovered peers matched to Room contacts via SHA-256 fingerprint
- ✅ **Auto-retry** — pending messages sent when matched contact comes into BLE range

### Premium UI (v0.1.4)
- ✅ **Design system (GhostTheme)** — Purple gradient brand colors, Surface0-3 dark palette, spacing grid, radii, component sizes
- ✅ **Premium message bubbles** — Purple gradient (sent), dark gray (received), grouped corners, slide-in animation, pulse animation for 📡 sprayed
- ✅ **Message grouping** — Consecutive messages from same sender merge bubble corners (<60s gap). Time headers inserted for 5+ minute gaps
- ✅ **Contact list rebrand** — "GHOST" 28sp branding header, pill search bar, stacked FABs, premium empty state with ghost emoji
- ✅ **Edge-to-edge display** — Immersive dark theme with slide + fade navigation transitions
- ✅ **Swipe gestures (Phase 2)** — Swipe right to reply (purple arrow), swipe left for copy/delete. Spring animation, 100dp threshold, haptic feedback
- ✅ **Reply bar (Phase 2)** — Quoted message preview above input: purple accent bar, sender name, truncated text, cancel X
- ✅ **Bottom sheets (Phase 2)** — Long-press message → Reply/Copy/Delete sheet. Tap chat avatar → Contact info sheet (avatar, QR, clear chat, delete)
- ✅ **Online indicator** — Green dot = peer seen via BLE in last 60s. Gray dot = peer seen before but not currently in range
- ✅ **Username sync** — Sender embeds display name in encrypted payload. Receiver auto-updates contact name on change

### Background Resilience (v0.1.4)
- ✅ **WakeLock** — PARTIAL_WAKE_LOCK keeps CPU alive during BLE scan/advertisement
- ✅ **Battery optimization exclusion** — Prompts user to disable battery optimization at first launch
- ✅ **Auto-restart on task removal** — AlarmManager restarts service within 1s if swiped from recents

### Security Hardening (v0.1.5)
v0.1.5 went through 10 rounds of bug hunting. Here's what got fixed:
- **Audit Results**: 73 bugs found, 67 fixed, 0 false positives.
- **Go Router**: Fixed severe deadlocks (mutex held across JNI callbacks), direct-send messages being incorrectly re-sprayed across the mesh, spray-to-sender relay loops in `OnPeerDiscovered`, message ID collisions on rapid sends (resolved with `crypto/rand` nonces), unsafe slice bounds in log statements (12 locations fixed), BoltDB 1-nanosecond timeout lock contention, BoltDB `PruneIfNeeded` catastrophic wipeouts, serializer integer overflows, double-stop panics (fixed with `sync.Once`), and `OnDeliver` passing dst instead of src.
- **BLE Transport**: Fixed GATT server responding `SUCCESS` on dropped messages, added GATT client 10-second connection timeouts, fixed concurrent GATT connections causing error 133, and fixed BLE scan fingerprint erasure.
- **Kotlin App & UI**: Added `POST_NOTIFICATIONS` runtime permission for Android 13+, fixed permission denial blocking the entire BLE service, added Room database indexes for query performance, fixed `SwipeableMessage` stale lambda capture in `LazyColumn`, reduced aggressive dedup window from 30s to 5s, fixed `IdentityManager` `SharedPreferences` corruption crashes, and fixed direct-send messages being silently lost when `bleAddress` was null.
- **Rust Crypto**: Fixed `unwrap` panics across the FFI boundary and added proper input validation.

---

## 3. Performance (Measured)

| Metric | Value | Notes |
|---|---|---|
| APK size (debug) | 46 MB | Includes Rust .so (arm64 + x86_64) + Go .aar (arm64 + x86_64). Release with R8 would be smaller. |
| Cold start time | ~1.5s | Measured on-device via `ActivityTaskManager: Displayed` logcat |
| BLE message latency | 2–4 seconds | Direct GATT write (connect→MTU→discover→write→disconnect) |
| BLE advertising packet | 21 bytes (main) + 11 bytes (scan response) | Under the 31-byte BLE legacy limit |
| Go router AAR | 4.9 MB | arm64 + x86_64 native libs |
| Rust crypto .so | ~300 KB per ABI | Ed25519 + X25519 + AES-256-GCM |
| Go unit tests | 13/13 pass in 0.009s | Store, serializer, router, spray, dedup |
| BLE range | ~10m indoors | Standard BLE 5.0, no external antenna |
| Lines of code | ~5,500 | Kotlin + Rust + Go (excluding stubs) |

---

## 4. What's Cut from the Whitepaper

| Whitepaper Claim | v0.1 Reality |
|---|---|
| 5 transport channels (BLE, ultrasonic, infrasonic, IR, WiFi Direct) | BLE only |
| Post-quantum crypto (ML-KEM-1024 + ML-DSA-65) | Classical only (X25519 + Ed25519 + AES-256-GCM) |
| Shamir (5,3) identity recovery | No recovery — keypair stored in app private dir |
| Biometric seed generation | None |
| Guardian selection algorithm | None |
| Predictive routing with INT8 TinyML | Spray-and-Wait opportunistic store-and-forward (Go/BoltDB). No dynamic route discovery. |
| Credit economy + reputation | None |
| Voice-first UI with Vosk ASR | Text-only Compose UI |
| 70% cover traffic + beacon morphing | None |
| 512-byte fixed packets | Variable-size, 512-byte max via BLE MTU negotiation |
| 0-RTT handshake under 5ms | No handshake — encrypt-and-send per message |
| 7-layer protocol stack | 3 components: crypto (Rust), routing (Go), transport+UI (Kotlin) |
| Federated learning for topology optimization | None |
| Multi-path routing with congestion control | Single-path spray-and-wait |

---

## 5. Known Limitations

- **No 3-phone relay test** — spray-and-wait is unit tested and router is verified on 2 phones, but multi-hop relay through an intermediate phone requires 3 devices
- **No delivery receipts** — single ✓ means "GATT write succeeded," not "peer received." 📡 means sprayed to carriers, but no ACK when destination receives. Requires bidirectional ACK protocol (planned for v0.2)
- **Spray-and-Wait delivery is probabilistic** — messages are replicated to encountered peers (L=4 copies). If no carrier meets the destination within TTL (24h), the message expires. There is no guaranteed delivery path.
- **Blind spraying** — v0.1 gives message copies to any encountered peer without considering encounter history. PeerInfo tracks EncounterCount and LastSeen but doesn't use this data for forwarding decisions. (Encounter-aware heuristics planned for v0.2)
- **No group chat** — 1:1 messaging only
- **Key loss = permanent data loss** — no backup, no recovery, no Shamir. Reinstalling the app generates a new identity
- **BLE range ~10m indoors** — standard BLE 5.0 limitation. No relay through WiFi Direct or other transports
- **App must remain running** — foreground service + WakeLock + auto-restart help, but Android can still kill it under extreme memory pressure or Doze mode
- **No message attachments** — text only
- **Debug APK is 46 MB** — mostly Go + Rust native libs for 2 ABIs. Release minification would reduce this
- **Database schema changes wipe data** — `fallbackToDestructiveMigration()` enabled for development speed
- **Username sync is one-way** — receiver updates contact name when a message arrives with a new name. If no messages are exchanged, the name stays stale.
- **Reply bar is UI-only** — quoted messages display in the reply bar but the actual "quoted message displayed above bubble" is deferred to v0.2

---

## 6. Architecture (v0.1 Only)

```
┌──────────────────────────────────────────────┐
│            Kotlin Android App                │
│  ┌────────────┐  ┌───────────┐  ┌──────────┐ │
│  │ Compose UI │  │  Room DB  │  │  BLE     │ │
│  │ 5 screens  │  │ Contacts  │  │ Manager  │ │
│  │            │  │ Messages  │  │ GATT S/C │ │
│  └─────┬──────┘  └─────┬─────┘  └────┬─────┘ │
│        │              │              │       │
│  ┌─────┴──────────────┴──────────────┴─────┐ │
│  │          GhostService (foreground)       │ │
│  │  • Router init + message routing         │ │
│  │  • Peer matching (fingerprint → contact) │ │
│  │  • Peer discovery → router spray/deliver │ │
│  │  • Message processing (decrypt/verify)   │ │
│  └─────┬──────────────┬────────────────────┘ │
│        │              │                      │
│  ┌─────┴──────┐ ┌─────┴──────────┐          │
│  │ Rust JNI   │ │ Go gomobile    │          │
│  │ ghost-     │ │ ghostrouter    │          │
│  │ crypto.so  │ │ .aar           │          │
│  │            │ │                │          │
│  │ Ed25519    │ │ Spray-and-wait │          │
│  │ X25519     │ │ BoltDB store   │          │
│  │ AES-256-GCM│ │ Expiry janitor │          │
│  └────────────┘ └────────────────┘          │
└──────────────────────────────────────────────┘
```

### Message Flow (Routed)

```
User taps Send
    ↓
ChatViewModel → encrypt(Rust JNI) → ciphertext
    ↓
GhostRouter.sendMessage(SHA-256(dst_pubkey), ciphertext)
    ↓
├─ Peer seen <60s? → EncodeMessage(routing header + ciphertext) → blob
│   ↓
│   BleManager.sendMessage(mac, blob) → GATT write → status = SENT ✓
│
└─ Peer not seen → save to BoltDB → status = SPRAYED 📡
    ↓
    [Later: BLE discovers a peer]
    ↓
    GhostRouter.onPeerDiscovered(peerId, rssi)
    ↓
    ├─ Message for this peer → deliver blob via BLE
    └─ Message for someone else → spray half copies via BLE

[Receiver side]
GATT server receives blob
    ↓
GhostRouter.onMessageReceived(blob)
    ↓
├─ Dst matches localID → "delivered" → DeliverHandler.onDeliver()
│   ↓
│   processRoutedPayload() → decrypt(Rust) → verify sig → save to Room → UI updates
│
└─ Dst doesn't match → "forwarded" → save to BoltDB → spray to next peer
```

### Wire Format

```
BLE GATT write payload:
[4 bytes: header length N, big-endian uint32]
[N bytes: JSON RoutingHeader {MessageID, Src, Dst, CopiesRemaining, TTLSeconds, HopCount, CreatedAt}]
[remaining: encrypted payload]

Encrypted payload (Rust):
ephemeral_x25519_pub(32) + nonce(12) + ciphertext_with_tag

Plaintext before encryption:
sender_ed25519_pub(32) + message_utf8 + ed25519_signature(64)
```

---

## 7. Build Instructions

### Prerequisites

- Android SDK (API 34) + NDK 27.2
- Rust + `cargo-ndk` with targets: `aarch64-linux-android`, `x86_64-linux-android`
- Go 1.22+ with `gomobile` and `gobind`
- Java 17 (JBR from Android Studio)

### Build

```bash
# Set environment
export ANDROID_HOME=/path/to/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
export JAVA_HOME=/path/to/jbr

# Build Rust native libs
cd rust
cargo ndk -t arm64-v8a -t x86_64 \
    -o ../android/app/src/main/jniLibs build --release -p ghost-crypto

# Build Go mesh router AAR
cd go/ghostrouter
gomobile bind -target android/arm64,android/amd64 -androidapi 26 \
    -o ../../android/app/libs/ghostrouter.aar .

# Build APK
./gradlew assembleDebug

# Install
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Or use the combined script:
```bash
./scripts/build-native.sh
```

### Test

```bash
# Go unit tests (13 tests)
cd go/ghostrouter && go test -v ./...

# Rust check
cd rust && cargo check -p ghost-crypto

# Gradle build
./gradlew assembleDebug
```

---

## 8. Roadmap

| Version | Goal | Status |
|---|---|---|
| **v0.1.0** | Project scaffold, BLE discovery, QR exchange, encrypted messaging | ✅ Complete |
| **v0.1.1** | Go spray-and-wait router (code + tests) | ✅ Complete |
| **v0.1.2** | Wire router into BLE flow | ✅ Complete |
| **v0.1.3** | Fix JNI memory bug, verify end-to-end on hardware | ✅ Complete |
| **v0.1.4** | Premium UI and background resilience | ✅ Complete |
| **v0.1.5** | Security hardening, bug audit, stability | ✅ Complete |
| **v0.2** | Delivery receipts, 3-phone relay test, contact introductions | Planned |
| **v0.3** | Group chat, message attachments, performance optimization | Planned |
| **v0.4** | WiFi Direct transport, improved battery management | Planned |
| **v1.0** | Post-quantum crypto, cover traffic, voice UI | Long-term |

---

## 9. File Map

```
ghost-protocol/
├── CHANGELOG.md                  # Version history
├── android/app/src/main/java/com/ghostprotocol/
│   ├── MainActivity.kt           # Navigation, username gate
│   ├── GhostService.kt           # Foreground service, router init, message routing
│   ├── IdentityManager.kt        # Ed25519/X25519 keypair management
│   ├── BatteryMonitor.kt         # 15-min battery logging
│   ├── ble/
│   │   └── BleManager.kt         # BLE advertise, scan, GATT server/client
│   ├── data/
│   │   ├── Contact.kt            # Room entity
│   │   ├── ContactDao.kt         # Room DAO
│   │   ├── MessageEntity.kt      # Room entity + status constants
│   │   ├── MessageDao.kt         # Room DAO
│   │   └── GhostDatabase.kt      # Room database (version 2)
│   ├── crypto/
│   │   └── GhostCrypto.kt        # Kotlin singleton wrapping Rust JNI
│   ├── router/
│   │   └── GhostRouter.kt        # Kotlin bridge for Go gomobile router
│   └── ui/
│       ├── ChatScreen.kt         # Chat UI + send via router
│       ├── ContactListScreen.kt  # Contact list with search
│       ├── QRShowScreen.kt       # Display QR code
│       ├── QRScanScreen.kt       # Scan QR code (CameraX + ML Kit)
│       ├── SettingsScreen.kt     # Settings, identity, debug
│       ├── UsernameSetupScreen.kt# First-launch username
│       └── AvatarGenerator.kt    # Deterministic color + initial
├── rust/ghost-crypto/src/
│   └── lib.rs                    # Ed25519, X25519, AES-256-GCM JNI functions
├── go/ghostrouter/
│   ├── message.go                # Data structures
│   ├── store.go                  # BoltDB persistence
│   ├── router.go                 # Spray-and-wait algorithm
│   ├── serializer.go             # Wire format codec
│   └── router_test.go            # 13 unit tests
├── scripts/
│   └── build-native.sh           # Combined Rust + Go + Gradle build
└── docs/
    ├── GHOST-v0.1-Status.md      # This file
    └── rfc/                      # Future protocol layer specs
```
