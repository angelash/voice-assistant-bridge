package com.audiobridge.client

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.iflytek.sparkchain.core.LogLvl
import com.iflytek.sparkchain.core.SparkChain
import com.iflytek.sparkchain.core.SparkChainConfig
import com.iflytek.sparkchain.core.asr.ASR
import com.iflytek.sparkchain.core.asr.AsrCallbacks
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "VoiceBridgeMain"
        private const val LAN_BASE_URL = "http://10.3.91.22:8765"
        private const val PUBLIC_BASE_URL = "http://voice-bridge.iepose.cn"
        private const val LAN_WIFI_SSID = "4399"
        private const val PREFS_NAME = "audiobridge"
        private const val REQ_RECORD_AUDIO = 1001
        private const val REQ_LOCATION = 1002

        // iFlytek SparkChain credentials (as requested to hardcode)
        private const val XFYUN_APP_ID = "5dd63117"
        private const val XFYUN_API_SECRET = "6eb631b964b8e0c9585e6426cf0949b5"
        private const val XFYUN_API_KEY = "c4d5af6436da6ac341a39e532042232c"

        private const val STT_SAMPLE_RATE = 16000
        private const val STT_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val STT_ENCODING = AudioFormat.ENCODING_PCM_16BIT
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

    private var sparkInitialized = false
    private var asr: ASR? = null
    private var asrToken = 0

    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private val audioWriting = AtomicBoolean(false)
    private val sttFinished = AtomicBoolean(false)
    @Volatile
    private var sttListening = false
    @Volatile
    private var lastAsrText = ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val asrCallbacks = object : AsrCallbacks {
        override fun onResult(asrResult: ASR.ASRResult, userTag: Any?) {
            val status = asrResult.status
            val textRaw = asrResult.bestMatchText?.trim().orEmpty()
            if (textRaw.isNotBlank()) {
                lastAsrText = textRaw
            }

            when (status) {
                0, 1 -> runOnUiThread { statusText.text = "Recognizing..." }
                2 -> {
                    sttListening = false
                    stopAudioCapture()
                    runOnUiThread {
                        sttButton.text = "Speak To Text"
                        statusText.text = "Speech recognized"
                    }
                    emitSpeechResult(textRaw.ifBlank { lastAsrText })
                }
                else -> runOnUiThread { statusText.text = "Recognizing..." }
            }
        }

        override fun onError(asrError: ASR.ASRError, userTag: Any?) {
            sttListening = false
            stopAudioCapture()
            runOnUiThread {
                sttButton.text = "Speak To Text"
                statusText.text = "STT failed: ${asrError.code}"
            }
            val msg = asrError.errMsg ?: "unknown"
            appendResult("[system] STT failed: code=${asrError.code}, msg=$msg")
            sttFinished.set(true)
        }
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

        sttButton.setOnClickListener {
            if (sttListening) {
                stopSpeechToText()
            } else {
                startSpeechToText()
            }
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
        stopAudioCapture()
        try {
            asr?.stop(true)
        } catch (_: Exception) {
            // ignore
        }
        asr = null
        if (sparkInitialized) {
            try {
                SparkChain.getInst().unInit()
            } catch (e: Exception) {
                Log.w(TAG, "SparkChain unInit failed: ${e.message}")
            }
            sparkInitialized = false
        }

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
                val source = item.optString("source").trim()
                val key = "$label::$text"
                if (shown.contains(key)) continue
                shown.add(key)
                appendResult("[$label] $text")
                if (item.optString("kind") != "error" && source != "local-operator") {
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

    private fun ensureSparkInitialized(): Boolean {
        if (sparkInitialized) return true
        return try {
            val logFile = File(filesDir, "sparkchain.log")
            val config = SparkChainConfig.builder()
                .appID(XFYUN_APP_ID)
                .apiKey(XFYUN_API_KEY)
                .apiSecret(XFYUN_API_SECRET)
                .logPath(logFile.absolutePath)
                .logLevel(LogLvl.WARN.value)
            val ret = SparkChain.getInst().init(applicationContext, config)
            sparkInitialized = ret == 0
            if (!sparkInitialized) {
                appendResult("[system] SparkChain init failed: $ret")
                statusText.text = "STT init failed: $ret"
            }
            sparkInitialized
        } catch (e: Exception) {
            appendResult("[system] SparkChain init exception: ${e.message ?: "unknown"}")
            statusText.text = "STT init exception"
            false
        }
    }

    private fun ensureAsr() {
        if (asr != null) return
        asr = ASR().also {
            it.registerCallbacks(asrCallbacks)
        }
    }

    private fun startSpeechToText() {
        if (sttListening) return

        if (!hasRecordAudioPermission()) {
            requestRecordAudioPermission()
            return
        }

        if (!ensureSparkInitialized()) {
            return
        }

        ensureAsr()
        val asrClient = asr ?: return
        asrClient.language("zh_cn")
        asrClient.domain("iat")
        asrClient.accent("mandarin")
        asrClient.vinfo(true)
        asrClient.dwa("wpgs")

        lastAsrText = ""
        sttFinished.set(false)
        asrToken += 1
        val ret = asrClient.start("voice-bridge-$asrToken")
        if (ret != 0) {
            appendResult("[system] STT start failed: $ret")
            statusText.text = "STT start failed: $ret"
            sttListening = false
            return
        }

        if (!startAudioCapture(asrClient)) {
            asrClient.stop(true)
            appendResult("[system] microphone unavailable")
            statusText.text = "Microphone unavailable"
            sttListening = false
            return
        }

        sttListening = true
        sttButton.text = "Stop Listening"
        statusText.text = "Listening..."
    }

    private fun stopSpeechToText() {
        if (!sttListening) return
        sttListening = false
        sttButton.text = "Speak To Text"
        statusText.text = "Processing..."
        stopAudioCapture()
        val ret = asr?.stop(false) ?: -1
        if (ret != 0 && sttFinished.compareAndSet(false, true)) {
            val fallback = lastAsrText.trim()
            if (fallback.isNotBlank()) {
                emitSpeechResult(fallback)
            } else {
                appendResult("[system] STT stop failed: $ret")
                statusText.text = "STT stop failed: $ret"
            }
        }
    }

    private fun emitSpeechResult(text: String) {
        if (!sttFinished.compareAndSet(false, true)) return
        val spoken = text.trim()
        if (spoken.isBlank()) {
            appendResult("[system] no valid speech text")
            runOnUiThread { statusText.text = "No speech result" }
            return
        }

        runOnUiThread {
            textInput.setText(spoken)
            statusText.text = "Speech recognized"
        }
        sendTextToBridge(spoken)
    }

    private fun startAudioCapture(asrClient: ASR): Boolean {
        stopAudioCapture()

        val minBuffer = AudioRecord.getMinBufferSize(STT_SAMPLE_RATE, STT_CHANNEL, STT_ENCODING)
        if (minBuffer <= 0) {
            Log.e(TAG, "Invalid min buffer size: $minBuffer")
            return false
        }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                STT_SAMPLE_RATE,
                STT_CHANNEL,
                STT_ENCODING,
                minBuffer * 2,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Create AudioRecord failed", e)
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            recorder.release()
            return false
        }

        audioRecord = recorder
        audioWriting.set(true)
        val buffer = ByteArray(minBuffer)
        audioThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                recorder.startRecording()
                while (audioWriting.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    val payload = if (read == buffer.size) buffer.clone() else buffer.copyOf(read)
                    val ret = asrClient.write(payload)
                    if (ret != 0) {
                        Log.e(TAG, "ASR write failed: $ret")
                        audioWriting.set(false)
                        sttListening = false
                        runOnUiThread {
                            sttButton.text = "Speak To Text"
                            statusText.text = "STT write failed: $ret"
                            appendResult("[system] STT write failed: $ret")
                        }
                        try {
                            asrClient.stop(true)
                        } catch (_: Exception) {
                            // ignore
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture failed", e)
                runOnUiThread {
                    appendResult("[system] audio capture failed: ${e.message ?: "unknown"}")
                    statusText.text = "Audio capture failed"
                }
            } finally {
                try {
                    recorder.stop()
                } catch (_: Exception) {
                    // ignore
                }
            }
        }
        audioThread?.start()
        return true
    }

    private fun stopAudioCapture() {
        audioWriting.set(false)

        val thread = audioThread
        audioThread = null
        if (thread != null && thread.isAlive) {
            try {
                thread.join(500)
            } catch (_: Exception) {
                // ignore
            }
        }

        val recorder = audioRecord
        audioRecord = null
        if (recorder != null) {
            try {
                recorder.stop()
            } catch (_: Exception) {
                // ignore
            }
            try {
                recorder.release()
            } catch (_: Exception) {
                // ignore
            }
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
        if (requestCode == REQ_RECORD_AUDIO && hasRecordAudioPermission()) {
            statusText.text = "Microphone permission granted"
        }
    }
}
