package com.ghostprotocol.data

import androidx.room.*

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey val id: String,          // hex SHA-256 of ed25519PubKey (first 16 chars)
    val name: String,
    val ed25519PubKey: String,            // Base64-encoded 32-byte key
    val x25519PubKey: String,             // Base64-encoded 32-byte key
    val bleAddress: String? = null,       // MAC address, updated on BLE discovery
    val isVerified: Boolean = false,
    val isIntroduced: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
