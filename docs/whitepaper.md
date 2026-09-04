# GHOST Protocol: An Offline Encrypted Mesh Messenger for Infrastructure-Denied Environments

**Version 0.3.5 — September 2026**

**Authors:** GHOST Protocol Research Group  
**Repository:** [github.com/sanromarth/ghost-protocol](https://github.com/sanromarth/ghost-protocol)

---

## Abstract

GHOST (Global Hybrid Offline Secure Transport) is an offline mesh messaging system for Android that enables cryptographically secure communication in infrastructure-denied environments: internet blackouts, active protests, remote expeditions, and natural disaster zones. GHOST operates over Bluetooth Low Energy (BLE) 5.0 and provides end-to-end encrypted messaging using X25519 key agreement, AES-256-GCM authenticated encryption, and Ed25519 digital signatures.

Messages are routed through an embedded Spray-and-Wait epidemic routing engine implemented in Go with BoltDB persistence, enabling multi-hop delay-tolerant delivery through intermediate carrier nodes when sender and recipient are not within direct radio range. 

**v0.3.5 introduces Protest Mode and Pairwise-Encrypted Cell Groups**:
1. **Dynamic Security Postures:** 4 runtime postures (`NORMAL`, `PROTEST`, `EMERGENCY`, `STEALTH`), including passive-only radio silence (`STEALTH`) to evade physical RF direction-finding equipment.
2. **Frictionless Contact Discovery:** One-tap mutual consent BLE handshakes (opcodes `0x10`/`0x11`) and 24-hour rotating BIP-39 short codes (opcodes `0x20`–`0x23`) derived deterministically from the device's private seed and UTC epoch day, slashing setup latency from 30+ seconds to under 3 seconds without camera alignment.
3. **Private Cell Groups:** Secure group messaging for up to 8 verified members using individual pairwise unicast envelopes (opcode `0x30`). Eliminates the cleartext broadcast leakage of Bridgefy, the public channel exposure of BitChat, and the unbounded forum storage replication of Briar.
4. **Physical Power-Aware Mesh Management:** Centralized `PowerPolicyEngine` governing BLE duty cycles across 4 operating modes (`ACTIVE`, `ECO`, `CRITICAL`, `DEEP_SLEEP`), single-session GATT message batching (cutting connection radio time by ~70%), and automatic relay shedding when battery drops below 20%.

The codebase comprises approximately 10,200 lines across three languages: Kotlin (~7.6k LOC: UI, BLE, Postures, Room DB v7), Go (~1.7k LOC: mesh routing and BoltDB storage), and Rust (~0.3k LOC: cryptography via JNI). All capabilities have been empirically verified across physical Android hardware.

---

## 1. Introduction & The Problem

### 1.1 The Fragility of Centralized Networks

Contemporary secure messaging platforms rely entirely on centralized cloud infrastructure. WhatsApp requires live TCP connections to Meta servers. Signal depends on Amazon Web Services for message relaying and Google Firebase for push wakeups. Telegram routes all traffic through corporate server clusters.

When authoritarian regimes impose internet shutdowns or natural disasters destroy cellular towers, these applications fail completely. Between 2016 and 2025, governments severed internet connectivity on over 1,190 documented occasions across 84 nations. During the 2021 Myanmar coup, mobile data was cut off for 72 consecutive days. During the 2023 earthquakes in Turkey and Syria, 4,500 cell towers were rendered inoperative while survivors were trapped under rubble.

A communication system that presumes a functioning IP layer cannot function when that layer is severed.

### 1.2 Prior Work & Where It Bleeds

Existing offline mesh messaging applications fall into severe engineering traps:

- **Bridgefy:** Claims mesh capabilities, but its group and broadcast modes send **cleartext packets with zero encryption**. As demonstrated by security audits, any adversary running Bridgefy in a crowd can read all broadcast messages in real time. Furthermore, its continuous radio scanning drains phone batteries in ~3 hours.
- **BitChat:** Implements Bluetooth mesh with Nostr identities. However, BitChat exposes persistent public `npub` keys over public Nostr relays, enabling global metadata tracking, and relies on public IRC-style channels (`#mesh`).
- **Briar:** Cryptographically sound, but suffers from *empty-room paralysis* (nodes cannot relay without explicit contact relationships), *storage explosion* (unbounded forum replication across all subscribed phones), and *battery drain* (~15% daily drain from unthrottled scanning).

### 1.3 The GHOST Architecture

GHOST takes a pragmatic engineering approach: **implement audited cryptographic primitives, bound mesh amplification, and strictly enforce physical energy constraints.**

| Dimension | Briar | Bridgefy | BitChat | GHOST v0.3.5 |
|---|---|---|---|---|
| **E2E Encryption** | ✅ E2E (Bramble) | ❌ Partial (Broadcast is cleartext) | ⚠️ Channel password | ✅ **X25519 + AES-256-GCM** |
| **Group Chat Model** | Unbounded Forums | Plaintext Broadcast | Public `#mesh` channels | ✅ **Pairwise Envelopes (max 8)** |
| **Multi-Hop Relay** | ❌ (Contacts only) | ✅ Unicast relay | ✅ Flood mesh | ✅ **Bounded Spray-and-Wait ($L=4$)** |
| **Power Management** | ❌ Static (~15%/day) | ❌ Unmanaged (~3h life) | ⚠️ Unmanaged | ✅ **4 Dynamic Modes (0.2–4%/hr)** |
| **Relay Load Shedding** | ❌ None | ❌ None | ❌ None | ✅ **Drops relaying if battery < 20%** |
| **In-Range Discovery** | QR Code only | Auto-add all | Nostr `npub` / QR | ✅ **1-Tap Consent + 24h BIP-39 Codes** |
| **RF Stealth Mode** | ❌ None | ❌ None | ❌ None | ✅ **TX Killed (Passive RX only)** |
| **Google Play Services**| Optional | Required | Optional | ✅ **Zero dependencies (Pure AOSP)** |

---

## 2. Cryptographic Architecture

GHOST relies exclusively on established, standardized cryptography implemented in Rust (`rust/ghost-crypto/`):
- **Key Agreement:** X25519 (RFC 7748) for Diffie-Hellman ephemeral shared secret derivation.
- **Authenticated Encryption:** AES-256-GCM (NIST SP 800-38D) with a unique 12-byte random nonce per encryption.
- **Digital Signatures:** Ed25519 (RFC 8032) for sender authentication and message integrity.
- **Identity Derivation:** Senders compute a 16-character hex Contact ID: `SHA-256(ed25519Pub)[0..7].toHex()`.

### 2.1 Deterministic Signature Deduplication Invariant
In dense mesh environments operating with continuous BLE scanning, identical radio packets arrive multiple times per second from different relay paths.

GHOST enforces deduplication by hashing the canonical **64-byte Ed25519 digital signature** over `(senderEd25519Pub || plaintext)`. Under RFC 8032, Ed25519 signatures are deterministic. Even if a sender re-encrypts a message with fresh ephemeral keys or different carrier nodes relay it, the signature over canonical plaintext remains invariant, guaranteeing instant dropping of duplicate deliveries within a 60-second sliding window.

---

## 3. Protest Mode & Ephemeral Discovery

To enable rapid connection establishment in hostile crowds without forcing users to stand still and align cameras, GHOST provides three distinct discovery channels:

1. **Reciprocal Cryptographic QR:** Zero-trust face-to-face exchange with hardware dual-pulse heartbeat haptics.
2. **One-Tap Nearby Discovery (Opcodes `0x10`/`0x11`):** In `PROTEST` posture, phones detect nearby 4-byte advertisement fingerprints and trigger authenticated GATT handshakes. A 20-second per-MAC rate limiter prevents notification flooding.
3. **24-Hour Rotating BIP-39 Short Codes (Opcodes `0x20`–`0x23`):**
   - Derived deterministically: `HMAC-SHA256(ed25519Seed, "GHOST_BIP39_SHORTCODE_V1" || epochDay)`.
   - Produces 3 BIP-39 words + a 4-digit suffix (`seed[6..7] % 10000`), yielding $\approx 8.6 \times 10^{13}$ combinations.
   - Rotates automatically at midnight UTC. Mismatched code probes are silently dropped to prevent adversary scanning.

---

## 4. Cell Groups: Private Group Architecture

GHOST v0.3.5 rejects the broadcast-cleartext approach of Bridgefy and the public-channel model of BitChat in favor of **Cell Groups**:

```
+-------------------------------------------------------------------------------+
|                      CELL GROUP FAN-OUT ARCHITECTURE                          |
|                                                                               |
|  Sender -> Group Roster (up to 8 verified members):                           |
|                                                                               |
|  [Member 1 (In Direct Range)]  <--- Direct GATT Write --- [Envelope 1 (281B)] |
|                                                                               |
|  [Mesh Carrier (Intermediary)] <--- BoltDB Spray (L=4) -- [Envelope 2 (281B)] |
|               |                                                               |
|               v                                                               |
|  [Member 2 (2 Hops Away)]      <--- Delivered via Mesh -- [Envelope 2 (281B)] |
+-------------------------------------------------------------------------------+
```

### Architectural Guarantees:
- **Pairwise Isolation:** Every outgoing message is individually encrypted with fresh ephemeral X25519 keys for each member. An intermediate relay carrying an envelope cannot read its content, inspect the member list, or determine which other members received it.
- **Strict Size Budgeting:** Each envelope is approximately 281 bytes, well under the 509-byte ATT MTU threshold, eliminating packet fragmentation failures.
- **Bounded Mesh Amplification:** Group size is capped at 8 members, ensuring network traffic scales as $O(N \cdot L)$ rather than unbounded epidemic replication.
- **Storage Lifecycle:** Group messages are auto-pruned from Room DB at 48 hours rolling retention.

---

## 5. Deployment & Hardware Verification

GHOST runs as a single, standalone debug APK (~46 MB including dual ABIs; release build ~18 MB). It requires no internet permissions, no account setup, and zero Google Play Services.

Verified operational parameters on physical hardware:
- **Direct BLE Latency:** 2–4 seconds.
- **Cell Group Fanout:** 4–6 seconds (3 peers).
- **Battery Drain (ECO):** ~1.5–2.0% per hour.
- **Battery Drain (CRITICAL):** <0.5% per hour.
- **Cold Start:** ~1.5 seconds.
- **Database:** Room schema version 7 (`GhostDatabase`).

---

## References

1. Access Now, "Internet shutdowns in 2023," accessnow.org/keepiton, 2024.
2. Briar Project, "Briar: Secure messaging, anywhere," briarproject.org.
3. Open Garden, "Bridgefy: Offline Messaging," bridgefy.me.
4. Jack Dorsey et al., "BitChat: BLE mesh messaging with Nostr identity," github.com/nicobao/bitchat, 2025.
5. D. J. Bernstein, N. Duif, T. Lange, P. Schwabe, and B.-Y. Yang, "High-speed high-security signatures," J. Cryptographic Engineering, 2012.
6. D. J. Bernstein, "Curve25519: new Diffie-Hellman speed records," PKC 2006.
7. NIST, "FIPS 197: Advanced Encryption Standard," 2001.
