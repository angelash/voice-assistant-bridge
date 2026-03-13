package com.audiobridge.client

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.audiobridge.client.audio.AudioTuningMode
import com.audiobridge.client.service.AudioBridgeForegroundService
import com.audiobridge.client.ws.AbpWebSocketClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private enum class LinkMode { AUTO, LAN, TUNNEL }

    private data class BridgeEndpoint(
        val mode: LinkMode,
        val baseUrl: String,
        val token: String,
        val wifiSsid: String?,
    )

    private lateinit var statusText: TextView
    private lateinit var audioStatusText: TextView
    private lateinit var hostInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var connectButton: Button
    private lateinit var uplinkSwitch: Switch
    private lateinit var downlinkSwitch: Switch
    private lateinit var tuningModeGroup: RadioGroup
    private lateinit var tuningModeLegacy: RadioButton
    private lateinit var tuningModeRobust: RadioButton

    private lateinit var linkModeGroup: RadioGroup
    private lateinit var linkModeAuto: RadioButton
    private lateinit var linkModeLan: RadioButton
    private lateinit var linkModeTunnel: RadioButton
    private lateinit var lanBaseUrlInput: EditText
    private lateinit var lanTokenInput: EditText
    private lateinit var lanWifiRuleInput: EditText
    private lateinit var tunnelBaseUrlInput: EditText
    private lateinit var tunnelTokenInput: EditText
    private lateinit var sessionIdInput: EditText
    private lateinit var clientIdInput: EditText
    private lateinit var textInput: EditText
    private lateinit var sendTextButton: Button
    private lateinit var sttButton: Button
    private lateinit var textResultView: TextView
    private lateinit var speakSwitch: Switch

    private var ignoreTuningModeUiChange: Boolean = false
    private var pendingStartAfterPermission: Boolean = false

    private var tts: TextToSpeech? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())
    private var statusUpdateRunnable: Runnable? = null

    private var service: AudioBridgeForegroundService? = null
    private var serviceBound: Boolean = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? AudioBridgeForegroundService.LocalBinder
            service = b?.getService()
            serviceBound = service != null
            service?.getSnapshot()?.let { snap ->
                syncTuningModeUi(snap.tuningMode)
            }
            updateUiOnce()
            startStatusUpdate()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            service = null
            stopStatusUpdate()
            updateUiOnce()
        }
    }

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val texts = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val spoken = texts?.firstOrNull()?.trim().orEmpty()
        if (spoken.isBlank()) return@registerForActivityResult
        textInput.setText(spoken)
        sendTextToBridge(spoken)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        audioStatusText = findViewById(R.id.audioStatusText)
        hostInput = findViewById(R.id.hostInput)
        tokenInput = findViewById(R.id.tokenInput)
        connectButton = findViewById(R.id.connectButton)
        uplinkSwitch = findViewById(R.id.uplinkSwitch)
        downlinkSwitch = findViewById(R.id.downlinkSwitch)
        tuningModeGroup = findViewById(R.id.tuningModeGroup)
        tuningModeLegacy = findViewById(R.id.tuningModeLegacy)
        tuningModeRobust = findViewById(R.id.tuningModeRobust)

        linkModeGroup = findViewById(R.id.linkModeGroup)
        linkModeAuto = findViewById(R.id.linkModeAuto)
        linkModeLan = findViewById(R.id.linkModeLan)
        linkModeTunnel = findViewById(R.id.linkModeTunnel)
        lanBaseUrlInput = findViewById(R.id.lanBaseUrlInput)
        lanTokenInput = findViewById(R.id.lanTokenInput)
        lanWifiRuleInput = findViewById(R.id.lanWifiRuleInput)
        tunnelBaseUrlInput = findViewById(R.id.tunnelBaseUrlInput)
        tunnelTokenInput = findViewById(R.id.tunnelTokenInput)
        sessionIdInput = findViewById(R.id.sessionIdInput)
        clientIdInput = findViewById(R.id.clientIdInput)
        textInput = findViewById(R.id.textInput)
        sendTextButton = findViewById(R.id.sendTextButton)
        sttButton = findViewById(R.id.sttButton)
        textResultView = findViewById(R.id.textResultView)
        speakSwitch = findViewById(R.id.speakSwitch)

        initTts()
        loadPrefs()

        uplinkSwitch.setOnCheckedChangeListener { _, checked -> service?.setEnableUplink(checked) }
        downlinkSwitch.setOnCheckedChangeListener { _, checked -> service?.setEnableDownlink(checked) }
        tuningModeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (ignoreTuningModeUiChange) return@setOnCheckedChangeListener
            val mode = if (checkedId == R.id.tuningModeLegacy) AudioTuningMode.LEGACY else AudioTuningMode.ROBUST
            service?.setTuningMode(mode)
        }

        connectButton.setOnClickListener {
            val snap = service?.getSnapshot()
            val connected = snap?.wsState == AbpWebSocketClient.State.CONNECTED
            val connecting = snap?.wsState == AbpWebSocketClient.State.CONNECTING
            if (!connected && !connecting) connectAudioBridge() else disconnectAudioBridge()
        }

        sendTextButton.setOnClickListener {
            val text = textInput.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) {
                statusText.text = "请输入文本"
                return@setOnClickListener
            }
            sendTextToBridge(text)
        }

        sttButton.setOnClickListener { startSpeechToText() }

        if (!hasRecordAudioPermission()) {
            requestRecordAudioPermission()
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, AudioBridgeForegroundService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        stopStatusUpdate()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            service = null
        }
        savePrefs()
    }

    private fun connectAudioBridge() {
        val host = hostInput.text?.toString()?.trim().orEmpty()
        if (host.isBlank()) {
            statusText.text = "请填写音频桥接 Host"
            return
        }

        val token = tokenInput.text?.toString()?.trim().orEmpty()
        val enableUplink = uplinkSwitch.isChecked
        val enableDownlink = downlinkSwitch.isChecked
        val mode = getSelectedTuningMode()

        if (enableUplink && !hasRecordAudioPermission()) {
            requestRecordAudioPermission()
            statusText.text = "需要麦克风权限"
            pendingStartAfterPermission = true
            return
        }

        if (!hasPostNotificationsPermission()) {
            requestPostNotificationsPermission()
            statusText.text = "需要通知权限以后台保活"
            pendingStartAfterPermission = true
            return
        }

        pendingStartAfterPermission = false
        startForegroundBridgeService(host, token, enableUplink, enableDownlink, mode)
        startStatusUpdate()
        savePrefs()
    }

    private fun disconnectAudioBridge() {
        pendingStartAfterPermission = false
        stopStatusUpdate()

        try {
            service?.requestStop()
        } catch (_: Exception) {
        }

        try {
            stopService(Intent(this, AudioBridgeForegroundService::class.java))
        } catch (_: Exception) {
        }

        if (serviceBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {
            } finally {
                serviceBound = false
                service = null
            }
        }
        updateUiOnce()
    }

    private fun startStatusUpdate() {
        if (statusUpdateRunnable != null) return
        statusUpdateRunnable = object : Runnable {
            override fun run() {
                updateUiOnce()
                handler.postDelayed(this, 500)
            }
        }
        statusUpdateRunnable?.let { handler.post(it) }
    }

    private fun stopStatusUpdate() {
        statusUpdateRunnable?.let { handler.removeCallbacks(it) }
        statusUpdateRunnable = null
    }

    private fun updateUiOnce() {
        val snap = service?.getSnapshot()
        if (snap == null) {
            connectButton.text = "连接"
            statusText.text = "音频桥接未连接"
            audioStatusText.text = ""
            return
        }

        connectButton.text = if (snap.wsState == AbpWebSocketClient.State.CONNECTED) "断开" else "连接"
        statusText.text = "音频状态: ${snap.wsState}"

        syncTuningModeUi(snap.tuningMode)

        audioStatusText.text = buildString {
            appendLine("Audio bridge status:")
            appendLine("  codec: ${snap.selectedCodec}")
            appendLine("  tuning: ${if (snap.tuningMode == AudioTuningMode.LEGACY) "Mode A" else "Mode B"}")
            appendLine("  uplink enabled: ${snap.enableUplink}")
            appendLine("  downlink enabled: ${snap.enableDownlink}")
            appendLine("  mic running: ${snap.captureRunning}")
            appendLine("  player running: ${snap.playerRunning}")
            appendLine("  uplink frames captured: ${snap.uplinkFramesCaptured}")
            appendLine("  uplink frames sent: ${snap.uplinkFramesSent} (suppressed ${snap.uplinkFramesSuppressed})")
            appendLine("  uplink bytes: ${formatBytes(snap.uplinkBytesSent)}")
            appendLine("  downlink frames played: ${snap.downlinkFramesPlayed}")
            appendLine("  downlink frames recv: ${snap.downlinkFramesReceived}")
            appendLine("  downlink bytes: ${formatBytes(snap.downlinkBytesReceived)}")
            appendLine("  player buffer: ${snap.playerBufferedMs}ms")
            appendLine("  underrun count: ${snap.playerUnderrunCount}")
            if (!snap.lastError.isNullOrBlank()) {
                appendLine("  last error: ${snap.lastError}")
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "-"
        if (bytes < 1024) return "${bytes}B"
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0)
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024.0))
        return String.format("%.2fGB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun appendResult(line: String) {
        runOnUiThread {
            val old = textResultView.text?.toString().orEmpty()
            val next = if (old.isBlank() || old == "(text result)") line else "$old\n$line"
            textResultView.text = next
        }
    }

    private fun selectedLinkMode(): LinkMode {
        return when (linkModeGroup.checkedRadioButtonId) {
            R.id.linkModeLan -> LinkMode.LAN
            R.id.linkModeTunnel -> LinkMode.TUNNEL
            else -> LinkMode.AUTO
        }
    }

    private fun currentWifiSsid(): String? {
        return try {
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ssid = manager?.connectionInfo?.ssid?.trim()?.trim('"').orEmpty()
            if (ssid.isBlank() || ssid.equals("<unknown ssid>", ignoreCase = true)) null else ssid
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeHttpBase(url: String): String {
        val raw = url.trim().trimEnd('/')
        if (raw.isBlank()) return ""
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    }

    private fun resolveBridgeEndpoint(): BridgeEndpoint {
        val lanUrl = normalizeHttpBase(lanBaseUrlInput.text?.toString().orEmpty())
        val tunnelUrl = normalizeHttpBase(tunnelBaseUrlInput.text?.toString().orEmpty())
        val lanToken = lanTokenInput.text?.toString()?.trim().orEmpty()
        val tunnelToken = tunnelTokenInput.text?.toString()?.trim().orEmpty()
        val rule = lanWifiRuleInput.text?.toString()?.trim().orEmpty()
        val wifi = currentWifiSsid()

        val mode = selectedLinkMode()
        val endpoint = when (mode) {
            LinkMode.LAN -> BridgeEndpoint(LinkMode.LAN, lanUrl, lanToken, wifi)
            LinkMode.TUNNEL -> BridgeEndpoint(LinkMode.TUNNEL, tunnelUrl, tunnelToken, wifi)
            LinkMode.AUTO -> {
                val useLan = lanUrl.isNotBlank() && rule.isNotBlank() && (wifi?.contains(rule, ignoreCase = true) == true)
                if (useLan) BridgeEndpoint(LinkMode.LAN, lanUrl, lanToken, wifi)
                else if (tunnelUrl.isNotBlank()) BridgeEndpoint(LinkMode.TUNNEL, tunnelUrl, tunnelToken, wifi)
                else BridgeEndpoint(LinkMode.LAN, lanUrl, lanToken, wifi)
            }
        }

        if (endpoint.baseUrl.isBlank()) {
            throw IllegalStateException("请配置可用的 Bridge Base URL")
        }
        return endpoint
    }

    private fun sendTextToBridge(text: String) {
        val input = text.trim()
        if (input.isBlank()) return
        textInput.setText("")
        savePrefs()

        appendResult("[用户] $input")

        Thread {
            try {
                val endpoint = resolveBridgeEndpoint()
                val sessionId = sessionIdInput.text?.toString()?.trim().orEmpty().ifBlank { "voice-bridge-session" }
                val clientId = clientIdInput.text?.toString()?.trim().orEmpty().ifBlank { "android-client" }

                runOnUiThread {
                    statusText.text = "文本发送中 (${endpoint.mode}, wifi=${endpoint.wifiSsid ?: "N/A"})"
                }

                val submitBody = JSONObject()
                    .put("text", input)
                    .put("session_id", sessionId)
                    .put("client_id", clientId)
                    .put("source", "android")
                val submitResp = postJson(endpoint, "/v1/messages", submitBody)

                val shown = linkedSetOf<String>()
                val localReply = submitResp.optString("local_reply").trim()
                if (localReply.isNotBlank()) {
                    val label = submitResp.optString("local_source_label").ifBlank { "本地接线员" }
                    appendResult("[$label] $localReply")
                    speak(localReply)
                    shown.add("$label::$localReply")
                }

                val messageId = submitResp.optString("message_id").trim()
                val initialState = submitResp.optString("status").uppercase(Locale.getDefault())

                if (messageId.isNotBlank() && initialState !in setOf("DELIVERED", "FAILED")) {
                    val terminal = pollTerminal(endpoint, messageId, timeoutSec = 180, intervalMs = 1000)
                    if (terminal != null) {
                        renderStatusMessages(terminal, shown)
                    } else {
                        appendResult("[系统] 终答等待超时")
                    }
                } else {
                    renderStatusMessages(submitResp, shown)
                }

                runOnUiThread { statusText.text = "文本发送完成" }
            } catch (e: Exception) {
                appendResult("[系统] 文本发送失败: ${e.message}")
                runOnUiThread { statusText.text = "文本发送失败" }
            }
        }.start()
    }

    private fun renderStatusMessages(payload: JSONObject, shown: MutableSet<String>) {
        val messages = payload.optJSONArray("messages")
        if (messages != null) {
            for (i in 0 until messages.length()) {
                val item = messages.optJSONObject(i) ?: continue
                val text = item.optString("text").trim()
                if (text.isBlank()) continue
                val label = item.optString("source_label").ifBlank { "助手" }
                val key = "$label::$text"
                if (shown.contains(key)) continue
                shown.add(key)
                appendResult("[$label] $text")
                if (item.optString("kind") != "error") {
                    speak(text)
                }
            }
        }

        val state = payload.optString("status").uppercase(Locale.getDefault())
        if (state == "FAILED") {
            val err = payload.optString("last_error").ifBlank { "openclaw_failed" }
            appendResult("[系统] 龙虾大脑失败: $err")
        }
    }

    private fun pollTerminal(endpoint: BridgeEndpoint, messageId: String, timeoutSec: Int, intervalMs: Long): JSONObject? {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeoutSec * 1000L) {
            val status = getJson(endpoint, "/v1/messages/$messageId")
            val state = status.optString("status").uppercase(Locale.getDefault())
            if (state == "DELIVERED" || state == "FAILED") {
                return status
            }
            Thread.sleep(intervalMs)
        }
        return null
    }

    private fun postJson(endpoint: BridgeEndpoint, path: String, body: JSONObject): JSONObject {
        val reqBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder().url(endpoint.baseUrl + path).post(reqBody)
        if (endpoint.token.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer ${endpoint.token}")
        }
        httpClient.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: $text")
            return JSONObject(text)
        }
    }

    private fun getJson(endpoint: BridgeEndpoint, path: String): JSONObject {
        val builder = Request.Builder().url(endpoint.baseUrl + path).get()
        if (endpoint.token.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer ${endpoint.token}")
        }
        httpClient.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: $text")
            return JSONObject(text)
        }
    }

    private fun startSpeechToText() {
        if (!hasRecordAudioPermission()) {
            requestRecordAudioPermission()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出要发送的内容")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            appendResult("[系统] 语音识别不可用: ${e.message}")
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
            }
        }
    }

    private fun speak(text: String) {
        if (!speakSwitch.isChecked) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "msg-${System.currentTimeMillis()}")
    }

    private fun prefs() = getSharedPreferences("audiobridge", MODE_PRIVATE)

    private fun loadPrefs() {
        val p = prefs()
        hostInput.setText(p.getString("audioHost", ""))
        tokenInput.setText(p.getString("audioToken", ""))
        uplinkSwitch.isChecked = p.getBoolean("audioUplink", true)
        downlinkSwitch.isChecked = p.getBoolean("audioDownlink", true)
        val tuning = AudioTuningMode.fromId(p.getInt("audioTuningMode", AudioTuningMode.ROBUST.id))
        syncTuningModeUi(tuning)

        when (p.getString("linkMode", "AUTO")) {
            "LAN" -> linkModeLan.isChecked = true
            "TUNNEL" -> linkModeTunnel.isChecked = true
            else -> linkModeAuto.isChecked = true
        }

        lanBaseUrlInput.setText(p.getString("lanBaseUrl", ""))
        lanTokenInput.setText(p.getString("lanToken", ""))
        lanWifiRuleInput.setText(p.getString("lanWifiRule", ""))
        tunnelBaseUrlInput.setText(p.getString("tunnelBaseUrl", ""))
        tunnelTokenInput.setText(p.getString("tunnelToken", ""))
        sessionIdInput.setText(p.getString("sessionId", "voice-bridge-session"))
        clientIdInput.setText(p.getString("clientId", "android-client"))
        speakSwitch.isChecked = p.getBoolean("speakEnabled", true)
    }

    private fun savePrefs() {
        val p = prefs().edit()
        p.putString("audioHost", hostInput.text?.toString()?.trim().orEmpty())
        p.putString("audioToken", tokenInput.text?.toString()?.trim().orEmpty())
        p.putBoolean("audioUplink", uplinkSwitch.isChecked)
        p.putBoolean("audioDownlink", downlinkSwitch.isChecked)
        p.putInt("audioTuningMode", getSelectedTuningMode().id)

        p.putString("linkMode", selectedLinkMode().name)
        p.putString("lanBaseUrl", lanBaseUrlInput.text?.toString()?.trim().orEmpty())
        p.putString("lanToken", lanTokenInput.text?.toString()?.trim().orEmpty())
        p.putString("lanWifiRule", lanWifiRuleInput.text?.toString()?.trim().orEmpty())
        p.putString("tunnelBaseUrl", tunnelBaseUrlInput.text?.toString()?.trim().orEmpty())
        p.putString("tunnelToken", tunnelTokenInput.text?.toString()?.trim().orEmpty())
        p.putString("sessionId", sessionIdInput.text?.toString()?.trim().orEmpty())
        p.putString("clientId", clientIdInput.text?.toString()?.trim().orEmpty())
        p.putBoolean("speakEnabled", speakSwitch.isChecked)
        p.apply()
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestRecordAudioPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
    }

    private fun hasPostNotificationsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestPostNotificationsPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
    }

    private fun startForegroundBridgeService(
        host: String,
        token: String,
        enableUplink: Boolean,
        enableDownlink: Boolean,
        tuningMode: AudioTuningMode,
    ) {
        val i = Intent(this, AudioBridgeForegroundService::class.java).apply {
            action = AudioBridgeForegroundService.ACTION_START
            putExtra(AudioBridgeForegroundService.EXTRA_HOST, host)
            putExtra(AudioBridgeForegroundService.EXTRA_TOKEN, token)
            putExtra(AudioBridgeForegroundService.EXTRA_ENABLE_UPLINK, enableUplink)
            putExtra(AudioBridgeForegroundService.EXTRA_ENABLE_DOWNLINK, enableDownlink)
            putExtra(AudioBridgeForegroundService.EXTRA_TUNING_MODE, tuningMode.id)
        }
        ContextCompat.startForegroundService(this, i)
    }

    private fun getSelectedTuningMode(): AudioTuningMode {
        return if (tuningModeLegacy.isChecked) AudioTuningMode.LEGACY else AudioTuningMode.ROBUST
    }

    private fun syncTuningModeUi(mode: AudioTuningMode) {
        ignoreTuningModeUiChange = true
        try {
            when (mode) {
                AudioTuningMode.LEGACY -> tuningModeLegacy.isChecked = true
                AudioTuningMode.ROBUST -> tuningModeRobust.isChecked = true
            }
        } finally {
            ignoreTuningModeUiChange = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStatusUpdate()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (!pendingStartAfterPermission) return

        if (requestCode == 1001 || requestCode == 1002) {
            val enableUplink = uplinkSwitch.isChecked
            val micOk = !enableUplink || hasRecordAudioPermission()
            val notifOk = hasPostNotificationsPermission()

            if (micOk && notifOk) {
                pendingStartAfterPermission = false
                connectAudioBridge()
            } else {
                pendingStartAfterPermission = false
            }
        }
    }
}
