package com.ghostprotocol

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.ghostprotocol.crypto.GhostCrypto
import java.security.MessageDigest

object IdentityManager {
    private const val PREFS_NAME = "ghost_identity"
    private const val KEY_IDENTITY = "identity_blob"   // 128 bytes Base64
    private const val KEY_DISPLAY_NAME = "GHOST_USERNAME"

    private var prefs: SharedPreferences? = null
    private var identityBlob: ByteArray? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val stored = prefs?.getString(KEY_IDENTITY, null)
            if (stored != null) {
                identityBlob = Base64.decode(stored, Base64.NO_WRAP)
            } else {
                // First launch: generate new identity
                val blob = GhostCrypto.generateIdentity()
                identityBlob = blob
                prefs?.edit()
                    ?.putString(KEY_IDENTITY, Base64.encodeToString(blob, Base64.NO_WRAP))
                    ?.putString(KEY_DISPLAY_NAME, "Ghost User")
                    ?.apply()
            }
        } catch (e: Exception) {
            android.util.Log.e("IdentityManager", "FATAL: Identity init failed: ${e.message}")
            // Generate fresh identity as fallback
            val blob = GhostCrypto.generateIdentity()
            identityBlob = blob
            prefs?.edit()
                ?.putString(KEY_IDENTITY, Base64.encodeToString(blob, Base64.NO_WRAP))
                ?.apply()
        }
    }

    private fun requireBlob(): ByteArray =
        identityBlob ?: throw IllegalStateException("IdentityManager.init() must be called before accessing keys")

    // Returns 32-byte Ed25519 seed (private signing key)
    fun getEd25519Seed(): ByteArray = requireBlob().copyOfRange(0, 32)

    // Returns 32-byte Ed25519 public key
    fun getEd25519PubKey(): ByteArray = requireBlob().copyOfRange(32, 64)

    // Returns 32-byte X25519 secret key
    fun getX25519Secret(): ByteArray = requireBlob().copyOfRange(64, 96)

    // Returns 32-byte X25519 public key
    fun getX25519PubKey(): ByteArray = requireBlob().copyOfRange(96, 128)

    // Returns Base64-encoded Ed25519 public key
    fun getEd25519PubKeyBase64(): String =
        Base64.encodeToString(getEd25519PubKey(), Base64.NO_WRAP)

    // Returns Base64-encoded X25519 public key
    fun getX25519PubKeyBase64(): String =
        Base64.encodeToString(getX25519PubKey(), Base64.NO_WRAP)

    // Returns first 4 bytes of SHA-256(ed25519_pubkey) — used as BLE fingerprint
    fun getFingerprint(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(getEd25519PubKey()).copyOfRange(0, 4)
    }

    // Returns hex string of first 8 chars of SHA-256(ed25519_pubkey) — used as contact ID
    fun getContactId(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(getEd25519PubKey()).take(8).joinToString("") { "%02x".format(it) }
    }

    fun getDisplayName(): String {
        return prefs?.getString(KEY_DISPLAY_NAME, "Ghost User") ?: "Ghost User"
    }

    fun setDisplayName(name: String) {
        prefs?.edit()?.putString(KEY_DISPLAY_NAME, name)?.apply()
    }

    // Wipe all identity data (panic button)
    fun wipeAll() {
        prefs?.edit()?.clear()?.apply()
        identityBlob = null
    }
}
