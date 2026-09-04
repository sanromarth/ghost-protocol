package com.ghostprotocol.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE contactId = :contactId ORDER BY timestamp ASC")
    fun getForContact(contactId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE contactId = :contactId ORDER BY timestamp ASC")
    suspend fun getMessagesForContactOnce(contactId: String): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE contactId = :contactId")
    suspend fun deleteForContact(contactId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: Int)

    @Query("UPDATE messages SET status = :newStatus WHERE contactId = :contactId AND status IN (:oldStatuses)")
    suspend fun updateStatusesForContact(contactId: String, oldStatuses: List<Int>, newStatus: Int)

    @Query("SELECT * FROM messages WHERE status = :status AND isOutgoing = 1")
    suspend fun getPendingMessages(status: Int = 0): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE contactId = :contactId AND isOutgoing = 1 AND status IN (0, 4) ORDER BY timestamp ASC")
    suspend fun getSprayedOrPendingForContact(contactId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE contentHash = :hash LIMIT 1")
    suspend fun getByContentHash(hash: String): MessageEntity?

    @Query("UPDATE messages SET status = :status WHERE contentHash = :hash")
    suspend fun updateStatusByHash(hash: String, status: Int)
}
