package com.audiobridge.client.phoneagent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.compose.ui.viewinterop.AndroidView
import com.audiobridge.client.MainActivity
import com.audiobridge.client.phoneagent.data.api.OkHttpPhoneAgentBridgeApi
import com.audiobridge.client.phoneagent.data.db.MessageEntity
import com.audiobridge.client.phoneagent.data.repository.MessageRepository
import com.audiobridge.client.phoneagent.data.settings.SettingsRepository
import com.audiobridge.client.phoneagent.model.AppSettings
import com.audiobridge.client.phoneagent.model.BridgeMessageStatus
import com.audiobridge.client.phoneagent.model.LocalMessageStatus
import com.audiobridge.client.phoneagent.service.PhoneAgentCaptureService
import com.audiobridge.client.phoneagent.service.PhoneAgentCaptureStatus
import com.audiobridge.client.phoneagent.service.PhoneAgentCaptureUiState
import com.audiobridge.client.phoneagent.worker.PhoneAgentSyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.WebSocket
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SPEECH_INPUT_TIMEOUT_MS = 15_000L
private const val SPEECH_INPUT_SAMPLE_RATE_HZ = 16_000

class PhoneAgentActivity : ComponentActivity() {
    private lateinit var repository: MessageRepository
    private lateinit var settingsRepository: SettingsRepository
    private val bridgeApi = OkHttpPhoneAgentBridgeApi()
    private var eventSocket: WebSocket? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsErrorMessage: String? = null
    private var speechInputRecord: AudioRecord? = null
    private var speechInputJob: Job? = null
    @Volatile
    private var speechListening = false
    @Volatile
    private var speechInputShouldSubmit = false
    private val speechHandler = Handler(Looper.getMainLooper())
    private var speechTimeoutRunnable: Runnable? = null
    private var audioRecorder: MediaRecorder? = null
    private var audioRecordingFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = MessageRepository.get(this)
        settingsRepository = SettingsRepository.get(this)
        PhoneAgentSyncScheduler.enqueue(this)
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                ttsReady = false
                ttsErrorMessage = "系统语音播报初始化失败。"
                return@TextToSpeech
            }
            val languageResult = tts?.setLanguage(Locale.CHINA) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (
                languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                ttsReady = false
                ttsErrorMessage = "系统语音播报不支持中文或缺少语音数据。"
            } else {
                ttsReady = true
                ttsErrorMessage = null
            }
        }

        setContent {
            val activity = this@PhoneAgentActivity
            val messages by repository.messages.collectAsStateWithLifecycle(initialValue = emptyList())
            val captureState by PhoneAgentCaptureStatus.state.collectAsStateWithLifecycle()
            var settings by remember { mutableStateOf(settingsRepository.load()) }
            var healthText by remember { mutableStateOf("未检查") }
            var eventText by remember { mutableStateOf("事件流未连接") }
            var issueText by remember { mutableStateOf<String?>(null) }
            var pollingEnabled by remember { mutableStateOf(!settings.useWebSocket) }
            var busy by remember { mutableStateOf(false) }
            var cameraPermissionGranted by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                )
            }
            var audioRecording by remember { mutableStateOf(false) }
            var pendingPhotoFile by remember { mutableStateOf<File?>(null) }
            var pendingPhotoPrompt by remember { mutableStateOf("请分析这张图片。") }
            var pendingAudioPrompt by remember { mutableStateOf("") }
            var pendingGalleryPrompt by remember { mutableStateOf("请分析这张图片。") }
            var pendingCaptureServiceAction by remember { mutableStateOf<String?>(null) }
            var lastReportedFailureId by remember { mutableStateOf<String?>(null) }
            var lastReportedCaptureError by remember { mutableStateOf<String?>(null) }
            val activityStartedAtMs = remember { System.currentTimeMillis() }
            val scope = rememberCoroutineScope()

            fun sendPlainText(text: String) {
                busy = true
                scope.launch {
                    runCatching { repository.sendText(text) }
                        .onSuccess { message ->
                            if (
                                message.localStatus == LocalMessageStatus.PENDING.name &&
                                !message.errorMessage.isNullOrBlank()
                            ) {
                                issueText = "Bridge 暂不可用，消息已进入离线队列：${message.errorMessage}"
                            }
                        }
                        .onFailure {
                            val message = "发送失败：${it.message ?: "unknown"}"
                            healthText = message
                            issueText = message
                        }
                    busy = false
                }
            }

            fun uploadPhotoAndSend(file: File, prompt: String, mimeType: String = "image/jpeg") {
                busy = true
                scope.launch {
                    runCatching {
                        repository.sendImageMessage(file = file, text = prompt, mimeType = mimeType)
                    }.onSuccess { message ->
                        if (
                            message.localStatus == LocalMessageStatus.PENDING.name &&
                            !message.errorMessage.isNullOrBlank()
                        ) {
                            issueText = "图片消息已进入离线队列，恢复连接后会继续上传并发送：${message.errorMessage}"
                        }
                    }.onFailure {
                        issueText = "图片发送失败：${it.message ?: it.javaClass.simpleName}"
                    }
                    busy = false
                }
            }

            fun uploadAudioAndSend(file: File, prompt: String) {
                busy = true
                scope.launch {
                    runCatching {
                        repository.sendAudioMessage(file = file, text = prompt)
                    }.onSuccess { message ->
                        if (
                            message.localStatus == LocalMessageStatus.PENDING.name &&
                            !message.errorMessage.isNullOrBlank()
                        ) {
                            issueText = "录音已保存并进入离线队列，恢复连接后会上传：${message.errorMessage}"
                        }
                    }.onFailure {
                        issueText = "录音发送失败：${it.message ?: it.javaClass.simpleName}"
                    }
                    busy = false
                }
            }

            val takePictureLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.TakePicture()
            ) { ok ->
                val file = pendingPhotoFile
                pendingPhotoFile = null
                if (ok && file != null && file.exists() && file.length() > 0L) {
                    uploadPhotoAndSend(file, pendingPhotoPrompt, "image/jpeg")
                } else if (!ok) {
                    issueText = "拍照已取消，未发送图片。"
                } else {
                    issueText = "拍照失败：没有得到可上传的图片文件。"
                }
            }

            val pickImageLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.GetContent()
            ) { uri ->
                if (uri == null) {
                    issueText = "图库选图已取消，未发送图片。"
                    return@rememberLauncherForActivityResult
                }
                runCatching { activity.copyGalleryImageToPrivateFile(uri) }
                    .onSuccess { (file, mimeType) ->
                        uploadPhotoAndSend(file, pendingGalleryPrompt, mimeType)
                    }
                    .onFailure {
                        issueText = "图库图片读取失败：${it.message ?: it.javaClass.simpleName}"
                    }
            }

            val cameraPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                cameraPermissionGranted = granted
                if (!granted) {
                    issueText = "未授予摄像头权限，无法打开 CameraX 预览或启动摄像头采集。"
                }
            }

            val audioPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    healthText = "正在听语音..."
                    activity.startSpeechInput(
                        onText = { sendPlainText(it) },
                        onStatus = { healthText = it },
                        reportIssue = {
                            healthText = it
                            issueText = it
                        },
                    )
                } else {
                    issueText = "未授予麦克风权限，无法语音输入。"
                }
            }

            val audioRecordPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    runCatching { activity.startAudioRecording() }
                        .onSuccess {
                            audioRecording = true
                            healthText = "录音中..."
                        }
                        .onFailure {
                            issueText = "启动录音失败：${it.message ?: it.javaClass.simpleName}"
                        }
                } else {
                    issueText = "未授予麦克风权限，无法录音保存。"
                }
            }

            val captureServicePermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                val action = pendingCaptureServiceAction
                pendingCaptureServiceAction = null
                if (action == null) return@rememberLauncherForActivityResult
                val denied = grants.filterValues { !it }.keys
                if (denied.isNotEmpty()) {
                    issueText = "缺少权限，无法启动采集服务：${denied.joinToString()}"
                    return@rememberLauncherForActivityResult
                }
                ContextCompat.startForegroundService(
                    activity,
                    PhoneAgentCaptureService.intent(activity, action),
                )
            }

            fun startCaptureService(action: String) {
                if (
                    action in setOf(
                        PhoneAgentCaptureService.ACTION_START_BACKGROUND_CAPTURE,
                        PhoneAgentCaptureService.ACTION_START_FRAME_STREAM,
                    ) &&
                    !settings.allowAutoCapture
                ) {
                    issueText = "设置未开启“允许手动启动前台持续采集”，不会启动摄像头持续采集。"
                    return
                }
                val permissions = requiredPermissionsForCaptureAction(action)
                    .filter {
                        ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
                    }
                    .toTypedArray()
                if (permissions.isNotEmpty()) {
                    pendingCaptureServiceAction = action
                    captureServicePermissionLauncher.launch(permissions)
                    return
                }
                ContextCompat.startForegroundService(
                    activity,
                    PhoneAgentCaptureService.intent(activity, action),
                )
            }

            fun stopCaptureService(action: String) {
                ContextCompat.startForegroundService(
                    activity,
                    PhoneAgentCaptureService.intent(activity, action),
                )
            }

            fun toggleAudioRecording(prompt: String) {
                if (audioRecording) {
                    runCatching { activity.stopAudioRecording() }
                        .onSuccess { file ->
                            audioRecording = false
                            healthText = "录音已保存：${file.name}"
                            val audioPrompt = pendingAudioPrompt.ifBlank { prompt }
                            pendingAudioPrompt = ""
                            uploadAudioAndSend(file, audioPrompt)
                        }
                        .onFailure {
                            audioRecording = false
                            pendingAudioPrompt = ""
                            issueText = "停止录音失败：${it.message ?: it.javaClass.simpleName}"
                        }
                    return
                }
                pendingAudioPrompt = prompt
                if (
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    audioRecordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return
                }
                runCatching { activity.startAudioRecording() }
                    .onSuccess {
                        audioRecording = true
                        healthText = "录音中..."
                    }
                    .onFailure {
                        issueText = "启动录音失败：${it.message ?: it.javaClass.simpleName}"
                    }
            }

            LaunchedEffect(settings.bridgeBaseUrl, settings.clientId, settings.sessionId, settings.useWebSocket) {
                pollingEnabled = !settings.useWebSocket
                openEventStream(
                    settings = settings,
                    updateStatus = { eventText = it },
                    updatePolling = { pollingEnabled = it },
                    reportIssue = { issueText = it },
                )
            }
            LaunchedEffect(settings.bridgeBaseUrl, settings.clientId, settings.sessionId) {
                while (true) {
                    repository.refreshActiveMessages()
                    delay(2500)
                }
            }
            LaunchedEffect(messages.lastOrNull()?.messageId, messages.lastOrNull()?.bridgeStatus, messages.lastOrNull()?.errorMessage) {
                val failed = messages.lastOrNull {
                    it.updatedAt >= activityStartedAtMs &&
                        (it.bridgeStatus == BridgeMessageStatus.FAILED ||
                            it.localStatus == LocalMessageStatus.FAILED.name)
                }
                if (failed != null && failed.messageId != lastReportedFailureId) {
                    lastReportedFailureId = failed.messageId
                    issueText = "消息处理失败：${failed.errorMessage ?: failed.bridgeStatus}"
                }
            }
            LaunchedEffect(captureState.lastError) {
                val error = captureState.lastError
                if (!error.isNullOrBlank() && error != lastReportedCaptureError) {
                    lastReportedCaptureError = error
                    issueText = error
                }
            }

            PhoneAgentTheme {
                PhoneAgentApp(
                    settings = settings,
                    messages = messages,
                    captureState = captureState,
                    healthText = healthText,
                    eventText = eventText,
                    issueText = issueText,
                    busy = busy,
                    cameraPermissionGranted = cameraPermissionGranted,
                    audioRecording = audioRecording,
                    onSaveSettings = { updated ->
                        settings = settingsRepository.save(updated)
                        healthText = "设置已保存"
                        PhoneAgentSyncScheduler.enqueue(this)
                    },
                    onHealthCheck = {
                        busy = true
                        scope.launch {
                            val result = repository.checkHealth(settings)
                            healthText = result.fold(
                                onSuccess = { "在线：$it" },
                                onFailure = {
                                    val message = "Health 检查失败：${it.message ?: it.javaClass.simpleName}"
                                    issueText = message
                                    message
                                },
                            )
                            busy = false
                        }
                    },
                    onSend = { text ->
                        sendPlainText(text)
                    },
                    onPickImage = { prompt ->
                        pendingGalleryPrompt = prompt.ifBlank { "请分析这张图片。" }
                        pickImageLauncher.launch("image/*")
                    },
                    onCaptureImage = { prompt ->
                        runCatching {
                            val file = activity.createPhoneAgentPhotoFile()
                            pendingPhotoFile = file
                            pendingPhotoPrompt = prompt.ifBlank { "请分析这张图片。" }
                            val uri = FileProvider.getUriForFile(
                                activity,
                                "${activity.packageName}.fileprovider",
                                file,
                            )
                            takePictureLauncher.launch(uri)
                        }.onFailure {
                            pendingPhotoFile = null
                            issueText = "启动相机失败：${it.message ?: it.javaClass.simpleName}"
                        }
                    },
                    onRequestCameraPermission = {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onCaptureCameraXImage = { imageCapture, prompt ->
                        val file = activity.createPhoneAgentPhotoFile()
                        activity.captureCameraXPhoto(
                            imageCapture = imageCapture,
                            file = file,
                            onSaved = {
                                uploadPhotoAndSend(file, prompt.ifBlank { "请分析这张图片。" }, "image/jpeg")
                            },
                            reportIssue = { issueText = it },
                        )
                    },
                    onToggleAudioRecording = { prompt ->
                        toggleAudioRecording(prompt.ifBlank { pendingAudioPrompt })
                    },
                    onVoiceInput = {
                        if (
                            ContextCompat.checkSelfPermission(
                                activity,
                                Manifest.permission.RECORD_AUDIO,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            healthText = "正在听语音..."
                            activity.startSpeechInput(
                                onText = { sendPlainText(it) },
                                onStatus = { healthText = it },
                                reportIssue = {
                                    healthText = it
                                    issueText = it
                                },
                            )
                        }
                    },
                    onSpeak = { text ->
                        speakText(text) { issueText = it }
                    },
                    onStartBackgroundCapture = {
                        startCaptureService(PhoneAgentCaptureService.ACTION_START_BACKGROUND_CAPTURE)
                    },
                    onStartFrameStream = {
                        startCaptureService(PhoneAgentCaptureService.ACTION_START_FRAME_STREAM)
                    },
                    onStopCameraCapture = {
                        stopCaptureService(PhoneAgentCaptureService.ACTION_STOP_CAMERA)
                    },
                    onStartWakeListener = {
                        startCaptureService(PhoneAgentCaptureService.ACTION_START_WAKE_LISTENER)
                    },
                    onStopWakeListener = {
                        stopCaptureService(PhoneAgentCaptureService.ACTION_STOP_WAKE_LISTENER)
                    },
                    onRetry = { messageId ->
                        busy = true
                        scope.launch {
                            val sent = repository.retry(messageId)
                            if (!sent) {
                                issueText = "重试未成功，消息仍保留在离线队列。"
                            }
                            busy = false
                        }
                    },
                    onClearLocalData = {
                        busy = true
                        scope.launch {
                            runCatching { repository.clearLocalData() }
                                .onSuccess {
                                    healthText = "本地数据已清空"
                                }
                                .onFailure {
                                    issueText = "清空本地数据失败：${it.message ?: it.javaClass.simpleName}"
                                }
                            busy = false
                        }
                    },
                    onClearRemoteSession = {
                        busy = true
                        scope.launch {
                            runCatching { repository.clearRemoteSessionData() }
                                .onSuccess {
                                    healthText = "远端会话已清空：$it"
                                }
                                .onFailure {
                                    issueText = "清空远端会话失败：${it.message ?: it.javaClass.simpleName}"
                                }
                            busy = false
                        }
                    },
                    onClearAllData = {
                        busy = true
                        scope.launch {
                            runCatching {
                                val remoteSummary = repository.clearRemoteSessionData()
                                repository.clearLocalData()
                                remoteSummary
                            }.onSuccess {
                                healthText = "本地和远端会话已清空：$it"
                            }.onFailure {
                                issueText = "清空本地+远端失败：${it.message ?: it.javaClass.simpleName}"
                            }
                            busy = false
                        }
                    },
                    onOpenLegacy = {
                        startActivity(Intent(this, MainActivity::class.java))
                    },
                    onDismissIssue = {
                        issueText = null
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        eventSocket?.cancel()
        eventSocket = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        ttsErrorMessage = null
        stopSpeechInputRecording(submit = false)
        speechInputJob?.cancel()
        speechInputJob = null
        runCatching { speechInputRecord?.release() }
        speechInputRecord = null
        releaseAudioRecorder()
        super.onDestroy()
    }

    private fun startSpeechInput(
        onText: (String) -> Unit,
        onStatus: (String) -> Unit,
        reportIssue: (String) -> Unit,
    ) {
        if (speechListening) {
            stopSpeechInputRecording(submit = true)
            onStatus("语音输入录音结束，正在转写...")
            return
        }
        val minBufferSize = AudioRecord.getMinBufferSize(
            SPEECH_INPUT_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            reportIssue("语音输入启动失败：设备不支持 16kHz 单声道 PCM 录音。")
            return
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SPEECH_INPUT_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufferSize, SPEECH_INPUT_SAMPLE_RATE_HZ * 2),
            )
        } catch (error: SecurityException) {
            reportIssue("语音输入启动失败：缺少麦克风权限。")
            return
        } catch (error: Exception) {
            reportIssue("语音输入启动失败：${error.message ?: error.javaClass.simpleName}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            reportIssue("语音输入启动失败：麦克风录音器初始化失败。")
            return
        }
        try {
            record.startRecording()
        } catch (error: SecurityException) {
            runCatching { record.release() }
            reportIssue("语音输入启动失败：缺少麦克风权限。")
            return
        } catch (error: Exception) {
            runCatching { record.release() }
            reportIssue("语音输入启动失败：${error.message ?: error.javaClass.simpleName}")
            return
        }

        speechInputRecord = record
        speechListening = true
        speechInputShouldSubmit = true
        onStatus("正在录音，15 秒内自动转写；再次点击语音可立即转写。")
        scheduleSpeechTimeout(onStatus)
        speechInputJob = lifecycleScope.launch {
            val audioResult = runCatching {
                withContext(Dispatchers.IO) {
                    readSpeechInputPcm(record, minBufferSize)
                }
            }
            val shouldSubmit = speechInputShouldSubmit
            finishSpeechListening()
            speechInputShouldSubmit = false
            speechInputJob = null
            val pcmAudio = audioResult.getOrElse { error ->
                if (shouldSubmit) {
                    reportIssue("语音输入录音失败：${error.message ?: error.javaClass.simpleName}")
                }
                return@launch
            }
            if (!shouldSubmit) {
                return@launch
            }
            if (pcmAudio.isEmpty()) {
                reportIssue("没有录到有效音频，请重新录音。")
                return@launch
            }
            onStatus("语音输入正在通过 Bridge STT 转写...")
            runCatching { repository.transcribePcmAudio(pcmAudio) }
                .onSuccess { text ->
                    val clean = text.trim()
                    if (clean.isBlank()) {
                        reportIssue("没有识别到可发送的语音内容。")
                    } else {
                        onStatus("语音输入已识别，正在发送...")
                        onText(clean)
                    }
                }
                .onFailure { error ->
                    reportIssue("语音输入转写失败：${error.message ?: error.javaClass.simpleName}")
                }
        }
    }

    private fun scheduleSpeechTimeout(onStatus: (String) -> Unit) {
        speechTimeoutRunnable?.let { speechHandler.removeCallbacks(it) }
        val timeout = Runnable {
            if (!speechListening) return@Runnable
            stopSpeechInputRecording(submit = true)
            onStatus("语音输入达到 15 秒，正在转写...")
        }
        speechTimeoutRunnable = timeout
        speechHandler.postDelayed(timeout, SPEECH_INPUT_TIMEOUT_MS)
    }

    private fun stopSpeechInputRecording(submit: Boolean) {
        speechInputShouldSubmit = submit
        speechListening = false
        speechTimeoutRunnable?.let { speechHandler.removeCallbacks(it) }
        speechTimeoutRunnable = null
        if (!submit) {
            runCatching { speechInputRecord?.stop() }
        }
    }

    private fun finishSpeechListening() {
        speechListening = false
        speechTimeoutRunnable?.let { speechHandler.removeCallbacks(it) }
        speechTimeoutRunnable = null
    }

    private fun readSpeechInputPcm(record: AudioRecord, minBufferSize: Int): ByteArray {
        val maxBytes = SPEECH_INPUT_SAMPLE_RATE_HZ * (SPEECH_INPUT_TIMEOUT_MS / 1000L).toInt() * 2
        val readBuffer = ByteArray(minOf(maxOf(minBufferSize, 1024), 4096))
        val audio = ByteArrayOutputStream(maxBytes)
        try {
            while (speechListening && audio.size() < maxBytes) {
                val remaining = maxBytes - audio.size()
                val read = record.read(readBuffer, 0, minOf(readBuffer.size, remaining))
                when {
                    read > 0 -> audio.write(readBuffer, 0, read)
                    read == 0 -> Unit
                    !speechListening -> Unit
                    read == AudioRecord.ERROR_INVALID_OPERATION ||
                        read == AudioRecord.ERROR_BAD_VALUE -> throw IOException("AudioRecord 读取错误 $read")
                    else -> throw IOException("AudioRecord 读取失败 $read")
                }
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            if (speechInputRecord === record) {
                speechInputRecord = null
            }
        }
        return audio.toByteArray()
    }

    private fun createPhoneAgentPhotoFile(): File {
        val dir = File(filesDir, "phone-agent-captures")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "phone_agent_$stamp.jpg")
    }

    private fun createPhoneAgentAudioFile(): File {
        val dir = File(filesDir, "phone-agent-audio")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "phone_agent_audio_$stamp.m4a")
    }

    private fun startAudioRecording() {
        if (audioRecorder != null) {
            throw IOException("已有录音正在进行")
        }
        val file = createPhoneAgentAudioFile()
        val recorder = createMediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioEncodingBitRate(96_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            audioRecorder = recorder
            audioRecordingFile = file
        } catch (error: Exception) {
            runCatching { recorder.release() }
            file.delete()
            audioRecorder = null
            audioRecordingFile = null
            throw IOException("录音启动失败：${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    private fun stopAudioRecording(): File {
        val recorder = audioRecorder ?: throw IOException("当前没有正在进行的录音")
        val file = audioRecordingFile ?: throw IOException("录音文件状态异常")
        try {
            recorder.stop()
        } catch (error: RuntimeException) {
            file.delete()
            throw IOException("录音停止失败，录音文件不可用：${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            runCatching { recorder.release() }
            audioRecorder = null
            audioRecordingFile = null
        }
        if (!file.exists() || file.length() <= 0L) {
            file.delete()
            throw IOException("录音文件为空，未上传")
        }
        return file
    }

    private fun releaseAudioRecorder() {
        runCatching { audioRecorder?.release() }
        audioRecorder = null
        audioRecordingFile = null
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            MediaRecorder()
        }
    }

    private fun copyGalleryImageToPrivateFile(uri: Uri): Pair<File, String> {
        val mimeType = contentResolver.getType(uri)?.trim().orEmpty()
        if (!mimeType.startsWith("image/")) {
            throw IOException("选择的文件不是图片：${mimeType.ifBlank { "unknown" }}")
        }
        val extension = when (mimeType.lowercase(Locale.US)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/jpeg", "image/jpg" -> "jpg"
            else -> throw IOException("暂不支持的图库图片类型：$mimeType")
        }
        val dir = File(filesDir, "phone-agent-captures")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "gallery_$stamp.$extension")
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开图库图片")
        input.use { source ->
            file.outputStream().use { target ->
                source.copyTo(target)
            }
        }
        if (!file.exists() || file.length() <= 0L) {
            file.delete()
            throw IOException("图库图片复制后为空")
        }
        return file to mimeType
    }

    private fun captureCameraXPhoto(
        imageCapture: ImageCapture,
        file: File,
        onSaved: () -> Unit,
        reportIssue: (String) -> Unit,
    ) {
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (file.exists() && file.length() > 0L) {
                        onSaved()
                    } else {
                        reportIssue("CameraX 拍照失败：保存的图片为空。")
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    reportIssue("CameraX 拍照失败：${exception.message ?: exception.imageCaptureError}")
                }
            },
        )
    }

    private fun speakText(text: String, reportIssue: (String) -> Unit) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val engine = tts
        if (!ttsReady || engine == null) {
            reportIssue(ttsErrorMessage ?: "系统语音播报尚未就绪。")
            return
        }
        val code = engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "phone-agent-${System.currentTimeMillis()}")
        if (code == TextToSpeech.ERROR) {
            reportIssue("系统语音播报启动失败。")
        }
    }

    private fun openEventStream(
        settings: AppSettings,
        updateStatus: (String) -> Unit,
        updatePolling: (Boolean) -> Unit,
        reportIssue: (String) -> Unit,
    ) {
        eventSocket?.cancel()
        eventSocket = null
        if (!settings.useWebSocket) {
            updatePolling(true)
            updateStatus("WebSocket 已关闭，使用轮询")
            return
        }
        runCatching {
            eventSocket = bridgeApi.openEventStream(
                settings = settings,
                onConnected = {
                    runOnUiThread {
                        updatePolling(false)
                        updateStatus("事件流已连接")
                    }
                },
                onEvent = { event ->
                    lifecycleScope.launch { repository.applyEvent(event) }
                },
                onFailure = { reason ->
                    runOnUiThread {
                        val message = "事件流断开，已切换为轮询：${reason.take(80)}"
                        updatePolling(true)
                        updateStatus(message)
                        reportIssue(message)
                    }
                },
                onClosed = {
                    runOnUiThread {
                        val message = "事件流已关闭，已切换为轮询。"
                        updatePolling(true)
                        updateStatus(message)
                        reportIssue(message)
                    }
                },
            )
            updateStatus("事件流连接中")
        }.onFailure { error ->
            val message = "事件流不可用，已切换为轮询：${error.message ?: error.javaClass.simpleName}"
            updatePolling(true)
            updateStatus(message)
            reportIssue(message)
        }
    }
}

