# GHOST Protocol Kotlin FFI Bridge Reference

> **Version:** v0.4.3 — Technical reference for native JNI, gomobile, subsystem coordination, and Android runtime resilience.
> **Platform:** Android AOSP / JNI / gomobile.

---

## 1. Architectural Overview

Kotlin acts as the primary system coordinator on Android. It operates as the unprivileged application runtime orchestrating:
1. **Rust (`libghost_crypto.so`) via JNI** for asymmetric key agreement (X25519), authenticated symmetric encryption (AES-256-GCM), and RFC 8032 digital signatures (Ed25519).
2. **Go (`ghostrouter.aar`) via gomobile** for delay-tolerant Spray-and-Wait ($L=4$) mesh routing, BoltDB carrier persistence, and crash-proof SQLite deduplication.
3. **Android BLE Stack (`android.bluetooth.*`)** via a serialized, non-blocking GATT operation queue (`GattOperationQueue`) that shields the fragile AOSP Bluetooth stack from concurrent access.
4. **Local Persistence (Room DB v9 / SQLite WAL)** providing crash-durable, atomic state management for messages, contacts, cell groups, and delivery telemetry.

Rust and Go share zero process memory; all payloads cross native boundaries as raw, immutable `ByteArray` objects managed by Kotlin coroutines on background dispatchers (`Dispatchers.IO` / `Dispatchers.Default`).

```
+-----------------------------------------------------------------------------------+
|                                KOTLIN RUNTIME                                     |
|                                                                                   |
|  [ChatViewModel] <--- StateFlow <--- [ConversationRepository] <--- [Room DB v9]   |
|         |                                                             ^           |
|         v                                                             |           |
|  [GattOperationQueue]              [DeliveryReceiptHandler] ----------+           |
|         |                                  |                          |           |
|         v                                  v                          |           |
|   (Serialized BLE)                  (Opcode 0x40 ACK)                 |           |
+---------+----------------------------------+--------------------------+-----------+
          |                                  |                          |
          v                                  v                          |
+-------------------+              +-------------------+                |
|   libghost_crypto | (JNI)        |   ghostrouter     | (gomobile)     |
|   X25519 / Ed25519|              |   BoltDB / SQLite | ---------------+
|   AES-256-GCM     |              |   Spray-and-Wait  |
+-------------------+              +-------------------+
```

---

## 2. Rust JNI Bridge (`ghost-crypto`)

**Native Library:** `libghost_crypto.so`
**Kotlin Wrapper:** `com.ghostprotocol.crypto.GhostCrypto`
**Crate:** `rust/ghost-crypto/`

```kotlin
object GhostCrypto {
    init {
        System.loadLibrary("ghost_crypto")
    }

    // Returns 128 bytes: ed25519_seed(32) + ed25519_pub(32) + x25519_secret(32) + x25519_pub(32)
    external fun generateIdentity(): ByteArray

    // X25519 ECDH agreement + AES-256-GCM authenticated encryption
    // Returns: ephemeral_pub(32) + nonce(12) + ciphertext + tag(16)
    external fun encrypt(recipientX25519Pub: ByteArray, plaintext: ByteArray): ByteArray

    // X25519 ECDH agreement + AES-256-GCM authenticated decryption
    // Returns: decrypted plaintext bytes
    external fun decrypt(myX25519Secret: ByteArray, ciphertext: ByteArray): ByteArray

    // RFC 8032 deterministic Ed25519 digital signature (64 bytes)
    external fun sign(ed25519Seed: ByteArray, message: ByteArray): ByteArray

    // RFC 8032 Ed25519 signature verification (returns true if valid)
    external fun verify(ed25519Pub: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}
```

### Invariants & Memory Safety:
- **Stateless & Thread-Safe:** All underlying Rust core functions (`generate_identity_core`, `encrypt_core`, `decrypt_core`, `sign_core`, `verify_core`) are pure, side-effect free, and thread-safe.
- **Unwind Isolation (`std::panic::catch_unwind`):** Every JNI entrypoint wraps native execution in `catch_unwind(AssertUnwindSafe(|| ...))`. If Rust encounters an out-of-memory or panic condition, the panic is caught and marshaled into a standard Java `RuntimeException` via `jni_error(&mut env, "...")`, preventing SIGSEGV or process termination (Invariants $O_3, O_4$).
- **Zero Dangling Pointers:** Byte arrays are copied immediately across the JNI boundary using `env.convert_byte_array()`. The native runtime retains no references to JVM heap memory once the call returns.

