# GHOST Protocol — Changelog

All notable changes to the GHOST Protocol project are documented in this file.

---

## [v0.4.4] — 2026-09-05

Cross-device reliability and transport framing release resolving low-end Android BLE write failures, ATT MTU negotiation race conditions, group message retransmission cancellations, reciprocal QR verification races, and truthful status tracking.

### Added
- **Dynamic BLE Transport Framing (`0xFB`, `GattOperationQueue.kt`):**
  - Added link-layer framing protocol (`OPCODE_BLE_FRAGMENT = 0xFB`) to slice payloads exceeding the connection's negotiated ATT MTU.
  - 7-byte wire header: `[1B opcode=0xFB][2B transferId][2B fragIndex][2B totalFrags][chunkPayload]`.
  - Pure slicing function `slicePayload(data, negotiatedMtu)` automatically slices envelopes when `data.size > negotiatedMtu - 3`.
  - Payloads fitting within the negotiated MTU bypass framing entirely for 100% backward compatibility.
- **Inbound Fragment Reassembly State Machine (`BleManager.kt`):**
  - In-memory `ReassemblySession` handling multi-fragment writes per peer MAC.
  - Bounded to 16 concurrent peer sessions with a 30-second inactivity timeout and 64 KB total transfer ceiling.
  - Out-of-order and duplicate fragment delivery handled idempotently.
  - Explicit prohibition against nested `0xFB` frames.
  - Immediate GATT write response returned for every received chunk.
  - Clean session teardown on peer disconnect or service shutdown.
- **Reciprocal QR Verification Queue (`GhostService.kt`, `QRScanScreen.kt`):**
  - Memory-backed `pendingOutboundVerifications` map caching handshake packets when a QR code is scanned before the peer's BLE advertisement has been observed.
  - Automatically dispatches queued verification packets once the peer's MAC is discovered.
  - 5-minute time-to-live with periodic sweep to prevent unbounded memory growth.

### Changed & Fixed
- **HCI Link Stabilization Delay (`GattOperationQueue.kt`):**
  - Added mandatory 100ms post-connect delay before calling `requestMtu(512)`.
  - Prevents immediate HCI timeouts and `GATT 133` disconnect loops on budget Bluetooth chipsets (e.g. MediaTek MT6739, Unisoc SC9863A).
  - Defaults connection MTU to 23 bytes (20 usable ATT bytes) if negotiation fails or times out.
- **Scan Burst Decoupling (`GhostService.kt`):**
  - Replaced `collectLatest` with `collect` on `BleManager.peers` flow.
  - Decoupled group message retransmissions into detached child coroutine jobs with a 10-second per-group debounce map (`lastGroupReflush`).
  - BLE scan bursts firing every 100–300ms no longer cancel in-flight Room database queries or active GATT writes.
- **Truthful Group Message Status Progression (`GroupMessageSender.kt`):**
  - Messages remain in `STATUS_PENDING` while direct GATT transmission is in flight.
  - Advances to `STATUS_SENT` only after physical GATT write confirmation from `onCharacteristicWrite`.
  - Falls back to `STATUS_SPRAYED` if direct unicast fails or peer is unreachable, allowing mesh multi-hop propagation.
  - Preserved Stage 3 terminal state protections ($U_2, U_{15}$) prohibiting downgrades from `STATUS_DELIVERED`.
- **Group Envelope Input Sanitization (`GroupMessageReceiver.kt`):**
  - Base64 payload decoding normalized with `.trim()` and `Base64.DEFAULT` flags to handle trailing newlines and whitespace.
  - Enforced strict 32-byte Ed25519 public key length validation before signature verification.
  - Added privacy-safe diagnostic logging (packet length, key fingerprint, sender ID prefix) without leaking message plaintext.
  - Maintained Ed25519 as authoritative for sender identity; corrupt or mismatched envelopes drop cleanly without silent fallback.

### Verified
- **Unit Test Suite (`BleReliabilityTest.kt`):**
  - 11 unit tests covering MTU 23/185/247/512 slicing, unfragmented payload invariance, 16-fragment MTU 23 splits, out-of-order reassembly, duplicate fragment deduplication, and 64 KB boundary guards.
- **Full Android Test Suite:** 52 / 52 passing tests (`./gradlew testDebugUnitTest`).
- **Clean APK Build:** `./gradlew assembleDebug` builds cleanly (44.3 MB debug APK).

---

## [v0.4.3] — 2026-09-05

Major release introducing **Stage 4 Android OEM Hell Engine (Hostile Runtime Verification)**. Validates the GHOST Protocol at the boundary of hostile mobile operating system lifecycles, vendor battery management, Bluetooth/GATT instability, background task termination, permission revocation, and native JNI boundaries.

### Added
- **Android OEM Hostile Runtime Engine (`go/ghostrouter/sim/oem`):**
  - High-speed, deterministic, discrete-event simulation engine modeling the Android OS boundary.
  - 10 specialized domain models: Activity lifecycle, `GhostService` foreground service, Linux process lifecycle & LMKD, `BluetoothAdapter` state machine, serialized `GattOperationQueue`, runtime BLE permissions, `PowerPolicyEngine`, Room SQLite disk persistence, Ed25519 identity stability across MAC rotation, and Rust/Go JNI boundaries.
  - 7 synthetic OEM hostility profiles: `OEM_STOCK` (AOSP/Pixel baseline), `OEM_BACKGROUND_AGGRESSIVE` (strict background freezer), `OEM_BLE_UNSTABLE` (flaky vendor stack & GATT 133), `OEM_MEMORY_PRESSURE` (low RAM & aggressive LMKD), `OEM_BATTERY_AGGRESSIVE` (deep sleep & wake lock suppression), `OEM_SERVICE_HOSTILE` (task-killer / swipe termination), and `OEM_MAXIMUM_HOSTILITY` (full combinatorial stress).
- **Comprehensive Runtime Invariants ($O_1$ through $O_{24}$):**
  - Enforces $O_1$ Durable Message Survival, $O_2$ Activity Decoupling, $O_3$ Service Restart Consistency, $O_4$ GATT Queue Serialization ($\le 1$ active connection), $O_5$ Closed GATT Safety, $O_6$ Terminal Delivery Invariance, $O_7$ Bluetooth Off Bounded Abort, $O_8$ Bluetooth On Recovery, $O_9$ MAC Rotation Stability, $O_{10}$ Permission Revocation Safety, $O_{11}$ Permission Restoration Recovery, $O_{12}$ Battery Relay Gating, $O_{13}$ Bounded Queue Depth, $O_{14}$ Bounded Observer Growth, $O_{15}$ Native Boundary Safety, $O_{16}$ Storage Failure Transparency, $O_{17}$ No Logical Duplicates, $O_{18}$ No Committed Message Loss, $O_{19}$ Valid State Progression, $O_{20}$ Deadlock Free Service, $O_{21}$ Exact Deterministic Replay, $O_{22}$ Wire Protocol Invariance, $O_{23}$ Identity Immutability, and $O_{24}$ Eventual Quiescence.
- **Validation Scope Attribution Framework:**
  - Distinctly delineates and documents validation boundaries: `MODEL_VALIDATED` (discrete Go runtime engine), `ANDROID_UNIT_VALIDATED` (JVM/Gradle unit test suite), and `PHYSICAL_DEVICE_VALIDATED` (requires physical hardware testing on actual OEM devices).
  - Explicitly documents the methodological distinction between virtual process restart durability and physical NAND/F2FS reboot durability.
