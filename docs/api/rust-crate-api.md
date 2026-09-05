# GHOST Protocol Rust Crate API Reference

> **Version:** v0.4.3 — Complete reference for `rust/ghost-crypto/` implementation, pure Rust core decoupling, JNI panic-isolation boundaries, and native test verification.
> **Platform:** Android AOSP / JNI (ARM64 `arm64-v8a`, x86_64 `x86_64`) / Host Native Rust (`cargo test`).

---

## 1. Architectural Overview

The `ghost-crypto` crate is GHOST's cryptographic engine. It implements audited, standardized cryptographic primitives compiled into a native shared library (`libghost_crypto.so`) via `cargo-ndk`.

In **v0.4.3**, the crate is organized into a clean two-tier architecture:
1. **Pure Rust Core Layer (`*_core`):** Pure, thread-safe, heap-isolated Rust functions operating on byte slices (`&[u8]`) and returning `Result<Vec<u8>, String>`. This layer has zero JNI dependencies and is directly testable with `cargo test` on any host architecture.
2. **JNI Exception & Unwind Barrier:** JNI entrypoints exposed to Android's JVM. Every JNI function wraps execution in `std::panic::catch_unwind(AssertUnwindSafe(|| ...))`. Any unexpected panic or memory allocation failure is caught and converted into a standard Java `RuntimeException` via `jni_error()`, preventing process termination or SIGSEGV crashes on Android (Invariants $O_3, O_4$).

```
+-------------------------------------------------------------------------------+
|                             KOTLIN JNI RUNTIME                                |
|  com.ghostprotocol.crypto.GhostCrypto                                         |
+---------------------------------------+---------------------------------------+
                                        | JNI Call
                                        v
+-------------------------------------------------------------------------------+
|                       JNI UNWIND & EXCEPTION BARRIER                          |
|  - std::panic::catch_unwind(AssertUnwindSafe(|| ...))                         |
|  - jni_error() runtime exception translation                                  |
|  - JVM DirectBuffer <-> Rust Vec byte slice conversion                        |
+---------------------------------------+---------------------------------------+
                                        | Pure Rust Slices
                                        v
+-------------------------------------------------------------------------------+
|                               PURE RUST CORE                                  |
|  generate_identity_core()                                                     |
|  encrypt_core()               decrypt_core()                                  |
|  sign_core()                  verify_core()                                   |
|                                                                               |
|  Dependencies: x25519-dalek, ed25519-dalek, aes-gcm, rand/OsRng               |
+-------------------------------------------------------------------------------+
```

---

## 2. Pure Rust Core API

These functions contain the cryptographic logic and can be used directly in native Rust environments, integration benchmarks, and host test harnesses:

### 2.1 Identity Generation
```rust
pub fn generate_identity_core() -> Vec<u8>
```
- **Description:** Generates a fresh 128-byte cryptographic identity using the OS CSPRNG (`rand::rngs::OsRng`).
- **Output Format (128 bytes):**
  $$\text{ed25519\_seed (32B)} \parallel \text{ed25519\_pub (32B)} \parallel \text{x25519\_secret (32B)} \parallel \text{x25519\_pub (32B)}$$
- **Invariants:**
  - `ed25519_pub` is derived deterministically from `ed25519_seed`.
  - `x25519_pub` is derived deterministically via scalar multiplication from `x25519_secret`.

### 2.2 Authenticated Encryption (X25519 ECDH + AES-256-GCM)
```rust
pub fn encrypt_core(recipient_x25519_pub: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, String>
```
- **Input:**
  - `recipient_x25519_pub`: Recipient's 32-byte static X25519 public key.
  - `plaintext`: Arbitrary byte slice.
- **Output (Variable length):**
  $$\text{ephemeral\_pub (32B)} \parallel \text{nonce (12B)} \parallel \text{ciphertext} \parallel \text{tag (16B)}$$
  *(Minimum length: 60 bytes for a 0-byte payload).*
