use jni::JNIEnv;
use jni::objects::{JByteArray, JObject};
use jni::sys::jboolean;
use ed25519_dalek::{SigningKey, Signer, VerifyingKey, Verifier, Signature};
use x25519_dalek::{StaticSecret, PublicKey as X25519PublicKey};
use rand::rngs::OsRng;
use rand::RngCore;
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use aes_gcm::aead::{Aead, AeadCore};
use sha2::{Sha256, Digest};
use std::convert::TryInto;
use std::panic::{catch_unwind, AssertUnwindSafe};

fn jni_error(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/RuntimeException", msg);
}

// ---------------------------------------------------------------------------
// Pure Rust Cryptographic Core (Panic-free & unit testable)
// ---------------------------------------------------------------------------

pub fn generate_identity_core() -> [u8; 128] {
    let mut csprng = OsRng;
    let mut seed = [0u8; 32];
    csprng.fill_bytes(&mut seed);
    let ed25519_signing_key = SigningKey::from_bytes(&seed);
    let ed25519_pubkey = ed25519_signing_key.verifying_key().to_bytes();

    let x25519_secret = StaticSecret::random_from_rng(OsRng);
    let x25519_pubkey = X25519PublicKey::from(&x25519_secret);

    let mut result = [0u8; 128];
    result[0..32].copy_from_slice(&seed);
    result[32..64].copy_from_slice(&ed25519_pubkey);
    result[64..96].copy_from_slice(x25519_secret.as_bytes());
    result[96..128].copy_from_slice(x25519_pubkey.as_bytes());
    result
}

pub fn encrypt_core(recipient_pub: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, &'static str> {
    if recipient_pub.len() != 32 {
        return Err("recipient_pub must be 32 bytes");
    }
    let mut pub_arr = [0u8; 32];
    pub_arr.copy_from_slice(recipient_pub);
    let recipient_pub_key = X25519PublicKey::from(pub_arr);

    let ephemeral_secret = StaticSecret::random_from_rng(OsRng);
    let ephemeral_pubkey = X25519PublicKey::from(&ephemeral_secret);
    let shared_secret = ephemeral_secret.diffie_hellman(&recipient_pub_key);

    let mut hasher = Sha256::new();
    hasher.update(shared_secret.as_bytes());
    let aes_key = hasher.finalize();

    let cipher = Aes256Gcm::new_from_slice(&aes_key)
        .map_err(|_| "Failed to initialize AES cipher")?;
    let nonce = Aes256Gcm::generate_nonce(&mut OsRng);

    let ciphertext = cipher.encrypt(&nonce, plaintext)
        .map_err(|_| "Encryption failed")?;

    let mut result = Vec::with_capacity(32 + 12 + ciphertext.len());
    result.extend_from_slice(ephemeral_pubkey.as_bytes());
    result.extend_from_slice(nonce.as_slice());
    result.extend_from_slice(&ciphertext);
    Ok(result)
}

pub fn decrypt_core(my_secret: &[u8], ciphertext_bundle: &[u8]) -> Result<Vec<u8>, &'static str> {
    if my_secret.len() != 32 {
        return Err("my_secret must be 32 bytes");
    }
    if ciphertext_bundle.len() < 32 + 12 + 16 {
        return Err("Ciphertext too short (minimum 60 bytes: 32 pub + 12 nonce + 16 tag)");
    }

    let ephemeral_pub_bytes = &ciphertext_bundle[0..32];
    let nonce_bytes = &ciphertext_bundle[32..44];
    let ciphertext = &ciphertext_bundle[44..];

    let mut pub_arr = [0u8; 32];
    pub_arr.copy_from_slice(ephemeral_pub_bytes);
    let ephemeral_pub_key = X25519PublicKey::from(pub_arr);

    let mut sec_arr = [0u8; 32];
    sec_arr.copy_from_slice(my_secret);
    let static_secret = StaticSecret::from(sec_arr);

    let shared_secret = static_secret.diffie_hellman(&ephemeral_pub_key);

    let mut hasher = Sha256::new();
    hasher.update(shared_secret.as_bytes());
    let aes_key = hasher.finalize();

    let cipher = Aes256Gcm::new_from_slice(&aes_key)
        .map_err(|_| "Failed to initialize AES cipher")?;
    let nonce = Nonce::from_slice(nonce_bytes);

    cipher.decrypt(nonce, ciphertext)
        .map_err(|_| "Decryption failed: integrity check failed or wrong key")
}

