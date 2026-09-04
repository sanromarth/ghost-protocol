package com.ghostprotocol.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupMessageDao {
    @Query("SELECT * FROM group_messages WHERE groupId = :groupId ORDER BY timestamp ASC")
    fun getMessagesForGroup(groupId: String): Flow<List<GroupMessageEntity>>

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId AND status IN (0, 4) ORDER BY timestamp ASC")
    suspend fun getPendingOrSprayedForGroup(groupId: String): List<GroupMessageEntity>

    @Query("SELECT COUNT(*) FROM group_messages WHERE groupId = :groupId AND status = 2")
    suspend fun getUnreadCountForGroup(groupId: String): Int

    @Insert
    suspend fun insert(message: GroupMessageEntity): Long

    @Query("UPDATE group_messages SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: Long, status: Int)

    @Query("DELETE FROM group_messages WHERE timestamp < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int

    @Query("DELETE FROM group_messages WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String): Int
}