- **Simulator CLI Integration (`cmd/ghost-sim`):**
  - Added `ghost-sim oem` subcommand supporting configurable scenario counts, PRNG master seeds, parallel worker pools, JSON output, and automated forensic Markdown report generation.
  - Integrated 5x deterministic replay verification and physical OEM observation trace import/export bridge.

### Verified
- **10,000-Scenario Deterministic Campaign:** 10,000 / 10,000 scenarios passed (100.0%) across all 7 OEM profiles ($P0=0, P1=0, P2=0, P3=0, P4=0$).
- **Concurrency & Race Detection:** Clean pass under Go Race Detector (`-race`) with zero data races.
- **Replay Determinism:** 5x repeated executions yield bit-for-bit identical state and metric hashes.

---

## [v0.4.2] — 2026-09-05

Milestone release delivering **Stage 3 UX & Application State Torture Hardening**. Stress-tests the complete user-facing application pipeline (`Compose UI -> ChatViewModel -> ConversationRepository -> Room Database -> Go Router / Rust Crypto -> BLE / GATT Stack`) under deterministic adversarial concurrency.

### Added
- **Deterministic UX / Responsiveness Torture Engine (`go/ghostrouter/sim/ux`):**
  - Models virtual time, Compose recomposition frame timing, coroutine dispatchers, and StateFlow/SharedFlow collectors.
  - Formal UX invariants ($U_1$ through $U_{15}$) evaluating UI responsiveness, transport truthfulness, memory leak prevention, and repository consistency.
  - Delta-debugging scenario minimizer (`shrinker.go`) for automatic test reduction.
  - Integrated `ghost-sim ux` CLI subcommand.

### Changed & Fixed
- **Atomic SQLite Status Guard in Room DAOs (`MessageDao.kt`, `GroupMessageDao.kt`):**
  - Fixed a subtle asynchronous race condition where delayed out-of-order transport events could downgrade a message from `STATUS_DELIVERED` (`2`) back to `STATUS_SENT` (`1`) or `STATUS_PENDING` (`0`).
  - Added atomic SQL condition: `AND (status != 2 OR :status = 2)`, mathematically guaranteeing terminal delivery invariance.
  - Added regression test suite `MessageStatusTransitionTest.kt` verifying status immutability.
- **Generator Causality Audit Fix (`generator.go`):**
  - Fixed synthetic receipt scheduling to strictly respect physical GATT transmission latencies, eliminating unclassified acausal receipt anomalies.

### Verified
- **10,000-Scenario UX Campaign:** 10,000 / 10,000 scenarios passed with 0 invariant violations and 0 unclassified acausal receipts.

---

## [v0.4.1] — 2026-09-04

Hardening sprint closing all findings from the **Extreme Mesh Torture Campaign (Stage 1 & Stage 2)**.

### Added
- **Deterministic Virtual Mesh Simulator & Torture Engine (`go/ghostrouter/sim`, `sim/torture`):**
  - Discrete-event simulation framework supporting 10 to 1,000+ virtual nodes.
  - 10-dimensional parameter fuzzing (topology, channel loss, battery drain, churn, TTL boundaries, hop counts, payload sizes).
  - 15 formal mesh routing invariants ($I_1$ through $I_{15}$) checking conservation of copies, delivery uniqueness, and hop limits.
  - Integrated `ghost-sim run` and `ghost-sim torture` CLI commands.

### Fixed
- **Defect A: Persistent Inbound Delivery Deduplication across Reboot (`I6_Dedup`):**
  - Fixed vulnerability where a destination node rebooting after receiving a message from one carrier would re-deliver the message to the application when encountering a second carrier.
  - Integrated persistent SQLite deduplication storage ensuring delivery records survive process and node reboots.
- **Defect B: Relay Gating Failure after Battery Depletion (`I7_RelayGating`):**
  - Fixed issue where nodes dropping below 20% battery continued to spray pre-existing transit messages during peer discovery.
  - Enforced dynamic relay willingness checks during neighbor encounter synchronization.

### Verified
- **100,000-Scenario Extended Campaign:** 100,000 / 100,000 scenarios passed with zero invariant violations ($I_1..I_{15} = 0$, $P_0..P_4 = 0$).
- **Protocol Invariance:** Zero changes to wire format, opcodes, cryptography, or Spray-and-Wait parameters ($L=4$, $\text{MaxHops}=10$, $\text{TTL}=24\text{h}$).

---

## [v0.4.0] — 2026-09-04

Major release focusing on **Mesh Reliability, Resilience & Production Hardening**. Prior to introducing physical transport extensions, this milestone eliminates Bluetooth Low Energy controller collisions, hardens delay-tolerant routing storage invariants, prevents transit denial-of-service, bounds memory allocations, isolates native cryptographic execution from JVM crashes, and enforces strict battery conservation rules.

### Added
- **Serialized GATT Operation Queue (`GattOperationQueue.kt`):**
  - Enforces strictly sequential client GATT connections and characteristic writes across all concurrent coroutines.
  - Inter-connection cool-off: strictly enforces a 150ms delay between disconnect and reconnect to the same peer MAC address, completely eliminating Android Bluetooth stack `GATT 133` controller collision errors.
  - Operation timeout: 15-second watchdog timer per queued operation preventing indefinite stack stalls.
  - Guaranteed resource teardown: ensures `gatt.close()` is invoked in all terminal states (`disconnect`, `onError`, `timeout`, cancellation).
  - Dual-API characteristic write: adopts Android 13+ (API 33+) `BluetoothGatt.writeCharacteristic(char, data, writeType)` while maintaining fully backward-compatible fallback for Android 8–12 (API 26–32).
  - Buffer overflow protection: expanded `_incomingMessages` channel buffer to capacity 256 with `BufferOverflow.DROP_OLDEST` to prevent coroutine backpressure stalls under heavy radio bursts.
- **Go DTN Store Quotas & Eviction Priority (`go/ghostrouter`):**
  - **Destination Quota:** Enforces a maximum cap of 50 transit relay messages per destination node in `SaveMessage`, eliminating rogue flooding and buffer starvation attacks.
  - **Local Pending Message Protection:** Rewrote `PruneIfNeeded` eviction priorities to strictly protect local unsent messages (`bytes.Equal(msg.Src, s.localID) && msg.Status == StatusPending`). The store strictly evicts: (1) expired messages, (2) delivered messages, (3) transit relay messages, and (4) local messages whose spray copies have been fully exhausted.
  - **BoltDB Corruption Auto-Recovery:** Enhanced `OpenStore` to detect corrupted database headers or invalid page tables on startup, automatically archive the damaged database file (`*.corrupt.<timestamp>`), and initialize a clean database without crashing or stranding service startup.
- **Direct Delivery Retries & Dedup Ring Buffer:**
  - Added direct delivery retry tracking (up to 3 encounter attempts) before marking a message delivered in the DTN router, eliminating single-encounter packet drop losses.
  - Bounded `DedupCache` with 2,048 entries, 24-hour TTL, and $O(1)$ ring buffer eviction.
- **Power Policy Engine Low-Battery Relay Cutoff & Hysteresis (`PowerPolicyEngine.kt`):**
  - **Strict Low-Battery Cutoff:** Forces `relayWillingness = 0.0f` whenever battery level drops below 20% while not charging, universally across ALL security postures (`STEALTH`, `PROTEST`, `EMERGENCY`).
  - **Battery Threshold Hysteresis:** Enforces a $\pm 2\%$ hysteresis band: enters `CRITICAL` mode at $<20\%$ and only exits back to `ECO` or `ACTIVE` when battery recovers to $\ge 22\%$ (or when actively charging).
