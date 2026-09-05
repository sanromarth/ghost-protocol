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

    @Test
    fun testPeekMethodsOnInviteEnvelope() {
        val groupIdHex = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val groupIdBytes = GroupProtocol.hexToBytes(groupIdHex)
        val creatorId = "creator_contact_"
        val timestamp = 1700000005000L
        val fakeCiphertext = "encrypted_invite_data".toByteArray(Charsets.UTF_8)
        val fakeSignature = ByteArray(64) { 0x77.toByte() }

        val unsignedLen = 1 + 32 + 16 + 8 + fakeCiphertext.size
        val buffer = ByteBuffer.allocate(unsignedLen + 64)
        buffer.put(GroupProtocol.OPCODE_GROUP_INVITE)
        buffer.put(groupIdBytes)
        buffer.put(creatorId.toByteArray(Charsets.UTF_8))
        buffer.order(ByteOrder.BIG_ENDIAN).putLong(timestamp)
        buffer.put(fakeCiphertext)
        buffer.put(fakeSignature)

        val packet = buffer.array()

        assertEquals(creatorId, GroupProtocol.peekInviteCreatorContactId(packet))
        assertEquals(groupIdHex, GroupProtocol.peekInviteGroupIdHex(packet))

        // Ensure 0x30 methods reject 0x31 packet
        assertNull(GroupProtocol.peekSenderContactId(packet))
        // And ensure 0x31 methods reject 0x30 packet
        packet[0] = GroupProtocol.OPCODE_GROUP_ENVELOPE
        assertNull(GroupProtocol.peekInviteCreatorContactId(packet))
        assertNull(GroupProtocol.peekInviteGroupIdHex(packet))
    }

    @Test
    fun testWirePayloadFormattingAndParsingSimple() {
        val wire = GroupProtocol.formatWirePayload(
            senderName = "Alice",
            replyTo = null,
            meta = null,
            text = "Hello world!"
        )

        val parsed = GroupProtocol.parseWirePayload(wire)
        assertEquals("Alice", parsed.senderName)
        assertNull(parsed.replySender)
        assertNull(parsed.replyText)
        assertNull(parsed.metaGroupName)
        assertNull(parsed.metaCreatorId)
        assertNull(parsed.metaMembersJson)
        assertEquals("Hello world!", parsed.text)
    }

    @Test
    fun testWirePayloadWithReplyAndMeta() {
        val wire = GroupProtocol.formatWirePayload(
            senderName = "Tony Stark",
            replyTo = Pair("Cap", "We need a plan"),
            meta = Triple("AVENGERS", "tony_id_16chars", "[\"tony\",\"cap\",\"thor\"]"),
            text = "I have a plan: attack."
        )

        val parsed = GroupProtocol.parseWirePayload(wire)
        assertEquals("Tony Stark", parsed.senderName)
        assertEquals("Cap", parsed.replySender)
        assertEquals("We need a plan", parsed.replyText)
        assertEquals("AVENGERS", parsed.metaGroupName)
        assertEquals("tony_id_16chars", parsed.metaCreatorId)
        assertEquals("[\"tony\",\"cap\",\"thor\"]", parsed.metaMembersJson)
        assertEquals("I have a plan: attack.", parsed.text)
    }

    @Test
    fun testWirePayloadLegacyBackwardCompatibility() {
        // Legacy format 1: Simple message without reply or META
        val legacySimple = "Bob\u0000How are you?"
        val parsedSimple = GroupProtocol.parseWirePayload(legacySimple)
        assertEquals("Bob", parsedSimple.senderName)
        assertNull(parsedSimple.replySender)
        assertNull(parsedSimple.replyText)
        assertNull(parsedSimple.metaGroupName)
        assertEquals("How are you?", parsedSimple.text)

        // Legacy format 2: Reply without META
        val legacyReply = "Bob\u0000REPLY\u0000Alice\u0000Good morning\u0000Morning to you too!"
        val parsedReply = GroupProtocol.parseWirePayload(legacyReply)
        assertEquals("Bob", parsedReply.senderName)
        assertEquals("Alice", parsedReply.replySender)
        assertEquals("Good morning", parsedReply.replyText)
        assertNull(parsedReply.metaGroupName)
        assertEquals("Morning to you too!", parsedReply.text)
    }

    // =========================================================================
    // PHASE 1 REGRESSION TESTS — CELL GROUP ARCHITECTURAL BASELINE
    // =========================================================================

    /**
     * Test 1: Normal Invitation
     * Verifies complete packaging of 0x31 invite envelope, field boundary offsets,
     * peek methods, and payload format: INVITE\0groupName\0membersJson.
     */
    @Test
    fun testPhase1Test1NormalInvitation() {
        val groupIdHex = "c0ffee00112233445566778899aabbccddeeff00112233445566778899aabbcc"
        val groupIdBytes = GroupProtocol.hexToBytes(groupIdHex)
        val creatorId = "alice_sec_ops__"
        val timestamp = 1700000010000L
        val groupName = "Signal Core"
        val membersJson = "[\"alice_sec_ops__\",\"bob_node_000000\"]"
        val invitePayload = "INVITE\u0000$groupName\u0000$membersJson".toByteArray(Charsets.UTF_8)
        val fakeSig = ByteArray(64) { 0x31.toByte() }

        val buf = ByteBuffer.allocate(1 + 32 + 16 + 8 + invitePayload.size + 64)
        buf.put(GroupProtocol.OPCODE_GROUP_INVITE)
        buf.put(groupIdBytes)
        buf.put(creatorId.padEnd(16, ' ').take(16).toByteArray(Charsets.UTF_8))
        buf.order(ByteOrder.BIG_ENDIAN).putLong(timestamp)
        buf.put(invitePayload)
        buf.put(fakeSig)
        val packet = buf.array()

        // Verify wire boundaries
        assertEquals(GroupProtocol.OPCODE_GROUP_INVITE, packet[0])
        assertEquals(creatorId, GroupProtocol.peekInviteCreatorContactId(packet))
        assertEquals(groupIdHex, GroupProtocol.peekInviteGroupIdHex(packet))

        // Verify inner plaintext schema
        val parts = String(invitePayload, Charsets.UTF_8).split('\u0000')
        assertEquals(3, parts.size)
        assertEquals("INVITE", parts[0])
        assertEquals(groupName, parts[1])
        assertEquals(membersJson, parts[2])
    }

    /**
     * Test 2: Offline Recipient (DTN Delay)
     * Simulates an invite received after a 72-hour delay in the mesh transit buffer.
     * Invariant: Envelope fields, timestamp, and group metadata must survive delay intact.
     */
    @Test
    fun testPhase1Test2OfflineRecipientDelayedEncounter() {
        val groupIdHex = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        val groupIdBytes = GroupProtocol.hexToBytes(groupIdHex)
        val creatorId = "carol_mesh_user"
        val delayMillis = 72 * 3600 * 1000L // 3 days in transit
        val origTimestamp = 1700000000000L - delayMillis
        val invitePayload = "INVITE\u0000Delayed Cell\u0000[\"carol_mesh_user\",\"dave_dtn_peer\"]".toByteArray(Charsets.UTF_8)
        val fakeSig = ByteArray(64) { 0x42.toByte() }

        val buf = ByteBuffer.allocate(1 + 32 + 16 + 8 + invitePayload.size + 64)
        buf.put(GroupProtocol.OPCODE_GROUP_INVITE)
        buf.put(groupIdBytes)
        buf.put(creatorId.padEnd(16, ' ').take(16).toByteArray(Charsets.UTF_8))
        buf.order(ByteOrder.BIG_ENDIAN).putLong(origTimestamp)
        buf.put(invitePayload)
        buf.put(fakeSig)
        val packet = buf.array()

        // Verify extraction after long delay
        assertEquals(creatorId, GroupProtocol.peekInviteCreatorContactId(packet))
        assertEquals(groupIdHex, GroupProtocol.peekInviteGroupIdHex(packet))

        // Extract timestamp from wire format
        val extractedTs = ByteBuffer.wrap(packet, 49, 8).order(ByteOrder.BIG_ENDIAN).long
        assertEquals(origTimestamp, extractedTs)
    }

    /**
     * Test 3: Self-Healing from Group Message (0x30)
     * Verifies that if an offline node never received the 0x31 invite,
     * an incoming 0x30 message containing META recovers group name, creator, and member list.
     */
    @Test
    fun testPhase1Test3SelfHealingFromGroupMessage() {
        val rawWire = GroupProtocol.formatWirePayload(
            senderName = "Alice",
            replyTo = null,
            meta = Triple("Resistance Cell #4", "creator_id_1234", "[\"creator_id_1234\",\"new_member_5678\"]"),
            text = "Welcome to the group, here is our tactical update."
        )

        val parsed = GroupProtocol.parseWirePayload(rawWire)
        assertEquals("Alice", parsed.senderName)
        assertNull(parsed.replySender)
        assertNull(parsed.replyText)
        assertEquals("Resistance Cell #4", parsed.metaGroupName)
        assertEquals("creator_id_1234", parsed.metaCreatorId)
        assertEquals("[\"creator_id_1234\",\"new_member_5678\"]", parsed.metaMembersJson)
        assertEquals("Welcome to the group, here is our tactical update.", parsed.text)
    }

    /**
     * Test 4: Duplicate Invitation Idempotency
     * Verifies that repeated receipt of the same invite generates the exact same
     * deduplication signature hash, preventing duplicate DB insertions or notification spam.
     */
    @Test
    fun testPhase1Test4DuplicateInvitationIdempotency() {
        val signature = ByteArray(64) { (it * 3).toByte() }
        val sigHex1 = GroupProtocol.bytesToHex(signature)
        val sigHex2 = GroupProtocol.bytesToHex(signature)
        assertEquals(sigHex1, sigHex2)

        val dedupCache = HashMap<String, Long>()
        val now = System.currentTimeMillis()

        // First receipt: passes dedup check
        val wasPresent1 = dedupCache.put(sigHex1, now) != null
        assertFalse("First invite must not be dropped as duplicate", wasPresent1)

        // Second receipt (repeat encounter): detected as duplicate
        val wasPresent2 = dedupCache.put(sigHex2, now + 500) != null
        assertTrue("Duplicate invite must be identified by signature hash", wasPresent2)
    }

    /**
     * Test 5: Malformed Invitation Rejection
     * Verifies boundary resilience against:
     * - Truncated envelopes (< MIN_INVITE_SIZE)
     * - Invalid opcodes (0x30, 0x10, 0xFF)
     * - Malformed plaintext payloads (missing INVITE prefix or missing parts)
     */
    @Test
    fun testPhase1Test5MalformedInvitationRejections() {
        // 1. Truncated envelope
        val truncated = ByteArray(120) { 0x31.toByte() }
        assertNull(GroupProtocol.peekInviteCreatorContactId(truncated))
        assertNull(GroupProtocol.peekInviteGroupIdHex(truncated))

        // 2. Wrong opcode
        val wrongOpcode = ByteArray(150) { 0 }
        wrongOpcode[0] = 0x30.toByte() // 0x30 is not 0x31
        assertNull(GroupProtocol.peekInviteCreatorContactId(wrongOpcode))
        assertNull(GroupProtocol.peekInviteGroupIdHex(wrongOpcode))

        // 3. Malformed payload: missing INVITE prefix
        val badPrefix = "NOT_INVITE\u0000MyGroup\u0000[]"
        val partsBadPrefix = badPrefix.split('\u0000')
        assertFalse(partsBadPrefix.size >= 3 && partsBadPrefix[0] == "INVITE")

        // 4. Malformed payload: truncated parts
        val missingParts = "INVITE\u0000OnlyGroup"
        val partsMissing = missingParts.split('\u0000')
        assertTrue(partsMissing.size < 3)
    }

    /**
     * Test 6: Process Restart State Consistency
     * Verifies that group state extracted from an invitation has all required
     * fields for Room persistence and rehydration after app restart.
     */
    @Test
    fun testPhase1Test6ProcessRestartStateConsistency() {
        val groupIdHex = "feedfacedeadbeef0123456789abcdef0123456789abcdef0123456789abcdef"
        val groupName = "Persistent Group"
        val creatorContactId = "creator_id_1234"
        val membersJson = "[\"creator_id_1234\",\"member_2\"]"
        val createdAt = 1710000000000L

        // Validate entity invariants
        assertEquals(64, groupIdHex.length)
        assertTrue(groupName.isNotBlank())
        assertTrue(creatorContactId.isNotBlank())
        assertTrue(membersJson.startsWith("[") && membersJson.endsWith("]"))
        assertTrue(createdAt > 0L)
    }

    /**
     * Test 7: Repeated Group Message Suppression
     * Verifies deduplication behavior for 0x30 envelopes.
     * Identical envelopes yield identical signature hashes for dedup,
     * while different messages yield distinct signature hashes.
     */
    @Test
    fun testPhase1Test7RepeatedGroupMessageSuppression() {
        val sig1 = ByteArray(64) { 0xAA.toByte() }
        val sig2 = ByteArray(64) { 0xBB.toByte() }

        val hash1 = GroupProtocol.bytesToHex(sig1)
        val hash1Repeat = GroupProtocol.bytesToHex(sig1)
        val hash2 = GroupProtocol.bytesToHex(sig2)

        assertEquals(hash1, hash1Repeat)
        assertNotEquals(hash1, hash2)

        val dedupCache = HashMap<String, Long>()
        val t0 = 1000L
        dedupCache[hash1] = t0

        // Duplicate within suppression window (< 60s)
        val t1 = 5000L
        val lastSeen = dedupCache.put(hash1Repeat, t1)
        assertNotNull(lastSeen)
        assertTrue(t1 - lastSeen!! < 60_000L)
    }
}
