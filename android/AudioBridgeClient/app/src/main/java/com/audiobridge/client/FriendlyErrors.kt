package com.audiobridge.client

import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object FriendlyErrors {
    private val backendMessages = mapOf(
        "invalid_json" to "请求格式不正确，请稍后重试。",
        "invalid_payload" to "提交内容不完整，请检查后重试。",
        "text_required" to "请输入内容后再发送。",
        "text is required" to "请输入内容后再发送。",
        "message_not_found" to "消息不存在，可能已过期，请重新发送。",
        "active_meeting_exists" to "已有会议正在进行中，请先结束当前会议。",
        "meeting_not_found" to "会议不存在或已结束，请刷新后重试。",
        "meeting_not_ended" to "会议尚未结束，暂时不能执行这个操作。",
        "segment_not_found" to "录音分段不存在，请重新上传。",
        "segment_meeting_mismatch" to "录音分段与当前会议不匹配，请重新上传。",
        "checksum_mismatch" to "文件校验失败，请重新上传。",
        "audio data required" to "没有检测到录音数据，请重新录音后重试。",
        "empty_audio" to "没有录到有效声音，请重新录音。",
        "stt_failed" to "语音识别失败，请重新录音后重试。",
        "job_in_progress" to "已有任务正在处理中，请稍后刷新查看。",
        "job_not_found" to "任务不存在，可能已完成或已被清理。",
        "speaker_name_required" to "请输入说话人名称后再保存。",
        "no_fields_to_update" to "没有可保存的内容。",
        "file_not_found" to "文件不存在，可能尚未生成完成。",
        "image data required" to "没有收到图片数据，请重新上传。",
        "image_not_found" to "图片不存在，可能尚未上传成功。",
        "openclaw_failed" to "龙虾大脑暂时不可用，请稍后重试。",
    )

    private val httpMessages = mapOf(
        400 to "请求参数不正确，请检查后重试。",
        401 to "服务拒绝访问，请检查登录状态或令牌。",
        403 to "当前操作没有权限。",
        404 to "请求的资源不存在，请刷新后重试。",
        408 to "请求超时，请稍后重试。",
        409 to "当前状态冲突，请刷新后再试。",
        413 to "提交内容过大，请压缩后重试。",
        429 to "请求过于频繁，请稍后重试。",
        500 to "服务端处理失败，请稍后重试。",
        502 to "网关暂时不可用，请稍后重试。",
        503 to "服务暂时不可用，请稍后重试。",
        504 to "服务响应超时，请稍后重试。",
    )

    fun backendMessage(code: String?, default: String = "请求失败，请稍后重试。"): String {
        val normalized = code.orEmpty().trim().lowercase()
        if (normalized.isBlank()) return default
        return backendMessages[normalized] ?: default
    }

    fun httpStatusMessage(status: Int, default: String = "服务处理失败，请稍后重试。"): String {
        return httpMessages[status] ?: default
    }

    fun throwableMessage(
        throwable: Throwable?,
        action: String = "请求服务",
        default: String = "${action}失败，请稍后重试。",
    ): String {
        val err = throwable ?: return default
        return when (err) {
            is SocketTimeoutException -> "${action}超时，请检查网络或稍后重试。"
            is ConnectException -> "${action}失败，当前无法连接到服务，请确认服务已启动。"
            is UnknownHostException -> "无法解析服务地址，请检查网络或服务配置。"
            else -> {
                val lowered = err.message.orEmpty().lowercase()
                when {
                    "timeout" in lowered || "timed out" in lowered ->
                        "${action}超时，请检查网络或稍后重试。"
                    "unable to resolve host" in lowered || "no address associated with hostname" in lowered ->
                        "无法解析服务地址，请检查网络或服务配置。"
                    "failed to connect" in lowered || "connection refused" in lowered || "connect failed" in lowered ->
                        "${action}失败，当前无法连接到服务，请确认服务已启动。"
                    "service prepare failed" in lowered ->
                        "本地服务尚未就绪，请先启动 bridge 服务后重试。"
                    else -> default
                }
            }
        }
    }

    fun jsonMessage(json: JSONObject?, default: String = "请求失败，请稍后重试。"): String {
        val payload = json ?: return default
        val explicitMessage = payload.optString("message").trim()
        if (explicitMessage.isNotBlank()) return explicitMessage

        val error = payload.optString("error").trim()
        if (error.isNotBlank()) {
            if (error.startsWith("http_")) {
                error.removePrefix("http_").toIntOrNull()?.let { return httpStatusMessage(it, default) }
            }
            Regex("""HTTP\s+(\d{3})""", RegexOption.IGNORE_CASE)
                .find(error)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let { return httpStatusMessage(it, default) }

            parseJsonOrNull(payload.optString("detail"))?.let { detailJson ->
                val detailMessage = detailJson.optString("message").trim()
                if (detailMessage.isNotBlank()) return detailMessage
                val detailError = detailJson.optString("error").trim()
                if (detailError.isNotBlank()) return backendMessage(detailError, default)
            }

            return backendMessage(error, default)
        }

        val status = payload.optInt("status", 0)
        if (status > 0) {
            return httpStatusMessage(status, default)
        }
        return default
    }

    fun httpPayloadMessage(
        status: Int,
        payload: String?,
        default: String = httpStatusMessage(status),
    ): String {
        val text = payload.orEmpty().trim()
        if (text.isBlank()) return httpStatusMessage(status, default)
        val json = parseJsonOrNull(text)
        return if (json != null) {
            jsonMessage(json, httpStatusMessage(status, default))
        } else {
            httpStatusMessage(status, default)
        }
    }

    private fun parseJsonOrNull(text: String?): JSONObject? {
        val value = text.orEmpty().trim()
        if (value.isBlank()) return null
        return try {
            JSONObject(value)
        } catch (_: Exception) {
            null
        }
    }
}
