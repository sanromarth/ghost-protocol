# GHOST Protocol Kotlin FFI Bridge Reference

> **Version:** v0.1.5 — reflects actual implemented FFI boundaries.

## 1. Overview

Kotlin is the glue layer. It talks to Rust for crypto (via JNI) and Go for routing (via gomobile). Rust and Go never call each other directly — Kotlin passes `ByteArray` between them.

## 2. Rust JNI Bridge (`ghost-crypto`)

**Native library:** `libghost_crypto.so` (loaded via `System.loadLibrary("ghost_crypto")`)

**Kotlin wrapper:** `GhostCrypto.kt`

### Functions Exposed

```kotlin
object GhostCrypto {
    // Generate 128-byte identity: ed25519_seed(32) + ed25519_pub(32) + x25519_secret(32) + x25519_pub(32)
    external fun generateIdentity(): ByteArray

    // X25519 key agreement → AES-256-GCM encrypt
    // Returns: [ephemeral_pub(32)][nonce(12)][ciphertext][tag(16)]
    external fun encrypt(recipientX25519Pub: ByteArray, plaintext: ByteArray): ByteArray

    // X25519 key agreement → AES-256-GCM decrypt
    external fun decrypt(myX25519Secret: ByteArray, ciphertext: ByteArray): ByteArray

    // Ed25519 signature (64 bytes)
    external fun sign(ed25519Seed: ByteArray, message: ByteArray): ByteArray

    // Ed25519 signature verification
    external fun verify(ed25519Pub: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}
```

### Not Implemented (from RFCs)
- `ghost_split_identity` / `ghost_reconstruct_identity` (Shamir) — planned v1.0
- `ghost_create_prekey` / `ghost_handshake` (X3DH) — planned v1.0
- `ghost_create_packet` (fixed-size packets) — planned v0.3
- `ghost_select_transport` (multi-transport) — planned v0.3

### Memory Safety
- Rust owns its heap. ByteArrays are copied across the JNI boundary
- All Rust functions are pure (no mutable global state) and thread-safe
- Errors translated to Java `RuntimeException` via JNI

## 3. Go Mobile Bridge (`ghostrouter`)

**AAR library:** `ghostrouter.aar` (generated via `gomobile bind`)

**Kotlin wrapper:** `GhostRouter.kt`

### Functions Exposed

```kotlin
class GhostRouter(
    localId: ByteArray,   // SHA-256(ed25519_pub) — 32 bytes
    dbPath: String,       // BoltDB file path
    handler: DeliverHandler
) {
    // Route message to destination. Returns SendResult(isDirect, blob)
    @Throws(Exception::class)
    fun sendMessage(dst: ByteArray, payload: ByteArray): Pair<Boolean, ByteArray?>

    // Notify router of peer in BLE range. Returns list of blobs to send
    fun onPeerDiscovered(peerId: ByteArray, rssi: Int): List<ByteArray>

    // Process incoming routed data. Returns: "delivered"/"forwarded"/"dropped"/"error"
    fun onMessageReceived(data: ByteArray): String

    // Get JSON-encoded stats
    fun getStats(): String

    // Close BoltDB and stop goroutines
    fun stop()
}

// Kotlin implements this interface — Go calls it on message delivery
interface DeliverHandler {
    fun onDeliver(senderId: ByteArray)
}
```

### gomobile Constraints
- `[][]byte` not supported → wrapped in Go `BlobList` struct, unwrapped in Kotlin
- `func` callbacks not supported → `DeliverHandler` Go interface
- Multi-return not supported → wrapped in Go `SendResult` struct
- **CRITICAL:** All `[]byte` params are JNI-backed memory freed after call. Go must `copy()` before storing.

### Not Implemented (from RFCs)
- `IssueCredit` / `GetBalance` / `GetReputation` (economy) — planned v1.0
- `StartGossip` / `StopGossip` (mesh gossip) — planned v1.0
- Protobuf serialization (currently JSON for routing headers)

## 4. Data Format (Not Protobuf)

v0.1.5 uses raw `ByteArray` for all FFI calls. No Protobuf `.proto` files exist yet.

| Data | Format | Size |
|---|---|---|
| Identity | Raw bytes | 128 bytes |
| QR payload | `GHOST:<Base64(ed25519_pub + x25519_pub + name)>` | Variable |
| Encrypted message | `[ephemeral_pub(32)][nonce(12)][ciphertext][tag(16)]` | Variable |
| Signed payload | `[ed25519_pub(32)][username\0message][ed25519_sig(64)]` | Variable |
| Routing header | JSON (not Protobuf) | Variable |

## 5. Thread Safety

| Function | Thread Safe? | Notes |
|---|---|---|
| `GhostCrypto.encrypt` | Yes | Pure function, no mutable state |
| `GhostCrypto.decrypt` | Yes | Pure function, no mutable state |
| `GhostCrypto.sign` | Yes | Pure function, no mutable state |
| `GhostCrypto.generateIdentity` | Yes | Thread-local RNG |
| `GhostRouter.sendMessage` | Yes | BoltDB serializes transactions |
| `GhostRouter.onPeerDiscovered` | Yes | BoltDB serializes transactions |
| `GhostRouter.onMessageReceived` | Yes | BoltDB serializes transactions |

## 6. Performance

| Metric | Measured Value |
|---|---|
| JNI call overhead | ~5μs per call |
| gomobile call overhead | ~10μs per call |
| AES-256-GCM encrypt (400 bytes) | ~50μs |
| Ed25519 sign | ~100μs |
| BoltDB store/get | ~200μs |
| **Total send latency** | **~500μs** (crypto + routing, excludes BLE) |
