package com.ghostprotocol.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupMessageDao {
    @Query("SELECT * FROM group_messages WHERE groupId = :groupId ORDER BY timestamp ASC")
    fun getMessagesForGroup(groupId: String): Flow<List<GroupMessageEntity>>

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId AND status IN (0, 4) ORDER BY timestamp ASC")
    suspend fun getPendingOrSprayedForGroup(groupId: String): List<GroupMessageEntity>

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId AND senderContactId = :senderContactId AND status IN (0, 4) ORDER BY timestamp ASC")
    suspend fun getPendingOrSprayedForGroup(groupId: String, senderContactId: String): List<GroupMessageEntity>

    @Query("SELECT COUNT(*) FROM group_messages WHERE groupId = :groupId AND status = 2")
    suspend fun getUnreadCountForGroup(groupId: String): Int

    @Insert
    suspend fun insert(message: GroupMessageEntity): Long

    // Terminal status guard: STATUS_DELIVERED (2) cannot be downgraded by stale callbacks
    @Query("UPDATE group_messages SET status = :status WHERE id = :messageId AND (status != 2 OR :status = 2)")
    suspend fun updateStatus(messageId: Long, status: Int)

    @Query("DELETE FROM group_messages WHERE timestamp < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int

    @Query("DELETE FROM group_messages WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String): Int

    @Query("SELECT * FROM group_messages WHERE contentHash = :hash LIMIT 1")
    suspend fun getByContentHash(hash: String): GroupMessageEntity?

    @Query("UPDATE group_messages SET deliveredMemberIdsJson = :json WHERE id = :messageId")
    suspend fun updateDeliveredMembers(messageId: Long, json: String)

    @Query("SELECT * FROM group_messages ORDER BY timestamp DESC")
    fun getAllGroupMessages(): Flow<List<GroupMessageEntity>>
}
