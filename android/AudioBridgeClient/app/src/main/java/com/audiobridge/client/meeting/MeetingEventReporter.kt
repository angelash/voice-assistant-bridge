package com.audiobridge.client.meeting

import android.util.Log
import com.audiobridge.client.FriendlyErrors
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class MeetingEventReporter(
    initialBaseUrl: String,
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "MeetingEventReporter"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    @Volatile
    private var baseUrl: String = initialBaseUrl

    fun setBaseUrl(baseUrl: String) {
        this.baseUrl = baseUrl
    }

    fun reportSttFinal(
        meetingId: String,
        lineIndex: Int,
        text: String,
        kind: String? = null,
        trigger: String? = null,
        wakeword: String? = null,
    ) {
        if (meetingId.isBlank() || text.isBlank()) return
        val payload = JSONObject().apply {
            put("text", text)
            put("line_index", lineIndex)
            put("text_length", text.length)
            put("finalized_at_ms", System.currentTimeMillis())
            if (!kind.isNullOrBlank()) {
                put("kind", kind)
            }
            if (!trigger.isNullOrBlank()) {
                put("trigger", trigger)
            }
            if (!wakeword.isNullOrBlank()) {
                put("wakeword", wakeword)
            }
        }
        reportEvent(meetingId = meetingId, eventType = "stt.final", payload = payload)
    }

    private fun reportEvent(
        meetingId: String,
        eventType: String,
        payload: JSONObject,
    ) {
        Thread {
            try {
                val eventObj = JSONObject().apply {
                    put("event_type", eventType)
                    put("source", "android")
                    put("ts_client", System.currentTimeMillis())
                    put("payload", payload)
                }

                val body = JSONObject().apply {
                    put("events", JSONArray().put(eventObj))
                }

                val request = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/v2/meetings/$meetingId/events:batch")
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Event reported: $eventType")
                        return@use
                    }
                    val payloadText = response.body?.string().orEmpty()
                    val friendly = FriendlyErrors.httpPayloadMessage(
                        response.code,
                        payloadText,
                        "同步会议字幕失败，请稍后重试。",
                    )
                    Log.w(TAG, "Failed to report $eventType: $friendly")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Event report exception: ${FriendlyErrors.throwableMessage(e, action = "同步会议字幕")}")
            }
        }.start()
    }
}
