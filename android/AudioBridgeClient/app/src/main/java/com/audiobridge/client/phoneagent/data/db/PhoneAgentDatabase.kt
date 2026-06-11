package com.audiobridge.client.phoneagent.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MessageEntity::class,
        PendingRequestEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class PhoneAgentDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun pendingRequestDao(): PendingRequestDao

    companion object {
        @Volatile
        private var instance: PhoneAgentDatabase? = null

        fun get(context: Context): PhoneAgentDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhoneAgentDatabase::class.java,
                    "phone_agent.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE phone_agent_messages ADD COLUMN artifactsJson TEXT")
            }
        }
    }
}