- **Rust / JNI Native Cryptographic Boundary Hardening (`ghost-crypto`):**
  - Extracted pure Rust core functions (`generate_identity_core`, `encrypt_core`, `decrypt_core`, `sign_core`, `verify_core`) completely decoupled from JNI types.
  - Wrapped all exported JNI functions (`Java_com_ghostprotocol_crypto_GhostCrypto_*`) in `std::panic::catch_unwind(AssertUnwindSafe(|| { ... }))`. Any native panic (allocation failure, slice bounds check) is caught and cleanly rethrown as a JVM `java/lang/RuntimeException` rather than aborting the Android process.
  - Added 12 pure-Rust unit tests verifying identity key generation, encryption roundtrips, tampered ciphertext rejection, tampered nonce rejection, tampered signature rejection, and invalid key lengths.
- **Multi-Node Discrete Mesh Simulator (`simulator_test.go`):**
  - Added a deterministic discrete-event simulator verifying multi-node mesh topologies across 7 production scenarios: Direct Delivery, Multi-Hop Line Routing, Partition & Reconvergence, Node Churn, 30% Radio Loss Tolerance, Duplicate Encounters, and Battery-Constrained Relay Refusal.

### Changed & Fixed
- **Group Protocol Test Suite:** Added 7 regression test cases in `GroupProtocolTest.kt` verifying Cell Group invite delivery, offline DTN routing, self-healing metadata recovery, idempotent reception, malformed envelope rejection, process restarts, and duplicate suppression.
- **Android Unit Tests:** Expanded test coverage to 43 unit test cases passing 100% across debug and release configurations.

---

## [v0.3.8] — 2026-09-04

**UI/UX & Jetpack Compose Architecture Hardening** release. Focused on locking protocol features to eliminate UI thread latency, eliminate list recomposition churn, standardize interactive touch targets to 48dp, and improve UI responsiveness.

### Added
- **`ChatViewModel` & Optimistic UI Loop (<1ms Perceived Latency):**
  - Extracted state management out of `ChatScreen.kt` into a standalone `ChatViewModel`.
  - Tapping Send immediately prepends an in-memory `PENDING` bubble to the UI `StateFlow` (<1ms perceived UI acknowledgement), decoupling user visual feedback from asynchronous Room DB transactions, AES-256-GCM encryption, and BLE/DTN dispatch.
  - Automatically reconciles optimistic items with Room database emissions via composite deduplication keys (`pending_${id}`).
- **Unified `ConversationRepository` & Off-Thread Data Processing:**
  - Aggregates 1:1 direct mesh contacts and Cell Groups into a single chronologically sorted `Flow<List<ConversationItem>>`.
  - All multi-table joins, latest message resolution, and O(1) RF peer fingerprint matching execute on `Dispatchers.Default`, shielding the UI thread from database or cryptographic overhead.
  - Pre-indexes discovered BLE peers by MAC address and canonical 8-character hex fingerprint, eliminating on-the-fly `MessageDigest` SHA-256 computation and `Base64` decoding inside `LazyColumn` item renderers.
- **`GhostComponents` Reusable Design System:**
  - `GhostStatusIndicator`: Authoritative, calm protocol status indicator (`•` pending dot, `✓` sent tick, `✓✓` delivered double-check in `#BB86FC`, `!` failed with retry, `∿` DTN mesh wave).
  - `GhostBadge`: Standardized metadata chips (`VERIFIED`, `INTRODUCED`, `CELL`).
  - `GhostEmptyState`: Minimalist, confident empty states across conversations, 1:1 chat, and Cell Groups without emojis.
  - `GhostActionFab`: Unified 56dp floating action button (`+`) replacing the 4 vertically stacked buttons on the main screen.
  - `NewChatBottomSheet`: Accessible modal bottom sheet consolidating Scan QR, Show QR, Short Code, and Create Cell Group workflows.
- **Cell Group Two-Tier Invite & Self-Healing Delivery (`com.ghostprotocol.group`):**
  - **Opcode `0x31` (`OPCODE_GROUP_INVITE`):** Cryptographic wire protocol codec (`[0x31 || 32B groupId || 16B creatorId || 8B ts || ciphertext || 64B sig]`) dispatched immediately upon group creation to all verified members over BLE and Go DTN router. Fits within a single 512-byte ATT MTU write (~261 bytes).
  - **Creator Verification & High-Priority Popups:** Recipient devices verify creator authenticity against verified contacts, verify Ed25519 signature, insert `GroupEntity` into Room DB, log a system welcome message, and trigger `NotificationHelper.showGroupInviteNotification` with direct group navigation.
  - **Self-Healing `META` Payloads (`0x30`):** Outgoing group message envelopes embed group metadata (`META\0groupName\0creatorId\0membersJson`) within pairwise ciphertext. Members who were out of radio range during group creation automatically self-heal, instantiate the group record, post the invite notification, and save the incoming message on first receipt.
- **Strict 48dp Physical Touch Targets:**
  - Enforced `GhostTheme.MinTouchTarget = 48.dp` across all interactive icon buttons, send triggers, back navigation, and list rows.
- **Navigation & Lifecycle Hardening:**
  - Replaced vertical modal navigation transitions with responsive 220ms horizontal slide transitions (`slideInHorizontally` / `slideOutHorizontally`).
  - Removed aggressive `CAMERA` permission request on application startup; camera permission is now requested lazily only when launching the QR scanner.
  - Guarded battery optimization exclusion requests with persistent shared preferences to avoid repeated startup prompts.

### Changed & Optimized
- **60 FPS Scrolling Performance:**
  - Removed the 1-second `delay(1000)` polling ticker loop from `ContactListScreen.kt` that was triggering redundant full-screen recompositions every second.
  - Hoisted dynamic sweeps and gradient brushes into static properties in `GhostTheme.kt`, eliminating per-frame graphic allocations.
  - Disabled infinite rotation animations in avatars within list items by default (`animateEtherealRing = false`), eliminating continuous CPU/GPU composition loops.
  - Replaced dynamic linear gradient shimmers on `STATUS_SPRAYED` bubbles with a static hoisted `GhostTheme.SprayedBorder`.

---

## [v0.3.7] — 2026-09-04

Major feature release implementing **Delivery Receipts** (`✓✓`) — end-to-end cryptographic acknowledgments (Opcode `0x40`) proving that a recipient has successfully decrypted and committed a message to Room database storage.

### Added
- **Cryptographic Delivery Receipts (Opcode `0x40`):**
  - Fixes the radio-only acknowledgment gap where `STATUS_SENT` (`✓`) only verified local GATT stack write.
  - Generates a signed 153-byte wire acknowledgment when destination completes local decryption and storage:
    `[1B: 0x40][64B: messageHash hex][16B: recipientContactId][8B: timestamp BE][64B: Ed25519 signature]`
  - Signed using recipient's Ed25519 identity seed; verified by sender against recipient's pinned public key.
  - Fits cleanly in a single BLE GATT write without L2CAP fragmentation (153 bytes << 512-byte MTU).
