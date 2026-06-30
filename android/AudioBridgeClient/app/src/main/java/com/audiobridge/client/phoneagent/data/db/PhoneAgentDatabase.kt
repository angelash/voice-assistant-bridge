package com.audiobridge.client.phoneagent.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ArtifactEntity::class,
        MessageEntity::class,
        PendingRequestEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class PhoneAgentDatabase : RoomDatabase() {
    abstract fun artifactDao(): ArtifactDao
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE phone_agent_messages ADD COLUMN artifactsJson TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS phone_agent_artifacts (
                        localId TEXT NOT NULL PRIMARY KEY,
                        bridgeArtifactId TEXT,
                        sessionId TEXT NOT NULL,
                        clientId TEXT NOT NULL,
                        artifactType TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        filename TEXT NOT NULL,
                        localPath TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        captureTs TEXT,
                        uploadStatus TEXT NOT NULL,
                        relatedMessageId TEXT,
                        source TEXT NOT NULL,
                        metaJson TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        lastError TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_phone_agent_artifacts_bridgeArtifactId ON phone_agent_artifacts(bridgeArtifactId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_phone_agent_artifacts_sessionId ON phone_agent_artifacts(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_phone_agent_artifacts_source ON phone_agent_artifacts(source)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_phone_agent_artifacts_createdAt ON phone_agent_artifacts(createdAt)")
            }
        }
    }
}