---

## 3. Go Mobile Bridge (`ghostrouter`)

**AAR Library:** `ghostrouter.aar` (generated via `gomobile bind`)
**Kotlin Wrapper:** `com.ghostprotocol.router.GhostRouter`
**Package:** `go/ghostrouter/`

```kotlin
class GhostRouter(
    private val localId: ByteArray,   // SHA-256(localEd25519PubKey) — 32 bytes
    private val dbPath: String,       // Absolute path to BoltDB file
    private val scope: CoroutineScope,
    private val onMessageForMe: (payload: ByteArray) -> Unit
) {
    // Queues message for destination. Returns Pair(isDirect, blobToSend)
    fun sendMessage(dst: ByteArray, payload: ByteArray): Pair<Boolean, ByteArray?>

    // Notifies router of an encountered peer. Returns list of blobs to transmit
    fun onPeerDiscovered(peerId: ByteArray, rssi: Int): List<ByteArray>

    // Passes incoming raw BLE data into the router.
    // Returns: "delivered", "forwarded", "dropped: <reason>", or "error: <details>"
    fun onMessageReceived(data: ByteArray): String

    // Sets relay willingness policy gate (0.0 = drop transit; 1.0 = full relay)
    fun setRelayWillingness(willingness: Float)

    // Closes BoltDB and terminates background janitor goroutines
    fun stop()
}

// DeliverHandler is the gomobile-exported interface invoked when Dst == localId
interface DeliverHandler {
    fun onDeliver(senderId: ByteArray?, payload: ByteArray?)
}
```

### gomobile Calling Rules:
- **Composite Slice Wrapping:** Gomobile does not support multi-dimensional slices (`[][]byte`). The Go router exports a `BlobList` wrapper providing `.Size()` and `.Get(i)`.
- **Heap Copying for Persistence:** Arguments passed as `[]byte` are backed by JVM direct byte buffers that become invalid after native return. The Go router explicitly creates independent heap copies (`make([]byte, len(src))`) before storing payloads in BoltDB or SQLite.
- **Crash-Proof Persistent Deduplication:** In addition to in-memory Bloom/LRU filters, `ghostrouter` maintains a persistent SQLite deduplication table (`seen_packets`) in WAL mode (`dedup.go`), guaranteeing that processed packets survive unexpected process death (Invariant $I_6$).

---

## 4. BLE GATT Operation Queue (`GattOperationQueue`)

The Android Bluetooth Low Energy stack (`BluetoothGatt`) suffers from severe historical instability when multiple GATT operations (connecting, service discovery, characteristic reads/writes, MTU negotiation) are executed concurrently. Invoking `writeCharacteristic` while an earlier GATT transaction is in flight frequently causes the stack to deadlock, drop callbacks, or emit the fatal internal error `GATT_ERROR (133)`.

To eliminate these failure modes, GHOST routes all BLE operations through a centralized, serialized queue:
`com.ghostprotocol.ble.GattOperationQueue`.

```kotlin
class GattOperationQueue(
    private val scope: CoroutineScope,
    private val defaultTimeoutMs: Long = 5_000L,
    private val maxCapacity: Int = 100
) {
    // Enqueues an operation to be executed sequentially
    fun enqueue(
        opId: String,
        timeoutMs: Long = defaultTimeoutMs,
        block: suspend (continuation: (GattResult) -> Unit) -> Unit
    ): Boolean

    // Callback invoked by BluetoothGattCallback when an operation finishes
    fun onOperationComplete(opId: String, result: GattResult)

    // Drops any pending operations for a disconnected device
    fun clearQueueForDevice(deviceAddress: String)
}
```