- **Deterministic Content Hashing & Wire Timestamp Sync:**
  - Standardized content hash computation across 1:1 and Cell Group messages:
    `SHA-256(senderContactId || timestampBE || plaintext)`
  - Solved clock skew and hash divergence on 1:1 messages by prefixing wire payload with an explicit timestamp token:
    `name\0TS\0timestamp\0[REPLY\0...]body`
  - Receiver parses and strips the `TS` token, saving the pristine plaintext into Room DB while computing the exact matching hash.
- **Room Database Migration v7 → v8 (`MIGRATION_7_8`):**
  - Added indexed `contentHash` column to `messages` and `group_messages` for instant receipt lookup without full table scans.
  - Added `deliveredMemberIdsJson` JSON text column to `group_messages` tracking per-member delivery acknowledgments.
- **Storm Prevention & Terminal Dispatch:**
  - First-delivery-only execution: receipts fire strictly on initial Room DB insertion (`getByContentHash == null`). Duplicate deliveries from multi-hop spray copies or re-encounter flushes are dropped silently.
  - Opcode `0x40` is terminal — no receipts are generated for delivery receipt packets.
  - System verification notices (`* ` prefix) are suppressed from triggering receipts.
  - Shared deduplication cache protects against receipt packet replay.
- **Tactical UI Status Feedback:**
  - 1:1 Chat: Upgraded `StatusIndicator` with overlapping GhostPurple double checkmarks (`✓✓`) for `STATUS_DELIVERED` (status 2), maintaining visual consistency with single check (`✓`) for `STATUS_SENT`.
  - Cell Groups: Outgoing message bubbles show live delivery count (`"Delivered to X/Y"`).
  - `GroupDeliveryDetailSheet`: Modal bottom sheet displaying real-time per-member status (`DELIVERED` vs `PENDING`) with member roles and status indicators.

---

## [v0.3.6] — 2026-09-04

Feature release implementing **Contact Introductions (Trust Web)** — one-way cryptographic vouching (Opcode `0x50`) allowing a mutual verified contact (Alice) to introduce Bob to Carol via signed encrypted envelopes without requiring physical QR code scanning.

### Added
- **Cryptographic Introduction Envelope (Opcode `0x50`):**
  - Wire protocol for one-way key vouching:
    `[1B: 0x50][32B: Bob ed25519Pub][32B: Bob x25519Pub][2B: nameLen BE][N: name UTF-8][16B: voucherContactId][64B: Alice Ed25519 signature]`
  - Alice signs `(0x50 || Bob.ed25519Pub || Bob.x25519Pub || nameLen || name || Carol.contactId || Alice.contactId)`.
  - Signature is verified by Carol against Alice's pinned Ed25519 public key.
  - Wire envelope size is ~163 bytes (for 16-char name), fitting into a single BLE GATT write without fragmentation.
  - Transport agnostic: encrypted to Carol's X25519 public key and routed over standard 1:1 mesh transport without modifying the Go router or Rust crypto engine.
- **One-Way Trust Invariants:**
  - Alice introduces Bob to Carol. Carol receives Bob's identity keys and attribution.
  - Bob is not automatically notified and does not receive Carol's keys (no bidirectional graph sync).
  - Carol can initiate messages to Bob once added.
- **Visual Distinction & Trust Integrity:**
  - Introduced contacts (`isIntroduced = true && isVerified = false`) render with a slate border (`#3F3F46`) and a small `"INTRODUCED"` chip.
  - Introduced contacts **never** receive the violet Ethereal Ring (Ghost Aura) until mutually verified in person via QR or Protest Mode Discovery.
  - Chat screen displays a persistent top banner: *"Introduced by Alice — not mutually verified"* with a direct tap action to open QR verification.
- **Room Database Migration v8 → v9 (`MIGRATION_8_9`):**
  - Added `isIntroduced` column (`INTEGER NOT NULL DEFAULT 0`) to `contacts` table.
  - Preserves introduction flags across contact re-insertions and verification upgrades.
- **In-Memory Introduction Lifecycle (`IntroductionHandler.kt`):**
  - Pending introductions cached in memory with a 10-minute expiry window.
  - Silent arrival: high-priority notification posted without vibration or heartbeat haptics to prevent acoustic detection in sensitive environments.
  - Terminal packet demux: opcode `0x50` intercepts immediately after decryption, preventing envelope insertion into message chat logs and suppressing delivery receipts.
- **User Interface Components:**
  - `IntroductionReviewBottomSheet`: Shows voucher details with Ghost Aura avatar, introduced contact card with fingerprint and `"INTRODUCED"` badge, cryptographic disclaimer, and "Add Contact" / "Decline" actions.
  - `IntroduceContactDialog`: Accessible from `ContactInfoBottomSheet` for mutually verified contacts, listing eligible verified recipients with confirmation dialog.
  - System notices: Logs `* You introduced Bob to Carol *` in Alice's local chat, and `* Alice introduced Bob to you *` in Carol's chat with Alice upon acceptance.

---

## [v0.3.5] — 2026-09-04

Major feature release implementing **Cell Groups** — private, verified group chat for up to 8 members using pairwise end-to-end encryption.

### Added
- **Pairwise Unicast Envelopes (Opcode `0x30`):**
  - Group messages are not broadcast in cleartext (unlike Bridgefy) and do not leak persistent public channel identifiers (unlike BitChat).
  - Every group message generates separate pairwise envelopes for each verified member:
    `[1B: 0x30][32B: groupId][16B: senderContactId][8B: timestamp][AES-256-GCM ciphertext][64B: Ed25519 signature]`
  - Encrypted with fresh ephemeral X25519 keypairs per member. Relays cannot decrypt message contents, view sender identities, or inspect member rosters.
  - Wire envelope size is ~281 bytes, fitting cleanly inside standard 512-byte BLE ATT MTU without requiring L2CAP fragmentation.
  - Routes directly to member peer IDs (`SHA-256(memberEd25519PubKey)`), naturally triggering standard `OnDeliver` callbacks on recipient devices with zero changes required in Go or Rust engines.
- **High-Entropy Group ID Generator:**
  - Derives a 64-character hex ID: `SHA-256(creatorEd25519Pub || timestampBE || 16-byte random nonce)`.
  - Nonce prevents ID collisions when multiple groups are created in the same millisecond.
- **Room Database Migration v6 → v7 (`MIGRATION_6_7`):**
  - Added `groups` table: `groupId`, `name`, `creatorContactId`, `memberContactIdsJson`, `createdAt`, `isActive`.
  - Added `group_messages` table: `id` (autoincrement), `groupId`, `senderContactId`, `text`, `timestamp`, `status`, `replyToSender`, `replyToText`.
  - Added indexes on `groupId`, `timestamp`, and `status`.
  - Added 48-hour rolling pruning query (`pruneOlderThan`) executed inside the background telemetry loop.
- **Cell Group Orchestrators:**
  - `GroupMessageSender`: Handles pairwise fan-out, direct GATT writes for in-range peers, mesh router queueing for out-of-range peers, and peer re-encounter re-flushing while strictly preserving original message timestamps for chronological ordering.
  - `GroupMessageReceiver`: Validates sender against `ContactDao`, verifies group membership in `GroupDao`, verifies Ed25519 signature (silent drop on mismatch), decrypts with local X25519 secret, and triggers batched notifications with unread counts.
