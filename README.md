<p align="center">
  <img src="docs/assets/logo.png" width="120" alt="GHOST logo">
</p>

<h1 align="center">GHOST Protocol</h1>

<p align="center">
  <em>Offline encrypted mesh messenger for Android. No internet. No servers. No phone numbers.</em>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8%2B-green" alt="Android 8+">
  <img src="https://img.shields.io/badge/license-MIT-blue" alt="MIT License">
  <img src="https://img.shields.io/badge/size-%3C50MB-blueviolet" alt="APK <50MB">
  <img src="https://img.shields.io/badge/version-v0.4.4-orange" alt="Version v0.4.4">
</p>

---

GHOST is an offline mesh messenger built for environments where cellular infrastructure is severed, heavily monitored, or nonexistent: protests, disaster zones, remote expeditions, or internet blackouts.

Two devices in Bluetooth range communicate directly peer-to-peer. Devices out of range communicate through multi-hop store-and-forward routing (Spray-and-Wait). Packets hop through intermediate phones without relays ever seeing message content, sender identities, or recipient public keys.

We built this across three distinct layers: **Kotlin** handles Android BLE GATT mechanics and Jetpack Compose UI, **Go** runs the embedded delay-tolerant router with BoltDB persistence, and **Rust** executes all cryptographic operations across JNI.

---

## Key Technical Characteristics

Offline mesh communications face distinct constraints compared to IP networks:
- Direct radio range is intermittent and partitioned.
- Public relay architectures can expose persistent identifiers.
- Continuous radio scanning causes high battery consumption.

GHOST is built around bounded resources, privacy, and operating system resilience:

1. **Pairwise Encrypted Cell Groups (v0.3.5/v0.4.0):** Private group chat for up to 8 members without cleartext broadcasts. Outgoing invites (`0x31`) and message envelopes (`0x30`) are encrypted with pairwise ephemeral keys. Self-healing `META` payloads allow offline recipients to join upon receiving their first group message.
2. **End-to-End Delivery Receipts (v0.3.7):** Cryptographic proof of receipt (`✓✓`) signed by the destination phone after Room database insertion. Distinguishes between transport write success and actual application reception.
3. **Contact Introductions / Trust Web (v0.3.6):** Alice vouches for Bob so Carol can add him without an immediate QR scan. Alice signs Bob's public keys; Carol verifies Alice's signature against her pinned contact list. One-way trust is visually enforced (slate ring, `INTRODUCED` chip) until mutual verification.
4. **Compose UI & Optimistic ACK (v0.3.8):** Extracted `ChatViewModel` with optimistic UI bubble acknowledgment; off-thread conversation aggregation via `ConversationRepository` on `Dispatchers.Default`; zero main-thread cryptographic or Room overhead during scrolling; 60 FPS `LazyColumn` frame timing; and unified 48dp minimum physical touch targets.
5. **Mesh Reliability & Low-End Device Resilience (v0.4.0–v0.4.4):**
   - **GATT Operation Queue & Dynamic MTU Slicing (`GattOperationQueue`):** Serialized FIFO client queue with 150ms cool-off per MAC address. Dynamic link-layer MTU framing (`0xFB`) slices payloads when data exceeds negotiated ATT MTU (defaulting to 23 on low-end MediaTek/Unisoc chipsets). 100ms post-connect link stabilization delay prevents controller resets before requesting 512-byte MTU.
   - **Scan Burst Decoupling:** Decouples BLE advertisement scan bursts from group message delivery so frequent beacon updates (every 100–300ms) do not abort in-flight database transactions or GATT transmissions.
   - **Reciprocal QR Verification Queue:** Enqueues verification handshakes in memory if the user scans before peer discovery, auto-flushing on the first observed advertisement.
   - **Go DTN Quotas & Eviction:** Bounded 2,048-entry dedup cache, 50-message transit cap per destination, and store eviction prioritizing local unsent pending messages. Automatic recovery in BoltDB.
   - **Battery Conservation:** Low-battery transit relay cutoff (<20%) across all postures with $\pm 2\%$ hysteresis.
   - **Rust/JNI Panic Boundaries:** Pure Rust cryptographic core with `std::panic::catch_unwind` error boundaries preventing native panics from aborting the JVM.
6. **Four Dynamic Security Postures (v0.3.0):**
   - `NORMAL`: Standard privacy. Discovery requires in-person QR scans. Default battery-saving BLE duty cycles.
   - `PROTEST`: High-readiness mesh. Scan rate increased to 1000ms / 200ms window. Background one-tap peer discovery active.
   - `EMERGENCY`: Continuous low-latency scanning (100% duty cycle). 100ms advertising bursts. Immediate packet forwarding.
   - `STEALTH`: Radio silence. BLE advertising transmitter completely disabled. Passive receiver only (listen without beaconing).
