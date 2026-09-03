# GHOST Protocol: An Offline Encrypted Mesh Messenger for Infrastructure-Denied Environments

**Version 0.2.0 — September 2026**

**Authors:** GHOST Protocol Research Group

---

## Abstract

GHOST (Global Hybrid Offline Secure Transport) is an offline mesh messaging system for Android that enables encrypted communication without internet connectivity, cellular infrastructure, or central servers. GHOST operates over Bluetooth Low Energy (BLE) 5.0 and provides end-to-end encrypted messaging using X25519 key agreement, AES-256-GCM authenticated encryption, and Ed25519 digital signatures.

Messages are routed through a Spray-and-Wait epidemic routing engine implemented in Go with BoltDB persistence, enabling multi-hop delay-tolerant delivery through intermediate relay nodes when peers are out of direct radio range. Contacts are established via cryptographic QR codes with reciprocal mutual verification.

**v0.2.0 introduces physical power-aware mesh management**: a centralized `PowerPolicyEngine` dynamically governing BLE duty cycles across 4 operating modes (ACTIVE, ECO, CRITICAL, DEEP_SLEEP), single-session GATT message batching (cutting connection overhead by ~70%), relay willingness shedding on dying batteries (<20%), persistent SQLite battery telemetry with CSV export, and BitChat-style reciprocal QR mutual verification.

The codebase is approximately 6,800 lines across three languages: Kotlin (UI, BLE, Power, Room DB), Rust (cryptography via JNI), and Go (mesh routing via gomobile). The debug APK is 46 MB and runs on Android 8.0+ devices with zero Google Play Services dependencies. All core capabilities are verified across physical Android devices.

---

## 1. Introduction: The Problem

### 1.1 The Centralization Trap

Contemporary secure messaging systems depend on infrastructure. WhatsApp requires persistent TCP connections to Meta-operated servers. Signal depends on Amazon Web Services for message relay and Google Firebase for push notifications. Telegram's "decentralized" architecture consists of five centralized clusters under a single corporate entity.

This dependency creates a single point of failure. Between 2016 and 2025, governments imposed internet shutdowns on at least 1,196 occasions across 84 countries [1]. During Myanmar's 2021 military coup, the junta severed all mobile data for 72 consecutive days. Iran's 2022 Mahsa Amini protests saw authorities throttle international bandwidth to 5 kbps. Turkey's 2023 earthquake killed 50,000 people while simultaneously destroying 4,500 cell towers.

Any protocol that assumes a functioning IP layer inherits the fragility of that layer.

### 1.2 Prior Work

Several offline mesh messaging systems exist. All have significant limitations:

**Briar** [2] is the most technically serious attempt. It supports Tor, WiFi, and Bluetooth transports. However, Briar suffers from the *empty room problem* (messages cannot traverse intermediate nodes without explicit opt-in), *identity death* (losing a device permanently destroys identity), and *battery exhaustion* (12–18% per day from continuous BLE scanning).

**Bridgefy** [3] gained prominence during the 2019 Hong Kong protests (1.5 million downloads). Security researchers demonstrated that Bridgefy leaked metadata (sender ID, recipient ID, message size) in cleartext Bluetooth advertisements.

**FireChat** [4] achieved mesh routing during the 2014 Umbrella Revolution but was never encrypted — all messages transmitted in plaintext.

**BitChat** [5], announced in 2025, implements Bluetooth mesh with Nostr-compatible identity. However, BitChat exposes persistent public `npub` identifiers on public Nostr relays, enabling global metadata tracking.

### 1.3 GHOST's Approach

GHOST takes a pragmatic engineering approach: **implement the necessary cryptographic core, verify it on real silicon, and systematically optimize physical power constraints.**

