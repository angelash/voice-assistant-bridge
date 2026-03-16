package com.audiobridge.client.conversation

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: $text")
            return JSONObject(text)
        }
    }

    override fun getJson(baseUrl: String, path: String): JSONObject {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: $text")
            return JSONObject(text)
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
