# GHOST Protocol: An Offline Encrypted Mesh Messenger for Infrastructure-Denied Environments

**Version 0.1.5 — September 2026**

**Authors:** GHOST Protocol Research Group

---

## Abstract

GHOST (Global Hybrid Offline Secure Transport) is an offline mesh messaging application for Android that enables encrypted communication without internet connectivity, cellular infrastructure, or cloud services. GHOST v0.1.5 operates over Bluetooth Low Energy (BLE) 5.0 and provides end-to-end encrypted text messaging between Android devices using X25519 key agreement, AES-256-GCM authenticated encryption, and Ed25519 digital signatures.

Messages are routed through a spray-and-wait epidemic routing algorithm implemented in Go, enabling multi-hop delivery through intermediate relay devices when sender and receiver are not in direct BLE range. Contacts are exchanged via QR codes containing cryptographic identity bundles. All data is stored locally — no accounts, no servers, no phone numbers.

**v0.1.5 has been tested on two physical Android phones** with verified bidirectional encrypted messaging. The spray-and-wait router is implemented and unit-tested (13/13 tests pass) but multi-hop relay through a third device is pending field verification.

The codebase is approximately 5,500 lines across three languages: Kotlin (UI + BLE), Rust (cryptography via JNI), and Go (mesh routing via gomobile). The debug APK is 46 MB and runs on Android 8.0+ devices with no Google Play Services dependency.

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

**BitChat** [5], announced in 2025, implements Bluetooth mesh with Nostr-compatible identity. As of this writing, BitChat has undergone no external security audit and provides no cover traffic, no traffic analysis resistance, and no forward secrecy.

### 1.3 GHOST's Approach

GHOST takes a pragmatic approach: **ship what works, then iterate.** Rather than designing a theoretical 7-layer protocol stack and hoping to implement it, GHOST v0.1.5 implements the minimum viable set of features needed for secure offline messaging, verifies them on real hardware, and documents what actually works versus what is planned.

| Failure Mode | Briar | Bridgefy | FireChat | BitChat | GHOST v0.1.5 |
|---|---|---|---|---|---|
| E2E encryption | ✓ | Partial | ✗ | ✓ | ✓ |
| Multi-hop relay | ✗ | ✓ | ✓ | ✓ | ✓ (implemented, pending 3-phone test) |
| Identity recovery | ✗ | ✗ | ✗ | ✗ | ✗ (planned v1.0) |
| Traffic analysis resistance | ✗ | ✗ | ✗ | ✗ | ✗ (planned v1.0) |
| Post-quantum crypto | ✗ | ✗ | ✗ | ✗ | ✗ (planned v1.0) |
| Custom username + avatar | ✗ | ✗ | ✓ | ✗ | ✓ |
| No Google Play Services | ✓ | ✗ | ✗ | ✓ | ✓ |

**Table 1.** Honest feature comparison. GHOST v0.1.5 does not claim features it has not implemented.

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

**Implementation:** [`BleManager.kt`](android/app/src/main/java/com/ghostprotocol/ble/BleManager.kt) (339 lines)

GHOST uses BLE 5.0 connectable advertising with a custom 128-bit UUID. Each device embeds a 4-byte identity fingerprint (first 4 bytes of SHA-256 of the Ed25519 public key) in the BLE scan response data. This allows passive identity matching without establishing a GATT connection.

Message transfer uses GATT client/server roles:
1. Sender connects to receiver's GATT server
2. MTU negotiated to 512 bytes
3. Message written to custom GATT characteristic
4. Connection closed immediately after write

**Measured performance:**
- BLE advertising packet: 21 bytes (main) + 11 bytes (scan response) — under the 31-byte BLE legacy limit
- Message latency: 2–4 seconds (dominated by BLE connection setup, not distance)
- Range: ~10m indoors (standard BLE 5.0, no external antenna)

### 3.2 QR Code Contact Exchange

**Implementation:** [`QRShowScreen.kt`](android/app/src/main/java/com/ghostprotocol/ui/QRShowScreen.kt) (94 lines), [`QRScanScreen.kt`](android/app/src/main/java/com/ghostprotocol/ui/QRScanScreen.kt) (236 lines)

Contact exchange uses QR codes to transmit cryptographic identity bundles out-of-band:

```
QR payload format:
GHOST:<Base64(ed25519_pub(32) + x25519_pub(32) + name_utf8)>
```

