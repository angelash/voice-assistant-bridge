package com.audiobridge.client.abp

import org.json.JSONArray
import org.json.JSONObject

sealed interface AbpControlMessage {
    val type: String
    fun toJson(): String
}

data class HelloMessage(
    val proto: String = AbpConstants.PROTO,
    val deviceId: String,
    val token: String? = null,
    val cap: HelloCapabilities,
) : AbpControlMessage {
    override val type: String = "hello"
    override fun toJson(): String {
        val codecArr = JSONArray().apply { cap.codec.forEach { put(it) } }
        val sampleRateArr = JSONArray().apply { cap.sampleRate.forEach { put(it) } }
        val frameMsArr = JSONArray().apply { cap.frameMs.forEach { put(it) } }
        
        val capObj = JSONObject()
            .put("codec", codecArr)
            .put("sampleRate", sampleRateArr)
            .put("frameMs", frameMsArr)
            .put("uplink", cap.uplink)
            .put("downlink", cap.downlink)

        return JSONObject()
            .put("type", type)
            .put("proto", proto)
            .put("deviceId", deviceId)
            .put("token", token)
            .put("cap", capObj)
            .toString()
    }
}

data class HelloCapabilities(
    val codec: Array<String>,
    val sampleRate: IntArray,
    val frameMs: IntArray,
    val uplink: Boolean,
    val downlink: Boolean,
)

data class WelcomeMessage(
    val sessionId: String,
    val selected: SelectedConfig,
    val server: ServerConfig,
) : AbpControlMessage {
    override val type: String = "welcome"
    override fun toJson(): String {
        val selectedObj = JSONObject()
            .put("codec", selected.codec)
            .put("sampleRate", selected.sampleRate)
            .put("channels", selected.channels)
            .put("frameMs", selected.frameMs)
        val serverObj = JSONObject().put("heartbeatMs", server.heartbeatMs)
        return JSONObject()
            .put("type", type)
            .put("sessionId", sessionId)
            .put("selected", selectedObj)
            .put("server", serverObj)
            .toString()
    }
}

data class SelectedConfig(
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val frameMs: Int,
)

data class ServerConfig(
    val heartbeatMs: Int,
)

data class PingMessage(val t: Long) : AbpControlMessage {
    override val type: String = "ping"
    override fun toJson(): String = JSONObject().put("type", type).put("t", t).toString()
}

data class PongMessage(val t: Long) : AbpControlMessage {
    override val type: String = "pong"
    override fun toJson(): String = JSONObject().put("type", type).put("t", t).toString()
}

data class ErrorMessage(val code: String, val message: String) : AbpControlMessage {
    override val type: String = "error"
    override fun toJson(): String =
        JSONObject().put("type", type).put("code", code).put("message", message).toString()
}

data class PttMessage(val enabled: Boolean) : AbpControlMessage {
    override val type: String = "ptt"
    override fun toJson(): String = JSONObject().put("type", type).put("enabled", enabled).toString()
}

data class MuteUplinkMessage(val enabled: Boolean) : AbpControlMessage {
    override val type: String = "muteUplink"
    override fun toJson(): String = JSONObject().put("type", type).put("enabled", enabled).toString()
}

data class MuteDownlinkMessage(val enabled: Boolean) : AbpControlMessage {
    override val type: String = "muteDownlink"
    override fun toJson(): String = JSONObject().put("type", type).put("enabled", enabled).toString()
}

/**
 * 配置同步消息（服务端 -> 客户端）
 * 用于动态调整 Android 端的音频参数
 */
data class ConfigMessage(
    val uplinkThreshold: Int? = null,
    val uplinkMinSilentFrames: Int? = null,
    val downlinkThreshold: Int? = null,
    val downlinkMinSilentFrames: Int? = null,
) : AbpControlMessage {
    override val type: String = "config"
    override fun toJson(): String = JSONObject().apply {
        put("type", type)
        uplinkThreshold?.let { put("uplinkThreshold", it) }
        uplinkMinSilentFrames?.let { put("uplinkMinSilentFrames", it) }
        downlinkThreshold?.let { put("downlinkThreshold", it) }
        downlinkMinSilentFrames?.let { put("downlinkMinSilentFrames", it) }
    }.toString()
}

object AbpControlJson {
    fun parse(json: String): AbpControlMessage {
        val obj = JSONObject(json)
        val type = obj.optString("type", "")
        if (type.isBlank()) {
            throw IllegalArgumentException("missing field: type")
        }

        return when (type) {
            "hello" -> {
                val capObj = obj.getJSONObject("cap")
                HelloMessage(
                    proto = obj.getString("proto"),
                    deviceId = obj.getString("deviceId"),
                    token = obj.optString("token", null),
                    cap = HelloCapabilities(
                        codec = capObj.getJSONArray("codec").let { arr ->
                            Array(arr.length()) { i -> arr.getString(i) }
                        },
                        sampleRate = capObj.getJSONArray("sampleRate").let { arr ->
                            IntArray(arr.length()) { i -> arr.getInt(i) }
                        },
                        frameMs = capObj.getJSONArray("frameMs").let { arr ->
                            IntArray(arr.length()) { i -> arr.getInt(i) }
                        },
                        uplink = capObj.getBoolean("uplink"),
                        downlink = capObj.getBoolean("downlink"),
                    ),
                )
            }

            "welcome" -> {
                val selectedObj = obj.getJSONObject("selected")
                val serverObj = obj.getJSONObject("server")
                WelcomeMessage(
                    sessionId = obj.getString("sessionId"),
                    selected = SelectedConfig(
                        codec = selectedObj.getString("codec"),
                        sampleRate = selectedObj.getInt("sampleRate"),
                        channels = selectedObj.getInt("channels"),
                        frameMs = selectedObj.getInt("frameMs"),
                    ),
                    server = ServerConfig(heartbeatMs = serverObj.getInt("heartbeatMs")),
                )
            }

            "ping" -> PingMessage(t = obj.getLong("t"))
            "pong" -> PongMessage(t = obj.getLong("t"))
            "error" -> ErrorMessage(code = obj.getString("code"), message = obj.getString("message"))
            "ptt" -> PttMessage(enabled = obj.getBoolean("enabled"))
            "muteUplink" -> MuteUplinkMessage(enabled = obj.getBoolean("enabled"))
            "muteDownlink" -> MuteDownlinkMessage(enabled = obj.getBoolean("enabled"))
            "config" -> ConfigMessage(
                uplinkThreshold = if (obj.has("uplinkThreshold")) obj.getInt("uplinkThreshold") else null,
                uplinkMinSilentFrames = if (obj.has("uplinkMinSilentFrames")) obj.getInt("uplinkMinSilentFrames") else null,
                downlinkThreshold = if (obj.has("downlinkThreshold")) obj.getInt("downlinkThreshold") else null,
                downlinkMinSilentFrames = if (obj.has("downlinkMinSilentFrames")) obj.getInt("downlinkMinSilentFrames") else null,
            )
            else -> throw IllegalArgumentException("unknown message type: $type")
        }
    }
}

