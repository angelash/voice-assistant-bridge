package com.audiobridge.client.conversation

import com.audiobridge.client.FriendlyErrors
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class OkHttpConversationTransport(
    private val httpClient: OkHttpClient,
) : ConversationTransport {
    override fun postJson(baseUrl: String, path: String, body: JSONObject): JSONObject {
        val reqBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(reqBody)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException(
                    FriendlyErrors.httpPayloadMessage(
                        resp.code,
                        text,
                        "发送消息失败，请稍后重试。",
                    )
                )
            }
            return try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw IOException("服务返回格式异常，请稍后重试。", e)
            }
        }
    }

    override fun getJson(baseUrl: String, path: String): JSONObject {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException(
                    FriendlyErrors.httpPayloadMessage(
                        resp.code,
                        text,
                        "读取消息状态失败，请稍后重试。",
                    )
                )
            }
            return try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw IOException("服务返回格式异常，请稍后重试。", e)
            }
        }
    }
}

object SharedConversationEngine {
    val engine: BridgeConversationEngine by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        BridgeConversationEngine(transport = OkHttpConversationTransport(client))
    }
}
