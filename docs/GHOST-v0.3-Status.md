# GHOST Protocol — v0.3 Implementation Status

> **Last updated:** 2026-09-04  
> **Version:** v0.3.7  
> **Build:** `app-debug.apk` (~46 MB)  
> **Kotlin Tests:** Passing (`./gradlew testDebugUnitTest` in 7s)  
> **Go Tests:** 15/15 passing (`go test -v ./...` in <0.05s)  
> **Database:** Room schema version 9 (`GhostDatabase`)  
> **Status:** ✅ Completed — Protest Mode Security Postures, Frictionless Discovery, Rotating BIP-39 Codes, Pairwise Cell Groups, One-Way Contact Introductions, and Cryptographic Delivery Receipts fully integrated.

---

## 1. What v0.3.0 Through v0.3.7 Accomplished

The v0.1 and v0.2 releases gave GHOST a solid foundation: delay-tolerant Spray-and-Wait routing in Go, X25519/Ed25519 cryptography in Rust, single-session GATT message batching, and dynamic battery policy management.

However, real-world deployment in hostile or high-pressure environments revealed four critical gaps:
1. **The In-Person QR Bottleneck:** In protests, disaster zones, or crowded spaces, requiring users to stand 10cm apart with steady phone cameras to scan 64-byte QR codes took 30+ seconds and frequently failed under movement, cracked screens, or poor lighting.
2. **The Group Messaging Dilemma:** Existing mesh apps handle groups poorly. Bridgefy sends group messages via unencrypted cleartext broadcast; BitChat exposes persistent Nostr keys on public relays; Briar replicates forums unboundedly, exhausting phone storage.
3. **The Transport ACK Blindspot:** A GATT write acknowledgment (`STATUS_SENT` / `✓`) only proves the local radio pushed bytes into the peer's Bluetooth stack. It does not prove the peer decrypted the payload, committed it to disk, or rendered it.
4. **The Isolated Cell Problem:** Activists within the same operational unit could not introduce peers to one another without physical in-person QR scans.

The v0.3 releases addressed each of these directly:
- **Protest Mode (v0.3.0):** 4 selectable security postures (`NORMAL`, `PROTEST`, `EMERGENCY`, `STEALTH`), one-tap nearby discovery handshakes (opcodes `0x10`/`0x11`), and 24-hour rotating BIP-39 short codes (opcodes `0x20`–`0x23`).
- **Cell Groups (v0.3.5):** Private, verified group messaging for 2–8 members with pairwise E2E encryption per member envelope (opcode `0x30`), room schema v7, and zero Go/Rust changes.
- **Contact Introductions / Trust Web (v0.3.6):** One-way cryptographic vouching (opcode `0x50`) allowing Alice to introduce Bob to Carol via signed encrypted envelopes. Visual distinction (`INTRODUCED` chip, slate border) preserves trust boundaries without granting the violet Ghost Aura until mutual QR verification. Room schema v9.
- **Delivery Receipts (v0.3.7):** Cryptographic E2E delivery acknowledgments (opcode `0x40`) with double checkmarks (`✓✓`), deterministic content hash matching, and Room schema v8.

---

## 2. Wire Protocol Specification

All radio traffic demuxes on byte 0 in Kotlin. Packets for discovery, short codes, and group messages never touch or complicate the Go router's store-and-forward tables:

```
+-----------------------------------------------------------------------------------------+
|                               GHOST PROTOCOL WIRE PACKETS                               |
+-----------------------------------------------------------------------------------------+

0x01: Go Router 1:1 Spray-and-Wait Message
[4B: Header Length BE] [JSON Routing Header] [AES-256-GCM Ciphertext]

0x10: Nearby Contact Request (Discovery)
[1B: 0x10] [32B: ed25519Pub] [32B: x25519Pub] [8B: timestamp] [UTF-8 Name] [64B: signature]

0x11: Nearby Contact Response (Discovery)
[1B: 0x11] [32B: ed25519Pub] [32B: x25519Pub] [8B: timestamp] [UTF-8 Name] [64B: signature]

0x20: Direct Short Code Query
[1B: 0x20] [32B: targetCodeHash] [32B: senderEd25519Pub] [64B: signature]

0x21: Direct Short Code Response
[1B: 0x21] [32B: responderEd25519Pub] [32B: responderX25519Pub] [64B: signature]

0x22: Mesh-Routed Short Code Query (Go Router)
[1B: 0x22] [32B: targetCodeHash] [32B: senderEd25519Pub] [64B: signature]

0x23: Mesh-Routed Short Code Response (Go Router)
[1B: 0x23] [32B: responderEd25519Pub] [32B: responderX25519Pub] [64B: signature]

0x30: Cell Group Individual Unicast Envelope
[1B: 0x30] [32B: groupId] [16B: senderContactId] [8B: timestamp] [Ciphertext] [64B: signature]

0x40: Cryptographic Delivery Receipt
[1B: 0x40] [64B: msgHash hex] [16B: recipientContactId] [8B: timestamp] [64B: signature]

0x50: Contact Introduction Envelope
[1B: 0x50] [32B: ed25519Pub] [32B: x25519Pub] [2B: nameLen] [UTF-8 Name] [16B: voucherContactId] [64B: signature]
```

