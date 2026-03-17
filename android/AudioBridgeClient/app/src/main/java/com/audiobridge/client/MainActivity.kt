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
import android.net.ConnectivityManager
import android.net.Uri
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
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
import com.audiobridge.client.conversation.BridgeConversationEngine
import com.audiobridge.client.conversation.BridgeEndpointInfo
import com.audiobridge.client.conversation.ConversationUiState
import com.audiobridge.client.conversation.ConversationState
import com.audiobridge.client.conversation.ConversationSubmitRequest
import com.audiobridge.client.conversation.LongReplyDecisionRequired
import com.audiobridge.client.conversation.RoleSource
import com.audiobridge.client.conversation.SharedConversationEngine
import com.audiobridge.client.meeting.MeetingControlBus
import com.audiobridge.client.meeting.MeetingManager
import com.audiobridge.client.meeting.MeetingUiState
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
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "VoiceBridgeMain"
        private const val VIEW_ID = ConversationUiState.VIEW_MAIN
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

        private const val LONG_REPLY_MAX_LISTEN_ATTEMPTS = 5
        private const val LONG_REPLY_SUMMARY_MAX_CHARS = 90
        private const val LAN_ROUTE_IPV4_PREFIX_BYTES = 3
        private const val REMOTE_HISTORY_REFRESH_MS = 15_000L
    }

    private enum class LinkMode { LAN, TUNNEL }

    private enum class SpeechPurpose { USER_MESSAGE, LONG_REPLY_DECISION }

    private enum class LongReplyChoice { SUMMARY, ORIGINAL, OTHER }

    private data class BridgeEndpoint(
        val mode: LinkMode,
        val baseUrl: String,
        val wifiSsid: String?,
    )

    private data class RouteDecision(
        val endpoint: BridgeEndpoint,
        val wifiSsid: String?,
        val activeWifiIpv4: String?,
        val lanBaseIpv4: String?,
        val usedSameSubnetFallback: Boolean,
        val usedLanHealthProbeFallback: Boolean,
    )

    private data class PendingLongReply(
        val request: LongReplyDecisionRequired,
        var decisionListenAttempts: Int = 0,
    )

    private data class LocalMeetingHistoryRecord(
        val meetingId: String,
        val status: String,
        val createdAt: String,
        val totalSegments: Int,
    )

    private data class RemoteMeetingHistoryRecord(
        val meetingId: String,
        val status: String,
        val createdAt: String,
    )

    private lateinit var statusText: TextView
    private lateinit var routeInfoText: TextView
    private lateinit var sessionIdInput: EditText
    private lateinit var clientIdInput: EditText
    private lateinit var textInput: EditText
    private lateinit var sendTextButton: Button
    private lateinit var sttButton: Button
    private lateinit var visualModeButton: Button
    private lateinit var mainDebugPanelToggleButton: Button
    private lateinit var mainMeetingPanelToggleButton: Button
    private lateinit var mainDebugPanelContainer: LinearLayout
    private lateinit var mainMeetingPanelContainer: LinearLayout
    private lateinit var longReplyChoiceContainer: LinearLayout
    private lateinit var longReplySummaryButton: Button
    private lateinit var longReplyOriginalButton: Button
    private lateinit var textResultView: TextView
    private lateinit var speakSwitch: Switch
    private lateinit var meetingModeSwitch: Switch
    private lateinit var meetingStatusText: TextView
    private lateinit var meetingInfoText: TextView
    private lateinit var refreshMeetingHistoryButton: Button
    private lateinit var meetingHistoryText: TextView
    private lateinit var imageSectionTitle: TextView
    private lateinit var imageButtonContainer: LinearLayout
    private lateinit var captureImageButton: Button
    private lateinit var selectImageButton: Button
    private lateinit var imageUploadStatusText: TextView

    private var tts: TextToSpeech? = null
    private var ttsReady = false
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
    private var isDebugPanelExpanded = false
    private var isMeetingPanelExpanded = false
    private var suppressMeetingSwitchCallback = false
    private val meetingToggleInFlight = AtomicBoolean(false)
    private var lastMeetingHistoryRefreshMs = 0L
    private val remoteHistoryInFlight = AtomicBoolean(false)
    private val remoteHistoryLock = Any()
    private var remoteMeetingHistory = emptyList<RemoteMeetingHistoryRecord>()
    private var remoteHistoryLastFetchMs = 0L
    private var remoteHistoryLastError: String? = null
    private var remoteHistoryBaseUrl: String? = null

    private val meetingControlDelegate = object : MeetingControlBus.Delegate {
        override fun onMeetingToggleRequested(enabled: Boolean) {
            runOnUiThread {
                if (meetingToggleInFlight.get()) {
                    return@runOnUiThread
                }
                if (meetingManager.isActive == enabled) {
                    setMeetingSwitchChecked(enabled)
                    updateMeetingStatusUI()
                    return@runOnUiThread
                }
                setMeetingSwitchChecked(enabled)
                onMeetingModeToggled(enabled)
            }
        }

        override fun onMeetingRefreshRequested() {
            runOnUiThread {
                updateMeetingStatusUI()
                refreshMeetingHistoryUI(force = true)
            }
        }
    }

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
    private val conversationEngine = SharedConversationEngine.engine

    private val conversationListener = object : BridgeConversationEngine.Listener {
        override fun onStateChanged(state: ConversationState) {
            ConversationUiState.updateState(state)
            if (!ConversationUiState.isActiveView(VIEW_ID)) return
            runOnUiThread {
                statusText.text = when (state) {
                    ConversationState.IDLE -> "Idle"
                    ConversationState.SENDING -> "Sending..."
                    ConversationState.WAITING_OPENCLAW -> "Waiting OpenClaw..."
                    ConversationState.RETRYING -> "Retrying..."
                    ConversationState.DELIVERED -> "Send complete"
                    ConversationState.FAILED -> "Send failed"
                }
            }
        }

        override fun onRoleMessage(message: com.audiobridge.client.conversation.RoleMessage) {
            ConversationUiState.pushRoleMessage(message)
            appendResult("[${message.sourceLabel}] ${message.textDisplay}")
            if (!ConversationUiState.isActiveView(VIEW_ID)) return
            if (message.source == RoleSource.OPENCLAW && !message.requiresLongDecision) {
                if (!speakSwitch.isChecked) {
                    appendResult("[system] TTS is disabled, skip voice playback")
                    return
                }
                speak(message.textRaw)
            }
        }

        override fun onSystemNotice(notice: com.audiobridge.client.conversation.SystemNotice) {
            appendResult("[system] ${notice.text}")
            if (!ConversationUiState.isActiveView(VIEW_ID)) return
            if (notice.isError) {
                runOnUiThread { statusText.text = "Send failed" }
            }
        }

        override fun onLongReplyDecisionRequired(request: LongReplyDecisionRequired) {
            if (!ConversationUiState.isActiveView(VIEW_ID)) return
            runOnUiThread {
                if (!ConversationUiState.isActiveView(VIEW_ID)) return@runOnUiThread
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
        visualModeButton = findViewById(R.id.visualModeButton)
        mainDebugPanelToggleButton = findViewById(R.id.mainDebugPanelToggleButton)
        mainMeetingPanelToggleButton = findViewById(R.id.mainMeetingPanelToggleButton)
        mainDebugPanelContainer = findViewById(R.id.mainDebugPanelContainer)
        mainMeetingPanelContainer = findViewById(R.id.mainMeetingPanelContainer)
        longReplyChoiceContainer = findViewById(R.id.longReplyChoiceContainer)
        longReplySummaryButton = findViewById(R.id.longReplySummaryButton)
        longReplyOriginalButton = findViewById(R.id.longReplyOriginalButton)
        textResultView = findViewById(R.id.textResultView)
        speakSwitch = findViewById(R.id.speakSwitch)
        meetingModeSwitch = findViewById(R.id.meetingModeSwitch)
        meetingStatusText = findViewById(R.id.meetingStatusText)
        meetingInfoText = findViewById(R.id.meetingInfoText)
        refreshMeetingHistoryButton = findViewById(R.id.refreshMeetingHistoryButton)
        meetingHistoryText = findViewById(R.id.meetingHistoryText)
        
        // M5: Image capture UI elements
        imageSectionTitle = findViewById(R.id.imageSectionTitle)
        imageButtonContainer = findViewById(R.id.imageButtonContainer)
        captureImageButton = findViewById(R.id.captureImageButton)
        selectImageButton = findViewById(R.id.selectImageButton)
        imageUploadStatusText = findViewById(R.id.imageUploadStatusText)

        // Initialize meeting mode components
        initMeetingMode()
        MeetingControlBus.bind(meetingControlDelegate)
        conversationEngine.addListener(conversationListener)

        initTts()
        loadPrefs()
        refreshRouteInfo()
        applyMainPanelExpansionStates()
        speakSwitch.setOnCheckedChangeListener { _, isChecked ->
            savePrefs()
            appendResult("[system] TTS ${if (isChecked) "enabled" else "disabled"}")
        }
        mainDebugPanelToggleButton.setOnClickListener {
            isDebugPanelExpanded = !isDebugPanelExpanded
            applyMainPanelExpansionStates()
        }
        mainMeetingPanelToggleButton.setOnClickListener {
            isMeetingPanelExpanded = !isMeetingPanelExpanded
            applyMainPanelExpansionStates()
        }
        refreshMeetingHistoryButton.setOnClickListener {
            refreshMeetingHistoryUI(force = true)
        }

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
        bindEnterToSend(textInput) {
            val text = textInput.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) return@bindEnterToSend
            if (isPendingLongReplyActive()) {
                clearPendingLongReply()
            }
            sendTextToBridge(text)
        }

        sttButton.setOnClickListener {
            runCatching {
                if (sttListening) {
                    stopSpeechToText()
                } else {
                    startSpeechToText(SpeechPurpose.USER_MESSAGE)
                }
            }.onFailure { err ->
                handleSttException("button-click", err, SpeechPurpose.USER_MESSAGE)
            }
        }
        longReplySummaryButton.setOnClickListener {
            handleLongReplyDecisionButton(LongReplyChoice.SUMMARY)
        }
        longReplyOriginalButton.setOnClickListener {
            handleLongReplyDecisionButton(LongReplyChoice.ORIGINAL)
        }
        setLongReplyChoiceButtonsVisible(false)

        visualModeButton.setOnClickListener {
            savePrefs()
            ConversationUiState.setActiveView(ConversationUiState.VIEW_VISUAL)
            setLongReplyChoiceButtonsVisible(isPendingLongReplyActive())
            val intent = Intent(this, VisualStageActivity::class.java)
                .putExtra("sessionId", sessionIdInput.text?.toString()?.trim().orEmpty())
                .putExtra("clientId", clientIdInput.text?.toString()?.trim().orEmpty())
            startActivity(intent)
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
        ConversationUiState.setActiveView(VIEW_ID)
        setLongReplyChoiceButtonsVisible(isPendingLongReplyActive())
        refreshRouteInfo()
        applyMainPanelExpansionStates()
        updateMeetingStatusUI()
    }

    override fun onStop() {
        super.onStop()
        savePrefs()
    }

    override fun onDestroy() {
        super.onDestroy()
        MeetingControlBus.unbind(meetingControlDelegate)
        conversationEngine.removeListener(conversationListener)
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
        ttsReady = false
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

    private fun lanBaseIpv4(): String? {
        val host = Uri.parse(LAN_BASE_URL).host ?: return null
        val parts = host.split('.')
        if (parts.size != 4) return null
        return host
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

    private fun isLanBaseHealthy(timeoutMillis: Long = 1200L): Boolean {
        return try {
            val req = Request.Builder()
                .url("${LAN_BASE_URL.trimEnd('/')}/health")
                .get()
                .build()
            httpClient.newBuilder()
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .build()
                .newCall(req)
                .execute()
                .use { it.isSuccessful }
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
            BridgeEndpoint(LinkMode.LAN, LAN_BASE_URL, wifi)
        } else {
            BridgeEndpoint(LinkMode.TUNNEL, PUBLIC_BASE_URL, wifi)
        }
        return RouteDecision(
            endpoint = endpoint,
            wifiSsid = wifi,
            activeWifiIpv4 = activeIpv4,
            lanBaseIpv4 = lanIpv4,
            usedSameSubnetFallback = useSameSubnetFallback,
            usedLanHealthProbeFallback = useLanHealthProbeFallback,
        )
    }

    private fun resolveBridgeEndpoint(allowNetworkProbe: Boolean = false): BridgeEndpoint {
        return resolveRouteDecision(allowNetworkProbe = allowNetworkProbe).endpoint
    }

    private fun refreshRouteInfo() {
        val decision = resolveRouteDecision(allowNetworkProbe = false)
        val endpoint = decision.endpoint
        routeInfoText.text = buildString {
            appendLine("Auto Route:")
            appendLine("  current wifi: ${decision.wifiSsid ?: "N/A"}")
            appendLine("  active wifi ipv4: ${decision.activeWifiIpv4 ?: "N/A"}")
            appendLine("  location perm: ${if (hasLocationPermission()) "granted" else "missing"}")
            appendLine("  lan wifi: $LAN_WIFI_SSID")
            appendLine("  lan host ipv4: ${decision.lanBaseIpv4 ?: "N/A"}")
            appendLine("  same-subnet fallback: ${if (decision.usedSameSubnetFallback) "used" else "not-used"}")
            appendLine("  lan health-probe fallback: ${if (decision.usedLanHealthProbeFallback) "used" else "not-used"}")
            appendLine("  selected mode: ${endpoint.mode}")
            appendLine("  selected base: ${endpoint.baseUrl}")
            appendLine("  lan base: $LAN_BASE_URL")
            appendLine("  public base: $PUBLIC_BASE_URL")
        }
    }

    private fun applyMainPanelExpansionStates() {
        mainDebugPanelContainer.visibility = if (isDebugPanelExpanded) View.VISIBLE else View.GONE
        mainMeetingPanelContainer.visibility = if (isMeetingPanelExpanded) View.VISIBLE else View.GONE
        updateMainPanelToggleTexts()
    }

    private fun updateMainPanelToggleTexts() {
        mainDebugPanelToggleButton.text = if (isDebugPanelExpanded) {
            "\u8BCA\u65AD \u25B2"
        } else {
            "\u8BCA\u65AD \u25BE"
        }
        val meetingState = when {
            meetingToggleInFlight.get() -> "\u8BE6\u60C5(\u5904\u7406\u4E2D)"
            meetingManager.isActive -> "\u8BE6\u60C5(\u4F1A\u8BAE\u4E2D)"
            else -> "\u8BE6\u60C5"
        }
        val arrow = if (isMeetingPanelExpanded) "\u25B2" else "\u25BE"
        mainMeetingPanelToggleButton.text = "$meetingState $arrow"
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
        setLongReplyChoiceButtonsVisible(false)
    }

    private fun setLongReplyChoiceButtonsVisible(visible: Boolean) {
        longReplyChoiceContainer.visibility = if (visible && ConversationUiState.isActiveView(VIEW_ID)) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun handleLongReplyDecisionButton(choice: LongReplyChoice) {
        val pending = pendingLongReply
        if (pending == null || System.currentTimeMillis() >= pending.request.deadlineAtMs) {
            clearPendingLongReply()
            runOnUiThread { statusText.text = "Long-reply choice expired" }
            return
        }
        applyLongReplyChoice(pending, choice)
    }

    private fun applyLongReplyChoice(
        pending: PendingLongReply,
        choice: LongReplyChoice,
    ) {
        when (choice) {
            LongReplyChoice.SUMMARY -> {
                clearPendingLongReply()
                appendResult("[system] 接线员：已选择简报播报")
                runOnUiThread { statusText.text = "Summarizing..." }
                Thread {
                    try {
                        val summary = conversationEngine.requestLocalSummary(
                            pending.request,
                            maxChars = LONG_REPLY_SUMMARY_MAX_CHARS,
                        )
                        val spokenSummary = summary.ifBlank { pending.request.originalDisplay }
                        speak(spokenSummary, force = true)
                        runOnUiThread { statusText.text = "Brief spoken" }
                    } catch (e: Exception) {
                        appendResult("[system] 简报失败，改为原文播报: ${e.message ?: "unknown"}")
                        speak(pending.request.originalRaw, force = true)
                        runOnUiThread { statusText.text = "Original spoken" }
                    }
                }.start()
            }

            LongReplyChoice.ORIGINAL -> {
                clearPendingLongReply()
                appendResult("[system] 接线员：已选择原文播报")
                speak(pending.request.originalRaw, force = true)
                runOnUiThread { statusText.text = "Original spoken" }
            }

            LongReplyChoice.OTHER -> Unit
        }
    }

    private fun beginLongReplyDecision(request: LongReplyDecisionRequired) {
        val display = normalizeForDisplay(request.originalRaw)
        if (display.isBlank()) return

        clearPendingLongReply()
        val pending = PendingLongReply(request = request)
        pendingLongReply = pending
        setLongReplyChoiceButtonsVisible(true)

        appendResult("[system] 内容较长，请在30秒内说“简报”或“原文”")
        speak("这条回复内容较长。请在三十秒内说简报或原文。", force = true)
        runOnUiThread { statusText.text = "Waiting long-reply choice..." }

        val timeoutTask = Runnable {
            val active = pendingLongReply
            if (active == null || active.request.id != pending.request.id) return@Runnable
            clearPendingLongReply()
            appendResult("[system] 30秒未选择，已略过该条语音播报")
            runOnUiThread { statusText.text = "Long reply skipped" }
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

    private fun sendTextToBridge(text: String) {
        val input = text.trim()
        if (input.isBlank()) return
        textInput.setText("")
        savePrefs()
        appendResult("[user] $input")
        val endpoint = resolveBridgeEndpoint(allowNetworkProbe = true)
        val sessionId = sessionIdInput.text?.toString()?.trim().orEmpty().ifBlank { "voice-bridge-session" }
        val clientId = clientIdInput.text?.toString()?.trim().orEmpty().ifBlank { "android-client" }
        runOnUiThread {
            statusText.text = "Sending (${endpoint.mode}, wifi=${endpoint.wifiSsid ?: "N/A"})"
            refreshRouteInfo()
        }
        conversationEngine.submitText(
            ConversationSubmitRequest(
                text = input,
                sessionId = sessionId,
                clientId = clientId,
                endpoint = BridgeEndpointInfo(
                    mode = endpoint.mode.name,
                    baseUrl = endpoint.baseUrl,
                    wifiSsid = endpoint.wifiSsid,
                ),
            )
        )
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

    private fun handleSttException(
        stage: String,
        throwable: Throwable,
        purpose: SpeechPurpose,
    ) {
        val msg = throwable.message ?: throwable::class.java.simpleName
        Log.e(TAG, "STT $stage exception", throwable)
        if (purpose == SpeechPurpose.LONG_REPLY_DECISION && isPendingLongReplyActive()) {
            scheduleLongReplyDecisionListening(delayMs = 700)
            return
        }
        sttListening = false
        runOnUiThread {
            sttButton.text = "Speak To Text"
            statusText.text = "STT error: $msg"
        }
        appendResult("[system] STT $stage exception: $msg")
    }

    private fun startSpeechToText(purpose: SpeechPurpose) {
        try {
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
        } catch (err: Throwable) {
            handleSttException("start", err, purpose)
        }
    }

    private fun stopSpeechToText() {
        try {
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
        } catch (err: Throwable) {
            handleSttException("stop", err, currentSpeechPurpose)
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
        if (pending == null || System.currentTimeMillis() >= pending.request.deadlineAtMs) {
            clearPendingLongReply()
            runOnUiThread { statusText.text = "Speech recognized" }
            sendTextToBridge(spoken)
            return
        }

        when (classifyLongReplyChoice(spoken)) {
            LongReplyChoice.SUMMARY -> {
                applyLongReplyChoice(pending, LongReplyChoice.SUMMARY)
            }

            LongReplyChoice.ORIGINAL -> {
                applyLongReplyChoice(pending, LongReplyChoice.ORIGINAL)
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
        ttsReady = false
        try {
            tts?.shutdown()
        } catch (_: Exception) {
            // ignore
        }
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS init failed: status=$status")
                runOnUiThread { appendResult("[system] TTS init failed: $status") }
                return@TextToSpeech
            }
            val zhResult = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (zhResult == TextToSpeech.LANG_MISSING_DATA || zhResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                val fallback = tts?.setLanguage(Locale.CHINESE) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "TTS language unsupported: zhResult=$zhResult fallback=$fallback")
                    runOnUiThread { appendResult("[system] TTS Chinese voice unavailable on device") }
                }
            }
            ttsReady = true
            Log.i(TAG, "TTS initialized, engine=${tts?.defaultEngine}")
        }
    }

    private fun initMeetingMode() {
        // Initialize MeetingManager
        meetingManager = MeetingManager(this)
        if (!meetingManager.initialize()) {
            Log.e(TAG, "Failed to initialize MeetingManager")
            meetingModeSwitch.isEnabled = false
            meetingStatusText.text = "Storage error"
            publishMeetingUiSnapshot()
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
                publishMeetingUiSnapshot()
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
                        publishMeetingUiSnapshot()
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
                        publishMeetingUiSnapshot()
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
                publishMeetingUiSnapshot()
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

            val clientId = clientIdInput.text?.toString()?.trim().orEmpty().ifBlank { "android-client" }
            meetingModeSwitch.isEnabled = false
            meetingStatusText.text = "Starting meeting..."
            publishMeetingUiSnapshot()

            Thread {
                val endpoint = resolveBridgeEndpoint(allowNetworkProbe = true)
                val baseUrl = endpoint.baseUrl
                activeMeetingBaseUrl = baseUrl
                updateMeetingNetworkTargets(baseUrl)
                val remoteMeetingId = createRemoteMeetingOnServer(baseUrl, clientId)

                runOnUiThread {
                    if (remoteMeetingId == null) {
                        appendResult("[meeting] Failed to create remote meeting")
                        activeMeetingBaseUrl = null
                        setMeetingSwitchChecked(false)
                        meetingStatusText.text = "Failed to start"
                        publishMeetingUiSnapshot()
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
                            publishMeetingUiSnapshot()
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
                        publishMeetingUiSnapshot()
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
        meetingModeSwitch.isEnabled = false
        meetingStatusText.text = "Ending meeting..."
        publishMeetingUiSnapshot()
        meetingManager.endMeeting()
        wakeWordController.onMeetingModeChanged(false)
        diskWriterConsumer.enabled = false
        kwsConsumer.enabled = false
        kwsConsumer.flush()
        stopMeetingAudioCapture()
        meetingToggleInFlight.set(false)
        meetingModeSwitch.isEnabled = true
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
        val stats = meetingManager.getStorageStats()
        val detail = StringBuilder()
        val quickStatus = if (meetingManager.isActive) {
            val meetingId = meetingManager.meetingId ?: "unknown"
            detail.append("Meeting: $meetingId\n")
            detail.append("Wake word: ${wakeWordStateMachine.getStateDescription()}\n")
            val activeConsumers = pcmBus.getActiveConsumerNames()
            if (activeConsumers.isNotEmpty()) {
                detail.append("Audio: ${activeConsumers.joinToString(", ")}\n")
            }
            "Active | ${meetingId.take(14)}"
        } else {
            if (sttListening) {
                detail.append("STT: Listening...\n")
            }
            if (pcmBus.isRunning) {
                val activeConsumers = pcmBus.getActiveConsumerNames()
                if (activeConsumers.isNotEmpty()) {
                    detail.append("Audio: ${activeConsumers.joinToString(", ")}\n")
                }
            }
            if (::uploadQueueManager.isInitialized && uploadQueueManager.isQueueActive) {
                detail.append(
                    "Upload: ${uploadQueueManager.uploadedCount}/" +
                        "${uploadQueueManager.pendingCount + uploadQueueManager.uploadedCount + uploadQueueManager.failedCount}\n",
                )
            }
            if (detail.isEmpty()) "Idle" else "Idle | background active"
        }

        detail.append(
            "Storage: %.1f MB, %d meetings, oldest: %d min".format(
                stats.totalMb,
                stats.totalMeetings,
                stats.oldestMeetingAgeMs / 60000,
            ),
        )
        meetingStatusText.text = quickStatus
        meetingInfoText.text = detail.toString()
        updateMainPanelToggleTexts()
        publishMeetingUiSnapshot()
        refreshMeetingHistoryUI(force = false)
    }

    private fun publishMeetingUiSnapshot() {
        MeetingUiState.update(
            active = meetingManager.isActive,
            busy = meetingToggleInFlight.get(),
            meetingId = meetingManager.meetingId,
            statusText = meetingStatusText.text?.toString().orEmpty(),
            infoText = meetingInfoText.text?.toString().orEmpty(),
        )
    }

    private fun refreshMeetingHistoryUI(force: Boolean) {
        if (!::meetingManager.isInitialized) return
        val now = System.currentTimeMillis()
        if (!force && now - lastMeetingHistoryRefreshMs < 2000L) {
            return
        }
        lastMeetingHistoryRefreshMs = now
        val localRecords = buildLocalMeetingHistoryRecords()
        renderMeetingHistoryText(localRecords)

        val needRemoteFetch = synchronized(remoteHistoryLock) {
            force ||
                remoteMeetingHistory.isEmpty() ||
                now - remoteHistoryLastFetchMs > REMOTE_HISTORY_REFRESH_MS
        }
        if (needRemoteFetch) {
            fetchRemoteMeetingHistoryAsync(forceNetworkProbe = force)
        }
    }

    private fun buildLocalMeetingHistoryRecords(): List<LocalMeetingHistoryRecord> {
        val meetings = meetingManager.listLocalMeetings()
        if (meetings.isEmpty()) return emptyList()
        return meetings.mapNotNull { meeting ->
            val meetingId = meeting.optString("meeting_id", "").trim()
            if (meetingId.isBlank()) return@mapNotNull null
            LocalMeetingHistoryRecord(
                meetingId = meetingId,
                status = meeting.optString("status", "unknown"),
                createdAt = meeting.optString("created_at", ""),
                totalSegments = meeting.optInt("total_segments", 0),
            )
        }
    }

    private fun renderMeetingHistoryText(localRecords: List<LocalMeetingHistoryRecord>) {
        val remoteSnapshot: List<RemoteMeetingHistoryRecord>
        val remoteError: String?
        val remoteFetchedAt: Long
        val remoteBaseUrl: String?
        synchronized(remoteHistoryLock) {
            remoteSnapshot = remoteMeetingHistory
            remoteError = remoteHistoryLastError
            remoteFetchedAt = remoteHistoryLastFetchMs
            remoteBaseUrl = remoteHistoryBaseUrl
        }

        val remoteById = remoteSnapshot.associateBy { it.meetingId }
        val renderedLines = mutableListOf<String>()
        var index = 1

        localRecords.take(10).forEach { local ->
            val remote = remoteById[local.meetingId]
            val source = if (remote != null) "LR" else "L "
            val createdAt = formatHistoryTime(local.createdAt).ifBlank {
                formatHistoryTime(remote?.createdAt.orEmpty())
            }.ifBlank { "n/a" }
            val remoteStatus = remote?.let { " | R:${it.status}" }.orEmpty()
            renderedLines += "$index. [$source] $createdAt | L:${local.status} seg=${local.totalSegments}$remoteStatus | ${local.meetingId.take(18)}"
            index += 1
        }

        remoteSnapshot
            .filter { remote -> localRecords.none { it.meetingId == remote.meetingId } }
            .take(10)
            .forEach { remote ->
                val createdAt = formatHistoryTime(remote.createdAt).ifBlank { "n/a" }
                renderedLines += "$index. [ R] $createdAt | R:${remote.status} | ${remote.meetingId.take(18)}"
                index += 1
            }

        if (renderedLines.isEmpty()) {
            renderedLines += "(no local/server meeting history)"
        }

        val remoteState = when {
            remoteError != null -> {
                val host = remoteBaseUrl?.trimEnd('/').orEmpty()
                if (host.isBlank()) {
                    "server: failed (${remoteError.take(80)})"
                } else {
                    "server: failed @ $host (${remoteError.take(80)})"
                }
            }
            remoteFetchedAt > 0L -> {
                val ageSec = ((System.currentTimeMillis() - remoteFetchedAt).coerceAtLeast(0L)) / 1000L
                val host = remoteBaseUrl?.trimEnd('/').orEmpty()
                if (host.isBlank()) {
                    "server: synced ${remoteSnapshot.size} items (${ageSec}s ago)"
                } else {
                    "server: synced ${remoteSnapshot.size} items from $host (${ageSec}s ago)"
                }
            }
            else -> "server: not fetched yet"
        }
        renderedLines += ""
        renderedLines += remoteState
        meetingHistoryText.text = renderedLines.joinToString("\n")
    }

    private fun formatHistoryTime(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""

        val normalized = if (value.endsWith("Z", ignoreCase = true)) {
            value.dropLast(1) + "+00:00"
        } else {
            value
        }
        val parserPatterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss.SSSXXX",
            "yyyy-MM-dd HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss",
        )
        for (pattern in parserPatterns) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    if (!pattern.contains("XXX")) {
                        // Old records may omit timezone but are generated in UTC on backend.
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                }
                val parsed = parser.parse(normalized) ?: continue
                val output = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
                    timeZone = TimeZone.getDefault()
                }
                return output.format(parsed)
            } catch (_: Exception) {
                // try next pattern
            }
        }
        return value
            .replace("T", " ")
            .replace("Z", "")
            .take(19)
    }

    private fun fetchRemoteMeetingHistoryAsync(forceNetworkProbe: Boolean) {
        if (remoteHistoryInFlight.getAndSet(true)) {
            return
        }
        Thread {
            val route = resolveBridgeEndpoint(allowNetworkProbe = forceNetworkProbe)
            val baseUrl = (activeMeetingBaseUrl ?: route.baseUrl).trimEnd('/')
            var parsed = emptyList<RemoteMeetingHistoryRecord>()
            var fetchError: String? = null
            try {
                val request = Request.Builder()
                    .url("$baseUrl/v2/meetings?limit=20")
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    val json = try {
                        JSONObject(payload.ifBlank { "{}" })
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    if (!response.isSuccessful || !json.optBoolean("ok", false)) {
                        fetchError = json.optString("error").ifBlank {
                            "http_${response.code}"
                        }
                    } else {
                        val meetings = json.optJSONArray("meetings")
                        val items = mutableListOf<RemoteMeetingHistoryRecord>()
                        if (meetings != null) {
                            for (i in 0 until meetings.length()) {
                                val obj = meetings.optJSONObject(i) ?: continue
                                val meetingId = obj.optString("meeting_id", "").trim()
                                if (meetingId.isBlank()) continue
                                items += RemoteMeetingHistoryRecord(
                                    meetingId = meetingId,
                                    status = obj.optString("status", "unknown"),
                                    createdAt = obj.optString("created_at", ""),
                                )
                            }
                        }
                        parsed = items
                    }
                }
            } catch (e: Exception) {
                fetchError = e.message ?: "request_failed"
            } finally {
                synchronized(remoteHistoryLock) {
                    remoteHistoryBaseUrl = baseUrl
                    if (fetchError == null) {
                        remoteMeetingHistory = parsed
                        remoteHistoryLastFetchMs = System.currentTimeMillis()
                        remoteHistoryLastError = null
                    } else {
                        remoteHistoryLastError = fetchError
                    }
                }
                remoteHistoryInFlight.set(false)
                runOnUiThread {
                    renderMeetingHistoryText(buildLocalMeetingHistoryRecords())
                }
            }
        }.start()
    }

    private fun speak(text: String, force: Boolean = false) {
        if (!ConversationUiState.isActiveView(VIEW_ID)) {
            Log.i(TAG, "Skip TTS speak: inactive view")
            return
        }
        if (!force && !speakSwitch.isChecked) return
        val speakText = normalizeForSpeech(text)
        if (speakText.isBlank()) return
        Log.i(TAG, "TTS speak requested, force=$force, length=${speakText.length}")
        if (!ttsReady || tts == null) {
            Log.w(TAG, "TTS not ready, reinitializing")
            appendResult("[system] TTS not ready, reinitializing")
            initTts()
            return
        }

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

        val ret = tts?.speak(speakText, TextToSpeech.QUEUE_ADD, null, utteranceId) ?: TextToSpeech.ERROR
        Log.i(TAG, "TTS speak return code=$ret")
        if (ret != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS speak failed: ret=$ret")
            appendResult("[system] TTS speak failed: $ret")
            ttsReady = false
            initTts()
        }
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

    private fun bindEnterToSend(
        input: EditText,
        onSend: () -> Unit,
    ) {
        input.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_ENTER || event == null) {
                return@setOnKeyListener false
            }
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.isShiftPressed) return@setOnKeyListener false
                    onSend()
                    true
                }
                KeyEvent.ACTION_UP -> !event.isShiftPressed
                else -> false
            }
        }
    }
    
    private fun hideImageUploadUI() {
        imageSectionTitle.visibility = View.GONE
        imageButtonContainer.visibility = View.GONE
        imageUploadStatusText.visibility = View.GONE
    }
}
