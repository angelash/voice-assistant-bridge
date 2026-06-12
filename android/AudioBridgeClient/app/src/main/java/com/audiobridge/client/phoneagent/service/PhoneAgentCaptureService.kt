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
    private var wakeSilentChunksSkipped = 0
    private var lastWakeTriggerAtMs = 0L

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
        if (wakeLoopJob?.isActive == true) return
        wakeListening = true
        wakeSilentChunksSkipped = 0
        lastWakeTriggerAtMs = 0L
        PhoneAgentCaptureStatus.update {
            it.copy(
                wakeListening = true,
                statusText = "语音唤醒正在启动 Bridge STT 分片监听...",
                lastWakeText = null,
                lastError = null,
            )
        }
        refreshForeground()
        startBridgeWakeLoop()
    }

    private fun stopWakeListener() {
        wakeListening = false
        wakeLoopJob?.cancel()
        wakeLoopJob = null
        wakeTranscriptionInFlight.set(false)
        wakeSilentChunksSkipped = 0
        lastWakeTriggerAtMs = 0L
        runCatching { wakeAudioRecord?.stop() }
        runCatching { wakeAudioRecord?.release() }
        wakeAudioRecord = null
        PhoneAgentCaptureStatus.update {
            it.copy(
                wakeListening = false,
                statusText = if (cameraMode != null) "${modeLabel(cameraMode)}运行中" else "语音唤醒已停止",
            )
        }
        refreshForeground()
        stopIfIdle()
    }

    private fun startBridgeWakeLoop() {
        wakeLoopJob = serviceScope.launch {
            val minBufferSize = AudioRecord.getMinBufferSize(
                WAKE_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferSize <= 0) {
                PhoneAgentCaptureStatus.setError("语音唤醒启动失败：设备不支持 16kHz 单声道 PCM 录音。")
                stopWakeListener()
                return@launch
            }
            val chunkBytes = WAKE_SAMPLE_RATE_HZ * WAKE_CHUNK_SECONDS * 2
            val bufferSize = maxOf(minBufferSize, chunkBytes / 2)
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    WAKE_SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            } catch (error: SecurityException) {
                PhoneAgentCaptureStatus.setError("语音唤醒启动失败：缺少麦克风权限。")
                stopWakeListener()
                return@launch
            } catch (error: Exception) {
                PhoneAgentCaptureStatus.setError("语音唤醒启动失败：${error.message ?: error.javaClass.simpleName}")
                stopWakeListener()
                return@launch
            }
            wakeAudioRecord = record
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                wakeAudioRecord = null
                PhoneAgentCaptureStatus.setError("语音唤醒启动失败：麦克风录音器初始化失败。")
                stopWakeListener()
                return@launch
            }
            try {
                record.startRecording()
            } catch (error: SecurityException) {
                PhoneAgentCaptureStatus.setError("语音唤醒启动失败：缺少麦克风权限。")
                stopWakeListener()
                return@launch
            } catch (error: Exception) {
                PhoneAgentCaptureStatus.setError("语音唤醒启动失败：${error.message ?: error.javaClass.simpleName}")
                stopWakeListener()
                return@launch
            }
            PhoneAgentCaptureStatus.update {
                it.copy(
                    wakeListening = true,
                    statusText = "语音唤醒正在使用 Bridge STT 分片监听：请说“小助手”。",
                    lastError = null,
                )
            }
            refreshForeground()

            val readBuffer = ByteArray(minOf(minBufferSize, 4096).coerceAtLeast(1024))
            val chunk = ByteArrayOutputStream(chunkBytes)
            try {
                while (isActive && wakeListening) {
                    val read = record.read(readBuffer, 0, readBuffer.size)
                    if (read > 0) {
                        chunk.write(readBuffer, 0, read)
                        if (chunk.size() >= chunkBytes) {
                            val pcm = chunk.toByteArray()
                            chunk.reset()
                            submitWakeChunk(pcm)
                        }
                    } else if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                        PhoneAgentCaptureStatus.setError("语音唤醒录音失败：AudioRecord 读取错误 $read。")
                        stopWakeListener()
                        return@launch
                    }
                }
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                if (wakeAudioRecord === record) {
                    wakeAudioRecord = null
                }
            }
        }
    }

    private fun submitWakeChunk(pcmAudio: ByteArray) {
        val remainingCooldownMs = wakeCooldownRemainingMs()
        if (remainingCooldownMs > 0L) {
            PhoneAgentCaptureStatus.update {
                it.copy(statusText = "语音唤醒冷却中，${(remainingCooldownMs + 999L) / 1000L} 秒后恢复监听。")
            }
            refreshForeground()
            return
        }
        val decision = WakeAudioGate.evaluate(pcmAudio)
        if (!decision.shouldTranscribe) {
            wakeSilentChunksSkipped += 1
            PhoneAgentCaptureStatus.update {
                it.copy(
                    statusText = "语音唤醒监听中：环境较安静，已跳过 $wakeSilentChunksSkipped 个无声分片。",
                    lastWakeText = null,
                )
            }
            refreshForeground()
            return
        }
        if (!wakeListening || !wakeTranscriptionInFlight.compareAndSet(false, true)) return
        wakeSilentChunksSkipped = 0
        serviceScope.launch {
            PhoneAgentCaptureStatus.update {
                it.copy(statusText = "语音唤醒正在转写最近 ${WAKE_CHUNK_SECONDS} 秒音频，能量 ${decision.avgAbs}。")
            }
            refreshForeground()
            runCatching { repository.transcribePcmAudio(pcmAudio) }
                .onSuccess { text ->
                    handleWakeText(text)
                }
                .onFailure { error ->
                    val rawMessage = error.message.orEmpty()
                    if (rawMessage.contains("stt_failed", ignoreCase = true)) {
                        PhoneAgentCaptureStatus.update {
                            it.copy(
                                statusText = "语音唤醒监听中：未识别到有效语音。",
                                lastWakeText = null,
                            )
                        }
                    } else {
                        PhoneAgentCaptureStatus.setError(
                            "语音唤醒转写失败：${rawMessage.ifBlank { error.javaClass.simpleName }}",
                        )
                        stopWakeListener()
                    }
                }
            wakeTranscriptionInFlight.set(false)
            refreshForeground()
        }
    }

    private fun wakeCooldownRemainingMs(nowMs: Long = SystemClock.elapsedRealtime()): Long {
        if (lastWakeTriggerAtMs <= 0L) return 0L
        return (lastWakeTriggerAtMs + WAKE_TRIGGER_COOLDOWN_MS - nowMs).coerceAtLeast(0L)
    }

    private fun handleWakeText(text: String) {
        val recognized = text.trim()
        if (recognized.isBlank()) {
            PhoneAgentCaptureStatus.update {
                it.copy(statusText = "语音唤醒监听中：未识别到有效语音。", lastWakeText = null)
            }
            refreshForeground()
            return
        }
        val phrase = WAKE_PHRASES.firstOrNull { phrase ->
            recognized.contains(phrase, ignoreCase = true)
        }
        if (phrase == null) {
            PhoneAgentCaptureStatus.update {
                it.copy(statusText = "语音唤醒监听中：上次识别未命中。", lastWakeText = recognized)
            }
            refreshForeground()
            return
        }
        val remainingCooldownMs = wakeCooldownRemainingMs()
        if (remainingCooldownMs > 0L) {
            PhoneAgentCaptureStatus.update {
                it.copy(
                    statusText = "语音唤醒冷却中，忽略重复命中。",
                    lastWakeText = recognized,
                )
            }
            refreshForeground()
            return
        }
        val command = recognized.substringAfter(phrase, missingDelimiterValue = "").trim(' ', '，', ',', '。')
        val messageText = if (command.isNotBlank()) {
            "语音唤醒：$command"
        } else {
            "语音唤醒已触发。识别文本：$recognized"
        }
        lastWakeTriggerAtMs = SystemClock.elapsedRealtime()
        PhoneAgentCaptureStatus.update {
            it.copy(statusText = "语音唤醒已触发，正在发送。", lastWakeText = recognized, lastError = null)
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
        private const val WAKE_SAMPLE_RATE_HZ = 16_000
        private const val WAKE_CHUNK_SECONDS = 5
        private const val WAKE_TRIGGER_COOLDOWN_MS = 12_000L
        private val WAKE_PHRASES = listOf("小助手", "你好助手", "手机助手", "phone agent")

        fun intent(context: Context, action: String): Intent {
            return Intent(context, PhoneAgentCaptureService::class.java).setAction(action)
        }
    }
}