7. **Three Verification Methods:**
   - **In-Person QR:** Cryptographic key exchange with haptic confirmation.
   - **One-Tap Nearby Discovery (0x10/0x11):** Detects nearby peers in Protest Mode and sends authenticated consent handshakes without camera alignment.
   - **24-Hour Rotating BIP-39 Short Codes (0x20-0x23):** 3-word + 4-digit code (e.g., `LION - COBALT - HARBOR - 4821`) derived deterministically from the device's private seed and the current UTC epoch day. Rotates automatically at midnight UTC.
8. **Battery-Aware Duty Cycles:** Continuous BLE scanning significantly drains device batteries. GHOST's `PowerPolicyEngine` dynamically throttles duty cycles across `ACTIVE`, `ECO`, `CRITICAL`, and `DEEP_SLEEP` modes. If battery drops below 20%, the phone sheds transit relaying burdens.
9. **Deterministic Dedup (RFC 8032):** Deduplicates on 64-byte Ed25519 signatures over `(pubkey + plaintext)`, preserving deduplication across re-encryptions and multi-hop carrier relays.
10. **4-Stage Simulation Verification Suite (v0.4.1–v0.4.3):**
   - **Stage 1 (Deterministic Virtual Mesh):** 10 to 1,000+ nodes in discrete virtual time (`ghost-sim run`).
   - **Stage 2 (Extreme Mesh Torture):** 100,000 deterministic scenarios verifying invariants $I_1..I_{15}$ after persistent reboot deduplication and relay gating hardening.
   - **Stage 3 (UX / Responsiveness Torture):** 10,000 scenarios validating Compose UI, coroutine dispatchers, and Room DAOs with atomic status protection (`AND (status != 2 OR :status = 2)`).
   - **Stage 4 (Android OEM Runtime Engine):** 10,000 scenarios across 7 hostile OEM profiles, verifying 24 formal invariants ($O_1..O_{24}$) with explicit validation scope attribution.

---

## Wire Protocol Opcodes

GHOST demuxes incoming GATT payloads at byte 0. Opcodes `0x10` through `0x23`, `0x31`, `0x40`, and `0x50` are handled at the Kotlin protocol layer, while `0x01` and `0x30` route through the Go mesh engine. Opcode `0xFB` is consumed directly by the link-layer GATT reassembler:

| Opcode | Protocol | Purpose | Payload Format |
|---|---|---|---|
| `0x01` | Go Router | 1:1 Spray-and-Wait Packet | `[4B headerLen][JSON routingHeader][AES-256-GCM ciphertext]` |
| `0x10` | Discovery | Nearby Contact Request | `[1B 0x10][32B ed25519Pub][32B x25519Pub][8B ts][name][64B sig]` |
| `0x11` | Discovery | Nearby Contact Response | `[1B 0x11][32B ed25519Pub][32B x25519Pub][8B ts][name][64B sig]` |
| `0x20` | Short Code | BLE Direct Code Query | `[1B 0x20][32B targetCodeHash][32B senderEd25519Pub][64B sig]` |
| `0x21` | Short Code | BLE Direct Code Response | `[1B 0x21][32B responderEd25519Pub][32B responderX25519Pub][64B sig]` |
| `0x22` | Short Code | Mesh Multi-Hop Code Query | `[1B 0x22][32B targetCodeHash][32B senderEd25519Pub][64B sig]` |
| `0x23` | Short Code | Mesh Multi-Hop Code Response| `[1B 0x23][32B responderEd25519Pub][32B responderX25519Pub][64B sig]` |
| `0x30` | Cell Group| Individual Unicast Envelope | `[1B 0x30][32B groupId][16B senderId][8B ts][ciphertext][64B sig]` |
| `0x31` | Cell Group| Group Invitation | `[1B 0x31][32B groupId][16B creatorId][8B ts][ciphertext][64B sig]` |
| `0x40` | Delivery Receipt | Cryptographic E2E Delivery Ack | `[1B 0x40][64B msgHash][16B recipientId][8B ts][64B sig]` |
| `0x50` | Introduction | Cryptographic Voucher Envelope | `[1B 0x50][32B edPub][32B xPub][2B nameLen][name][16B voucherId][64B sig]` |
| `0xFB` | GATT Transport | Link-Layer Fragment | `[1B 0xFB][2B transferId][2B fragIndex][2B totalFrags][chunkPayload]` |

---

## Technical Architecture

