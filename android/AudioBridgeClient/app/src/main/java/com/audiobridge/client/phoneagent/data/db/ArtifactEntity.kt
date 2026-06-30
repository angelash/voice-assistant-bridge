package com.audiobridge.client.phoneagent.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "phone_agent_artifacts",
    indices = [
        Index("bridgeArtifactId"),
        Index("sessionId"),
        Index("source"),
        Index("createdAt"),
    ],
)
data class ArtifactEntity(
    @PrimaryKey val localId: String,
    val bridgeArtifactId: String? = null,
    val sessionId: String,
    val clientId: String,
    val artifactType: String,
    val mimeType: String,
    val filename: String,
    val localPath: String,
    val sizeBytes: Long,
    val captureTs: String?,
    val uploadStatus: String,
    val relatedMessageId: String? = null,
    val source: String,
    val metaJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastError: String? = null,
)

object ArtifactUploadStatus {
    const val QUEUED = "QUEUED"
    const val UPLOADING = "UPLOADING"
    const val UPLOADED = "UPLOADED"
    const val FAILED = "FAILED"
}
