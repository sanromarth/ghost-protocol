# GHOST Protocol Threat Model

- **Author:** PEDDI SANKARA RAO
- **Version:** v0.4.3
- **Date:** September 5, 2026
- **Scope:** Threat actors, cryptographic assumptions, protocol mitigations, and residual risks

---

## 1. Threat Actors and Mitigations

| Threat Actor | Capabilities | Status | Engineering Mitigations & Constraints |
|---|---|---|---|
| **Passive RF Sniffer** | Captures 2.4GHz BLE advertising and GATT packets | Mitigated | Payloads encrypted via X25519 ECDH + AES-256-GCM. Relays cannot decrypt transit blobs. |
| **Active Radio Scanner / Direction Finder** | Locates transmitting Bluetooth radios with directional antennas | Partially Mitigated | In `STEALTH` posture, the advertising transmitter is disabled (listen-only). In `NORMAL`/`PROTEST`, transmitting radio emissions can be physically located within RF range (~10–30m). |
| **Malicious Mesh Relay (Carrier)** | Stores and sprays transit messages; attempts to read or tamper | Mitigated | Carrier nodes see only opaque blobs. AEAD tags detect bit-flipping; Ed25519 signatures authenticate senders. In Cell Groups, pairwise envelopes ensure relays cannot inspect member rosters. |
| **Replay / Flooding Attacker** | Replays captured BLE packets to waste battery or duplicate bubbles | Mitigated | Receiver deduplicates using persistent SQLite tables and deterministic 64-byte Ed25519 digital signatures over `(pubkey + plaintext)` (RFC 8032), invariant across destination reboots ($I_6$). |
| **Probe / Scanner Attack** | Broadcasts random short code queries to discover active identities | Mitigated | `ShortCodeManager` silently drops mismatched query hashes. Zero radio packets are transmitted in response to an incorrect probe. |
| **Hostile Mobile OS / Task-Killer** | LMKD kills processes, vendor battery savers suppress wake locks, and BLE stacks drop connections with GATT 133 | Mitigated | Foreground service with `START_STICKY` + AlarmManager restart; serialized `GattOperationQueue` with 150ms MAC cool-off; atomic Room status guard `AND (status != 2 OR :status = 2)`. |
| **Compromised Phone (Physical Extraction)** | Law enforcement or adversary seizes unlocked or rooted phone | Not Mitigated | Keys are stored in Android app-private storage (`/data/data/com.ghostprotocol/shared_prefs/`). A rooted device or unlocked extraction allows reading the database directly. Duress PIN or database encryption is not currently implemented. |
| **Traffic Timing & Proximity Analyst** | Analyzes packet timestamps and device proximities | Not Mitigated | No cover traffic or fixed-size packet morphing exists. An adversary monitoring a physical area can correlate message exchange timing and BLE fingerprints. |
| **Quantum Adversary** | Captures traffic for post-quantum decryption | Not Mitigated | Classical X25519 / Ed25519 only. Hybrid post-quantum key encapsulation is not implemented. |

---

## 2. What v0.3.7 Protects

### 2.1 Confidentiality & Forward Secrecy
- **Every Message Encrypted:** Plaintext is encrypted using X25519 ECDH key agreement combined with AES-256-GCM authenticated encryption.
- **Fresh Ephemeral Keys:** A new ephemeral X25519 keypair is generated for every single message. Compromise of an identity key does not retroactively decrypt past intercepted sessions.
- **Pairwise Group Isolation:** Unlike Bridgefy (which broadcasts group messages in cleartext) or BitChat (which posts to public channels), GHOST Cell Groups encrypt separate envelopes per member. Intermediate carriers carrying group bundles see only opaque payloads and cannot determine who else is in the group.

### 2.2 Authenticity & Deduplication Invariants
- **Deterministic Digital Signatures (RFC 8032):** Senders sign `(senderEd25519Pub || plaintext)` using Ed25519. Digital signatures are verified by the recipient before saving to disk.
- **Why Ciphertext Hashing Failed:** Earlier alpha builds attempted to deduplicate on `SHA-256(ciphertext)`. Because each transmission generates fresh ephemeral keys and AES nonces, ciphertexts were completely different, causing duplicate messages to slip past the filter under continuous BLE scanning. Signing over canonical plaintext produces an invariant 64-byte signature that catches duplicates across re-encryptions and multi-hop carrier relays.

### 2.3 Receipt Authenticity & Loop Prevention (v0.3.7)
- **Signed Delivery Acknowledgments:** Opcode `0x40` delivery receipts are authenticated by the recipient's Ed25519 signature over the computed message content hash `SHA-256(senderId || timestamp || plaintext)`. An attacker cannot forge receipts without possessing the recipient's private Ed25519 seed.
- **Terminal Packet Dispatch:** Opcode `0x40` packets never trigger delivery receipts themselves, preventing acknowledgment cascades. System event notices (`* ` prefix) are similarly filtered.

### 2.4 Cryptographic Vouching & One-Way Trust (v0.3.6)
- **Voucher Authentication:** Opcode `0x50` introduction envelopes are signed by the introducing contact (Alice). Carol validates Alice's signature against her pinned contact list before presenting the contact for review. Unverified vouchers or tampered signatures are dropped silently.
- **Honest Trust Boundaries:** Introduced contacts are strictly one-way: Bob is not notified and does not receive Carol's public keys. Introduced contacts are persisted with `isIntroduced = true, isVerified = false` (slate avatar ring, `INTRODUCED` chip) and never receive the violet Ghost Aura until mutually verified via QR or Discovery.