---

## 3. Component Breakdown

### 3.1 Security Posture Engine (`SecurityPostureManager.kt`)
Governs the device's radio exposure and scanning aggressiveness:
- **`NORMAL`**: Standard privacy. Scanning is throttled by `PowerPolicyEngine` (500ms–2000ms). Discovery requires in-person QR exchange.
- **`PROTEST`**: High-readiness mesh. Scan interval 1000ms / 200ms window. Background one-tap BLE discovery is active.
- **`EMERGENCY`**: 100% duty cycle (continuous scanning in `SCAN_MODE_LOW_LATENCY`), 100ms advertising interval. Immediate forwarding.
- **`STEALTH`**: Complete radio silence on TX. BLE advertising is killed. Radio operates in passive receive mode only.
- **Low-Battery Revert:** If the device is in `PROTEST` or `EMERGENCY` and battery drops below 15%, the posture automatically reverts to `NORMAL` to avoid stranding the user with a dead phone.

### 3.2 Nearby Discovery (`DiscoveryManager.kt`)
- Emits opcode `0x10` request upon user tap.
- Enforces a 20-second per-MAC rate limiter (maximum 3 connection attempts per minute per peer) to eliminate notification spam in dense crowds.
- Inserts peers into Room DB with `isVerified = true` upon mutual consent.

### 3.3 24-Hour Rotating BIP-39 Short Codes (`ShortCodeManager.kt`)
- Generates 3 BIP-39 words + a 4-digit number (e.g. `LION - COBALT - HARBOR - 4821`).
- **Derivation:** `HMAC-SHA256(Ed25519 Seed, "GHOST_BIP39_SHORTCODE_V1" || UTC Epoch Day)`.
- 11-bit extraction maps to 2048-word BIP-39 list (`seed[0..1] and 0x7FF`). The 4-digit number is `seed[6..7] % 10000`. Total entropy is $\approx 8.6 \times 10^{13}$ combinations.
- Code rotates automatically at midnight UTC without leaking the underlying private seed.
- Anti-probing defense: Queries for non-matching codes are silently discarded without emitting any radio response.

### 3.4 Cell Groups (`com.ghostprotocol.group.*`)
- Hard limit of 8 members per group (2–8).
- **Pairwise Unicast Envelopes:** Instead of broadcasting cleartext or attempting virtual group routing through the Go engine, outgoing messages loop through members and encrypt separate envelopes using `GhostCrypto.encrypt(memberX25519Pub, wireText)`.
- **Destination:** Each envelope routes to `SHA-256(memberEd25519PubKey)`. In-range members receive direct GATT writes; out-of-range members are queued in Go BoltDB with $L=4$ Spray-and-Wait copies.
- Recipient phones receive standard Go router `OnDeliver` events, demux `0x30`, verify the Ed25519 signature, decrypt the payload, and insert into `group_messages`.

### 3.5 Delivery Receipts (`com.ghostprotocol.receipt.*`)
- Opcode `0x40` wire protocol (153 bytes) sent when destination completes Room DB insertion.
- Content hash: `SHA-256(senderContactId || timestampBE || cleanPlaintext)`.
- Explicit `TS\0timestamp` wire token eliminates clock drift discrepancies between sender and receiver.
- Storm prevention: receipt fires strictly on first delivery (`getByContentHash == null`); opcode `0x40` is terminal (never acknowledged); system notices are suppressed.
- UI status: 1:1 chat displays GhostPurple double checkmark (`✓✓`); group messages display live count (`"Delivered to X/Y"`) with detail bottom sheet.