| Dimension | Briar | Bridgefy | FireChat | BitChat | GHOST v0.2.0 |
|---|---|---|---|---|---|
| E2E encryption | ✓ | Partial | ✗ | ✓ | **✓ (X25519 + AES-256-GCM)** |
| Multi-hop relay | ✗ | ✓ | ✓ | ✓ | **✓ (Spray-and-Wait, BoltDB)** |
| Power-aware policy | ✗ (Static) | ✗ | ✗ | ✗ | **✓ (4 modes, dynamic duty cycle)** |
| Single-session batching | ✗ | ✗ | ✗ | ✗ | **✓ (~70% radio time reduction)** |
| Relay load shedding | ✗ | ✗ | ✗ | ✗ | **✓ (Drops transit if battery <20%)** |
| Reciprocal verification | QR | ✗ | ✗ | Nostr / QR | **✓ (Two-way mutual scan + wire packet)** |
| Global public tracker | None | Static BLE | None | Nostr `npub` | **None (4-byte ephemeral fingerprint)** |
| No Google Play Services | ✓ | ✗ | ✗ | ✓ | **✓ (100% AOSP standalone)** |

**Table 1.** Technical comparison across decentralized offline messaging systems.

---

## 2. System Architecture

GHOST v0.1.5 consists of three components connected via foreign function interfaces:

```
┌──────────────────────────────────────────────────┐
│              Kotlin Android App                  │
│  ┌─────────────┐  ┌───────────┐  ┌────────────┐ │
│  │  Compose UI  │  │  Room DB  │  │    BLE     │ │
│  │  6 screens   │  │  SQLite   │  │  Manager   │ │
│  │  Jetpack     │  │  Contacts │  │  GATT S/C  │ │
│  │  Navigation  │  │  Messages │  │  Advertise │ │
│  └──────┬───────┘  └─────┬─────┘  └─────┬──────┘ │
│         │               │               │        │
│  ┌──────┴───────────────┴───────────────┴──────┐ │
│  │         GhostService (foreground)           │ │
│  │  • Router init + message routing            │ │
│  │  • Peer matching (BLE fingerprint → Room)   │ │
│  │  • Peer discovery → spray/deliver           │ │
│  │  • Message processing (decrypt/verify)      │ │
│  │  • WakeLock + battery optimization          │ │
│  └──────┬───────────────┬──────────────────────┘ │
│         │               │                        │
│  ┌──────┴───────┐ ┌─────┴────────────┐          │
│  │  Rust JNI    │ │  Go gomobile     │          │
│  │  ghost-      │ │  ghostrouter     │          │
│  │  crypto.so   │ │  .aar            │          │
│  │              │ │                  │          │
│  │  Ed25519     │ │  Spray-and-wait  │          │
│  │  X25519      │ │  BoltDB store    │          │
│  │  AES-256-GCM │ │  Binary spray    │          │
│  │  ~300KB/arch │ │  4.9 MB AAR      │          │
│  └──────────────┘ └──────────────────┘          │
└──────────────────────────────────────────────────┘
```

**Figure 1.** GHOST v0.1.5 architecture. Three languages, two FFI bridges, zero servers.

### 2.1 Language Rationale

- **Rust** for cryptography: Memory-safe, constant-time implementations from `dalek-cryptography`. No garbage collection pauses during crypto operations. Compiled to `libghost_crypto.so` and loaded via JNI.
- **Go** for routing: Goroutine-based concurrency for background message processing. BoltDB for persistent message queue. Compiled to `ghostrouter.aar` via gomobile.
- **Kotlin** for UI and BLE: Android platform APIs (BLE, Camera, Room DB) and Jetpack Compose declarative UI. Coroutine-based async.

### 2.2 Data Flow

**Sending a message:**
```
User taps Send
    → Kotlin: encrypt plaintext with Rust JNI
    → Kotlin: call Go router.sendMessage(dstId, ciphertext)
    → Go: peer seen <60s ago?
        YES → encode routing header + ciphertext → return blob
            → Kotlin: BLE GATT write blob to peer → status = SENT ✓
        NO  → store in BoltDB → status = SPRAYED 📡
            → [later: BLE discovers a peer]
            → Go: onPeerDiscovered() → return delivery/spray blobs
            → Kotlin: BLE GATT write each blob
```

**Receiving a message:**
```
BLE GATT server receives blob
    → Kotlin: call Go router.onMessageReceived(blob)
    → Go: decode routing header
        Dst matches localId → "delivered" → callback fires
            → Kotlin: decrypt with Rust JNI → verify Ed25519 sig → save to Room DB
        Dst doesn't match → "forwarded" → store in BoltDB for later spray
```

---

## 3. Implemented Features

### 3.1 BLE Discovery and Transport

