package com.ghostprotocol.receipt

import com.ghostprotocol.crypto.GhostCrypto
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Parsed Delivery Receipt payload (Opcode 0x40).
 */
data class DeliveryReceipt(
    val messageHash: String,
    val recipientContactId: String,
    val receiptTimestamp: Long,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DeliveryReceipt
        return messageHash == other.messageHash &&
                recipientContactId == other.recipientContactId &&
                receiptTimestamp == other.receiptTimestamp &&
                signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = messageHash.hashCode()
        result = 31 * result + recipientContactId.hashCode()
        result = 31 * result + receiptTimestamp.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

/**
 * Pure Kotlin protocol codec for GHOST v0.3.7 Delivery Receipts.
 *
 * Wire Format (153 bytes):
 * [1B: 0x40]                              // Delivery receipt opcode
 * [64B: Message Hash (UTF-8 hex string)]  // SHA-256(senderId || timestamp || plaintext)
 * [16B: Recipient Contact ID (UTF-8 hex)] // The person sending the receipt ("I got it")
 * [8B: Receipt Timestamp (millis, BE)]    // When the receipt was generated
 * [64B: Ed25519 signature over [0x40 || messageHash || recipientId || receiptTimestamp]]
 */
object DeliveryReceiptProtocol {

    const val OPCODE_DELIVERY_RECEIPT: Byte = 0x40
    const val RECEIPT_PAYLOAD_SIZE = 89
    const val RECEIPT_TOTAL_SIZE = 153

    /**
     * Computes deterministic SHA-256 hash across sender ID, original send timestamp, and plaintext.
     * Both sender and receiver compute this independently to achieve identical 64-char hex keys.
     */
    fun computeMessageHash(senderContactId: String, timestamp: Long, plaintext: String): String {
        val senderBytes = senderContactId.toByteArray(Charsets.UTF_8)
        val tsBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(timestamp).array()
        val textBytes = plaintext.toByteArray(Charsets.UTF_8)

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(senderBytes)
        digest.update(tsBytes)
        digest.update(textBytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Encodes and signs a 153-byte delivery receipt packet.
     */
    fun encodeReceipt(
        messageHash: String,
        recipientContactId: String,
        ed25519Seed: ByteArray,
        timestamp: Long = System.currentTimeMillis()
    ): ByteArray {
        val hashBytes = messageHash.toByteArray(Charsets.UTF_8).copyOf(64)
        val recipientBytes = recipientContactId.toByteArray(Charsets.UTF_8).copyOf(16)
        val tsBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(timestamp).array()

        val payload = ByteArray(RECEIPT_PAYLOAD_SIZE)
        payload[0] = OPCODE_DELIVERY_RECEIPT
        System.arraycopy(hashBytes, 0, payload, 1, 64)
        System.arraycopy(recipientBytes, 0, payload, 65, 16)
        System.arraycopy(tsBytes, 0, payload, 81, 8)

        val signature = GhostCrypto.sign(ed25519Seed, payload)
        val packet = ByteArray(RECEIPT_TOTAL_SIZE)
        System.arraycopy(payload, 0, packet, 0, RECEIPT_PAYLOAD_SIZE)
        System.arraycopy(signature, 0, packet, RECEIPT_PAYLOAD_SIZE, 64)
        return packet
    }

    /**
     * Decodes a delivery receipt packet without verifying signature.
     */
    fun decodeReceipt(data: ByteArray): DeliveryReceipt? {
        if (data.size < RECEIPT_TOTAL_SIZE) return null
        if (data[0] != OPCODE_DELIVERY_RECEIPT) return null

        val messageHash = String(data, 1, 64, Charsets.UTF_8).trim('\u0000')
        val recipientContactId = String(data, 65, 16, Charsets.UTF_8).trim('\u0000')
        val tsBuffer = ByteBuffer.wrap(data, 81, 8).order(ByteOrder.BIG_ENDIAN)
        val receiptTimestamp = tsBuffer.long
        val signature = data.copyOfRange(89, 153)

        return DeliveryReceipt(
            messageHash = messageHash,
            recipientContactId = recipientContactId,
            receiptTimestamp = receiptTimestamp,
            signature = signature
        )
    }

    /**
     * Verifies the Ed25519 signature of a delivery receipt using recipient's known public key.
     */
    fun verifyReceipt(receipt: DeliveryReceipt, recipientEd25519Pub: ByteArray): Boolean {
        if (receipt.signature.size != 64) return false

        val hashBytes = receipt.messageHash.toByteArray(Charsets.UTF_8).copyOf(64)
        val recipientBytes = receipt.recipientContactId.toByteArray(Charsets.UTF_8).copyOf(16)
        val tsBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(receipt.receiptTimestamp).array()

        val payload = ByteArray(RECEIPT_PAYLOAD_SIZE)
        payload[0] = OPCODE_DELIVERY_RECEIPT
        System.arraycopy(hashBytes, 0, payload, 1, 64)
        System.arraycopy(recipientBytes, 0, payload, 65, 16)
        System.arraycopy(tsBytes, 0, payload, 81, 8)

        return GhostCrypto.verify(recipientEd25519Pub, payload, receipt.signature)
    }
}