- **Process:**
  1. Generates an ephemeral X25519 secret key via `EphemeralSecret::random_from_rng(OsRng)`.
  2. Computes the Diffie-Hellman shared secret: `shared_secret = ephemeral_secret.diffie_hellman(recipient_pub)`.
  3. Generates a random 12-byte nonce via `OsRng`.
  4. Encrypts and authenticates `plaintext` using `Aes256Gcm::new(Key::from_slice(shared_secret.as_bytes()))`.
  5. Prefixes the ephemeral public key and nonce to the ciphertext and tag.

### 2.3 Authenticated Decryption
```rust
pub fn decrypt_core(my_x25519_secret: &[u8], ciphertext: &[u8]) -> Result<Vec<u8>, String>
```
- **Input:**
  - `my_x25519_secret`: Local 32-byte static X25519 secret key.
  - `ciphertext`: Output buffer produced by `encrypt_core()`.
- **Validation:**
  - Enforces `my_x25519_secret.len() == 32`.
  - Enforces `ciphertext.len() >= 60` ($32 + 12 + 0 + 16$).
- **Process:**
  1. Extracts `ephemeral_pub` (bytes `0..32`).
  2. Extracts `nonce` (bytes `32..44`).
  3. Extracts encrypted body and authentication tag (bytes `44..`).
  4. Computes Diffie-Hellman shared secret: `shared_secret = my_secret.diffie_hellman(ephemeral_pub)`.
  5. Decrypts and verifies tag via `Aes256Gcm`. Returns decrypted `Vec<u8>` or `Err` if authentication fails or data was tampered with.

### 2.4 Digital Signature (RFC 8032 Ed25519)
```rust
pub fn sign_core(ed25519_seed: &[u8], message: &[u8]) -> Result<Vec<u8>, String>
```
- **Input:**
  - `ed25519_seed`: Local 32-byte Ed25519 private seed.
  - `message`: Payload bytes to authenticate.
- **Output:** Exactly 64 bytes containing the canonical Ed25519 signature.
- **Invariants:**
  - Strictly deterministic per RFC 8032. Signing identical message bytes with the same seed always produces identical signature bytes.

### 2.5 Signature Verification
```rust
pub fn verify_core(pubkey_bytes: &[u8], message_bytes: &[u8], sig_bytes: &[u8]) -> bool
```
- **Input:**
  - `pubkey_bytes`: Signer's 32-byte Ed25519 public key.
  - `message_bytes`: Original unencrypted payload.
  - `sig_bytes`: 64-byte Ed25519 signature.
- **Validation:** Enforces `pubkey_bytes.len() == 32` and `sig_bytes.len() == 64`.
- **Output:** Returns `true` if and only if the signature is valid for the public key and message. Never panics on malformed input.

---

## 3. JNI Export Layer & Unwind Isolation

All JNI functions are exposed under the package name `com.ghostprotocol.crypto.GhostCrypto`:

```rust
#[no_mangle]
pub extern "C" fn Java_com_ghostprotocol_crypto_GhostCrypto_generateIdentity(
    mut env: JNIEnv, _class: JClass
) -> jbyteArray

#[no_mangle]
pub extern "C" fn Java_com_ghostprotocol_crypto_GhostCrypto_encrypt(
    mut env: JNIEnv, _class: JClass,
    recipient_x25519_pub: jbyteArray,
    plaintext: jbyteArray
) -> jbyteArray

#[no_mangle]
pub extern "C" fn Java_com_ghostprotocol_crypto_GhostCrypto_decrypt(
    mut env: JNIEnv, _class: JClass,
    my_x25519_secret: jbyteArray,
    ciphertext: jbyteArray
) -> jbyteArray

#[no_mangle]
pub extern "C" fn Java_com_ghostprotocol_crypto_GhostCrypto_sign(
    mut env: JNIEnv, _class: JClass,
    ed25519_seed: jbyteArray,
    message: jbyteArray
) -> jbyteArray

#[no_mangle]
pub extern "C" fn Java_com_ghostprotocol_crypto_GhostCrypto_verify(
    mut env: JNIEnv, _class: JClass,
    ed25519_pub: jbyteArray,
    message: jbyteArray,
    signature: jbyteArray
) -> jboolean
```

