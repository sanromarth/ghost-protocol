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

fn jni_error<'a>(env: &mut JNIEnv<'a>, msg: &str) {
    let _ = env.throw_new("java/lang/RuntimeException", msg);
}

#[no_mangle]
pub extern "system" fn Java_com_ghostprotocol_crypto_GhostCrypto_generateIdentity<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> JByteArray<'local> {
    let mut csprng = OsRng;
    let mut seed = [0u8; 32];
    csprng.fill_bytes(&mut seed);
    let ed25519_signing_key = SigningKey::from_bytes(&seed);
    let ed25519_pubkey = ed25519_signing_key.verifying_key().to_bytes();

    let x25519_secret = StaticSecret::random_from_rng(OsRng);
    let x25519_pubkey = X25519PublicKey::from(&x25519_secret);

    let mut result = Vec::with_capacity(128);
    result.extend_from_slice(&seed);
    result.extend_from_slice(&ed25519_pubkey);
    result.extend_from_slice(x25519_secret.as_bytes());
    result.extend_from_slice(x25519_pubkey.as_bytes());

    match env.byte_array_from_slice(&result) {
        Ok(arr) => arr,
        Err(e) => {
            jni_error(&mut env, &format!("Failed to create byte array: {}", e));
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
    if recipient_pub_bytes.len() != 32 {
        jni_error(&mut env, "recipient_pub must be 32 bytes");
        return JByteArray::default();
    }
    let recipient_pub_key = {
        let mut arr = [0u8; 32];
        arr.copy_from_slice(&recipient_pub_bytes);
        X25519PublicKey::from(arr)
    };

    let plaintext_bytes = match env.convert_byte_array(&plaintext) {
        Ok(b) => b,
        Err(_) => {
            jni_error(&mut env, "Failed to read plaintext");
            return JByteArray::default();
        }
    };

    let ephemeral_secret = StaticSecret::random_from_rng(OsRng);
    let ephemeral_pubkey = X25519PublicKey::from(&ephemeral_secret);
    let shared_secret = ephemeral_secret.diffie_hellman(&recipient_pub_key);

    let mut hasher = Sha256::new();
    hasher.update(shared_secret.as_bytes());
    let aes_key = hasher.finalize();

    let cipher = match Aes256Gcm::new_from_slice(&aes_key) {
        Ok(c) => c,
        Err(_) => {
            jni_error(&mut env, "Failed to create AES cipher");
            return JByteArray::default();
        }
    };
    let nonce = Aes256Gcm::generate_nonce(&mut OsRng);

    let ciphertext = match cipher.encrypt(&nonce, plaintext_bytes.as_ref()) {
        Ok(c) => c,
        Err(_) => {
            jni_error(&mut env, "Encryption failed");
            return JByteArray::default();
        }
    };

    let mut result = Vec::with_capacity(32 + 12 + ciphertext.len());
    result.extend_from_slice(ephemeral_pubkey.as_bytes());
    result.extend_from_slice(nonce.as_slice());
    result.extend_from_slice(&ciphertext);

    match env.byte_array_from_slice(&result) {
        Ok(arr) => arr,
        Err(_) => {
            jni_error(&mut env, "Failed to create byte array");
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
    if my_secret_bytes.len() != 32 {
        jni_error(&mut env, "my_secret must be 32 bytes");
        return JByteArray::default();
    }
    
    let ciphertext_bytes = match env.convert_byte_array(&ciphertext_bundle) {
        Ok(b) => b,
        Err(_) => {
            jni_error(&mut env, "Failed to read ciphertext_bundle");
            return JByteArray::default();
        }
    };
    
    if ciphertext_bytes.len() < 32 + 12 + 16 {
        jni_error(&mut env, "Ciphertext too short (minimum 60 bytes: 32 pub + 12 nonce + 16 tag)");
        return JByteArray::default();
    }
    
    let (ephemeral_pub_bytes, rest) = ciphertext_bytes.split_at(32);
    let (nonce_bytes, ciphertext) = rest.split_at(12);

    let ephemeral_pub_key = {
        let mut arr = [0u8; 32];
        arr.copy_from_slice(ephemeral_pub_bytes);
        X25519PublicKey::from(arr)
    };
    
    let static_secret = {
        let mut arr = [0u8; 32];
        arr.copy_from_slice(&my_secret_bytes);
        StaticSecret::from(arr)
    };

    let shared_secret = static_secret.diffie_hellman(&ephemeral_pub_key);
    
    let mut hasher = Sha256::new();
    hasher.update(shared_secret.as_bytes());
    let aes_key = hasher.finalize();

    let cipher = match Aes256Gcm::new_from_slice(&aes_key) {
        Ok(c) => c,
        Err(_) => {
            jni_error(&mut env, "Failed to create AES cipher");
            return JByteArray::default();
        }
    };
    let nonce = Nonce::from_slice(nonce_bytes);
    
    let plaintext = match cipher.decrypt(nonce, ciphertext) {
        Ok(p) => p,
        Err(_) => {
            jni_error(&mut env, "Decryption failed");
            return JByteArray::default();
        }
    };

    match env.byte_array_from_slice(&plaintext) {
        Ok(arr) => arr,
        Err(_) => {
            jni_error(&mut env, "Failed to create byte array");
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
    if seed_bytes.len() != 32 {
        jni_error(&mut env, "seed must be 32 bytes");
        return JByteArray::default();
    }

    let message_bytes = match env.convert_byte_array(&message) {
        Ok(b) => b,
        Err(_) => {
            jni_error(&mut env, "Failed to read message");
            return JByteArray::default();
        }
    };

    let seed_arr: &[u8; 32] = match seed_bytes.as_slice().try_into() {
        Ok(a) => a,
        Err(_) => {
            jni_error(&mut env, "Invalid seed length");
            return JByteArray::default();
        }
    };
    let signing_key = SigningKey::from_bytes(seed_arr);
    let signature = signing_key.sign(&message_bytes);

    match env.byte_array_from_slice(&signature.to_bytes()) {
        Ok(arr) => arr,
        Err(_) => {
            jni_error(&mut env, "Failed to create byte array");
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
    if pubkey_bytes.len() != 32 {
        return jni::sys::JNI_FALSE;
    }

    let message_bytes = match env.convert_byte_array(&message) {
        Ok(b) => b,
        Err(_) => return jni::sys::JNI_FALSE,
    };

    let sig_bytes = match env.convert_byte_array(&signature) {
        Ok(b) => b,
        Err(_) => return jni::sys::JNI_FALSE,
    };
    if sig_bytes.len() != 64 {
        return jni::sys::JNI_FALSE;
    }

    let pubkey_arr: &[u8; 32] = match pubkey_bytes.as_slice().try_into() {
        Ok(a) => a,
        Err(_) => return jni::sys::JNI_FALSE,
    };
    let verifying_key = match VerifyingKey::from_bytes(pubkey_arr) {
        Ok(k) => k,
        Err(_) => return jni::sys::JNI_FALSE,
    };

    let sig_arr: &[u8; 64] = match sig_bytes.as_slice().try_into() {
        Ok(a) => a,
        Err(_) => return jni::sys::JNI_FALSE,
    };
    let sig = Signature::from_bytes(sig_arr);

    if verifying_key.verify(&message_bytes, &sig).is_ok() {
        jni::sys::JNI_TRUE
    } else {
        jni::sys::JNI_FALSE
    }
}
