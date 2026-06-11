package com.audiobridge.client.phoneagent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM phone_agent_messages ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM phone_agent_messages WHERE messageId = :messageId LIMIT 1")
    suspend fun get(messageId: String): MessageEntity?

    @Query(
        """
        SELECT * FROM phone_agent_messages
        WHERE bridgeStatus NOT IN ('DELIVERED', 'FAILED')
            OR (bridgeStatus = 'DELIVERED' AND finalReply IS NULL AND createdAt >= :recentCreatedAfter)
        ORDER BY createdAt ASC
        LIMIT :limit
        """
    )
    suspend fun activeForRefresh(recentCreatedAfter: Long, limit: Int = 50): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("DELETE FROM phone_agent_messages")
    suspend fun deleteAll()
}