**Implementation:** [`BleManager.kt`](android/app/src/main/java/com/ghostprotocol/ble/BleManager.kt)

GHOST uses BLE 5.0 connectable advertising with a custom 128-bit service UUID. Each device embeds its 4-byte identity fingerprint (first 4 bytes of SHA-256 of the Ed25519 public key) directly into the primary 31-byte advertising packet (`advData`). This allows passive BLE scanners to identify known contacts immediately without waiting for scan response packets, and ensures continuous connection tracking when Android rotates private BLE MAC addresses.

Message transfer uses GATT client/server roles:
1. Sender connects to receiver's GATT server
2. MTU negotiated to 512 bytes
3. Single or batched messages written sequentially to custom GATT characteristic
4. Connection closed immediately after write

**Measured performance:**
- BLE advertising packet: 21 bytes (primary advData) — strictly within the 31-byte legacy limit
- Direct message latency: 2–4 seconds (dominated by BLE connection setup and service discovery)
- Batched message latency: 4–5 seconds for 5 messages (~70% reduction in radio on-time)
- Range: ~10m indoors (standard BLE 5.0, no external amplifiers)

### 3.2 Reciprocal QR Code Contact Exchange

**Implementation:** [`QRShowScreen.kt`](android/app/src/main/java/com/ghostprotocol/ui/QRShowScreen.kt), [`QRScanScreen.kt`](android/app/src/main/java/com/ghostprotocol/ui/QRScanScreen.kt)

Contact exchange uses QR codes to transmit cryptographic identity bundles out-of-band:

```
QR payload format:
GHOST:<Base64(ed25519_pub(32) + x25519_pub(32) + name_utf8)>
```

**BitChat-Style Reciprocal Flow:**
1. Device A scans Device B's QR code.
2. Device A decodes keys, saves B into Room DB with `isVerified = true`.
3. Device A emits haptic feedback, displays a toast, and **automatically navigates to `QRShowScreen`**, presenting Device A's QR code so Device B can scan back without delay.
4. Device A transmits a signed verification packet over BLE to Device B.
5. When reciprocal scan or handshake is confirmed, both devices insert `* mutual verification with <name> *`, trigger an Android system notification, and display the green verified badge `🔒 ✔` in chat and contact lists.

### 3.3 End-to-End Encryption

**Implementation:** [`rust/ghost-crypto/src/lib.rs`](rust/ghost-crypto/src/lib.rs) (299 lines)

Every message is encrypted and signed using three cryptographic primitives:

| Primitive | Algorithm | Purpose | Library |
|---|---|---|---|
| Key agreement | X25519 ECDH | Per-message shared secret | `x25519-dalek` |
| Symmetric cipher | AES-256-GCM | Authenticated encryption | `aes-gcm` |
| Digital signature | Ed25519 | Origin authentication & integrity | `ed25519-dalek` |

### 3.4 Envelope & Wire Formats

**Direct Wire Format:**
```
[4 bytes: header length N, big-endian uint32]
[N bytes: JSON RoutingHeader]
[remaining: encrypted payload]
```

**Implementation:** 6 Compose screens, ~1,100 lines total

- **Contact list:** GHOST branding, pill search bar, avatar with online indicator, stacked FABs
- **Chat:** Purple gradient sent bubbles, dark gray received, message grouping, time headers, inline status icons, animated send/receive
- **QR Show/Scan:** CameraX real-time scanning with ML Kit
- **Settings:** Identity display, QR code, battery debug, delete all data
- **Username setup:** First-launch screen

**Message status indicators:**
| Icon | Status | Meaning |
|---|---|---|
| ⏳ | PENDING | Encrypting / BLE connecting |
| ✓ | SENT | GATT write confirmed by BLE stack |
| ✓✓ | DELIVERED | Reserved for future delivery receipts |
| 📡 | SPRAYED | Queued in Go router for relay delivery |
| ⚠ | FAILED | BLE write failed |

---

## 4. Performance (Measured)

All measurements taken on physical Android 12+ devices.