### Unwind Isolation Pattern:
```rust
fn jni_error(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/RuntimeException", msg);
}

// Example from Java_com_ghostprotocol_crypto_GhostCrypto_encrypt:
let outcome = catch_unwind(AssertUnwindSafe(|| {
    encrypt_core(&pubkey_bytes, &plaintext_bytes)
}));

match outcome {
    Ok(Ok(encrypted)) => env.byte_array_from_slice(&encrypted).unwrap_or(std::ptr::null_mut()),
    Ok(Err(err_msg)) => {
        jni_error(&mut env, &format!("Encryption error: {}", err_msg));
        std::ptr::null_mut()
    }
    Err(_) => {
        jni_error(&mut env, "Panic during encryption");
        std::ptr::null_mut()
    }
}
```

---

## 4. Cryptographic Primitives & Dependency Audit

| Primitive | Crate & Version | Standard | Purpose |
|---|---|---|---|
| **X25519** | `x25519-dalek 2.0` | RFC 7748 | Ephemeral Diffie-Hellman key agreement |
| **Ed25519** | `ed25519-dalek 2.1` | RFC 8032 | Deterministic digital signatures (authentication, integrity, deduplication) |
| **AES-256-GCM** | `aes-gcm 0.10` | NIST SP 800-38D | Authenticated encryption with associated data (AEAD) |
| **CSPRNG** | `rand 0.8` (`OsRng`) | AOSP `/dev/urandom` | Ephemeral key generation, nonce generation |

---

## 5. Native Test Suite Coverage

The native test suite in `rust/ghost-crypto/src/lib.rs` executes without requiring the Android emulator or JVM:

```bash
cd rust/ghost-crypto
cargo test
```

### Verified Test Cases:
1. `test_generate_identity_lengths_and_uniqueness`: Verifies exact 128-byte layout, ensures two consecutive identity calls produce distinct entropy, and confirms that the generated Ed25519 public key successfully verifies signatures produced by the seed.
2. `test_encrypt_decrypt_roundtrip`: Verifies full end-to-end encryption and decryption cycle, confirming exact payload preservation.
3. `test_tampered_ciphertext_fails`: Flips a single bit in the ciphertext payload; verifies that AES-GCM tag verification rejects the payload with an authentication error.
4. `test_tampered_nonce_fails`: Flips a single bit in the 12-byte nonce field; confirms immediate authentication failure.
5. `test_tampered_ephemeral_pub_fails`: Flips a bit in the 32-byte ephemeral public key; confirms key agreement mismatch and decryption failure.
6. `test_short_ciphertext_fails`: Supplies a 59-byte buffer (1 byte shorter than the mandatory 60-byte minimum header); confirms graceful error return without buffer underflow or panic.
7. `test_wrong_secret_decrypt_fails`: Attempts decryption of valid ciphertext using an unrelated X25519 secret key; confirms authentication failure.
8. `test_sign_verify_roundtrip`: Confirms 64-byte signature generation and verification.
9. `test_tampered_signature_fails`: Mutates signature bytes; confirms `verify_core()` returns `false`.
10. `test_tampered_message_fails`: Mutates plaintext message bytes; confirms `verify_core()` returns `false`.
11. `test_wrong_pubkey_fails`: Verifies signature with an alternate public key; confirms rejection.
12. `test_invalid_key_lengths`: Passes truncated 16-byte keys to encrypt, decrypt, sign, and verify; confirms all return clean errors rather than panicking.

---

## 6. Forward Compatibility & RFC Roadmap

The `ghost-crypto` crate is the foundational Layer 3 primitive. The higher-layer abstractions specified in the RFC roadmap build upon these primitives:
- `ghost-common` — Shared types and Blake3 hashing.
- `ghost-physics` — Omnimodal physical layer orchestration.
- `ghost-privacy` — Chaff generation, packet morphing, and steganographic shaping.
- `ghost-transport` — ML-KEM-1024 post-quantum hybrid KEM and Double Ratchet.
- `ghost-identity` — Shamir Secret Sharing threshold recovery.
