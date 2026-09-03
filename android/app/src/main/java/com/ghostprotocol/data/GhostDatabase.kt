package com.ghostprotocol.data

import android.content.Context
import androidx.room.*
import com.ghostprotocol.power.TelemetryDao
import com.ghostprotocol.power.TelemetryEntity

@Database(entities = [Contact::class, MessageEntity::class, TelemetryEntity::class], version = 5, exportSchema = false)
abstract class GhostDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun telemetryDao(): TelemetryDao

    companion object {
        @Volatile
        private var INSTANCE: GhostDatabase? = null

        fun getInstance(context: Context): GhostDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GhostDatabase::class.java,
                    "ghost_database"
                ).fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
