package com.ghostprotocol.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ghostprotocol.power.TelemetryDao
import com.ghostprotocol.power.TelemetryEntity

@Database(
    entities = [
        Contact::class,
        MessageEntity::class,
        TelemetryEntity::class,
        GroupEntity::class,
        GroupMessageEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class GhostDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun groupDao(): GroupDao
    abstract fun groupMessageDao(): GroupMessageDao

    companion object {
        @Volatile
        private var INSTANCE: GhostDatabase? = null

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE contacts ADD COLUMN isVerified INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS groups (
                        groupId TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        creatorContactId TEXT NOT NULL,
                        memberContactIdsJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS group_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        groupId TEXT NOT NULL,
                        senderContactId TEXT NOT NULL,
                        text TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        status INTEGER NOT NULL,
                        replyToSender TEXT,
                        replyToText TEXT
                    )
                """.trimIndent())

                database.execSQL("CREATE INDEX IF NOT EXISTS idx_group_messages_groupId ON group_messages(groupId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_group_messages_timestamp ON group_messages(timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_group_messages_status ON group_messages(status)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN contentHash TEXT")
                database.execSQL("ALTER TABLE group_messages ADD COLUMN contentHash TEXT")
                database.execSQL("ALTER TABLE group_messages ADD COLUMN deliveredMemberIdsJson TEXT NOT NULL DEFAULT '[]'")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_contentHash ON messages(contentHash)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_group_messages_contentHash ON group_messages(contentHash)")
            }
        }

        fun getInstance(context: Context): GhostDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GhostDatabase::class.java,
                    "ghost_database"
                )
                .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