### 3.6 Contact Introductions (`com.ghostprotocol.introduction.*`)
- Opcode `0x50` wire protocol (147 + N bytes) enabling Alice to vouch for Bob so Carol can add him.
- Cryptographic vouching: Alice signs Bob's Ed25519 and X25519 public keys, name, Carol's contact ID, and Alice's contact ID. Carol verifies the signature against Alice's pinned Ed25519 key.
- Strict one-way trust invariant: Bob is not notified and does not receive Carol's keys. No bidirectional graph synchronization.
- Visual distinction: Introduced contacts are marked with a slate avatar border (`#3F3F46`) and an `"INTRODUCED"` chip. They never receive the violet Ghost Aura until mutually verified via QR or Discovery.
- Chat screen provides a persistent banner with tap-to-verify action for upgrading trust.

---

## 4. The 4x Duplicate Bug: Root Cause & Resolution

During real-device testing in `PROTEST` and `EMERGENCY` modes, sending a single message produced 4 duplicate chat bubbles on the receiving device.

### Investigation & Root Cause
1. **Sender-side Burst:** In low-latency scan modes, Android BLE scan callbacks fire every 100–200ms. In `GhostService.kt`, the encounter check:
   ```kotlin
   if (!hasPendingRoom && (now - lastCall < 10_000)) continue
   ```
   completely skipped the 10-second throttle whenever a message was in `STATUS_PENDING`. This triggered 4 overlapping delivery tasks before the first GATT write even negotiated MTU.
2. **Receiver-side Deduplication Failure:** The receiver checked `SHA-256(ciphertext)`. Because every outgoing transmission generated fresh ephemeral X25519 keys and AES nonces, the ciphertexts were completely different. The ciphertext hash check failed to catch the duplicates.
3. **ChatScreen Redundancy:** On direct GATT send failure, `ChatScreen.kt` invoked `router.sendMessage` a second time, queueing a second copy into Go BoltDB with a different message ID.

### The Fix
- **Deterministic Ed25519 Signature Deduplication (RFC 8032):** The receiver now hashes the 64-byte Ed25519 digital signature over `(senderPubKey + plaintext)`. Because Ed25519 signatures are deterministic, all re-encryptions and relays share the identical signature.
- Added a 60-second sliding signature cache (`recentMessageSignatures`) and a 6-second content cache (`recentMessageContents`).
- Enforced an unconditional 5-second encounter throttle per MAC address.
- Added `deliveringContacts` in-flight concurrency lock to prevent overlapping delivery coroutines.
- Cleaned up redundant fallback calls in `ChatScreen.kt`.

---

## 5. Persistence & Schema Migrations

The Room database has been migrated from version 5 to 9:

```
v5 (messages: +replyToSender, +replyToText)
  │
  ▼ MIGRATION_5_6
v6 (contacts: +isVerified INTEGER NOT NULL DEFAULT 0)
  │
  ▼ MIGRATION_6_7
v7 (new tables: groups, group_messages; indexes on groupId, timestamp, status)
  │
  ▼ MIGRATION_7_8
v8 (messages & group_messages: +contentHash TEXT; group_messages: +deliveredMemberIdsJson TEXT)
  │
  ▼ MIGRATION_8_9
v9 (contacts: +isIntroduced INTEGER NOT NULL DEFAULT 0)
```

### Schema Additions in v8 and v9:
```sql
-- Migration 7 -> 8 (Delivery Receipts)
ALTER TABLE messages ADD COLUMN contentHash TEXT;
CREATE INDEX idx_messages_contentHash ON messages(contentHash);

ALTER TABLE group_messages ADD COLUMN contentHash TEXT;
CREATE INDEX idx_group_messages_contentHash ON group_messages(contentHash);
ALTER TABLE group_messages ADD COLUMN deliveredMemberIdsJson TEXT NOT NULL DEFAULT '[]';

-- Migration 8 -> 9 (Contact Introductions)
ALTER TABLE contacts ADD COLUMN isIntroduced INTEGER NOT NULL DEFAULT 0;
```

---

## 6. Verification Status

| Test Suite | Command | Result | Notes |
|---|---|---|---|
| **Android Unit Tests** | `./gradlew testDebugUnitTest` | ✅ Passed (7s) | Covers Room DAOs, GroupProtocol, ReceiptProtocol, IntroductionProtocol, ShortCode HMAC, and signatures |
| **Go Router Tests** | `cd go/ghostrouter && go test -v ./...` | ✅ Passed (15/15) | Covers BoltDB store, spray routing, batch serializer, and relay willingness gate |
| **Debug APK Build** | `./gradlew assembleDebug` | ✅ Passed (5s) | Clean compilation on API 34 with dual ARM64/x86_64 native binaries |
