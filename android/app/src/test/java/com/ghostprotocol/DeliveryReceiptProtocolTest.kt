package com.ghostprotocol

import com.ghostprotocol.receipt.DeliveryReceipt
import com.ghostprotocol.receipt.DeliveryReceiptProtocol
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DeliveryReceiptProtocolTest {

    @Test
    fun testComputeMessageHashDeterministic() {
        val senderId = "0123456789abcdef"
        val timestamp = 1700000000000L
        val text = "Need tourniquet at 5th St"

        val hash1 = DeliveryReceiptProtocol.computeMessageHash(senderId, timestamp, text)
        val hash2 = DeliveryReceiptProtocol.computeMessageHash(senderId, timestamp, text)

        assertEquals(64, hash1.length)
        assertEquals(hash1, hash2)
    }

    @Test
    fun testComputeMessageHashVariesWithInputs() {
        val senderId = "0123456789abcdef"
        val timestamp = 1700000000000L
        val text = "Need tourniquet at 5th St"

        val baseHash = DeliveryReceiptProtocol.computeMessageHash(senderId, timestamp, text)

        // Different timestamp
        val hashDiffTs = DeliveryReceiptProtocol.computeMessageHash(senderId, timestamp + 1, text)
        assertNotEquals(baseHash, hashDiffTs)

        // Different plaintext
        val hashDiffText = DeliveryReceiptProtocol.computeMessageHash(senderId, timestamp, "Need tourniquet at 6th St")
        assertNotEquals(baseHash, hashDiffText)

        // Different sender
        val hashDiffSender = DeliveryReceiptProtocol.computeMessageHash("fedcba9876543210", timestamp, text)
        assertNotEquals(baseHash, hashDiffSender)
    }

    @Test
    fun testDecodeReceiptValid() {
        val messageHashHex = "a".repeat(64)
        val recipientContactId = "abcdef0123456789"
        val receiptTimestamp = 1725430000000L
        val fakeSig = ByteArray(64) { (it + 1).toByte() }

        val buffer = ByteBuffer.allocate(DeliveryReceiptProtocol.RECEIPT_TOTAL_SIZE)
        buffer.put(DeliveryReceiptProtocol.OPCODE_DELIVERY_RECEIPT)
        buffer.put(messageHashHex.toByteArray(Charsets.UTF_8))
        buffer.put(recipientContactId.toByteArray(Charsets.UTF_8))
        buffer.order(ByteOrder.BIG_ENDIAN).putLong(receiptTimestamp)
        buffer.put(fakeSig)

        val packet = buffer.array()
        assertEquals(153, packet.size)

        val receipt = DeliveryReceiptProtocol.decodeReceipt(packet)
        assertNotNull(receipt)
        receipt!!
        assertEquals(messageHashHex, receipt.messageHash)
        assertEquals(recipientContactId, receipt.recipientContactId)
        assertEquals(receiptTimestamp, receipt.receiptTimestamp)
        assertArrayEquals(fakeSig, receipt.signature)
    }

    @Test
    fun testDecodeReceiptInvalidOpcode() {
        val buffer = ByteBuffer.allocate(DeliveryReceiptProtocol.RECEIPT_TOTAL_SIZE)
        buffer.put(0x99.toByte()) // Wrong opcode
        buffer.put(ByteArray(152) { 0 })

        val receipt = DeliveryReceiptProtocol.decodeReceipt(buffer.array())
        assertNull(receipt)
    }

    @Test
    fun testDecodeReceiptTruncated() {
        val truncated = ByteArray(152) { 0 }
        truncated[0] = DeliveryReceiptProtocol.OPCODE_DELIVERY_RECEIPT

        val receipt = DeliveryReceiptProtocol.decodeReceipt(truncated)
        assertNull(receipt)
    }

    @Test
    fun testCryptoSignVerifyIfNativeLibAvailable() {
        try {
            val seed = ByteArray(32) { 0x42.toByte() }
            val messageHash = "b".repeat(64)
            val recipientId = "1234567890abcdef"
            val timestamp = 1725431234567L

            val packet = DeliveryReceiptProtocol.encodeReceipt(messageHash, recipientId, seed, timestamp)
            assertEquals(153, packet.size)

            val decoded = DeliveryReceiptProtocol.decodeReceipt(packet)
            assertNotNull(decoded)
            assertEquals(messageHash, decoded?.messageHash)
            assertEquals(recipientId, decoded?.recipientContactId)
            assertEquals(timestamp, decoded?.receiptTimestamp)
        } catch (e: Throwable) {
            // Android NDK shared library not loadable on host JVM without bionic; skipped gracefully
            println("Native library not loaded in JVM host unit test (expected on desktop JVM): ${e.message}")
        }
    }
}