- **Cell Group Tactical UI:**
  - `GroupCreationScreen`: Enforces hard 2–8 member cap; filters for verified contacts only (`isVerified = true`). Unverified contacts are disabled.
  - `GroupChatScreen`: Monospace chat layout, sender attribution labels, small 26dp hexagon avatars beside incoming bubbles, quoted swipe-to-reply support, and animated violet shimmer borders for messages in `STATUS_SPRAYED`.
  - `GroupInfoBottomSheet`: Displays group metadata, member roster with `CREATOR` and `YOU` badges, and leave/delete actions.
  - `HexagonAvatar`: Deterministic hue gradient derived from group name with a violet border.
  - `ContactListScreen`: Interleaved active Cell Groups marked with a purple `CELL` pill badge; added dedicated "CELL" Floating Action Button.
  - `MainActivity`: Navigation routes `"group_creation"` and `"group_chat/{groupId}"`, with automatic deep-link routing when opened from notifications.

---

## [v0.3.0] — 2026-09-03

Major release implementing **Protest Mode**: security posture management, one-tap nearby BLE discovery, and 24-hour rotating BIP-39 short codes.

### Added
- **Security Posture Engine (`SecurityPostureManager`):**
  - State engine providing 4 discrete operating postures:
    - `NORMAL`: Default mode. Discovery requires in-person QR scanning. Standard power policy.
    - `PROTEST`: High-readiness mode. 1000ms scan / 200ms window. Background one-tap BLE discovery enabled.
    - `EMERGENCY`: Maximum mesh throughput. 100% duty cycle (continuous scanning), 100ms advertising. Immediate forwarding.
    - `STEALTH`: Radio silence. BLE advertising completely stopped. Passive receiver mode only (listen without transmitting RF).
  - Low-battery failsafe: Automatically reverts from `EMERGENCY` or `PROTEST` back to `NORMAL` when battery drops below 15%.
- **Nearby BLE Discovery & One-Tap Handshake (Opcodes `0x10` & `0x11`):**
  - Wire protocol for frictionless in-range contact discovery:
    - Request (`0x10`): `[1B 0x10][32B ed25519Pub][32B x25519Pub][8B timestamp][name (max 32B)][64B signature]`
    - Response (`0x11`): `[1B 0x11][32B ed25519Pub][32B x25519Pub][8B timestamp][name (max 32B)][64B signature]`
  - Demuxed at byte 0 in `BleManager` without touching the Go routing layer.
  - 20-second per-MAC rate limiter (maximum 3 requests/minute per device) preventing notification flood attacks in dense crowds.
  - Generates high-priority Android notification with "Add Contact" action button.
- **24-Hour Rotating BIP-39 Short Verification Codes (Opcodes `0x20`–`0x23`):**
  - Derives a 3-word + 4-digit code (e.g. `LION - COBALT - HARBOR - 4821`) using HMAC-SHA256 over `(Ed25519 Seed || UTC Epoch Day)`.
  - 11-bit index extraction over standard BIP-39 2048-word list (`seed[0..1] and 0x7FF`) + 4-digit numeric suffix (`seed[6..7] % 10000`). Total entropy ~8.6 × 10¹³ combinations.
  - Rotates deterministically at midnight UTC. The private seed never leaves the device.
  - Wire opcodes:
    - `0x20`: Direct BLE query (`[1B 0x20][32B targetCodeHash][32B senderEd25519Pub][64B sig]`).
    - `0x21`: Direct BLE response (`[1B 0x21][32B responderEd25519Pub][32B responderX25519Pub][64B sig]`).
    - `0x22`: Mesh-routed multi-hop query across Go router.
    - `0x23`: Mesh-routed multi-hop response.
  - Anti-probing defense: Queries with mismatched code hashes are dropped silently with zero response packet emitted over the air.
- **UI Screens:**
  - `ShortCodeScreen`: Displays user's active 3-word code, 4-digit suffix, QR code, and countdown timer to next UTC midnight rotation.
  - `ShortCodeInputScreen`: Word auto-complete chip input against BIP-39 dictionary with direct and mesh query dispatch.

### Fixed
- **Duplicate "4x Message" Scan Burst Bug:**
  - *Root Cause 1 (Sender):* In `PROTEST` and `EMERGENCY` modes, BLE scan callbacks fire every 100–200ms. In `GhostService.kt`, the check `if (!hasPendingRoom && (now - lastCall < 10_000)) continue` skipped throttling whenever a message was in `STATUS_PENDING`, triggering overlapping concurrent delivery loops.
  - *Root Cause 2 (Receiver):* Deduplication checked `SHA-256(ciphertext)`. Because each send encrypted plaintext with a fresh ephemeral X25519 key and random AES nonce, ciphertexts were completely different, causing the hash dedup check to miss all duplicate packets.
  - *Fix:* Switched deduplication to hash the 64-byte Ed25519 digital signature over `(senderPubKey + plaintext)` (RFC 8032). Because Ed25519 signatures are deterministic, all re-encryptions and relays share the identical signature. Added 60s signature cache (`recentMessageSignatures`) and 6s content cache (`recentMessageContents`). Added unconditional 5-second encounter throttle and `deliveringContacts` in-flight concurrency lock.

---

## [v0.2.0] — 2026-09-03

Major release focusing on physical battery efficiency, delay-tolerant mesh delivery, and reciprocal contact verification. Verified across two physical Android devices over BLE.

### Added
- **PowerPolicyEngine:** Centralized state engine evaluating 5 device inputs every 30 seconds (battery level, charging state, screen status, peer count, pending queue depth, encounter recency). Automatically transitions across 4 operating modes:
  - `ACTIVE`: 500ms scan / 100ms adv, high TX power, full relay willingness (1.0). For charging or high-density routing.
  - `ECO`: 2000ms scan / 500ms adv, medium TX power, battery-proportional relay willingness (0.3–1.0). Default walking state.
  - `CRITICAL`: 60s scan / 1000ms adv, low TX power, relay disabled (0.0), Partial WakeLock released. For battery < 20%.
  - `DEEP_SLEEP`: 300s scan / 2000ms adv, low TX power, relay disabled (0.0), Partial WakeLock released. For stationary overnight nodes (>30m without encounters).
- **Single-Session GATT Message Batching:**
  - Wire format: `[1B count][4B len1][msg1][4B len2][msg2]...`
  - Bundles pending queue into a single connection, MTU 512 negotiation, and service discovery cycle. Chained sequential GATT writes eliminate connection thrashing and reduce active radio on-time by ~70%.
- **Relay Willingness Gating:** Thread-safe dynamic gate in Go router (`SetRelayWillingness`). When willingness is 0.0, forwarded transit messages are dropped prior to BoltDB storage, preserving scarce flash write cycles and battery life on dying nodes.
- **Battery & Mesh Telemetry:**
  - SQLite snapshot logging (`telemetry_snapshots`) recording 15 runtime metrics every 60s (temperatures, GATT connection counters, TX/RX byte counts, CPU wakeups, scan runtimes).
  - 48-hour rolling retention pruning.
  - One-tap CSV export via Android `FileProvider` and system share sheet.
- **Reciprocal Mutual QR Verification:**
  - Scanning a peer's QR code triggers haptic feedback and automatically transitions to `QRShowScreen` on the scanner's device so the peer can scan back without touching navigation buttons.
  - Wire-level verification handshake: transmits signed verification payload over BLE upon scan.
  - When reciprocal scan or handshake completes, inserts `* mutual verification with <name> *` system event, fires high-priority Android system notification (`Mutual verification: You and <name> verified each other`), triggers hardware dual-pulse heartbeat haptics, and activates the signature Ghost Aura (animated Ethereal Ring) around the contact's avatar.
  - In-chat system events rendered as centered, monospace log lines with bracketed timestamps (`[HH:mm:ss]`).
