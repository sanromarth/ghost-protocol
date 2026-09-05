# GHOST Application Layer RFC (Layer 7)

**Author:** PEDDI SANKARA RAO
**Kotlin Module:** `android/app/`
**Latest Version:** v0.4.3 (Compose Architecture, Optimistic Pipeline & Runtime Resilience)
**Target Platform:** Android 8.0+ (API 26+), 1GB RAM, 8GB Flash, Zero Google Play Services

---

## 1. Purpose

The GHOST Application Layer delivers an accessible, ultra-reliable Android client interface engineered specifically for infrastructure-denied environments: internet blackouts, active protests, remote field operations, and disaster recovery zones.

The application architecture is explicitly designed to remain responsive, truthful, and stable under hostile operating conditions: unannounced OS process death, aggressive OEM task killers, unstable Bluetooth stacks, memory pressure, and out-of-order asynchronous events.

---

## 2. Core Architectural Pillars (v0.4.3)

```
+-------------------------------------------------------------------------------+
|                            PRESENTATION LAYER                                 |
|                                                                               |
|  [ChatScreen / ContactListScreen] (Jetpack Compose, 60 FPS, 48dp Targets)     |
|         |                                              ^                      |
|         v                                              | StateFlow            |
|  [ChatViewModel]                                [ConversationRepository]      |
|  - <1ms Optimistic Bubble Prepend               - Off-Thread Joins            |
|  - Sub-Millisecond Perceived Send               - Dispatchers.Default         |
+---------+----------------------------------------------+----------------------+
          | Async Coroutines                             ^
          v                                              | Room Live Flow
+--------------------------------------------------------+----------------------+
|                               DATA & SERVICE LAYER                            |
|                                                                               |
|  [GhostService] (START_STICKY Foreground Service, PARTIAL_WAKE_LOCK)          |
|  - GattOperationQueue (Serialized BLE, 5s Timeout, Mutex-Free)               |
|  - PowerPolicyEngine (ACTIVE, ECO, CRITICAL, DEEP_SLEEP)                      |
|  - DeliveryReceiptHandler (Opcode 0x40 Signed ACKs)                           |
|  - Room Database v9 (SQLite WAL, Atomic Status Guard Invariant U15)           |
+-------------------------------------------------------------------------------+
```

### 2.1 Sub-Millisecond Optimistic Send Acknowledgement ($U_1$)
In low-connectivity or high-latency mesh environments, blocking UI input on cryptographic operations, disk transactions, or radio writes creates a sluggish user experience. `ChatViewModel` intercepts the send event and immediately prepends an in-memory `STATUS_PENDING` bubble to the UI `StateFlow`.
- **Perceived Latency:** **<1 millisecond** from tap to bubble appearance.
- **Asynchronous Execution:** Background coroutines concurrently execute X25519 ECDH key agreement, AES-256-GCM authenticated encryption, Room DB insertion, and BLE/GATT dispatch.

### 2.2 Off-Thread Feed Joins & 60 FPS Rendering ($U_4$)
`ConversationRepository` aggregates 1:1 direct contacts, 8-member Cell Groups, latest message previews, unread badges, and O(1) RF peer fingerprint matches entirely on `Dispatchers.Default`.
- The UI thread executes **zero** database queries, **zero** cryptographic SHA-256 hashes, and **zero** Base64 decodes during list scrolling passes.
- Gradient brushes (`EtherealSweepBrush`, `OutgoingBubbleBrush`) are hoisted as static singletons in `GhostTheme.kt`.
- Item animations within scrollable list items avoid infinite rotation loops, guaranteeing a continuous 60 FPS frame rate under rapid touch scrolling.

### 2.3 Strict Touch Ergonomics
All interactive components (icon buttons, text fields, conversation cards, modal dismissals) strictly enforce the 48dp physical touch target standard (`GhostTheme.MinTouchTarget`), ensuring usability with gloved hands or in high-stress operational settings.

---

## 3. Serialized BLE Operation Queue (`GattOperationQueue`) & Transport Framing

The Android Bluetooth Low Energy stack (`BluetoothGatt`) deadlocks, drops callbacks, or emits fatal `133 (GATT_ERROR)` faults when multiple GATT operations occur simultaneously. GHOST coordinates all BLE activity through a serialized coroutine queue:

```kotlin
class GattOperationQueue(
    private val scope: CoroutineScope,
    private val defaultTimeoutMs: Long = 5_000L,
    private val maxCapacity: Int = 100
)
```

