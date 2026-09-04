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
  <img src="https://img.shields.io/badge/version-v0.3.5-orange" alt="Version v0.3.5">
</p>

---

GHOST is an offline mesh messenger built for environments where cellular infrastructure is severed, heavily monitored, or nonexistent: protests, disaster zones, remote expeditions, or internet blackouts.

Two devices in Bluetooth range communicate directly peer-to-peer. Devices out of range communicate through multi-hop store-and-forward routing (Spray-and-Wait). Packets hop through intermediate phones without relays ever seeing message content, sender identities, or recipient public keys.

We built this across three distinct layers: **Kotlin** handles Android BLE GATT mechanics and Jetpack Compose UI, **Go** runs the embedded delay-tolerant router with BoltDB persistence, and **Rust** executes all cryptographic operations across JNI.

---

## What Actually Sets GHOST Apart

Most "offline" mesh apps fall apart under real physical inspection:
- **Bridgefy** claims mesh encryption, but its group and broadcast modes send plaintext directly over the air. Anyone running a packet sniffer or the app itself reads your messages in real time.
- **BitChat** ties identities to Nostr `npub` keys, exposing persistent tracking IDs and falling back to internet relay servers.
- **Briar** is cryptographically sound but drains batteries fast (~15%/day continuous scan) and suffers from unbounded forum replication.

GHOST is engineered around real physical constraints:

1. **Pairwise Encrypted Cell Groups (v0.3.5):** Private group chat for up to 8 members. No cleartext broadcast, no open IRC channels. Every outgoing group message generates separate pairwise envelopes encrypted with fresh ephemeral X25519 keys for each member. Intermediate carrier nodes cannot read anything.
2. **Four Dynamic Security Postures (v0.3.0):**
   - `NORMAL`: Standard privacy. Discovery requires in-person QR scans. Default battery-saving BLE duty cycles.
   - `PROTEST`: High-readiness mesh. Scan rate increased to 1000ms / 200ms window. Background one-tap peer discovery active.
   - `EMERGENCY`: Continuous low-latency scanning (100% duty cycle). 100ms advertising bursts. Immediate packet forwarding.
   - `STEALTH`: Radio silence. BLE advertising transmitter completely killed. Passive receiver only (listen without beaconing).
3. **Three Verification Methods:**
   - **In-Person QR:** Zero-trust cryptographic key exchange with hardware dual-pulse heartbeat haptic confirmation.
   - **One-Tap Nearby Discovery (0x10/0x11):** Detects nearby peers in Protest Mode and sends authenticated consent handshakes without camera alignment.
   - **24-Hour Rotating BIP-39 Short Codes (0x20-0x23):** 3-word + 4-digit code (e.g., `LION - COBALT - HARBOR - 4821`) derived deterministically from the device's private seed and the current UTC epoch day. Can be spoken or written on a board across a crowd. Rotates automatically at midnight UTC.
4. **Battery-Aware Duty Cycles:** Continuous BLE scanning kills an Android battery in under 4 hours. GHOST's `PowerPolicyEngine` dynamically throttles duty cycles across `ACTIVE`, `ECO`, `CRITICAL`, and `DEEP_SLEEP` modes. If battery drops below 20%, the phone sheds transit relaying burdens to keep the device alive.
5. **Deterministic Dedup (RFC 8032):** In continuous scanning modes, duplicate radio packets arrive multiple times per second. GHOST deduplicates on 64-byte Ed25519 signatures over `(pubkey + plaintext)`, preserving deduplication across re-encryptions and multi-hop carrier relays.

---

## Wire Protocol Opcodes

GHOST demuxes incoming GATT payloads at byte 0. Opcodes `0x10` through `0x23` are handled purely at the Kotlin layer for immediate local consent, while `0x01` and `0x30` route through the Go mesh engine:

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

---

## Technical Architecture