- **Signature Cypherpunk UI/UX Architecture:**
  - **Ghost Aura / Ethereal Ring:** Replaced generic emoji badges with an animated neon violet sweep-gradient rim (`#9D4EDD` → `#C77DFF` → `#7B2CBF`) surrounding avatars of mutually verified peers. Clean dark slate rim (`#3F3F46`) for unverified peers.
  - **Live Radio Proximity Wave:** Replaced generic green internet dots with live physical BLE RF signal metrics (`∿∿∿ ~3m Direct (-58 dBm)`, `∿∿ ~8m Direct (-72 dBm)`, `∿ ~15m Edge (-84 dBm)`, or `📡 Relayed (Mesh Ready)` when out of direct range).
  - **Dual-Pulse Heartbeat Haptic Verification:** Visceral `bump... thump-thump` double-pulse vibration waveform (35ms bump, 90ms pause, 75ms max-power thump) confirming cryptographic mutual verification directly into the user's palm.
  - **Delay-Tolerant Sprayed Physics:** Message bubbles in `STATUS_SPRAYED` display an animated breathing neon violet shimmer border, visually indicating active radio transit, snapping to a solid check border upon delivery.
  - **Tactical Survival HUD Mode:** 1-tap quick toggle turning the entire UI into true `#000000` OLED black, switching typography to tactical high-contrast phosphor green (`#52B788`) and amber (`#FFB703`), completely disabling GPU animations to conserve battery, and displaying a top persistent telemetry strip (`⚡ HUD [OLED BLACK] • 84% • ECO | 2000ms | 2 PEERS`).
- **Quoted Reply Envelope Protocol:** Wire format parses `\u0000REPLY\u0000<quotedSender>\u0000<quotedText>\u0000<bodyText>`, persisting `replyToSender` and `replyToText` in Room DB. Backward-compatible with non-quoted messages.

### Fixed
- **DTN Store-and-Forward Re-encounter Delivery:**
  - Fixed dead-code guard in `GhostService.kt` where `if (ghostRouter == null)` prevented Room database delayed-queue processing.
  - Added atomic Room query `getSprayedOrPendingForContact(contactId)` (`status IN (0, 4)`).
  - Upon BLE peer discovery, automatically pulls delayed messages from Room DB, encrypts with fresh ephemeral keys, sequentially delivers them via `suspendCancellableCoroutine` over GATT, and promotes status from `STATUS_SPRAYED` (4) to `STATUS_SENT` (1), updating UI icon from 📡 to `✓`.
- **Message Deduplication:** Replaced plaintext-based hashing with ciphertext-hash deduplication (includes ephemeral nonce) over a 60-second sliding window, preventing legitimate identical repeated messages from being dropped.
- **Peer Online Status Flapping:** Added 120-second timeout window and 4-byte primary advertising packet fallback to prevent contact status flapping during Android BLE MAC address rotation.
- **GATT 133 Connection Storms:** Fixed concurrent GATT connection attempts to identical MAC addresses via coroutine mutex serialization.
- **Service Notification Actions:** Added notification action buttons to cycle power policies directly from the Android notification shade.

### Database & Schema Migrations
- Bumped Room database schema from version 3 → 4 (`TelemetryEntity` table added).
- Bumped Room database schema from version 4 → 5 (`replyToSender` and `replyToText` columns added to `messages`).
- Bumped Room database schema from version 5 → 6 (`isVerified` column added to `contacts` with `MIGRATION_5_6`).

### Testing & Verification
- 15/15 Go tests passing (<0.05s) covering store, serializer, routing, batch encoding roundtrip, and relay willingness gating.
- Physical dual-device hardware verification completed on Android API 34.

---

## [v0.1.5] — 2026-09-02

Stability and security release. 10 rounds of bug hunting across Go, Kotlin, and Rust. 73 bugs found, 67 fixed, 0 false positives.

### Fixed (group by component):

**Go Router (router.go, store.go, serializer.go, message.go)**
- Fixed deadlock: OnDeliver callback invoked while holding mutex (router.go)
- Fixed direct-send messages staying StatusPending, causing re-spray across mesh (router.go)
- Fixed spray-to-sender relay loop: messages no longer sprayed back to originator (router.go)
- Fixed message ID collision on rapid sends: added crypto/rand nonce (router.go)
- Fixed unsafe [:4] slice bounds in 12 log statements: added shortHex() helper (router.go)
- Fixed Router.Stop() panic on double call: wrapped with sync.Once (router.go)
- Fixed BoltDB timeout of 1 nanosecond causing lock contention (store.go)
- Fixed BoltDB PruneIfNeeded using file size instead of record count (store.go)
- Fixed serializer integer overflow on malformed header length (serializer.go)
- Fixed OnDeliver passing dst instead of src to Kotlin callback (router.go)

**Kotlin App (Android)**
- Fixed GATT server responding GATT_SUCCESS when SharedFlow buffer full: message silently lost (BleManager.kt)
- Fixed GATT client missing connection timeout: added 10-second Handler timeout (BleManager.kt)
- Fixed concurrent GATT connections to same MAC causing error 133 (GhostService.kt)
- Fixed BLE scan callback erasing existing fingerprint on scan without response data (BleManager.kt)
- Fixed BleManager crash on invalid MAC address format (BleManager.kt)
- Fixed POST_NOTIFICATIONS not requested at runtime on Android 13+ (MainActivity.kt)
- Fixed CAMERA permission denial blocking entire BLE service start (MainActivity.kt)
- Fixed SwipeableMessage stale lambda capture in LazyColumn: added rememberUpdatedState (SwipeableMessage.kt)
- Fixed SwipeableMessage coroutine flooding: 60-120 coroutines/sec in drag handler (SwipeableMessage.kt)
- Fixed ChatScreen online dot always showing green: now checks BLE peer recency (ChatScreen.kt)
- Fixed sendMessage race condition on rapid taps: added compareAndSet guard (ChatScreen.kt)
- Fixed direct-send with null bleAddress silently losing message (ChatScreen.kt)
- Fixed retryMessage with isDirect+null bleAddress not marking FAILED (ChatScreen.kt)
- Fixed double-tap opening duplicate ChatScreen: added launchSingleTop (ContactListScreen.kt)
- Fixed IdentityManager crash on corrupted SharedPreferences (IdentityManager.kt)
- Fixed IdentityManager force-unwrap NPE: added requireBlob() helper (IdentityManager.kt)
- Fixed name sync BLE address wipe: added ContactDao.updateName() atomic query (ContactDao.kt)
- Fixed GhostRouter.sendMessage swallowing router errors as 'queued' (GhostRouter.kt)
- Fixed GhostRouter.sendMessage returning false/null when router is null (GhostRouter.kt)
- Fixed OnDeliver callback parameter named 'dst' instead of 'senderId' (GhostRouter.kt)
- Fixed broadcastNameUpdate signing payload N times inside loop (GhostService.kt)
- Fixed onTaskRemoved PendingIntent.getService crash on Android 8+ (GhostService.kt)
- Fixed aggressive 30-second dedup window dropping repeated messages: reduced to 5s (GhostService.kt)
- Added Room database indexes on (contactId, timestamp) and (status, isOutgoing) (MessageEntity.kt)
- Bumped database version 2 → 3 for schema change (GhostDatabase.kt)