### Operational Characteristics:
1. **Strict Serialized Execution:** The queue processes operations strictly one at a time. A new GATT operation will never be initiated until the previous operation's callback (`onCharacteristicWrite`, `onConnectionStateChange`, etc.) fires or the operation times out (Invariant $O_4$).
2. **Deterministic 5-Second Timeout:** If a remote peripheral disconnects silently or the local radio drops an AOSP callback, the active operation times out after 5,000 ms. The queue clears the pending lock and proceeds to the next queued item (Invariant $O_4$).
3. **Late Callback Immunity:** If a timed-out or cancelled GATT callback arrives late from the Android kernel, `onOperationComplete` verifies that the callback's `opId` matches the currently active transaction. Mismatched or stale callbacks are silently dropped without corrupting subsequent queue state (Invariant $O_5$).
4. **Bounded Capacity & Backpressure:** The queue is bounded to 100 pending operations. If a burst of application traffic exceeds capacity, excess operations are dropped with backpressure rather than consuming unbounded memory (Invariant $O_{13}$).

---

## 5. Pairwise Group Messaging (`GroupMessageSender` / `GroupMessageReceiver`)

GHOST implements private group messaging without cleartext broadcast leakage or unbounded forum storage replication:

- **Pairwise Unicast Architecture:** Groups are capped at 8 members. Outgoing messages loop over the roster, generating an independent ciphertext envelope for each member:
  `GhostCrypto.encrypt(memberX25519Pub, wirePayload)`.
- **Fanout Mechanics (`GroupMessageSender`):**
  - If a member is directly connected via BLE, the envelope is enqueued into `GattOperationQueue` for direct GATT write.
  - If a member is out of direct radio range, the envelope is handed to `GhostRouter.sendMessage()`, which stores the message in BoltDB and sprays up to $L=4$ copies to encountered mesh carriers.
- **Two-Tier Membership Delivery:**
  - **Tier 1 (Explicit Invite `0x31`):** Dispatched upon group creation. Contains group metadata and initial roster, signed by the creator.
  - **Tier 2 (Self-Healing `0x30` Envelope):** Every regular group message embeds a compact `META` header within the pairwise ciphertext. If an offline or out-of-range member misses the initial `0x31` invite, the receiver automatically parses the embedded `META` descriptor, verifies the creator's Ed25519 signature, creates the local `GroupEntity`, posts a high-priority system notification, and inserts the message.

---

## 6. Delivery Receipt Protocol (`DeliveryReceiptHandler`)

Transport-level write acknowledgments only confirm that a radio frame was accepted by a peer's Bluetooth stack. GHOST implements true cryptographic end-to-end delivery receipts:

1. **Receipt Generation (Opcode `0x40`):**
   When the recipient device decrypts a payload and successfully commits it to Room DB, `DeliveryReceiptHandler` computes:
   $$\text{ContentHash} = \text{SHA-256}(\text{SenderContactId} \parallel \text{Timestamp}_{\text{BE}} \parallel \text{Plaintext})$$
   The recipient signs this 32-byte hash with its private Ed25519 identity seed, creating a 153-byte receipt envelope:
   `[1B: 0x40] [64B: ContentHash Hex] [16B: RecipientContactId] [8B: Timestamp] [64B: Ed25519 Sig]`.
2. **Receipt Ingestion & Status Promotion:**
   Upon receiving an Opcode `0x40` packet, `DeliveryReceiptHandler` verifies the signature against the recipient's pinned Ed25519 public key. If valid, it locates the matching message in Room DB via `getByContentHash()` and promotes its status to `STATUS_DELIVERED` (2), triggering the UI GhostPurple double checkmark (`✓✓`).

---

## 7. Room Atomic State Progression & Invariant Guard

To prevent race conditions where out-of-order background coroutines or late GATT callbacks downgrade a delivered message, Room DAOs enforce strict conditional update guards:

```kotlin
@Dao
interface MessageDao {
    // Monotonic guard: only transition to SENT if the current status is PENDING
    @Query("UPDATE messages SET status = :newStatus WHERE id = :id AND status = 0")
    suspend fun updateStatusIfPending(id: Long, newStatus: Int): Int

    @Query("SELECT * FROM messages WHERE contentHash = :hash LIMIT 1")
    suspend fun getByContentHash(hash: String): MessageEntity?
}

@Dao
interface GroupMessageDao {
    // Monotonic guard for group envelopes
    @Query("UPDATE group_messages SET status = :newStatus WHERE id = :id AND status = 0")
    suspend fun updateGroupMessageStatusIfPending(id: Long, newStatus: Int): Int
}
```

