# GHOST Cryptographic Subsystem (Rust)

> **Directory:** `rust/`  
> **Primary Crate:** `rust/ghost-crypto/`  
> **Rust Version:** 1.75+ (Edition 2021)  
> **Output Artifact:** `libghost_crypto.so` (JNI shared library)

---

## 1. Purpose & Responsibilities

The Rust subsystem implements the core cryptographic primitives for the GHOST Protocol:
- **Key Agreement:** X25519 (RFC 7748) for Diffie-Hellman ephemeral shared secret derivation.
- **Authenticated Symmetric Encryption:** AES-256-GCM (NIST SP 800-38D) with unique 12-byte nonces.
- **Deterministic Digital Signatures:** Ed25519 (RFC 8032) for sender authentication and duplicate packet dropping.
- **Identity Derivation:** 128-byte identity keypair generation using the system CSPRNG (`OsRng`).

---

## 2. Two-Tier Architecture

To achieve both high testability and rock-solid mobile runtime stability, the crate is bifurcated into two layers:

1. **Pure Rust Core Layer (`*_core`):**
   - Pure, stateless, thread-safe functions operating on raw byte slices:
     - `generate_identity_core() -> Vec<u8>`
     - `encrypt_core(recipient_x25519_pub, plaintext) -> Result<Vec<u8>, String>`
     - `decrypt_core(my_x25519_secret, ciphertext) -> Result<Vec<u8>, String>`
     - `sign_core(ed25519_seed, message) -> Result<Vec<u8>, String>`
     - `verify_core(pubkey_bytes, message_bytes, sig_bytes) -> bool`
   - Zero JNI dependencies. Directly testable on any developer workstation with `cargo test`.
2. **JNI Unwind Isolation Barrier:**
   - JNI entrypoints exposed to Android under `com.ghostprotocol.crypto.GhostCrypto`.
   - Every function wraps execution in `std::panic::catch_unwind(AssertUnwindSafe(|| ...))`.
   - Any panic or allocation failure is safely caught and converted into a standard Java `RuntimeException` via `jni_error()`, preventing process termination or SIGSEGV crashes on Android (Invariants $O_3, O_4$).

---

## 3. Testing & Verification

### Running Native Host Tests
```bash
cd rust/ghost-crypto
cargo test
```
*Executes 12 test cases covering identity uniqueness, roundtrip encryption/decryption, tampered ciphertext rejection, tampered nonces, wrong keys, and invalid key lengths.*

### Building for Android ABIs (`cargo-ndk`)
```bash
# Requires cargo-ndk: cargo install cargo-ndk
cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -o ../android/app/src/main/jniLibs \
  build --release -p ghost-crypto
```