| Metric | Measured Value | Notes |
|---|---|---|
| APK size (debug) | 46 MB | Rust .so (2 ABIs) + Go .aar (2 ABIs). Release with R8 would be smaller. |
| Go router AAR | 4.9 MB | arm64 + x86_64 native libs |
| Rust crypto .so | ~300 KB per ABI | Ed25519 + X25519 + AES-256-GCM |
| Cold start time | ~1.5s | `ActivityTaskManager: Displayed` logcat |
| BLE message latency | 2–4 seconds | GATT connect → MTU → discover → write → disconnect |
| BLE range | ~10m indoors | Standard BLE 5.0, no external antenna |
| Go unit tests | 13/13 pass in 0.009s | Store, serializer, router, spray, dedup |
| Lines of code | ~5,000 | Kotlin + Rust + Go (excluding stubs) |
| BLE advertising | 21 + 11 bytes | Under 31-byte BLE legacy limit |

---

## 5. Security Analysis

### 5.1 What v0.1.5 Defends Against

v0.1.5 went through 10 rounds of internal bug hunting. 73 bugs found, 67 fixed. The major ones:
- **Concurrency & Deadlocks:** Fixed Go router deadlocks and BoltDB contention issues.
- **FFI Panics:** Mitigated Rust unwrap panics across the JNI boundary.
- **Resource Exhaustion:** Fixed GATT buffer overflow and added 10-second GATT client timeouts.
- **Collision Vulnerabilities:** `computeMessageID` now includes random nonces to prevent message ID collisions on rapid sends.
- **Logic Bugs:** Fixed direct-send messages being re-sprayed, relay loops in `OnPeerDiscovered`, and dedup window optimizations (reduced from 30s to 5s).


| Threat | Defense | Implementation |
|---|---|---|
| **Passive eavesdropping** | X25519 ECDH + AES-256-GCM per-message encryption | `ghost-crypto/src/lib.rs` |
| **Message tampering** | AES-256-GCM 128-bit authentication tag | `ghost-crypto/src/lib.rs` |
| **Message forgery** | Ed25519 digital signature on every message | `ghost-crypto/src/lib.rs` |
| **Replay attacks** | Sliding 60s window deduplication on ciphertext with ephemeral nonces | `GhostService.kt` |
| **Battery exhaustion (Denial-of-Sleep)** | `PowerPolicyEngine` throttles duty cycle; `relayWillingness` drops transit packets at <20% battery | `PowerPolicyEngine.kt`, `router.go` |
| **GATT connection storms** | Mutex serialization + single-session message batching | `BleManager.kt` |
| **Metadata harvesting** | No server, no accounts, no phone numbers, ephemeral BLE fingerprints | Architecture |
| **Infrastructure dependency** | BLE-only, zero internet connectivity required | `BleManager.kt` |

### 5.2 What v0.2.0 Does NOT Defend Against

| Threat | Status | Planned |
|---|---|---|
| **Device seizure** | Keys stored in app private dir, no hardware keystore | v1.0: encrypted keystore |
| **Traffic analysis** | No cover traffic, no packet padding | v0.3.5: fixed-size packets + cover traffic |
| **Quantum adversary** | X25519/Ed25519 vulnerable to Shor's algorithm | v1.0: ML-KEM-1024 hybrid |
| **Friction in protests** | In-person QR scanning requires static visual alignment | v0.3.0: Protest Mode (1-tap BLE consent + short codes) |
| **Identity loss** | No recovery mechanism | v1.0: Shamir (5,3) threshold |
| **Sybil attacks** | No identity verification beyond reciprocal QR exchange | v1.0: guardian attestation |

### 5.3 Threat Model

GHOST v0.2.0's security is appropriate for:

- **Tier 1 (Passive Observer):** ✅ Fully defended. All message content is encrypted with fresh ephemeral keys per message. Signatures prevent forgery.
- **Tier 2 (Active Network Adversary):** ⚠ Partially defended. Messages cannot be forged or tampered with. Battery drain attacks are mitigated. However, an active adversary can perform traffic analysis (message timing, BLE MAC clusters).
- **Tier 3 (State-Level Adversary):** ❌ Not defended. Device seizure exposes all keys and message history. No plausible deniability.
- **Tier 4 (Quantum Adversary):** ❌ Not defended. X25519 and Ed25519 are vulnerable to quantum computers.

---

## 6. Comparison with Related Work

### 6.1 Feature Matrix

