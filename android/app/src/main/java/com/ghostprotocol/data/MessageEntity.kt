package com.ghostprotocol.data

import androidx.room.*
import java.util.UUID

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["contactId", "timestamp"]),
        Index(value = ["status", "isOutgoing"])
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val contactId: String,               // FK to Contact.id
    val content: String,                 // plaintext message text
    val isOutgoing: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isVerified: Boolean = false,     // Ed25519 signature verified
    val status: Int = 0  // 0=PENDING, 1=SENT, 2=DELIVERED, 3=FAILED
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_SENT = 1
        const val STATUS_DELIVERED = 2
        const val STATUS_FAILED = 3
        const val STATUS_SPRAYED = 4
    }
}
