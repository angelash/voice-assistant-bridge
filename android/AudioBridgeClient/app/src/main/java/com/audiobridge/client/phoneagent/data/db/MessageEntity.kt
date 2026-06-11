package com.audiobridge.client.phoneagent.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.audiobridge.client.phoneagent.model.BridgeMessageStatus
import com.audiobridge.client.phoneagent.model.LocalMessageStatus
import com.audiobridge.client.phoneagent.model.PhoneAgentDefaults

@Entity(tableName = "phone_agent_messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val sessionId: String,
    val clientId: String,
    val source: String = PhoneAgentDefaults.SOURCE,
    val role: String = "user",
    val text: String,
    val localReply: String? = null,
    val finalReply: String? = null,
    val localStatus: String = LocalMessageStatus.PENDING.name,
    val bridgeStatus: String = BridgeMessageStatus.NEW,
    val artifactsJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val errorMessage: String? = null,
)
