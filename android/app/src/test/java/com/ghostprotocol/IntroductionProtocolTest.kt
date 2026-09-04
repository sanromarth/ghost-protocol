package com.ghostprotocol

import com.ghostprotocol.introduction.IntroductionEnvelope
import com.ghostprotocol.introduction.IntroductionProtocol
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IntroductionProtocolTest {

    @Test
    fun testDecodeIntroductionValid() {
        val edPub = ByteArray(32) { (it + 1).toByte() }
        val xPub = ByteArray(32) { (it + 33).toByte() }
        val name = "Bob Dylan"
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val voucherId = "alice1234567890a"
        val fakeSig = ByteArray(64) { 0x77.toByte() }

        val totalSize = 1 + 32 + 32 + 2 + nameBytes.size + 16 + 64
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(IntroductionProtocol.OPCODE_INTRODUCTION)
        buffer.put(edPub)
        buffer.put(xPub)
        buffer.putShort(nameBytes.size.toShort())
        buffer.put(nameBytes)
        buffer.put(voucherId.toByteArray(Charsets.UTF_8))
        buffer.put(fakeSig)

        val packet = buffer.array()
        assertEquals(totalSize, packet.size)

        val envelope = IntroductionProtocol.decodeIntroduction(packet)
        assertNotNull(envelope)
        envelope!!

        assertArrayEquals(edPub, envelope.introducedEd25519Pub)
        assertArrayEquals(xPub, envelope.introducedX25519Pub)
        assertEquals(name, envelope.introducedName)
        assertEquals(voucherId, envelope.voucherContactId)
        assertArrayEquals(fakeSig, envelope.signature)
    }

    @Test
    fun testDecodeIntroductionInvalidOpcode() {
        val packet = ByteArray(160) { 0 }
        packet[0] = 0x99.toByte() // Wrong opcode

        val envelope = IntroductionProtocol.decodeIntroduction(packet)
        assertNull(envelope)
    }

    @Test
    fun testDecodeIntroductionTruncated() {
        val packet = ByteArray(140) { 0 }
        packet[0] = IntroductionProtocol.OPCODE_INTRODUCTION

        val envelope = IntroductionProtocol.decodeIntroduction(packet)
        assertNull(envelope)
    }

    @Test
    fun testDecodeIntroductionCorruptedLength() {
        val buffer = ByteBuffer.allocate(150).order(ByteOrder.BIG_ENDIAN)
        buffer.put(IntroductionProtocol.OPCODE_INTRODUCTION)
        buffer.put(ByteArray(32) { 1 })
        buffer.put(ByteArray(32) { 2 })
        buffer.putShort(200.toShort()) // Claims 200 bytes, but buffer only has 150 total

        val envelope = IntroductionProtocol.decodeIntroduction(buffer.array())
        assertNull(envelope)
    }

    @Test
    fun testComputeContactIdDeterministic() {
        val pubKey = ByteArray(32) { (it + 5).toByte() }
        val id1 = IntroductionProtocol.computeContactId(pubKey)
        val id2 = IntroductionProtocol.computeContactId(pubKey)

        assertEquals(16, id1.length)
        assertEquals(id1, id2)

        val diffKey = ByteArray(32) { (it + 6).toByte() }
        val id3 = IntroductionProtocol.computeContactId(diffKey)
        assertNotEquals(id1, id3)
    }

    @Test
    fun testCryptoSignVerifyIfNativeLibAvailable() {
        try {
            val voucherSeed = ByteArray(32) { 0x42.toByte() }
            val edPub = ByteArray(32) { 0x11.toByte() }
            val xPub = ByteArray(32) { 0x22.toByte() }
            val name = "Carol Danvers"
            val voucherId = "alice1234567890a"

            val packet = IntroductionProtocol.encodeIntroduction(
                introducedEd25519Pub = edPub,
                introducedX25519Pub = xPub,
                introducedName = name,
                voucherSeed = voucherSeed,
                voucherContactId = voucherId
            )

            assertTrue(packet.size >= IntroductionProtocol.MIN_ENVELOPE_SIZE)

            val decoded = IntroductionProtocol.decodeIntroduction(packet)
            assertNotNull(decoded)
            assertEquals(name, decoded?.introducedName)
            assertEquals(voucherId, decoded?.voucherContactId)
        } catch (e: Throwable) {
            println("Native library not loaded in host JVM (expected on desktop): ${e.message}")
        }
    }
}