**Rust Crypto (lib.rs)**
- Fixed verify() throwing RuntimeException instead of returning false on invalid signatures (lib.rs)
- Fixed .unwrap() panics in encrypt/decrypt crashing process across FFI boundary (lib.rs)
- Fixed minimum ciphertext length check: 44 → 60 bytes (lib.rs)

---

## [v0.1.4] — 2026-09-02

### ✨ Flagship UI Phase 1: Premium Design System

Complete visual overhaul of the messaging UI:
- **GhostTheme.kt** — Design tokens: colors (Purple gradient, Surface0-3, status colors), spacing, radii, component sizes
- **ChatScreen.kt** — Purple gradient sent bubbles, dark gray received bubbles, message grouping (consecutive messages merge corners), time headers (5+ min gap), inline timestamps + status icons, premium pill-shaped input field, slide-in animation, pulse for sprayed, empty state, scroll-to-bottom FAB
- **ContactListScreen.kt** — "GHOST" branding header (28sp bold), pill search bar, stacked FABs (scan/QR), premium empty state, avatar with online indicator
- **MainActivity.kt** — Edge-to-edge display, Ghost dark color scheme, slide + fade navigation transitions

### ✨ Flagship UI Phase 2: Swipe Gestures + Reply Mode + Bottom Sheets

Premium interactions:
- **SwipeableMessage.kt** [NEW] — Bidirectional swipe: right reveals reply arrow (GhostPurple), left reveals copy/delete icons. Spring animation, 100dp threshold, haptic feedback at threshold
- **ReplyBar.kt** [NEW] — Quoted message preview above input field: 4dp purple accent bar, sender name, truncated message, cancel X. Tap to scroll to original message
- **BottomSheets.kt** [NEW] — Two modal bottom sheets:
  - `MessageActionsBottomSheet`: Reply, Copy, Delete (red). Triggered by long-press on message
  - `ContactInfoBottomSheet`: Large avatar, name, handle, Show QR button, Clear Chat, Delete Contact with confirmation dialogs. Triggered by tapping avatar in chat bar

### 🐛 Fix: Green Dot Shows Offline Peers as Online

**Problem:** Online indicator checked `contact.bleAddress != null` — once a peer was discovered, they appeared online forever, even after turning off their phone.

**Fix:** Now checks `BleManager.peers.lastSeen < 60_000ms`. Green dot = peer seen in last 60 seconds via BLE. Gray dot = peer was seen before but is currently not in range.

### 🐛 Fix: Sprayed Messages Don't Deliver When Peer Returns (CRITICAL)

**Problem:** `startPeerMatching()` only called `ghostRouter.onPeerDiscovered()` when `existingContact.bleAddress != peer.address` — i.e., only on first discovery or MAC rotation. If a peer left BLE range and returned with the same MAC address, the router was never notified, so queued (📡 SPRAYED) messages were never delivered.

**Fix:** Removed the address-change guard. `onPeerDiscovered()` is now called **every time** a known peer is seen, with a 10-second per-peer throttle to avoid spamming the router on every BLE scan tick.

### 🐛 Fix: Username Changes Don't Propagate

**Problem:** Username was set during QR exchange and never updated. If User A changed their name, User B still saw the old name forever.

**Fix:** Sender now embeds username in every encrypted message payload using `username\0messageText` format. On receive, if sender's name differs from stored contact name, Room DB is updated. Backward-compatible: old-format messages (no null byte) are parsed as plain text.

### 📝 Documentation Overhaul

- **whitepaper.md** — Complete rewrite. Every claim now backed by code. Aspirational features moved to "Future Work". Real measured performance. Honest security analysis (Tier 1 ✅, Tier 2 ⚠, Tier 3-4 ❌). Routing correctly described as "opportunistic store-and-forward" (not "dynamic route discovery").
- **README.md** — Rewritten with honest feature list, 3-command quick start, architecture table, roadmap
- **CHANGELOG.md** — Updated with v0.1.4 entry

### Background Service Resilience

- `PARTIAL_WAKE_LOCK` in GhostService — keeps CPU alive during BLE operations
- Battery optimization exclusion popup in MainActivity
- `onTaskRemoved()` — alarm-based auto-restart within 1s when swiped from recents
- `WAKE_LOCK` + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permissions in manifest

### Changed
- `android/.../ui/GhostTheme.kt` [NEW] — Design system tokens
- `android/.../ui/SwipeableMessage.kt` [NEW] — Bidirectional swipe wrapper
- `android/.../ui/ReplyBar.kt` [NEW] — Reply bar composable
- `android/.../ui/BottomSheets.kt` [NEW] — Message actions + contact info sheets
- `android/.../ui/ChatScreen.kt` — Full rewrite (gradient bubbles, grouping, swipe, reply, sheets)
- `android/.../ui/ContactListScreen.kt` — Full rewrite (branding, search, online status fix)
- `android/.../MainActivity.kt` — Edge-to-edge, transitions, battery prompt
- `android/.../GhostService.kt` — WakeLock, peer matching fix, username sync, onTaskRemoved restart
- `android/.../AndroidManifest.xml` — WakeLock + battery permissions
- `docs/whitepaper.md` — Complete honest rewrite
- `README.md` — Complete honest rewrite

---

## [v0.1.3] — 2026-09-02

### 🐛 Critical Fix: gomobile JNI Memory Bug

**Problem:** Go router said `forwarded` for every message, even when the message was destined for the receiving phone. Messages were never `delivered` through the router.

**Root cause:** gomobile passes `[]byte` parameters as JNI-backed memory that gets freed after the Kotlin→Go call returns. Storing the slice reference (instead of copying) caused use-after-free — `localID` contained garbage/zeros, so `bytes.Equal(header.Dst, r.localID)` never matched.

**Evidence from logcat:**
```
BEFORE: localID=10a800fb7f00000000000000f0489d260000000000000000... (zeros = freed JNI memory)
AFTER:  localID=c7efcb4b478d8358a91a3813f0489d26a6782f0eeda01796... (clean 32-byte SHA-256)
```

**Fix:** Added `copy()` for all `[]byte` parameters in 4 Go functions:
- `NewRouter(localID)` — copy `localID`
- `SendMessage(dst, payload)` — copy `dst` and `payload`
- `OnPeerDiscovered(peerID)` — copy `peerID`
- `OnMessageReceived(data)` — copy `data`

### 🐛 Fix: Legacy Auto-Retry Conflict

**Problem:** `startPeerMatching()` auto-retry sent raw ciphertext (no routing envelope) bypassing the Go router. Receiver's router tried to decode routing header from random crypto bytes → `forwarded` or `error`.

**Fix:** Disabled legacy auto-retry when router is active — `onPeerDiscovered()` handles delivery/spray instead. Auto-retry only runs as fallback when `ghostRouter == null`.

### 🐛 Fix: Receive Path Fallback

**Problem:** If Go router returned `forwarded` or `error` (e.g., raw ciphertext from a phone running an older build), the message was silently swallowed.

**Fix:** Added `directDecryptAndSave()` fallback method. When router returns `forwarded` or `error`, tries direct Rust decrypt. Handles backward compatibility with older builds.

