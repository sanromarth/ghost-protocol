# GHOST Protocol — Changelog

All notable changes to the GHOST Protocol project are documented in this file.

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
