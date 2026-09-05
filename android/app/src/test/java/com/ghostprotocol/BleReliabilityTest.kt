package com.ghostprotocol

import com.ghostprotocol.ble.GattOperationQueue
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit test suite verifying BLE client queue serialization logic,
 * batch chunk framing, boundary guards, and cool-off calculations.
 */
class BleReliabilityTest {

    @Test
    fun testParseBatchChunksValidMultiMessage() {
        val msg1 = "Hello mesh".toByteArray(Charsets.UTF_8)
        val msg2 = "Second packet payload".toByteArray(Charsets.UTF_8)
        val msg3 = ByteArray(100) { (it and 0xFF).toByte() }

        val totalLen = 1 + (4 + msg1.size) + (4 + msg2.size) + (4 + msg3.size)
        val buf = ByteBuffer.allocate(totalLen)
        buf.put(3.toByte()) // 3 messages
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putInt(msg1.size)
        buf.put(msg1)
        buf.putInt(msg2.size)
        buf.put(msg2)
        buf.putInt(msg3.size)
        buf.put(msg3)

        val chunks = GattOperationQueue.parseBatchChunks(buf.array())
        assertNotNull("Valid batch must parse successfully", chunks)
        assertEquals(3, chunks!!.size)
        assertArrayEquals(msg1, chunks[0])
        assertArrayEquals(msg2, chunks[1])
        assertArrayEquals(msg3, chunks[2])
    }

    @Test
    fun testParseBatchChunksEmptyAndZeroCount() {
        assertNull("Empty array must return null", GattOperationQueue.parseBatchChunks(ByteArray(0)))

        val zeroCount = byteArrayOf(0)
        assertNull("Zero count must return null", GattOperationQueue.parseBatchChunks(zeroCount))
    }

    @Test
    fun testParseBatchChunksTruncatedLength() {
        // Declares 2 messages but only provides length for 1
        val buf = ByteBuffer.allocate(10)
        buf.put(2.toByte())
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putInt(4)
        buf.put("test".toByteArray(Charsets.UTF_8))
        // truncated: missing 2nd message length header
        buf.put(1.toByte())

        assertNull("Truncated batch must return null", GattOperationQueue.parseBatchChunks(buf.array()))
    }

    @Test
    fun testParseBatchChunksOversizedLength() {
        val buf = ByteBuffer.allocate(9)
        buf.put(1.toByte())
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putInt(1000) // Claims 1000 bytes, but buffer ends
        buf.put("ABCD".toByteArray(Charsets.UTF_8))

        assertNull("Batch with invalid claimed length must return null", GattOperationQueue.parseBatchChunks(buf.array()))
    }

    @Test
    fun testCoolOffDefaultWindow() {
        assertEquals(150L, GattOperationQueue.DEFAULT_COOL_OFF_MS)
    }

    @Test
    fun testGattStateEnumCompleteness() {
        val states = GattOperationQueue.GattState.values().map { it.name }
        assertTrue(states.contains("IDLE"))
        assertTrue(states.contains("CONNECTING"))
        assertTrue(states.contains("CONNECTED"))
        assertTrue(states.contains("NEGOTIATING_MTU"))
        assertTrue(states.contains("DISCOVERING_SERVICES"))
        assertTrue(states.contains("WRITING"))
        assertTrue(states.contains("DISCONNECTING"))
        assertTrue(states.contains("CLOSED"))
    }

    // ===== v0.3.8: MTU Transport Fragmentation (0xFB) Tests =====

    @Test
    fun testSlicePayloadUnfragmentedUnderMtu() {
        // At MTU 23, max payload capacity is 20 bytes
        val smallData = "12345678901234567890".toByteArray(Charsets.UTF_8) // 20 bytes
        val frames23 = GattOperationQueue.slicePayload(smallData, negotiatedMtu = 23)
        assertEquals("Payload <= MTU - 3 must remain unfragmented", 1, frames23.size)
        assertArrayEquals("Payload must be byte-for-byte identical", smallData, frames23[0])

        // At MTU 512, max payload capacity is 509 bytes
        val handshake196 = ByteArray(196) { it.toByte() }
        val frames512 = GattOperationQueue.slicePayload(handshake196, negotiatedMtu = 512)
        assertEquals("196B payload under MTU 512 must remain unfragmented", 1, frames512.size)
        assertArrayEquals("Unfragmented payload must have zero overhead", handshake196, frames512[0])
    }

