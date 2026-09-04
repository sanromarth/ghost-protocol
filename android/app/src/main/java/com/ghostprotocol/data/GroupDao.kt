package com.ghostprotocol.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups WHERE isActive = 1 ORDER BY createdAt DESC")
    fun getAllActive(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE isActive = 1 ORDER BY createdAt DESC")
    suspend fun getAllActiveOnce(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE groupId = :groupId LIMIT 1")
    suspend fun getById(groupId: String): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: GroupEntity): Long

    @Query("UPDATE groups SET isActive = 0 WHERE groupId = :groupId")
    suspend fun deactivate(groupId: String)

    @Query("DELETE FROM groups WHERE groupId = :groupId")
    suspend fun delete(groupId: String)
}
