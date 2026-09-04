package com.ghostprotocol.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,           // 64-char hex of SHA-256(creatorEd25519Pub + creationTimestamp + nonce)
    val name: String,
    val creatorContactId: String,              // 16-char hex contact ID of creator
    val memberContactIdsJson: String,          // JSON array of contact IDs, e.g., ["a3f7e2...","b8c1d4..."]
    val createdAt: Long,
    val isActive: Boolean = true
)
