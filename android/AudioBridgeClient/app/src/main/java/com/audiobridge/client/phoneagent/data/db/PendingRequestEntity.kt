package com.audiobridge.client.phoneagent.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "phone_agent_pending_requests")
data class PendingRequestEntity(
    @PrimaryKey val id: String,
    val requestType: String,
    val payloadJson: String,
    val retryCount: Int = 0,
    val nextRetryAt: Long = 0L,
    val createdAt: Long,
    val updatedAt: Long,
    val lastError: String? = null,
)

object PendingRequestType {
    const val SEND_MESSAGE = "send_message"
    const val UPLOAD_ARTIFACT = "upload_artifact"
}