### Invariant Contract ($U_{15}, O_6$):
- **Monotonic Progression:** A message progresses strictly along the directed graph:
  $$\text{STATUS\_PENDING (0)} \longrightarrow \text{STATUS\_SENT (1)} \longrightarrow \text{STATUS\_DELIVERED (2)}$$
- **Terminal Status Invariance:** A message that has reached `STATUS_DELIVERED` (2) can NEVER be rolled back to `STATUS_SENT` (1) or `STATUS_PENDING` (0), even if a late GATT write callback returns `STATUS_SENT` or an intermediate mesh relay reports a duplicate transit status.

---

## 8. Complete Wire Protocol Layouts

```text
Opcode 0x01: Go Router 1:1 Spray Packet
[4B: uint32 headerLen BE] [headerLen bytes: JSON routingHeader] [AES-256-GCM Ciphertext]

Opcode 0x10: Nearby Discovery Request
[1B: 0x10] [32B: ed25519Pub] [32B: x25519Pub] [8B: timestamp BE] [max 32B: utf8 name] [64B: ed25519Sig]

Opcode 0x11: Nearby Discovery Response
[1B: 0x11] [32B: ed25519Pub] [32B: x25519Pub] [8B: timestamp BE] [max 32B: utf8 name] [64B: ed25519Sig]

Opcode 0x20 / 0x22: BIP-39 Short Code Query (0x20 direct, 0x22 mesh)
[1B: opcode] [32B: targetCodeHash] [32B: senderEd25519Pub] [64B: ed25519Sig]

Opcode 0x21 / 0x23: BIP-39 Short Code Response (0x21 direct, 0x23 mesh)
[1B: opcode] [32B: responderEd25519Pub] [32B: responderX25519Pub] [64B: ed25519Sig]

Opcode 0x30: Cell Group Individual Unicast Envelope
[1B: 0x30] [32B: groupId raw] [16B: senderContactId utf8] [8B: timestamp BE] [ciphertext] [64B: ed25519Sig]

Opcode 0x31: Cell Group Member Invite Envelope
[1B: 0x31] [32B: groupId raw] [16B: creatorContactId utf8] [8B: timestamp BE] [ciphertext] [64B: ed25519Sig]

Opcode 0x40: Cryptographic Delivery Receipt
[1B: 0x40] [64B: messageHash hex] [16B: recipientContactId utf8] [8B: timestamp BE] [64B: ed25519Sig]

Opcode 0x50: Cryptographic Contact Introduction
[1B: 0x50] [32B: ed25519Pub] [32B: x25519Pub] [2B: nameLen BE] [max 32B: utf8 name] [16B: voucherContactId utf8] [64B: ed25519Sig]
```

---

## 9. Deduplication Invariants

1. **Ciphertexts Are Never Hashed for Deduplication:** Because each transmission generates fresh ephemeral X25519 keys and random 12-byte AES nonces, ciphertexts are non-deterministic.
2. **Deterministic Ed25519 Signatures (RFC 8032):** Senders compute signatures over canonical plaintext: `(senderEd25519Pub || plaintext)`. Under RFC 8032, Ed25519 signatures are strictly deterministic. Recipients hash this 64-byte signature and verify against:
   - An in-memory 60-second sliding LRU cache (`recentMessageSignatures`).
   - The Go router's persistent SQLite deduplication table (`seen_packets` in WAL mode).
   Any duplicate arrival (whether received directly, relayed via multiple mesh carriers, or replayed) is immediately dropped prior to decrypting or database insertion.

---

## 10. FFI Latency & Overhead

Benchmarked on Google Pixel 7 (ARM64, Android 14):

| Operation | Latency |
|---|---|
| JNI transition overhead | ~5 μs |
| gomobile transition overhead | ~10 μs |
| X25519 key agreement + AES-256-GCM encrypt (200B) | ~45 μs |
| Ed25519 digital signature generation | ~90 μs |
| Ed25519 digital signature verification | ~180 μs |
| BoltDB transaction write | ~200 μs |
| SQLite WAL deduplication check | ~120 μs |
| **Total local processing latency per message** | **< 1 millisecond** (excludes BLE radio) |

> [!NOTE]
> All JNI and gomobile operations execute strictly on background coroutine dispatchers (`Dispatchers.IO` / `Dispatchers.Default`). The UI main thread never invokes native methods synchronously, ensuring instant optimistic message bubble rendering (<1ms).
