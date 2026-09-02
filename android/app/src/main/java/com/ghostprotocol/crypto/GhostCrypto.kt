package com.ghostprotocol.crypto

object GhostCrypto {
    init {
        System.loadLibrary("ghost_crypto")
    }

    /**
     * Generate a new identity keypair.
     * Returns 128 bytes: ed25519_seed(32) + ed25519_pub(32) + x25519_secret(32) + x25519_pub(32)
     */
    external fun generateIdentity(): ByteArray

    /**
     * Encrypt plaintext for a recipient.
     * @param recipientX25519Pub 32-byte X25519 public key of recipient
     * @param plaintext message bytes to encrypt
     * @return ephemeral_pubkey(32) + nonce(12) + ciphertext_with_tag
     */
    external fun encrypt(recipientX25519Pub: ByteArray, plaintext: ByteArray): ByteArray

    /**
     * Decrypt a ciphertext blob.
     * @param myX25519Secret 32-byte X25519 secret key
     * @param ciphertext output from encrypt()
     * @return decrypted plaintext bytes
     */
    external fun decrypt(myX25519Secret: ByteArray, ciphertext: ByteArray): ByteArray

    /**
     * Sign a message with Ed25519.
     * @param ed25519Seed 32-byte Ed25519 seed (private key)
     * @param message bytes to sign
     * @return 64-byte Ed25519 signature
     */
    external fun sign(ed25519Seed: ByteArray, message: ByteArray): ByteArray

    /**
     * Verify an Ed25519 signature.
     * @param ed25519Pub 32-byte Ed25519 public key
     * @param message original message bytes
     * @param signature 64-byte signature to verify
     * @return true if signature is valid
     */
    external fun verify(ed25519Pub: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}
