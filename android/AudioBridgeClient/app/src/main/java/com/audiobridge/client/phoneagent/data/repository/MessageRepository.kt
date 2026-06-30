package com.audiobridge.client.phoneagent.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.audiobridge.client.phoneagent.data.api.BridgeEvent
import com.audiobridge.client.phoneagent.data.api.BridgeArtifactRef
import com.audiobridge.client.phoneagent.data.api.BridgeArtifactUploadResponse
import com.audiobridge.client.phoneagent.data.api.BridgeMessagePayload
import com.audiobridge.client.phoneagent.data.api.BridgeSubmitRequest
import com.audiobridge.client.phoneagent.data.api.OkHttpPhoneAgentBridgeApi
import com.audiobridge.client.phoneagent.data.api.PhoneAgentBridgeApi
import com.audiobridge.client.phoneagent.data.db.ArtifactEntity
import com.audiobridge.client.phoneagent.data.db.ArtifactUploadStatus
import com.audiobridge.client.phoneagent.data.db.MessageEntity
import com.audiobridge.client.phoneagent.data.db.PendingRequestEntity
import com.audiobridge.client.phoneagent.data.db.PendingRequestType
import com.audiobridge.client.phoneagent.data.db.PhoneAgentDatabase
import com.audiobridge.client.phoneagent.data.settings.SettingsRepository
import com.audiobridge.client.phoneagent.model.AppSettings
import com.audiobridge.client.phoneagent.model.BridgeMessageStatus
import com.audiobridge.client.phoneagent.model.LocalMessageStatus
import com.audiobridge.client.phoneagent.model.PhoneAgentDefaults
import com.audiobridge.client.phoneagent.policy.AndroidPhoneAgentDeviceState
import com.audiobridge.client.phoneagent.policy.PhoneAgentPolicy
import com.audiobridge.client.phoneagent.worker.PhoneAgentSyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

data class SyncOutcome(
    val attempted: Int,
    val succeeded: Int,
    val failed: Int,
    val pendingCount: Int,
)

