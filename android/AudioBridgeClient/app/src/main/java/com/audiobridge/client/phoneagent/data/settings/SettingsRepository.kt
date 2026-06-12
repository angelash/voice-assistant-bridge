package com.audiobridge.client.phoneagent.data.settings

import android.content.Context
import com.audiobridge.client.phoneagent.model.AppSettings
import com.audiobridge.client.phoneagent.model.PhoneAgentDefaults

class SettingsRepository private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        return AppSettings(
            bridgeBaseUrl = normalizeBaseUrl(
                prefs.getString(KEY_BRIDGE_BASE_URL, PhoneAgentDefaults.DEFAULT_BRIDGE_BASE_URL).orEmpty()
            ),
            clientId = prefs.getString(KEY_CLIENT_ID, PhoneAgentDefaults.DEFAULT_CLIENT_ID)
                ?.trim()
                ?.ifBlank { PhoneAgentDefaults.DEFAULT_CLIENT_ID }
                ?: PhoneAgentDefaults.DEFAULT_CLIENT_ID,
            sessionId = prefs.getString(KEY_SESSION_ID, PhoneAgentDefaults.defaultSessionId())
                ?.trim()
                ?.ifBlank { PhoneAgentDefaults.defaultSessionId() }
                ?: PhoneAgentDefaults.defaultSessionId(),
            apiToken = prefs.getString(KEY_API_TOKEN, "")?.trim().orEmpty(),
            useWebSocket = prefs.getBoolean(KEY_USE_WEBSOCKET, true),
            allowMobileNetworkSync = prefs.getBoolean(KEY_ALLOW_MOBILE_NETWORK_SYNC, true),
            allowAutoCapture = prefs.getBoolean(KEY_ALLOW_AUTO_CAPTURE, false),
            allowCaptureOnBattery = prefs.getBoolean(KEY_ALLOW_CAPTURE_ON_BATTERY, false),
        )
    }

    fun save(settings: AppSettings): AppSettings {
        val normalized = settings.copy(
            bridgeBaseUrl = normalizeBaseUrl(settings.bridgeBaseUrl),
            clientId = settings.clientId.trim().ifBlank { PhoneAgentDefaults.DEFAULT_CLIENT_ID },
            sessionId = settings.sessionId.trim().ifBlank { PhoneAgentDefaults.defaultSessionId() },
            apiToken = settings.apiToken.trim(),
        )
        prefs.edit()
            .putString(KEY_BRIDGE_BASE_URL, normalized.bridgeBaseUrl)
            .putString(KEY_CLIENT_ID, normalized.clientId)
            .putString(KEY_SESSION_ID, normalized.sessionId)
            .putString(KEY_API_TOKEN, normalized.apiToken)
            .putBoolean(KEY_USE_WEBSOCKET, normalized.useWebSocket)
            .putBoolean(KEY_ALLOW_MOBILE_NETWORK_SYNC, normalized.allowMobileNetworkSync)
            .putBoolean(KEY_ALLOW_AUTO_CAPTURE, normalized.allowAutoCapture)
            .putBoolean(KEY_ALLOW_CAPTURE_ON_BATTERY, normalized.allowCaptureOnBattery)
            .apply()
        return normalized
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return PhoneAgentDefaults.DEFAULT_BRIDGE_BASE_URL
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return "http://$trimmed"
    }

    companion object {
        private const val PREFS_NAME = "phone_agent_settings"
        private const val KEY_BRIDGE_BASE_URL = "bridgeBaseUrl"
        private const val KEY_CLIENT_ID = "clientId"
        private const val KEY_SESSION_ID = "sessionId"
        private const val KEY_API_TOKEN = "apiToken"
        private const val KEY_USE_WEBSOCKET = "useWebSocket"
        private const val KEY_ALLOW_MOBILE_NETWORK_SYNC = "allowMobileNetworkSync"
        private const val KEY_ALLOW_AUTO_CAPTURE = "allowAutoCapture"
        private const val KEY_ALLOW_CAPTURE_ON_BATTERY = "allowCaptureOnBattery"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
        }
    }
}
