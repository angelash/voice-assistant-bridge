package com.audiobridge.client

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.audiobridge.client.audio.AudioConfig
import com.audiobridge.client.audio.AudioRecordCapture
import com.audiobridge.client.audio.DiskWriterConsumer
import com.audiobridge.client.audio.KwsDetectorConsumer
import com.audiobridge.client.audio.PcmDistributionBus
import com.audiobridge.client.audio.SttForwarderConsumer
import com.audiobridge.client.meeting.MeetingManager
import com.audiobridge.client.upload.ImageUploadManager
import com.audiobridge.client.upload.UploadQueueManager
import com.audiobridge.client.upload.UploadStatus
import com.audiobridge.client.wakeword.WakeWordController
import com.audiobridge.client.wakeword.WakeWordStateMachine
import com.audiobridge.client.wakeword.WakewordEventReporter
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
import java.util.UUID
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
        private const val REQ_CAMERA = 1003
        private const val REQ_GALLERY = 1004
        private const val REQ_CAMERA_PERMISSION = 1005

        // iFlytek SparkChain credentials (as requested to hardcode)
        private const val XFYUN_APP_ID = "5dd63117"
        private const val XFYUN_API_SECRET = "6eb631b964b8e0c9585e6426cf0949b5"
        private const val XFYUN_API_KEY = "c4d5af6436da6ac341a39e532042232c"

        private const val STT_SAMPLE_RATE = 16000
        private const val STT_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val STT_ENCODING = AudioFormat.ENCODING_PCM_16BIT

        private const val LONG_REPLY_LIMIT = 30
        private const val LONG_REPLY_TIMEOUT_MS = 30_000L
        private const val LONG_REPLY_MAX_LISTEN_ATTEMPTS = 5
        private const val LONG_REPLY_SUMMARY_MAX_CHARS = 90
    }

    private enum class LinkMode { LAN, TUNNEL }

    private enum class SpeechPurpose { USER_MESSAGE, LONG_REPLY_DECISION }

    private enum class LongReplyChoice { SUMMARY, ORIGINAL, OTHER }

    private data class BridgeEndpoint(
        val mode: LinkMode,
        val baseUrl: String,
        val wifiSsid: String?,
    )

    private data class PendingLongReply(
        val id: String,
        val endpoint: BridgeEndpoint,
        val sessionId: String,
        val clientId: String,
        val originalRaw: String,
        val originalDisplay: String,
        val deadlineAtMs: Long,
        var decisionListenAttempts: Int = 0,
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
    private lateinit var meetingModeSwitch: Switch
    private lateinit var meetingStatusText: TextView
    private lateinit var meetingInfoText: TextView
    private lateinit var imageSectionTitle: TextView
    private lateinit var imageButtonContainer: LinearLayout
    private lateinit var captureImageButton: Button
    private lateinit var selectImageButton: Button
    private lateinit var imageUploadStatusText: TextView

    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Meeting mode components
    private lateinit var meetingManager: MeetingManager
    private lateinit var pcmBus: PcmDistributionBus
    private lateinit var wakeWordStateMachine: WakeWordStateMachine
    private lateinit var wakeWordController: WakeWordController
    private lateinit var wakewordEventReporter: WakewordEventReporter
    private lateinit var uploadQueueManager: UploadQueueManager
    private lateinit var imageUploadManager: ImageUploadManager
    private lateinit var diskWriterConsumer: DiskWriterConsumer
    private lateinit var kwsConsumer: KwsDetectorConsumer
    private var sttForwarderConsumer: SttForwarderConsumer? = null
    private var audioCapture: AudioRecordCapture? = null
    private var pendingUploadMeetingId: String? = null
    private var pendingFinalizeMeetingId: String? = null
    private var activeMeetingBaseUrl: String? = null
    private var suppressMeetingSwitchCallback = false
    private val meetingToggleInFlight = AtomicBoolean(false)

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
    @Volatile
    private var currentSpeechPurpose = SpeechPurpose.USER_MESSAGE

    @Volatile
    private var pendingLongReply: PendingLongReply? = null
    private var pendingLongReplyTimeoutTask: Runnable? = null
    private var pendingLongReplyListenTask: Runnable? = null
    
    // M5: Image capture
    private var pendingCameraImageUri: Uri? = null

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
            val purpose = currentSpeechPurpose

            when (status) {
                0, 1 -> {
                    runOnUiThread {
                        statusText.text = if (purpose == SpeechPurpose.LONG_REPLY_DECISION) {
                            "Listening choice..."
                        } else {
                            "Recognizing..."
                        }
                    }
                }
                2 -> {
                    sttListening = false
                    // Disable STT forwarder and stop unified capture if not in meeting mode
                    sttForwarderConsumer?.enabled = false
                    stopUnifiedAudioCapture()
                    runOnUiThread {
                        sttButton.text = "Speak To Text"
                        statusText.text = if (purpose == SpeechPurpose.LONG_REPLY_DECISION) {
                            "Choice recognized"
                        } else {
                            "Speech recognized"
                        }
                    }
                    emitSpeechResult(textRaw.ifBlank { lastAsrText }, purpose)
                }
                else -> runOnUiThread { statusText.text = "Recognizing..." }
            }
        }

        override fun onError(asrError: ASR.ASRError, userTag: Any?) {
            val purpose = currentSpeechPurpose
            sttListening = false
            // Disable STT forwarder and stop unified capture if not in meeting mode
            sttForwarderConsumer?.enabled = false
            stopUnifiedAudioCapture()
            runOnUiThread {
                sttButton.text = "Speak To Text"
            }

            if (purpose == SpeechPurpose.LONG_REPLY_DECISION && isPendingLongReplyActive()) {
                sttFinished.set(true)
                runOnUiThread { statusText.text = "Waiting your choice..." }
                scheduleLongReplyDecisionListening(delayMs = 700)
                return
            }

            runOnUiThread { statusText.text = "STT failed: ${asrError.code}" }
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
        meetingModeSwitch = findViewById(R.id.meetingModeSwitch)
        meetingStatusText = findViewById(R.id.meetingStatusText)
        meetingInfoText = findViewById(R.id.meetingInfoText)
        
        // M5: Image capture UI elements
        imageSectionTitle = findViewById(R.id.imageSectionTitle)
        imageButtonContainer = findViewById(R.id.imageButtonContainer)
        captureImageButton = findViewById(R.id.captureImageButton)
        selectImageButton = findViewById(R.id.selectImageButton)
        imageUploadStatusText = findViewById(R.id.imageUploadStatusText)

        // Initialize meeting mode components
        initMeetingMode()

        initTts()
        loadPrefs()
        refreshRouteInfo()

        sendTextButton.setOnClickListener {
            val text = textInput.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) {
                statusText.text = "Please enter text"
                return@setOnClickListener
            }
            if (isPendingLongReplyActive()) {
                clearPendingLongReply()
            }
            sendTextToBridge(text)
        }

        sttButton.setOnClickListener {
            if (sttListening) {
                stopSpeechToText()
            } else {
                startSpeechToText(SpeechPurpose.USER_MESSAGE)
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
        clearPendingLongReply()
        stopUnifiedAudioCapture()
        stopAudioCapture()  // Legacy cleanup

        // Clean up meeting mode
        if (meetingManager.isActive) {
            meetingManager.endMeeting()
        }
        stopMeetingAudioCapture()
        wakeWordStateMachine.destroy()
        
        // M2: Stop upload queue processing
        if (::uploadQueueManager.isInitialized) {
            uploadQueueManager.stopProcessing()
        }

        // Flush STT forwarder
        sttForwarderConsumer?.flush()

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

    private fun normalizedLength(text: String): Int {
        return normalizeForDisplay(text).replace(Regex("""\s+"""), "").length
    }

    private fun isLongOpenClawReply(textRaw: String): Boolean {
        return normalizedLength(textRaw) > LONG_REPLY_LIMIT
    }

    private fun classifyLongReplyChoice(spoken: String): LongReplyChoice {
        val normalized = normalizeForDisplay(spoken)
            .lowercase(Locale.getDefault())
            .replace(Regex("""\s+"""), "")
        val originalKeywords = listOf("原文", "全文", "照读", "不简报", "不要简报", "不简化", "完整")
        val summaryKeywords = listOf("简报", "摘要", "总结", "概括", "简化", "简短", "要点", "简版")
        if (originalKeywords.any { normalized.contains(it) }) return LongReplyChoice.ORIGINAL
        if (summaryKeywords.any { normalized.contains(it) }) return LongReplyChoice.SUMMARY
        return LongReplyChoice.OTHER
    }

    private fun isPendingLongReplyActive(): Boolean {
        val pending = pendingLongReply ?: return false
        return System.currentTimeMillis() < pending.deadlineAtMs
    }

    private fun clearPendingLongReply() {
        pendingLongReply = null
        pendingLongReplyTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        pendingLongReplyTimeoutTask = null
        pendingLongReplyListenTask?.let { mainHandler.removeCallbacks(it) }
        pendingLongReplyListenTask = null
    }

    private fun beginLongReplyDecision(
        endpoint: BridgeEndpoint,
        sessionId: String,
        clientId: String,
        originalRaw: String,
    ) {
        val display = normalizeForDisplay(originalRaw)
        if (display.isBlank()) return

        clearPendingLongReply()
        val pending = PendingLongReply(
            id = UUID.randomUUID().toString(),
            endpoint = endpoint,
            sessionId = sessionId,
            clientId = clientId,
            originalRaw = originalRaw,
            originalDisplay = display,
            deadlineAtMs = System.currentTimeMillis() + LONG_REPLY_TIMEOUT_MS,
        )
        pendingLongReply = pending

        appendResult("[system] 内容较长，请在30秒内说“简报”或“原文”")
        speak("这条回复内容较长。请在三十秒内说简报或原文。", force = true)
        runOnUiThread { statusText.text = "Waiting long-reply choice..." }

        val timeoutTask = Runnable {
            val active = pendingLongReply
            if (active == null || active.id != pending.id) return@Runnable
            clearPendingLongReply()
            appendResult("[system] 30秒未选择，已略过该条语音播报")
            runOnUiThread { statusText.text = "Long reply skipped" }
        }
        pendingLongReplyTimeoutTask = timeoutTask
        mainHandler.postDelayed(timeoutTask, LONG_REPLY_TIMEOUT_MS)

        scheduleLongReplyDecisionListening(delayMs = 900)
    }

    private fun scheduleLongReplyDecisionListening(delayMs: Long) {
        val pending = pendingLongReply ?: return
        if (System.currentTimeMillis() >= pending.deadlineAtMs) return
        pendingLongReplyListenTask?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            val active = pendingLongReply ?: return@Runnable
            if (active.id != pending.id) return@Runnable
            if (System.currentTimeMillis() >= active.deadlineAtMs) return@Runnable
            if (sttListening) return@Runnable
            if (active.decisionListenAttempts >= LONG_REPLY_MAX_LISTEN_ATTEMPTS) return@Runnable
            active.decisionListenAttempts += 1
            startSpeechToText(SpeechPurpose.LONG_REPLY_DECISION)
        }
        pendingLongReplyListenTask = task
        mainHandler.postDelayed(task, delayMs)
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
                        renderStatusMessages(terminal, shown, endpoint, sessionId, clientId)
                    } else {
                        appendResult("[system] timeout waiting final reply")
                    }
                } else {
                    renderStatusMessages(submitResp, shown, endpoint, sessionId, clientId)
                }

                runOnUiThread { statusText.text = "Send complete" }
            } catch (e: Exception) {
                appendResult("[system] send failed: ${e.message ?: "unknown"}")
                runOnUiThread { statusText.text = "Send failed" }
            }
        }.start()
    }

    private fun renderStatusMessages(
        payload: JSONObject,
        shown: MutableSet<String>,
        endpoint: BridgeEndpoint,
        sessionId: String,
        clientId: String,
    ) {
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
                if (item.optString("kind") == "error") continue
                if (source == "local-operator") continue

                if (source == "openclaw" && isLongOpenClawReply(textRaw)) {
                    beginLongReplyDecision(endpoint, sessionId, clientId, textRaw)
                } else {
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

    private fun requestLocalSummary(pending: PendingLongReply): String {
        val body = JSONObject()
            .put("text", pending.originalRaw)
            .put("session_id", pending.sessionId)
            .put("client_id", pending.clientId)
            .put("source", "android")
            .put("max_chars", LONG_REPLY_SUMMARY_MAX_CHARS)
        val resp = postJson(pending.endpoint, "/v1/operator/summarize", body)
        if (!resp.optBoolean("ok", false)) {
            throw IllegalStateException(resp.optString("error").ifBlank { "summary_failed" })
        }
        return resp.optString("summary").trim()
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

    private fun startSpeechToText(purpose: SpeechPurpose) {
        if (sttListening) return

        if (!hasRecordAudioPermission()) {
            requestRecordAudioPermission()
            return
        }

        if (!ensureSparkInitialized()) {
            return
        }

        currentSpeechPurpose = purpose
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
            sttListening = false
            if (purpose == SpeechPurpose.LONG_REPLY_DECISION && isPendingLongReplyActive()) {
                scheduleLongReplyDecisionListening(delayMs = 700)
            } else {
                appendResult("[system] STT start failed: $ret")
                statusText.text = "STT start failed: $ret"
            }
            return
        }

        // Use unified audio capture via PcmDistributionBus
        if (!startUnifiedAudioCapture()) {
            asrClient.stop(true)
            sttListening = false
            if (purpose == SpeechPurpose.LONG_REPLY_DECISION && isPendingLongReplyActive()) {
                scheduleLongReplyDecisionListening(delayMs = 700)
            } else {
                appendResult("[system] microphone unavailable")
                statusText.text = "Microphone unavailable"
            }
            return
        }

        sttListening = true
        sttButton.text = "Stop Listening"
        statusText.text = if (purpose == SpeechPurpose.LONG_REPLY_DECISION) {
            "Listening choice..."
        } else {
            "Listening..."
        }
    }

    private fun stopSpeechToText() {
        if (!sttListening) return
        sttListening = false
        sttButton.text = "Speak To Text"
        statusText.text = "Processing..."
        
        // Disable STT forwarder and stop unified capture if not in meeting mode
        sttForwarderConsumer?.enabled = false
        stopUnifiedAudioCapture()
        
        val purpose = currentSpeechPurpose
        val ret = asr?.stop(false) ?: -1
        if (ret != 0 && sttFinished.compareAndSet(false, true)) {
            val fallback = lastAsrText.trim()
            if (fallback.isNotBlank()) {
                emitSpeechResult(fallback, purpose)
            } else if (purpose == SpeechPurpose.LONG_REPLY_DECISION && isPendingLongReplyActive()) {
                scheduleLongReplyDecisionListening(delayMs = 700)
            } else {
                appendResult("[system] STT stop failed: $ret")
                statusText.text = "STT stop failed: $ret"
            }
        }
    }

    private fun emitSpeechResult(text: String, purpose: SpeechPurpose) {
        if (!sttFinished.compareAndSet(false, true)) return
        val spoken = text.trim()
        if (spoken.isBlank()) {
            if (purpose == SpeechPurpose.LONG_REPLY_DECISION && isPendingLongReplyActive()) {
                runOnUiThread { statusText.text = "Waiting your choice..." }
                scheduleLongReplyDecisionListening(delayMs = 700)
                return
            }
            appendResult("[system] no valid speech text")
            runOnUiThread { statusText.text = "No speech result" }
            return
        }

        if (purpose == SpeechPurpose.LONG_REPLY_DECISION) {
            handleLongReplyDecisionSpeech(spoken)
            return
        }

        runOnUiThread {
            textInput.setText(spoken)
            statusText.text = "Speech recognized"
        }
        sendTextToBridge(spoken)
    }

    private fun handleLongReplyDecisionSpeech(spoken: String) {
        val pending = pendingLongReply
        if (pending == null || System.currentTimeMillis() >= pending.deadlineAtMs) {
            clearPendingLongReply()
            runOnUiThread { statusText.text = "Speech recognized" }
            sendTextToBridge(spoken)
            return
        }

        when (classifyLongReplyChoice(spoken)) {
            LongReplyChoice.SUMMARY -> {
                clearPendingLongReply()
                appendResult("[system] 接线员：已选择简报播报")
                runOnUiThread { statusText.text = "Summarizing..." }
                Thread {
                    try {
                        val summary = requestLocalSummary(pending)
                        val spokenSummary = summary.ifBlank { pending.originalDisplay }
                        speak(spokenSummary, force = true)
                        runOnUiThread { statusText.text = "Brief spoken" }
                    } catch (e: Exception) {
                        appendResult("[system] 简报失败，改为原文播报: ${e.message ?: "unknown"}")
                        speak(pending.originalRaw, force = true)
                        runOnUiThread { statusText.text = "Original spoken" }
                    }
                }.start()
            }

            LongReplyChoice.ORIGINAL -> {
                clearPendingLongReply()
                appendResult("[system] 接线员：已选择原文播报")
                speak(pending.originalRaw, force = true)
                runOnUiThread { statusText.text = "Original spoken" }
            }

            LongReplyChoice.OTHER -> {
                clearPendingLongReply()
                runOnUiThread {
                    textInput.setText(spoken)
                    statusText.text = "Speech recognized"
                }
                sendTextToBridge(spoken)
            }
        }
    }

    /**
     * Start unified audio capture via PcmDistributionBus.
     * This is the single audio capture chain used by both STT and meeting mode.
     * 
     * Strategy:
     * - If meeting mode is active, audio capture is already running - just enable STT consumer
     * - If meeting mode is not active, start a dedicated STT-only capture
     */
    private fun startUnifiedAudioCapture(): Boolean {
        // Check if audio capture is already running (meeting mode)
        if (audioCapture?.isRunning == true) {
            Log.i(TAG, "Reusing existing meeting audio capture for STT")
            sttForwarderConsumer?.enabled = true
            return true
        }

        // Start a new audio capture for STT-only mode
        audioCapture = AudioRecordCapture()
        val capture = audioCapture!!

        if (!capture.start()) {
            Log.e(TAG, "Failed to start STT audio capture")
            audioCapture = null
            return false
        }

        // Enable STT forwarder before starting distribution
        sttForwarderConsumer?.enabled = true

        // Connect capture to distribution bus
        pcmBus.startDistribution(capture)
        
        Log.i(TAG, "Started STT-only audio capture via unified bus")
        return true
    }

    /**
     * Stop unified audio capture.
     * Only stops if not in meeting mode (to preserve meeting recording).
     */
    private fun stopUnifiedAudioCapture() {
        // Don't stop if meeting mode is active
        if (meetingManager.isActive) {
            Log.i(TAG, "Meeting mode active, keeping audio capture running")
            return
        }

        // Stop the STT-only audio capture
        pcmBus.stopDistribution()
        audioCapture?.stop()
        audioCapture = null
        
        Log.i(TAG, "Stopped STT-only audio capture")
    }

    // Legacy method kept for reference - now replaced by unified approach
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

    private fun initMeetingMode() {
        // Initialize MeetingManager
        meetingManager = MeetingManager(this)
        if (!meetingManager.initialize()) {
            Log.e(TAG, "Failed to initialize MeetingManager")
            meetingModeSwitch.isEnabled = false
            meetingStatusText.text = "Storage error"
        }

        // Initialize PCM Distribution Bus
        pcmBus = PcmDistributionBus()

        // Initialize wake word components
        wakeWordStateMachine = WakeWordStateMachine()
        wakeWordController = WakeWordController(wakeWordStateMachine)

        val initialMeetingBaseUrl = resolveBridgeEndpoint().baseUrl
        
        // Initialize wakeword event reporter (M2)
        wakewordEventReporter = WakewordEventReporter(initialMeetingBaseUrl, httpClient)
        
        // Initialize upload queue manager (M2)
        uploadQueueManager = UploadQueueManager(initialMeetingBaseUrl, httpClient)
        
        // Initialize image upload manager (M5)
        val deviceId = try {
            android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        imageUploadManager = ImageUploadManager(initialMeetingBaseUrl, deviceId, httpClient)
        
        // Setup image upload callbacks
        imageUploadManager.onTaskStatusChanged = { task ->
            runOnUiThread {
                when (task.status) {
                    ImageUploadManager.ImageTask.Status.UPLOADED -> {
                        appendResult("[image] Photo ${task.seq} uploaded")
                    }
                    ImageUploadManager.ImageTask.Status.FAILED -> {
                        appendResult("[image] Photo ${task.seq} failed: ${task.lastError}")
                    }
                    else -> {}
                }
                updateImageUploadStatus()
            }
        }
        
        imageUploadManager.onQueueProgress = { pending, uploaded, failed ->
            runOnUiThread {
                imageUploadStatusText.text = "Images: $uploaded uploaded, $pending pending"
            }
        }
        
        // Setup image capture buttons
        captureImageButton.setOnClickListener {
            if (!hasCameraPermission()) {
                requestCameraPermission()
            } else {
                dispatchTakePictureIntent()
            }
        }
        
        selectImageButton.setOnClickListener {
            dispatchSelectImageIntent()
        }
        
        // Setup upload queue callbacks
        uploadQueueManager.onTaskStatusChanged = { task ->
            runOnUiThread {
                when (task.status) {
                    UploadQueueManager.UploadTask.Status.UPLOADED -> {
                        appendResult("[upload] Segment ${task.seq} uploaded")
                    }
                    UploadQueueManager.UploadTask.Status.FAILED -> {
                        appendResult("[upload] Segment ${task.seq} failed: ${task.lastError}")
                    }
                    else -> {}
                }
                updateMeetingStatusUI()
            }
        }
        
        uploadQueueManager.onQueueProgress = { pending, uploaded, failed ->
            runOnUiThread {
                meetingInfoText.text = "Upload: $uploaded done, $pending pending, $failed failed"
            }
        }
        
        uploadQueueManager.onAllTasksComplete = {
            val uploadedMeetingId = pendingUploadMeetingId
            if (uploadedMeetingId != null) {
                val failed = uploadQueueManager.failedCount
                if (failed > 0) {
                    runOnUiThread {
                        appendResult("[upload] Completed with $failed failed segments, keep local data for retry")
                        meetingInfoText.text = "Upload failed: $failed segments"
                        updateMeetingStatusUI()
                    }
                    pendingUploadMeetingId = null
                    if (pendingFinalizeMeetingId == uploadedMeetingId) {
                        pendingFinalizeMeetingId = null
                        finalizeRemoteMeetingAsync(uploadedMeetingId, triggerTranscription = false)
                    }
                } else {
                    runOnUiThread {
                        appendResult("[upload] All segments uploaded")
                        meetingInfoText.text = "All uploads complete"
                    }
                    val marked = meetingManager.markMeetingUploaded(uploadedMeetingId)
                    val deleted = meetingManager.cleanupUploadedMeetings(7)
                    Log.i(TAG, "Upload complete for $uploadedMeetingId, marked=$marked, cleaned=$deleted")
                    if (deleted > 0) {
                        runOnUiThread {
                            appendResult("[cleanup] Deleted $deleted uploaded meetings (retention=7 days)")
                            updateMeetingStatusUI()
                        }
                    }
                    pendingUploadMeetingId = null

                    if (pendingFinalizeMeetingId == uploadedMeetingId) {
                        pendingFinalizeMeetingId = null
                        finalizeRemoteMeetingAsync(uploadedMeetingId)
                    }
                }
            }
        }

        // Create consumers
        diskWriterConsumer = DiskWriterConsumer(meetingManager)
        kwsConsumer = KwsDetectorConsumer()
        
        // Create STT forwarder with resampling (48kHz -> 16kHz)
        sttForwarderConsumer = SttForwarderConsumer(
            onPcmCallback = { data16k ->
                // Forward resampled 16kHz PCM to ASR
                asr?.let { asrClient ->
                    val ret = asrClient.write(data16k)
                    if (ret != 0) {
                        Log.e(TAG, "ASR write failed via bus: $ret")
                    }
                }
            },
            sourceSampleRate = AudioConfig.SAMPLE_RATE, // 48000
            targetSampleRate = STT_SAMPLE_RATE           // 16000
        )

        // Register consumers with the bus
        pcmBus.registerConsumer(diskWriterConsumer)
        pcmBus.registerConsumer(kwsConsumer)
        pcmBus.registerConsumer(sttForwarderConsumer!!)

        // Wire wake word detection
        kwsConsumer.onWakeWordDetected = {
            Log.i(TAG, "Wake word detected!")
            runOnUiThread {
                Toast.makeText(this, "Wake word detected!", Toast.LENGTH_SHORT).show()
            }
            wakeWordController.onWakeWordDetected()
            
            // M2: Report wakeword event to server
            meetingManager.meetingId?.let { meetingId ->
                wakewordEventReporter.reportWakeWordDetected(meetingId)
            }
        }

        // Wire wake word state changes
        wakeWordStateMachine.onStateChanged = { oldState, newState ->
            runOnUiThread {
                updateMeetingStatusUI()
            }
            
            // M2: Report state transitions to server
            meetingManager.meetingId?.let { meetingId ->
                when (newState) {
                    WakeWordStateMachine.State.COMMAND_WINDOW -> {
                        wakewordEventReporter.reportCommandWindowStarted(meetingId)
                    }
                    WakeWordStateMachine.State.COOLDOWN -> {
                        wakewordEventReporter.reportCooldownStarted(meetingId)
                    }
                    WakeWordStateMachine.State.LISTENING -> {
                        if (oldState == WakeWordStateMachine.State.COMMAND_WINDOW) {
                            wakewordEventReporter.reportCommandWindowEnded(meetingId, false)
                        } else if (oldState == WakeWordStateMachine.State.COOLDOWN) {
                            wakewordEventReporter.reportCooldownEnded(meetingId)
                        }
                    }
                    else -> {}
                }
            }
        }

        // Wire MeetingManager callbacks
        meetingManager.onMeetingStarted = { meetingId ->
            Log.i(TAG, "Meeting started: $meetingId")
            runOnUiThread {
                appendResult("[meeting] Started: $meetingId")
                updateMeetingStatusUI()
                showImageUploadUI()
            }
        }

        meetingManager.onMeetingEnded = { meetingId ->
            Log.i(TAG, "Meeting ended: $meetingId")
            runOnUiThread {
                appendResult("[meeting] Ended: $meetingId")
                updateMeetingStatusUI()
                hideImageUploadUI()
            }

            pendingFinalizeMeetingId = meetingId
            activeMeetingBaseUrl?.let { uploadQueueManager.setBaseUrl(it) }
            
            // M2: Trigger upload of all segments after meeting ends
            val manifest = meetingManager.getUploadManifest(meetingId)
            if (manifest != null) {
                val segments = manifest.optJSONArray("segments")
                var enqueuedCount = 0
                if (segments != null && segments.length() > 0) {
                    for (i in 0 until segments.length()) {
                        val seg = segments.optJSONObject(i)
                        if (seg == null) continue
                        val seq = seg.optInt("seq")
                        val fileName = seg.optString("file")
                        val file = meetingManager.getMeetingAudioFile(meetingId, fileName)
                        if (file.exists()) {
                            uploadQueueManager.enqueue(
                                meetingId = meetingId,
                                seq = seq,
                                file = file,
                                checksum = computeFileChecksum(file)
                            )
                            enqueuedCount++
                        }
                    }
                    if (enqueuedCount > 0) {
                        pendingUploadMeetingId = meetingId
                    } else {
                        runOnUiThread {
                            appendResult("[upload] No valid segment files found, keep local data for investigation")
                        }
                        Log.w(TAG, "No valid segment files to enqueue for meeting $meetingId")
                        pendingUploadMeetingId = null
                        pendingFinalizeMeetingId = null
                        finalizeRemoteMeetingAsync(meetingId, triggerTranscription = false)
                    }
                    Log.i(TAG, "Queued $enqueuedCount/${segments.length()} segments for upload")
                } else {
                    meetingManager.markMeetingUploaded(meetingId)
                    val deleted = meetingManager.cleanupUploadedMeetings(7)
                    Log.i(TAG, "No segments in manifest; marked uploaded and cleaned $deleted meetings")
                    pendingUploadMeetingId = null
                    pendingFinalizeMeetingId = null
                    finalizeRemoteMeetingAsync(meetingId, triggerTranscription = false)
                }
            } else {
                runOnUiThread {
                    appendResult("[upload] No manifest found, skip upload and keep local data")
                }
                Log.w(TAG, "No upload manifest found for meeting $meetingId")
                pendingUploadMeetingId = null
                pendingFinalizeMeetingId = null
                finalizeRemoteMeetingAsync(meetingId, triggerTranscription = false)
            }
        }

        meetingManager.onSegmentSealed = { segmentId, seq, file ->
            Log.d(TAG, "Segment sealed: $segmentId, size=${file.length()}")
            runOnUiThread {
                meetingInfoText.text = "Segment $seq saved: ${file.length() / 1024}KB"
            }
        }

        meetingManager.onError = { message ->
            Log.e(TAG, "MeetingManager error: $message")
            runOnUiThread {
                Toast.makeText(this, "Meeting error: $message", Toast.LENGTH_SHORT).show()
            }
        }

        // Wire meeting mode switch
        meetingModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressMeetingSwitchCallback) {
                return@setOnCheckedChangeListener
            }
            onMeetingModeToggled(isChecked)
        }

        updateMeetingStatusUI()
    }
    
    private fun setMeetingSwitchChecked(checked: Boolean) {
        suppressMeetingSwitchCallback = true
        meetingModeSwitch.isChecked = checked
        suppressMeetingSwitchCallback = false
    }

    private fun updateMeetingNetworkTargets(baseUrl: String) {
        wakewordEventReporter.setBaseUrl(baseUrl)
        uploadQueueManager.setBaseUrl(baseUrl)
        imageUploadManager.setBaseUrl(baseUrl)
    }

    private fun createRemoteMeetingOnServer(baseUrl: String, clientId: String): String? {
        val normalizedBase = baseUrl.trimEnd('/')
        val jsonType = "application/json; charset=utf-8".toMediaType()
        val createBody = JSONObject().put("client_id", clientId)
        val createRequest = Request.Builder()
            .url("$normalizedBase/v2/meetings")
            .post(createBody.toString().toRequestBody(jsonType))
            .build()

        try {
            httpClient.newCall(createRequest).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                val json = try {
                    JSONObject(payload.ifBlank { "{}" })
                } catch (_: Exception) {
                    JSONObject()
                }

                val meetingId = when {
                    response.isSuccessful && json.optBoolean("ok", false) -> json.optString("meeting_id", "")
                    response.code == 409 && json.optString("error") == "active_meeting_exists" ->
                        json.optString("active_meeting_id", "")
                    else -> ""
                }.trim()

                if (meetingId.isBlank()) {
                    Log.e(TAG, "Create remote meeting failed: code=${response.code}, payload=$payload")
                    return null
                }

                val modeOnOk = setRemoteMeetingMode(normalizedBase, meetingId, enabled = true)
                if (!modeOnOk) {
                    Log.e(TAG, "Failed to enable remote meeting mode for $meetingId")
                    return null
                }

                return meetingId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Create remote meeting exception: ${e.message}", e)
            return null
        }
    }

    private fun setRemoteMeetingMode(baseUrl: String, meetingId: String, enabled: Boolean): Boolean {
        val normalizedBase = baseUrl.trimEnd('/')
        val jsonType = "application/json; charset=utf-8".toMediaType()
        val modeBody = JSONObject().put("mode", if (enabled) "on" else "off")
        val modeRequest = Request.Builder()
            .url("$normalizedBase/v2/meetings/$meetingId/mode")
            .post(modeBody.toString().toRequestBody(jsonType))
            .build()

        return try {
            httpClient.newCall(modeRequest).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                val json = try {
                    JSONObject(payload.ifBlank { "{}" })
                } catch (_: Exception) {
                    JSONObject()
                }
                val ok = response.isSuccessful && json.optBoolean("ok", false)
                if (!ok) {
                    Log.e(TAG, "Set remote mode failed: code=${response.code}, payload=$payload")
                }
                ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "Set remote mode exception: ${e.message}", e)
            false
        }
    }

    private fun triggerRemoteTranscription(baseUrl: String, meetingId: String) {
        val normalizedBase = baseUrl.trimEnd('/')
        val jsonType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url("$normalizedBase/v2/meetings/$meetingId/transcription:run")
            .post("{}".toRequestBody(jsonType))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    runOnUiThread { appendResult("[transcription] queued for meeting $meetingId") }
                } else {
                    val json = try {
                        JSONObject(payload.ifBlank { "{}" })
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    val err = json.optString("error", "unknown")
                    if (response.code == 409 && err == "job_in_progress") {
                        runOnUiThread { appendResult("[transcription] already in progress for $meetingId") }
                    } else {
                        Log.w(TAG, "Transcription trigger failed: code=${response.code}, payload=$payload")
                        runOnUiThread { appendResult("[transcription] trigger failed: $err") }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Transcription trigger exception: ${e.message}")
            runOnUiThread { appendResult("[transcription] trigger exception: ${e.message}") }
        }
    }

    private fun finalizeRemoteMeetingAsync(meetingId: String, triggerTranscription: Boolean = true) {
        val baseUrl = activeMeetingBaseUrl ?: resolveBridgeEndpoint().baseUrl
        Thread {
            val modeOffOk = setRemoteMeetingMode(baseUrl, meetingId, enabled = false)
            if (!modeOffOk) {
                runOnUiThread { appendResult("[meeting] remote mode off failed: $meetingId") }
                return@Thread
            }
            runOnUiThread { appendResult("[meeting] remote ended: $meetingId") }
            if (triggerTranscription) {
                triggerRemoteTranscription(baseUrl, meetingId)
            }
            activeMeetingBaseUrl = null
        }.start()
    }

    private fun computeFileChecksum(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun onMeetingModeToggled(enabled: Boolean) {
        if (meetingToggleInFlight.getAndSet(true)) {
            return
        }

        if (enabled) {
            if (!hasRecordAudioPermission()) {
                requestRecordAudioPermission()
                setMeetingSwitchChecked(false)
                meetingToggleInFlight.set(false)
                return
            }

            val endpoint = resolveBridgeEndpoint()
            val clientId = clientIdInput.text?.toString()?.trim().orEmpty().ifBlank { "android-client" }
            val baseUrl = endpoint.baseUrl
            activeMeetingBaseUrl = baseUrl
            updateMeetingNetworkTargets(baseUrl)
            meetingModeSwitch.isEnabled = false
            meetingStatusText.text = "Starting meeting..."

            Thread {
                val remoteMeetingId = createRemoteMeetingOnServer(baseUrl, clientId)

                runOnUiThread {
                    if (remoteMeetingId == null) {
                        appendResult("[meeting] Failed to create remote meeting")
                        activeMeetingBaseUrl = null
                        setMeetingSwitchChecked(false)
                        meetingStatusText.text = "Failed to start"
                        meetingModeSwitch.isEnabled = true
                        meetingToggleInFlight.set(false)
                        updateMeetingStatusUI()
                        return@runOnUiThread
                    }

                    val meetingId = meetingManager.startMeeting(remoteMeetingId)
                    if (meetingId != null) {
                        wakeWordController.onMeetingModeChanged(true)
                        diskWriterConsumer.enabled = true
                        kwsConsumer.enabled = true
                        if (!startMeetingAudioCapture()) {
                            appendResult("[meeting] Audio capture start failed")
                            meetingManager.endMeeting()
                            wakeWordController.onMeetingModeChanged(false)
                            diskWriterConsumer.enabled = false
                            kwsConsumer.enabled = false
                            kwsConsumer.flush()
                            stopMeetingAudioCapture()
                            setMeetingSwitchChecked(false)
                            meetingStatusText.text = "Failed to start audio"
                            activeMeetingBaseUrl = null
                            Thread {
                                setRemoteMeetingMode(baseUrl, remoteMeetingId, enabled = false)
                            }.start()
                        } else {
                            appendResult("[meeting] Remote meeting ready: $meetingId")
                        }
                    } else {
                        appendResult("[meeting] Failed to start local meeting")
                        setMeetingSwitchChecked(false)
                        meetingStatusText.text = "Failed to start"
                        activeMeetingBaseUrl = null
                        Thread {
                            setRemoteMeetingMode(baseUrl, remoteMeetingId, enabled = false)
                        }.start()
                    }

                    meetingModeSwitch.isEnabled = true
                    meetingToggleInFlight.set(false)
                    updateMeetingStatusUI()
                }
            }.start()
            return
        }

        // Stop meeting locally first; remote finalization happens after upload completion.
        meetingManager.endMeeting()
        wakeWordController.onMeetingModeChanged(false)
        diskWriterConsumer.enabled = false
        kwsConsumer.enabled = false
        kwsConsumer.flush()
        stopMeetingAudioCapture()
        meetingToggleInFlight.set(false)
        updateMeetingStatusUI()
    }

    private fun startMeetingAudioCapture(): Boolean {
        if (audioCapture?.isRunning == true) {
            return true
        }

        audioCapture = AudioRecordCapture()
        val capture = audioCapture!!

        if (!capture.start()) {
            Log.e(TAG, "Failed to start meeting audio capture")
            return false
        }

        // Connect capture to distribution bus
        pcmBus.startDistribution(capture)
        return true
    }

    private fun stopMeetingAudioCapture() {
        pcmBus.stopDistribution()
        audioCapture?.stop()
        audioCapture = null
    }

    private fun updateMeetingStatusUI() {
        val sb = StringBuilder()

        if (meetingManager.isActive) {
            val meetingId = meetingManager.meetingId ?: "unknown"
            sb.append("Meeting: $meetingId\n")
            sb.append("Wake word: ${wakeWordStateMachine.getStateDescription()}\n")

            // Show active consumers
            val activeConsumers = pcmBus.getActiveConsumerNames()
            if (activeConsumers.isNotEmpty()) {
                sb.append("Audio: ${activeConsumers.joinToString(", ")}\n")
            }

            val stats = meetingManager.getStorageStats()
            sb.append("Storage: %.1f MB in %d meetings".format(stats.totalMb, stats.totalMeetings))
        } else {
            // Show STT status when not in meeting
            if (sttListening) {
                sb.append("STT: Listening...\n")
            }
            if (pcmBus.isRunning) {
                val activeConsumers = pcmBus.getActiveConsumerNames()
                if (activeConsumers.isNotEmpty()) {
                    sb.append("Audio: ${activeConsumers.joinToString(", ")}\n")
                }
            }
            
            // M2: Show upload queue status
            if (::uploadQueueManager.isInitialized && uploadQueueManager.isQueueActive) {
                sb.append("Upload: ${uploadQueueManager.uploadedCount}/${uploadQueueManager.pendingCount + uploadQueueManager.uploadedCount + uploadQueueManager.failedCount}\n")
            }
            
            if (sb.isEmpty()) {
                sb.append("Idle")
            }
        }

        meetingStatusText.text = sb.toString()

        // Update info text with storage stats
        val stats = meetingManager.getStorageStats()
        meetingInfoText.text = "Local: %.1f MB, %d meetings, oldest: %d min".format(
            stats.totalMb,
            stats.totalMeetings,
            stats.oldestMeetingAgeMs / 60000
        )
    }

    private fun speak(text: String, force: Boolean = false) {
        if (!force && !speakSwitch.isChecked) return
        val speakText = normalizeForSpeech(text)
        if (speakText.isBlank()) return

        // Suppress wake word during TTS playback
        if (::wakeWordController.isInitialized && meetingManager.isActive) {
            wakeWordController.onTtsStarted()
        }

        // Use UTTERANCE_COMPLETE listener to resume wake word
        val utteranceId = "msg-${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // Already suppressed above
            }

            override fun onDone(utteranceId: String?) {
                // Resume wake word after TTS
                if (::wakeWordController.isInitialized && meetingManager.isActive) {
                    wakeWordController.onTtsEnded()
                }
            }

            override fun onError(utteranceId: String?) {
                // Resume wake word on error too
                if (::wakeWordController.isInitialized && meetingManager.isActive) {
                    wakeWordController.onTtsEnded()
                }
            }
        })

        tts?.speak(speakText, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun loadPrefs() {
        val p = prefs()
        sessionIdInput.setText(p.getString("sessionId", "voice-bridge-session"))
        clientIdInput.setText(p.getString("clientId", "android-client"))
        speakSwitch.isChecked = p.getBoolean("speakEnabled", true)
        // Don't restore meeting mode on restart - user should explicitly enable
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
            if (isPendingLongReplyActive() && !sttListening) {
                scheduleLongReplyDecisionListening(delayMs = 400)
            }
        }
        if (requestCode == REQ_CAMERA_PERMISSION && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            dispatchTakePictureIntent()
        }
    }
    
    // ========== M5: Image Capture and Upload ==========
    
    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERMISSION)
    }
    
    private fun dispatchTakePictureIntent() {
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (takePictureIntent.resolveActivity(packageManager) != null) {
            // Create a file for the photo
            val photoFile = File(
                getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES),
                "meeting_photo_${System.currentTimeMillis()}.jpg"
            )
            pendingCameraImageUri = Uri.fromFile(photoFile)
            
            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraImageUri)
            startActivityForResult(takePictureIntent, REQ_CAMERA)
        } else {
            Toast.makeText(this, "No camera app available", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun dispatchSelectImageIntent() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        startActivityForResult(intent, REQ_GALLERY)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            REQ_CAMERA -> {
                if (resultCode == RESULT_OK && pendingCameraImageUri != null) {
                    handleCapturedImage(pendingCameraImageUri!!)
                    pendingCameraImageUri = null
                } else {
                    Toast.makeText(this, "Photo capture cancelled", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_GALLERY -> {
                if (resultCode == RESULT_OK && data != null && data.data != null) {
                    handleCapturedImage(data.data!!)
                }
            }
        }
    }
    
    private fun handleCapturedImage(uri: Uri) {
        val meetingId = meetingManager.meetingId
        if (meetingId == null) {
            Toast.makeText(this, "No active meeting", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            // Copy image to app storage
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Toast.makeText(this, "Could not read image", Toast.LENGTH_SHORT).show()
                return
            }
            
            val imageFile = File(
                filesDir,
                "meeting_images/${meetingId}/image_${System.currentTimeMillis()}.jpg"
            )
            imageFile.parentFile?.mkdirs()
            
            inputStream.use { input ->
                imageFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            // Add to upload queue
            activeMeetingBaseUrl?.let { imageUploadManager.setBaseUrl(it) }
            val task = imageUploadManager.addImage(imageFile, meetingId, imageFile.name)
            imageUploadManager.processQueue()
            
            Toast.makeText(this, "Photo added to upload queue", Toast.LENGTH_SHORT).show()
            appendResult("[image] Photo queued for upload: ${task.imageId}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle captured image", e)
            Toast.makeText(this, "Failed to process image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateImageUploadStatus() {
        val stats = imageUploadManager.getStats()
        imageUploadStatusText.text = "Images: ${stats.uploaded} uploaded, ${stats.pending} pending, ${stats.failed} failed"
    }
    
    private fun showImageUploadUI() {
        imageSectionTitle.visibility = View.VISIBLE
        imageButtonContainer.visibility = View.VISIBLE
        imageUploadStatusText.visibility = View.VISIBLE
        updateImageUploadStatus()
    }
    
    private fun hideImageUploadUI() {
        imageSectionTitle.visibility = View.GONE
        imageButtonContainer.visibility = View.GONE
        imageUploadStatusText.visibility = View.GONE
    }
}
