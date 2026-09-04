# GHOST Protocol Kotlin FFI Bridge Reference

> **Version:** v0.3.5 — Technical reference for native JNI, gomobile, and subsystem coordination.  
> **Platform:** Android AOSP / JNI / gomobile.

---

## 1. Architectural Overview

Kotlin acts as the system coordinator on Android. It interfaces with:
1. **Rust (`libghost_crypto.so`) via JNI** for asymmetric key agreement (X25519), authenticated encryption (AES-256-GCM), and digital signatures (Ed25519).
2. **Go (`ghostrouter.aar`) via gomobile** for delay-tolerant Spray-and-Wait mesh routing and BoltDB storage.

Rust and Go share zero process memory; all payloads cross boundaries as raw `ByteArray` objects managed by Kotlin.

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

### Invariants:
- All Rust functions are pure, stateless, and thread-safe.
- Inputs and outputs are copied across the JNI boundary to prevent dangling pointers.
- Panics in Rust translate to standard Java exceptions via `catch_unwind`.

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
- `[][]byte` is unsupported by gomobile $\rightarrow$ wrapped in Go `BlobList` with `.Size()` and `.Get(i)`.
- `[]byte` arguments are backed by JVM direct byte buffers that can be freed when the native call returns. The Go router explicitly creates independent heap copies (`make([]byte, len(src))`) before storing in BoltDB.

---

## 4. Complete Wire Protocol Layouts

```
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
```

---

## 5. Deduplication Invariants

1. **Never Hash Ciphertext for Deduplication:** Because each transmission generates fresh ephemeral X25519 keys and AES nonces, ciphertexts are non-deterministic.
2. **Deterministic Ed25519 Signatures (RFC 8032):** Senders compute signatures over `(senderEd25519Pub || plaintext)`. The recipient hashes the resulting 64-byte signature and checks against a 60-second in-memory LRU cache (`recentMessageSignatures`). Any duplicate arrival (direct, re-encrypted, or relayed) is immediately dropped.

---

## 6. FFI Latency & Overhead

Measured on Google Pixel 7 (ARM64):

| Operation | Latency |
|---|---|
| JNI transition overhead | ~5 μs |
| gomobile transition overhead | ~10 μs |
| X25519 key agreement + AES-256-GCM encrypt (200B) | ~45 μs |
| Ed25519 digital signature generation | ~90 μs |
| Ed25519 digital signature verification | ~180 μs |
| BoltDB transaction write | ~200 μs |
| **Total local processing latency per message** | **< 1 millisecond** (excludes BLE radio) |