Scanning a QR code:
1. Decode Base64 payload
2. Extract Ed25519 public key (bytes 0–31), X25519 public key (bytes 32–63), display name (bytes 64+)
3. Compute contact ID: `SHA-256(ed25519_pub).take(8).toHex()` → 16-char hex string
4. Store in Room database with both public keys

QR scanning uses CameraX with ML Kit barcode detection for real-time recognition.

### 3.3 End-to-End Encryption

**Implementation:** [`rust/ghost-crypto/src/lib.rs`](rust/ghost-crypto/src/lib.rs) (299 lines)

Every message is encrypted and signed using three cryptographic primitives:

| Primitive | Algorithm | Purpose | Library |
|---|---|---|---|
| Key agreement | X25519 ECDH | Per-message shared secret | `x25519-dalek` |
| Encryption | AES-256-GCM | Authenticated encryption with 128-bit tag | `aes-gcm` |
| Signature | Ed25519 | Message authentication + non-repudiation | `ed25519-dalek` |

**Encryption flow:**
```
1. Generate ephemeral X25519 keypair
2. ECDH(ephemeral_secret, recipient_x25519_pub) → shared_secret
3. plaintext = sender_ed25519_pub(32) ‖ message_utf8 ‖ ed25519_signature(64)
4. nonce = random(12)
5. ciphertext = AES-256-GCM(shared_secret, nonce, plaintext)
6. wire format = ephemeral_x25519_pub(32) ‖ nonce(12) ‖ ciphertext_with_tag
```

**Verification flow:**
```
1. ECDH(my_x25519_secret, ephemeral_x25519_pub) → shared_secret
2. plaintext = AES-256-GCM_decrypt(shared_secret, nonce, ciphertext)
3. sender_ed25519_pub = plaintext[0..32]
4. message = plaintext[32..len-64]
5. signature = plaintext[len-64..len]
6. verify Ed25519(sender_ed25519_pub, sender_ed25519_pub ‖ message, signature)
```

### 3.4 Identity Management

**Implementation:** [`IdentityManager.kt`](android/app/src/main/java/com/ghostprotocol/IdentityManager.kt) (78 lines)

Identity is a 128-byte blob generated once and stored in the app's private directory:

```
identity_blob(128) = ed25519_seed(32) + ed25519_pub(32) + x25519_secret(32) + x25519_pub(32)
```

Derived identifiers:
- **Contact ID:** `SHA-256(ed25519_pub).take(8).toHex()` → 16 hex chars (e.g., `a3f7b2c4e1d08f9a`)
- **Display handle:** `#` + first 6 hex chars (e.g., `#a3f7b2`)
- **BLE fingerprint:** `SHA-256(ed25519_pub).take(4)` → 4 bytes in scan response

> **⚠ Known limitation:** Identity is not recoverable. Uninstalling the app or clearing data permanently destroys the keypair and all associated contacts. Shamir secret sharing recovery is planned for v1.0.

### 3.5 Opportunistic Store-and-Forward Routing (Spray-and-Wait)

**Implementation:** [`go/ghostrouter/router.go`](go/ghostrouter/router.go) (389 lines), [`go/ghostrouter/store.go`](go/ghostrouter/store.go) (364 lines)

GHOST uses the binary Spray-and-Wait algorithm for message delivery when the destination is not directly reachable via BLE. This is **not** dynamic route discovery (like AODV or DSR, which build routing tables by querying the network for paths). It is **opportunistic store-and-forward replication**: messages are given to encountered peers who physically carry them until they meet the destination.

**Why this design:**
- No infrastructure exists to maintain routing tables
- In sparse, highly mobile scenarios (protests, disasters), network topology changes every 30 seconds — traditional routing protocols would spend all their time repairing broken routes
- Spray-and-Wait is the simplest algorithm that works without any pre-planned routes or centralized coordination
- The Go implementation is ~300 lines. AODV would be 2,000+ with route request/reply/timeout/repair logic

**How it works:**

**Phase 1 — Spray:** When a message is created and the destination is not reachable, the sender initializes the message with `copies = 4`. When a relay peer is discovered via BLE, the sender gives half its copies to the relay. The relay then carries those copies and sprays them further.

**Phase 2 — Wait:** When a node has only 1 copy remaining, it holds the message until it encounters the destination directly.

