package com.audiobridge.client.wakeword

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Wake Word State Machine
 * 
 * Manages wake word detection lifecycle with proper gating to avoid conflicts:
 * - LISTENING: Actively listening for wake word
 * - COMMAND_WINDOW: Wake word detected, listening for command (6-8 seconds)
 * - COOLDOWN: Command window ended, brief pause before resuming (2-3 seconds)
 * - SUPPRESSED: TTS is playing, wake word detection is suppressed
 * 
 * Design principle: KWS shares the same AudioRecord with STT via PcmDistributionBus.
 */
class WakeWordStateMachine {

    companion object {
        private const val TAG = "WakeWordStateMachine"
        
        // Default timing (in milliseconds)
        const val DEFAULT_COMMAND_WINDOW_MS = 7000L   // 7 seconds
        const val DEFAULT_COOLDOWN_MS = 2000L          // 2 seconds
    }

    /**
     * Wake word state
     */
    enum class State {
        IDLE,           // Not in meeting mode, KWS disabled
        LISTENING,      // Actively listening for wake word
        COMMAND_WINDOW, // Wake word triggered, listening for command
        COOLDOWN,       // Brief pause before resuming
        SUPPRESSED      // TTS playing, detection suppressed
    }

    // Current state
    private val currentState = AtomicReference(State.IDLE)
    
    // Timing configuration
    var commandWindowMs: Long = DEFAULT_COMMAND_WINDOW_MS
    var cooldownMs: Long = DEFAULT_COOLDOWN_MS

    // Handler for timeouts
    private val handler = Handler(Looper.getMainLooper())
    
    // State change callbacks
    var onStateChanged: ((oldState: State, newState: State) -> Unit)? = null
    var onWakeWordDetected: (() -> Unit)? = null
    var onCommandWindowStarted: (() -> Unit)? = null
    var onCommandWindowEnded: (() -> Unit)? = null
    var onCooldownStarted: (() -> Unit)? = null
    var onCooldownEnded: (() -> Unit)? = null

    // Timeout runnables
    private var commandWindowTimeout: Runnable? = null
    private var cooldownTimeout: Runnable? = null

    val state: State get() = currentState.get()
    val isListening: Boolean get() = state == State.LISTENING
    val isInCommandWindow: Boolean get() = state == State.COMMAND_WINDOW
    val isSuppressed: Boolean get() = state == State.SUPPRESSED

    /**
     * Enable wake word detection (enter meeting mode)
     */
    fun enable() {
        if (currentState.get() == State.IDLE) {
            transitionTo(State.LISTENING)
            Log.i(TAG, "Wake word detection enabled")
        }
    }

    /**
     * Disable wake word detection (exit meeting mode)
     */
    fun disable() {
        cancelAllTimeouts()
        transitionTo(State.IDLE)
        Log.i(TAG, "Wake word detection disabled")
    }

    /**
     * Called when wake word is detected
     * Should only be called from LISTENING state
     */
    fun onWakeWordTriggered() {
        if (currentState.get() != State.LISTENING) {
            Log.w(TAG, "Wake word triggered but not in LISTENING state: ${currentState.get()}")
            return
        }

        Log.i(TAG, "Wake word detected, entering command window")
        onWakeWordDetected?.invoke()
        enterCommandWindow()
    }

    /**
     * Enter command window state
     */
    private fun enterCommandWindow() {
        transitionTo(State.COMMAND_WINDOW)
        onCommandWindowStarted?.invoke()

        // Set up command window timeout
        commandWindowTimeout = Runnable {
            Log.i(TAG, "Command window timeout")
            enterCooldown()
        }
        handler.postDelayed(commandWindowTimeout!!, commandWindowMs)
    }

    /**
     * Exit command window and enter cooldown
     */
    fun exitCommandWindow() {
        if (currentState.get() != State.COMMAND_WINDOW) return
        cancelCommandWindowTimeout()
        enterCooldown()
    }

    /**
     * Enter cooldown state
     */
    private fun enterCooldown() {
        transitionTo(State.COOLDOWN)
        onCooldownStarted?.invoke()

        cooldownTimeout = Runnable {
            Log.i(TAG, "Cooldown ended, resuming listening")
            transitionTo(State.LISTENING)
            onCooldownEnded?.invoke()
        }
        handler.postDelayed(cooldownTimeout!!, cooldownMs)
    }

    /**
     * Suppress wake word detection (e.g., during TTS playback)
     */
    fun suppress() {
        val previousState = currentState.get()
        if (previousState == State.IDLE) return
        
        cancelAllTimeouts()
        transitionTo(State.SUPPRESSED)
        Log.i(TAG, "Wake word detection suppressed (was: $previousState)")
    }

    /**
     * Resume wake word detection after suppression
     */
    fun resume() {
        if (currentState.get() != State.SUPPRESSED) return
        
        Log.i(TAG, "Resuming wake word detection")
        transitionTo(State.LISTENING)
    }

    /**
     * Manually trigger command window end (user finished speaking)
     */
    fun endCommand() {
        if (currentState.get() == State.COMMAND_WINDOW) {
            exitCommandWindow()
        }
    }