private enum class PhoneAgentTab(val title: String) {
    Chat("聊天"),
    Capture("采集"),
    Settings("设置"),
}

@Composable
private fun PhoneAgentTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Color(0xFF126A6A),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFC7EEEE),
        onPrimaryContainer = Color(0xFF082F2F),
        secondary = Color(0xFF6A5B13),
        secondaryContainer = Color(0xFFFFEDB0),
        tertiary = Color(0xFF7B4353),
        background = Color(0xFFF7F8F5),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFE4E8E3),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneAgentApp(
    settings: AppSettings,
    messages: List<MessageEntity>,
    captureState: PhoneAgentCaptureUiState,
    healthText: String,
    eventText: String,
    issueText: String?,
    busy: Boolean,
    cameraPermissionGranted: Boolean,
    audioRecording: Boolean,
    onSaveSettings: (AppSettings) -> Unit,
    onHealthCheck: () -> Unit,
    onSend: (String) -> Unit,
    onPickImage: (String) -> Unit,
    onCaptureImage: (String) -> Unit,
    onRequestCameraPermission: () -> Unit,
    onCaptureCameraXImage: (ImageCapture, String) -> Unit,
    onToggleAudioRecording: (String) -> Unit,
    onVoiceInput: () -> Unit,
    onSpeak: (String) -> Unit,
    onStartBackgroundCapture: () -> Unit,
    onStartFrameStream: () -> Unit,
    onStopCameraCapture: () -> Unit,
    onStartWakeListener: () -> Unit,
    onStopWakeListener: () -> Unit,
    onRetry: (String) -> Unit,
    onClearLocalData: () -> Unit,
    onClearRemoteSession: () -> Unit,
    onClearAllData: () -> Unit,
    onOpenLegacy: () -> Unit,
    onDismissIssue: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(PhoneAgentTab.Chat) }
    issueText?.let { text ->
        AlertDialog(
            onDismissRequest = onDismissIssue,
            title = { Text("需要处理") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = onDismissIssue) {
                    Text("知道了")
                }
            },
        )
    }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Phone Agent", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                settings.bridgeBaseUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        TextButton(onClick = onHealthCheck) {
                            Text("Health")
                        }
                    },
                )
                TabRow(selectedTabIndex = selectedTab.ordinal) {
                    PhoneAgentTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.title) },
                        )
                    }
                }
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            if (selectedTab == PhoneAgentTab.Chat) {
                ChatInputBar(
                    enabled = !busy,
                    onSend = onSend,
                    onPickImage = onPickImage,
                    onCaptureImage = onCaptureImage,
                    onToggleAudioRecording = onToggleAudioRecording,
                    onVoiceInput = onVoiceInput,
                    audioRecording = audioRecording,
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (selectedTab) {
                PhoneAgentTab.Chat -> ChatScreen(
                    messages = messages,
                    healthText = healthText,
                    eventText = eventText,
                    onRetry = onRetry,
                    onSpeak = onSpeak,
                )
                PhoneAgentTab.Capture -> CaptureScreen(
                    captureState = captureState,
                    cameraPermissionGranted = cameraPermissionGranted,
                    audioRecording = audioRecording,
                    busy = busy,
                    onRequestCameraPermission = onRequestCameraPermission,
                    onPickImage = onPickImage,
                    onCaptureCameraXImage = onCaptureCameraXImage,
                    onToggleAudioRecording = onToggleAudioRecording,
                    onStartBackgroundCapture = onStartBackgroundCapture,
                    onStartFrameStream = onStartFrameStream,
                    onStopCameraCapture = onStopCameraCapture,
                    onStartWakeListener = onStartWakeListener,
                    onStopWakeListener = onStopWakeListener,
                )
                PhoneAgentTab.Settings -> SettingsScreen(
                    initialSettings = settings,
                    healthText = healthText,
                    eventText = eventText,
                    onSave = onSaveSettings,
                    onHealthCheck = onHealthCheck,
                    onClearLocalData = onClearLocalData,
                    onClearRemoteSession = onClearRemoteSession,
                    onClearAllData = onClearAllData,
                    onOpenLegacy = onOpenLegacy,
                )
            }
        }
    }
}