**Honest limitations of Spray-and-Wait:**
- **Delivery is probabilistic, not guaranteed.** If no carrier ever meets the destination, the message expires after TTL (24h).
- **No delivery confirmation.** The sender knows the message was sprayed but not whether it arrived. (Delivery receipts planned for v0.2.)
- **Blind spraying.** v0.1.5 gives copies to any encountered peer without considering whether that peer is likely to meet the destination. (Encounter-history heuristics planned for v0.2.)
- **Copy overhead.** Each message is replicated up to L=4 times. In dense networks (100+ nodes), this creates significant BLE traffic.

**Routing header (wire format):**
```
[4 bytes: header length N, big-endian uint32]
[N bytes: JSON RoutingHeader]
[remaining: encrypted payload]
```

**RoutingHeader fields:**
```json
{
  "MessageID": "uuid",
  "Src": "<base64 32-byte SHA-256>",
  "Dst": "<base64 32-byte SHA-256>",
  "CopiesRemaining": 4,
  "TTLSeconds": 86400,
  "HopCount": 0,
  "CreatedAt": 1725235200
}
```

**Router parameters:**
| Parameter | Value | Rationale |
|---|---|---|
| Initial copies | 4 | Balance between delivery probability and network overhead |
| Max hops | 10 | Prevent infinite forwarding |
| TTL | 24 hours | Messages expire after 1 day |
| Recent peer window | 60 seconds | If peer seen <60s ago, send directly |
| DB size limit | 50 MB | Auto-prune oldest messages when exceeded |
| Stale peer timeout | 7 days | Remove peers not seen in a week |
| Expiry janitor interval | 60 seconds | Background cleanup of expired messages |

**Test coverage:** 13 unit tests covering store CRUD, serializer round-trip, direct delivery, forwarding, hop limit, TTL expiry, deduplication, spray on discovery, direct delivery when peer recent, and statistics reporting. All pass.

### 3.6 Persistent Storage

**Kotlin (Room/SQLite):** [`GhostDatabase.kt`](android/app/src/main/java/com/ghostprotocol/data/GhostDatabase.kt) — Contacts and messages survive app restart. Database version 2 with destructive migration for development.

**Go (BoltDB):** [`store.go`](go/ghostrouter/store.go) — Queued/sprayed messages persist across process restart. Auto-expiry of TTL-exceeded messages. 50 MB pruning limit.

### 3.7 User Interface

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
| **Replay attacks** | Unique UUID per message + deduplication in Go router | `router.go`, `store.go` |
| **Metadata harvesting** | No server, no accounts, no phone numbers | Architecture |
| **Infrastructure dependency** | BLE-only, no internet required | `BleManager.kt` |

### 5.2 What v0.1.5 Does NOT Defend Against

| Threat | Status | Planned |
|---|---|---|
| **Device seizure** | Keys stored in app private dir, no encryption at rest | v1.0: encrypted keystore |
| **Traffic analysis** | No cover traffic, no packet padding | v1.0: fixed-size packets + cover traffic |
| **Quantum adversary** | X25519/Ed25519 vulnerable to Shor's algorithm | v1.0: ML-KEM-768 hybrid |
| **BLE fingerprinting** | Static 4-byte fingerprint in scan response | v0.3: rotating fingerprints |
| **Key compromise** | No forward secrecy (no double ratchet) | v0.3: double ratchet protocol |
| **Identity loss** | No recovery mechanism | v1.0: Shamir (5,3) threshold |
| **Sybil attacks** | No identity verification beyond QR exchange | v1.0: guardian attestation |

### 5.3 Threat Model

GHOST v0.1.5's security is appropriate for:

- **Tier 1 (Passive Observer):** ✅ Fully defended. All message content is encrypted. Signatures prevent forgery.
- **Tier 2 (Active Network Adversary):** ⚠ Partially defended. Messages cannot be forged or tampered with. However, an active adversary can perform traffic analysis (message timing, sizes, BLE MAC addresses).
- **Tier 3 (State-Level Adversary):** ❌ Not defended. Device seizure exposes all keys and message history. No plausible deniability.
- **Tier 4 (Quantum Adversary):** ❌ Not defended. X25519 and Ed25519 are vulnerable to quantum computers.

### 5.4 Cryptographic Assumptions

GHOST v0.1.5 assumes:
1. The Computational Diffie-Hellman (CDH) problem is hard over Curve25519
2. AES-256-GCM is IND-CCA2 secure
3. Ed25519 is EUF-CMA secure
4. The Android Linux kernel is not compromised at the hardware level
5. The `dalek-cryptography` Rust implementations are correct and constant-time

