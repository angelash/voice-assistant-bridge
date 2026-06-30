package com.audiobridge.client.phoneagent.data.api

import com.audiobridge.client.phoneagent.model.AppSettings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

interface PhoneAgentBridgeApi {
    fun health(settings: AppSettings): JSONObject
    fun transcribePcmAudio(settings: AppSettings, pcmAudio: ByteArray): String
    fun uploadArtifact(
        settings: AppSettings,
        file: File,
        artifactType: String,
        mimeType: String,
        captureTs: String?,
        metaJson: String? = null,
    ): BridgeArtifactUploadResponse
    fun submitMessage(settings: AppSettings, request: BridgeSubmitRequest): BridgeMessagePayload
    fun fetchMessageStatus(settings: AppSettings, messageId: String): BridgeMessagePayload
    fun clearRemoteSession(settings: AppSettings): JSONObject
    fun openEventStream(
        settings: AppSettings,
        onConnected: () -> Unit,
        onEvent: (BridgeEvent) -> Unit,
        onFailure: (String) -> Unit,
        onClosed: () -> Unit,
    ): WebSocket
}

class OkHttpPhoneAgentBridgeApi(
    private val httpClient: OkHttpClient = defaultHttpClient(),
) : PhoneAgentBridgeApi {
    override fun health(settings: AppSettings): JSONObject {
        val request = Request.Builder()
            .url("${settings.bridgeBaseUrl.trimEnd('/')}/health")
            .get()
            .withAuth(settings.apiToken)
            .build()
        return executeJson(request, "Bridge 健康检查失败")
    }

    override fun transcribePcmAudio(settings: AppSettings, pcmAudio: ByteArray): String {
        if (pcmAudio.isEmpty()) {
            throw IOException("音频分片为空")
        }
        val body = pcmAudio.toRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder()
            .url("${settings.bridgeBaseUrl.trimEnd('/')}/v1/audio/transcriptions")
            .post(body)
            .withAuth(settings.apiToken)
            .build()
        val json = executeJson(request, "语音转写失败", readTimeoutSec = 180)
        if (!json.optBoolean("ok", false)) {
            throw IOException(json.optString("error").ifBlank { "Bridge 未返回语音转写结果" })
        }
        return json.optString("text").trim()
    }

    override fun uploadArtifact(
        settings: AppSettings,
        file: File,
        artifactType: String,
        mimeType: String,
        captureTs: String?,
        metaJson: String?,
    ): BridgeArtifactUploadResponse {
        if (!file.exists() || file.length() <= 0L) {
            throw IOException("文件不存在或为空")
        }
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("artifact_type", artifactType)
            .addFormDataPart("client_id", settings.clientId)
            .addFormDataPart("session_id", settings.sessionId)
            .addFormDataPart("source", "android")
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody(mimeType.toMediaType())
            )
        if (!captureTs.isNullOrBlank()) {
            bodyBuilder.addFormDataPart("capture_ts", captureTs)
        }
        if (!metaJson.isNullOrBlank()) {
            bodyBuilder.addFormDataPart("meta_json", metaJson)
        }
        val httpRequest = Request.Builder()
            .url("${settings.bridgeBaseUrl.trimEnd('/')}/v1/artifacts")
            .post(bodyBuilder.build())
            .withAuth(settings.apiToken)
            .build()
        val json = executeJson(httpRequest, "上传附件失败")
        if (!json.optBoolean("ok", false)) {
            throw IOException(json.optString("error").ifBlank { "Bridge 未接受附件" })
        }
        val artifactId = json.optString("artifact_id").trim()
        if (artifactId.isBlank()) {
            throw IOException("上传附件失败：Bridge 未返回 artifact_id")
        }
        return BridgeArtifactUploadResponse(
            ok = true,
            artifactId = artifactId,
            type = json.optString("type").trim(),
            mimeType = json.optString("mime_type").trim(),
            filename = json.optString("filename").trim(),
            sizeBytes = json.optLong("size_bytes"),
            url = json.optString("url").trim(),
        )
    }

    override fun submitMessage(settings: AppSettings, request: BridgeSubmitRequest): BridgeMessagePayload {
        val body = BridgeApiParser.requestToJson(request)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val httpRequest = Request.Builder()
            .url("${settings.bridgeBaseUrl.trimEnd('/')}/v1/messages")
            .post(body)
            .withAuth(settings.apiToken)
            .build()
        val json = executeJson(httpRequest, "发送消息失败")
        val parsed = BridgeApiParser.parseSubmit(json)
        if (!parsed.ok) {
            throw IOException(json.optString("error").ifBlank { "Bridge 未接受消息" })
        }
        return parsed
    }

    override fun fetchMessageStatus(settings: AppSettings, messageId: String): BridgeMessagePayload {
        val httpRequest = Request.Builder()
            .url("${settings.bridgeBaseUrl.trimEnd('/')}/v1/messages/$messageId")
            .get()
            .withAuth(settings.apiToken)
            .build()
        val json = executeJson(httpRequest, "读取消息状态失败")
        val parsed = BridgeApiParser.parseStatus(json)
        if (!parsed.ok) {
            throw IOException(json.optString("error").ifBlank { "Bridge 状态响应异常" })
        }
        return parsed
    }

    override fun clearRemoteSession(settings: AppSettings): JSONObject {
        val base = settings.bridgeBaseUrl.toHttpUrlOrNull()
            ?: throw IOException("Bridge 地址格式不正确")
        val url = base.newBuilder()
            .encodedPath("/v1/sessions")
            .addPathSegment(settings.sessionId)
            .addQueryParameter("client_id", settings.clientId)
            .build()
        val request = Request.Builder()
            .url(url)
            .delete()
            .withAuth(settings.apiToken)
            .build()
        val json = executeJson(request, "清空远端会话失败")
        if (!json.optBoolean("ok", false)) {
            throw IOException(json.optString("error").ifBlank { "Bridge 未确认清空远端会话" })
        }
        return json
    }

    override fun openEventStream(
        settings: AppSettings,
        onConnected: () -> Unit,
        onEvent: (BridgeEvent) -> Unit,
        onFailure: (String) -> Unit,
        onClosed: () -> Unit,
    ): WebSocket {
        val base = settings.bridgeBaseUrl.toHttpUrlOrNull()
            ?: throw IOException("Bridge 地址格式不正确")
        val httpEventsUrl = base.newBuilder()
            .encodedPath("/v1/events")
            .query(null)
            .addQueryParameter("session_id", settings.sessionId)
            .addQueryParameter("client_id", settings.clientId)
            .build()
        val url = httpEventsUrl.toString()
            .replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://")
        val request = Request.Builder()
            .url(url)
            .withAuth(settings.apiToken)
            .build()
        return httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    onConnected()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val event = BridgeApiParser.parseEvent(JSONObject(text))
                        if (event.eventType == "connected") {
                            onConnected()
                        } else if (event.messageId.isNotBlank()) {
                            onEvent(event)
                        }
                    } catch (e: Exception) {
                        onFailure("事件解析失败: ${e.message ?: e.javaClass.simpleName}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code?.let { "HTTP $it, " }.orEmpty()
                    onFailure(code + (t.message ?: t.javaClass.simpleName))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onClosed()
                }
            }
        )
    }

    private fun executeJson(request: Request, action: String, readTimeoutSec: Long? = null): JSONObject {
        val client = if (readTimeoutSec == null) {
            httpClient
        } else {
            httpClient.newBuilder().readTimeout(readTimeoutSec, TimeUnit.SECONDS).build()
        }
        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("$action: HTTP ${response.code} ${compactError(bodyText)}".trim())
            }
            return try {
                JSONObject(bodyText.ifBlank { "{}" })
            } catch (e: JSONException) {
                throw IOException("$action: 服务返回格式异常", e)
            }
        }
    }

    private fun Request.Builder.withAuth(token: String): Request.Builder {
        val clean = token.trim()
        if (clean.isNotBlank()) {
            header("Authorization", "Bearer $clean")
        }
        return this
    }

    private fun compactError(payload: String): String {
        if (payload.isBlank()) return ""
        return try {
            val json = JSONObject(payload)
            json.optString("error").ifBlank { payload.take(160) }
        } catch (_: Exception) {
            payload.replace(Regex("\\s+"), " ").take(160)
        }
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