```
+-------------------------------------------------------------------------+
|                        ANDROID APPLICATION LAYER                        |
|                                                                         |
|   Compose UI (v0.3.8 Architecture)  SecurityPostureManager  PowerEngine |
|   - ContactList (ConversationFeed)  - NORMAL / PROTEST     - ACTIVE/ECO |
|   - ChatScreen (Optimistic Bubble)  - EMERGENCY / STEALTH  - CRITICAL   |
|   - GroupChat (Pairwise Delivery)   - 15% Battery Revert   - DEEP SLEEP |
|   - GhostComponents (48dp Targets)                                      |
|                                                                         |
|   ConversationRepository            ChatViewModel                       |
|   - Off-thread join & sort          - Sub-1ms optimistic ACK            |
|   - O(1) RF fingerprint match       - Background Room & BLE dispatch    |
|                                                                         |
|   DiscoveryManager (0x10/0x11)      ShortCodeManager (0x20-0x23)        |
|   - 1-Tap Consent Handshake         - BIP-39 2048-Word Dictionary       |
|   - 20s Per-MAC Throttle            - UTC Midnight Key Rotation         |
|                                                                         |
|   Group Engine (0x30)     ReceiptHandler (0x40)   IntroHandler (0x50)   |
|   - Pairwise Unicast      - SHA-256 Content Hash  - 1-Way Voucher Verif |
|   - Sig Dedup Cache       - First-Delivery Ack    - 10m Pending Cache   |
|                           - Double Check (✓✓)     - Slate Avatar / Chip |
|                                                                         |
|   Room DB (Schema v9)                                                   |
|   - contacts (isIntroduced flag) & messages (contentHash index)         |
|   - groups & group_messages (contentHash + deliveredMemberIdsJson)      |
|   - telemetry_snapshots                                                 |
+------------------------------------+------------------------------------+
                                     |
              +----------------------+----------------------+
              | JNI                                         | gomobile
              v                                             v
+-----------------------------+               +---------------------------+
|    RUST CRYPTO ENGINE       |               |    GO MESH ROUTER         |
|                             |               |                           |
|  - X25519 ECDH              |               |  - Spray-and-Wait (L=4)   |
|  - AES-256-GCM AEAD         |               |  - BoltDB Message Store   |
|  - Ed25519 Sign / Verify    |               |  - Single-Session Batch   |
|  - Panic-Safe C Interface   |               |  - Relay Willingness Gate |
+-----------------------------+               +---------------------------+
```

---

## Building from Source

### Prerequisites
- Android SDK (API 34, compileSdk 34)
- Android NDK (r27b / 27.2+)
- OpenJDK 17 or 21 (bundled JBR in Android Studio works)
- Go 1.22+ with `gomobile` installed
- Rust toolchain with `cargo-ndk` (`aarch64-linux-android`, `x86_64-linux-android`)

### Build Steps

1. **Build Native Libraries (Go AAR + Rust .so):**
   ```bash
   ./scripts/build-native.sh
   ```

   Or compile the Go router manually:
   ```bash
   cd go/ghostrouter
   gomobile bind -target android/arm64,android/amd64 -androidapi 26 -o ../../android/app/libs/ghostrouter.aar .
   ```

2. **Run Unit & Simulation Test Suites:**
   ```bash
   # Android / Kotlin unit tests (Room, GroupProtocol, ShortCodes, StatusTransition)
   ./gradlew testDebugUnitTest

   # Go router core tests (unit + race detector)
   cd go/ghostrouter && go test -race -v ./...

   # Rust cryptographic engine tests
   cd rust/ghost-crypto && cargo test --lib

   # Build the deterministic simulator CLI
   cd go/ghostrouter && go build -o bin/ghost-sim ./cmd/ghost-sim

   # Stage 1: Deterministic Mesh Simulation Scenarios
   ./bin/ghost-sim run direct
   ./bin/ghost-sim run stress --nodes 100 --messages 1000

   # Stage 2: Extreme Mesh Torture Campaign (100k scenarios)
   ./bin/ghost-sim torture --scenarios 100000 --seed 123456789 --workers 8

   # Stage 3: UX & Responsiveness Torture Campaign (10k scenarios)
   ./bin/ghost-sim ux --scenarios 10000 --seed 42 --workers 8

   # Stage 4: Android OEM Hostile Runtime Engine (10k scenarios)
   ./bin/ghost-sim oem --scenarios 10000 --seed 42 --workers 8
   ```

3. **Build the Debug APK:**
   ```bash
   ./gradlew assembleDebug
   ```

4. **Install on Device:**
   ```bash
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
   ```

---