---

## 6. Comparison with Related Work

### 6.1 Feature Matrix

| Feature | GHOST v0.1.5 | Briar | Bridgefy | BitChat |
|---|---|---|---|---|
| E2E encryption | AES-256-GCM + Ed25519 | Double ratchet | AES (post-2020) | Nostr NIP-44 |
| Key exchange | QR code (out-of-band) | QR code | Server-mediated | Nostr relay |
| Multi-hop routing | Spray-and-wait (Go) | None | Proprietary | Flooding |
| Offline operation | Full (BLE only) | Partial (needs Tor for some) | Full (BLE) | Full (BLE) |
| Forward secrecy | No | Yes (double ratchet) | No | No |
| Group chat | No | Yes | Yes | No |
| Identity recovery | No | No | N/A | No |
| Custom username | Yes | Yes | Yes | No (pubkey only) |
| Deterministic avatar | Yes (SHA-256 color) | No | No | No |
| Open source | Yes | Yes | No | Yes |
| External audit | No | Yes | Yes (found critical flaws) | No |
| Lines of code | ~5,000 | ~60,000 | Proprietary | ~3,000 |
| Google Play required | No | No | Yes | No |

### 6.2 Honest Assessment

**Where GHOST v0.1.5 wins:**
- Three-language architecture (Rust crypto, Go routing, Kotlin UI) provides stronger isolation than single-language implementations
- Spray-and-wait routing with BoltDB persistence is more sophisticated than BitChat's naive flooding
- Custom usernames and deterministic avatars provide better UX than Nostr-style raw pubkey identifiers
- 46 MB APK is smaller than Briar (~25 MB but requires Tor libraries for full functionality)

**Where competitors win:**
- **Briar** has forward secrecy (double ratchet), group chat, forums, and has been externally audited
- **Bridgefy** has a larger user base and proven real-world deployment (Hong Kong protests)
- **BitChat** has Nostr ecosystem integration and simpler identity model

---

## 7. Known Limitations

1. **No message recovery.** Key loss = permanent data loss. No backup, no cloud sync, no Shamir recovery.
2. **No delivery receipts.** Single ✓ means "BLE write succeeded," not "peer received and decrypted."
3. **No group chat.** 1:1 messaging only.
4. **BLE range ~10m indoors.** Standard BLE 5.0 limitation. No WiFi Direct or other transports.
5. **No iOS support.** Android only. iOS would require CoreBluetooth + Swift UI reimplementation.
6. **Debug APK is 46 MB.** Mostly Go + Rust native libs for 2 ABIs. Release minification would reduce this.
7. **Multi-hop not field-tested.** Spray-and-wait is unit-tested (13/13 pass) and router wiring is verified on 2 phones, but relay through a 3rd device requires 3 physical phones.
8. **No forward secrecy.** Each message uses a fresh ephemeral key for encryption, but there is no double ratchet. Compromising a device's long-term X25519 key allows decryption of captured ciphertexts.
9. **Database schema changes wipe data.** `fallbackToDestructiveMigration()` is enabled for development speed.
10. **BLE address rotation.** Android rotates BLE MAC addresses every ~15 minutes. GHOST handles this via fingerprint matching, but there's a brief window where a peer appears as a new device.

---

## 8. Future Work

### v0.2 — Delivery Assurance
- Delivery receipts (double checkmark protocol)
- Contact introductions (A introduces B to C without QR)
- Multi-hop relay field-tested with 3+ phones
- Message deletion (local + request remote deletion)

### v0.3 — Maturity
- Group chat (fan-out encryption per member)
- File/image attachments (chunked BLE transfer)
- Double ratchet forward secrecy
- Rotating BLE fingerprints
- Performance optimization (Rust .so size, startup time)

### v0.4 — Transport Expansion
- WiFi Direct transport (50m range, 10 Mbps)
- WiFi Aware (Android 8.0+, infrastructure-free discovery)
- Improved battery management with adaptive scanning intervals

### v1.0 — Full Protocol
- Post-quantum cryptography: ML-KEM-768 hybrid key exchange (X25519 ‖ ML-KEM)
- Cover traffic: fixed 512-byte packets, Poisson timing, 30% dummy traffic
- Shamir (5,3) identity recovery with guardian selection
- Encrypted keystore (keys encrypted at rest with biometric-bound key)
- Traffic analysis resistance
- Additional transports (ultrasonic, infrared) for RF-denied environments

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
