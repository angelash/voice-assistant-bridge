package com.audiobridge.client.audio

import android.util.Log
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PCM Distribution Bus
 * 
 * Distributes PCM audio data to multiple consumers from a single AudioRecord source.
 * This ensures KWS, STT, and disk recording all share the same microphone input.
 * 
 * Design principle: Only ONE AudioRecord instance is allowed at any time.
 */
class PcmDistributionBus {

    companion object {
        private const val TAG = "PcmDistributionBus"
    }

    /**
     * Consumer interface for PCM data
     */
    interface PcmConsumer {
        val name: String
        val enabled: Boolean
        fun onPcmData(data: ByteArray)
        fun flush() {}
    }

    private val consumers = CopyOnWriteArrayList<PcmConsumer>()
    private val isDistributing = AtomicBoolean(false)
    
    // Single audio record reference (externally managed)
    @Volatile private var activeCapture: AudioRecordCapture? = null

    val isRunning: Boolean get() = isDistributing.get()
    val consumerCount: Int get() = consumers.size

    /**
     * Register a PCM consumer
     */
    fun registerConsumer(consumer: PcmConsumer) {
        if (!consumers.contains(consumer)) {
            consumers.add(consumer)
            Log.i(TAG, "Registered consumer: ${consumer.name}, total: ${consumers.size}")
        }
    }

    /**
     * Unregister a PCM consumer
     */
    fun unregisterConsumer(consumer: PcmConsumer) {
        consumers.remove(consumer)
        Log.i(TAG, "Unregistered consumer: ${consumer.name}, remaining: ${consumers.size}")
    }

    /**
     * Start distribution from an AudioRecordCapture
     * Returns false if distribution is already active
     */
    fun startDistribution(capture: AudioRecordCapture): Boolean {
        if (isDistributing.getAndSet(true)) {
            Log.w(TAG, "Distribution already active")
            return false
        }

        activeCapture = capture
        
        // Set up the frame callback
        capture.onFrameAvailable = { data ->
            distributeToConsumers(data)
        }

        Log.i(TAG, "Started PCM distribution")
        return true
    }

    /**
     * Stop distribution
     */
    fun stopDistribution() {
        activeCapture?.onFrameAvailable = null
        activeCapture = null
        isDistributing.set(false)

        // Flush all consumers
        consumers.forEach { it.flush() }

        Log.i(TAG, "Stopped PCM distribution")
    }

    private fun distributeToConsumers(data: ByteArray) {
        consumers.forEach { consumer ->
            if (consumer.enabled) {
                try {
                    consumer.onPcmData(data)
                } catch (e: Exception) {
                    Log.e(TAG, "Consumer ${consumer.name} error: ${e.message}")
                }
            }
        }
    }

    /**
     * Check if any consumer is currently active (needs audio)
     */
    fun hasActiveConsumer(): Boolean {
        return consumers.any { it.enabled }
    }

    /**
     * Get names of active consumers
     */
    fun getActiveConsumerNames(): List<String> {
        return consumers.filter { it.enabled }.map { it.name }
    }
}


/**
 * Disk Writer Consumer
 * Writes PCM data to disk via MeetingManager
 */
class DiskWriterConsumer(
    private val meetingManager: com.audiobridge.client.meeting.MeetingManager
) : PcmDistributionBus.PcmConsumer {

    override val name: String = "disk-writer"
    override var enabled: Boolean = false
    private var bytesWritten = 0L

    override fun onPcmData(data: ByteArray) {
        if (!enabled) return
        meetingManager.writePcmData(data)
        bytesWritten += data.size
    }

    override fun flush() {
        bytesWritten = 0
    }

    fun getBytesWritten(): Long = bytesWritten
}


/**
 * STT Forwarder Consumer
 * Forwards PCM data to the STT engine (iFlytek ASR)
 * 
 * Handles resampling from 48kHz (AudioConfig.SAMPLE_RATE) to 16kHz (STT expected rate).
 * Buffers data to ensure clean resampling across frame boundaries.
 */