```
+-------------------------------------------------------------------------+
|                        ANDROID APPLICATION LAYER                        |
|                                                                         |
|   Jetpack Compose UI          SecurityPostureManager     PowerEngine    |
|   - ContactList (CELL chip)   - NORMAL / PROTEST         - ACTIVE / ECO |
|   - GroupChat / GroupCreate   - EMERGENCY / STEALTH      - CRITICAL     |
|   - HexagonAvatar / HUD       - 15% Battery Revert       - DEEP SLEEP   |
|                                                                         |
|   DiscoveryManager (0x10/0x11)   ShortCodeManager (0x20-0x23)           |
|   - 1-Tap Consent Handshake      - BIP-39 2048-Word Dictionary          |
|   - 20s Per-MAC Throttle         - UTC Midnight Key Rotation            |
|                                                                         |
|   Group Messaging Engine (0x30)  Room DB (Schema v7)                    |
|   - GroupMessageSender           - contacts                             |
|   - GroupMessageReceiver         - messages                             |
|   - Deterministic Sig Dedup      - groups & group_messages              |
|                                  - telemetry_snapshots                  |
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

2. **Run Unit Tests:**
   ```bash
   # Android / Kotlin unit tests (Room, GroupProtocol, ShortCodes)
   ./gradlew testDebugUnitTest

   # Go router mesh engine tests (15/15 tests)
   cd go/ghostrouter && go test -v ./...
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

Measured directly on hardware (Pixel 7 / Android API 34):

| Metric | Measured Value | Notes |
|---|---|---|
| **APK Size** | ~46 MB debug | Unstripped dual-ABI binaries (`arm64-v8a` + `x86_64`). Release ~18 MB. |
| **Cold Start** | ~1.5 seconds | Time to first interactive frame on OLED display. |
| **Direct 1:1 Latency** | 2–4 seconds | GATT connect $\rightarrow$ MTU 512 negotiate $\rightarrow$ service discover $\rightarrow$ write. |
| **Cell Group Fanout** | 3–6 seconds | Pairwise unicast loop for 4–8 members over local radio. |
| **Single Envelope Size**| ~281 bytes | Fits inside a single ATT write without L2CAP fragmentation. |
| **Battery Consumption**| 1.5–2.0% / hr | In default `ECO` mode. Drops to <0.5%/hr in `CRITICAL` mode. |
| **Codebase Size** | ~10,200 LOC | Kotlin: ~7.6k LOC, Go: ~1.7k LOC, Rust: ~0.3k LOC, Tests: ~0.6k LOC. |

---

## Security Assessment & Realities

GHOST provides genuine cryptographic confidentiality and integrity, but we do not make false marketing claims:

- **What is protected:** Content confidentiality (X25519 + AES-256-GCM), sender integrity (Ed25519 signatures), pairwise group isolation (relays cannot decrypt group envelopes), and replay resistance (deterministic signature cache).
- **What is NOT protected:** Physical radio direction-finding (an adversary with a directional antenna can locate a transmitting Bluetooth radio), operating-system-level compromise (a rooted phone with malware extracts keys from app-private storage), and passive traffic timing analysis.

For complete details on attack vectors, mitigations, and physical boundaries, read the [Threat Model](docs/architecture/threat-model.md).

---

## Documentation Index

- [Whitepaper](docs/whitepaper.md) — Protocol architecture, threat model comparison, and mathematical background.
- [v0.3 Status Report](docs/GHOST-v0.3-Status.md) — Implementation details for Protest Mode and Cell Groups.
- [Changelog](CHANGELOG.md) — Complete release history across all versions.
- [System Architecture](docs/architecture/system-diagram.md) — Component diagrams, sequence flows, and wire layouts.
- [Threat Model](docs/architecture/threat-model.md) — Realistic security analysis and boundary definitions.
- [Performance Budgets](docs/architecture/performance-budgets.md) — Battery curves, radio timing, and memory allocation.
- [Kotlin FFI Reference](docs/api/kotlin-ffi-bridge.md) — JNI and gomobile bridge interfaces.

---

## License

MIT
