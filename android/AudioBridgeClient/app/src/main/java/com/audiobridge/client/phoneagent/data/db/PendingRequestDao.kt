package com.audiobridge.client.phoneagent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingRequestDao {
    @Query("SELECT * FROM phone_agent_pending_requests WHERE id = :id LIMIT 1")
    suspend fun get(id: String): PendingRequestEntity?

    @Query(
        """
        SELECT * FROM phone_agent_pending_requests
        WHERE requestType = :requestType AND nextRetryAt <= :nowMs
        ORDER BY createdAt ASC
        LIMIT :limit
        """
    )
    suspend fun due(requestType: String, nowMs: Long, limit: Int = 20): List<PendingRequestEntity>

    @Query("SELECT COUNT(*) FROM phone_agent_pending_requests")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(request: PendingRequestEntity)

    @Query("DELETE FROM phone_agent_pending_requests WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM phone_agent_pending_requests")
    suspend fun deleteAll()
}

