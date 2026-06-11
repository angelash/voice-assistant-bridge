package com.audiobridge.client.phoneagent.data.api

import com.audiobridge.client.phoneagent.model.BridgeMessageStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class BridgeArtifactRef(
    val artifactId: String,
    val type: String,
)

data class BridgeArtifactUploadResponse(
    val ok: Boolean,
    val artifactId: String,
    val type: String,
    val mimeType: String,
    val filename: String,
    val sizeBytes: Long,
    val url: String,
)

data class BridgeSubmitRequest(
    val messageId: String,
    val text: String,
    val sessionId: String,
    val clientId: String,
    val source: String,
    val artifacts: List<BridgeArtifactRef> = emptyList(),
)

data class BridgeReplyItem(
    val source: String,
    val sourceLabel: String,
    val kind: String,
    val text: String,
)

data class BridgeMessagePayload(
    val ok: Boolean,
    val messageId: String,
    val sessionId: String,
    val clientId: String,
    val status: String,
    val localReply: String?,
    val replies: List<BridgeReplyItem>,
    val lastError: String?,
)

data class BridgeEvent(
    val eventType: String,
    val messageId: String,
    val sessionId: String?,
    val clientId: String?,
    val status: String?,
    val source: String?,
    val text: String?,
    val lastError: String?,
)

object BridgeApiParser {
    fun requestToJson(request: BridgeSubmitRequest): JSONObject {
        return JSONObject()
            .put("text", request.text)
            .put("client_id", request.clientId)
            .put("session_id", request.sessionId)
            .put("source", request.source)
            .put("message_id", request.messageId)
            .put(
                "artifacts",
                JSONArray().also { array ->
                    request.artifacts.forEach { artifact ->
                        array.put(
                            JSONObject()
                                .put("artifact_id", artifact.artifactId)
                                .put("type", artifact.type)
                        )
                    }
                }
            )
    }

    fun parseSubmit(json: JSONObject): BridgeMessagePayload {
        val localReply = json.cleanString("local_reply")
        val replies = if (localReply == null) {
            emptyList()
        } else {
            listOf(
                BridgeReplyItem(
                    source = json.optString("local_source").ifBlank { "local-operator" },
                    sourceLabel = json.optString("local_source_label").ifBlank { "本地接线员" },
                    kind = "quick_reply",
                    text = localReply,
                )
            )
        }
        return BridgeMessagePayload(
            ok = json.optBoolean("ok", false),
            messageId = json.optString("message_id").trim(),
            sessionId = json.optString("session_id").trim(),
            clientId = json.optString("client_id").trim(),
            status = normalizeStatus(json.optString("status")),
            localReply = localReply,
            replies = replies,
            lastError = json.cleanString("last_error"),
        )
    }

    fun parseStatus(json: JSONObject): BridgeMessagePayload {
        val replies = mutableListOf<BridgeReplyItem>()
        val messages = json.optJSONArray("messages")
        if (messages != null) {
            for (index in 0 until messages.length()) {
                val item = messages.optJSONObject(index) ?: continue
                val text = item.optString("text").trim()
                if (text.isBlank()) continue
                replies += BridgeReplyItem(
                    source = item.optString("source").trim(),
                    sourceLabel = item.optString("source_label").trim(),
                    kind = item.optString("kind").trim(),
                    text = text,
                )
            }
        }
        val localReply = replies.firstOrNull { it.kind == "quick_reply" || it.source == "local-operator" }?.text
        return BridgeMessagePayload(
            ok = json.optBoolean("ok", false),
            messageId = json.optString("message_id").trim(),
            sessionId = json.optString("session_id").trim(),
            clientId = json.optString("client_id").trim(),
            status = normalizeStatus(json.optString("status")),
            localReply = localReply,
            replies = replies,
            lastError = json.cleanString("last_error"),
        )
    }

    fun parseEvent(json: JSONObject): BridgeEvent {
        return BridgeEvent(
            eventType = json.optString("event_type").trim(),
            messageId = json.optString("message_id").trim(),
            sessionId = json.cleanString("session_id"),
            clientId = json.cleanString("client_id"),
            status = normalizeStatus(json.optString("status")).ifBlank { null },
            source = json.cleanString("source"),
            text = json.cleanString("text"),
            lastError = json.cleanString("last_error"),
        )
    }

    fun normalizeStatus(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        return when (value.lowercase(Locale.US)) {
            "accepted" -> BridgeMessageStatus.ACCEPTED
            "local_reply", "local_replied" -> BridgeMessageStatus.LOCAL_REPLIED
            "forwarded" -> BridgeMessageStatus.FORWARDED
            "waiting_openclaw" -> BridgeMessageStatus.WAITING_OPENCLAW
            "retrying" -> BridgeMessageStatus.RETRYING
            "openclaw_reply", "openclaw_received" -> BridgeMessageStatus.OPENCLAW_RECEIVED
            "delivered" -> BridgeMessageStatus.DELIVERED
            "failed" -> BridgeMessageStatus.FAILED
            "new" -> BridgeMessageStatus.NEW
            else -> value.uppercase(Locale.US)
        }
    }

    private fun JSONObject.cleanString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).trim().ifBlank { null }
    }
}
