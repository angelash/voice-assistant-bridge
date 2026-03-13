package com.audiobridge.client

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "VoiceBridgeMain"
        private const val HARDCODED_LAN_BASE_URL = "http://10.3.91.22:8765"
        private const val HARDCODED_PUBLIC_BASE_URL = "http://voice-bridge.iepose.cn"
        private const val HARDCODED_LAN_WIFI_KEYWORD = "4399"
    }

    private enum class LinkMode { LAN, TUNNEL }

    private data class BridgeEndpoint(
        val mode: LinkMode,
        val baseUrl: String,
        val wifiSsid: String?,
    )

    private lateinit var statusText: TextView
    private lateinit var routeInfoText: TextView
    private lateinit var sessionIdInput: EditText
    private lateinit var clientIdInput: EditText
    private lateinit var textInput: EditText
    private lateinit var sendTextButton: Button
    private lateinit var sttButton: Button
    private lateinit var textResultView: TextView
    private lateinit var speakSwitch: Switch

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var sttListening = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            appendResult("[系统] 语音识别已取消")
            statusText.text = "语音识别已取消"
            return@registerForActivityResult
        }

        val data = result.data
        if (data == null) {
            appendResult("[系统] 语音识别返回为空")
            statusText.text = "语音识别失败"
            return@registerForActivityResult
        }

        val texts = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val spoken = texts?.firstOrNull()?.trim().orEmpty()
        if (spoken.isBlank()) {
            appendResult("[系统] 未识别到有效文本")
            statusText.text = "语音识别无结果"
            return@registerForActivityResult
        }

        textInput.setText(spoken)
        sendTextToBridge(spoken)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        routeInfoText = findViewById(R.id.routeInfoText)
        sessionIdInput = findViewById(R.id.sessionIdInput)
        clientIdInput = findViewById(R.id.clientIdInput)
        textInput = findViewById(R.id.textInput)
        sendTextButton = findViewById(R.id.sendTextButton)
        sttButton = findViewById(R.id.sttButton)
        textResultView = findViewById(R.id.textResultView)
        speakSwitch = findViewById(R.id.speakSwitch)

        initTts()
        loadPrefs()
        refreshRouteInfo()

        sendTextButton.setOnClickListener {
            val text = textInput.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) {
                statusText.text = "请输入文本"
                return@setOnClickListener
            }
            sendTextToBridge(text)
        }

        sttButton.setOnClickListener {
            startSpeechToText()
        }

        if (!hasLocationPermission()) {
            requestLocationPermission()
        }

        if (!hasRecordAudioPermission()) {
            requestRecordAudioPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRouteInfo()
    }

    override fun onStop() {
        super.onStop()
        savePrefs()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseSpeechRecognizer()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun currentWifiSsid(): String? {
        if (!hasLocationPermission()) return null
        return try {
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ssid = manager?.connectionInfo?.ssid?.trim()?.trim('"').orEmpty()
            if (ssid.isBlank() || ssid.equals("<unknown ssid>", ignoreCase = true)) null else ssid
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveBridgeEndpoint(): BridgeEndpoint {
        val wifi = currentWifiSsid()
        val useLan = wifi?.contains(HARDCODED_LAN_WIFI_KEYWORD, ignoreCase = true) == true
        return if (useLan) {
            BridgeEndpoint(LinkMode.LAN, HARDCODED_LAN_BASE_URL, wifi)
        } else {
            BridgeEndpoint(LinkMode.TUNNEL, HARDCODED_PUBLIC_BASE_URL, wifi)
        }
    }

    private fun refreshRouteInfo() {
        val endpoint = resolveBridgeEndpoint()
        routeInfoText.text = buildString {
            appendLine("Auto Route:")
            appendLine("  current wifi: ${endpoint.wifiSsid ?: "N/A"}")
            appendLine("  location perm: ${if (hasLocationPermission()) "granted" else "missing"}")
            appendLine("  wifi keyword: $HARDCODED_LAN_WIFI_KEYWORD")
            appendLine("  selected mode: ${endpoint.mode}")
            appendLine("  selected base: ${endpoint.baseUrl}")
            appendLine("  lan base: $HARDCODED_LAN_BASE_URL")
            appendLine("  public base: $HARDCODED_PUBLIC_BASE_URL")
        }
    }

    private fun appendResult(line: String) {
        runOnUiThread {
            val old = textResultView.text?.toString().orEmpty()
            val next = if (old.isBlank() || old == "(text result)") line else "$old\n$line"
            textResultView.text = next
        }
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
                    statusText.text = "文本发送中 (${endpoint.mode}, wifi=${endpoint.wifiSsid ?: "N/A"}, ${endpoint.baseUrl})"
                    refreshRouteInfo()
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
        val req = Request.Builder()
            .url(endpoint.baseUrl.trimEnd('/') + path)
            .post(reqBody)
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: $text")
            return JSONObject(text)
        }
    }

    private fun getJson(endpoint: BridgeEndpoint, path: String): JSONObject {
        val req = Request.Builder()
            .url(endpoint.baseUrl.trimEnd('/') + path)
            .get()
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: $text")
            return JSONObject(text)
        }
    }

    private fun buildRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出要发送的内容")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }

    private fun startSpeechToText() {
        if (sttListening) return

        if (!hasRecordAudioPermission()) {
            requestRecordAudioPermission()
            return
        }

        val intent = buildRecognizerIntent()
        val canLaunchActivity = intent.resolveActivity(packageManager) != null
        if (canLaunchActivity) {
            statusText.text = "正在启动系统语音识别"
            speechLauncher.launch(intent)
            return
        }

        Log.w(TAG, "No activity can handle ACTION_RECOGNIZE_SPEECH, fallback to SpeechRecognizer service")
        startSpeechRecognizerFallback(intent)
    }

    private fun startSpeechRecognizerFallback(intent: Intent) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            val currentService = Settings.Secure.getString(contentResolver, "voice_recognition_service")
            val msg = if (currentService.isNullOrBlank()) {
                "STT不可用：系统未配置语音识别服务，请在系统语音输入设置里启用"
            } else {
                "STT不可用：语音识别服务不可用($currentService)"
            }
            appendResult("[系统] $msg")
            statusText.text = "STT不可用"
            openVoiceInputSettings()
            return
        }

        releaseSpeechRecognizer()

        val component = findRecognitionServiceComponent()
        speechRecognizer = if (component != null) {
            Log.i(TAG, "Using RecognitionService: $component")
            SpeechRecognizer.createSpeechRecognizer(this, component)
        } else {
            Log.i(TAG, "Using default RecognitionService")
            SpeechRecognizer.createSpeechRecognizer(this)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                runOnUiThread { statusText.text = "请开始说话..." }
            }

            override fun onBeginningOfSpeech() {
                runOnUiThread { statusText.text = "识别中..." }
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                runOnUiThread { statusText.text = "处理中..." }
            }

            override fun onError(error: Int) {
                sttListening = false
                val msg = speechErrorMessage(error)
                appendResult("[系统] STT失败: $msg")
                runOnUiThread { statusText.text = "语音识别失败: $msg" }
                releaseSpeechRecognizer()
            }

            override fun onResults(results: Bundle?) {
                sttListening = false
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()

                if (spoken.isBlank()) {
                    appendResult("[系统] 未识别到有效文本")
                    runOnUiThread { statusText.text = "语音识别无结果" }
                } else {
                    runOnUiThread { textInput.setText(spoken) }
                    sendTextToBridge(spoken)
                }
                releaseSpeechRecognizer()
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            sttListening = true
            statusText.text = "正在语音识别..."
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            sttListening = false
            appendResult("[系统] STT启动失败: ${e.message}")
            statusText.text = "语音识别启动失败"
            releaseSpeechRecognizer()
        }
    }

    private fun openVoiceInputSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (_: Exception) {
        }
    }

    private fun findRecognitionServiceComponent(): ComponentName? {
        return try {
            val services = packageManager.queryIntentServices(
                Intent("android.speech.RecognitionService"),
                0,
            )
            if (services.isEmpty()) return null

            val preferred = services.firstOrNull {
                it.serviceInfo?.packageName == "com.google.android.googlequicksearchbox"
            } ?: services.firstOrNull {
                it.serviceInfo?.packageName == "com.vivo.voicerecognition"
            } ?: services.first()

            val info = preferred.serviceInfo ?: return null
            val className = if (info.name.startsWith(".")) "${info.packageName}${info.name}" else info.name
            ComponentName(info.packageName, className)
        } catch (e: Exception) {
            Log.w(TAG, "findRecognitionServiceComponent failed: ${e.message}")
            null
        }
    }

    private fun speechErrorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "音频采集错误"
            SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
            SpeechRecognizer.ERROR_NETWORK -> "网络错误"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
            SpeechRecognizer.ERROR_NO_MATCH -> "未识别到内容"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙"
            SpeechRecognizer.ERROR_SERVER -> "识别服务异常"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "说话超时"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "语言不支持"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "语言暂不可用"
            else -> "未知错误($error)"
        }
    }

    private fun releaseSpeechRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        } finally {
            speechRecognizer = null
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
        sessionIdInput.setText(p.getString("sessionId", "voice-bridge-session"))
        clientIdInput.setText(p.getString("clientId", "android-client"))
        speakSwitch.isChecked = p.getBoolean("speakEnabled", true)
    }

    private fun savePrefs() {
        val p = prefs().edit()
        p.putString("sessionId", sessionIdInput.text?.toString()?.trim().orEmpty())
        p.putString("clientId", clientIdInput.text?.toString()?.trim().orEmpty())
        p.putBoolean("speakEnabled", speakSwitch.isChecked)
        p.apply()
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestRecordAudioPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1002)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002) {
            refreshRouteInfo()
        }
    }
}
