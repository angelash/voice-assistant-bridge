package com.audiobridge.client.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Captures 48kHz mono PCM frames from a single AudioRecord instance.
 *
 * We prefer the voice communication source so the platform can apply built-in
 * echo handling. When available, AEC/NS effects are also attached directly to
 * the recorder session.
 */
class AudioRecordCapture {

    companion object {
        private const val TAG = "AudioRecordCapture"
    }

    private var audioRecord: AudioRecord? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private val isCapturing = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var tuningMode: AudioTuningMode = AudioTuningMode.ROBUST

    var onFrameAvailable: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val isRunning: Boolean
        get() = isCapturing.get()

    fun setTuningMode(mode: AudioTuningMode) {
        tuningMode = mode
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isCapturing.get()) {
            Log.w(TAG, "Already capturing")
            return true
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            onError?.invoke("Unable to determine a valid audio buffer size.")
            return false
        }

        val multiplier = if (tuningMode == AudioTuningMode.ROBUST) 6 else 4
        val actualBufferSize = maxOf(bufferSize, AudioConfig.BYTES_PER_FRAME * multiplier)

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AudioConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                actualBufferSize,
            )

            val record = audioRecord
            if (record?.state != AudioRecord.STATE_INITIALIZED) {
                onError?.invoke("AudioRecord initialization failed.")
                cleanupRecorder()
                return false
            }

            attachAudioEffects(record)
            record.startRecording()
            isCapturing.set(true)

            captureThread = thread(name = "AudioRecordCapture") {
                captureLoop()
            }

            Log.i(TAG, "Capture started, bufferSize=$actualBufferSize")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            onError?.invoke("Failed to start capture: ${e.message ?: "unknown error"}")
            cleanupRecorder()
            false
        }
    }

    fun stop() {
        if (!isCapturing.get()) return

        isCapturing.set(false)
        captureThread?.interrupt()
        captureThread = null

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Stopping AudioRecord failed", e)
        }

        cleanupRecorder()
        Log.i(TAG, "Capture stopped")
    }

    private fun captureLoop() {
        if (tuningMode == AudioTuningMode.ROBUST) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (_: Exception) {
                // ignore
            }
        }

        val frameBuffer = ByteArray(AudioConfig.BYTES_PER_FRAME)
        var offset = 0

        while (isCapturing.get()) {
            try {
                val record = audioRecord ?: break
                val bytesRead = record.read(frameBuffer, offset, frameBuffer.size - offset)

                when {
                    bytesRead > 0 -> {
                        offset += bytesRead
                        if (offset >= AudioConfig.BYTES_PER_FRAME) {
                            onFrameAvailable?.invoke(frameBuffer.copyOf())
                            offset = 0
                        }
                    }
                    bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                        Log.e(TAG, "AudioRecord invalid operation")
                        onError?.invoke("Audio capture entered an invalid state.")
                        break
                    }
                    bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                        Log.e(TAG, "AudioRecord bad value")
                        onError?.invoke("Audio capture received invalid parameters.")
                        break
                    }
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "Capture thread interrupted")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Capture loop failed", e)
                onError?.invoke("Audio capture failed: ${e.message ?: "unknown error"}")
                break
            }
        }

        Log.i(TAG, "Capture loop ended")
    }

    private fun attachAudioEffects(record: AudioRecord) {
        releaseAudioEffects()
        val sessionId = record.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            acousticEchoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                enabled = true
            }
            Log.i(TAG, "AcousticEchoCanceler enabled=${acousticEchoCanceler?.enabled == true}")
        } else {
            Log.i(TAG, "AcousticEchoCanceler unavailable")
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                enabled = true
            }
            Log.i(TAG, "NoiseSuppressor enabled=${noiseSuppressor?.enabled == true}")
        } else {
            Log.i(TAG, "NoiseSuppressor unavailable")
        }
    }

    private fun cleanupRecorder() {
        releaseAudioEffects()
        try {
            audioRecord?.release()
        } catch (_: Exception) {
            // ignore
        }
        audioRecord = null
    }

    private fun releaseAudioEffects() {
        try {
            acousticEchoCanceler?.release()
        } catch (_: Exception) {
            // ignore
        }
        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
            // ignore
        }
        acousticEchoCanceler = null
        noiseSuppressor = null
    }
}
