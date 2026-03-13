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
        private const val LAN_BASE_URL = "http://10.3.91.22:8765"
        private const val PUBLIC_BASE_URL = "http://voice-bridge.iepose.cn"
        private const val LAN_WIFI_SSID = "4399"
        private const val PREFS_NAME = "audiobridge"
        private const val REQ_RECORD_AUDIO = 1001
        private const val REQ_LOCATION = 1002
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

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            sttListening = false
            if (result.resultCode != RESULT_OK) {
                appendResult("[system] speech recognition canceled")
                statusText.text = "Speech recognition canceled"
                return@registerForActivityResult
            }

            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()

            if (spoken.isBlank()) {
                appendResult("[system] no valid speech text")
                statusText.text = "No speech result"
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
                statusText.text = "Please enter text"
                return@setOnClickListener
            }
            sendTextToBridge(text)
        }

        sttButton.setOnClickListener { startSpeechToText() }

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
        val useLan = wifi?.equals(LAN_WIFI_SSID, ignoreCase = true) == true
        return if (useLan) {
            BridgeEndpoint(LinkMode.LAN, LAN_BASE_URL, wifi)
        } else {
            BridgeEndpoint(LinkMode.TUNNEL, PUBLIC_BASE_URL, wifi)
        }
    }

    private fun refreshRouteInfo() {
        val endpoint = resolveBridgeEndpoint()
        routeInfoText.text = buildString {
            appendLine("Auto Route:")
            appendLine("  current wifi: ${endpoint.wifiSsid ?: "N/A"}")
            appendLine("  location perm: ${if (hasLocationPermission()) "granted" else "missing"}")
            appendLine("  lan wifi: $LAN_WIFI_SSID")
            appendLine("  selected mode: ${endpoint.mode}")
            appendLine("  selected base: ${endpoint.baseUrl}")
            appendLine("  lan base: $LAN_BASE_URL")
            appendLine("  public base: $PUBLIC_BASE_URL")
        }
    }

    private fun appendResult(line: String) {
        runOnUiThread {
            val old = textResultView.text?.toString().orEmpty()
            val next = if (old.isBlank() || old == "(text result)") line else "$old\n$line"
            textResultView.text = next
        }
    }

    private fun normalizeForDisplay(text: String): String {
        return text
            .trim()
            .replace(Regex("""^\s*\[\[[^\]]+\]\]\s*"""), "")
            .trim()
    }

    private fun normalizeForSpeech(text: String): String {
        var cleaned = normalizeForDisplay(text)
        cleaned = cleaned.replace(
            Regex(
                """[\x{1F300}-\x{1F5FF}\x{1F600}-\x{1F64F}\x{1F680}-\x{1F6FF}\x{1F700}-\x{1F77F}\x{1F780}-\x{1F7FF}\x{1F800}-\x{1F8FF}\x{1F900}-\x{1F9FF}\x{1FA00}-\x{1FAFF}\x{2700}-\x{27BF}\x{2600}-\x{26FF}\x{FE00}-\x{FE0F}\x{1F1E6}-\x{1F1FF}]""",
            ),
            "",
        )
        cleaned = cleaned.replace(Regex("""`{1,3}"""), " ")
        cleaned = cleaned.replace(Regex("""[*_~#>]"""), " ")
        cleaned = cleaned.replace(Regex("""\s{2,}"""), " ").trim()
        return cleaned
    }

    private fun sendTextToBridge(text: String) {
        val input = text.trim()
        if (input.isBlank()) return
        textInput.setText("")
        savePrefs()
        appendResult("[user] $input")

        Thread {
            try {
                val endpoint = resolveBridgeEndpoint()
                val sessionId = sessionIdInput.text?.toString()?.trim().orEmpty().ifBlank { "voice-bridge-session" }
                val clientId = clientIdInput.text?.toString()?.trim().orEmpty().ifBlank { "android-client" }

                runOnUiThread {
                    statusText.text = "Sending (${endpoint.mode}, wifi=${endpoint.wifiSsid ?: "N/A"})"
                    refreshRouteInfo()
                }

                val submitBody = JSONObject()
                    .put("text", input)
                    .put("session_id", sessionId)
                    .put("client_id", clientId)
                    .put("source", "android")

                val submitResp = postJson(endpoint, "/v1/messages", submitBody)

                val shown = linkedSetOf<String>()
                val localReplyRaw = submitResp.optString("local_reply").trim()
                val localReply = normalizeForDisplay(localReplyRaw)
                if (localReply.isNotBlank()) {
                    val label = submitResp.optString("local_source_label").ifBlank { "Local Operator" }
                    appendResult("[$label] $localReply")
                    speak(localReplyRaw)
                    shown.add("$label::$localReply")
                }

                val messageId = submitResp.optString("message_id").trim()
                val state = submitResp.optString("status").uppercase(Locale.getDefault())
                if (messageId.isNotBlank() && state !in setOf("DELIVERED", "FAILED")) {
                    val terminal = pollTerminal(endpoint, messageId, timeoutSec = 180, intervalMs = 1000)
                    if (terminal != null) {
                        renderStatusMessages(terminal, shown)
                    } else {
                        appendResult("[system] timeout waiting final reply")
                    }
                } else {
                    renderStatusMessages(submitResp, shown)
                }

                runOnUiThread { statusText.text = "Send complete" }
            } catch (e: Exception) {
                appendResult("[system] send failed: ${e.message ?: "unknown"}")
                runOnUiThread { statusText.text = "Send failed" }
            }
        }.start()
    }

    private fun renderStatusMessages(payload: JSONObject, shown: MutableSet<String>) {
        val messages = payload.optJSONArray("messages")
        if (messages != null) {
            for (i in 0 until messages.length()) {
                val item = messages.optJSONObject(i) ?: continue
                val textRaw = item.optString("text").trim()
                val text = normalizeForDisplay(textRaw)
                if (text.isBlank()) continue
                val label = item.optString("source_label").ifBlank { "Assistant" }
                val key = "$label::$text"
                if (shown.contains(key)) continue
                shown.add(key)
                appendResult("[$label] $text")
                if (item.optString("kind") != "error") {
                    speak(textRaw)
                }
            }
        }

        val state = payload.optString("status").uppercase(Locale.getDefault())
        if (state == "FAILED") {
            val err = payload.optString("last_error").ifBlank { "openclaw_failed" }
            appendResult("[system] openclaw failed: $err")
        }
    }

    private fun pollTerminal(
        endpoint: BridgeEndpoint,
        messageId: String,
        timeoutSec: Int,
        intervalMs: Long,
    ): JSONObject? {
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
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message")
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
            sttListening = true
            statusText.text = "Starting speech recognition"
            speechLauncher.launch(intent)
            return
        }

        Log.w(TAG, "No activity for ACTION_RECOGNIZE_SPEECH, fallback to RecognitionService")
        startSpeechRecognizerFallback(intent)
    }

    private fun startSpeechRecognizerFallback(intent: Intent) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            val currentService = Settings.Secure.getString(contentResolver, "voice_recognition_service")
            val msg = if (currentService.isNullOrBlank()) {
                "STT unavailable: no speech service configured."
            } else {
                "STT unavailable: recognition service not ready ($currentService)"
            }
            appendResult("[system] $msg")
            statusText.text = "STT unavailable"
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
                runOnUiThread { statusText.text = "Speak now..." }
            }

            override fun onBeginningOfSpeech() {
                runOnUiThread { statusText.text = "Listening..." }
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                runOnUiThread { statusText.text = "Processing..." }
            }

            override fun onError(error: Int) {
                sttListening = false
                val msg = speechErrorMessage(error)
                appendResult("[system] STT failed: $msg")
                runOnUiThread { statusText.text = "Speech failed: $msg" }
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
                    appendResult("[system] no valid speech text")
                    runOnUiThread { statusText.text = "No speech result" }
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
            statusText.text = "Listening..."
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            sttListening = false
            appendResult("[system] STT start failed: ${e.message ?: "unknown"}")
            statusText.text = "STT start failed"
            releaseSpeechRecognizer()
        }
    }

    private fun openVoiceInputSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun findRecognitionServiceComponent(): ComponentName? {
        return try {
            val services = packageManager.queryIntentServices(Intent("android.speech.RecognitionService"), 0)
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
            SpeechRecognizer.ERROR_AUDIO -> "Audio capture error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing audio permission"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Service error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable"
            else -> "Unknown error($error)"
        }
    }

    private fun releaseSpeechRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
            // ignore
        } finally {
            speechRecognizer = null
            sttListening = false
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
        val speakText = normalizeForSpeech(text)
        if (speakText.isBlank()) return
        tts?.speak(speakText, TextToSpeech.QUEUE_ADD, null, "msg-${System.currentTimeMillis()}")
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun loadPrefs() {
        val p = prefs()
        sessionIdInput.setText(p.getString("sessionId", "voice-bridge-session"))
        clientIdInput.setText(p.getString("clientId", "android-client"))
        speakSwitch.isChecked = p.getBoolean("speakEnabled", true)
    }

    private fun savePrefs() {
        prefs().edit()
            .putString("sessionId", sessionIdInput.text?.toString()?.trim().orEmpty())
            .putString("clientId", clientIdInput.text?.toString()?.trim().orEmpty())
            .putBoolean("speakEnabled", speakSwitch.isChecked)
            .apply()
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
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            refreshRouteInfo()
        }
    }
}
