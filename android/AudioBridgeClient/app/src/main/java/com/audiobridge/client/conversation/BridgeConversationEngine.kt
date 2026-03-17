package com.audiobridge.client.conversation

import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

enum class ConversationState {
    IDLE,
    SENDING,
    WAITING_OPENCLAW,
    RETRYING,
    DELIVERED,
    FAILED,
}

enum class RoleSource {
    LOCAL_OPERATOR,
    OPENCLAW,
}

data class BridgeEndpointInfo(
    val mode: String,
    val baseUrl: String,
    val wifiSsid: String?,
)

data class ConversationSubmitRequest(
    val text: String,
    val sessionId: String,
    val clientId: String,
    val endpoint: BridgeEndpointInfo,
    val source: String = "android",
    val timeoutSec: Int = 180,
    val intervalMs: Long = 1000L,
)

data class RoleMessage(
    val source: RoleSource,
    val sourceLabel: String,
    val textRaw: String,
    val textDisplay: String,
    val requiresLongDecision: Boolean,
)

data class SystemNotice(
    val text: String,
    val isError: Boolean = false,
)

data class LongReplyDecisionRequired(
    val id: String,
    val endpoint: BridgeEndpointInfo,
    val sessionId: String,
    val clientId: String,
    val originalRaw: String,
    val originalDisplay: String,
    val deadlineAtMs: Long,
)

interface ConversationTransport {
    fun postJson(baseUrl: String, path: String, body: JSONObject): JSONObject
    fun getJson(baseUrl: String, path: String): JSONObject
}