class MessageRepository private constructor(
    private val appContext: Context,
    private val database: PhoneAgentDatabase,
    private val settingsRepository: SettingsRepository,
    private val bridgeApi: PhoneAgentBridgeApi,
) {
    private val messageDao = database.messageDao()
    private val pendingDao = database.pendingRequestDao()
    private val artifactDao = database.artifactDao()
    private val messageUpdateMutex = Mutex()

    val messages: Flow<List<MessageEntity>> = messageDao.observeAll()
    val artifacts: Flow<List<ArtifactEntity>> = artifactDao.observeAll()

    suspend fun sendText(
        text: String,
        artifacts: List<BridgeArtifactRef> = emptyList(),
    ): MessageEntity = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        require(cleanText.isNotBlank()) { "text is required" }
        val settings = settingsRepository.load()
        val now = System.currentTimeMillis()
        val message = MessageEntity(
            messageId = "msg-${UUID.randomUUID()}",
            sessionId = settings.sessionId,
            clientId = settings.clientId,
            source = PhoneAgentDefaults.SOURCE,
            text = cleanText,
            localStatus = LocalMessageStatus.PENDING.name,
            bridgeStatus = BridgeMessageStatus.NEW,
            artifactsJson = artifactRefsToJson(artifacts),
            createdAt = now,
            updatedAt = now,
        )
        val pending = PendingRequestEntity(
            id = message.messageId,
            requestType = PendingRequestType.SEND_MESSAGE,
            payloadJson = buildPendingPayload(message, artifacts).toString(),
            createdAt = now,
            updatedAt = now,
        )
        database.withTransaction {
            messageDao.upsert(message)
            pendingDao.upsert(pending)
        }
        trySendPending(message.messageId, scheduleOnFailure = true)
        messageDao.get(message.messageId) ?: message
    }

    suspend fun sendImageMessage(
        file: File,
        text: String,
        mimeType: String = "image/jpeg",
        source: String = "camera-photo",
    ): MessageEntity = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0L) {
            throw IOException("图片文件不存在或为空")
        }
        val cleanText = text.trim().ifBlank { "请分析这张图片。" }
        sendArtifactMessage(
            file = file,
            text = cleanText,
            artifactType = "image",
            mimeType = mimeType.ifBlank { "image/jpeg" },
            source = source,
        )
    }

    suspend fun sendAudioMessage(
        file: File,
        text: String,
        mimeType: String = "audio/mp4",
        source: String = "audio-recording",
    ): MessageEntity = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0L) {
            throw IOException("录音文件不存在或为空")
        }
        val cleanText = text.trim().ifBlank { "请转写并分析这段录音。" }
        sendArtifactMessage(
            file = file,
            text = cleanText,
            artifactType = "audio",
            mimeType = mimeType.ifBlank { "audio/mp4" },
            source = source,
        )
    }

    suspend fun sendVideoFrameMessage(
        file: File,
        text: String,
        mimeType: String = "image/jpeg",
        source: String = "visual-frame",
        metaJson: String? = null,
    ): MessageEntity = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0L) {
            throw IOException("视频帧文件不存在或为空")
        }
        val cleanText = text.trim().ifBlank { "这是手机摄像头实时帧流的一帧，请分析当前画面。" }
        sendArtifactMessage(
            file = file,
            text = cleanText,
            artifactType = "image",
            mimeType = mimeType.ifBlank { "image/jpeg" },
            source = source,
            metaJson = metaJson,
        )
    }

    suspend fun uploadImageArtifact(
        file: File,
        source: String = "visual-stream",
        metaJson: String? = null,
    ): BridgeArtifactUploadResponse = withContext(Dispatchers.IO) {
        val settings = settingsRepository.load()
        uploadArtifactOnly(
            settings = settings,
            file = file,
            artifactType = "image",
            mimeType = "image/jpeg",
            source = source,
            metaJson = metaJson,
        )
    }

    suspend fun transcribePcmAudio(pcmAudio: ByteArray): String = withContext(Dispatchers.IO) {
        val settings = settingsRepository.load()
        bridgeApi.transcribePcmAudio(settings, pcmAudio)
    }

    suspend fun retry(messageId: String): Boolean = withContext(Dispatchers.IO) {
        val message = messageDao.get(messageId) ?: return@withContext false
        val now = System.currentTimeMillis()
        val existingPending = pendingDao.get(messageId)
        val payloadJson = existingPending
            ?.payloadJson
            ?.takeIf { payload ->
                runCatching {
                    val json = JSONObject(payload)
                    json.optString("local_artifact_path").isNotBlank() ||
                        json.optString("local_image_path").isNotBlank()
                }.getOrDefault(false)
            }
            ?: buildPendingPayload(message, parseArtifactRefs(message.artifactsJson)).toString()
        pendingDao.upsert(
            PendingRequestEntity(
                id = message.messageId,
                requestType = PendingRequestType.SEND_MESSAGE,
                payloadJson = payloadJson,
                retryCount = 0,
                nextRetryAt = 0L,
                createdAt = now,
                updatedAt = now,
                lastError = null,
            )
        )
        trySendPending(messageId, scheduleOnFailure = true)
    }

    suspend fun syncDuePending(limit: Int = 20): SyncOutcome = withContext(Dispatchers.IO) {
        val due = pendingDao.due(PendingRequestType.SEND_MESSAGE, System.currentTimeMillis(), limit)
        var succeeded = 0
        var failed = 0
        for (pending in due) {
            if (trySendPending(pending.id, scheduleOnFailure = false)) {
                succeeded += 1
            } else {
                failed += 1
            }
        }
        SyncOutcome(
            attempted = due.size,
            succeeded = succeeded,
            failed = failed,
            pendingCount = pendingDao.count(),
        )
    }

    suspend fun refreshActiveMessages(): Int = withContext(Dispatchers.IO) {
        val settings = settingsRepository.load()
        val recentCreatedAfter = System.currentTimeMillis() - RECENT_DELIVERED_REFRESH_WINDOW_MS
        val active = messageDao.activeForRefresh(recentCreatedAfter)
            .filter { it.localStatus != LocalMessageStatus.PENDING.name }
        var refreshed = 0
        for (message in active) {
            runCatching {
                val payload = bridgeApi.fetchMessageStatus(settings, message.messageId)
                applyPayload(payload, localStatusOverride = null)
                refreshed += 1
            }.onFailure { error ->
                val current = messageDao.get(message.messageId) ?: return@onFailure
                messageDao.upsert(
                    current.copy(
                        updatedAt = System.currentTimeMillis(),
                        errorMessage = compactError(error),
                    )
                )
            }
        }
        refreshed
    }

    suspend fun applyEvent(event: BridgeEvent) = withContext(Dispatchers.IO) {
        if (event.messageId.isBlank()) return@withContext
        var shouldFetchDeliveredStatus = false
        var shouldDropPending = false
        messageUpdateMutex.withLock {
            val current = messageDao.get(event.messageId) ?: return@withLock
            val eventStatus = event.status?.ifBlank { null } ?: statusFromEventType(event.eventType)
            shouldDropPending = eventStatus != null
            var nextLocalReply = current.localReply
            var nextFinalReply = current.finalReply
            if (!event.text.isNullOrBlank()) {
                when {
                    event.eventType == "local_reply" || event.source == "local-operator" -> nextLocalReply = event.text
                    event.eventType == "openclaw_reply" || event.source == "openclaw" -> nextFinalReply = event.text
                }
            }
            val nextLocalStatus = when (eventStatus) {
                BridgeMessageStatus.FAILED -> LocalMessageStatus.FAILED.name
                else -> LocalMessageStatus.SENT.name
            }
            messageDao.upsert(
                current.copy(
                    localReply = nextLocalReply,
                    finalReply = nextFinalReply,
                    localStatus = nextLocalStatus,
                    bridgeStatus = eventStatus ?: current.bridgeStatus,
                    errorMessage = event.lastError ?: current.errorMessage,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            shouldFetchDeliveredStatus =
                eventStatus == BridgeMessageStatus.DELIVERED && nextFinalReply.isNullOrBlank()
        }
        if (shouldFetchDeliveredStatus) {
            refreshMessageStatus(event.messageId)
        }
        if (shouldDropPending) {
            pendingDao.delete(event.messageId)
        }
    }

    suspend fun clearLocalData() = withContext(Dispatchers.IO) {
        database.withTransaction {
            pendingDao.deleteAll()
            messageDao.deleteAll()
            artifactDao.deleteAll()
        }
        listOf("phone-agent-captures", "phone-agent-audio", "phone-agent-stream").forEach { name ->
            File(appContext.filesDir, name).deleteRecursively()
            File(appContext.cacheDir, name).deleteRecursively()
        }
    }

    suspend fun deleteLocalArtifact(localId: String): Boolean = withContext(Dispatchers.IO) {
        val artifact = artifactDao.get(localId) ?: return@withContext false
        deleteArtifactFileIfPrivate(artifact)
        artifactDao.delete(localId)
        true
    }

    suspend fun pruneArtifactsOlderThan(days: Int): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - days.coerceIn(1, 365).toLong() * 24L * 60L * 60L * 1000L
        var deleted = 0
        while (true) {
            val batch = artifactDao.olderThan(cutoff)
            if (batch.isEmpty()) break
            batch.forEach { artifact ->
                deleteArtifactFileIfPrivate(artifact)
                artifactDao.delete(artifact.localId)
                deleted += 1
            }
            if (batch.size < 500) break
        }
        deleted
    }

    suspend fun clearRemoteSessionData(): String = withContext(Dispatchers.IO) {
        val settings = settingsRepository.load()
        val json = bridgeApi.clearRemoteSession(settings)
        val deleted = json.optJSONObject("deleted")
        val messages = deleted?.optInt("messages", 0) ?: 0
        val artifacts = deleted?.optInt("artifacts", 0) ?: 0
        val files = deleted?.optInt("artifact_files", 0) ?: 0
        "消息 $messages 条，附件 $artifacts 个，文件 $files 个"
    }

    suspend fun checkHealth(settings: AppSettings): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val json = bridgeApi.health(settings)
            val role = json.optString("role").ifBlank { "voice-bridge" }
            val status = json.optString("status").ifBlank { "ok" }
            val openClawEnabled = json.optBoolean("openclaw_enabled", false)
            if (!openClawEnabled) {
                return@runCatching "$role: $status"
            }
            val openClawStatus = json.optString("openclaw_status").ifBlank {
                if (json.optBoolean("openclaw_available", false)) "ok" else "unavailable"
            }
            val openClawHealth = json.optString("openclaw_health").trim()
            if (openClawStatus != "ok") {
                val detail = openClawHealth.ifBlank { openClawStatus }
                throw IOException("龙虾大脑不可用：$detail")
            }
            "$role: $status，龙虾大脑: $openClawStatus"
        }
    }

    private suspend fun trySendPending(messageId: String, scheduleOnFailure: Boolean): Boolean {
        var pending = pendingDao.get(messageId) ?: return true
        val message = messageDao.get(messageId) ?: run {
            pendingDao.delete(messageId)
            return true
        }
        if (PendingRequestPruner.shouldDropConfirmedPending(message.localStatus, message.bridgeStatus)) {
            pendingDao.delete(messageId)
            return true
        }
        val settings = settingsRepository.load()
        val now = System.currentTimeMillis()
        messageDao.upsert(
            message.copy(
                localStatus = LocalMessageStatus.SENDING.name,
                updatedAt = now,
                errorMessage = null,
            )
        )
        return try {
            val networkDecision = PhoneAgentPolicy.networkUse(
                settings,
                AndroidPhoneAgentDeviceState.read(appContext),
            )
            if (!networkDecision.allowed) {
                throw IOException(networkDecision.reason)
            }
            val payload = JSONObject(pending.payloadJson)
            var artifacts = parseArtifactRefs(payload.optJSONArray("artifacts"))
            val localArtifactPath = payload.optString("local_artifact_path").trim()
                .ifBlank { payload.optString("local_image_path").trim() }
            val localArtifactId = payload.optString("local_artifact_id").trim()
            if (artifacts.isEmpty() && localArtifactPath.isNotBlank()) {
                val artifactFile = File(localArtifactPath)
                if (!artifactFile.exists() || artifactFile.length() <= 0L) {
                    throw IOException("本地附件文件不存在或为空：${artifactFile.name.ifBlank { localArtifactPath }}")
                }
                val artifactType = payload.optString("artifact_type").ifBlank { "file" }
                updateArtifactStatus(
                    localArtifactId,
                    ArtifactUploadStatus.UPLOADING,
                    bridgeArtifactId = null,
                    lastError = null,
                )
                val artifact = bridgeApi.uploadArtifact(
                    settings = settings,
                    file = artifactFile,
                    artifactType = artifactType,
                    mimeType = payload.optString("mime_type").ifBlank { defaultMimeType(artifactType) },
                    captureTs = payload.optString("capture_ts").ifBlank { isoUtc(artifactFile.lastModified()) },
                    metaJson = payload.optString("meta_json").ifBlank { null },
                )
                artifacts = listOf(
                    BridgeArtifactRef(
                        artifactId = artifact.artifactId,
                        type = artifact.type.ifBlank { artifactType },
                    )
                )
                payload.put("artifacts", artifactRefsToJsonArray(artifacts))
                payload.remove("local_artifact_path")
                payload.remove("local_image_path")
                payload.remove("local_artifact_id")
                pending = pending.copy(
                    payloadJson = payload.toString(),
                    updatedAt = System.currentTimeMillis(),
                    lastError = null,
                )
                pendingDao.upsert(pending)
                val current = messageDao.get(messageId) ?: message
                messageDao.upsert(
                    current.copy(
                        artifactsJson = artifactRefsToJson(artifacts),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                updateArtifactStatus(
                    localArtifactId,
                    ArtifactUploadStatus.UPLOADED,
                    bridgeArtifactId = artifact.artifactId,
                    lastError = null,
                )
            }
            val request = BridgeSubmitRequest(
                messageId = payload.optString("message_id").ifBlank { message.messageId },
                text = payload.optString("text").ifBlank { message.text },
                sessionId = payload.optString("session_id").ifBlank { message.sessionId },
                clientId = payload.optString("client_id").ifBlank { message.clientId },
                source = payload.optString("source").ifBlank { PhoneAgentDefaults.SOURCE },
                artifacts = artifacts,
            )
            val response = bridgeApi.submitMessage(settings, request)
            applyPayload(response, localStatusOverride = LocalMessageStatus.SENT.name)
            pendingDao.delete(messageId)
            true
        } catch (error: Exception) {
            val compact = compactError(error)
            runCatching {
                val payload = JSONObject(pending.payloadJson)
                updateArtifactStatus(
                    payload.optString("local_artifact_id").trim(),
                    ArtifactUploadStatus.QUEUED,
                    bridgeArtifactId = null,
                    lastError = compact,
                )
            }
            val retryCount = pending.retryCount + 1
            val failedMessage = messageDao.get(messageId) ?: message
            messageDao.upsert(
                failedMessage.copy(
                    localStatus = LocalMessageStatus.PENDING.name,
                    updatedAt = System.currentTimeMillis(),
                    errorMessage = compact,
                )
            )
            val delayMs = retryDelayMs(retryCount)
            pendingDao.upsert(
                pending.copy(
                    retryCount = retryCount,
                    nextRetryAt = System.currentTimeMillis() + delayMs,
                    updatedAt = System.currentTimeMillis(),
                    lastError = compact,
                )
            )
            if (scheduleOnFailure) {
                PhoneAgentSyncScheduler.enqueue(appContext, initialDelayMs = delayMs)
            }
            false
        }
    }

    private suspend fun applyPayload(
        payload: BridgeMessagePayload,
        localStatusOverride: String?,
    ) {
        messageUpdateMutex.withLock {
            val current = messageDao.get(payload.messageId) ?: return@withLock
            var localReply = current.localReply
            var finalReply = current.finalReply
            var error = payload.lastError ?: current.errorMessage
            for (reply in payload.replies) {
                when {
                    reply.kind == "quick_reply" || reply.source == "local-operator" -> localReply = reply.text
                    reply.kind == "final_reply" || reply.source == "openclaw" -> finalReply = reply.text
                    reply.kind == "error" -> error = reply.text
                }
            }
            val bridgeStatus = payload.status.ifBlank { current.bridgeStatus }
            val localStatus = localStatusOverride ?: when (bridgeStatus) {
                BridgeMessageStatus.FAILED -> LocalMessageStatus.FAILED.name
                else -> current.localStatus
            }
            messageDao.upsert(
                current.copy(
                    sessionId = payload.sessionId.ifBlank { current.sessionId },
                    clientId = payload.clientId.ifBlank { current.clientId },
                    localReply = localReply,
                    finalReply = finalReply,
                    localStatus = localStatus,
                    bridgeStatus = bridgeStatus,
                    errorMessage = error,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            if (PendingRequestPruner.shouldDropForBridgePayload(bridgeStatus)) {
                pendingDao.delete(payload.messageId)
            }
        }
    }

    private suspend fun refreshMessageStatus(messageId: String) {
        runCatching {
            val settings = settingsRepository.load()
            val payload = bridgeApi.fetchMessageStatus(settings, messageId)
            applyPayload(payload, localStatusOverride = null)
        }.onFailure { error ->
            messageUpdateMutex.withLock {
                val current = messageDao.get(messageId) ?: return@withLock
                messageDao.upsert(
                    current.copy(
                        updatedAt = System.currentTimeMillis(),
                        errorMessage = compactError(error),
                    )
                )
            }
        }
    }

    private fun buildPendingPayload(message: MessageEntity, artifacts: List<BridgeArtifactRef>): JSONObject {
        return JSONObject()
            .put("message_id", message.messageId)
            .put("text", message.text)
            .put("client_id", message.clientId)
            .put("session_id", message.sessionId)
            .put("source", message.source)
            .put(
                "artifacts",
                JSONArray().also { array ->
                    artifacts.forEach { artifact ->
                        array.put(
                            JSONObject()
                                .put("artifact_id", artifact.artifactId)
                                .put("type", artifact.type)
                        )
                    }
                }
            )
    }

    private suspend fun sendArtifactMessage(
        file: File,
        text: String,
        artifactType: String,
        mimeType: String,
        source: String,
        metaJson: String? = null,
    ): MessageEntity {
        val settings = settingsRepository.load()
        val now = System.currentTimeMillis()
        val message = MessageEntity(
            messageId = "msg-${UUID.randomUUID()}",
            sessionId = settings.sessionId,
            clientId = settings.clientId,
            source = PhoneAgentDefaults.SOURCE,
            text = text,
            localStatus = LocalMessageStatus.PENDING.name,
            bridgeStatus = BridgeMessageStatus.NEW,
            createdAt = now,
            updatedAt = now,
        )
        val artifact = createArtifactEntity(
            settings = settings,
            file = file,
            artifactType = artifactType,
            mimeType = mimeType,
            source = source,
            relatedMessageId = message.messageId,
            uploadStatus = ArtifactUploadStatus.QUEUED,
            metaJson = metaJson,
            now = now,
        )
        val pending = PendingRequestEntity(
            id = message.messageId,
            requestType = PendingRequestType.SEND_MESSAGE,
            payloadJson = buildArtifactPendingPayload(message, artifact, artifactType, mimeType).toString(),
            createdAt = now,
            updatedAt = now,
        )
        database.withTransaction {
            messageDao.upsert(message)
            artifactDao.upsert(artifact)
            pendingDao.upsert(pending)
        }
        trySendPending(message.messageId, scheduleOnFailure = true)
        return messageDao.get(message.messageId) ?: message
    }

    private fun buildArtifactPendingPayload(
        message: MessageEntity,
        artifact: ArtifactEntity,
        artifactType: String,
        mimeType: String,
    ): JSONObject {
        return buildPendingPayload(message, emptyList())
            .put("local_artifact_id", artifact.localId)
            .put("local_artifact_path", artifact.localPath)
            .put("artifact_type", artifactType)
            .put("mime_type", mimeType)
            .put("capture_ts", artifact.captureTs)
            .also { json ->
                if (!artifact.metaJson.isNullOrBlank()) {
                    json.put("meta_json", artifact.metaJson)
                }
            }
    }

    private suspend fun uploadArtifactOnly(
        settings: AppSettings,
        file: File,
        artifactType: String,
        mimeType: String,
        source: String,
        metaJson: String?,
    ): BridgeArtifactUploadResponse {
        if (!file.exists() || file.length() <= 0L) {
            throw IOException("附件文件不存在或为空")
        }
        val now = System.currentTimeMillis()
        var artifact = artifactDao.getByLocalPath(file.absolutePath)
        if (artifact == null) {
            artifact = createArtifactEntity(
                settings = settings,
                file = file,
                artifactType = artifactType,
                mimeType = mimeType,
                source = source,
                relatedMessageId = null,
                uploadStatus = ArtifactUploadStatus.UPLOADING,
                metaJson = metaJson,
                now = now,
            )
        } else {
            artifact = artifact.copy(
                uploadStatus = ArtifactUploadStatus.UPLOADING,
                source = source,
                metaJson = metaJson ?: artifact.metaJson,
                updatedAt = now,
                lastError = null,
            )
        }
        artifactDao.upsert(artifact)
        return try {
            val response = bridgeApi.uploadArtifact(
                settings = settings,
                file = file,
                artifactType = artifactType,
                mimeType = mimeType,
                captureTs = artifact.captureTs,
                metaJson = artifact.metaJson,
            )
            artifactDao.upsert(
                artifact.copy(
                    bridgeArtifactId = response.artifactId,
                    uploadStatus = ArtifactUploadStatus.UPLOADED,
                    sizeBytes = response.sizeBytes.takeIf { it > 0L } ?: artifact.sizeBytes,
                    updatedAt = System.currentTimeMillis(),
                    lastError = null,
                )
            )
            response
        } catch (error: Exception) {
            artifactDao.upsert(
                artifact.copy(
                    uploadStatus = ArtifactUploadStatus.FAILED,
                    updatedAt = System.currentTimeMillis(),
                    lastError = compactError(error),
                )
            )
            throw error
        }
    }

    private fun createArtifactEntity(
        settings: AppSettings,
        file: File,
        artifactType: String,
        mimeType: String,
        source: String,
        relatedMessageId: String?,
        uploadStatus: String,
        metaJson: String?,
        now: Long,
    ): ArtifactEntity {
        return ArtifactEntity(
            localId = "art-${UUID.randomUUID()}",
            sessionId = settings.sessionId,
            clientId = settings.clientId,
            artifactType = artifactType,
            mimeType = mimeType,
            filename = file.name,
            localPath = file.absolutePath,
            sizeBytes = file.length().coerceAtLeast(0L),
            captureTs = isoUtc(file.lastModified().takeIf { it > 0L } ?: now),
            uploadStatus = uploadStatus,
            relatedMessageId = relatedMessageId,
            source = source,
            metaJson = metaJson,
            createdAt = now,
            updatedAt = now,
        )
    }

    private suspend fun updateArtifactStatus(
        localId: String,
        uploadStatus: String,
        bridgeArtifactId: String?,
        lastError: String?,
    ) {
        if (localId.isBlank()) return
        val current = artifactDao.get(localId) ?: return
        artifactDao.upsert(
            current.copy(
                bridgeArtifactId = bridgeArtifactId ?: current.bridgeArtifactId,
                uploadStatus = uploadStatus,
                updatedAt = System.currentTimeMillis(),
                lastError = lastError,
            )
        )
    }

    private fun deleteArtifactFileIfPrivate(artifact: ArtifactEntity) {
        val path = artifact.localPath.trim()
        if (path.isBlank()) return
        val file = File(path)
        if (!file.exists()) return
        val allowedRoots = listOf(appContext.filesDir, appContext.cacheDir).map { it.canonicalFile }
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return
        val insidePrivateDir = allowedRoots.any { root ->
            canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)
        }
        if (insidePrivateDir) {
            file.delete()
        }
    }

    private fun parseArtifactRefs(array: JSONArray?): List<BridgeArtifactRef> {
        if (array == null) return emptyList()
        val refs = mutableListOf<BridgeArtifactRef>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val artifactId = item.optString("artifact_id").trim()
            val type = item.optString("type").trim().ifBlank { "file" }
            if (artifactId.isNotBlank()) {
                refs += BridgeArtifactRef(artifactId = artifactId, type = type)
            }
        }
        return refs
    }

    private fun parseArtifactRefs(raw: String?): List<BridgeArtifactRef> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { parseArtifactRefs(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    private fun artifactRefsToJson(artifacts: List<BridgeArtifactRef>): String? {
        if (artifacts.isEmpty()) return null
        return artifactRefsToJsonArray(artifacts).toString()
    }

    private fun artifactRefsToJsonArray(artifacts: List<BridgeArtifactRef>): JSONArray {
        return JSONArray().also { array ->
            artifacts.forEach { artifact ->
                array.put(
                    JSONObject()
                        .put("artifact_id", artifact.artifactId)
                        .put("type", artifact.type)
                )
            }
        }
    }

    private fun statusFromEventType(eventType: String): String? {
        return when (eventType.lowercase(Locale.US)) {
            "accepted" -> BridgeMessageStatus.ACCEPTED
            "local_reply" -> BridgeMessageStatus.LOCAL_REPLIED
            "forwarded" -> BridgeMessageStatus.FORWARDED
            "waiting_openclaw" -> BridgeMessageStatus.WAITING_OPENCLAW
            "retrying" -> BridgeMessageStatus.RETRYING
            "openclaw_reply" -> BridgeMessageStatus.OPENCLAW_RECEIVED
            "delivered" -> BridgeMessageStatus.DELIVERED
            "failed" -> BridgeMessageStatus.FAILED
            else -> null
        }
    }

    private fun retryDelayMs(retryCount: Int): Long {
        val shift = retryCount.coerceIn(0, 6)
        return (2_000L * (1L shl shift)).coerceAtMost(5 * 60 * 1000L)
    }

    private fun compactError(error: Throwable): String {
        val raw = when (error) {
            is IOException -> error.message
            else -> error.message ?: error.javaClass.simpleName
        }.orEmpty()
        return raw.replace(Regex("\\s+"), " ").take(180).ifBlank { "请求失败" }
    }

    private fun isoUtc(timeMs: Long): String {
        val instant = java.time.Instant.ofEpochMilli(timeMs.coerceAtLeast(0L))
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant)
    }

    private fun defaultMimeType(artifactType: String): String {
        return when (artifactType.lowercase(Locale.US)) {
            "image" -> "image/jpeg"
            "audio" -> "audio/mp4"
            else -> "application/octet-stream"
        }
    }

    companion object {
        @Volatile
        private var instance: MessageRepository? = null
        private const val RECENT_DELIVERED_REFRESH_WINDOW_MS = 10 * 60 * 1000L

        fun get(context: Context): MessageRepository {
            return instance ?: synchronized(this) {
                instance ?: MessageRepository(
                    appContext = context.applicationContext,
                    database = PhoneAgentDatabase.get(context),
                    settingsRepository = SettingsRepository.get(context),
                    bridgeApi = OkHttpPhoneAgentBridgeApi(),
                ).also { instance = it }
            }
        }
    }
}