    @Test
    fun testSlicePayloadFragmentedAtMinimumMtu23() {
        // 196-byte verification handshake at MTU 23:
        // maxWrite = 20 bytes, maxSlice = 13 bytes
        // 196 / 13 = 15.07 -> 16 fragments
        val data = ByteArray(196) { (it * 7).toByte() }
        val transferId = 0x1234
        val frames = GattOperationQueue.slicePayload(data, negotiatedMtu = 23, transferId = transferId)

        assertEquals("196 bytes at MTU 23 must yield 16 fragments", 16, frames.size)

        // Verify each frame framing
        val reconstructed = ByteArray(196)
        var offset = 0
        for (i in 0 until 16) {
            val frame = frames[i]
            assertTrue("Each frame must not exceed MTU - 3 (20 bytes)", frame.size <= 20)
            assertEquals("Opcode must be 0xFB", GattOperationQueue.OPCODE_BLE_FRAGMENT, frame[0])

            val tid = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
            val idx = ((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF)
            val tot = ((frame[5].toInt() and 0xFF) shl 8) or (frame[6].toInt() and 0xFF)

            assertEquals("Transfer ID must match", transferId, tid)
            assertEquals("Fragment index must match", i, idx)
            assertEquals("Total fragments must be 16", 16, tot)

            val sliceLen = frame.size - GattOperationQueue.FRAGMENT_HEADER_SIZE
            System.arraycopy(frame, GattOperationQueue.FRAGMENT_HEADER_SIZE, reconstructed, offset, sliceLen)
            offset += sliceLen
        }

        assertEquals("Reconstructed length must match original", 196, offset)
        assertArrayEquals("Reconstructed bytes must match original exactly", data, reconstructed)
    }

    @Test
    fun testSlicePayloadAcrossVariousMtus() {
        val payload = ByteArray(506) { (it and 0xFF).toByte() } // Large routed message
        val mtus = listOf(23, 185, 247, 512)

        for (mtu in mtus) {
            val frames = GattOperationQueue.slicePayload(payload, negotiatedMtu = mtu, transferId = 42)
            val maxPayload = mtu - 3

            if (payload.size <= maxPayload) {
                assertEquals("Should not fragment when fitting within MTU $mtu", 1, frames.size)
                assertArrayEquals(payload, frames[0])
            } else {
                assertTrue("Must produce multiple fragments for MTU $mtu", frames.size > 1)
                // Reconstruct and verify
                val reassembled = ByteArray(payload.size)
                var pos = 0
                for (frame in frames) {
                    assertTrue("Frame size ${frame.size} must be <= $maxPayload", frame.size <= maxPayload)
                    assertEquals(GattOperationQueue.OPCODE_BLE_FRAGMENT, frame[0])
                    val sliceLen = frame.size - GattOperationQueue.FRAGMENT_HEADER_SIZE
                    System.arraycopy(frame, GattOperationQueue.FRAGMENT_HEADER_SIZE, reassembled, pos, sliceLen)
                    pos += sliceLen
                }
                assertEquals(payload.size, pos)
                assertArrayEquals("Reassembly failed at MTU $mtu", payload, reassembled)
            }
        }
    }

    @Test
    fun testReassemblyOutOfOrderAndDuplicates() {
        val original = "The quick brown fox jumps over the lazy dog and traverses the GHOST BLE mesh!".toByteArray(Charsets.UTF_8)
        val frames = GattOperationQueue.slicePayload(original, negotiatedMtu = 23, transferId = 999)
        assertTrue(frames.size > 1)

        val totalFrags = frames.size
        val receivedSlots = arrayOfNulls<ByteArray>(totalFrags)
        var receivedCount = 0

        // Shuffle frames to simulate out-of-order delivery
        val shuffledIndices = frames.indices.shuffled()

        // Deliver shuffled frames + inject duplicates
        val deliverySequence = shuffledIndices.toMutableList()
        deliverySequence.add(0, shuffledIndices[0]) // Duplicate first delivered frame
        deliverySequence.add(shuffledIndices.last()) // Duplicate last delivered frame

        for (idx in deliverySequence) {
            val frame = frames[idx]
            val fragIndex = ((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF)
            val slice = frame.copyOfRange(GattOperationQueue.FRAGMENT_HEADER_SIZE, frame.size)

            if (receivedSlots[fragIndex] == null) {
                receivedSlots[fragIndex] = slice
                receivedCount++
            }
        }

        assertEquals("All distinct slots must be filled", totalFrags, receivedCount)

        // Combine
        val totalBytes = receivedSlots.filterNotNull().sumOf { it.size }
        val reassembled = ByteArray(totalBytes)
        var pos = 0
        for (slot in receivedSlots) {
            assertNotNull("Slot must not be null", slot)
            System.arraycopy(slot!!, 0, reassembled, pos, slot.size)
            pos += slot.size
        }

        assertArrayEquals("Out-of-order reassembly with duplicates must match original", original, reassembled)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testPayloadExceeding64KbCeilingThrows() {
        val oversized = ByteArray(GattOperationQueue.MAX_RECONSTRUCTED_PAYLOAD_BYTES + 1)
        GattOperationQueue.slicePayload(oversized, negotiatedMtu = 23)
    }
}