class BridgeConversationEngine(
    private val transport: ConversationTransport,
    private val longReplyLimit: Int = 30,
    private val longReplyDecisionTimeoutMs: Long = 30_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private companion object {
        private val ENGLISH_WORD_REGEX = Regex("""\b[A-Za-z]+(?:['’-][A-Za-z]+)*\b""")
        private val CJK_CHAR_REGEX = Regex("""[\p{IsHan}]""")
        private val OTHER_TOKEN_REGEX = Regex("""[\p{L}\p{N}]+""")
    }

    interface Listener {
        fun onStateChanged(state: ConversationState) {}
        fun onRoleMessage(message: RoleMessage) {}
        fun onSystemNotice(notice: SystemNotice) {}
        fun onLongReplyDecisionRequired(request: LongReplyDecisionRequired) {}
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun submitText(request: ConversationSubmitRequest) {
        val cleanText = request.text.trim()
        if (cleanText.isBlank()) return
        val submitRequest = request.copy(text = cleanText)
        Thread {
            runCatching { submitTextSync(submitRequest) }.onFailure { err ->
                emitState(ConversationState.FAILED)
                emitSystemNotice("send failed: ${err.message ?: "unknown"}", isError = true)
            }
        }.start()
    }

    fun submitTextSync(request: ConversationSubmitRequest) {
        var state: ConversationState = ConversationState.IDLE
        fun setState(next: ConversationState) {
            if (next == state) return
            state = next
            emitState(next)
        }

        setState(ConversationState.SENDING)

        val submitBody = JSONObject()
            .put("text", request.text)
            .put("session_id", request.sessionId)
            .put("client_id", request.clientId)
            .put("source", request.source)

        val submitResp = transport.postJson(request.endpoint.baseUrl, "/v1/messages", submitBody)
        val shown = linkedSetOf<String>()

        val localReplyRaw = submitResp.optString("local_reply").trim()
        val localReply = normalizeForDisplay(localReplyRaw)
        if (localReply.isNotBlank()) {
            val label = submitResp.optString("local_source_label").ifBlank { "本地接线员" }
            emitRoleMessage(
                RoleMessage(
                    source = RoleSource.LOCAL_OPERATOR,
                    sourceLabel = label,
                    textRaw = localReplyRaw,
                    textDisplay = localReply,
                    requiresLongDecision = false,
                )
            )
            shown.add("$label::$localReply")
        }

        val messageId = submitResp.optString("message_id").trim()
        val submitStatus = submitResp.optString("status").uppercase(Locale.getDefault())
        val submitMapped = mapStatus(submitStatus)
        if (submitMapped != null) setState(submitMapped)

        val terminalPayload = if (messageId.isNotBlank() && submitStatus !in setOf("DELIVERED", "FAILED")) {
            pollTerminal(
                endpoint = request.endpoint,
                messageId = messageId,
                timeoutSec = request.timeoutSec,
                intervalMs = request.intervalMs,
                onIntermediateState = { interim ->
                    val mapped = mapStatus(interim)
                    if (mapped != null) setState(mapped)
                },
            )
        } else {
            submitResp
        }

        if (terminalPayload == null) {
            setState(ConversationState.FAILED)
            emitSystemNotice("timeout waiting final reply", isError = true)
            return
        }

        processPayloadMessages(terminalPayload, shown, request)
        val finalMapped = mapStatus(terminalPayload.optString("status").uppercase(Locale.getDefault()))
        setState(finalMapped ?: ConversationState.DELIVERED)
    }

    fun requestLocalSummary(request: LongReplyDecisionRequired, maxChars: Int = 90): String {
        val body = JSONObject()
            .put("text", request.originalRaw)
            .put("session_id", request.sessionId)
            .put("client_id", request.clientId)
            .put("source", "android")
            .put("max_chars", maxChars)
        val resp = transport.postJson(request.endpoint.baseUrl, "/v1/operator/summarize", body)
        if (!resp.optBoolean("ok", false)) {
            throw IllegalStateException(resp.optString("error").ifBlank { "summary_failed" })
        }
        return resp.optString("summary").trim()
    }

    private fun pollTerminal(
        endpoint: BridgeEndpointInfo,
        messageId: String,
        timeoutSec: Int,
        intervalMs: Long,
        onIntermediateState: (String) -> Unit,
    ): JSONObject? {
        val started = nowMs()
        while (nowMs() - started < timeoutSec * 1000L) {
            val status = transport.getJson(endpoint.baseUrl, "/v1/messages/$messageId")
            val state = status.optString("status").uppercase(Locale.getDefault())
            onIntermediateState(state)
            if (state == "DELIVERED" || state == "FAILED") {
                return status
            }
            Thread.sleep(intervalMs)
        }
        return null
    }

    private fun processPayloadMessages(
        payload: JSONObject,
        shown: MutableSet<String>,
        request: ConversationSubmitRequest,
    ) {
        val messages = payload.optJSONArray("messages")
        if (messages != null) {
            for (i in 0 until messages.length()) {
                val item = messages.optJSONObject(i) ?: continue
                val textRaw = item.optString("text").trim()
                val text = normalizeForDisplay(textRaw)
                if (text.isBlank()) continue
                val label = item.optString("source_label").ifBlank { "Assistant" }
                val source = item.optString("source").trim()
                val kind = item.optString("kind")
                val key = "$label::$text"
                if (shown.contains(key)) continue
                shown.add(key)

                if (kind == "error") {
                    emitSystemNotice(text, isError = true)
                    continue
                }

                if (source == "local-operator") {
                    emitRoleMessage(
                        RoleMessage(
                            source = RoleSource.LOCAL_OPERATOR,
                            sourceLabel = label,
                            textRaw = textRaw,
                            textDisplay = text,
                            requiresLongDecision = false,
                        )
                    )
                    continue
                }

                if (source == "openclaw") {
                    val longReply = isLongOpenClawReply(textRaw)
                    emitRoleMessage(
                        RoleMessage(
                            source = RoleSource.OPENCLAW,
                            sourceLabel = label,
                            textRaw = textRaw,
                            textDisplay = text,
                            requiresLongDecision = longReply,
                        )
                    )
                    if (longReply) {
                        emitLongReplyDecisionRequired(
                            LongReplyDecisionRequired(
                                id = UUID.randomUUID().toString(),
                                endpoint = request.endpoint,
                                sessionId = request.sessionId,
                                clientId = request.clientId,
                                originalRaw = textRaw,
                                originalDisplay = text,
                                deadlineAtMs = nowMs() + longReplyDecisionTimeoutMs,
                            )
                        )
                    }
                }
            }
        }

        val state = payload.optString("status").uppercase(Locale.getDefault())
        if (state == "FAILED") {
            val err = payload.optString("last_error").ifBlank { "openclaw_failed" }
            emitSystemNotice("openclaw failed: $err", isError = true)
        }
    }

    private fun mapStatus(status: String): ConversationState? {
        return when (status) {
            "SENDING", "NEW", "LOCAL_REPLIED", "FORWARDED" -> ConversationState.SENDING
            "WAITING_OPENCLAW" -> ConversationState.WAITING_OPENCLAW
            "RETRYING" -> ConversationState.RETRYING
            "DELIVERED" -> ConversationState.DELIVERED
            "FAILED" -> ConversationState.FAILED
            else -> null
        }
    }

    private fun normalizeForDisplay(text: String): String {
        return text
            .trim()
            .replace(Regex("""^\s*\[\[[^\]]+\]\]\s*"""), "")
            .trim()
    }

    private fun longReplyUnits(text: String): Int {
        val normalized = normalizeForDisplay(text)
        if (normalized.isBlank()) return 0

        val englishWords = ENGLISH_WORD_REGEX.findAll(normalized).count()
        val cjkChars = CJK_CHAR_REGEX.findAll(normalized).count()
        val stripped = CJK_CHAR_REGEX.replace(ENGLISH_WORD_REGEX.replace(normalized, " "), " ")
        val otherTokens = OTHER_TOKEN_REGEX.findAll(stripped).count()
        return englishWords + cjkChars + otherTokens
    }

    private fun isLongOpenClawReply(textRaw: String): Boolean {
        return longReplyUnits(textRaw) > longReplyLimit
    }

    private fun emitState(state: ConversationState) {
        listeners.forEach { listener ->
            runCatching { listener.onStateChanged(state) }
        }
    }

    private fun emitRoleMessage(message: RoleMessage) {
        listeners.forEach { listener ->
            runCatching { listener.onRoleMessage(message) }
        }
    }

    private fun emitSystemNotice(text: String, isError: Boolean) {
        val notice = SystemNotice(text = text, isError = isError)
        listeners.forEach { listener ->
            runCatching { listener.onSystemNotice(notice) }
        }
    }

    private fun emitLongReplyDecisionRequired(request: LongReplyDecisionRequired) {
        listeners.forEach { listener ->
            runCatching { listener.onLongReplyDecisionRequired(request) }
        }
    }
}
