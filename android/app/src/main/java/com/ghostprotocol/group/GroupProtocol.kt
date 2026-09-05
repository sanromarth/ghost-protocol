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
    const val OPCODE_GROUP_INVITE: Byte = 0x31.toByte()

    // Minimum envelope size: 1 (opcode) + 32 (groupId) + 16 (senderId) + 8 (ts) + 64 (sig) = 121 bytes
    const val MIN_ENVELOPE_SIZE = 121
    const val MIN_INVITE_SIZE = 121

    data class GroupInvite(
        val groupIdBytes: ByteArray,
        val groupIdHex: String,
        val creatorContactId: String,
        val timestamp: Long,
        val ciphertext: ByteArray,
        val signature: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GroupInvite) return false
            return groupIdHex == other.groupIdHex &&
                    creatorContactId == other.creatorContactId &&
                    timestamp == other.timestamp &&
                    ciphertext.contentEquals(other.ciphertext) &&
                    signature.contentEquals(other.signature)
        }

        override fun hashCode(): Int {
            var result = groupIdHex.hashCode()
            result = 31 * result + creatorContactId.hashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + ciphertext.contentHashCode()
            result = 31 * result + signature.contentHashCode()
            return result
        }
    }

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

    /**
     * Encodes an individual invite envelope for Opcode 0x31.
     * Signed with creator's Ed25519 seed over:
     * [0x31 || 32B groupId || 16B creatorContactId || 8B timestamp || ciphertext]
     */
    fun encodeInviteEnvelope(
        groupIdHex: String,
        creatorContactId: String,
        timestamp: Long,
        ciphertext: ByteArray,
        ed25519Seed: ByteArray
    ): ByteArray {
        val groupIdBytes = if (groupIdHex.length == 64) {
            hexToBytes(groupIdHex)
        } else {
            MessageDigest.getInstance("SHA-256").digest(groupIdHex.toByteArray(Charsets.UTF_8))
        }

        val creatorIdBytes = creatorContactId.padEnd(16, ' ').take(16).toByteArray(Charsets.UTF_8)
        val tsBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(timestamp).array()

        val unsignedLen = 1 + 32 + 16 + 8 + ciphertext.size
        val unsignedBuffer = ByteBuffer.allocate(unsignedLen)
        unsignedBuffer.put(OPCODE_GROUP_INVITE)
        unsignedBuffer.put(groupIdBytes)
        unsignedBuffer.put(creatorIdBytes)
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
     * Decodes and cryptographically verifies an incoming group invite envelope (Opcode 0x31).
     * Returns null if signature verification fails or envelope is malformed.
     */
    fun decodeInviteEnvelope(data: ByteArray, creatorEd25519Pub: ByteArray): GroupInvite? {
        if (data.size < MIN_INVITE_SIZE) return null
        if (data[0] != OPCODE_GROUP_INVITE) return null

        val unsignedLen = data.size - 64
        val unsignedBytes = data.copyOfRange(0, unsignedLen)
        val signature = data.copyOfRange(unsignedLen, data.size)

        if (!GhostCrypto.verify(creatorEd25519Pub, unsignedBytes, signature)) {
            return null
        }

        val buffer = ByteBuffer.wrap(unsignedBytes)
        buffer.get() // Skip opcode 0x31

        val groupIdBytes = ByteArray(32)
        buffer.get(groupIdBytes)
        val groupIdHex = bytesToHex(groupIdBytes)

        val creatorIdBytes = ByteArray(16)
        buffer.get(creatorIdBytes)
        val creatorContactId = String(creatorIdBytes, Charsets.UTF_8).trim()

        val timestamp = buffer.long

        val cipherLen = buffer.remaining()
        val ciphertext = ByteArray(cipherLen)
        buffer.get(ciphertext)

        return GroupInvite(
            groupIdBytes = groupIdBytes,
            groupIdHex = groupIdHex,
            creatorContactId = creatorContactId,
            timestamp = timestamp,
            ciphertext = ciphertext,
            signature = signature
        )
    }

    /**
     * Extracts creator contact ID from an invite envelope without verifying signature.
     */
    fun peekInviteCreatorContactId(data: ByteArray): String? {
        if (data.size < MIN_INVITE_SIZE || data[0] != OPCODE_GROUP_INVITE) return null
        return try {
            val creatorIdBytes = data.copyOfRange(33, 49)
            String(creatorIdBytes, Charsets.UTF_8).trim()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts group ID hex from an invite envelope without verifying signature.
     */
    fun peekInviteGroupIdHex(data: ByteArray): String? {
        if (data.size < MIN_INVITE_SIZE || data[0] != OPCODE_GROUP_INVITE) return null
        return try {
            val groupIdBytes = data.copyOfRange(1, 33)
            bytesToHex(groupIdBytes)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Wire payload representation for decrypted group message payloads.
     */
    data class ParsedWirePayload(
        val senderName: String?,
        val replySender: String?,
        val replyText: String?,
        val metaGroupName: String?,
        val metaCreatorId: String?,
        val metaMembersJson: String?,
        val text: String
    )

    /**
     * Formats wire plaintext payload embedding sender name, optional reply token,
     * and optional self-healing META group descriptor.
     */
    fun formatWirePayload(
        senderName: String,
        replyTo: Pair<String, String>? = null,
        meta: Triple<String, String, String>? = null, // (groupName, creatorId, membersJson)
        text: String
    ): String {
        val cleanSender = senderName.replace("\u0000", " ")
        val sb = StringBuilder(cleanSender)
        if (replyTo != null) {
            val qSender = replyTo.first.replace("\u0000", " ")
            val qText = replyTo.second.take(120).replace("\u0000", " ")
            sb.append("\u0000REPLY\u0000").append(qSender).append("\u0000").append(qText)
        }
        if (meta != null) {
            val gName = meta.first.replace("\u0000", " ")
            val cId = meta.second.replace("\u0000", " ")
            val mJson = meta.third.replace("\u0000", " ")
            sb.append("\u0000META\u0000").append(gName).append("\u0000").append(cId).append("\u0000").append(mJson)
        }
        sb.append("\u0000").append(text)
        return sb.toString()
    }

    /**
     * Parses wire plaintext payload extracting sender name, reply metadata,
     * self-healing META group descriptor, and actual message body.
     */
    fun parseWirePayload(rawText: String): ParsedWirePayload {
        val parts = rawText.split('\u0000')
        if (parts.size <= 1) {
            return ParsedWirePayload(
                senderName = null,
                replySender = null,
                replyText = null,
                metaGroupName = null,
                metaCreatorId = null,
                metaMembersJson = null,
                text = rawText
            )
        }

        var idx = 0
        val senderName = if (parts[idx].isNotEmpty()) parts[idx] else null
        idx++

        var replySender: String? = null
        var replyText: String? = null
        if (idx < parts.size && parts[idx] == "REPLY") {
            idx++
            if (idx < parts.size) {
                replySender = parts[idx].ifEmpty { null }
                idx++
            }
            if (idx < parts.size) {
                replyText = parts[idx].ifEmpty { null }
                idx++
            }
        }

        var metaGroupName: String? = null
        var metaCreatorId: String? = null
        var metaMembersJson: String? = null
        if (idx < parts.size && parts[idx] == "META") {
            idx++
            if (idx < parts.size) {
                metaGroupName = parts[idx].ifEmpty { null }
                idx++
            }
            if (idx < parts.size) {
                metaCreatorId = parts[idx].ifEmpty { null }
                idx++
            }
            if (idx < parts.size) {
                metaMembersJson = parts[idx].ifEmpty { null }
                idx++
            }
        }

        val text = if (idx < parts.size) {
            parts.drop(idx).joinToString("\u0000")
        } else {
            ""
        }

        return ParsedWirePayload(
            senderName = senderName,
            replySender = replySender,
            replyText = replyText,
            metaGroupName = metaGroupName,
            metaCreatorId = metaCreatorId,
            metaMembersJson = metaMembersJson,
            text = text
        )
    }
}
