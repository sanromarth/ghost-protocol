package com.ghostprotocol.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name ASC")
    fun getAll(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts")
    suspend fun getAllOnce(): List<Contact>

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun getById(id: String): Contact?

    @Query("SELECT * FROM contacts WHERE bleAddress = :address")
    suspend fun getByBleAddress(address: String): Contact?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: Contact)

    @Query("UPDATE contacts SET bleAddress = :address WHERE id = :id")
    suspend fun updateBleAddress(id: String, address: String)

    @Query("UPDATE contacts SET name = :name WHERE id = :id")
    suspend fun updateName(id: String, name: String)

    @Query("UPDATE contacts SET isVerified = :isVerified WHERE id = :id")
    suspend fun updateVerified(id: String, isVerified: Boolean)

    @Delete
    suspend fun delete(contact: Contact)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()
}
