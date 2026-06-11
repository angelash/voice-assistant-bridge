package com.audiobridge.client.phoneagent.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.audiobridge.client.phoneagent.data.repository.MessageRepository
import com.audiobridge.client.phoneagent.model.LocalMessageStatus
import com.audiobridge.client.phoneagent.ui.PhoneAgentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PhoneAgentCaptureService : LifecycleService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameUploadInFlight = AtomicBoolean(false)
    private lateinit var repository: MessageRepository
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraAnalysis: ImageAnalysis? = null
    private var cameraMode: String? = null
    private var lastFrameUploadAtMs = 0L
    private var wakeListening = false
    private var wakeAudioRecord: AudioRecord? = null
    private var wakeLoopJob: Job? = null
    private val wakeTranscriptionInFlight = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        repository = MessageRepository.get(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_BACKGROUND_CAPTURE -> startCameraMode(MODE_BACKGROUND_CAPTURE)
            ACTION_START_FRAME_STREAM -> startCameraMode(MODE_FRAME_STREAM)
            ACTION_STOP_CAMERA -> stopCameraMode()
            ACTION_START_WAKE_LISTENER -> startWakeListener()
            ACTION_STOP_WAKE_LISTENER -> stopWakeListener()
            ACTION_STOP_ALL -> {
                stopCameraMode()
                stopWakeListener()
                stopSelf()
            }
            else -> refreshForeground()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCameraMode()
        stopWakeListener()
        cameraExecutor.shutdownNow()
        serviceScope.cancel()
        PhoneAgentCaptureStatus.update {
            it.copy(
                cameraMode = null,
                wakeListening = false,
                statusText = "采集服务已停止",
            )
        }
        super.onDestroy()
    }

    private fun startCameraMode(mode: String) {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            val message = "缺少摄像头权限，无法启动${modeLabel(mode)}。"
            PhoneAgentCaptureStatus.setError(message)
            return
        }
        cameraMode = mode
        lastFrameUploadAtMs = 0L
        refreshForeground()
        bindCameraAnalyzer(mode)
    }

    private fun bindCameraAnalyzer(mode: String) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    cameraAnalysis?.let { provider.unbind(it) }
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(cameraExecutor) { image ->
                        handleCameraFrame(image, mode)
                    }
                    cameraAnalysis = analysis
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        analysis,
                    )
                    PhoneAgentCaptureStatus.update {
                        it.copy(
                            cameraMode = mode,
                            statusText = "${modeLabel(mode)}已启动，正在等待第一帧。",
                            lastError = null,
                        )
                    }
                    refreshForeground()
                } catch (error: Exception) {
                    val message = "${modeLabel(mode)}启动失败：${error.message ?: error.javaClass.simpleName}"
                    PhoneAgentCaptureStatus.setError(message)
                    stopCameraMode()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun handleCameraFrame(image: androidx.camera.core.ImageProxy, mode: String) {
        val now = SystemClock.elapsedRealtime()
        val interval = if (mode == MODE_FRAME_STREAM) FRAME_STREAM_INTERVAL_MS else BACKGROUND_CAPTURE_INTERVAL_MS
        if (now - lastFrameUploadAtMs < interval || !frameUploadInFlight.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastFrameUploadAtMs = now
        val file = createStreamFrameFile(mode)
        try {
            CameraFrameEncoder.writeJpeg(image, file)
        } catch (error: Exception) {
            frameUploadInFlight.set(false)
            PhoneAgentCaptureStatus.setError("视频帧保存失败：${error.message ?: error.javaClass.simpleName}")
            image.close()
            return
        } finally {
            image.close()
        }

        serviceScope.launch {
            val result = runCatching {
                repository.sendVideoFrameMessage(
                    file = file,
                    text = promptForMode(mode),
                    mimeType = "image/jpeg",
                )
            }
            result.onSuccess { message ->
                val queued = message.localStatus == LocalMessageStatus.PENDING.name &&
                    !message.errorMessage.isNullOrBlank()
                val status = if (queued) {
                    "${modeLabel(mode)}已保存一帧并进入离线队列：${message.errorMessage}"
                } else {
                    "${modeLabel(mode)}已上传一帧：${message.messageId.take(12)}"
                }
                PhoneAgentCaptureStatus.update {
                    it.copy(
                        cameraMode = mode,
                        statusText = status,
                        lastFrameAtMs = System.currentTimeMillis(),
                        lastError = if (queued) status else null,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException || error.message == "Job was cancelled") {
                    PhoneAgentCaptureStatus.update {
                        it.copy(
                            cameraMode = cameraMode,
                            statusText = if (cameraMode == null) "摄像头采集已停止" else it.statusText,
                        )
                    }
                    frameUploadInFlight.set(false)
                    return@launch
                }
                val message = "${modeLabel(mode)}上传失败：${error.message ?: error.javaClass.simpleName}"
                PhoneAgentCaptureStatus.setError(message)
            }
            frameUploadInFlight.set(false)
            refreshForeground()
        }
    }

    private fun stopCameraMode() {
        cameraMode = null
        frameUploadInFlight.set(false)
        cameraAnalysis?.let { analysis ->
            runCatching { cameraProvider?.unbind(analysis) }
        }
        cameraAnalysis = null
        cameraProvider = null
        PhoneAgentCaptureStatus.update {
            it.copy(
                cameraMode = null,
                statusText = if (wakeListening) "语音唤醒监听中" else "摄像头采集已停止",
            )
        }
        refreshForeground()
        stopIfIdle()
    }

    private fun startWakeListener() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            val message = "缺少麦克风权限，无法启动语音唤醒监听。"
            PhoneAgentCaptureStatus.setError(message)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            PhoneAgentCaptureStatus.setError("系统没有可用的语音识别服务，无法语音唤醒。")
            return
        }
        wakeListening = true
        PhoneAgentCaptureStatus.update {
            it.copy(
                wakeListening = true,
                statusText = "语音唤醒正在启动系统识别服务...",
                lastWakeText = null,
                lastError = null,
            )
        }
        refreshForeground()
        val recognizer = wakeRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            wakeRecognizer = it
        }
        recognizer.setRecognitionListener(wakeRecognitionListener())
        startWakeRecognizer(recognizer)
    }

    private fun stopWakeListener() {
        wakeListening = false
        wakeRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        wakeRestartRunnable = null
        cancelWakeReadyTimeout()
        runCatching { wakeRecognizer?.cancel() }
        runCatching { wakeRecognizer?.destroy() }
        wakeRecognizer = null
        PhoneAgentCaptureStatus.update {
            it.copy(
                wakeListening = false,
                statusText = if (cameraMode != null) "${modeLabel(cameraMode)}运行中" else "语音唤醒已停止",
            )
        }
        refreshForeground()
        stopIfIdle()
    }

    private fun startWakeRecognizer(recognizer: SpeechRecognizer) {
        if (!wakeListening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        PhoneAgentCaptureStatus.update {
            it.copy(statusText = "语音唤醒正在启动系统识别服务...")
        }
        refreshForeground()
        scheduleWakeReadyTimeout()
        runCatching {
            recognizer.startListening(intent)
        }.onFailure { error ->
            cancelWakeReadyTimeout()
            PhoneAgentCaptureStatus.setError("语音唤醒启动失败：${error.message ?: error.javaClass.simpleName}")
            scheduleWakeRestart(2_000L)
        }
    }

    private fun wakeRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                cancelWakeReadyTimeout()
                PhoneAgentCaptureStatus.update {
                    it.copy(statusText = "语音唤醒已进入系统收音：请说“小助手”。", lastError = null)
                }
                refreshForeground()
            }

            override fun onBeginningOfSpeech() {
                PhoneAgentCaptureStatus.update {
                    it.copy(statusText = "语音唤醒正在识别语音...")
                }
                refreshForeground()
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                if (!wakeListening) return
                cancelWakeReadyTimeout()
                val message = wakeSpeechErrorMessage(error)
                PhoneAgentCaptureStatus.update {
                    it.copy(statusText = "语音唤醒监听中：$message")
                }
                refreshForeground()
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    PhoneAgentCaptureStatus.setError(message)
                    stopWakeListener()
                    return
                }
                scheduleWakeRestart(1_200L)
            }

            override fun onResults(results: Bundle?) {
                if (!wakeListening) return
                val texts = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                handleWakeResults(texts)
                scheduleWakeRestart(800L)
            }
        }
    }

    private fun handleWakeResults(texts: List<String>) {
        if (texts.isEmpty()) {
            PhoneAgentCaptureStatus.update {
                it.copy(statusText = "语音唤醒监听中：未识别到有效语音。")
            }
            refreshForeground()
            return
        }
        val match = texts.firstNotNullOfOrNull { text ->
            val phrase = WAKE_PHRASES.firstOrNull { phrase ->
                text.contains(phrase, ignoreCase = true)
            }
            if (phrase == null) null else text to phrase
        }
        if (match == null) {
            val recognized = texts.joinToString(" / ")
            PhoneAgentCaptureStatus.update {
                it.copy(statusText = "语音唤醒监听中：上次识别未命中。", lastWakeText = recognized)
            }
            refreshForeground()
            return
        }
        val (text, phrase) = match
        val command = text.substringAfter(phrase, missingDelimiterValue = "").trim(' ', '，', ',', '。')
        val messageText = if (command.isNotBlank()) {
            "语音唤醒：$command"
        } else {
            "语音唤醒已触发。识别文本：$text"
        }
        PhoneAgentCaptureStatus.update {
            it.copy(statusText = "语音唤醒已触发，正在发送。", lastWakeText = text, lastError = null)
        }
        refreshForeground()
        serviceScope.launch {
            runCatching { repository.sendText(messageText) }
                .onSuccess { message ->
                    val queued = message.localStatus == LocalMessageStatus.PENDING.name &&
                        !message.errorMessage.isNullOrBlank()
                    val status = if (queued) {
                        "语音唤醒消息已进入离线队列：${message.errorMessage}"
                    } else {
                        "语音唤醒消息已发送：${message.messageId.take(12)}"
                    }
                    PhoneAgentCaptureStatus.update {
                        it.copy(statusText = status, lastError = if (queued) status else null)
                    }
                }
                .onFailure { error ->
                    PhoneAgentCaptureStatus.setError("语音唤醒消息发送失败：${error.message ?: error.javaClass.simpleName}")
                }
            refreshForeground()
        }
    }

    private fun scheduleWakeRestart(delayMs: Long) {
        wakeRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            val recognizer = wakeRecognizer
            if (wakeListening && recognizer != null) {
                startWakeRecognizer(recognizer)
            }
        }
        wakeRestartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun scheduleWakeReadyTimeout() {
        cancelWakeReadyTimeout()
        val runnable = Runnable {
            if (!wakeListening) return@Runnable
            val message = "系统语音识别服务启动后没有进入收音状态，语音唤醒不可用。"
            PhoneAgentCaptureStatus.setError(message)
            stopWakeListener()
        }
        wakeReadyTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, WAKE_READY_TIMEOUT_MS)
    }

    private fun cancelWakeReadyTimeout() {
        wakeReadyTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        wakeReadyTimeoutRunnable = null
    }

    private fun refreshForeground() {
        if (cameraMode == null && !wakeListening) {
            return
        }
        val notification = buildNotification()
        val type = foregroundType()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: Exception) {
            PhoneAgentCaptureStatus.setError("前台服务启动失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun stopIfIdle() {
        if (cameraMode == null && !wakeListening) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun foregroundType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var type = 0
        if (cameraMode != null) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (wakeListening) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    private fun buildNotification(): android.app.Notification {
        val openIntent = Intent(this, PhoneAgentActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, PhoneAgentCaptureService::class.java).setAction(ACTION_STOP_ALL)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when {
            cameraMode != null && wakeListening -> "Phone Agent 正在采集和监听"
            cameraMode != null -> "Phone Agent ${modeLabel(cameraMode)}运行中"
            wakeListening -> "Phone Agent 语音唤醒监听中"
            else -> "Phone Agent 采集服务"
        }
        val state = PhoneAgentCaptureStatus.state.value
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setContentText(state.statusText.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(state.statusText))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Phone Agent 采集",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "手机摄像头采集、实时帧流和语音唤醒"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun createStreamFrameFile(mode: String): File {
        val dir = File(filesDir, "phone-agent-stream")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "${mode}_$stamp.jpg")
    }

    private fun modeLabel(mode: String?): String {
        return when (mode) {
            MODE_BACKGROUND_CAPTURE -> "持续后台采集"
            MODE_FRAME_STREAM -> "实时视频帧流"
            else -> "摄像头采集"
        }
    }

    private fun promptForMode(mode: String): String {
        return when (mode) {
            MODE_BACKGROUND_CAPTURE -> "这是手机持续后台采集的一帧，请简要描述画面中可见内容。"
            MODE_FRAME_STREAM -> "这是手机实时视频帧流的一帧，请分析当前画面并给出可操作观察。"
            else -> "请分析这张图片。"
        }
    }

    private fun wakeSpeechErrorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "麦克风录音错误"
            SpeechRecognizer.ERROR_CLIENT -> "识别客户端错误"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
            SpeechRecognizer.ERROR_NETWORK -> "网络不可用"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
            SpeechRecognizer.ERROR_NO_MATCH -> "没有命中唤醒词"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务正忙"
            SpeechRecognizer.ERROR_SERVER -> "识别服务错误"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到语音"
            else -> "识别错误码 $error"
        }
    }

    companion object {
        const val ACTION_START_BACKGROUND_CAPTURE = "com.audiobridge.client.phoneagent.START_BACKGROUND_CAPTURE"
        const val ACTION_START_FRAME_STREAM = "com.audiobridge.client.phoneagent.START_FRAME_STREAM"
        const val ACTION_STOP_CAMERA = "com.audiobridge.client.phoneagent.STOP_CAMERA"
        const val ACTION_START_WAKE_LISTENER = "com.audiobridge.client.phoneagent.START_WAKE_LISTENER"
        const val ACTION_STOP_WAKE_LISTENER = "com.audiobridge.client.phoneagent.STOP_WAKE_LISTENER"
        const val ACTION_STOP_ALL = "com.audiobridge.client.phoneagent.STOP_ALL"
        const val MODE_BACKGROUND_CAPTURE = "background_capture"
        const val MODE_FRAME_STREAM = "frame_stream"

        private const val NOTIFICATION_CHANNEL_ID = "phone_agent_capture"
        private const val NOTIFICATION_ID = 6001
        private const val BACKGROUND_CAPTURE_INTERVAL_MS = 30_000L
        private const val FRAME_STREAM_INTERVAL_MS = 3_000L
        private const val WAKE_READY_TIMEOUT_MS = 8_000L
        private val WAKE_PHRASES = listOf("小助手", "你好助手", "手机助手", "phone agent")

        fun intent(context: Context, action: String): Intent {
            return Intent(context, PhoneAgentCaptureService::class.java).setAction(action)
        }
    }
}