### Invariants ($O_4, O_5, O_{13}, O_{21}$):
1. **Serialized Execution ($O_4$):** Exactly one GATT operation is active at any time. Subsequent operations remain queued until the active callback returns or times out.
2. **Deterministic Timeout ($O_4$):** Active operations time out after 5,000 ms, freeing the queue even if the AOSP stack completely drops the underlying hardware callback.
3. **Late Callback Drop ($O_5$):** Stale or timed-out GATT callbacks arriving late from the kernel are verified against `opId` and dropped silently, preventing corruption of subsequent transactions.
4. **Bounded Capacity ($O_{13}$):** Capacity is capped at 100 pending operations; excess traffic triggers controlled backpressure.
5. **100ms Link Stabilization Delay:** Immediate calls to `requestMtu(512)` inside `onConnectionStateChange` cause HCI timeouts on budget chipsets (MediaTek/Unisoc). A 100ms stabilization delay is enforced before issuing the MTU request.
6. **Dynamic MTU Slicing & Transport Framing (`0xFB`, $O_{21}$):** If MTU negotiation defaults or drops to 23 bytes (leaving 20 usable ATT bytes), payloads exceeding `negotiatedMtu - 3` are sliced into 7-byte framed fragments (`[0xFB][2B transferId][2B fragIndex][2B totalFrags][data...]`). Payloads within `negotiatedMtu - 3` are sent unfragmented for 100% backward compatibility.
7. **Bounded Reassembly Session:** Receiving nodes reassemble inbound `0xFB` frames in memory, bounded to 16 concurrent peers, 30s session timeout, and 64 KB total size limit. Duplicate and out-of-order frames are handled idempotently.

---

## 4. Room Atomic Status Progression & Invariant Guard

To guarantee that a message confirmed as delivered cannot be rolled back by a delayed transport callback, Room DAOs enforce atomic conditional updates:

```kotlin
@Query("UPDATE messages SET status = :newStatus WHERE id = :id AND status = 0")
suspend fun updateStatusIfPending(id: Long, newStatus: Int): Int
```

### Invariant Contract ($U_2, U_{15}, O_6$):
$$\text{STATUS\_PENDING (0)} \longrightarrow \text{STATUS\_SENT (1) or STATUS\_SPRAYED (4)} \longrightarrow \text{STATUS\_DELIVERED (2)}$$

- `STATUS_DELIVERED` (2) is strictly terminal.
- **Truthful Status Progression:** In direct group messaging, outgoing envelopes remain in `STATUS_PENDING` while the physical GATT write is in flight. Upon write confirmation from the GATT callback, status advances to `STATUS_SENT`. If direct write fails or the peer is unreachable, status transitions to `STATUS_SPRAYED` for mesh propagation.
- If a message has already been marked `STATUS_DELIVERED` via an incoming Opcode `0x40` receipt, late transport callbacks attempting to record `STATUS_SENT` or `STATUS_SPRAYED` affect 0 rows, preserving UI truthfulness unconditionally.

---

## 5. Android Hostile Runtime Resilience ($O_1..O_{24}$)

GHOST includes comprehensive defenses against aggressive Android OEM battery managers and runtime hostility:

1. **Activity Decoupling ($O_2$):** Mesh relaying, BLE scanning, and delay-tolerant store operations execute within `GhostService`, a foreground service holding a `PARTIAL_WAKE_LOCK`. Swiping the UI activity away from the Recents screen does not disrupt background mesh operation.
2. **Process Death Durability ($O_1, O_3$):** If the OS kills the process under low memory (LMKD), `GhostService` is configured with `START_STICKY` and backed by `AlarmManager` wakeups. Upon restart, persistent Room DB and BoltDB stores re-instantiate identical state without message loss.
3. **Bluetooth State Transitions ($O_7, O_8$):** If Bluetooth is toggled off, active operations are aborted within 500ms; when toggled back on, scanning and advertising resume automatically.
4. **Dynamic MAC Rotation ($O_9$):** Ephemeral advertisement addresses rotate every 15 minutes; incoming connections are accepted without dropping session keys.
5. **Permission Hardening ($O_{10}, O_{11}$):** Runtime permission revocations are detected gracefully without crashing; mesh capabilities resume immediately upon re-grant.
6. **Battery Relay Gating ($O_{12}$):** Below 20% battery or during sleep modes, relay willingness drops to $0.0$, shedding transit load while preserving local delivery.
7. **Scan Burst Decoupling:** Scan advertisement emissions from `BleManager.peers` arrive in rapid bursts (every 100–300ms). Collecting via `Flow.collectLatest` previously cancelled active group database queries and in-flight GATT transmissions. The collector uses `Flow.collect` and launches group retransmissions into detached child coroutine jobs with a 10-second per-group debounce map.
8. **Reciprocal Verification Queuing:** In-person QR exchanges can result in one peer scanning before the local scanner has observed the peer's BLE MAC address. Outbound verification handshakes lacking a resolved BLE address are buffered in memory and flushed immediately once the peer advertisement appears.
9. **Strict Cryptographic Key Validation:** Inbound group envelopes undergo strict Base64 normalization (whitespace/newline trimming) and 32-byte Ed25519 public key validation. Ed25519 signatures remain authoritative for sender identity; corrupt or mismatched envelopes are dropped with diagnostic logging.