    private fun transitionTo(newState: State) {
        val oldState = currentState.getAndSet(newState)
        if (oldState != newState) {
            Log.d(TAG, "State transition: $oldState -> $newState")
            onStateChanged?.invoke(oldState, newState)
        }
    }

    private fun cancelCommandWindowTimeout() {
        commandWindowTimeout?.let {
            handler.removeCallbacks(it)
            commandWindowTimeout = null
        }
    }

    private fun cancelCooldownTimeout() {
        cooldownTimeout?.let {
            handler.removeCallbacks(it)
            cooldownTimeout = null
        }
    }

    private fun cancelAllTimeouts() {
        cancelCommandWindowTimeout()
        cancelCooldownTimeout()
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        cancelAllTimeouts()
        transitionTo(State.IDLE)
    }

    /**
     * Get state description for UI display
     */
    fun getStateDescription(): String {
        return when (state) {
            State.IDLE -> "未启用"
            State.LISTENING -> "监听中"
            State.COMMAND_WINDOW -> "等待指令"
            State.COOLDOWN -> "冷却中"
            State.SUPPRESSED -> "播报中"
        }
    }
}


/**
 * Wake Word Controller
 * 
 * Coordinates KWS state machine with TTS suppression and meeting mode.
 */
class WakeWordController(
    private val stateMachine: WakeWordStateMachine
) {
    private var meetingModeEnabled = false
    private var ttsPlaying = false

    /**
     * Called when meeting mode is toggled
     */
    fun onMeetingModeChanged(enabled: Boolean) {
        meetingModeEnabled = enabled
        if (enabled) {
            stateMachine.enable()
        } else {
            stateMachine.disable()
        }
    }

    /**
     * Called when TTS starts playing
     */
    fun onTtsStarted() {
        ttsPlaying = true
        if (meetingModeEnabled) {
            stateMachine.suppress()
        }
    }

    /**
     * Called when TTS stops playing
     */
    fun onTtsEnded() {
        ttsPlaying = false
        if (meetingModeEnabled) {
            stateMachine.resume()
        }
    }

    /**
     * Called when wake word is detected by the detector
     */
    fun onWakeWordDetected() {
        if (meetingModeEnabled && stateMachine.isListening) {
            stateMachine.onWakeWordTriggered()
        }
    }

    val currentState: WakeWordStateMachine.State get() = stateMachine.state
    val isMeetingModeEnabled: Boolean get() = meetingModeEnabled
}


/**
 * Wakeword Event Reporter
 * 
 * M2: Reports wakeword events to the server for tracking and history.
 */
class WakewordEventReporter(
    baseUrl: String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "WakewordEventReporter"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    @Volatile
    private var baseUrl: String = baseUrl.trimEnd('/')

    fun setBaseUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
        if (normalized.isBlank()) return
        baseUrl = normalized
        Log.i(TAG, "Wakeword reporter base URL updated: $baseUrl")
    }

    /**
     * Report a wakeword detection event
     */
    fun reportWakeWordDetected(
        meetingId: String,
        confidence: Float = 1.0f,
        keyword: String = "default"
    ) {
        reportEvent(
            meetingId = meetingId,
            eventType = "wakeword.detected",
            payload = JSONObject().apply {
                put("confidence", confidence)
                put("keyword", keyword)
            }
        )
    }

    /**
     * Report command window start
     */
    fun reportCommandWindowStarted(meetingId: String) {
        reportEvent(
            meetingId = meetingId,
            eventType = "wakeword.command_window.started",
            payload = JSONObject()
        )
    }

    /**
     * Report command window end
     */
    fun reportCommandWindowEnded(meetingId: String, commandCaptured: Boolean = false) {
        reportEvent(
            meetingId = meetingId,
            eventType = "wakeword.command_window.ended",
            payload = JSONObject().apply {
                put("command_captured", commandCaptured)
            }
        )
    }

    /**
     * Report cooldown start
     */
    fun reportCooldownStarted(meetingId: String) {
        reportEvent(
            meetingId = meetingId,
            eventType = "wakeword.cooldown.started",
            payload = JSONObject()
        )
    }

    /**
     * Report cooldown end
     */
    fun reportCooldownEnded(meetingId: String) {
        reportEvent(
            meetingId = meetingId,
            eventType = "wakeword.cooldown.ended",
            payload = JSONObject()
        )
    }

    private fun reportEvent(
        meetingId: String,
        eventType: String,
        payload: JSONObject
    ) {
        Thread {
            try {
                val eventObj = JSONObject().apply {
                    put("event_type", eventType)
                    put("source", "android")
                    put("ts_client", System.currentTimeMillis())
                    put("payload", payload)
                }
                
                val body = JSONObject().apply {
                    put("events", org.json.JSONArray().put(eventObj))
                }
                
                val request = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/v2/meetings/$meetingId/events:batch")
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Event reported: $eventType")
                    } else {
                        Log.w(TAG, "Failed to report event: $eventType, code=${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reporting event: $eventType", e)
            }
        }.start()
    }
}