@Composable
private fun ChatScreen(
    messages: List<MessageEntity>,
    healthText: String,
    eventText: String,
    onRetry: (String) -> Unit,
    onSpeak: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val lastMessage = messages.lastOrNull()
    LaunchedEffect(lastMessage?.messageId, lastMessage?.updatedAt) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        StatusStrip(healthText = healthText, eventText = eventText)
        HorizontalDivider()
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "还没有消息",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }
                items(messages, key = { it.messageId }) { message ->
                    MessageThread(message = message, onRetry = onRetry, onSpeak = onSpeak)
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun CaptureScreen(
    captureState: PhoneAgentCaptureUiState,
    cameraPermissionGranted: Boolean,
    audioRecording: Boolean,
    busy: Boolean,
    onRequestCameraPermission: () -> Unit,
    onPickImage: (String) -> Unit,
    onCaptureCameraXImage: (ImageCapture, String) -> Unit,
    onToggleAudioRecording: (String) -> Unit,
    onStartBackgroundCapture: () -> Unit,
    onStartFrameStream: () -> Unit,
    onStopCameraCapture: () -> Unit,
    onStartWakeListener: () -> Unit,
    onStopWakeListener: () -> Unit,
) {
    var prompt by remember { mutableStateOf("请分析这张图片。") }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusPanel(captureState)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
        ) {
            if (!cameraPermissionGranted) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = onRequestCameraPermission, shape = RoundedCornerShape(6.dp)) {
                        Text("授权摄像头")
                    }
                }
            } else {
                CameraXPreview(
                    onImageCaptureReady = { imageCapture = it },
                    onPreviewError = { PhoneAgentCaptureStatus.setError(it) },
                )
            }
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("提问/采集说明") },
            minLines = 1,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val capture = imageCapture ?: return@Button
                    onCaptureCameraXImage(capture, prompt)
                },
                enabled = !busy && cameraPermissionGranted && imageCapture != null,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("预览拍照", maxLines = 1)
            }
            OutlinedButton(
                onClick = { onPickImage(prompt.ifBlank { "请分析这张图片。" }) },
                enabled = !busy,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("图库选图", maxLines = 1)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onToggleAudioRecording(audioPromptForCapturePrompt(prompt)) },
                enabled = !busy,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (audioRecording) "停录并上传" else "开始录音", maxLines = 1)
            }
        }
        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onStartBackgroundCapture,
                enabled = captureState.cameraMode != PhoneAgentCaptureService.MODE_BACKGROUND_CAPTURE,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("后台采集", maxLines = 1)
            }
            OutlinedButton(
                onClick = onStartFrameStream,
                enabled = captureState.cameraMode != PhoneAgentCaptureService.MODE_FRAME_STREAM,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("实时帧流", maxLines = 1)
            }
            OutlinedButton(
                onClick = onStopCameraCapture,
                enabled = captureState.cameraMode != null,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("停相机", maxLines = 1)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onStartWakeListener,
                enabled = !captureState.wakeListening,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("语音唤醒", maxLines = 1)
            }
            OutlinedButton(
                onClick = onStopWakeListener,
                enabled = captureState.wakeListening,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("停唤醒", maxLines = 1)
            }
        }
    }
}

