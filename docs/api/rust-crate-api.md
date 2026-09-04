# GHOST Protocol Rust Crate API Reference

> **Version:** v0.3.5 — reflects actual implemented code in `rust/ghost-crypto/`.
> The `ghost-common`, `ghost-physics`, `ghost-privacy`, `ghost-transport`, and `ghost-identity` crates described in RFCs do not exist yet.
>
> **v0.3.5 Primitive Invariance:** The 5 Rust cryptographic primitives (Identity Gen, X25519 ECDH + AES-256-GCM Encrypt/Decrypt, Ed25519 Sign/Verify) provide complete cryptographic coverage across all versions: 1:1 direct messages, v0.3.0 Protest Mode discovery handshakes (`0x20`/`0x21`), 24-hour rotating short codes (`0x22`/`0x23`), and v0.3.5 Cell Group pairwise envelopes (`0x30`). Zero changes to native Rust code were required.

## ghost-crypto (Implemented)

**Crate:** `rust/ghost-crypto/` (~300 lines)
**Native library:** `libghost_crypto.so` (compiled per ABI via cargo-ndk)

### JNI Functions

All functions are exposed to Kotlin via JNI. The Rust crate contains no public Rust API — it's purely a JNI bridge.

```rust
// Generate 128-byte identity blob
// Returns: ed25519_seed(32) + ed25519_pub(32) + x25519_secret(32) + x25519_pub(32)
#[no_mangle]
pub extern "C" fn Java_com_ghostprotocol_crypto_GhostCrypto_generateIdentity(
    env: JNIEnv, _class: JClass
) -> jbyteArray

// X25519 ECDH key agreement → AES-256-GCM encryption
// Input: recipient's X25519 public key (32 bytes), plaintext (variable)
// Output: ephemeral_pub(32) + nonce(12) + ciphertext + tag(16)
#[no_mangle]
pub extern "C" fn Java_com_ghostprotocol_crypto_GhostCrypto_encrypt(
    env: JNIEnv, _class: JClass,
    recipient_x25519_pub: jbyteArray,
    plaintext: jbyteArray
) -> jbyteArray

// X25519 ECDH key agreement → AES-256-GCM decryption
// Input: my X25519 secret key (32 bytes), ciphertext (from encrypt())
// Output: plaintext
#[no_mangle]
pub extern "C" fn Java_com_ghostprotocol_crypto_GhostCrypto_decrypt(
    env: JNIEnv, _class: JClass,
    my_x25519_secret: jbyteArray,
    ciphertext: jbyteArray
) -> jbyteArray

// Ed25519 digital signature
// Input: ed25519_seed (32 bytes), message (variable)
// Output: signature (64 bytes)
#[no_mangle]
pub extern "C" fn Java_com_ghostprotocol_crypto_GhostCrypto_sign(
    env: JNIEnv, _class: JClass,
    ed25519_seed: jbyteArray,
    message: jbyteArray
) -> jbyteArray

// Ed25519 signature verification
// Input: ed25519_pub (32 bytes), message (variable), signature (64 bytes)
// Output: boolean
#[no_mangle]
pub extern "C" fn Java_com_ghostprotocol_crypto_GhostCrypto_verify(
    env: JNIEnv, _class: JClass,
    ed25519_pub: jbyteArray,
    message: jbyteArray,
    signature: jbyteArray
) -> jboolean
```

### Cryptographic Primitives Used

| Primitive | Library | Purpose |
|---|---|---|
| X25519 | `x25519-dalek` | Elliptic-curve Diffie-Hellman key agreement |
| Ed25519 | `ed25519-dalek` | Digital signatures (authentication + integrity) |
| AES-256-GCM | `aes-gcm` | Authenticated encryption |
| CSPRNG | `rand` / `OsRng` | Key generation, nonce generation |

### Crypto Flow

```
Encrypt:
  1. Generate ephemeral X25519 keypair
  2. ECDH(ephemeral_secret, recipient_pub) → shared_secret
  3. AES-256-GCM encrypt(shared_secret, nonce, plaintext)
  4. Return: ephemeral_pub ‖ nonce ‖ ciphertext ‖ tag

Decrypt:
  1. Extract ephemeral_pub from ciphertext
  2. ECDH(my_secret, ephemeral_pub) → shared_secret
  3. AES-256-GCM decrypt(shared_secret, nonce, ciphertext)
  4. Return: plaintext
```

### Security Properties
- **Forward secrecy:** Each message uses a fresh ephemeral X25519 keypair. Compromising the long-term key does not compromise past messages.
- **Authenticated encryption:** AES-256-GCM provides both confidentiality and integrity.
- **Sender authentication:** Ed25519 signature over `ed25519_pub ‖ plaintext` proves sender identity.

### Not Implemented (from RFCs)
- `ghost-common` — shared types and BLAKE3 hashing
- `ghost-physics` — BLE, ultrasonic, WiFi Direct, IR, infrasonic transports
- `ghost-privacy` — packet morphing, cover traffic, steganography
- `ghost-transport` — X3DH handshake, Double Ratchet, ML-KEM-1024, ML-DSA-65
- `ghost-identity` — Shamir secret sharing, biometric seed, guardian selection

All planned for v1.0. See `docs/rfc/` for design specs.
