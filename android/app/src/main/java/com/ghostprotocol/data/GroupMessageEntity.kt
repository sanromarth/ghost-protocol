package com.ghostprotocol.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "group_messages",
    indices = [
        Index(value = ["groupId", "timestamp"]),
        Index(value = ["status"]),
        Index(value = ["contentHash"])
    ]
)
data class GroupMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: String,
    val senderContactId: String,               // 16-char hex
    val text: String,                          // plaintext, stored locally after decryption
    val timestamp: Long,
    val status: Int,                           // STATUS_PENDING=0, STATUS_SENT=1, STATUS_DELIVERED=2, STATUS_FAILED=3, STATUS_SPRAYED=4
    val replyToSender: String? = null,
    val replyToText: String? = null,
    val contentHash: String? = null,           // SHA-256(senderContactId || timestamp || plaintext)
    val deliveredMemberIdsJson: String = "[]"  // JSON array of contact IDs who acknowledged receipt
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_SENT = 1
        const val STATUS_DELIVERED = 2
        const val STATUS_FAILED = 3
        const val STATUS_SPRAYED = 4
    }
}