### Changed
- `go/ghostrouter/router.go` — `copy()` all JNI byte slices in 4 exported functions
- `android/.../GhostService.kt` — disable legacy auto-retry when router active, add `directDecryptAndSave()` fallback
- `android/.../ble/BleManager.kt` — added `getRouter()` accessor

### Verified
- ✅ All messages delivered bidirectionally through Go router on 2 physical phones
- ✅ Logcat confirms: `sendMessage → direct`, `message xxxx is for us! Delivering`, `ROUTED DECRYPT SUCCESS`
- ✅ 13/13 Go unit tests pass
- ✅ `go vet` clean

---

## [v0.1.2] — 2026-09-01

### 🔌 Wired Go Router into BLE Flow (3 Changes)

Connected the Go spray-and-wait router to the actual BLE message paths. Previously the router initialized but was never called.

#### Change 1: Send Path (`ChatScreen.kt`)
- After encrypting, calls `GhostRouter.sendMessage(dstId, ciphertext)`
- If direct → sends routed blob via BLE → status SENT
- If queued → status SPRAYED (📡 icon in UI)
- Falls back to legacy `BleManager.sendMessage()` if no router

#### Change 2: Receive Path (`GhostService.kt`)
- Incoming BLE data passes through `GhostRouter.onMessageReceived()` first
- `"delivered"` → `DeliverHandler.onDeliver()` callback fires `processRoutedPayload()`
- `"forwarded"` → message stored in Go BoltDB for future spraying
- `"dropped"` → TTL/hop/duplicate, ignored
- Falls back to direct decrypt if no router

#### Change 3: Peer Discovery (`GhostService.kt`)
- After fingerprint match, calls `GhostRouter.onPeerDiscovered(peerId, rssi)`
- Sends returned blobs (direct deliveries + spray copies) via BLE

### Changed
- `android/.../ui/ChatScreen.kt` — send path routes through Go router
- `android/.../GhostService.kt` — receive path and peer discovery wired to router
- `android/.../ble/BleManager.kt` — added `getRouter()` accessor

---

## [v0.1.1] — 2026-09-01

### 🚀 Sprint 3: Spray-and-Wait Mesh Routing (Go gomobile)

Multi-hop message forwarding via Go gomobile. Messages can now reach phones not in direct BLE range via intermediate relay phones using the spray-and-wait algorithm.

#### Go Package (`go/ghostrouter/`)
- **`message.go`** — Data structures: `Message`, `RoutingHeader`, `PeerInfo`, status constants
- **`store.go`** — BoltDB persistence: CRUD, `DeleteExpired`, `PruneIfNeeded` (50MB cap), `DeleteStalePeers`
- **`router.go`** — Spray-and-wait algorithm with binary spray (copies=4), `DeliverHandler` interface, `BlobList`/`SendResult` wrappers for gomobile compatibility
- **`serializer.go`** — Wire format: `[4B headerLen][JSON RoutingHeader][encrypted payload]`
- **`router_test.go`** — 13 unit tests: store CRUD, serializer roundtrip, direct delivery, forwarding, hop limit, TTL expiry, dedup, spray on discovery, direct when peer recent, deliver on discovery, stats

#### Kotlin Integration
- **`GhostRouter.kt`** — Kotlin bridge wrapping gomobile `Router` + `DeliverHandler` interface
- **`GhostService.kt`** — `initRouter()` on startup, `processRoutedPayload()` for decryption, `stop()` in onDestroy
- **`BleManager.kt`** — `ghostRouter` field + `setRouter()` method
- **`MessageEntity.kt`** — Added `STATUS_SPRAYED = 4`
- **`ChatScreen.kt`** — Added 📡 icon for sprayed status
- **`build.gradle.kts`** — Added `ghostrouter.aar` dependency

#### gomobile Constraints Solved
- `[][]byte` not supported → wrapped in `BlobList` struct with `Size()/Get()` methods
- `func` callbacks not supported → replaced with `DeliverHandler` Go interface
- Multi-return not supported → wrapped in `SendResult` struct
- Go `int` maps to Java `long` in `OnPeerDiscovered`

#### Build
- `gomobile bind` → `ghostrouter.aar` (4.9 MB, arm64 + x86_64)
- `./gradlew assembleDebug` → BUILD SUCCESSFUL
- 13/13 Go tests pass, `go vet` clean

---

## [v0.1.0] — 2026-09-01

### 🏗️ Sprint 1 + 2: Foundation

#### Sprint 1: Project Scaffold + BLE Discovery
- Android project with Jetpack Compose UI
- BLE 5.0 advertising and scanning
- Connectable advertising with 4-byte SHA-256 fingerprint in scan response
- Foreground service (`GhostService`) for persistent BLE operation
- Notification channel with IMPORTANCE_DEFAULT

#### Sprint 2: QR Contact Exchange + Encrypted Messaging
- QR code generation and scanning (CameraX + ML Kit barcode)
- Contact storage in Room database
- Ed25519/X25519 key generation via Rust JNI (`ghost-crypto` crate)
- AES-256-GCM encryption with X25519 key agreement
- Ed25519 digital signatures on every message
- BLE GATT server (receive) and client (send) with MTU 512
- Message persistence in Room database
- Peer fingerprint matching (BLE scan → SHA-256 → Room contact lookup)
- Auto-retry pending messages when peer rediscovered

### 🐛 Bug Fixes (5/5)
1. **BLE ad size overflow** — switched to `startAdvertising(settings, advData, scanResponse, callback)` 3-arg overload to stay under 31-byte limit
2. **Message loss on screen close** — moved message processing from ChatScreen to GhostService (always alive)
3. **Fingerprint matching** — fixed first-4-bytes vs first-8-bytes mismatch between BLE fingerprint and contact ID
4. **Stale contact BLE address** — update `bleAddress` in Room when peer re-scanned with different MAC (BLE address rotation)
5. **Delete menu** — fixed contact deletion crash

### ✨ Polish Sprint
- **Custom usernames** — set at first launch, stored in SharedPreferences, embedded in QR
- **Deterministic avatars** — color + initial from SHA-256(ed25519_pubkey)
- **Short handles** — `#a3f7b2` format (first 6 hex chars of pubkey hash)
- **Contact search** — filter by name or handle in contact list
- **Battery monitor** — 15-minute interval logging for power analysis
- **Settings screen** — identity display, QR code, battery debug, delete all data, app version
- **Quit notification** — "Quit GHOST" action button on foreground service notification (requires IMPORTANCE_DEFAULT)
- **Message timestamps** — relative display ("now", "2m", "1h", "3d")
- **Delivery status icons** — ⏳ pending, ✓ sent, ⚠ failed

### Identity Format
```
128-byte blob = ed25519_seed(32) + ed25519_pub(32) + x25519_secret(32) + x25519_pub(32)
QR format: GHOST:<base64(ed25519_pub(32) + x25519_pub(32) + name_utf8)>
Contact ID: SHA-256(ed25519_pub).take(8).toHex() → 16-char hex string
BLE fingerprint: SHA-256(ed25519_pub).take(4) → 4 bytes in scan response
```

### Architecture
```
Kotlin App (Jetpack Compose)
   ├── Rust JNI (ghost-crypto) → Ed25519, X25519, AES-256-GCM
   ├── Go gomobile (ghostrouter) → Spray-and-wait, BoltDB
   └── Room DB (SQLite) → Contacts, Messages
```

### Tested On
- 2× physical Android phones (Android 12+)
- Bidirectional encrypted messaging verified
- BLE range ~10m indoors
- No Google Play Services required
