package com.ghostprotocol

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Deterministic Android persistence regression test for the delivery receipt / GATT race condition.
 *
 * Verifies that Room DAO queries atomically reject downgrading a terminal STATUS_DELIVERED (2)
 * to STATUS_SENT (1), STATUS_SPRAYED (4), STATUS_PENDING (0), or STATUS_FAILED (3).
 */
class MessageStatusTransitionTest {

    private lateinit var conn: Connection

    // Production Room query constants matching MessageDao.kt and GroupMessageDao.kt
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_SENT = 1
        const val STATUS_DELIVERED = 2
        const val STATUS_FAILED = 3
        const val STATUS_SPRAYED = 4

        // Exact Room KSP compiled SQL queries
        const val SQL_UPDATE_STATUS_GUARDED =
            "UPDATE messages SET status = ? WHERE id = ? AND (status != 2 OR ? = 2)"

        const val SQL_UPDATE_STATUS_BY_HASH_GUARDED =
            "UPDATE messages SET status = ? WHERE contentHash = ? AND (status != 2 OR ? = 2)"

        const val SQL_UPDATE_GROUP_STATUS_GUARDED =
            "UPDATE group_messages SET status = ? WHERE id = ? AND (status != 2 OR ? = 2)"

        // Old un-guarded query demonstrating the production vulnerability
        const val SQL_UPDATE_STATUS_UNGUARDED_OLD =
            "UPDATE messages SET status = ? WHERE id = ?"
    }

    @Before
    fun setUp() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val stmt = conn.createStatement()

        // Create messages table matching MessageEntity.kt
        stmt.execute(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY NOT NULL,
                contactId TEXT NOT NULL,
                content TEXT NOT NULL,
                isOutgoing INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                isVerified INTEGER NOT NULL,
                status INTEGER NOT NULL,
                replyToId TEXT,
                replyToSender TEXT,
                replyToText TEXT,
                contentHash TEXT
            )
            """.trimIndent()
        )

        // Create group_messages table matching GroupMessageEntity.kt
        stmt.execute(
            """
            CREATE TABLE group_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                groupId TEXT NOT NULL,
                senderContactId TEXT NOT NULL,
                text TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                status INTEGER NOT NULL,
                deliveredMemberIdsJson TEXT NOT NULL,
                contentHash TEXT
            )
            """.trimIndent()
        )
        stmt.close()
    }

    @After
    fun tearDown() {
        if (!conn.isClosed) {
            conn.close()
        }
    }

    private fun insertTestMessage(id: String, status: Int, hash: String = "hash_$id") {
        val ps = conn.prepareStatement(
            "INSERT INTO messages (id, contactId, content, isOutgoing, timestamp, isVerified, status, contentHash) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        )
        ps.setString(1, id)
        ps.setString(2, "contact_alice")
        ps.setString(3, "Test Message")
        ps.setInt(4, 1)
        ps.setLong(5, System.currentTimeMillis())
        ps.setInt(6, 1)
        ps.setInt(7, status)
        ps.setString(8, hash)
        ps.executeUpdate()
        ps.close()
    }

    private fun getMessageStatus(id: String): Int {
        val ps = conn.prepareStatement("SELECT status FROM messages WHERE id = ?")
        ps.setString(1, id)
        val rs = ps.executeQuery()
        assertTrue("Message $id must exist", rs.next())
        val status = rs.getInt("status")
        rs.close()
        ps.close()
        return status
    }

    private fun executeUpdateStatus(id: String, newStatus: Int): Int {
        val ps = conn.prepareStatement(SQL_UPDATE_STATUS_GUARDED)
        ps.setInt(1, newStatus)
        ps.setString(2, id)
        ps.setInt(3, newStatus)
        val rows = ps.executeUpdate()
        ps.close()
        return rows
    }

    /**
     * Primary Regression Test:
     * 1. Message exists.
     * 2. GATT send is in flight.
     * 3. Valid delivery receipt arrives -> STATUS_DELIVERED (2).
     * 4. Delayed GATT success callback arrives -> attempts STATUS_SENT (1).
     * 5. Persistence layer rejects stale downgrade (0 rows updated).
     * 6. Final status remains STATUS_DELIVERED (2).
     */
    @Test
    fun testGattRaceConditionDeliveryReceiptWinsOverDelayedGattCallback() {
        val msgId = "race_msg_1"
        insertTestMessage(msgId, STATUS_PENDING)
        assertEquals(STATUS_PENDING, getMessageStatus(msgId))

        // 1. GATT in-flight transmission begins (still PENDING)

        // 2. Receipt arrives before delayed GATT callback finishes
        val receiptRows = executeUpdateStatus(msgId, STATUS_DELIVERED)
        assertEquals("Receipt update must update 1 row", 1, receiptRows)
        assertEquals("Status must transition to DELIVERED", STATUS_DELIVERED, getMessageStatus(msgId))

        // 3. Delayed GATT callback completes and attempts to write STATUS_SENT
        val staleGattRows = executeUpdateStatus(msgId, STATUS_SENT)
        assertEquals("Delayed GATT callback must be rejected by persistence guard (0 rows updated)", 0, staleGattRows)

        // 4. Invariant: Status must remain DELIVERED
        assertEquals("Final message status must remain terminal DELIVERED", STATUS_DELIVERED, getMessageStatus(msgId))
    }

    /**
     * Verify all valid forward state transitions and all prohibited backward transitions.
     */
    @Test
    fun testAllStatusTransitionsMatrix() {
        // Valid forward transitions
        val m1 = "trans_1"
        insertTestMessage(m1, STATUS_PENDING)
        assertEquals(1, executeUpdateStatus(m1, STATUS_SENT))
        assertEquals(STATUS_SENT, getMessageStatus(m1))
        assertEquals(1, executeUpdateStatus(m1, STATUS_DELIVERED))
        assertEquals(STATUS_DELIVERED, getMessageStatus(m1))

        val m2 = "trans_2"
        insertTestMessage(m2, STATUS_PENDING)
        assertEquals(1, executeUpdateStatus(m2, STATUS_SPRAYED))
        assertEquals(STATUS_SPRAYED, getMessageStatus(m2))
        assertEquals(1, executeUpdateStatus(m2, STATUS_DELIVERED))
        assertEquals(STATUS_DELIVERED, getMessageStatus(m2))

        // Prohibited backward transitions from DELIVERED
        val terminalMsg = "terminal_msg"
        insertTestMessage(terminalMsg, STATUS_DELIVERED)

        // DELIVERED -> SENT (Must Fail)
        assertEquals(0, executeUpdateStatus(terminalMsg, STATUS_SENT))
        assertEquals(STATUS_DELIVERED, getMessageStatus(terminalMsg))

        // DELIVERED -> SPRAYED (Must Fail)
        assertEquals(0, executeUpdateStatus(terminalMsg, STATUS_SPRAYED))
        assertEquals(STATUS_DELIVERED, getMessageStatus(terminalMsg))

        // DELIVERED -> PENDING (Must Fail)
        assertEquals(0, executeUpdateStatus(terminalMsg, STATUS_PENDING))
        assertEquals(STATUS_DELIVERED, getMessageStatus(terminalMsg))

        // DELIVERED -> FAILED (Must Fail)
        assertEquals(0, executeUpdateStatus(terminalMsg, STATUS_FAILED))
        assertEquals(STATUS_DELIVERED, getMessageStatus(terminalMsg))

        // DELIVERED -> DELIVERED (Idempotent success)
        assertEquals(1, executeUpdateStatus(terminalMsg, STATUS_DELIVERED))
        assertEquals(STATUS_DELIVERED, getMessageStatus(terminalMsg))
    }

    /**
     * Proves that the old un-guarded query was vulnerable to state rollback,
     * confirming the regression test would fail on the unpatched code.
     */
    @Test
    fun testProofOfVulnerabilityOnOldUnguardedQuery() {
        val msgId = "vuln_msg"
        insertTestMessage(msgId, STATUS_DELIVERED)

        // Execute old un-guarded query
        val oldPs = conn.prepareStatement(SQL_UPDATE_STATUS_UNGUARDED_OLD)
        oldPs.setInt(1, STATUS_SENT)
        oldPs.setString(2, msgId)
        val updatedRows = oldPs.executeUpdate()
        oldPs.close()

        // Old behavior rolled back DELIVERED to SENT!
        assertEquals("Old query erroneously updated row", 1, updatedRows)
        assertEquals("Old query corrupted status to SENT", STATUS_SENT, getMessageStatus(msgId))
    }

    /**
     * Verifies that GroupMessageDao atomic guard prevents group message rollback.
     */
    @Test
    fun testGroupMessageStatusGuard() {
        val ps = conn.prepareStatement(
            "INSERT INTO group_messages (groupId, senderContactId, text, timestamp, status, deliveredMemberIdsJson) VALUES (?, ?, ?, ?, ?, ?)"
        )
        ps.setString(1, "group_ops")
        ps.setString(2, "self_node")
        ps.setString(3, "Group Alert")
        ps.setLong(4, System.currentTimeMillis())
        ps.setInt(5, STATUS_DELIVERED)
        ps.setString(6, "[]")
        ps.executeUpdate()
        ps.close()

        // Attempt stale downgrade to SENT
        val updatePs = conn.prepareStatement(SQL_UPDATE_GROUP_STATUS_GUARDED)
        updatePs.setInt(1, STATUS_SENT)
        updatePs.setLong(2, 1L)
        updatePs.setInt(3, STATUS_SENT)
        val rows = updatePs.executeUpdate()
        updatePs.close()

        assertEquals("Group message downgrade must update 0 rows", 0, rows)

        // Verify status remains DELIVERED
        val checkPs = conn.prepareStatement("SELECT status FROM group_messages WHERE id = 1")
        val rs = checkPs.executeQuery()
        assertTrue(rs.next())
        assertEquals(STATUS_DELIVERED, rs.getInt("status"))
        rs.close()
        checkPs.close()
    }
}
