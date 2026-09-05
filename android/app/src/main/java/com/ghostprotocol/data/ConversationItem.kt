package com.ghostprotocol.data

/**
 * Unified representation of a conversation in the GHOST mesh:
 * represents either a 1:1 Direct mesh chat or an encrypted Cell Group.
 * Pre-aggregated upstream to eliminate expensive database queries,
 * hashing, and sorting during LazyColumn rendering.
 */
sealed class ConversationItem {
    abstract val id: String
    abstract val name: String
    abstract val lastMessageText: String?
    abstract val lastMessageTime: Long
    abstract val unreadCount: Int

    val key: String
        get() = when (this) {
            is Direct -> "direct_$id"
            is Group -> "group_$id"
        }

    data class Direct(
        override val id: String,                    // Contact.id (16-char hex)
        override val name: String,
        override val lastMessageText: String?,
        override val lastMessageTime: Long,
        override val unreadCount: Int = 0,
        val ed25519PubKeyBase64: String,
        val isVerified: Boolean,
        val isIntroduced: Boolean,
        val isDirectRadio: Boolean,                 // Peer in direct BLE range
        val directRssi: Int?,                       // RSSI in dBm if direct
        val lastMessageStatus: Int?,                // STATUS_PENDING, STATUS_SENT, STATUS_DELIVERED, STATUS_SPRAYED, STATUS_FAILED
        val lastMessageIsOutgoing: Boolean
    ) : ConversationItem()

    data class Group(
        override val id: String,                    // GroupEntity.groupId
        override val name: String,
        override val lastMessageText: String?,
        override val lastMessageTime: Long,
        override val unreadCount: Int = 0,
        val memberCount: Int,
        val lastMessageSenderName: String?,
        val lastMessageStatus: Int?,
        val lastMessageIsOutgoing: Boolean
    ) : ConversationItem()
}