pub fn sign_core(seed: &[u8], message: &[u8]) -> Result<[u8; 64], &'static str> {
    if seed.len() != 32 {
        return Err("seed must be 32 bytes");
    }
    let seed_arr: &[u8; 32] = seed.try_into().map_err(|_| "Invalid seed length")?;
    let signing_key = SigningKey::from_bytes(seed_arr);
    let signature = signing_key.sign(message);
    Ok(signature.to_bytes())
}

pub fn verify_core(pubkey: &[u8], message: &[u8], signature: &[u8]) -> bool {
    if pubkey.len() != 32 || signature.len() != 64 {
        return false;
    }
    let pubkey_arr: &[u8; 32] = match pubkey.try_into() {
        Ok(a) => a,
        Err(_) => return false,
    };
    let verifying_key = match VerifyingKey::from_bytes(pubkey_arr) {
        Ok(k) => k,
        Err(_) => return false,
    };
    let sig_arr: &[u8; 64] = match signature.try_into() {
        Ok(a) => a,
        Err(_) => return false,
    };
    let sig = Signature::from_bytes(sig_arr);
    verifying_key.verify(message, &sig).is_ok()
}

// ---------------------------------------------------------------------------
// JNI Exports with Panic Safety Boundaries
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_ghostprotocol_crypto_GhostCrypto_generateIdentity<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> JByteArray<'local> {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        generate_identity_core()
    }));

    match outcome {
        Ok(id_bytes) => {
            match env.byte_array_from_slice(&id_bytes) {
                Ok(arr) => arr,
                Err(e) => {
                    jni_error(&mut env, &format!("Failed to create byte array: {}", e));
                    JByteArray::default()
                }
            }
        }
        Err(_) => {
            jni_error(&mut env, "Panic in generateIdentity");
            JByteArray::default()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ghostprotocol_crypto_GhostCrypto_encrypt<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    recipient_pub: JByteArray<'local>,
    plaintext: JByteArray<'local>,
) -> JByteArray<'local> {
    let recipient_pub_bytes = match env.convert_byte_array(&recipient_pub) {
        Ok(b) => b,
        Err(_) => {
            jni_error(&mut env, "Failed to read recipient_pub");
            return JByteArray::default();
        }
    };
    let plaintext_bytes = match env.convert_byte_array(&plaintext) {
        Ok(b) => b,
        Err(_) => {
            jni_error(&mut env, "Failed to read plaintext");
            return JByteArray::default();
        }
    };

    let outcome = catch_unwind(AssertUnwindSafe(|| {
        encrypt_core(&recipient_pub_bytes, &plaintext_bytes)
    }));

    match outcome {
        Ok(Ok(encrypted_bytes)) => {
            match env.byte_array_from_slice(&encrypted_bytes) {
                Ok(arr) => arr,
                Err(e) => {
                    jni_error(&mut env, &format!("Failed to create byte array: {}", e));
                    JByteArray::default()
                }
            }
        }
        Ok(Err(err_msg)) => {
            jni_error(&mut env, err_msg);
            JByteArray::default()
        }
        Err(_) => {
            jni_error(&mut env, "Panic during encryption");
            JByteArray::default()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ghostprotocol_crypto_GhostCrypto_decrypt<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    my_secret: JByteArray<'local>,
    ciphertext_bundle: JByteArray<'local>,
) -> JByteArray<'local> {
    let my_secret_bytes = match env.convert_byte_array(&my_secret) {
        Ok(b) => b,
        Err(_) => {
            jni_error(&mut env, "Failed to read my_secret");
            return JByteArray::default();
        }
    };
    let ciphertext_bytes = match env.convert_byte_array(&ciphertext_bundle) {
        Ok(b) => b,
        Err(_) => {
            jni_error(&mut env, "Failed to read ciphertext_bundle");
            return JByteArray::default();
        }
    };

    let outcome = catch_unwind(AssertUnwindSafe(|| {
        decrypt_core(&my_secret_bytes, &ciphertext_bytes)
    }));

    match outcome {
        Ok(Ok(decrypted_bytes)) => {
            match env.byte_array_from_slice(&decrypted_bytes) {
                Ok(arr) => arr,
                Err(e) => {
                    jni_error(&mut env, &format!("Failed to create byte array: {}", e));
                    JByteArray::default()
                }
            }
        }
        Ok(Err(err_msg)) => {
            jni_error(&mut env, err_msg);
            JByteArray::default()
        }
        Err(_) => {
            jni_error(&mut env, "Panic during decryption");
            JByteArray::default()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ghostprotocol_crypto_GhostCrypto_sign<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    seed: JByteArray<'local>,
    message: JByteArray<'local>,
) -> JByteArray<'local> {
    let seed_bytes = match env.convert_byte_array(&seed) {
        Ok(b) => b,
        Err(_) => {
            jni_error(&mut env, "Failed to read seed");
            return JByteArray::default();
        }
    };
    let message_bytes = match env.convert_byte_array(&message) {
        Ok(b) => b,
        Err(_) => {
            jni_error(&mut env, "Failed to read message");
            return JByteArray::default();
        }
    };

    let outcome = catch_unwind(AssertUnwindSafe(|| {
        sign_core(&seed_bytes, &message_bytes)
    }));

    match outcome {
        Ok(Ok(sig_bytes)) => {
            match env.byte_array_from_slice(&sig_bytes) {
                Ok(arr) => arr,
                Err(e) => {
                    jni_error(&mut env, &format!("Failed to create byte array: {}", e));
                    JByteArray::default()
                }
            }
        }
        Ok(Err(err_msg)) => {
            jni_error(&mut env, err_msg);
            JByteArray::default()
        }
        Err(_) => {
            jni_error(&mut env, "Panic during signing");
            JByteArray::default()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ghostprotocol_crypto_GhostCrypto_verify<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    pubkey: JByteArray<'local>,
    message: JByteArray<'local>,
    signature: JByteArray<'local>,
) -> jboolean {
    let pubkey_bytes = match env.convert_byte_array(&pubkey) {
        Ok(b) => b,
        Err(_) => return jni::sys::JNI_FALSE,
    };
    let message_bytes = match env.convert_byte_array(&message) {
        Ok(b) => b,
        Err(_) => return jni::sys::JNI_FALSE,
    };
    let sig_bytes = match env.convert_byte_array(&signature) {
        Ok(b) => b,
        Err(_) => return jni::sys::JNI_FALSE,
    };

    let outcome = catch_unwind(AssertUnwindSafe(|| {
        verify_core(&pubkey_bytes, &message_bytes, &sig_bytes)
    }));

    match outcome {
        Ok(valid) => {
            if valid {
                jni::sys::JNI_TRUE
            } else {
                jni::sys::JNI_FALSE
            }
        }
        Err(_) => {
            jni_error(&mut env, "Panic during verification");
            jni::sys::JNI_FALSE
        }
    }
}

// ---------------------------------------------------------------------------
// Unit Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_generate_identity_lengths_and_uniqueness() {
        let id1 = generate_identity_core();
        let id2 = generate_identity_core();

        assert_eq!(id1.len(), 128);
        assert_eq!(id2.len(), 128);
        assert_ne!(id1, id2, "Subsequent identities must not be identical");

        // Ed25519 seed is at 0..32, pub at 32..64
        let msg = b"GHOST identity verification test";
        let sig = sign_core(&id1[0..32], msg).expect("Signing must succeed");
        assert!(verify_core(&id1[32..64], msg, &sig), "Signature must verify with pubkey");
    }

    #[test]
    fn test_encrypt_decrypt_roundtrip() {
        let id = generate_identity_core();
        let recipient_x25519_secret = &id[64..96];
        let recipient_x25519_pub = &id[96..128];

        let plaintext = b"GHOST mesh test message payload: hello world!";
        let ciphertext = encrypt_core(recipient_x25519_pub, plaintext)
            .expect("Encryption must succeed");

        // Ciphertext should be 32 (ephemeral pub) + 12 (nonce) + plaintext.len() + 16 (tag)
        assert_eq!(ciphertext.len(), 32 + 12 + plaintext.len() + 16);

        let decrypted = decrypt_core(recipient_x25519_secret, &ciphertext)
            .expect("Decryption must succeed");
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn test_tampered_ciphertext_fails() {
        let id = generate_identity_core();
        let recipient_x25519_secret = &id[64..96];
        let recipient_x25519_pub = &id[96..128];

        let plaintext = b"Sensitive payload";
        let mut ciphertext = encrypt_core(recipient_x25519_pub, plaintext).unwrap();

        // Flip one bit in the ciphertext / tag region
        let last_idx = ciphertext.len() - 1;
        ciphertext[last_idx] ^= 0x01;

        let result = decrypt_core(recipient_x25519_secret, &ciphertext);
        assert!(result.is_err(), "Decryption must fail when ciphertext is tampered");
    }

    #[test]
    fn test_tampered_nonce_fails() {
        let id = generate_identity_core();
        let recipient_x25519_secret = &id[64..96];
        let recipient_x25519_pub = &id[96..128];

        let plaintext = b"Sensitive payload";
        let mut ciphertext = encrypt_core(recipient_x25519_pub, plaintext).unwrap();

        // Flip one bit in the nonce (indices 32..44)
        ciphertext[35] ^= 0x01;

        let result = decrypt_core(recipient_x25519_secret, &ciphertext);
        assert!(result.is_err(), "Decryption must fail when nonce is tampered");
    }

    #[test]
    fn test_tampered_ephemeral_pub_fails() {
        let id = generate_identity_core();
        let recipient_x25519_secret = &id[64..96];
        let recipient_x25519_pub = &id[96..128];

        let plaintext = b"Sensitive payload";
        let mut ciphertext = encrypt_core(recipient_x25519_pub, plaintext).unwrap();

        // Flip one bit in ephemeral public key (indices 0..32)
        ciphertext[5] ^= 0x01;

        let result = decrypt_core(recipient_x25519_secret, &ciphertext);
        assert!(result.is_err(), "Decryption must fail when ephemeral pub is tampered");
    }

    #[test]
    fn test_short_ciphertext_fails() {
        let id = generate_identity_core();
        let recipient_x25519_secret = &id[64..96];

        let short_data = [0u8; 59]; // 1 byte less than minimum 60
        let result = decrypt_core(recipient_x25519_secret, &short_data);
        assert!(result.is_err());
    }

    #[test]
    fn test_wrong_secret_decrypt_fails() {
        let id1 = generate_identity_core();
        let id2 = generate_identity_core();

        let recipient_x25519_pub = &id1[96..128];
        let wrong_secret = &id2[64..96];

        let plaintext = b"Top secret mesh dispatch";
        let ciphertext = encrypt_core(recipient_x25519_pub, plaintext).unwrap();

        let result = decrypt_core(wrong_secret, &ciphertext);
        assert!(result.is_err(), "Decryption with wrong secret key must fail");
    }

    #[test]
    fn test_sign_verify_roundtrip() {
        let id = generate_identity_core();
        let seed = &id[0..32];
        let pubkey = &id[32..64];

        let msg = b"GHOST protocol batch hash 0xdeadbeef";
        let sig = sign_core(seed, msg).expect("Signing must succeed");
        assert_eq!(sig.len(), 64);

        assert!(verify_core(pubkey, msg, &sig), "Verification must succeed");
    }

    #[test]
    fn test_tampered_signature_fails() {
        let id = generate_identity_core();
        let seed = &id[0..32];
        let pubkey = &id[32..64];

        let msg = b"GHOST protocol batch hash 0xdeadbeef";
        let mut sig = sign_core(seed, msg).unwrap();
        sig[10] ^= 0x80;

        assert!(!verify_core(pubkey, msg, &sig), "Tampered signature must not verify");
    }

    #[test]
    fn test_tampered_message_fails() {
        let id = generate_identity_core();
        let seed = &id[0..32];
        let pubkey = &id[32..64];

        let msg = b"Original message";
        let tampered_msg = b"Tampered message";
        let sig = sign_core(seed, msg).unwrap();

        assert!(!verify_core(pubkey, tampered_msg, &sig), "Tampered message must not verify");
    }

    #[test]
    fn test_wrong_pubkey_fails() {
        let id1 = generate_identity_core();
        let id2 = generate_identity_core();

        let msg = b"Some broadcast";
        let sig = sign_core(&id1[0..32], msg).unwrap();

        assert!(!verify_core(&id2[32..64], msg, &sig), "Different pubkey must reject signature");
    }

    #[test]
    fn test_invalid_key_lengths() {
        let short_key = [0u8; 16];
        assert!(encrypt_core(&short_key, b"hello").is_err());
        assert!(decrypt_core(&short_key, &[0u8; 70]).is_err());
        assert!(sign_core(&short_key, b"hello").is_err());
        assert!(!verify_core(&short_key, b"hello", &[0u8; 64]));
        assert!(!verify_core(&[0u8; 32], b"hello", &short_key));
    }
}
