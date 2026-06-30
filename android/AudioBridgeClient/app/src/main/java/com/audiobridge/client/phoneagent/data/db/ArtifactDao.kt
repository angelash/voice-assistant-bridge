package com.audiobridge.client.phoneagent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtifactDao {
    @Query("SELECT * FROM phone_agent_artifacts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM phone_agent_artifacts WHERE localId = :localId LIMIT 1")
    suspend fun get(localId: String): ArtifactEntity?

    @Query("SELECT * FROM phone_agent_artifacts WHERE localPath = :localPath LIMIT 1")
    suspend fun getByLocalPath(localPath: String): ArtifactEntity?

    @Query("SELECT * FROM phone_agent_artifacts WHERE createdAt < :cutoffMs ORDER BY createdAt ASC LIMIT :limit")
    suspend fun olderThan(cutoffMs: Long, limit: Int = 500): List<ArtifactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(artifact: ArtifactEntity)

    @Query("DELETE FROM phone_agent_artifacts WHERE localId = :localId")
    suspend fun delete(localId: String)

    @Query("DELETE FROM phone_agent_artifacts")
    suspend fun deleteAll()
}