| Feature | GHOST v0.2.0 | Briar | Bridgefy | BitChat |
|---|---|---|---|---|
| E2E encryption | **AES-256-GCM + Ed25519** | Double ratchet | AES (post-2020) | Nostr NIP-44 |
| Forward secrecy | **Yes (ephemeral X25519 per send)** | Yes (double ratchet) | No | No |
| Key exchange | **Reciprocal QR (out-of-band)** | QR code | Server-mediated | Nostr relay / QR |
| Multi-hop routing | **Spray-and-wait (Go + BoltDB)** | None (local forum only) | Proprietary | Flooding |
| Power management | **Dynamic PowerPolicyEngine** | Static throttling | None | None |
| Message batching | **Single GATT session batching** | No | No | No |
| Offline operation | **Full (BLE only)** | Partial (needs Tor for some) | Full (BLE) | Full (BLE) |
| Group chat | No (planned v0.3) | Yes | Yes | No |
| Custom username | Yes | Yes | Yes | No (pubkey only) |
| Deterministic avatar | Yes (SHA-256 color) | No | No | No |
| Open source | Yes | Yes | No | Yes |
| Lines of code | **~6,800** | ~60,000 | Proprietary | ~3,000 |
| Google Play required | **No** | No | Yes | No |

### 6.2 Honest Assessment

**Where GHOST v0.2.0 wins:**
- Three-language architecture (Rust crypto, Go routing, Kotlin UI) provides clean separation of concerns and crash isolation across FFI.
- PowerPolicyEngine with 4 dynamic operating modes and single-session GATT batching reduces radio on-time by ~70%, preventing the battery drain that plagued earlier mesh messengers.
- Delay-tolerant store-and-forward re-encounter delivery ensures pending messages are reliably flushed when peers re-enter radio range.
- BitChat-style reciprocal QR verification provides seamless two-way mutual verification without manual screen switching.

**Where competitors win:**
- **Briar** has double ratchet session state, group forums, and years of field audits.
- **Bridgefy** has automated frictionless nearby discovery (at the cost of total metadata and message privacy).
- **BitChat** has Nostr ecosystem reach and verbal `npub` sharing (at the cost of persistent public tracking).

---

## 7. Known Limitations

1. **QR-Only Contact Setup Friction:** In-person QR scanning is zero-TOFU and eliminates MITM, but creates critical latency in chaotic survival or protest scenarios where users cannot stop to align cameras. Addressed by **v0.3 Protest Mode**.
2. **Delivery Confirmation vs Radio Write:** Single `✓` confirms GATT write succeeded at the receiver's BLE stack. Application-level end-to-end delivery confirmation (`✓✓`) is scheduled for v0.3.5.
3. **Physical BLE Range:** Standard BLE 5.0 indoor range is ~10m. WiFi Direct high-bandwidth transport is scheduled for v0.4.
4. **Multi-Hop Mesh Scale Testing:** Binary spray-and-wait is verified in Go unit tests (15/15 pass) and 2-phone DTN store-and-forward re-encounter delivery is verified on physical silicon. Dense mesh testing with 10+ devices is scheduled for field trials.
5. **Continuous Ratchet:** Per-message ephemeral X25519 provides forward secrecy per transmission, but full Double Ratchet continuous session ratcheting is scheduled for future milestones.
6. **BLE MAC Address Rotation:** Android rotates private BLE MACs every 15–30 minutes. GHOST binds identity to the 4-byte fingerprint in the primary advertisement packet (`advData`), mitigating MAC churn.

---

## 8. Future Work & Roadmap

### v0.2.0 — Power & Reliability (Completed)
- PowerPolicyEngine with 4 dynamic modes (ACTIVE, ECO, CRITICAL, DEEP_SLEEP)
- Single-session GATT message batching (~70% radio time reduction)
- Relay willingness load shedding on dying batteries (<20%)
- Battery & mesh telemetry with SQLite logging and CSV export
- DTN store-and-forward automatic re-encounter delivery
- BitChat-style reciprocal QR scanning and mutual verification (`🔒 ✔`)

### v0.2.5 — Trust Web
- Contact Introductions: Alice introduces Bob to Carol via signed cryptographic introduction envelope