### 2.5 Denial-of-Sleep & Radio Protection
- **Relay Load Shedding:** When device battery drops below 20%, `PowerPolicyEngine` sets `relayWillingness = 0.0` in the Go router. The node stops accepting forwarded messages from other phones, acting strictly as an edge node to keep the device alive.
- **20-Second Per-MAC Discovery Limiter:** In `PROTEST` mode, background discovery packets (`0x10`) are limited to 3 per minute per MAC address, preventing an adversary with a laptop from spamming notifications across a crowd.

### 2.6 Client Threading & Memory Safety (v0.3.8)
- **Asynchronous In-Memory Bubble Isolation:** State mutation for optimistic sending is scoped strictly within `ChatViewModel`'s `StateFlow`. Message text is held in transient memory until the background coroutine completes Room DB insertion and AES-256-GCM encryption. UI updates do not block database transactions.
- **Scroll Memory & ANR Prevention:** Eliminating `MessageDigest` and `Base64` calls inside `LazyColumn` item composition blocks prevents Application Not Responding (ANR) lockups under high-rate radio burst ingress.
- **Permission Least Privilege:** Removing `CAMERA` from Android startup manifests ensures the app does not hold an ambient hardware camera handle in the background, only requesting camera access upon explicit user navigation to the QR scanner.

### 2.7 Mobile OS Runtime Boundary Mitigations (v0.4.0–v0.4.3)
- **BLE Controller Collision & GATT 133 Mitigation:** Android BLE controller race conditions are mitigated via `GattOperationQueue`'s strict single-connection FIFO serialization and 150ms cool-off per MAC address.
- **Terminal Delivery Invariance ($O_6$):** Delayed transport callbacks or out-of-order delivery receipts cannot rollback a `DELIVERED` message back to `SENT` or `PENDING` due to the Room atomic status guard `AND (status != 2 OR :status = 2)`.
- **Persistent Reboot Deduplication ($I_6$):** Delivery deduplication records survive process termination, LMKD kills, and device reboots via persistent SQLite storage, preventing redundant delivery from multi-carrier sprays.
- **Native Panic Shielding ($O_{15}$):** Rust FFI calls are guarded by `std::panic::catch_unwind`, translating memory safety faults or slicing panics into clean JVM runtime exceptions rather than crashing the operating system process.
- **Background Survival ($O_2, O_3$):** `GhostService` runs as an ongoing `connectedDevice` foreground service with `START_STICKY` and an `AlarmManager` wake-up intent scheduled on `onTaskRemoved`, enabling recovery from background process termination.

---

## 3. What v0.4.3 Does NOT Protect (Known Limitations)

### 3.1 Metadata Leakage Over BLE
- **4-Byte Key Fingerprint:** To match peers without initiating a GATT connection, devices advertise a 4-byte hash: `SHA-256(ed25519Pub)[0..3]`. While Android rotates MAC addresses, this 4-byte fingerprint remains constant across advertisements until posture is changed. A stationary BLE sniffer can track when this fingerprint enters and leaves radio range.
- **Packet Length Correlation:** Messages are not padded to fixed sizes (e.g. 512-byte blocks). An attacker inspecting packet sizes can correlate that a 120-byte write corresponds to a short acknowledgment, while an 800-byte write corresponds to a multi-member group envelope.

### 3.2 Physical & OS Vulnerabilities
- **Device Seizure:** All keys reside in application storage. If an activist is detained with their phone unlocked, the messaging database is directly readable.
- **No Remote Wipe:** GHOST has no internet connection, so remote wipe is physically impossible. A manual "Panic Button" exists in settings, but it requires user action.

---

## 4. Group Chat Security Comparison

| Metric | Bridgefy | BitChat | Briar | GHOST Cell Groups (v0.3.5) |
|---|---|---|---|---|
| **Group Mode Encryption** | ❌ **Cleartext Broadcast** (anyone in range reads it) | ⚠️ Channel password / open Nostr | ✅ E2E encrypted | ✅ **Pairwise E2E per member** |
| **Relay Privacy** | ❌ Relays read plaintext | ❌ Relays read Nostr events | ⚠️ Relays store full forum | ✅ **Relays handle opaque envelopes** |
| **Roster Exposure** | Exposed to all nearby radios | Exposed on public relays | Shared with all forum subscribers | **Hidden** (envelopes addressed individually) |
| **Storage Amplification** | N/A | High (Nostr relays) | ❌ **Severe** (unbounded forum replication) | ✅ **Bounded** (max 8 members, 48h auto-pruning) |
| **Battery Impact** | ❌ Uncontrolled (phone dies in ~3h) | ⚠️ Medium | ❌ Heavy (~15%/day) | ✅ **Throttled by PowerPolicyEngine** |

---

## 5. Security Postures & Defensive Use

| Posture | BLE Radio State | Threat Mitigation | Recommended Scenario |
|---|---|---|---|
| `NORMAL` | Standard Duty Cycle (2000ms scan / 500ms adv) | Balanced power and privacy; requires in-person QR exchange | Daily walking around, routine communication |
| `PROTEST` | High-Duty Cycle (1000ms scan / 200ms adv) | Rapid nearby contact discovery without physical camera alignment | Protests, fast-moving crowds, rallies |
| `EMERGENCY` | Continuous Low-Latency (100% duty, 100ms adv) | Maximum packet delivery speed across high-density mesh | Blackouts, earthquakes, search & rescue |
| `STEALTH` | **Zero Radio Transmission** (TX killed, RX only) | **Eliminates RF detection** by police direction-finding equipment | Evading kettling, hiding, checkpoint traversal |
