package com.ghostprotocol

import com.ghostprotocol.group.GroupProtocol
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GroupProtocolTest {

    @Test
    fun testGenerateGroupIdUniqueness() {
        val pubKey = ByteArray(32) { (it + 1).toByte() }
        val id1 = GroupProtocol.generateGroupId(pubKey, 1000L)
        val id2 = GroupProtocol.generateGroupId(pubKey, 1000L)

        assertEquals(64, id1.length)
        assertEquals(64, id2.length)
        // With random nonce, two IDs generated even at the same millisecond should be distinct
        assertNotEquals(id1, id2)
    }

    @Test
    fun testHexConversionRoundtrip() {
        val originalHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val bytes = GroupProtocol.hexToBytes(originalHex)
        assertEquals(32, bytes.size)
        val roundtripHex = GroupProtocol.bytesToHex(bytes)
        assertEquals(originalHex, roundtripHex)
    }

    @Test
    fun testPeekMethodsOnEnvelope() {
        val groupIdHex = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val groupIdBytes = GroupProtocol.hexToBytes(groupIdHex)
        val senderId = "0123456789abcdef"
        val timestamp = 1700000000000L
        val fakeCiphertext = "encrypted_payload".toByteArray(Charsets.UTF_8)
        val fakeSignature = ByteArray(64) { 0x55.toByte() }

        val unsignedLen = 1 + 32 + 16 + 8 + fakeCiphertext.size
        val buffer = ByteBuffer.allocate(unsignedLen + 64)
        buffer.put(GroupProtocol.OPCODE_GROUP_ENVELOPE)
        buffer.put(groupIdBytes)
        buffer.put(senderId.toByteArray(Charsets.UTF_8))
        buffer.order(ByteOrder.BIG_ENDIAN).putLong(timestamp)
        buffer.put(fakeCiphertext)
        buffer.put(fakeSignature)

        val packet = buffer.array()

        assertEquals(senderId, GroupProtocol.peekSenderContactId(packet))
        assertEquals(groupIdHex, GroupProtocol.peekGroupIdHex(packet))
    }

    @Test
    fun testInvalidOpcodeReturnsNull() {
        val packet = ByteArray(150) { 0 }
        packet[0] = 0x99.toByte() // Wrong opcode
        assertNull(GroupProtocol.peekSenderContactId(packet))
        assertNull(GroupProtocol.peekGroupIdHex(packet))
    }
}