### v0.3.0 — Protest Mode & Fluid Discovery
- Nearby BLE discovery with one-tap mutual consent (<3s setup)
- 24-hour rotating BIP-39 shareable short codes (verbal / sign sharing)
- "Protest Mode" quick toggle in notification shade

### v0.3.5 — Group Messaging & Receipts
- End-to-end delivery receipts (`✓✓` protocol)
- Multi-peer group chat with pairwise fan-out encryption
- Chunked BLE binary file transfer

### v0.4.0 — Emergency Transport
- Channel 0 Emergency Public Broadcast ("Ghost Megaphone") for unencrypted localized alert bursts
- WiFi Direct high-speed transport fallback (50m range, 10 Mbps)

### v1.0.0 — Production Cypherpunk Hardening
- Post-quantum cryptography: ML-KEM-1024 hybrid key exchange
- Sphinx cover traffic with Poisson timing to resist traffic analysis
- Shamir (5,3) secret sharing identity recovery with biometric seed
- Encrypted keystore at rest bound to Android hardware security module

---

## 9. Build and Test

### Prerequisites
- Android SDK (API 34) + NDK 27.2
- Rust + `cargo-ndk` with targets: `aarch64-linux-android`, `x86_64-linux-android`
- Go 1.22+ with `gomobile` and `gobind`
- Java 17

### Build Commands
```bash
# Build Rust crypto library
cd rust && cargo ndk -t arm64-v8a -t x86_64 \
    -o ../android/app/src/main/jniLibs build --release -p ghost-crypto

# Build Go mesh router AAR
cd go/ghostrouter && gomobile bind -target android/arm64,android/amd64 \
    -androidapi 26 -o ../../android/app/libs/ghostrouter.aar .

# Build APK
./gradlew assembleDebug

# Install on device
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### Run Tests
```bash
# Go unit tests (13 tests)
cd go/ghostrouter && go test -v ./...

# Rust check
cd rust && cargo check -p ghost-crypto
```

For detailed implementation status, see [`docs/GHOST-v0.1.5-Status.md`](GHOST-v0.1.5-Status.md).

---

## References

[1] Access Now, "Internet shutdowns in 2023," accessnow.org/keepiton, 2024.

[2] Briar Project, "Briar: Secure messaging, anywhere," briarproject.org.

[3] Open Garden, "Bridgefy: Offline Messaging," bridgefy.me.

[4] Open Garden, "FireChat," opengarden.com/firechat.

[5] Jack Dorsey et al., "BitChat: BLE mesh messaging with Nostr identity," github.com/nicobao/bitchat, 2025.

[6] D. J. Bernstein, N. Duif, T. Lange, P. Schwabe, and B.-Y. Yang, "High-speed high-security signatures," J. Cryptographic Engineering, 2012.

[7] D. J. Bernstein, "Curve25519: new Diffie-Hellman speed records," PKC 2006.

[8] NIST, "FIPS 197: Advanced Encryption Standard," 2001.

[9] A. Shamir, "How to share a secret," Commun. ACM, 1979.

[10] NIST, "FIPS 203: Module-Lattice-Based Key-Encapsulation Mechanism Standard (ML-KEM)," 2024.

---

## Appendix A: Wire Formats

### A.1 Identity Blob (128 bytes)
```
[0..32)   Ed25519 seed
[32..64)  Ed25519 public key
[64..96)  X25519 secret key
[96..128) X25519 public key
```

### A.2 QR Code Payload
```
GHOST:<Base64(ed25519_pub(32) + x25519_pub(32) + name_utf8)>
```

### A.3 Encrypted Message
```
ephemeral_x25519_pub(32) + nonce(12) + AES-256-GCM(
    key = ECDH(ephemeral, recipient_x25519),
    plaintext = sender_ed25519_pub(32) + message_utf8 + ed25519_signature(64)
)
```

### A.4 Routing Envelope
```
[4 bytes: header length N, big-endian uint32]
[N bytes: JSON-encoded RoutingHeader]
[remaining: encrypted message (A.3 format)]
```

### A.5 BLE Advertising
```
Main advertising data (21 bytes):
  Flags(3) + Complete 128-bit UUID(18)

Scan response data (11 bytes):
  Complete Local Name("GHOST", 7) + Manufacturer Data(fingerprint, 4)
```
