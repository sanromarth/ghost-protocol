package com.ghostprotocol.group

import com.ghostprotocol.crypto.GhostCrypto
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pure Kotlin wire protocol codec for Cell Group messaging.
 * Zero Android dependencies.
 *
 * Wire format for Opcode 0x30 (Individual Unicast Envelope):
 * [1B: 0x30]
 * [32B: Group ID (raw SHA-256 bytes)]
 * [16B: Sender Contact ID (UTF-8 bytes)]
 * [8B: Timestamp (millis, big-endian uint64)]
 * [variable: AES-256-GCM ciphertext]
 * [64B: Ed25519 signature over [0x30 || groupId || senderId || timestamp || ciphertext]]
 */
object GroupProtocol {

    const val OPCODE_GROUP_ENVELOPE: Byte = 0x30.toByte()

    // Minimum envelope size: 1 (opcode) + 32 (groupId) + 16 (senderId) + 8 (ts) + 64 (sig) = 121 bytes
    const val MIN_ENVELOPE_SIZE = 121

    data class GroupEnvelope(
        val groupIdBytes: ByteArray,
        val groupIdHex: String,
        val senderContactId: String,
        val timestamp: Long,
        val ciphertext: ByteArray,
        val signature: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GroupEnvelope) return false
            return groupIdHex == other.groupIdHex &&
                    senderContactId == other.senderContactId &&
                    timestamp == other.timestamp &&
                    ciphertext.contentEquals(other.ciphertext) &&
                    signature.contentEquals(other.signature)
        }

        override fun hashCode(): Int {
            var result = groupIdHex.hashCode()
            result = 31 * result + senderContactId.hashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + ciphertext.contentHashCode()
            result = 31 * result + signature.contentHashCode()
            return result
        }
    }

    /**
     * Generates a 64-char hex Group ID with high entropy:
     * SHA-256(creatorEd25519Pub || timestampBE || 16-byte random nonce)
     */
    fun generateGroupId(creatorEd25519Pub: ByteArray, timestamp: Long = System.currentTimeMillis()): String {
        val nonce = ByteArray(16)
        SecureRandom().nextBytes(nonce)

        val tsBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(timestamp).array()
        val md = MessageDigest.getInstance("SHA-256")
        md.update(creatorEd25519Pub)
        md.update(tsBytes)
        md.update(nonce)
        val hash = md.digest()
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Converts a 64-character hex string into a 32-byte raw array.
     */
    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val result = ByteArray(clean.length / 2)
        for (i in result.indices) {
            val idx = i * 2
            result[i] = clean.substring(idx, idx + 2).toInt(16).toByte()
        }
        return result
    }

    /**
     * Converts a byte array to lowercase hex string.
     */
    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Encodes a single member envelope with Ed25519 signature.
     */
    fun encodeEnvelope(
        groupIdHex: String,
        senderContactId: String,
        timestamp: Long,
        ciphertext: ByteArray,
        ed25519Seed: ByteArray
    ): ByteArray {
        val groupIdBytes = if (groupIdHex.length == 64) {
            hexToBytes(groupIdHex)
        } else {
            MessageDigest.getInstance("SHA-256").digest(groupIdHex.toByteArray(Charsets.UTF_8))
        }

        // Sender contact ID: exactly 16 bytes UTF-8
        val senderIdBytes = senderContactId.padEnd(16, ' ').take(16).toByteArray(Charsets.UTF_8)
        val tsBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(timestamp).array()

        val unsignedLen = 1 + 32 + 16 + 8 + ciphertext.size
        val unsignedBuffer = ByteBuffer.allocate(unsignedLen)
        unsignedBuffer.put(OPCODE_GROUP_ENVELOPE)
        unsignedBuffer.put(groupIdBytes)
        unsignedBuffer.put(senderIdBytes)
        unsignedBuffer.put(tsBytes)
        unsignedBuffer.put(ciphertext)

        val unsignedBytes = unsignedBuffer.array()
        val signature = GhostCrypto.sign(ed25519Seed, unsignedBytes)

        val finalBuffer = ByteBuffer.allocate(unsignedLen + 64)
        finalBuffer.put(unsignedBytes)
        finalBuffer.put(signature)

        return finalBuffer.array()
    }

    /**
     * Decodes and cryptographically verifies an incoming group envelope.
     * Returns null if signature verification fails or envelope is malformed.
     */
    fun decodeEnvelope(data: ByteArray, senderEd25519Pub: ByteArray): GroupEnvelope? {
        if (data.size < MIN_ENVELOPE_SIZE) return null
        if (data[0] != OPCODE_GROUP_ENVELOPE) return null

        val unsignedLen = data.size - 64
        val unsignedBytes = data.copyOfRange(0, unsignedLen)
        val signature = data.copyOfRange(unsignedLen, data.size)

        // Mandatory cryptographic signature verification
        if (!GhostCrypto.verify(senderEd25519Pub, unsignedBytes, signature)) {
            return null
        }

        val buffer = ByteBuffer.wrap(unsignedBytes)
        buffer.get() // Skip opcode 0x30

        val groupIdBytes = ByteArray(32)
        buffer.get(groupIdBytes)
        val groupIdHex = bytesToHex(groupIdBytes)

        val senderIdBytes = ByteArray(16)
        buffer.get(senderIdBytes)
        val senderContactId = String(senderIdBytes, Charsets.UTF_8).trim()

        val timestamp = buffer.long

        val cipherLen = buffer.remaining()
        val ciphertext = ByteArray(cipherLen)
        buffer.get(ciphertext)

        return GroupEnvelope(
            groupIdBytes = groupIdBytes,
            groupIdHex = groupIdHex,
            senderContactId = senderContactId,
            timestamp = timestamp,
            ciphertext = ciphertext,
            signature = signature
        )
    }

    /**
     * Extracts the sender contact ID from an envelope without verifying signature.
     * Useful for looking up the sender's public key before calling decodeEnvelope().
     */
    fun peekSenderContactId(data: ByteArray): String? {
        if (data.size < MIN_ENVELOPE_SIZE || data[0] != OPCODE_GROUP_ENVELOPE) return null
        return try {
            val senderIdBytes = data.copyOfRange(33, 49)
            String(senderIdBytes, Charsets.UTF_8).trim()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts the group ID hex from an envelope without verifying signature.
     */
    fun peekGroupIdHex(data: ByteArray): String? {
        if (data.size < MIN_ENVELOPE_SIZE || data[0] != OPCODE_GROUP_ENVELOPE) return null
        return try {
            val groupIdBytes = data.copyOfRange(1, 33)
            bytesToHex(groupIdBytes)
        } catch (_: Exception) {
            null
        }
    }
}