---

## 6. Formal Invariant Sets

### 6.1 Application / UX Invariants ($U_1..U_{15}$)
- **$U_1$ (Optimistic Bubble ACK):** Outgoing send triggers immediate in-memory UI bubble in $<1\text{ms}$.
- **$U_2$ (Monotonic Status Progression):** Status progresses strictly `PENDING` $\to$ `SENT` or `SPRAYED` $\to$ `DELIVERED`.
- **$U_3$ (Room DB Durability):** Messages persisted to SQLite WAL before background dispatch finishes.
- **$U_4$ (60 FPS Scrolling):** Zero cryptographic or database overhead on the main thread during list scrolling.
- **$U_5$ (Single-Session Batching):** Multiple queued messages for a peer transmit in a single GATT session.
- **$U_6$ (Pairwise Group Isolation):** Group messages individually encrypted; members cannot inspect other member keys.
- **$U_7$ (Deterministic Receipt Generation):** Destination emits signed Opcode `0x40` receipt upon initial Room insert.
- **$U_8$ (Receipt Storm Suppression):** Duplicate arrivals and receipts never trigger duplicate receipt packets.
- **$U_9$ (Visual Trust Honesty):** Introduced contacts render with slate border; violet aura requires mutual QR verification.
- **$U_{10}$ (Touch Target Conformance):** 100% of interactive touch targets $\ge 48\text{dp}$.
- **$U_{11}$ (48h Rolling Group Retention):** Group messages pruned after 48 hours rolling retention.
- **$U_{12}$ (Battery Guard Revert):** High-drain security postures revert to `NORMAL` if battery falls below 15%.
- **$U_{13}$ (Self-Healing Group Membership):** Missing members auto-enrolled via embedded `META` payload.
- **$U_{14}$ (Deterministic Short Code Derivation):** 24h rotating BIP-39 codes derived from seed and UTC epoch day.
- **$U_{15}$ (Atomic Status Guard):** `updateStatusIfPending` prevents receipt rollback race conditions.

### 6.2 Android OEM Hostile Runtime Invariants ($O_1..O_{24}$)
- **$O_1$ (Durable Storage Survival):** Messages survive simulated process death without data loss.
- **$O_2$ (Activity Decoupling):** Background service continues mesh routing when activity is destroyed.
- **$O_3$ (Service Restart Consistency):** `START_STICKY` service restores valid router and GATT state.
- **$O_4$ (GATT Queue Serialization):** Strict FIFO execution; 5,000ms operation timeout.
- **$O_5$ (Closed GATT Safety):** Late callbacks after timeout or disconnect are safely dropped.
- **$O_6$ (Terminal Delivery Invariance):** Delivered status is immutable across late transport callbacks.
- **$O_7$ (Bluetooth Off Abort):** Radio shutdown aborts pending operations within 500ms.
- **$O_8$ (Bluetooth On Recovery):** Radio activation automatically restarts scan and advertise loops.
- **$O_9$ (MAC Rotation Stability):** Advertised address rotation preserves peer routing state.
- **$O_{10}$ (Permission Revocation Safety):** Permission loss pauses radio operations without crashing.
- **$O_{11}$ (Permission Restoration):** Re-granting permissions resumes BLE scanning and advertising.
- **$O_{12}$ (Battery Relay Gating):** Battery $<20\%$ sheds transit relaying; preserves local delivery.
- **$O_{13}$ (Bounded Queue Depth):** GATT queue capped at 100 operations with backpressure.
- **$O_{14}$ (Bounded Observer Growth):** Coroutine collectors and Flow subscriptions strictly bounded.
- **$O_{15}$ (Native JNI Boundary Safety):** JNI panics caught via `catch_unwind` and converted to Java exceptions.
- **$O_{16}$ (Storage Full Transparency):** SQLite full errors handled gracefully without corrupting database.
- **$O_{17}$ (Low Memory Trim):** `onTrimMemory` triggers cache shedding and memory cleanup.
- **$O_{18}$ (WakeLock Management):** `PARTIAL_WAKE_LOCK` held only during active radio operations.
- **$O_{19}$ (Clock Skew Invariance):** Timestamps normalized via explicit wire tokens; immune to device skew.
- **$O_{20}$ (Concurrent Peer Safety):** Multi-peer simultaneous connections serialized without corruption.
- **$O_{21}$ (MTU Negotiation & Dynamic Slicing Safety):** MTU negotiation failure falls back gracefully to 23-byte baseline, slicing envelopes dynamically via `0xFB` without data loss or write rejection.
- **$O_{22}$ (Notification Channel Integrity):** Foreground notification maintained continuously.
- **$O_{23}$ (Deterministic Replay Identity):** Seeded event trace replay produces bitwise identical state.
- **$O_{24}$ (Zero Resource Leaks):** Zero leaked file descriptors, cursors, coroutines, or wake locks.