class SttForwarderConsumer(
    private val onPcmCallback: (ByteArray) -> Unit,
    private val sourceSampleRate: Int = AudioConfig.SAMPLE_RATE,
    private val targetSampleRate: Int = 16000
) : PcmDistributionBus.PcmConsumer {

    companion object {
        private const val TAG = "SttForwarderConsumer"
        // Buffer size: accumulate enough data for clean resampling (60ms at 48kHz = 2880 samples = 5760 bytes)
        private const val BUFFER_SIZE_BYTES = 5760
    }

    override val name: String = "stt-forwarder"
    override var enabled: Boolean = false

    // Accumulation buffer for resampling
    private val buffer = java.io.ByteArrayOutputStream()
    private var bytesAccumulated = 0

    override fun onPcmData(data: ByteArray) {
        if (!enabled) return

        // If no resampling needed, pass through directly
        if (sourceSampleRate == targetSampleRate) {
            onPcmCallback(data)
            return
        }

        // Accumulate data for clean resampling
        synchronized(buffer) {
            buffer.write(data)
            bytesAccumulated += data.size

            // When we have enough data, resample and forward
            if (bytesAccumulated >= BUFFER_SIZE_BYTES) {
                val rawData = buffer.toByteArray()
                buffer.reset()
                bytesAccumulated = 0

                // Resample to target rate
                val resampled = PcmResampler.downsample(rawData, sourceSampleRate, targetSampleRate)
                Log.d(TAG, "Resampled ${rawData.size} bytes (${rawData.size / 2} samples) @ ${sourceSampleRate}Hz " +
                        "-> ${resampled.size} bytes @ ${targetSampleRate}Hz")
                onPcmCallback(resampled)
            }
        }
    }

    override fun flush() {
        synchronized(buffer) {
            if (bytesAccumulated > 0) {
                val rawData = buffer.toByteArray()
                buffer.reset()
                bytesAccumulated = 0

                if (sourceSampleRate != targetSampleRate) {
                    val resampled = PcmResampler.downsample(rawData, sourceSampleRate, targetSampleRate)
                    if (resampled.isNotEmpty()) {
                        onPcmCallback(resampled)
                    }
                } else {
                    onPcmCallback(rawData)
                }
            }
        }
    }
}


/**
 * KWS Detector Consumer
 * Receives PCM data for wake word detection
 */
class KwsDetectorConsumer : PcmDistributionBus.PcmConsumer {

    companion object {
        private const val TAG = "KwsDetectorConsumer"
        private const val SAMPLE_RATE = AudioConfig.SAMPLE_RATE
        private const val ENERGY_THRESHOLD = 1200.0
        private const val BURST_END_FACTOR = 0.6
        private const val MIN_BURST_MS = 120L
        private const val MAX_BURST_MS = 900L
        private const val MAX_GAP_BETWEEN_BURSTS_MS = 1200L
        private const val DETECTION_COOLDOWN_MS = 4000L
    }

    override val name: String = "kws-detector"
    override var enabled: Boolean = false
    
    // KWS callback when wake word is detected
    var onWakeWordDetected: (() -> Unit)? = null

    // Simple 2-burst detector state (minimal viable local KWS chain)
    private var inBurst = false
    private var burstStartAtMs = 0L
    private var burstCount = 0
    private var lastBurstEndAtMs = 0L
    private var lastDetectedAtMs = 0L

    override fun onPcmData(data: ByteArray) {
        if (!enabled) return
        detectWakeWord(data)
    }

    private fun detectWakeWord(data: ByteArray) {
        if (data.size < 2) return

        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastDetectedAtMs < DETECTION_COOLDOWN_MS) {
            return
        }

        val rms = calculateRms(data)
        val isVoiced = rms >= ENERGY_THRESHOLD
        val isBurstEnd = rms < ENERGY_THRESHOLD * BURST_END_FACTOR

        if (!inBurst && isVoiced) {
            inBurst = true
            burstStartAtMs = nowMs
            return
        }

        if (inBurst && isBurstEnd) {
            inBurst = false
            val burstDuration = nowMs - burstStartAtMs
            if (burstDuration in MIN_BURST_MS..MAX_BURST_MS) {
                if (nowMs - lastBurstEndAtMs > MAX_GAP_BETWEEN_BURSTS_MS) {
                    burstCount = 0
                }
                burstCount += 1
                lastBurstEndAtMs = nowMs

                // Two short bursts in a short window => wakeword-like trigger
                if (burstCount >= 2) {
                    burstCount = 0
                    lastDetectedAtMs = nowMs
                    Log.i(TAG, "Wake trigger detected by local 2-burst heuristic")
                    onWakeWordDetected?.invoke()
                }
            } else if (burstDuration > MAX_BURST_MS) {
                burstCount = 0
                lastBurstEndAtMs = nowMs
            }
        }
    }

    private fun calculateRms(data: ByteArray): Double {
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < data.size) {
            val low = data[i].toInt() and 0xFF
            val high = data[i + 1].toInt()
            val sample = (high shl 8) or low
            sum += (sample * sample).toDouble()
            samples++
            i += 2
        }
        if (samples == 0) return 0.0
        return kotlin.math.sqrt(sum / samples)
    }

    override fun flush() {
        inBurst = false
        burstStartAtMs = 0L
        burstCount = 0
        lastBurstEndAtMs = 0L
    }
}