@Composable
private fun StatusPanel(captureState: PhoneAgentCaptureUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("采集状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(captureState.statusText, style = MaterialTheme.typography.bodyMedium)
            val modeText = when (captureState.cameraMode) {
                PhoneAgentCaptureService.MODE_BACKGROUND_CAPTURE -> "持续后台采集"
                PhoneAgentCaptureService.MODE_FRAME_STREAM -> "实时视频帧流"
                null -> "未运行"
                else -> captureState.cameraMode
            }
            Text(
                "相机：$modeText / 唤醒：${if (captureState.wakeListening) "监听中" else "未运行"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!captureState.lastWakeText.isNullOrBlank()) {
                Text(
                    "最近语音：${captureState.lastWakeText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CameraXPreview(
    onImageCaptureReady: (ImageCapture?) -> Unit,
    onPreviewError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
    DisposableEffect(lifecycleOwner, previewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var preview: Preview? = null
        var imageCapture: ImageCapture? = null
        val listener = Runnable {
            runCatching {
                val cameraProvider = cameraProviderFuture.get()
                preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                )
                onImageCaptureReady(imageCapture)
            }.onFailure {
                onImageCaptureReady(null)
                onPreviewError("CameraX 预览启动失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            onImageCaptureReady(null)
            runCatching {
                if (cameraProviderFuture.isDone) {
                    val cameraProvider = cameraProviderFuture.get()
                    preview?.let { cameraProvider.unbind(it) }
                    imageCapture?.let { cameraProvider.unbind(it) }
                }
            }
        }
    }
}

@Composable
private fun StatusStrip(healthText: String, eventText: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            healthText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            eventText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MessageThread(
    message: MessageEntity,
    onRetry: (String) -> Unit,
    onSpeak: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.widthIn(max = 330.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(message.text, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        statusLine(message),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                    if (canRetry(message)) {
                        OutlinedButton(
                            onClick = { onRetry(message.messageId) },
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text("重试")
                        }
                    }
                }
            }
        }
        if (!message.localReply.isNullOrBlank()) {
            AssistantBubble(label = "本地首答", text = message.localReply)
        }
        if (!message.finalReply.isNullOrBlank()) {
            AssistantBubble(label = "最终回复", text = message.finalReply, onSpeak = { onSpeak(message.finalReply) })
        }
        if (!message.errorMessage.isNullOrBlank() && message.finalReply.isNullOrBlank()) {
            AssistantBubble(label = "错误", text = message.errorMessage)
        }
    }
}

@Composable
private fun AssistantBubble(
    label: String,
    text: String,
    onSpeak: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (onSpeak != null) {
                    OutlinedButton(
                        onClick = onSpeak,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text("播报")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    enabled: Boolean,
    onSend: (String) -> Unit,
    onPickImage: (String) -> Unit,
    onCaptureImage: (String) -> Unit,
    onToggleAudioRecording: (String) -> Unit,
    onVoiceInput: () -> Unit,
    audioRecording: Boolean,
) {
    var text by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        val clean = text.trim()
        if (clean.isBlank()) return
        onSend(clean)
        text = ""
        keyboard?.hide()
    }
    fun capture() {
        val prompt = text.trim().ifBlank { "请分析这张图片。" }
        onCaptureImage(prompt)
        text = ""
        keyboard?.hide()
    }
    fun pickImage() {
        val prompt = text.trim().ifBlank { "请分析这张图片。" }
        onPickImage(prompt)
        text = ""
        keyboard?.hide()
    }
    fun recordAudio() {
        val prompt = text.trim().ifBlank { "请转写并分析这段录音。" }
        onToggleAudioRecording(prompt)
        if (audioRecording) {
            text = ""
            keyboard?.hide()
        }
    }
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                minLines = 1,
                maxLines = 4,
                placeholder = { Text("输入消息") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onVoiceInput,
                    enabled = enabled,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("语音", maxLines = 1)
                }
                OutlinedButton(
                    onClick = { recordAudio() },
                    enabled = enabled,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (audioRecording) "停录并发" else "录音", maxLines = 1)
                }
                OutlinedButton(
                    onClick = { pickImage() },
                    enabled = enabled,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("图库", maxLines = 1)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { capture() },
                    enabled = enabled,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("系统拍照", maxLines = 1)
                }
                Button(
                    onClick = { submit() },
                    enabled = enabled && text.isNotBlank(),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("发送", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    initialSettings: AppSettings,
    healthText: String,
    eventText: String,
    onSave: (AppSettings) -> Unit,
    onHealthCheck: () -> Unit,
    onClearLocalData: () -> Unit,
    onClearRemoteSession: () -> Unit,
    onClearAllData: () -> Unit,
    onOpenLegacy: () -> Unit,
) {
    var bridgeBaseUrl by remember(initialSettings) { mutableStateOf(initialSettings.bridgeBaseUrl) }
    var clientId by remember(initialSettings) { mutableStateOf(initialSettings.clientId) }
    var sessionId by remember(initialSettings) { mutableStateOf(initialSettings.sessionId) }
    var apiToken by remember(initialSettings) { mutableStateOf(initialSettings.apiToken) }
    var useWebSocket by remember(initialSettings) { mutableStateOf(initialSettings.useWebSocket) }
    var allowMobileNetworkSync by remember(initialSettings) {
        mutableStateOf(initialSettings.allowMobileNetworkSync)
    }
    var allowAutoCapture by remember(initialSettings) {
        mutableStateOf(initialSettings.allowAutoCapture)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = bridgeBaseUrl,
            onValueChange = { bridgeBaseUrl = it },
            label = { Text("Bridge 地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = sessionId,
            onValueChange = { sessionId = it },
            label = { Text("Session ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiToken,
            onValueChange = { apiToken = it },
            label = { Text("Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("WebSocket 状态流")
            Switch(checked = useWebSocket, onCheckedChange = { useWebSocket = it })
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = allowMobileNetworkSync,
                onCheckedChange = { allowMobileNetworkSync = it },
            )
            Text("允许移动网络同步")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = allowAutoCapture,
                onCheckedChange = { allowAutoCapture = it },
            )
            Text("允许手动启动前台持续采集")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(
                        AppSettings(
                            bridgeBaseUrl = bridgeBaseUrl,
                            clientId = clientId,
                            sessionId = sessionId,
                            apiToken = apiToken,
                            useWebSocket = useWebSocket,
                            allowMobileNetworkSync = allowMobileNetworkSync,
                            allowAutoCapture = allowAutoCapture,
                        )
                    )
                },
                shape = RoundedCornerShape(6.dp),
            ) {
                Text("保存")
            }
            OutlinedButton(
                onClick = onHealthCheck,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text("检查连接")
            }
        }
        HorizontalDivider()
        Text("连接状态", style = MaterialTheme.typography.titleSmall)
        Text(healthText, style = MaterialTheme.typography.bodyMedium)
        Text(eventText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onClearLocalData,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.tertiary),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("清空本地", maxLines = 1)
            }
            OutlinedButton(
                onClick = onClearRemoteSession,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.tertiary),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("清空远端", maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onClearAllData,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.tertiary),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text("清空本地+远端")
            }
            OutlinedButton(
                onClick = onOpenLegacy,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text("旧版音频桥")
            }
        }
    }
}

private const val DEFAULT_IMAGE_PROMPT = "请分析这张图片。"
private const val DEFAULT_AUDIO_PROMPT = "请转写并分析这段录音。"

private fun audioPromptForCapturePrompt(prompt: String): String {
    val clean = prompt.trim()
    return if (clean.isBlank() || clean == DEFAULT_IMAGE_PROMPT) {
        DEFAULT_AUDIO_PROMPT
    } else {
        clean
    }
}

private fun statusLine(message: MessageEntity): String {
    val local = message.localStatus.lowercase()
    val bridge = message.bridgeStatus.lowercase()
    val id = message.messageId.take(12)
    return "$local / $bridge / $id"
}

private fun canRetry(message: MessageEntity): Boolean {
    return message.localStatus == LocalMessageStatus.PENDING.name ||
        message.localStatus == LocalMessageStatus.FAILED.name ||
        message.bridgeStatus == BridgeMessageStatus.FAILED
}

private fun requiredPermissionsForCaptureAction(action: String): List<String> {
    val permissions = mutableListOf<String>()
    when (action) {
        PhoneAgentCaptureService.ACTION_START_BACKGROUND_CAPTURE,
        PhoneAgentCaptureService.ACTION_START_FRAME_STREAM -> {
            permissions += Manifest.permission.CAMERA
        }
        PhoneAgentCaptureService.ACTION_START_WAKE_LISTENER -> {
            permissions += Manifest.permission.RECORD_AUDIO
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && permissions.isNotEmpty()) {
        permissions += Manifest.permission.POST_NOTIFICATIONS
    }
    return permissions
}
