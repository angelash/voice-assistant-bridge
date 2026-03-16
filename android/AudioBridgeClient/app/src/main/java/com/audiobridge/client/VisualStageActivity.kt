package com.audiobridge.client

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.audiobridge.client.conversation.BridgeEndpointInfo
import com.audiobridge.client.conversation.ConversationState
import com.audiobridge.client.conversation.ConversationSubmitRequest
import com.audiobridge.client.conversation.LongReplyDecisionRequired
import com.audiobridge.client.conversation.RoleMessage
import com.audiobridge.client.conversation.RoleSource
import com.audiobridge.client.conversation.SharedConversationEngine
import com.audiobridge.client.ui.EyeAvatarView
import com.iflytek.sparkchain.core.LogLvl
import com.iflytek.sparkchain.core.SparkChain
import com.iflytek.sparkchain.core.SparkChainConfig
import com.iflytek.sparkchain.core.asr.ASR
import com.iflytek.sparkchain.core.asr.AsrCallbacks
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VisualStageActivity : AppCompatActivity() {
    private companion object {
        private const val TAG = "VisualStageActivity"
        private const val PREFS_NAME = "audiobridge"
        private const val LAN_BASE_URL = "http://10.3.91.22:8765"
        private const val PUBLIC_BASE_URL = "http://voice-bridge.iepose.cn"
        private const val LAN_WIFI_SSID = "4399"
        private const val LAN_ROUTE_IPV4_PREFIX_BYTES = 3
        private const val SUBTITLE_HISTORY_MAX = 5

        private const val REQ_RECORD_AUDIO = 2001
        private const val REQ_LOCATION = 2002

        private const val STT_SAMPLE_RATE = 16000
        private const val STT_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val STT_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val LONG_REPLY_MAX_LISTEN_ATTEMPTS = 5
        private const val LONG_REPLY_SUMMARY_MAX_CHARS = 90

        // iFlytek SparkChain credentials (as requested to hardcode)
        private const val XFYUN_APP_ID = "5dd63117"
        private const val XFYUN_API_SECRET = "6eb631b964b8e0c9585e6426cf0949b5"
        private const val XFYUN_API_KEY = "c4d5af6436da6ac341a39e532042232c"
    }

    private enum class SpeechPurpose { USER_MESSAGE, LONG_REPLY_DECISION }

    private enum class LongReplyChoice { SUMMARY, ORIGINAL, OTHER }

    private data class PendingLongReply(
        val request: LongReplyDecisionRequired,
        var decisionListenAttempts: Int = 0,
    )

    private data class RouteDecision(
        val endpoint: BridgeEndpointInfo,
        val wifiSsid: String?,
        val activeWifiIpv4: String?,
        val lanBaseIpv4: String?,
    )

    private lateinit var statusText: TextView
    private lateinit var eyeAvatarView: EyeAvatarView
    private lateinit var localSubtitleText: TextView
    private lateinit var openclawSubtitleText: TextView
    private lateinit var inputText: EditText
    private lateinit var sendButton: Button
    private lateinit var sttButton: Button
    private lateinit var closeButton: Button

    private var tts: TextToSpeech? = null
    private var ttsSpeaking = false

    private var sessionId: String = "voice-bridge-session"
    private var clientId: String = "android-client"

    private val localMessages = ArrayDeque<String>()
    private val openclawMessages = ArrayDeque<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val conversationEngine = SharedConversationEngine.engine
    private var currentConversationState = ConversationState.IDLE

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

    private val routeProbeClient = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1200, TimeUnit.MILLISECONDS)
        .build()

    private val conversationListener = object : com.audiobridge.client.conversation.BridgeConversationEngine.Listener {
        override fun onStateChanged(state: ConversationState) {
            runOnUiThread {
                currentConversationState = state
                updateStatus(
                    when (state) {
                        ConversationState.IDLE -> "Idle"
                        ConversationState.SENDING -> "Sending..."
                        ConversationState.WAITING_OPENCLAW -> "Waiting OpenClaw..."
                        ConversationState.RETRYING -> "Retrying..."
                        ConversationState.DELIVERED -> "Delivered"
                        ConversationState.FAILED -> "Failed"
                    },
                    isError = state == ConversationState.FAILED,
                )
                refreshEyeMode()
            }
        }

        override fun onRoleMessage(message: RoleMessage) {
            runOnUiThread {
                if (message.source == RoleSource.LOCAL_OPERATOR) {
                    pushRoleLine(localMessages, message.textDisplay)
                    renderRoleMessages()
                    return@runOnUiThread
                }

                pushRoleLine(openclawMessages, message.textDisplay)
                renderRoleMessages()
                if (!message.requiresLongDecision) {
                    speak(message.textRaw)
                }
            }
        }

        override fun onSystemNotice(notice: com.audiobridge.client.conversation.SystemNotice) {
            runOnUiThread {
                updateStatus(notice.text, isError = notice.isError)
                if (notice.isError) {
                    eyeAvatarView.setMode(EyeAvatarView.Mode.FAILED)
                }
            }
        }

        override fun onLongReplyDecisionRequired(request: LongReplyDecisionRequired) {
            runOnUiThread {
                beginLongReplyDecision(request)
            }
        }
    }

    private val asrCallbacks = object : AsrCallbacks {
        override fun onResult(asrResult: ASR.ASRResult, userTag: Any?) {
            val status = asrResult.status
            val textRaw = asrResult.bestMatchText?.trim().orEmpty()
            if (textRaw.isNotBlank()) {
                lastAsrText = textRaw
            }
            val purpose = currentSpeechPurpose

            when (status) {
                0, 1 -> runOnUiThread { updateStatus(if (purpose == SpeechPurpose.LONG_REPLY_DECISION) "Listening choice..." else "Recognizing...") }
                2 -> {
                    sttListening = false
                    stopAudioCapture()
                    runOnUiThread {
                        sttButton.text = "语音"
                        updateStatus(if (purpose == SpeechPurpose.LONG_REPLY_DECISION) "Choice recognized" else "Speech recognized")
                    }
                    emitSpeechResult(textRaw.ifBlank { lastAsrText }, purpose)
                }
                else -> runOnUiThread { updateStatus("Recognizing...") }
            }
        }

        override fun onError(asrError: ASR.ASRError, userTag: Any?) {
            val purpose = currentSpeechPurpose
            sttListening = false
            stopAudioCapture()
            runOnUiThread { sttButton.text = "语音" }

            if (purpose == SpeechPurpose.LONG_REPLY_DECISION && isPendingLongReplyActive()) {
                sttFinished.set(true)
                runOnUiThread { updateStatus("Waiting your choice...") }
                scheduleLongReplyDecisionListening(delayMs = 700)
                return
            }

            sttFinished.set(true)
            runOnUiThread { updateStatus("STT failed: ${asrError.code}", isError = true) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_visual_stage)

        statusText = findViewById(R.id.visualStatusText)
        eyeAvatarView = findViewById(R.id.eyeAvatarView)
        localSubtitleText = findViewById(R.id.localSubtitleText)
        openclawSubtitleText = findViewById(R.id.openclawSubtitleText)
        inputText = findViewById(R.id.visualInputText)
        sendButton = findViewById(R.id.visualSendButton)
        sttButton = findViewById(R.id.visualSttButton)
        closeButton = findViewById(R.id.closeVisualButton)

        initTts()
        loadSessionClient()
        renderRoleMessages()
        refreshEyeMode()

        conversationEngine.addListener(conversationListener)

        sendButton.setOnClickListener {
            if (isPendingLongReplyActive()) {
                clearPendingLongReply()
            }
            sendTextToBridge(inputText.text?.toString().orEmpty())
        }
        sttButton.setOnClickListener {
            if (sttListening) stopSpeechToText() else startSpeechToText(SpeechPurpose.USER_MESSAGE)
        }
        closeButton.setOnClickListener {
            finish()
        }
        inputText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTextToBridge(inputText.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }

        if (!hasLocationPermission()) {
            requestLocationPermission()
        }
        if (!hasRecordAudioPermission()) {
            requestRecordAudioPermission()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        conversationEngine.removeListener(conversationListener)
        clearPendingLongReply()
        stopSpeechToText()
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
            } catch (_: Exception) {
                // ignore
            }
            sparkInitialized = false
        }
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun loadSessionClient() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        sessionId = intent.getStringExtra("sessionId")
            ?.trim()
            .orEmpty()
            .ifBlank { prefs.getString("sessionId", "voice-bridge-session").orEmpty() }
            .ifBlank { "voice-bridge-session" }
        clientId = intent.getStringExtra("clientId")
            ?.trim()
            .orEmpty()
            .ifBlank { prefs.getString("clientId", "android-client").orEmpty() }
            .ifBlank { "android-client" }
    }

    private fun sendTextToBridge(text: String) {
        val input = text.trim()
        if (input.isBlank()) {
            updateStatus("Please enter text")
            return
        }
        inputText.setText("")
        val endpoint = resolveBridgeEndpoint(allowNetworkProbe = true)
        updateStatus("Sending (${endpoint.mode}, wifi=${endpoint.wifiSsid ?: "N/A"})")
        conversationEngine.submitText(
            ConversationSubmitRequest(
                text = input,
                sessionId = sessionId,
                clientId = clientId,
                endpoint = endpoint,
            )
        )
    }

    private fun pushRoleLine(queue: ArrayDeque<String>, line: String) {
        if (line.isBlank()) return
        if (queue.isNotEmpty() && queue.last() == line) return
        queue.addLast(line)
        while (queue.size > SUBTITLE_HISTORY_MAX) {
            queue.removeFirst()
        }
    }

    private fun renderRoleMessages() {
        localSubtitleText.text = if (localMessages.isEmpty()) "(暂无)" else localMessages.joinToString("\n")
        openclawSubtitleText.text = if (openclawMessages.isEmpty()) "(暂无)" else openclawMessages.joinToString("\n")
    }

    private fun updateStatus(text: String, isError: Boolean = false) {
        statusText.text = text
        statusText.setTextColor(if (isError) 0xFFFFB4B4.toInt() else 0xFFE9EAED.toInt())
    }

    private fun refreshEyeMode() {
        if (ttsSpeaking) {
            eyeAvatarView.setMode(EyeAvatarView.Mode.SPEAKING)
            return
        }
        val mode = when (currentConversationState) {
            ConversationState.SENDING -> EyeAvatarView.Mode.SENDING
            ConversationState.WAITING_OPENCLAW, ConversationState.RETRYING -> EyeAvatarView.Mode.WAITING
            ConversationState.FAILED -> EyeAvatarView.Mode.FAILED
            else -> EyeAvatarView.Mode.IDLE
        }
        eyeAvatarView.setMode(mode)
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

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
            }
        }
    }

    private fun speak(text: String, force: Boolean = false) {
        val speakText = normalizeForSpeech(text)
        if (speakText.isBlank()) return
        if (!force && speakText.isBlank()) return

        val utteranceId = "visual-${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    ttsSpeaking = true
                    refreshEyeMode()
                }
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    ttsSpeaking = false
                    refreshEyeMode()
                }
            }

            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    ttsSpeaking = false
                    refreshEyeMode()
                }
            }
        })
        tts?.speak(speakText, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    private fun normalizedLength(text: String): Int {
        return normalizeForDisplay(text).replace(Regex("""\s+"""), "").length
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
        return System.currentTimeMillis() < pending.request.deadlineAtMs
    }

    private fun clearPendingLongReply() {
        pendingLongReply = null
        pendingLongReplyTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        pendingLongReplyTimeoutTask = null
        pendingLongReplyListenTask?.let { mainHandler.removeCallbacks(it) }
        pendingLongReplyListenTask = null
    }

    private fun beginLongReplyDecision(request: LongReplyDecisionRequired) {
        if (normalizedLength(request.originalRaw) <= 30) return
        clearPendingLongReply()
        val pending = PendingLongReply(request = request)
        pendingLongReply = pending
        updateStatus("内容较长，请在30秒内说“简报”或“原文”")
        speak("这条回复内容较长。请在三十秒内说简报或原文。", force = true)

        val timeoutTask = Runnable {
            val active = pendingLongReply
            if (active == null || active.request.id != pending.request.id) return@Runnable
            clearPendingLongReply()
            updateStatus("30秒未选择，已略过该条语音播报", isError = true)
        }
        pendingLongReplyTimeoutTask = timeoutTask
        val delay = (request.deadlineAtMs - System.currentTimeMillis()).coerceAtLeast(1000L)
        mainHandler.postDelayed(timeoutTask, delay)
        scheduleLongReplyDecisionListening(delayMs = 900)
    }

    private fun scheduleLongReplyDecisionListening(delayMs: Long) {
        val pending = pendingLongReply ?: return
        if (System.currentTimeMillis() >= pending.request.deadlineAtMs) return
        pendingLongReplyListenTask?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            val active = pendingLongReply ?: return@Runnable
            if (active.request.id != pending.request.id) return@Runnable
            if (System.currentTimeMillis() >= active.request.deadlineAtMs) return@Runnable
            if (sttListening) return@Runnable
            if (active.decisionListenAttempts >= LONG_REPLY_MAX_LISTEN_ATTEMPTS) return@Runnable
            active.decisionListenAttempts += 1
            startSpeechToText(SpeechPurpose.LONG_REPLY_DECISION)
        }
        pendingLongReplyListenTask = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun handleLongReplyDecisionSpeech(spoken: String) {
        val pending = pendingLongReply
        if (pending == null || System.currentTimeMillis() >= pending.request.deadlineAtMs) {
            clearPendingLongReply()
            sendTextToBridge(spoken)
            return
        }
        when (classifyLongReplyChoice(spoken)) {
            LongReplyChoice.SUMMARY -> {
                clearPendingLongReply()
                updateStatus("Summarizing...")
                Thread {
                    try {
                        val summary = conversationEngine.requestLocalSummary(
                            pending.request,
                            maxChars = LONG_REPLY_SUMMARY_MAX_CHARS,
                        )
                        val spokenSummary = summary.ifBlank { pending.request.originalDisplay }
                        speak(spokenSummary, force = true)
                        runOnUiThread { updateStatus("Brief spoken") }
                    } catch (_: Exception) {
                        speak(pending.request.originalRaw, force = true)
                        runOnUiThread { updateStatus("Summary failed, original spoken", isError = true) }
                    }
                }.start()
            }

            LongReplyChoice.ORIGINAL -> {
                clearPendingLongReply()
                speak(pending.request.originalRaw, force = true)
                updateStatus("Original spoken")
            }

            LongReplyChoice.OTHER -> {
                clearPendingLongReply()
                sendTextToBridge(spoken)
            }
        }
    }

    private fun ensureSparkInitialized(): Boolean {
        if (sparkInitialized) return true
        return try {
            val config = SparkChainConfig.builder()
                .appID(XFYUN_APP_ID)
                .apiKey(XFYUN_API_KEY)
                .apiSecret(XFYUN_API_SECRET)
                .logPath("${filesDir.absolutePath}/sparkchain-visual.log")
                .logLevel(LogLvl.WARN.value)
            val ret = SparkChain.getInst().init(applicationContext, config)
            sparkInitialized = ret == 0
            if (!sparkInitialized) {
                updateStatus("STT init failed: $ret", isError = true)
            }
            sparkInitialized
        } catch (_: Exception) {
            updateStatus("STT init exception", isError = true)
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
        if (!ensureSparkInitialized()) return

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
        val ret = asrClient.start("visual-stage-$asrToken")
        if (ret != 0) {
            if (purpose == SpeechPurpose.LONG_REPLY_DECISION && isPendingLongReplyActive()) {
                scheduleLongReplyDecisionListening(delayMs = 700)
            } else {
                updateStatus("STT start failed: $ret", isError = true)
            }
            return
        }

        if (!startAudioCapture(asrClient)) {
            asrClient.stop(true)
            if (purpose == SpeechPurpose.LONG_REPLY_DECISION && isPendingLongReplyActive()) {
                scheduleLongReplyDecisionListening(delayMs = 700)
            } else {
                updateStatus("Microphone unavailable", isError = true)
            }
            return
        }

        sttListening = true
        sttButton.text = "停止"
        updateStatus(if (purpose == SpeechPurpose.LONG_REPLY_DECISION) "Listening choice..." else "Listening...")
    }

    private fun stopSpeechToText() {
        if (!sttListening) return
        sttListening = false
        sttButton.text = "语音"
        updateStatus("Processing...")
        stopAudioCapture()

        val purpose = currentSpeechPurpose
        val ret = asr?.stop(false) ?: -1
        if (ret != 0 && sttFinished.compareAndSet(false, true)) {
            val fallback = lastAsrText.trim()
            if (fallback.isNotBlank()) {
                emitSpeechResult(fallback, purpose)
            } else if (purpose == SpeechPurpose.LONG_REPLY_DECISION && isPendingLongReplyActive()) {
                scheduleLongReplyDecisionListening(delayMs = 700)
            } else {
                updateStatus("STT stop failed: $ret", isError = true)
            }
        }
    }

    private fun emitSpeechResult(text: String, purpose: SpeechPurpose) {
        if (!sttFinished.compareAndSet(false, true)) return
        val spoken = text.trim()
        if (spoken.isBlank()) {
            if (purpose == SpeechPurpose.LONG_REPLY_DECISION && isPendingLongReplyActive()) {
                scheduleLongReplyDecisionListening(delayMs = 700)
                return
            }
            updateStatus("No speech result", isError = true)
            return
        }
        if (purpose == SpeechPurpose.LONG_REPLY_DECISION) {
            handleLongReplyDecisionSpeech(spoken)
            return
        }
        runOnUiThread {
            inputText.setText(spoken)
        }
        sendTextToBridge(spoken)
    }

    private fun startAudioCapture(asrClient: ASR): Boolean {
        stopAudioCapture()
        val minBuffer = AudioRecord.getMinBufferSize(STT_SAMPLE_RATE, STT_CHANNEL, STT_ENCODING)
        if (minBuffer <= 0) return false
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                STT_SAMPLE_RATE,
                STT_CHANNEL,
                STT_ENCODING,
                minBuffer * 2,
            )
        } catch (_: Exception) {
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
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
                        audioWriting.set(false)
                        sttListening = false
                        runOnUiThread {
                            sttButton.text = "语音"
                            updateStatus("STT write failed: $ret", isError = true)
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
                runOnUiThread { updateStatus("Audio capture failed", isError = true) }
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

    private fun normalizeSsid(raw: String?): String? {
        val value = raw?.trim()?.trim('"').orEmpty()
        if (value.isBlank()) return null
        if (value.equals("<unknown ssid>", ignoreCase = true)) return null
        return value
    }

    private fun currentWifiSsidFromCapabilities(): String? {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val wifiInfo = caps.transportInfo as? WifiInfo ?: return null
        return normalizeSsid(wifiInfo.ssid)
    }

    private fun currentWifiSsid(): String? {
        if (!hasLocationPermission()) return null
        currentWifiSsidFromCapabilities()?.let { return it }
        return try {
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            normalizeSsid(manager?.connectionInfo?.ssid)
        } catch (_: Exception) {
            null
        }
    }

    private fun activeWifiIpv4(): String? {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val link = cm.getLinkProperties(active) ?: return null
        val addr = link.linkAddresses
            .mapNotNull { it.address as? Inet4Address }
            .firstOrNull { !it.isLoopbackAddress }
            ?: return null
        return addr.hostAddress
    }

    private fun sameIpv4Prefix(ipA: String, ipB: String, prefixBytes: Int): Boolean {
        val a = ipA.split('.')
        val b = ipB.split('.')
        if (a.size != 4 || b.size != 4) return false
        val checkedBytes = prefixBytes.coerceIn(1, 4)
        for (i in 0 until checkedBytes) {
            if (a[i] != b[i]) return false
        }
        return true
    }

    private fun lanBaseIpv4(): String? {
        val host = Uri.parse(LAN_BASE_URL).host ?: return null
        val parts = host.split('.')
        if (parts.size != 4) return null
        return host
    }

    private fun isLanBaseHealthy(): Boolean {
        return try {
            val req = Request.Builder().url("${LAN_BASE_URL.trimEnd('/')}/health").get().build()
            routeProbeClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveRouteDecision(allowNetworkProbe: Boolean = false): RouteDecision {
        val wifi = currentWifiSsid()
        val activeIpv4 = activeWifiIpv4()
        val lanIpv4 = lanBaseIpv4()
        val useSsidLan = wifi?.equals(LAN_WIFI_SSID, ignoreCase = true) == true
        val useSameSubnetFallback = !useSsidLan &&
            wifi == null &&
            activeIpv4 != null &&
            lanIpv4 != null &&
            sameIpv4Prefix(activeIpv4, lanIpv4, LAN_ROUTE_IPV4_PREFIX_BYTES)
        val useLanHealthProbeFallback = !useSsidLan &&
            !useSameSubnetFallback &&
            allowNetworkProbe &&
            activeIpv4 != null &&
            isLanBaseHealthy()
        val useLan = useSsidLan || useSameSubnetFallback || useLanHealthProbeFallback
        val endpoint = if (useLan) {
            BridgeEndpointInfo(mode = "LAN", baseUrl = LAN_BASE_URL, wifiSsid = wifi)
        } else {
            BridgeEndpointInfo(mode = "TUNNEL", baseUrl = PUBLIC_BASE_URL, wifiSsid = wifi)
        }
        return RouteDecision(
            endpoint = endpoint,
            wifiSsid = wifi,
            activeWifiIpv4 = activeIpv4,
            lanBaseIpv4 = lanIpv4,
        )
    }

    private fun resolveBridgeEndpoint(allowNetworkProbe: Boolean = false): BridgeEndpointInfo {
        return resolveRouteDecision(allowNetworkProbe = allowNetworkProbe).endpoint
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
        if (requestCode == REQ_RECORD_AUDIO && hasRecordAudioPermission()) {
            updateStatus("Microphone permission granted")
        }
    }
}
