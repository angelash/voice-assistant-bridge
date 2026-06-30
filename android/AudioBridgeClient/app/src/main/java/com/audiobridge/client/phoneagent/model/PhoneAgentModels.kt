package com.audiobridge.client.phoneagent.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PhoneAgentDefaults {
    const val DEFAULT_BRIDGE_BASE_URL = "http://127.0.0.1:8765"
    const val DEFAULT_CLIENT_ID = "android-phone"
    const val SOURCE = "android"

    fun defaultSessionId(nowMs: Long = System.currentTimeMillis()): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(nowMs))
        return "daily-agent-$day"
    }
}

enum class LocalMessageStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
}

object BridgeMessageStatus {
    const val NEW = "NEW"
    const val ACCEPTED = "ACCEPTED"
    const val LOCAL_REPLIED = "LOCAL_REPLIED"
    const val FORWARDED = "FORWARDED"
    const val WAITING_OPENCLAW = "WAITING_OPENCLAW"
    const val RETRYING = "RETRYING"
    const val OPENCLAW_RECEIVED = "OPENCLAW_RECEIVED"
    const val DELIVERED = "DELIVERED"
    const val FAILED = "FAILED"

    val terminal = setOf(DELIVERED, FAILED)
}

data class AppSettings(
    val bridgeBaseUrl: String = PhoneAgentDefaults.DEFAULT_BRIDGE_BASE_URL,
    val clientId: String = PhoneAgentDefaults.DEFAULT_CLIENT_ID,
    val sessionId: String = PhoneAgentDefaults.defaultSessionId(),
    val apiToken: String = "",
    val useWebSocket: Boolean = true,
    val allowMobileNetworkSync: Boolean = true,
    val allowAutoCapture: Boolean = false,
    val allowCaptureOnBattery: Boolean = false,
    val frameStreamIntervalSec: Int = 2,
    val backgroundCaptureIntervalSec: Int = 60,
    val streamSummaryEveryFrames: Int = 3,
    val maxFramesPerCaptureSession: Int = 180,
    val captureRetentionDays: Int = 7,
)