## Real-World Performance & Resource Budgets

Measured directly on hardware (Pixel 7 / Android API 34) and verified via the virtual torture harness:

| Metric | Measured Value | Notes |
|---|---|---|
| **APK Size** | ~46 MB debug | Unstripped dual-ABI binaries (`arm64-v8a` + `x86_64`). Release ~18 MB. |
| **Cold Start** | ~1.5 seconds | Time to first interactive frame on OLED display. |
| **Perceived Send ACK**| **<1 ms** | Optimistic in-memory bubble append before async DB/radio dispatch. |
| **Lazy List Scroll**| **60 FPS** | Zero Room queries, zero Base64 decode, and zero SHA-256 during scroll. |
| **Direct 1:1 Latency** | 2–4 seconds | GATT connect $\rightarrow$ MTU 512 negotiate $\rightarrow$ service discover $\rightarrow$ write. |
| **Cell Group Fanout** | 3–6 seconds | Pairwise unicast loop for 4–8 members over local radio. |
| **Single Envelope Size**| ~281 bytes | Fits inside a single ATT write without L2CAP fragmentation. |
| **Battery Consumption**| 1.5–2.0% / hr | In default `ECO` mode. Drops to <0.5%/hr in `CRITICAL` mode. |
| **Simulation Throughput**| **>16,000 scen/s** | Parallel execution across 8 cores in discrete virtual time. |
| **Codebase Size** | ~14,800 LOC | Kotlin: ~8.4k LOC, Go: ~5.3k LOC, Rust: ~0.3k LOC, Tests/Sim: ~3.2k LOC. |

---

## Security Assessment & Realities

GHOST provides genuine cryptographic confidentiality and integrity, but we do not make false marketing claims:

- **What is protected:** Content confidentiality (X25519 + AES-256-GCM), sender integrity (Ed25519 signatures), pairwise group isolation (relays cannot decrypt group envelopes), and replay resistance (deterministic signature cache).
- **What is NOT protected:** Physical radio direction-finding (an adversary with a directional antenna can locate a transmitting Bluetooth radio), operating-system-level compromise (a rooted phone with malware extracts keys from app-private storage), and passive traffic timing analysis.

For complete details on attack vectors, mitigations, and physical boundaries, read the [Threat Model](docs/security/threat-model.md).

---

## Documentation Index

A comprehensive catalog is available in the [Documentation Master Index](docs/README.md).

- **Release & Status:**
  - [v0.4 Implementation Status](docs/releases/GHOST-v0.4-Status.md) — Production hardening, GATT queue, and 4-tier verification results.
  - [v0.3 Status Report](docs/releases/GHOST-v0.3-Status.md) — Implementation details for Protest Mode and Cell Groups.
  - [Changelog](CHANGELOG.md) — Complete release history across all versions.
  - [Whitepaper](docs/whitepaper.md) — Protocol architecture, threat model comparison, and mathematical foundations.
- **Verification & Torture Engines:**
  - [Simulation Architecture](docs/testing/simulation.md) — 4-Stage verification hierarchy from virtual mesh to OEM runtime ($O_1..O_{24}$).
  - [Extreme Mesh Torture Engine](docs/testing/torture-testing.md) — Methodology, 15 routing invariants ($I_1..I_{15}$), and edge-case hardening ($I_6, I_7$).
- **Architecture & Specifications:**
  - [System Architecture](docs/architecture/system-diagram.md) — Complete system diagrams, GATT queues, and event flows.
  - [Threat Model](docs/security/threat-model.md) — Realistic security analysis, radio attack surfaces, and OEM hostility.
  - [Performance Budgets](docs/architecture/performance-budgets.md) — Frame time, perceived ACK, battery curves, and memory limits.
- **Developer & API References:**
  - [Kotlin FFI Reference](docs/api/kotlin-ffi-bridge.md) — JNI and gomobile bridge interfaces.
  - [Go Package API](docs/api/go-package-api.md) — `ghostrouter`, `sim`, `torture`, `ux`, and `oem` packages.
  - [Rust Crate API](docs/api/rust-crate-api.md) — Pure Rust core and panic-safe JNI bindings.
  - [RFC Index](docs/rfc/rfc-007-application.md) — RFC specifications covering physics, privacy, transport, routing, identity, economy, and application layers.
- **Governance & Engineering:**
  - [Repository Ownership](docs/engineering/repository-ownership.md) — Subsystem boundaries and review gates.
  - [Contributing Guide](CONTRIBUTING.md) — Engineering standards and pull request workflows.
  - [Security Policy](SECURITY.md) — Vulnerability disclosure and security contacts.

---

## License

MIT
