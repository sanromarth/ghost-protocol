# GHOST Protocol — Changelog

All notable changes to the GHOST Protocol project are documented in this file.

---

## [v0.2.0] — 2026-09-03

Battery-Aware Mesh Power System and Message Batching release.

### Added

**Message Batching (Go & BLE)**
- Added `EncodeBatch` and `DecodeBatch` in `serializer.go` using wire format `[1B count][4B len1][msg1][4B len2][msg2]...`
- `OnPeerDiscovered` in `router.go` now groups multiple pending messages for a single peer into one batched blob. Single messages continue through the legacy wire format for backward compatibility.
- Added `BleManager.sendBatch()` in Kotlin: connects to GATT once, negotiates MTU 512, and chains writes sequentially via `onCharacteristicWrite` callback to avoid L2CAP buffer overflow. Timeout scales with message count (capped at 45s).
- Added `TestBatchEncodingRoundtrip` unit test in Go (15/15 tests passing).

**PowerPolicyEngine & Dynamic Control (Kotlin)**
- Added `PowerPolicyEngine.kt` implementing a centralized 4-mode power state machine:
  - `ACTIVE`: Charging or in a crowd (>10 peers with pending queue). Low-latency scanning (500ms/100ms), 100ms adv, high TX power, full relay (1.0).
  - `ECO`: Default walking mode. Balanced scan (2000ms/100ms), 500ms adv, medium TX power, battery-scaled relay (0.3–1.0).
  - `CRITICAL`: Battery < 20% and unplugged. Low-power scan (60s/200ms), 1000ms adv, low TX power, relaying disabled (0.0).
  - `DEEP_SLEEP`: Screen off, stationary, no peers seen for >30m, battery > 20%. Deep discovery scan (5min/500ms), low TX power, relaying disabled.
- Added manual mode override API (`forceMode`) with 1-hour expiration timer and auto-revert.
- Partial wake lock management: released during `CRITICAL` and `DEEP_SLEEP` modes to allow device CPU sleep.
- Added `lastAppliedPolicy` caching in `GhostService.kt` to eliminate 30-second BLE restart thrashing when policy parameters are steady.

**Relay Willingness Policy Gate (Go & JNI Bridge)**
- Added `relayWillingness float32` to `Router` in `router.go` with `SetRelayWillingness` and `GetRelayWillingness`.
- Policy gate in `OnMessageReceived()` forwarded branch: drops incoming relay messages when willingness $\le 0$ without storing. Spray-and-wait binary splitting math and routing logic remain untouched.
- Direct JNI call added in `GhostRouter.kt` to control relay willingness from the power policy engine.
- Added `TestRelayWillingnessGate` unit test in Go verifying drop behavior, forwarding, and value clamping.

**Battery Telemetry & Persistence (Android)**
- Added `BatteryTelemetry.kt` with Room entity `TelemetryEntity` and DAO `TelemetryDao`.
- Telemetry captures 15 fields every 60 seconds: battery %, temperature, scan/adv cumulative runtimes, GATT connections, TX/RX bytes, CPU wakeups, and delivered/forwarded counts.
- 7-day automatic data pruning to prevent unbounded SQLite growth while preserving weekly trends.
- Built-in CSV export functionality sharing via Android share sheet (`Intent.ACTION_SEND`).
- Room database schema updated from version 3 to 4 with destructive migration fallback.

**UI & Controls**
- Added **Power Management** section to `SettingsScreen.kt`:
  - Color-coded current mode chip indicator (`ACTIVE` green, `ECO` blue, `CRITICAL` orange, `DEEP_SLEEP` gray).
  - Battery percentage indicator and latest telemetry snapshot summary card.
  - Manual mode override chips (`ACT`, `ECO`, `CRIT`, `SLEEP`) with 1-hour override logic.
  - "Export Battery Report" button to export and share telemetry CSV.
- Added `ACTION_CYCLE_MODE` quick toggle action to the ongoing foreground notification.
- Bumped app version string to `v0.2`.

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

### 🔒 Background Service Resilience

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
